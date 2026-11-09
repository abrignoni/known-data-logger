package com.abrignoni.knowndatalogger

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/**
 * Records taps, typed text and screen changes in the apps under test while a capture session is
 * on, shows the floating mark button, and takes screenshots. Never reads any app's stored data.
 */
class CaptureService : AccessibilityService() {

    private lateinit var repo: Repo
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val text = TextBuffer()
    private var rules: List<Rule> = emptyList()
    private var mark: View? = null
    private var lastScreen: Pair<String, String>? = null
    /** Last app seen in the foreground, ignoring System UI (status bar and shade events come between app and mark). */
    private var lastApp: String? = null

    override fun onServiceConnected() {
        repo = Repo.get(this)
        rules = repo.loadRules()
        repo.log(Draft(action = "capture_service_connected", source = Source.SYSTEM), kind = Kind.SYSTEM_EVENT)
        instance = this
        syncSession()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        flushText()
        repo.log(Draft(action = "capture_service_disconnected", source = Source.SYSTEM), kind = Kind.SYSTEM_EVENT)
        removeMark()
        stopPackageWatch()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    // Settings are read once per session start, not on every keystroke.
    private var active = false
    private var allApps = false
    private var shotAuto = true
    private var shotTap = false
    private var pkgs: Set<String> = emptySet()
    /** Fields seen as password fields this session; they stay withheld even if shown as plain text. */
    private val secretKeys = HashSet<String>()
    /**
     * Recent notifications (key to elapsed ms), so a re-post of the same notification within 60 s is
     * logged once. The key includes the notification's own post time (Notification.when): two
     * messages whose shown text is identical (for example "New message" when content is hidden)
     * are still two notifications.
     */
    private val seenNotifications = LinkedHashMap<String, Long>()
    private var maskCodes = true
    /** Version of each captured app when the session started or last changed. */
    private val versions = HashMap<String, String?>()
    private var packageReceiver: android.content.BroadcastReceiver? = null

    /** Called when the session is started or stopped from the app; re-reads settings, apps and rules. */
    fun syncSession() {
        active = repo.flag(Repo.KEY_SESSION, false)
        allApps = repo.flag(Repo.KEY_ALL_APPS, false)
        shotAuto = repo.flag(Repo.KEY_SHOT_AUTO, true)
        shotTap = repo.flag(Repo.KEY_SHOT_TAP, false)
        pkgs = repo.capturePackages()
        rules = repo.loadRules()
        secretKeys.clear()
        seenNotifications.clear()
        maskCodes = repo.flag(Repo.KEY_MASK_CODES, true)
        if (active) { showMark(); startPackageWatch() } else { flushText(); removeMark(); stopPackageWatch() }
    }

    fun reloadRules() { rules = repo.loadRules() }

    // ---- App changes during a session ----

    /** Records versions at session start and logs any app under test that changed since it was added. */
    private fun startPackageWatch() {
        versions.clear()
        val added = repo.db.apps().associate { it.pkg to it.versionName }
        for (p in pkgs) {
            val now = repo.appVersion(p)
            versions[p] = now
            if (p !in added) continue
            // Compared with the last version logged, so a change is reported once, not at every reconnect.
            val known = repo.knownVersion(p) ?: added[p]
            if (known != now) {
                repo.log(Draft(appPackage = p, action = "app_version_changed_since_last_seen", source = Source.SYSTEM,
                    detail = "version ${known ?: "unknown"} last seen, ${now ?: "not installed"} now"), kind = Kind.SYSTEM_EVENT)
            }
            repo.setKnownVersion(p, now)
        }
        if (packageReceiver != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) = onPackageChange(i)
        }
        val f = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
            addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
            addAction(android.content.Intent.ACTION_PACKAGE_REMOVED)
            addAction(android.content.Intent.ACTION_PACKAGE_DATA_CLEARED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, android.content.Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(r, f)
        packageReceiver = r
    }

    private fun stopPackageWatch() {
        packageReceiver?.let { runCatching { unregisterReceiver(it) } }
        packageReceiver = null
    }

    private fun onPackageChange(i: android.content.Intent) {
        val p = i.data?.schemeSpecificPart ?: return
        if (!active || !wanted(p)) return
        val replacing = i.getBooleanExtra(android.content.Intent.EXTRA_REPLACING, false)
        val old = versions[p]
        val now = repo.appVersion(p)
        val action = when (i.action) {
            android.content.Intent.ACTION_PACKAGE_REPLACED -> "app_updated"
            android.content.Intent.ACTION_PACKAGE_ADDED -> if (replacing) return else "app_installed"
            android.content.Intent.ACTION_PACKAGE_REMOVED -> if (replacing) return else "app_removed"
            android.content.Intent.ACTION_PACKAGE_DATA_CLEARED -> "app_data_cleared"
            else -> return
        }
        versions[p] = now
        repo.setKnownVersion(p, now)
        repo.log(Draft(appPackage = p, action = action, source = Source.SYSTEM,
            detail = "version ${old ?: "none"} to ${now ?: "none"}"), kind = Kind.SYSTEM_EVENT)
    }

    private fun wanted(pkg: String): Boolean = pkg != packageName && (allApps || pkg in pkgs)

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::repo.isInitialized || !active) return
        val pkg = event.packageName?.toString() ?: return
        if (!wanted(pkg)) return
        // Incoming notifications are recorded even while locked: that is when messages usually arrive.
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) { onNotification(event, pkg); return }
        // Nothing else is recorded while the phone is locked: the lock screen is where PINs are typed.
        if (getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> onText(event, pkg)
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> flushText()
            AccessibilityEvent.TYPE_VIEW_CLICKED -> onTap(event, pkg)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onScreen(event, pkg)
        }
    }

    /** Password by flag or by input type, including "show password" and number passwords. */
    private fun isSecret(event: AccessibilityEvent?, node: AccessibilityNodeInfo?): Boolean {
        if (event?.isPassword == true || node?.isPassword == true) return true
        val t = node?.inputType ?: return false
        val cls = t and android.text.InputType.TYPE_MASK_CLASS
        val variation = t and android.text.InputType.TYPE_MASK_VARIATION
        return (cls == android.text.InputType.TYPE_CLASS_TEXT && variation in setOf(
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == android.text.InputType.TYPE_CLASS_NUMBER && variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private fun onText(event: AccessibilityEvent, pkg: String) {
        val src = event.source
        val viewId = src?.viewIdResourceName
        val stableKey = "$pkg|${viewId ?: event.className}"
        if (stableKey in secretKeys || isSecret(event, src)) {
            secretKeys += stableKey
            text.discard()
            return
        }
        val key = "$stableKey|${src?.hashCode() ?: 0}"
        val before = event.beforeText?.toString()
        if (Rules.isBulkClear(before, event.addedCount, event.removedCount)) {
            // The field went from text to empty in one change: record what was there, then the clear.
            flushText()
            val raw = repo.log(Draft(appPackage = pkg, action = AutoAction.FIELD_CLEARED, content = before,
                detail = viewId?.let { "view $it" }, source = Source.AUTO_EVENT), kind = Kind.AUTO_EVENT).last()
            applyRule(pkg, RuleEvent.FIELD_CLEARED, viewId, null, raw, before)
            return
        }
        // An empty field reports its hint as its text; record it as empty.
        val value = if (src?.isShowingHintText == true) "" else event.text?.joinToString("")
        text.update(pkg, viewId, key, value, false, repo.stamp())?.let { writeTyped(it) }
    }

    private fun flushText() { text.flush()?.let { writeTyped(it) } }

    /** Typed text is stamped at its last keystroke, not when the field was left. */
    private fun writeTyped(t: Typed) {
        val d = Draft(appPackage = t.pkg, action = AutoAction.TYPED_TEXT, content = t.text,
            detail = t.viewId?.let { "view $it" }, source = Source.AUTO_EVENT)
        if (t.stamp != null) repo.log(d, stamp = t.stamp, kind = Kind.AUTO_EVENT, detect = false)
        else repo.log(d, kind = Kind.AUTO_EVENT)
    }

    /** True when the field holding input focus in the active window is a password field. */
    private fun passwordFieldFocused(): Boolean = try {
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        f != null && (isSecret(null, f) || "${f.packageName}|${f.viewIdResourceName ?: f.className}" in secretKeys)
    } catch (e: Exception) { false }

    /** Text, else content description, of the node or its first described children. */
    private fun labelOf(event: AccessibilityEvent, src: AccessibilityNodeInfo?): String? {
        val direct = event.text?.joinToString(" ")?.takeIf { it.isNotBlank() }
            ?: event.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        if (direct != null) return direct
        if (src == null) return null
        src.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        src.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        for (i in 0 until minOf(src.childCount, 6)) {
            val c = src.getChild(i) ?: continue
            (c.contentDescription ?: c.text)?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun onTap(event: AccessibilityEvent, pkg: String) {
        if (passwordFieldFocused()) {
            // A keypad built from buttons can spell a PIN through tap labels: record only that a tap happened.
            flushText()
            repo.log(Draft(appPackage = pkg, action = AutoAction.TAP, detail = "label withheld: a password field had focus",
                source = Source.AUTO_EVENT), kind = Kind.AUTO_EVENT)
            return
        }
        val src = event.source
        val viewId = src?.viewIdResourceName
        val label = masked(labelOf(event, src))
        val typedBefore = text.currentOrLast(pkg)
        flushText()
        val raw = repo.log(Draft(appPackage = pkg, action = AutoAction.TAP, content = label,
            detail = listOfNotNull(viewId?.let { "view $it" }, event.className?.let { "class $it" }).joinToString(", "),
            source = Source.AUTO_EVENT), kind = Kind.AUTO_EVENT).last()
        if (!applyRule(pkg, RuleEvent.TAP, viewId, label, raw, typedBefore) && shotTap) screenshot(raw.entryId)
    }

    /** Writes an auto-labelled action for a matching rule (unreviewed) and screenshots it. */
    private fun applyRule(pkg: String, event: String, viewId: String?, label: String?, raw: Entry, typed: String?): Boolean {
        val rule = Rules.match(rules, pkg, event, viewId, label) ?: return false
        val labelled = repo.log(Draft(appPackage = pkg, action = rule.action,
            content = if (rule.content == "last_typed") typed else null,
            refersTo = raw.entryId, detail = "auto rule ${rule.id}", source = Source.AUTO_RULE, reviewed = false),
            stamp = raw.stamp, detect = false).last()
        if (shotAuto) screenshot(labelled.entryId)
        return true
    }

    /** Masks codes when the option is on; used for every captured text that can show notification content. */
    private fun masked(t: String?): String? = if (maskCodes) NotificationMap.maskCodes(t).first else t

    private fun onScreen(event: AccessibilityEvent, pkg: String) {
        // Only the first text item is the window's title. System UI puts the whole notification shade,
        // every notification's text included, into the later items, so they are never recorded.
        val title = masked(event.text?.firstOrNull { !it.isNullOrBlank() }?.toString()?.take(200))
            ?: event.className?.toString() ?: ""
        val key = pkg to title
        if (key == lastScreen) return
        if (lastScreen?.first != pkg) text.forgetLast()
        lastScreen = key
        if (pkg != SYSTEM_UI) lastApp = pkg
        flushText()
        repo.log(Draft(appPackage = pkg, action = AutoAction.SCREEN, content = title,
            detail = event.className?.let { "class $it" }, source = Source.AUTO_EVENT), kind = Kind.AUTO_EVENT)
    }

    /**
     * A notification posted by a captured app. The raw event keeps the title and text; message, call
     * and missed-call categories also get an auto-labelled action for review.
     */
    private fun onNotification(event: AccessibilityEvent, pkg: String) {
        // Toasts arrive as the same event type; they are not incoming events.
        if (event.className?.toString()?.contains("Toast") == true) return
        val n = event.parcelableData as? android.app.Notification
        if (n == null) {
            // Seen on Android 17 (Pixel 7a): while locked, the event comes without the Notification, only a
            // short text. Keep it, mark it withheld, and leave the label to review (no category to go on).
            val shown = masked(event.text?.joinToString(" ")?.takeIf { it.isNotBlank() })
            val key = "$pkg|withheld|$shown"
            val now = android.os.SystemClock.elapsedRealtime()
            val last = seenNotifications[key]
            if (last != null && now - last < 5_000) return
            seenNotifications.remove(key); seenNotifications[key] = now
            repo.log(Draft(appPackage = pkg, action = AutoAction.NOTIFICATION, content = shown, direction = "in",
                detail = NotificationMap.WITHHELD, source = Source.AUTO_EVENT), kind = Kind.AUTO_EVENT)
            return
        }
        if (n.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        var body = (extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(android.app.Notification.EXTRA_TEXT))?.toString()
            ?: event.text?.joinToString(" ")?.takeIf { it.isNotBlank() }
        val category = n.category
        val callType = if (Build.VERSION.SDK_INT >= 31 && extras.containsKey(android.app.Notification.EXTRA_CALL_TYPE))
            extras.getInt(android.app.Notification.EXTRA_CALL_TYPE) else null
        val now = android.os.SystemClock.elapsedRealtime()
        val key = "$pkg|$title|$body|$category|$callType|${n.`when`}"
        val last = seenNotifications[key]
        if (last != null && now - last < 60_000) return
        seenNotifications.remove(key); seenNotifications[key] = now
        if (seenNotifications.size > 100) seenNotifications.remove(seenNotifications.keys.first())
        val (shown, masked) = if (maskCodes) NotificationMap.maskCodes(body) else body to false
        body = shown
        val detail = "category ${category ?: "none"}" + (callType?.let { ", call type $it" } ?: "") +
            (if (masked) ", 4 to 8 digit code withheld" else "")
        val raw = repo.log(Draft(appPackage = pkg, action = AutoAction.NOTIFICATION, content = body, counterpart = title,
            direction = "in", detail = detail, source = Source.AUTO_EVENT), kind = Kind.AUTO_EVENT).last()
        val action = NotificationMap.actionFor(category, callType) ?: return
        repo.log(Draft(appPackage = pkg, action = action, content = body, counterpart = title, direction = "in",
            refersTo = raw.entryId, detail = "auto: notification category $category", source = Source.AUTO_RULE,
            reviewed = false), stamp = raw.stamp, detect = false)
    }

    /** Mark button tap: stamp the moment, then screenshot it. */
    private fun onMark() {
        flushText()
        // The mark carries the app that was last seen in the foreground, so its label lands on that app.
        val row = repo.log(Draft(appPackage = lastApp ?: lastScreen?.first, action = AutoAction.MARK, source = Source.MARK),
            kind = Kind.AUTO_EVENT).last()
        screenshot(row.entryId)
    }

    // ---- Screenshots ----

    private fun screenshot(entryId: String) {
        // A pending row first, so a capture lost to a stopped service still shows up.
        repo.recordShot(Shot(entryId, null, null, "pending", System.currentTimeMillis()))
        if (Build.VERSION.SDK_INT < 30) {
            repo.recordShot(Shot(entryId, null, null, "unsupported", System.currentTimeMillis()))
            return
        }
        // Hide the mark button so it is not in the frame, then capture.
        mark?.visibility = View.INVISIBLE
        main.postDelayed({ takeNow(entryId) }, 150)
    }

    private fun takeNow(entryId: String) {
        if (Build.VERSION.SDK_INT < 30) return
        takeScreenshot(Display.DEFAULT_DISPLAY, io, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                main.post { mark?.visibility = View.VISIBLE }
                val shot = try {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val bmp = hw!!.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    val blank = isBlank(bmp)
                    val name = "$entryId.png"
                    val f = File(repo.screensDir(), name)
                    val written = f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    if (!written) { f.delete(); error("PNG write failed") }
                    Shot(entryId, name, sha256Hex(f.readBytes()), if (blank) "blank" else "ok", System.currentTimeMillis())
                } catch (e: Exception) {
                    Shot(entryId, null, null, "failed:${e.javaClass.simpleName}", System.currentTimeMillis())
                }
                main.post { repo.recordShot(shot) }
            }

            override fun onFailure(errorCode: Int) {
                main.post {
                    mark?.visibility = View.VISIBLE
                    repo.recordShot(Shot(entryId, null, null, "failed:code_$errorCode", System.currentTimeMillis()))
                }
            }
        })
    }

    /** True when every sampled pixel is black: a protected window or an empty frame. */
    private fun isBlank(b: Bitmap): Boolean {
        val stepX = maxOf(1, b.width / 24); val stepY = maxOf(1, b.height / 48)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val p = b.getPixel(x, y)
                if (Color.red(p) > 8 || Color.green(p) > 8 || Color.blue(p) > 8) return false
                x += stepX
            }
            y += stepY
        }
        return true
    }

    // ---- Floating mark button ----

    private fun showMark() {
        if (mark != null) return
        val wm = getSystemService(WindowManager::class.java)
        val size = (56 * resources.displayMetrics.density).toInt()
        val v = TextView(this).apply {
            text = "MARK"; textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(200, 183, 28, 28)) }
            contentDescription = "Known-data logger mark"
        }
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 0; y = (220 * resources.displayMetrics.density).toInt() }
        // Drag to move; a short touch without movement is a mark.
        var downY = 0f; var startY = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startY = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dy) > 12) { moved = true; lp.y = startY + dy; wm.updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) onMark()
            }
            true
        }
        wm.addView(v, lp)
        mark = v
    }

    private fun removeMark() {
        mark?.let { getSystemService(WindowManager::class.java).removeView(it) }
        mark = null
    }

    companion object {
        const val SYSTEM_UI = "com.android.systemui"

        /** The running service, if enabled, so the app can start or stop a session. */
        @Volatile var instance: CaptureService? = null
    }
}
