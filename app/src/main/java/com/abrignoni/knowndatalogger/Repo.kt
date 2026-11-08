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
)

class Repo(private val context: Context) {
    val db = Db(context)
    private val resolver get() = context.contentResolver
    private val pm get() = context.packageManager

    var scenarioId: String
        get() = db.setting("scenario_id") ?: ""
        set(v) = db.putSetting("scenario_id", v.trim())
    var deviceLabel: String
        get() = db.setting("device_label") ?: ""
        set(v) = db.putSetting("device_label", v.trim())

    fun stamp(): Stamp = Clocks.stamp(resolver)

    /** Start stamp of an open Start/Done window, kept in the database so a killed app keeps it. */
    fun saveWindowStart(s: Stamp?) {
        if (s == null) { db.writableDatabase.delete("settings", "key=?", arrayOf("window_start")); return }
        db.putSetting("window_start", JSONObject().apply {
            put("utc_ms", s.utcMs); put("tz_id", s.tzId); put("tz_offset_min", s.tzOffsetMin)
            put("elapsed_ms", s.elapsedMs); put("boot_count", s.bootCount)
            put("network_time_ms", s.networkTimeMs ?: JSONObject.NULL); put("gnss_time_ms", s.gnssTimeMs ?: JSONObject.NULL)
        }.toString())
    }

    fun loadWindowStart(): Stamp? = db.setting("window_start")?.let {
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

    /** Inserts a row and appends it to the live JSONL file that survives an app crash. */
    private fun write(e: Entry): Entry {
        val seq = db.insert(e)
        val saved = e.copy(seq = seq)
        try {
            liveFile().appendText(saved.toJson().toString() + "\n")
        } catch (ignored: Exception) {
            // The database row is the record; the live copy is a convenience.
        }
        return saved
    }

    fun liveFile(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "actions-live.jsonl")

    /** Logs system rows for detected reboots and clock or zone changes, then returns them. */
    private fun systemRowsFor(stamp: Stamp): List<Entry> =
        Timeline.detectChanges(db.entries(), stamp).map { (kind, text) ->
            write(base(kind, stamp).copy(action = if (kind == Kind.CLOCK_CHANGE) "clock_change_detected" else "boot_detected", detail = text))
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
    fun log(draft: Draft, stamp: Stamp = stamp(), windowS: Double? = null, kind: String = Kind.ACTION): List<Entry> {
        val sys = systemRowsFor(stamp)
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
            )
        )
    }

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
        log(Draft(action = "export", detail = "folder $name"), s, kind = Kind.EXPORT)
        val rows = db.entries()
        val jsonlBytes = jsonl(rows).toByteArray(Charsets.UTF_8)
        val csvBytes = Csv.document(rows).toByteArray(Charsets.UTF_8)
        val manifestBytes = manifest(rows, jsonlBytes, s).toString(2).toByteArray(Charsets.UTF_8)

        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val dir = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: error("Could not create the export folder")
        fun put(file: String, mime: String, bytes: ByteArray) {
            val doc = DocumentsContract.createDocument(resolver, dir, mime, file) ?: error("Could not create $file")
            resolver.openOutputStream(doc, "w")!!.use { it.write(bytes) }
        }
        put("manifest.json", "application/json", manifestBytes)
        put("actions.jsonl", "application/octet-stream", jsonlBytes)
        put("actions.csv", "text/csv", csvBytes)
        return name
    }
}
