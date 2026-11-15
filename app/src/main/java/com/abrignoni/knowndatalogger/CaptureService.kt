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
import android.view.HapticFeedbackConstants
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
    private var kdlInFront: Boolean
        get() = com.abrignoni.knowndatalogger.kdlInFront
        set(v) { com.abrignoni.knowndatalogger.kdlInFront = v }

    /**
     * Called by KDL's own screen on resume and pause (same process), so MARK never covers KDL's
     * buttons. Window events were not reliable for this on a Pixel 7a.
     */
    fun kdlVisibilityChanged(front: Boolean) {
        kdlInFront = front
        mark?.visibility = if (front) View.INVISIBLE else View.VISIBLE
    }
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
        main.removeCallbacks(poll); main.removeCallbacks(scan)
        instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    // Settings are read once per session start, not on every keystroke.
    private var active = false
    private var allApps = false
    private var shotAuto = true
    private var shotTap = false
    private var saveTree = true
    /** View trees taken at a screenshot, waiting for that screenshot's result so both go in one chained row. */
    private val pendingTrees = HashMap<String, TreeFile>()
    private val shotApps = HashMap<String, String?>()
    private var pkgs: Set<String> = emptySet()
    /** Apps on the Setup list, without the always-captured System UI and Settings. */
    private var listed: Set<String> = emptySet()
    /** Fields seen as password fields this session; they stay withheld even if shown as plain text. */
    private val secretKeys = HashSet<String>()
    /**
     * Recent notifications (key to elapsed ms), so a re-post of the same notification within 60 s is
     * logged once. The key includes the notification's own post time (Notification.when): two
     * messages whose shown text is identical (for example "New message" when content is hidden)
     * are still two notifications.
     */
    private val seenNotifications = LinkedHashMap<String, Long>()
    /** Text arriving on a screen that is already open (an incoming message in an open chat). */
    private val chatWatch = ChatWatch()
    /** What this phone sent or was notified of lately (text to elapsed ms), so a line on screen is not taken for a new receive. */
    private val recentOwn = LinkedHashMap<String, Long>()
    private val recentNotified = LinkedHashMap<String, Long>()
    private val ruleLabelled = LinkedHashMap<String, Long>()
    private val scan = Runnable { scanOpenScreen() }
    private var lastScanAt = 0L
    /**
     * A look every few seconds while a session runs, besides the look after each burst of content changes.
     * Some app builds send no content-change event when a message arrives: Google Messages
     * (messages.android_20250422_03_RC00, Galaxy A16 5G, 15 November 2026) sent none from the window
     * opening until the screen was touched, so a message received while the phone sat idle was never seen.
     * Without events Android's node cache is never told the screen changed, so it is cleared first.
     */
    private val poll = object : Runnable {
        override fun run() {
            if (!active) return
            if (android.os.SystemClock.uptimeMillis() - lastScanAt >= POLL_MS) {
                if (android.os.Build.VERSION.SDK_INT >= 34) clearCache()
                scanOpenScreen()
            }
            main.postDelayed(this, POLL_MS)
        }
    }
    /** For the session's summary row: how often the open screen was looked at, started again, and gave new lines. */
    private var watchLooks = 0
    private var watchBaselines = 0
    private var watchFound = 0
    private var watchLastKey: Any? = null
    private var maskCodes = true
    /** Version of each captured app when the session started or last changed. */
    private val versions = HashMap<String, String?>()
    private var packageReceiver: android.content.BroadcastReceiver? = null

    /** Called when the session is started or stopped from the app; re-reads settings, apps and rules. */
    fun syncSession() {
        active = repo.flag(Repo.KEY_SESSION, false)
        // Capture is on now, so a session that began as hand logging is a capture session from here:
        // if the service is lost later, the Session tab warns as it does for any other.
        if (active && repo.handOnly) repo.setFlag(Repo.KEY_HAND_ONLY, false)
        allApps = repo.flag(Repo.KEY_ALL_APPS, false)
        shotAuto = repo.flag(Repo.KEY_SHOT_AUTO, true)
        shotTap = repo.flag(Repo.KEY_SHOT_TAP, false)
        saveTree = repo.flag(Repo.KEY_TREE, true)
        pkgs = repo.capturePackages()
        listed = repo.db.apps().map { it.pkg }.toSet()
        // Forgotten when the session ends, not when the service reconnects in the middle of one.
        if (!active) lastTestApp = null
        rules = repo.loadRules()
        secretKeys.clear()
        seenNotifications.clear()
        if (!active && watchLooks > 0) repo.log(Draft(action = "screen_watch_summary", source = Source.SYSTEM,
            detail = "looks $watchLooks, baselines $watchBaselines, new lines $watchFound"), kind = Kind.SYSTEM_EVENT)
        chatWatch.reset(); recentOwn.clear(); recentNotified.clear(); ruleLabelled.clear()
        watchLooks = 0; watchBaselines = 0; watchFound = 0; watchLastKey = null
        maskCodes = repo.flag(Repo.KEY_MASK_CODES, true)
        main.removeCallbacks(poll)
        if (active) main.postDelayed(poll, POLL_MS)
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
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> onSelection(event, pkg)
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> flushText()
            AccessibilityEvent.TYPE_VIEW_CLICKED -> onTap(event, pkg)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onScreen(event, pkg)
            // Content changes come in bursts; one look after they settle.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (pkg in listed) { main.removeCallbacks(scan); main.postDelayed(scan, 600) }
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
            remember(recentOwn, before)
            val raw = repo.log(Draft(appPackage = pkg, action = AutoAction.FIELD_CLEARED, content = before,
                detail = viewId?.let { "view $it" }, source = Source.AUTO_EVENT), kind = Kind.AUTO_EVENT).last()
            applyRule(pkg, RuleEvent.FIELD_CLEARED, viewId, null, raw, before)
            return
        }
        // An empty field reports its hint as its text; record it as empty.
        val value = if (src?.isShowingHintText == true) "" else event.text?.joinToString("")
        text.update(pkg, viewId, key, value, false, repo.stamp())?.let { writeTyped(it) }
    }

    /**
     * A send in a view-based app often replaces the field's text in one call. Android reports that
     * as a selection change with no text change, so the clear is read from the field itself.
     */
    private fun onSelection(event: AccessibilityEvent, pkg: String) {
        if (!text.hasPending()) return
        val src = event.source ?: return
        val viewId = src.viewIdResourceName
        val stableKey = "$pkg|${viewId ?: event.className}"
        if (stableKey in secretKeys || isSecret(event, src)) return
        val empty = src.isShowingHintText || src.text.isNullOrEmpty()
        val typed = text.clearedWithoutChange("$stableKey|${src.hashCode()}", empty) ?: return
        writeTyped(typed)
        remember(recentOwn, typed.text)
        val raw = repo.log(Draft(appPackage = pkg, action = AutoAction.FIELD_CLEARED, content = typed.text,
            detail = listOfNotNull(viewId?.let { "view $it" }, "no text change reported").joinToString(", "),
            source = Source.AUTO_EVENT), kind = Kind.AUTO_EVENT).last()
        applyRule(pkg, RuleEvent.FIELD_CLEARED, viewId, null, raw, typed.text)
    }

    private fun flushText() { text.flush()?.let { writeTyped(it) } }

    /** Typed text is stamped at its last keystroke, not when the field was left. */
    private fun writeTyped(t: Typed) {
        // Typed text is also this phone's own message when an app sends without a field-clear event
        // (Telegram 13.2.2, 15 November 2026: 15 of 15 sends typed, 0 cleared).
        remember(recentOwn, t.text)
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
        val labelled = repo.log(Draft(appPackage = pkg, action = rule.action, direction = directionFor(rule.action),
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
        if (pkg in listed) lastTestApp = pkg
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
        remember(recentNotified, body)
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

    private fun remember(map: LinkedHashMap<String, Long>, t: String?) {
        val s = t?.trim()?.takeIf { it.isNotEmpty() } ?: return
        map.remove(s); map[s] = android.os.SystemClock.elapsedRealtime()
        while (map.size > 50) map.remove(map.keys.first())
    }

    /** True when the line holds a text this phone sent or was notified of in the last 2 minutes. */
    private fun recentlyIn(map: Map<String, Long>, line: String, within: Long = 120_000, exact: Boolean = false): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        return map.any { (t, at) -> now - at < within && (if (exact) line == t else line.contains(t)) }
    }

    /**
     * Looks at the open screen of an app under test and logs text that came onto it (ChatWatch). Each new
     * line is a raw text_appeared event with its side of the screen; a line on the left, not one this phone
     * just sent or was notified of, also gets a received_message label for review. Never while locked,
     * never System UI, never an editable field or a password field.
     */
    private fun scanOpenScreen() {
        lastScanAt = android.os.SystemClock.uptimeMillis()
        if (!active || kdlInFront) return
        if (getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true) return
        val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg == SYSTEM_UI || pkg !in listed || !wanted(pkg)) return
        val win = android.graphics.Rect().also { root.getBoundsInScreen(it) }
        val lines = ArrayList<ShownText>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 60 || lines.size > 400) return
            if (n.isEditable || isSecret(null, n)) return
            // A row that scrolls sideways holds suggestions, not messages: Google Messages' smart replies
            // ("Okay", "Nice") sit in a HorizontalScrollView under each received message, and were taken for
            // received messages (Galaxy A16 5G and moto g play, 15 November 2026: 6 false of 36 labels).
            val cls0 = n.className?.toString() ?: ""
            if (cls0.endsWith("HorizontalScrollView") || cls0.endsWith("ViewPager") || cls0.endsWith("ViewPager2")) return
            // View text only: buttons, avatars and "is typing" carry a description and no text, and they come
            // and go as the examiner types (Messenger, 15 November 2026: 7 of 7 false labels were those).
            val cls = n.className?.toString() ?: ""
            val t = if (cls.endsWith("Button")) null else n.text?.toString()?.trim()
            if (!t.isNullOrEmpty() && n.isVisibleToUser) {
                val r = android.graphics.Rect().also { n.getBoundsInScreen(it) }
                if (r.width() > 0 && r.height() > 0) lines += ShownText(t, r.left, r.top, r.right, r.bottom)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        try { walk(root, 0) } catch (e: Exception) { return }
        val key = lastScreen ?: (pkg to "")
        if (watchLooks == 0) repo.log(Draft(appPackage = pkg, action = "screen_watch_active", source = Source.SYSTEM,
            detail = "${lines.size} lines on the first look"), kind = Kind.SYSTEM_EVENT)
        watchLooks++
        if (key != watchLastKey) { watchBaselines++; watchLastKey = key }
        val found = chatWatch.look(key, lines, win.width())
        watchFound += found.size
        for (a in found) {
            // An app can say in the line itself which way it went: Telegram's message cells span the whole width
            // (side "across") and end with "Received at <time>" or "Sent at <time>". A text_appeared rule names that.
            val rule = Rules.match(rules, pkg, RuleEvent.TEXT_APPEARED, null, a.text)
            // A line holding what this phone just typed is its own message: dropped, unless a rule says it was
            // sent. Telegram reports no field clear, so its "Sent at" line is the only sign of a send (Galaxy
            // A16 5G and moto g play, 15 November 2026: 0 of 15 sends labelled per phone before this).
            if (recentlyIn(recentOwn, a.text) && (rule == null || directionFor(rule.action) != "out")) continue
            val shown = masked(a.text.take(2000))
            val raw = repo.log(Draft(appPackage = pkg, action = AutoAction.TEXT_APPEARED, content = shown,
                detail = "side ${a.side}", source = Source.AUTO_EVENT), kind = Kind.AUTO_EVENT).last()
            if (rule != null) {
                if (directionFor(rule.action) == "in" && recentlyIn(recentNotified, a.text)) continue
                // One label per message, though its line changes in place (Rules.lineKey).
                val k = Rules.lineKey(rule, a.text)
                if (k != null && recentlyIn(ruleLabelled, k, 600_000, exact = true)) continue
                remember(ruleLabelled, k)
                val labelled = repo.log(Draft(appPackage = pkg, action = rule.action, direction = directionFor(rule.action),
                    content = if (rule.content == "line") shown else null, refersTo = raw.entryId,
                    detail = "auto rule ${rule.id}", source = Source.AUTO_RULE, reviewed = false), stamp = raw.stamp, detect = false).last()
                if (shotAuto) screenshot(labelled.entryId)
                continue
            }
            if (a.side != "left" || recentlyIn(recentNotified, a.text)) continue
            val labelled = repo.log(Draft(appPackage = pkg, action = "received_message", content = shown, direction = "in",
                refersTo = raw.entryId, detail = "auto: text appeared on the left of an open screen", source = Source.AUTO_RULE,
                reviewed = false), stamp = raw.stamp, detect = false).last()
            if (shotAuto) screenshot(labelled.entryId)
        }
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

    /**
     * The view tree of the window in front, taken at the screenshot. Only for an app that is being
     * captured, never System UI (its tree holds the whole notification shade) and never while the
     * phone is locked. What was typed in a password field is left out; every other text is kept.
     */
    private fun captureTree(entryId: String): TreeFile {
        val now = System.currentTimeMillis()
        fun none(why: String) = TreeFile(entryId, null, null, "not_captured:$why", now)
        return try {
            if (getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true) return none("phone locked")
            val root = rootInActiveWindow ?: return none("no window")
            val pkg = root.packageName?.toString() ?: return none("no package")
            if (pkg == SYSTEM_UI || !wanted(pkg)) return none("$pkg is not an app under test")
            var count = 0
            fun walk(n: AccessibilityNodeInfo, depth: Int): VNode {
                count++
                val id = n.viewIdResourceName
                val r = android.graphics.Rect().also { n.getBoundsInScreen(it) }
                val secret = isSecret(null, n) || "${n.packageName}|${id ?: n.className}" in secretKeys
                val kids = ArrayList<VNode>()
                if (depth < 80) for (i in 0 until n.childCount) {
                    if (count >= 6000) break
                    val c = try { n.getChild(i) } catch (e: Exception) { null } ?: continue
                    kids += walk(c, depth + 1)
                }
                return VNode(n.className?.toString(), n.packageName?.toString(), id, n.text?.toString(),
                    n.contentDescription?.toString(), "[${r.left},${r.top}][${r.right},${r.bottom}]",
                    n.isClickable, n.isScrollable, secret, n.isChecked, n.isSelected, kids)
            }
            val bytes = ViewTree.xml(walk(root, 0)).toByteArray(Charsets.UTF_8)
            val name = "$entryId.xml"
            File(repo.treesDir(), name).writeBytes(bytes)
            TreeFile(entryId, name, sha256Hex(bytes), if (count >= 6000) "ok_cut_at_6000_nodes" else "ok", now)
        } catch (e: Exception) {
            TreeFile(entryId, null, null, "failed:${e.javaClass.simpleName}", now)
        }
    }

    /** Stores the screenshot and the tree, then one chained row with the hashes of both files. */
    private fun finishCapture(shot: Shot) {
        if (!kdlInFront) mark?.visibility = View.VISIBLE
        val tree = pendingTrees.remove(shot.entryId)
        repo.recordShot(shot)
        tree?.let { repo.recordTree(it) }
        repo.logCaptureFiles(shot.entryId, shotApps.remove(shot.entryId), shot, tree)
    }

    private fun takeNow(entryId: String) {
        if (Build.VERSION.SDK_INT < 30) return
        shotApps[entryId] = lastApp ?: lastScreen?.first
        if (saveTree) pendingTrees[entryId] = captureTree(entryId)
        takeScreenshot(Display.DEFAULT_DISPLAY, io, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
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
                main.post { finishCapture(shot) }
            }

            override fun onFailure(errorCode: Int) {
                main.post { finishCapture(Shot(entryId, null, null, "failed:code_$errorCode", System.currentTimeMillis())) }
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
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (8 * resources.displayMetrics.density).toInt()
            // Starts low on the right, clear of most controls; a dragged position is remembered.
            y = repo.db.setting("mark_y")?.toIntOrNull() ?: (resources.displayMetrics.heightPixels * 0.7).toInt()
        }
        // Drag to move; a short touch without movement is a mark.
        var downY = 0f; var startY = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startY = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dy) > 12) { moved = true; lp.y = startY + dy; wm.updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    // A short buzz says the mark was taken; unlike a banner, it cannot end up in the screenshot.
                    v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
                    onMark()
                } else repo.db.putSetting("mark_y", lp.y.toString())
            }
            true
        }
        v.visibility = if (kdlInFront) View.INVISIBLE else View.VISIBLE
        wm.addView(v, lp)
        mark = v
    }

    private fun removeMark() {
        mark?.let { getSystemService(WindowManager::class.java).removeView(it) }
        mark = null
    }

    companion object {
        const val SYSTEM_UI = "com.android.systemui"
        /** Milliseconds between periodic looks (see poll). */
        const val POLL_MS = 3000L

        /** The running service, if enabled, so the app can start or stop a session. */
        @Volatile var instance: CaptureService? = null

        /** The app under test last seen in front this session; the hand-log sheet starts on it. */
        @Volatile var lastTestApp: String? = null
    }
}
