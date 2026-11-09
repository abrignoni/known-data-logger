package com.abrignoni.knowndatalogger

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** What the Log screen hands over for one entry. */
data class Draft(
    val appPackage: String? = null,
    val action: String = "",
    val content: String? = null,
    val counterpart: String? = null,
    val direction: String? = null,
    val conversationId: String? = null,
    val timeQuality: String = TimeQuality.EXACT,
    val expectedArtifacts: List<String> = emptyList(),
    val detail: String? = null,
    val refersTo: String? = null,
    val source: String = Source.MANUAL,
    val reviewed: Boolean = true,
)

class Repo private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: Repo? = null

        /** One Repo per process, shared by the screens and the capture service. */
        fun get(context: Context): Repo =
            instance ?: synchronized(this) { instance ?: Repo(context.applicationContext).also { instance = it } }

        const val KEY_SESSION = "capture_session_active"
        const val KEY_SHOT_AUTO = "shot_on_auto_action"
        const val KEY_SHOT_TAP = "shot_on_every_tap"
        const val KEY_ALL_APPS = "capture_all_apps"
        const val KEY_MASK_CODES = "mask_notification_codes"
        val DEFAULT_CAPTURE_PACKAGES = setOf("com.android.systemui", "com.android.settings")
    }

    val db = Db(context)

    /** Stamp of the newest forward-time row, so change detection does not re-read the table. */
    private var lastStamp: Stamp? = null
    private var lastStampLoaded = false
    private val resolver get() = context.contentResolver
    private val pm get() = context.packageManager

    var scenarioId: String
        get() = db.setting("scenario_id") ?: ""
        set(v) = db.putSetting("scenario_id", v.trim())
    var deviceLabel: String
        get() = db.setting("device_label") ?: ""
        set(v) = db.putSetting("device_label", v.trim())

    fun stamp(): Stamp = Clocks.stamp(resolver)

    /** Last version of a captured app that the log has accounted for. */
    fun knownVersion(pkg: String): String? = db.setting("known_version:$pkg")
    fun setKnownVersion(pkg: String, v: String?) = db.putSetting("known_version:$pkg", v ?: "")

    /** First-run setup finished. Installs from before it existed count as set up once they have a scenario. */
    var onboarded: Boolean
        get() = db.setting("onboarded") == "1" || scenarioId.isNotBlank()
        set(v) = db.putSetting("onboarded", if (v) "1" else "0")

    // ---- Sessions ----

    val sessionActive: Boolean get() = flag(KEY_SESSION, false)

    /** Stamp of the running session's start, for the timer. */
    fun sessionStart(): Stamp? = loadStamp("session_start")

    fun startSession() {
        val s = stamp()
        setFlag(KEY_SESSION, true)
        saveStamp("session_start", s)
        val allApps = flag(KEY_ALL_APPS, false)
        log(Draft(action = "capture_session_start", source = Source.SYSTEM,
            detail = "apps: " + (if (allApps) "all" else capturePackages().sorted().joinToString(" ")) +
                "; screenshots: auto=${flag(KEY_SHOT_AUTO, true)} every_tap=${flag(KEY_SHOT_TAP, false)}"),
            s, kind = Kind.SYSTEM_EVENT)
    }

    fun stopSession() {
        setFlag(KEY_SESSION, false)
        log(Draft(action = "capture_session_stop", source = Source.SYSTEM), kind = Kind.SYSTEM_EVENT)
    }

    /** The newest session (running or finished) and what it produced. */
    fun lastSession(): SessionSummary? = summarize(db.entries(), db.shots())

    fun pendingCounts(): PendingCounts = PendingCounts.of(Review.pending(db.entries()))

    /** How many of the items to check happened at or after this session's start. */
    fun pendingInSession(start: Stamp): Int = Review.pending(db.entries()).count {
        it.stamp.bootCount > start.bootCount || (it.stamp.bootCount == start.bootCount && it.stamp.elapsedMs >= start.elapsedMs)
    }

    fun flag(key: String, default: Boolean): Boolean = db.setting(key)?.let { it == "1" } ?: default
    fun setFlag(key: String, v: Boolean) = db.putSetting(key, if (v) "1" else "0")

    /** Packages the capture service records: apps under test plus system UI and Settings. */
    fun capturePackages(): Set<String> = db.apps().map { it.pkg }.toSet() + DEFAULT_CAPTURE_PACKAGES

    /** rules.json in the app's external folder: rules pushed with adb and rules taught in the app. */
    fun rulesFile(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "rules.json")

    private fun bundledRules(): List<Rule> = try {
        parseRules(context.assets.open("rules/default.json").bufferedReader().use { it.readText() })
    } catch (e: Exception) { emptyList() }

    fun localRules(): List<Rule> = try {
        rulesFile().takeIf { it.exists() }?.let { parseRules(it.readText()) } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    /**
     * Local rules for rewriting the file. Refuses when the file exists but does not parse cleanly
     * (bad JSON or a rule dropped for a bad regex), so saving never destroys rules pushed by hand.
     */
    private fun localRulesForWrite(): List<Rule> {
        val f = rulesFile()
        if (!f.exists()) return emptyList()
        val text = f.readText()
        val parsed = try { parseRules(text) } catch (e: Exception) { null }
        val declared = try { JSONObject(text).getJSONArray("rules").length() } catch (e: Exception) { -1 }
        if (parsed == null || parsed.size != declared) {
            error("rules.json has a rule this app cannot read; fix or remove it before saving rules")
        }
        return parsed
    }

    /** Bundled rules, overridden rule by rule by the local rules.json. */
    fun loadRules(): List<Rule> = Rules.merge(bundledRules(), localRules())

    /** Rule ids that come from the local file rather than the APK. */
    fun localRuleIds(): Set<String> = localRules().map { it.id }.toSet()

    /** Saves a rule taught in the app into rules.json and logs that it was added. */
    fun saveTaughtRule(rule: Rule) {
        rulesFile().writeText(rulesToJson(Rules.merge(localRulesForWrite(), listOf(rule))))
        log(Draft(appPackage = rule.pkg, action = "rule_taught", source = Source.SYSTEM,
            detail = "${rule.id}: ${rule.event} ${rule.viewId ?: rule.labelRegex} -> ${rule.action}"), kind = Kind.SYSTEM_EVENT)
    }

    fun removeLocalRule(id: String) {
        rulesFile().writeText(rulesToJson(localRulesForWrite().filter { it.id != id }))
        log(Draft(action = "rule_removed", source = Source.SYSTEM, detail = id), kind = Kind.SYSTEM_EVENT)
    }

    fun screensDir(): File = File(context.filesDir, "screens").apply { mkdirs() }

    fun recordShot(shot: Shot) = db.putShot(shot)

    /** Start stamp of an open Start/Done window, kept in the database so a killed app keeps it. */
    fun saveWindowStart(s: Stamp?) = saveStamp("window_start", s)

    fun loadWindowStart(): Stamp? = loadStamp("window_start")

    private fun saveStamp(key: String, s: Stamp?) {
        if (s == null) { db.writableDatabase.delete("settings", "key=?", arrayOf(key)); return }
        db.putSetting(key, JSONObject().apply {
            put("utc_ms", s.utcMs); put("tz_id", s.tzId); put("tz_offset_min", s.tzOffsetMin)
            put("elapsed_ms", s.elapsedMs); put("boot_count", s.bootCount)
            put("network_time_ms", s.networkTimeMs ?: JSONObject.NULL); put("gnss_time_ms", s.gnssTimeMs ?: JSONObject.NULL)
        }.toString())
    }

    private fun loadStamp(key: String): Stamp? = db.setting(key)?.let {
        val o = JSONObject(it)
        Stamp(
            utcMs = o.getLong("utc_ms"), tzId = o.getString("tz_id"), tzOffsetMin = o.getInt("tz_offset_min"),
            elapsedMs = o.getLong("elapsed_ms"), bootCount = o.getInt("boot_count"),
            networkTimeMs = if (o.isNull("network_time_ms")) null else o.getLong("network_time_ms"),
            gnssTimeMs = if (o.isNull("gnss_time_ms")) null else o.getLong("gnss_time_ms"),
        )
    }

    private fun latestProfileId(): Long? = db.profiles().lastOrNull()?.id

    fun appVersion(pkg: String?): String? = pkg?.let {
        try { pm.getPackageInfo(it, 0).versionName } catch (e: PackageManager.NameNotFoundException) { null }
    }

    private fun accountFor(pkg: String?): String? = pkg?.let { p -> db.apps().firstOrNull { it.pkg == p }?.account }

    /** row_hash of the newest row, the link the next row hashes over. */
    private var lastHash: String? = null

    /**
     * Rows written before schema 3 have no hash. They are hashed once, in seq order, and a system
     * row records that their hashes were made at upgrade time, not when they were written.
     */
    @Synchronized
    private fun ensureChain() {
        if (lastHash != null) return
        val rows = db.entries()
        val firstMissing = rows.indexOfFirst { it.rowHash == null }
        if (firstMissing < 0) { lastHash = rows.lastOrNull()?.rowHash ?: Chain.GENESIS; return }
        var prev = if (firstMissing == 0) Chain.GENESIS else rows[firstMissing - 1].rowHash!!
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (r in rows.drop(firstMissing)) {
                val h = Chain.rowHash(r, prev)
                db.setHash(r.seq, prev, h)
                prev = h
            }
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
        lastHash = prev
        val s = stamp()
        write(base(Kind.SYSTEM_EVENT, s).copy(action = "hash_chain_backfilled", source = Source.SYSTEM,
            detail = "rows ${rows[firstMissing].seq} to ${rows.last().seq} were hashed when the app was upgraded, not when they were written"))
    }

    /**
     * Inserts a row with its place in the hash chain, then appends it to the live JSONL file that
     * survives an app crash. Synchronized: the capture service, the screens and the export thread
     * all write, and the chain needs one writer at a time.
     */
    @Synchronized
    private fun write(raw: Entry): Entry {
        ensureChain()
        val e = raw.cleaned()
        val w = db.writableDatabase
        val saved: Entry
        w.beginTransaction()
        try {
            val seq = db.insert(e)
            val prev = lastHash ?: Chain.GENESIS
            val withSeq = e.copy(seq = seq, prevHash = prev)
            val h = Chain.rowHash(withSeq, prev)
            db.setHash(seq, prev, h)
            w.setTransactionSuccessful()
            saved = withSeq.copy(rowHash = h)
        } finally { w.endTransaction() }
        lastHash = saved.rowHash
        try {
            liveFile().appendText(saved.toJson().toString() + "\n")
        } catch (ignored: Exception) {
            // The database row is the record; the live copy is a convenience.
        }
        return saved
    }

    /** The newest row's hash, shown on the Export screen so the tester can note it. */
    fun lastRowHash(): String? { ensureChain(); return lastHash?.ifEmpty { null } }

    fun liveFile(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "actions-live.jsonl")

    /** Logs system rows for detected reboots and clock or zone changes, then returns them. */
    private fun systemRowsFor(stamp: Stamp): List<Entry> {
        if (!lastStampLoaded) {
            lastStamp = db.entries().filter { it.revisionN == 0 }.maxWithOrNull(Timeline.ORDER)?.stamp
            lastStampLoaded = true
        }
        val rows = Timeline.detectChangesFrom(lastStamp, stamp).map { (kind, text) ->
            write(base(kind, stamp).copy(
                action = if (kind == Kind.CLOCK_CHANGE) "clock_change_detected" else "boot_detected",
                detail = text, source = Source.SYSTEM,
            ))
        }
        lastStamp = stamp
        return rows
    }

    private fun base(kind: String, stamp: Stamp) = Entry(
        entryId = UUID.randomUUID().toString(),
        kind = kind,
        scenarioId = scenarioId,
        deviceLabel = deviceLabel,
        profileId = latestProfileId(),
        stamp = stamp,
        editedUtcMs = stamp.utcMs,
    )

    /** Logs an action at the given stamp. Returns any system rows logged first, then the entry. */
    fun log(
        draft: Draft, stamp: Stamp = stamp(), windowS: Double? = null, kind: String = Kind.ACTION,
        detect: Boolean = true,
    ): List<Entry> {
        // Back-dated rows (a Start/Done window, a label for an earlier mark) skip change detection.
        val sys = if (detect) systemRowsFor(stamp) else emptyList()
        val e = base(kind, stamp).copy(
            timeQuality = draft.timeQuality,
            windowS = windowS,
            appPackage = draft.appPackage,
            appVersion = appVersion(draft.appPackage),
            account = accountFor(draft.appPackage),
            action = draft.action,
            content = draft.content?.ifBlank { null },
            counterpart = draft.counterpart?.ifBlank { null },
            direction = draft.direction?.ifBlank { null },
            conversationId = draft.conversationId?.ifBlank { null },
            refersTo = draft.refersTo,
            expectedArtifacts = draft.expectedArtifacts,
            detail = draft.detail?.ifBlank { null },
            source = draft.source,
            reviewed = draft.reviewed,
        )
        return sys + write(e)
    }

    /** Writes a new revision. The action time (stamp) never changes across revisions. */
    fun revise(current: Entry, edited: Draft): Entry {
        val now = stamp()
        return write(
            current.copy(
                seq = 0,
                revisionN = current.revisionN + 1,
                editedUtcMs = now.utcMs,
                appPackage = edited.appPackage,
                appVersion = if (edited.appPackage == current.appPackage) current.appVersion else appVersion(edited.appPackage),
                account = if (edited.appPackage == current.appPackage) current.account else accountFor(edited.appPackage),
                action = edited.action,
                content = edited.content?.ifBlank { null },
                counterpart = edited.counterpart?.ifBlank { null },
                direction = edited.direction?.ifBlank { null },
                conversationId = edited.conversationId?.ifBlank { null },
                timeQuality = edited.timeQuality,
                expectedArtifacts = edited.expectedArtifacts,
                detail = edited.detail?.ifBlank { null },
                reviewed = true,
            )
        )
    }

    /** Undo of an accept: a new revision marked not reviewed again. */
    fun unaccept(current: Entry): Entry =
        write(current.copy(seq = 0, revisionN = current.revisionN + 1, editedUtcMs = stamp().utcMs, reviewed = false))

    fun tombstone(current: Entry): Entry =
        write(current.copy(seq = 0, revisionN = current.revisionN + 1, kind = Kind.TOMBSTONE, editedUtcMs = stamp().utcMs))

    fun refreshProfile(carrier: String?, notes: String?): Long {
        val s = stamp()
        val id = db.insertProfile(
            Profile(
                manufacturer = Build.MANUFACTURER, model = Build.MODEL, device = Build.DEVICE,
                fingerprint = Build.FINGERPRINT, release = Build.VERSION.RELEASE,
                incremental = Build.VERSION.INCREMENTAL, sdkInt = Build.VERSION.SDK_INT,
                securityPatch = Build.VERSION.SECURITY_PATCH, kernel = System.getProperty("os.version"),
                baseband = Build.getRadioVersion(), carrier = carrier?.ifBlank { null }, notes = notes?.ifBlank { null },
            ),
            s,
        )
        log(Draft(action = "profile_snapshot", detail = "profile id $id"), s, kind = Kind.SYSTEM_EVENT)
        return id
    }

    /** Launcher apps only, visible through the manifest's <queries> element. */
    fun launcherApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != context.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    fun addApp(pkg: String, label: String, account: String?) {
        val info = pm.getPackageInfo(pkg, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        db.upsertApp(
            AppUnderTest(
                pkg = pkg, label = label, versionName = info.versionName, versionCode = code,
                firstInstallMs = info.firstInstallTime, lastUpdateMs = info.lastUpdateTime,
                account = account?.ifBlank { null }, addedUtcMs = System.currentTimeMillis(),
            )
        )
    }

    // ---- Export ----

    private fun appJson(a: AppUnderTest): JSONObject = JSONObject().apply {
        put("package", a.pkg); put("label", a.label)
        put("version_name_at_add", a.versionName ?: JSONObject.NULL)
        put("version_code_at_add", a.versionCode ?: JSONObject.NULL)
        put("version_name_at_export", appVersion(a.pkg) ?: JSONObject.NULL)
        put("first_install_ms", a.firstInstallMs ?: JSONObject.NULL)
        put("last_update_ms", a.lastUpdateMs ?: JSONObject.NULL)
        put("account", a.account ?: JSONObject.NULL)
        put("added_utc_ms", a.addedUtcMs)
    }

    private fun profileJson(p: Profile): JSONObject = JSONObject().apply {
        fun n(v: Any?): Any = v ?: JSONObject.NULL
        put("id", n(p.id)); put("utc_ms", p.utcMs); put("boot_count", p.bootCount)
        put("manufacturer", n(p.manufacturer)); put("model", n(p.model)); put("device", n(p.device))
        put("fingerprint", n(p.fingerprint)); put("release", n(p.release)); put("incremental", n(p.incremental))
        put("sdk_int", n(p.sdkInt)); put("security_patch", n(p.securityPatch)); put("kernel", n(p.kernel))
        put("baseband", n(p.baseband)); put("carrier", n(p.carrier)); put("notes", n(p.notes))
    }

    fun manifest(rows: List<Entry>, jsonlBytes: ByteArray, exportStamp: Stamp): JSONObject {
        val self = pm.getPackageInfo(context.packageName, 0)
        return JSONObject().apply {
            put("format", "known-data-logger")
            put("schema_version", SCHEMA_VERSION)
            put("logger", JSONObject().apply {
                put("package", context.packageName)
                put("version_name", self.versionName ?: JSONObject.NULL)
                put("first_install_ms", self.firstInstallTime)
                put("last_update_ms", self.lastUpdateTime)
            })
            put("scenario_id", scenarioId)
            put("device_label", deviceLabel)
            put("profiles", JSONArray(db.profiles().map { profileJson(it) }))
            put("apps_under_test", JSONArray(db.apps().map { appJson(it) }))
            val local = localRuleIds()
            put("rules", JSONArray(loadRules().map { r ->
                JSONObject().apply {
                    put("id", r.id); put("package", r.pkg); put("event", r.event); put("view_id", r.viewId ?: JSONObject.NULL)
                    put("label_regex", r.labelRegex ?: JSONObject.NULL); put("action", r.action)
                    put("origin", if (r.id in local) "local" else "bundled"); put("verified_with", r.verifiedWith ?: JSONObject.NULL)
                }
            }))
            put("tolerances_s", JSONObject().apply {
                put("exact", Tolerance.EXACT_S)
                put("before_after", Tolerance.BEFORE_AFTER_S)
                put("approximate", Tolerance.APPROXIMATE_S)
                put("windowed", "window_s")
            })
            put("row_rules", "Every revision is a row. The current version of an entry is its highest revision_n; " +
                "a tombstone as the highest revision removes the entry. Action time fields never change across revisions. " +
                "Order rows by (boot_count, elapsed_ms, seq).")
            put("export", JSONObject().apply {
                put("utc_ms", exportStamp.utcMs); put("elapsed_ms", exportStamp.elapsedMs)
                put("boot_count", exportStamp.bootCount); put("tz_id", exportStamp.tzId)
                put("tz_offset_min", exportStamp.tzOffsetMin)
            })
            put("files", JSONObject().apply {
                put("actions.jsonl", JSONObject().apply {
                    put("sha256", sha256Hex(jsonlBytes)); put("rows", rows.size)
                })
            })
            // The last link of the chain; a copy kept elsewhere shows whether rows were cut off the end.
            put("hash_chain", JSONObject().apply {
                put("rows", rows.size)
                put("last_seq", rows.maxOfOrNull { it.seq } ?: JSONObject.NULL)
                put("last_row_hash", rows.maxByOrNull { it.seq }?.rowHash ?: JSONObject.NULL)
                put("first_break_seq", Chain.firstBreak(rows) ?: JSONObject.NULL)
            })
        }
    }

    fun exportFolderName(s: Stamp): String {
        val t = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(s.utcMs))
        fun safe(x: String) = x.ifBlank { "unnamed" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "kdl_${safe(scenarioId)}_${safe(deviceLabel)}_$t"
    }

    /**
     * Logs the export as its own entry, then writes manifest.json, actions.jsonl and actions.csv
     * into a new folder under the tree the user picked. Returns the folder name.
     */
    fun exportTo(treeUri: Uri): String {
        val s = stamp()
        val name = exportFolderName(s)
        // The export row comes first so the export lists itself; a failure is logged after it.
        log(Draft(action = "export", detail = "folder $name"), s, kind = Kind.EXPORT)
        try {
            return writeExport(treeUri, name, s)
        } catch (e: Exception) {
            log(Draft(action = "export_failed", detail = "folder $name: ${e.javaClass.simpleName}", source = Source.SYSTEM),
                kind = Kind.SYSTEM_EVENT)
            throw e
        }
    }

    /** Screenshots are copied and hashed one at a time; manifest.json is written last. */
    private fun writeExport(treeUri: Uri, name: String, s: Stamp): String {
        val rows = db.entries()
        val shots = db.shots()
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val dir = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: error("Could not create the export folder")
        fun put(file: String, mime: String, bytes: ByteArray) {
            val doc = DocumentsContract.createDocument(resolver, dir, mime, file) ?: error("Could not create $file")
            resolver.openOutputStream(doc, "w")!!.use { it.write(bytes) }
        }

        val exportHash = HashMap<String, String>()
        var screens: Uri? = null
        shots.values.sortedBy { it.utcMs }.forEach { sh ->
            val f = sh.file?.let { File(screensDir(), it) } ?: return@forEach
            if (!f.exists()) return@forEach
            if (screens == null) {
                screens = DocumentsContract.createDocument(resolver, dir, DocumentsContract.Document.MIME_TYPE_DIR, "screens")
                    ?: error("Could not create screens folder")
            }
            val doc = DocumentsContract.createDocument(resolver, screens!!, "image/png", f.name.removeSuffix(".png"))
                ?: error("Could not create ${f.name}")
            val md = java.security.MessageDigest.getInstance("SHA-256")
            resolver.openOutputStream(doc, "w")!!.use { out ->
                f.inputStream().use { inp ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        md.update(buf, 0, n); out.write(buf, 0, n)
                    }
                }
            }
            exportHash[sh.entryId] = md.digest().joinToString("") { "%02x".format(it) }
        }

        val jsonlBytes = jsonl(rows, shots).toByteArray(Charsets.UTF_8)
        put("actions.jsonl", "application/octet-stream", jsonlBytes)
        put("actions.csv", "text/csv", Csv.document(rows, shots).toByteArray(Charsets.UTF_8))
        val shotList = JSONArray(shots.values.sortedBy { it.utcMs }.map { sh ->
            val now = exportHash[sh.entryId]
            JSONObject().apply {
                put("entry_id", sh.entryId); put("file", sh.file ?: JSONObject.NULL); put("status", sh.status)
                put("sha256_at_capture", sh.sha256 ?: JSONObject.NULL); put("sha256_at_export", now ?: JSONObject.NULL)
                // A file that was recorded but is missing now counts as a mismatch, not as unknown.
                put("hash_match", when {
                    sh.sha256 == null -> JSONObject.NULL
                    now == null -> false
                    else -> sh.sha256 == now
                })
            }
        })
        put("manifest.json", "application/json",
            manifest(rows, jsonlBytes, s).apply { put("screenshots", shotList) }.toString(2).toByteArray(Charsets.UTF_8))
        return name
    }
}
