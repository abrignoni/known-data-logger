package com.abrignoni.knowndatalogger

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

/**
 * The logger's own database, kl.db. It is evidence on the test device too, so its schema is
 * versioned (user_version) and documented in docs/DATABASE.md. Rollback journal, never WAL,
 * so an extraction holds no live -wal file.
 */
class Db(context: Context) : SQLiteOpenHelper(context, NAME, null, SCHEMA_VERSION) {

    companion object {
        const val NAME = "kl.db"
    }

    init {
        setWriteAheadLoggingEnabled(false)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.disableWriteAheadLogging()
        db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL(
            """CREATE TABLE profiles (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                utc_ms INTEGER NOT NULL, elapsed_ms INTEGER NOT NULL, boot_count INTEGER NOT NULL,
                manufacturer TEXT, model TEXT, device TEXT, fingerprint TEXT, release TEXT,
                incremental TEXT, sdk_int INTEGER, security_patch TEXT, kernel TEXT, baseband TEXT,
                carrier TEXT, notes TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE apps (
                package TEXT PRIMARY KEY, label TEXT, version_name TEXT, version_code INTEGER,
                first_install_ms INTEGER, last_update_ms INTEGER, account TEXT,
                added_utc_ms INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE entries (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                entry_id TEXT NOT NULL, revision_n INTEGER NOT NULL, kind TEXT NOT NULL,
                scenario_id TEXT NOT NULL, device_label TEXT NOT NULL, profile_id INTEGER,
                utc_ms INTEGER NOT NULL, tz_id TEXT NOT NULL, tz_offset_min INTEGER NOT NULL,
                elapsed_ms INTEGER NOT NULL, boot_count INTEGER NOT NULL,
                network_time_ms INTEGER, gnss_time_ms INTEGER,
                time_quality TEXT NOT NULL, window_s REAL, edited_utc_ms INTEGER NOT NULL,
                app_package TEXT, app_version TEXT, account TEXT, action TEXT NOT NULL,
                content TEXT, counterpart TEXT, direction TEXT, conversation_id TEXT,
                refers_to TEXT, expected_artifacts TEXT NOT NULL, detail TEXT)"""
        )
        db.execSQL("CREATE INDEX entries_entry_id ON entries(entry_id)")
        upgradeTo2(db)
        upgradeTo3(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrations only add; they never drop or rewrite data.
        if (oldVersion < 2) upgradeTo2(db)
        if (oldVersion < 3) upgradeTo3(db)
    }

    /** Schema 2: row source, review flag, screenshots. Existing rows become manual and reviewed. */
    private fun upgradeTo2(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE entries ADD COLUMN source TEXT NOT NULL DEFAULT 'manual'")
        db.execSQL("ALTER TABLE entries ADD COLUMN reviewed INTEGER NOT NULL DEFAULT 1")
        db.execSQL(
            """CREATE TABLE screenshots (
                entry_id TEXT PRIMARY KEY, file TEXT, sha256 TEXT, status TEXT NOT NULL,
                utc_ms INTEGER NOT NULL)"""
        )
    }

    /** Schema 3: hash chain columns. Rows written before it are hashed by Repo on first use. */
    private fun upgradeTo3(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE entries ADD COLUMN prev_hash TEXT")
        db.execSQL("ALTER TABLE entries ADD COLUMN row_hash TEXT")
    }

    fun setHash(seq: Long, prev: String, row: String) {
        writableDatabase.update("entries", ContentValues().apply { put("prev_hash", prev); put("row_hash", row) },
            "seq=?", arrayOf(seq.toString()))
    }

    fun insert(e: Entry): Long {
        val v = ContentValues().apply {
            put("entry_id", e.entryId); put("revision_n", e.revisionN); put("kind", e.kind)
            put("scenario_id", e.scenarioId); put("device_label", e.deviceLabel); put("profile_id", e.profileId)
            put("utc_ms", e.stamp.utcMs); put("tz_id", e.stamp.tzId); put("tz_offset_min", e.stamp.tzOffsetMin)
            put("elapsed_ms", e.stamp.elapsedMs); put("boot_count", e.stamp.bootCount)
            put("network_time_ms", e.stamp.networkTimeMs); put("gnss_time_ms", e.stamp.gnssTimeMs)
            put("time_quality", e.timeQuality); put("window_s", e.windowS); put("edited_utc_ms", e.editedUtcMs)
            put("app_package", e.appPackage); put("app_version", e.appVersion); put("account", e.account)
            put("action", e.action); put("content", e.content); put("counterpart", e.counterpart)
            put("direction", e.direction); put("conversation_id", e.conversationId); put("refers_to", e.refersTo)
            put("expected_artifacts", JSONArray(e.expectedArtifacts).toString()); put("detail", e.detail)
            put("source", e.source); put("reviewed", if (e.reviewed) 1 else 0)
        }
        return writableDatabase.insertOrThrow("entries", null, v)
    }

    fun entries(): List<Entry> =
        readableDatabase.rawQuery("SELECT * FROM entries ORDER BY seq", null).use { c ->
            val out = ArrayList<Entry>(c.count)
            while (c.moveToNext()) out += c.toEntry()
            out
        }

    private fun Cursor.str(n: String): String? = getColumnIndexOrThrow(n).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(n: String): Long? = getColumnIndexOrThrow(n).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.dbl(n: String): Double? = getColumnIndexOrThrow(n).let { if (isNull(it)) null else getDouble(it) }

    private fun Cursor.toEntry(): Entry {
        val arts = JSONArray(str("expected_artifacts") ?: "[]")
        return Entry(
            seq = long("seq")!!, entryId = str("entry_id")!!, revisionN = long("revision_n")!!.toInt(),
            kind = str("kind")!!, scenarioId = str("scenario_id")!!, deviceLabel = str("device_label")!!,
            profileId = long("profile_id"),
            stamp = Stamp(
                utcMs = long("utc_ms")!!, tzId = str("tz_id")!!, tzOffsetMin = long("tz_offset_min")!!.toInt(),
                elapsedMs = long("elapsed_ms")!!, bootCount = long("boot_count")!!.toInt(),
                networkTimeMs = long("network_time_ms"), gnssTimeMs = long("gnss_time_ms"),
            ),
            timeQuality = str("time_quality")!!, windowS = dbl("window_s"), editedUtcMs = long("edited_utc_ms")!!,
            appPackage = str("app_package"), appVersion = str("app_version"), account = str("account"),
            action = str("action")!!, content = str("content"), counterpart = str("counterpart"),
            direction = str("direction"), conversationId = str("conversation_id"), refersTo = str("refers_to"),
            expectedArtifacts = (0 until arts.length()).map { arts.getString(it) }, detail = str("detail"),
            source = str("source") ?: Source.MANUAL, reviewed = (long("reviewed") ?: 1L) != 0L,
            prevHash = str("prev_hash"), rowHash = str("row_hash"),
        )
    }

    fun putShot(s: Shot) {
        writableDatabase.insertWithOnConflict("screenshots", null, ContentValues().apply {
            put("entry_id", s.entryId); put("file", s.file); put("sha256", s.sha256)
            put("status", s.status); put("utc_ms", s.utcMs)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun shots(): Map<String, Shot> =
        readableDatabase.rawQuery("SELECT * FROM screenshots", null).use { c ->
            val out = HashMap<String, Shot>()
            while (c.moveToNext()) {
                val id = c.str("entry_id")!!
                out[id] = Shot(id, c.str("file"), c.str("sha256"), c.str("status")!!, c.long("utc_ms") ?: 0)
            }
            out
        }

    fun setting(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun putSetting(key: String, value: String) {
        writableDatabase.insertWithOnConflict(
            "settings", null, ContentValues().apply { put("key", key); put("value", value) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun insertProfile(p: Profile, stamp: Stamp): Long =
        writableDatabase.insertOrThrow("profiles", null, ContentValues().apply {
            put("utc_ms", stamp.utcMs); put("elapsed_ms", stamp.elapsedMs); put("boot_count", stamp.bootCount)
            put("manufacturer", p.manufacturer); put("model", p.model); put("device", p.device)
            put("fingerprint", p.fingerprint); put("release", p.release); put("incremental", p.incremental)
            put("sdk_int", p.sdkInt); put("security_patch", p.securityPatch); put("kernel", p.kernel)
            put("baseband", p.baseband); put("carrier", p.carrier); put("notes", p.notes)
        })

    fun profiles(): List<Profile> =
        readableDatabase.rawQuery("SELECT * FROM profiles ORDER BY id", null).use { c ->
            val out = mutableListOf<Profile>()
            while (c.moveToNext()) out += Profile(
                id = c.long("id"), utcMs = c.long("utc_ms") ?: 0, bootCount = (c.long("boot_count") ?: -1).toInt(),
                manufacturer = c.str("manufacturer"), model = c.str("model"), device = c.str("device"),
                fingerprint = c.str("fingerprint"), release = c.str("release"), incremental = c.str("incremental"),
                sdkInt = c.long("sdk_int")?.toInt(), securityPatch = c.str("security_patch"),
                kernel = c.str("kernel"), baseband = c.str("baseband"), carrier = c.str("carrier"), notes = c.str("notes"),
            )
            out
        }

    fun upsertApp(a: AppUnderTest) {
        writableDatabase.insertWithOnConflict("apps", null, ContentValues().apply {
            put("package", a.pkg); put("label", a.label); put("version_name", a.versionName)
            put("version_code", a.versionCode); put("first_install_ms", a.firstInstallMs)
            put("last_update_ms", a.lastUpdateMs); put("account", a.account); put("added_utc_ms", a.addedUtcMs)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeApp(pkg: String) {
        writableDatabase.delete("apps", "package=?", arrayOf(pkg))
    }

    fun apps(): List<AppUnderTest> =
        readableDatabase.rawQuery("SELECT * FROM apps ORDER BY label", null).use { c ->
            val out = mutableListOf<AppUnderTest>()
            while (c.moveToNext()) out += AppUnderTest(
                pkg = c.str("package")!!, label = c.str("label") ?: "", versionName = c.str("version_name"),
                versionCode = c.long("version_code"), firstInstallMs = c.long("first_install_ms"),
                lastUpdateMs = c.long("last_update_ms"), account = c.str("account"),
                addedUtcMs = c.long("added_utc_ms") ?: 0,
            )
            out
        }
}

data class Profile(
    val id: Long? = null,
    val utcMs: Long = 0,
    val bootCount: Int = -1,
    val manufacturer: String?,
    val model: String?,
    val device: String?,
    val fingerprint: String?,
    val release: String?,
    val incremental: String?,
    val sdkInt: Int?,
    val securityPatch: String?,
    val kernel: String?,
    val baseband: String?,
    val carrier: String?,
    val notes: String?,
)

data class AppUnderTest(
    val pkg: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long?,
    val firstInstallMs: Long?,
    val lastUpdateMs: Long?,
    val account: String?,
    val addedUtcMs: Long,
)
