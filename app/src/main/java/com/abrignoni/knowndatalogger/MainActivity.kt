package com.abrignoni.knowndatalogger

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = Repo.get(applicationContext)
        windowStart = repo.loadWindowStart()?.let { Pending(it) }
        setContent {
            MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                App(repo)
            }
        }
    }
}

private val TABS = listOf("Scenario", "Capture", "Log", "Timeline", "Export")
private const val NO_SECRETS = "No passwords or passcodes here. Exports get shared."

@Composable
fun App(repo: Repo) {
    var tab by rememberSaveable { mutableIntStateOf(1) }
    // Bumped after every write so screens re-read the database.
    var version by remember { mutableIntStateOf(0) }
    val bump: () -> Unit = { version++ }
    // Re-read the database whenever the app comes back to the front (capture writes while it is away).
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { version++; onPauseOrDispose { } }
    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(t) })
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize()) {
            key(version) {
                when (tab) {
                    0 -> ScenarioScreen(repo, bump)
                    1 -> CaptureScreen(repo, bump)
                    2 -> LogScreen(repo, bump, onExportNeeded = { tab = 4 })
                    3 -> TimelineScreen(repo, bump)
                    else -> ExportScreen(repo, bump)
                }
            }
        }
    }
}

private fun toast(ctx: android.content.Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

private fun hhmmss(s: Stamp): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneOffset.ofTotalSeconds(s.tzOffsetMin * 60))
        .format(Instant.ofEpochMilli(s.utcMs))

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

// ---------------- Scenario ----------------

@Composable
fun ScenarioScreen(repo: Repo, bump: () -> Unit) {
    val ctx = LocalContext.current
    var scenario by remember { mutableStateOf(repo.scenarioId) }
    var device by remember { mutableStateOf(repo.deviceLabel) }
    var carrier by remember { mutableStateOf(repo.db.profiles().lastOrNull()?.carrier ?: "") }
    var notes by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    val profile = repo.db.profiles().lastOrNull()
    val apps = repo.db.apps()

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section("Scenario and device")
        OutlinedTextField(scenario, { scenario = it }, label = { Text("Scenario id (same on every phone)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(device, { device = it }, label = { Text("Device label, e.g. phone A") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { repo.scenarioId = scenario; repo.deviceLabel = device; toast(ctx, "Saved"); bump() }) { Text("Save") }

        Section("Device profile")
        if (profile == null) Text("No snapshot yet. Take one before logging.")
        else Text(
            "${profile.manufacturer} ${profile.model}, Android ${profile.release} (SDK ${profile.sdkInt})\n" +
                "Build ${profile.incremental}, patch ${profile.securityPatch}\n" +
                "Fingerprint ${profile.fingerprint}\nBaseband ${profile.baseband ?: "none"}\n" +
                "Snapshot at boot ${profile.bootCount}, ${repo.db.profiles().size} snapshot(s)",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(carrier, { carrier = it }, label = { Text("Carrier") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(notes, { notes = it }, label = { Text("Profile notes") }, supportingText = { Text(NO_SECRETS) }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { repo.refreshProfile(carrier, notes); toast(ctx, "Profile snapshot added"); bump() }) {
            Text(if (profile == null) "Take snapshot" else "Add new snapshot")
        }

        Section("Apps under test")
        apps.forEach { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(a.label, fontWeight = FontWeight.Medium)
                    Text("${a.pkg} ${a.versionName ?: ""}${a.account?.let { ", account $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { repo.db.removeApp(a.pkg); bump() }) { Text("Remove") }
            }
        }
        OutlinedButton(onClick = { picking = true }) { Text("Add app") }
        Spacer(Modifier.height(24.dp))
    }

    if (picking) AppPicker(repo, onDone = { picking = false; bump() })
}

@Composable
private fun AppPicker(repo: Repo, onDone: () -> Unit) {
    val all = remember { repo.launcherApps() }
    var chosen by remember { mutableStateOf<Pair<String, String>?>(null) }
    var account by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (chosen == null) "Pick an app" else chosen!!.second) },
        text = {
            if (chosen == null) {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(all) { p ->
                        Column(Modifier.fillMaxWidth().clickable { chosen = p }.padding(vertical = 8.dp)) {
                            Text(p.second); Text(p.first, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                OutlinedTextField(account, { account = it }, label = { Text("Account handle (optional)") }, supportingText = { Text(NO_SECRETS) })
            }
        },
        confirmButton = {
            if (chosen != null) TextButton(onClick = { repo.addApp(chosen!!.first, chosen!!.second, account); onDone() }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

// ---------------- Log ----------------

private data class Pending(val stamp: Stamp)

/** Start stamp for a windowed action; kept across tab switches for the life of the process. */
private var windowStart: Pending? by mutableStateOf(null)
private var lastDraft: Draft? = null

/** Name of the last export folder, kept across screen refreshes. */
private var lastExport: String? by mutableStateOf(null)

@Composable
fun LogScreen(repo: Repo, bump: () -> Unit, onExportNeeded: () -> Unit) {
    val ctx = LocalContext.current
    val apps = repo.db.apps()
    var app by remember { mutableStateOf(lastDraft?.appPackage) }
    var action by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var counterpart by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf("") }
    var conversation by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf(TimeQuality.EXACT) }
    var expected by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    val ready = repo.scenarioId.isNotBlank() && repo.deviceLabel.isNotBlank()

    fun draft() = Draft(
        appPackage = app, action = action.trim(), content = content, counterpart = counterpart,
        direction = direction, conversationId = conversation, timeQuality = quality,
        expectedArtifacts = expected.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }, detail = note,
    )
    fun report(rows: List<Entry>) {
        val sys = rows.filter { it.kind != Kind.ACTION }
        toast(ctx, if (sys.isEmpty()) "Logged" else "Logged. Also noted: " + sys.joinToString("; ") { it.detail ?: it.action })
    }
    fun commit() {
        val d = draft()
        val rows = repo.log(d)
        lastDraft = d
        report(rows)
        bump()
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (!ready) Text("Set the scenario id and device label on the Scenario tab first.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
        Section("App")
        Picker(
            value = apps.firstOrNull { it.pkg == app }?.label ?: "None (device or system)",
            options = listOf<Pair<String?, String>>(null to "None (device or system)") + apps.map { it.pkg to it.label },
            onPick = { app = it },
        )
        Section("Action")
        Picker(value = action.ifBlank { "Pick a template" }, options = ACTION_TEMPLATES.map { it to templateLabel(it) }, onPick = { action = it })
        OutlinedTextField(action, { action = it }, label = { Text("Action (template key or free text)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(content, { content = it }, label = { Text("Content: exact text, filename or caption") }, supportingText = { Text(NO_SECRETS) }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(counterpart, { counterpart = it }, label = { Text("Counterpart") }, modifier = Modifier.weight(1f))
            OutlinedTextField(conversation, { conversation = it }, label = { Text("Conversation") }, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("" to "No direction", "out" to "Out", "in" to "In").forEach { (v, l) ->
                FilterChip(selected = direction == v, onClick = { direction = v }, label = { Text(l) })
            }
        }
        Text("The tap is", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TimeQuality.ALL.forEach { q -> FilterChip(selected = quality == q, onClick = { quality = q }, label = { Text(q) }) }
        }
        OutlinedTextField(expected, { expected = it }, label = { Text("Expected artifacts, e.g. aleapp:key") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it }, label = { Text("Note") }, supportingText = { Text(NO_SECRETS) }, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(8.dp))
        Button(
            enabled = ready, modifier = Modifier.fillMaxWidth().height(56.dp),
            onClick = { if (action.trim() == FACTORY_RESET) confirmReset = true else commit() },
        ) { Text("Log now") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(enabled = ready, modifier = Modifier.weight(1f), onClick = {
                report(repo.log(Draft(appPackage = app))); bump()
            }) { Text("Stamp only") }
            OutlinedButton(enabled = ready && lastDraft != null, modifier = Modifier.weight(1f), onClick = {
                report(repo.log(lastDraft!!)); bump()
            }) { Text("Repeat last") }
        }
        val start = windowStart
        OutlinedButton(enabled = ready, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), onClick = {
            if (start == null) {
                val s = repo.stamp()
                repo.saveWindowStart(s); windowStart = Pending(s); toast(ctx, "Start stamped. Tap Done when it ends.")
            } else {
                val end = repo.stamp()
                val d = draft()
                report(repo.log(d, start.stamp, windowS = (end.elapsedMs - start.stamp.elapsedMs) / 1000.0))
                repo.saveWindowStart(null); windowStart = null; lastDraft = d; bump()
            }
        }) { Text(if (start == null) "Start (call, recording)" else "Done: started ${hhmmss(start.stamp)}") }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Factory reset") },
        text = { Text("A reset erases this log on the phone. The reset entry will be logged, then you'll export. Copy the export off the phone before resetting.") },
        confirmButton = { TextButton(onClick = { confirmReset = false; commit(); onExportNeeded() }) { Text("Log and export") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
}

@Composable
private fun <T> Picker(value: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(value) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { onPick(v); open = false }) }
        }
    }
}

// ---------------- Capture ----------------

private fun serviceEnabled(ctx: android.content.Context): Boolean {
    val enabled = android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
    return enabled.split(':').any { it.equals("${ctx.packageName}/${CaptureService::class.java.name}", true) || it.equals("${ctx.packageName}/.CaptureService", true) }
}

@Composable
fun CaptureScreen(repo: Repo, bump: () -> Unit) {
    val ctx = LocalContext.current
    val enabled = serviceEnabled(ctx)
    val active = repo.flag(Repo.KEY_SESSION, false)
    var shotAuto by remember { mutableStateOf(repo.flag(Repo.KEY_SHOT_AUTO, true)) }
    var shotTap by remember { mutableStateOf(repo.flag(Repo.KEY_SHOT_TAP, false)) }
    var allApps by remember { mutableStateOf(repo.flag(Repo.KEY_ALL_APPS, false)) }
    var maskCodes by remember { mutableStateOf(repo.flag(Repo.KEY_MASK_CODES, true)) }
    val ready = repo.scenarioId.isNotBlank() && repo.deviceLabel.isNotBlank()
    val rules = remember { repo.loadRules() }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section("Capture service")
        Text(if (enabled) "On" else "Off. Turn on \"Known-data logger\" in Accessibility settings.",
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = { ctx.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open accessibility settings") }
        Text("On Android 13 and later, a sideloaded app may first need App info, menu, Allow restricted settings.", style = MaterialTheme.typography.bodySmall)

        Section("Session")
        Text(if (active) "Capturing. The red MARK button is on screen." else "Not capturing.")
        Button(enabled = enabled && ready, modifier = Modifier.fillMaxWidth().height(56.dp), onClick = {
            val start = !active
            repo.setFlag(Repo.KEY_SESSION, start)
            repo.log(Draft(action = if (start) "capture_session_start" else "capture_session_stop", source = Source.SYSTEM,
                detail = if (start) "apps: " + (if (allApps) "all" else repo.capturePackages().sorted().joinToString(" ")) +
                    "; screenshots: auto=$shotAuto every_tap=$shotTap" else null), kind = Kind.SYSTEM_EVENT)
            CaptureService.instance?.syncSession()
            toast(ctx, if (start) "Session started" else "Session stopped"); bump()
        }) { Text(if (active) "Stop session" else "Start session") }
        if (!ready) Text("Set the scenario id and device label first.", color = MaterialTheme.colorScheme.error)

        Section("Options (applied at session start)")
        Row(Modifier.fillMaxWidth().clickable { shotAuto = !shotAuto; repo.setFlag(Repo.KEY_SHOT_AUTO, shotAuto) }) { Checkbox(shotAuto, { shotAuto = it; repo.setFlag(Repo.KEY_SHOT_AUTO, it) }); Text("Screenshot each auto-labelled action", Modifier.padding(top = 12.dp)) }
        Row(Modifier.fillMaxWidth().clickable { shotTap = !shotTap; repo.setFlag(Repo.KEY_SHOT_TAP, shotTap) }) { Checkbox(shotTap, { shotTap = it; repo.setFlag(Repo.KEY_SHOT_TAP, it) }); Text("Screenshot every tap", Modifier.padding(top = 12.dp)) }
        Row(Modifier.fillMaxWidth().clickable { maskCodes = !maskCodes; repo.setFlag(Repo.KEY_MASK_CODES, maskCodes) }) { Checkbox(maskCodes, { maskCodes = it; repo.setFlag(Repo.KEY_MASK_CODES, it) }); Text("Withhold codes in notifications (4 to 8 digits near words like code or OTP)", Modifier.padding(top = 12.dp)) }
        Row(Modifier.fillMaxWidth().clickable { allApps = !allApps; repo.setFlag(Repo.KEY_ALL_APPS, allApps) }) { Checkbox(allApps, { allApps = it; repo.setFlag(Repo.KEY_ALL_APPS, it) }); Text("Capture all apps, not only apps under test", Modifier.padding(top = 12.dp)) }
        Text("The MARK button always takes a screenshot." +
            (if (android.os.Build.VERSION.SDK_INT < 30) " Screenshots need Android 11 or later; this phone will log them as unsupported." else ""),
            style = MaterialTheme.typography.bodySmall)
        Text("Captured apps: " + (if (allApps) "all" else repo.capturePackages().sorted().joinToString(", ")), style = MaterialTheme.typography.bodySmall)

        Section("Auto-label rules (${rules.size})")
        val local = remember { repo.localRuleIds() }
        rules.forEach { r ->
            Row(Modifier.fillMaxWidth()) {
                Text("${r.id}: ${r.event} in ${r.pkg} -> ${r.action}" + if (r.id in local) " (local)" else " (bundled)",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(top = 12.dp))
                if (r.id in local) TextButton(onClick = {
                    try { repo.removeLocalRule(r.id); CaptureService.instance?.reloadRules() }
                    catch (ex: IllegalStateException) { toast(ctx, ex.message ?: "Rule not removed") }
                    bump()
                }) { Text("Remove") }
            }
        }
        Text("Teach a rule from Timeline: label a captured tap, cleared field or mark and tick Make a rule. " +
            "Local rules live in rules.json in the app's external files folder (adb pull to share, adb push to load).",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------- Timeline ----------------

private val FILTERS = listOf("To review", "Actions", "Everything")

/** Selected timeline filter, kept across screen refreshes. */
private val timelineFilter = mutableIntStateOf(1)

@Composable
fun TimelineScreen(repo: Repo, bump: () -> Unit) {
    val rows = remember { repo.db.entries() }
    val shots = remember { repo.db.shots() }
    val current = remember(rows) { Timeline.current(rows).reversed() }
    val review = remember(rows) { Review.pending(rows).map { it.entryId }.toSet() }
    var filter by remember { timelineFilter }
    var editing by remember { mutableStateOf<Entry?>(null) }
    val labels = remember { repo.db.apps().associate { it.pkg to it.label } }
    val shown = when (filter) {
        0 -> current.filter { it.entryId in review }
        1 -> current.filter { it.kind != Kind.AUTO_EVENT || it.entryId in review }
        else -> current
    }

    Column {
        Section("Timeline, newest first")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FILTERS.forEachIndexed { i, f ->
                FilterChip(selected = filter == i, onClick = { filter = i },
                    label = { Text(if (i == 0) "$f (${review.size})" else f) })
            }
        }
        LazyColumn {
            items(shown, key = { it.entryId }) { e ->
                // A screenshot belongs to the entry id; a label made from a mark or event shows the event's.
                val shot = shots[e.entryId] ?: e.refersTo?.let { shots[it] }
                Column(Modifier.fillMaxWidth().clickable { editing = e }.padding(vertical = 8.dp)) {
                    val title = when {
                        e.isMark -> "MARK"
                        e.needsDetail -> "NEEDS DETAIL"
                        else -> templateLabel(e.action)
                    }
                    Text(
                        "${hhmmss(e.stamp)}  $title" + (if (e.revisionN > 0) "  (rev ${e.revisionN})" else "") +
                            (if (e.entryId in review) "  [review]" else ""),
                        fontWeight = FontWeight.Medium,
                    )
                    val sub = listOfNotNull(
                        e.source.takeIf { it != Source.MANUAL },
                        e.appPackage?.let { labels[it] ?: it },
                        e.counterpart?.let { (if (e.direction == "in") "from " else "to ") + it },
                        e.windowS?.let { "window ${"%.0f".format(it)} s" },
                        e.content?.let { "\"$it\"" },
                        e.refersTo?.let { "refers to an earlier entry" },
                        shot?.let { "screenshot ${it.status}" },
                        e.detail,
                    ).joinToString(", ")
                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
    editing?.let { e -> EditDialog(repo, e, shots[e.entryId] ?: e.refersTo?.let { shots[it] }, onDone = { editing = null; bump() }) }
}

@Composable
private fun ShotThumb(repo: Repo, shot: Shot?) {
    if (shot?.file == null) { shot?.let { Text("Screenshot: ${it.status}", style = MaterialTheme.typography.bodySmall) }; return }
    val bmp = remember(shot.file) {
        android.graphics.BitmapFactory.decodeFile(java.io.File(repo.screensDir(), shot.file).path,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })
    }
    if (bmp != null) androidx.compose.foundation.Image(bmp.asImageBitmap(), contentDescription = "Screenshot",
        modifier = Modifier.height(240.dp).padding(vertical = 4.dp))
    if (shot.status != "ok") Text("Screenshot: ${shot.status}", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun EditDialog(repo: Repo, e: Entry, shot: Shot?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val labellingMark = e.isMark
    val rawEvent = e.kind == Kind.AUTO_EVENT && !e.isMark
    var action by remember { mutableStateOf(if (labellingMark || rawEvent) "" else e.action) }
    var content by remember { mutableStateOf(if (labellingMark) "" else e.content ?: "") }
    var counterpart by remember { mutableStateOf(e.counterpart ?: "") }
    var conversation by remember { mutableStateOf(e.conversationId ?: "") }
    var note by remember { mutableStateOf(if (labellingMark || rawEvent) "" else e.detail ?: "") }
    var quality by remember { mutableStateOf(e.timeQuality) }
    var expected by remember { mutableStateOf(e.expectedArtifacts.joinToString(", ")) }
    fun expectedList() = expected.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    // Teach mode: the captured tap or cleared field this label can become a rule from.
    val teachSource = remember(e.entryId) {
        when {
            rawEvent && e.action in setOf(AutoAction.TAP, AutoAction.FIELD_CLEARED) && e.appPackage != null -> e
            labellingMark -> sourceEventFor(e, repo.db.entries())
            else -> null
        }?.takeIf {
            // A cleared field is matched by view id only: its label would be the typed message itself.
            viewIdOf(it) != null || (it.action == AutoAction.TAP && !it.content.isNullOrBlank())
        }
    }
    var teach by remember { mutableStateOf(false) }
    var teachUseTyped by remember { mutableStateOf(teachSource?.action == AutoAction.FIELD_CLEARED) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text((if (labellingMark) "Label mark " else if (rawEvent) "Label event " else "Edit ") + hhmmss(e.stamp)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ShotThumb(repo, shot)
                Text(
                    when {
                        labellingMark || rawEvent -> "Saving creates an action at this moment that points at the captured event."
                        e.source == Source.AUTO_RULE && !e.reviewed -> "Labelled by ${e.detail ?: "a rule"}. Accept it, or change it and save."
                        else -> "Edits are saved as a new revision. The action time stays fixed."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (rawEvent) Text("Captured: ${e.action} ${e.content?.let { "\"$it\"" } ?: ""} ${e.detail ?: ""}", style = MaterialTheme.typography.bodySmall)
                Picker(value = action.ifBlank { "Pick a template" }, options = ACTION_TEMPLATES.map { it to templateLabel(it) }, onPick = { action = it })
                OutlinedTextField(action, { action = it }, label = { Text("Action") })
                OutlinedTextField(content, { content = it }, label = { Text("Content") }, supportingText = { Text(NO_SECRETS) })
                OutlinedTextField(counterpart, { counterpart = it }, label = { Text("Counterpart") })
                OutlinedTextField(conversation, { conversation = it }, label = { Text("Conversation") })
                Row { TimeQuality.ALL.forEach { q -> FilterChip(selected = quality == q, onClick = { quality = q }, label = { Text(q) }) } }
                OutlinedTextField(expected, { expected = it }, label = { Text("Expected artifacts") })
                OutlinedTextField(note, { note = it }, label = { Text("Note") }, supportingText = { Text(NO_SECRETS) })
                teachSource?.let { src ->
                    val what = if (src.action == AutoAction.FIELD_CLEARED) "field cleared" else "tap"
                    val target = viewIdOf(src) ?: "label \"${src.content}\""
                    Row(Modifier.fillMaxWidth().clickable { teach = !teach }) { Checkbox(teach, { teach = it }); Text("Make a rule: $what on $target in ${src.appPackage}", Modifier.padding(top = 12.dp)) }
                    if (teach) Row(Modifier.fillMaxWidth().clickable { teachUseTyped = !teachUseTyped }) { Checkbox(teachUseTyped, { teachUseTyped = it }); Text("Use the text typed before it as content", Modifier.padding(top = 12.dp)) }
                }
                if (!labellingMark && !rawEvent) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        repo.log(Draft(appPackage = e.appPackage, action = "deleted_item", refersTo = e.entryId, counterpart = e.counterpart, conversationId = e.conversationId))
                        toast(ctx, "Deletion logged now, pointing at this entry"); onDone()
                    }) { Text("I deleted this, log it") }
                }
                TextButton(onClick = { repo.tombstone(e); toast(ctx, "Removed (kept as a tombstone)"); onDone() }) {
                    Text(if (labellingMark) "Discard mark" else "Remove entry")
                }
            }
        },
        confirmButton = {
            Row {
                if (e.source == Source.AUTO_RULE && !e.reviewed) TextButton(onClick = {
                    repo.revise(e, Draft(appPackage = e.appPackage, action = e.action, content = e.content, counterpart = e.counterpart,
                        direction = e.direction, conversationId = e.conversationId, timeQuality = e.timeQuality,
                        expectedArtifacts = e.expectedArtifacts, detail = e.detail))
                    onDone()
                }) { Text("Accept") }
                TextButton(onClick = {
                    if (labellingMark || rawEvent) {
                        if (action.isBlank()) { toast(ctx, "Pick or type an action first"); return@TextButton }
                        repo.log(Draft(appPackage = e.appPackage, action = action.trim(), content = content, counterpart = counterpart,
                            conversationId = conversation, timeQuality = quality, expectedArtifacts = expectedList(), detail = note,
                            refersTo = e.entryId, source = if (labellingMark) Source.MARK else Source.MANUAL), stamp = e.stamp, detect = false)
                        val src = teachSource
                        if (teach && src != null) {
                            val vid = viewIdOf(src)
                            val pkgV = repo.appVersion(src.appPackage)
                            try {
                                repo.saveTaughtRule(Rule(
                                    id = "taught_${src.appPackage}_${action.trim()}_${src.stamp.utcMs}".replace(Regex("[^A-Za-z0-9_.]"), "_"),
                                    pkg = src.appPackage!!,
                                    viewId = vid,
                                    labelRegex = if (vid == null) "^" + Regex.escape(src.content!!) + "$" else null,
                                    action = action.trim(),
                                    content = if (teachUseTyped) "last_typed" else null,
                                    verifiedWith = "taught on ${repo.deviceLabel} (${android.os.Build.MODEL}), app version ${pkgV ?: "unknown"}, " +
                                        Csv.utcIso(System.currentTimeMillis()),
                                    event = if (src.action == AutoAction.FIELD_CLEARED) RuleEvent.FIELD_CLEARED else RuleEvent.TAP,
                                ))
                                CaptureService.instance?.reloadRules()
                                toast(ctx, "Rule saved")
                            } catch (ex: IllegalStateException) {
                                // rules.json could not be read cleanly; it is left untouched.
                                toast(ctx, ex.message ?: "Rule not saved")
                            }
                        }
                    } else {
                        repo.revise(e, Draft(
                            appPackage = e.appPackage, action = action.trim(), content = content, counterpart = counterpart,
                            direction = e.direction, conversationId = conversation, timeQuality = quality,
                            expectedArtifacts = expectedList(), detail = note,
                        ))
                    }
                    onDone()
                }) { Text(if (labellingMark || rawEvent) "Save label" else "Save revision") }
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

// ---------------- Export ----------------

@Composable
fun ExportScreen(repo: Repo, bump: () -> Unit) {
    val ctx = LocalContext.current
    val rows = remember { repo.db.entries() }
    val current = remember(rows) { Timeline.current(rows) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            toast(ctx, "Exporting")
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            // Off the main thread: a long session can hold many screenshots.
            Thread {
                val result = runCatching { repo.exportTo(uri) }
                main.post {
                    result.onSuccess { lastExport = it; toast(ctx, "Exported") }
                        .onFailure { toast(ctx, "Export failed: ${it.message}") }
                    bump()
                }
            }.start()
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section("Export")
        Text("${current.size} entries, ${rows.size} rows including revisions and system rows.")
        Text("Writes a new folder with manifest.json, actions.jsonl and actions.csv. The export is logged as its own entry.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
        Button(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Export to folder") }
        lastExport?.let { Text("Last export: $it", modifier = Modifier.padding(top = 8.dp)) }
        repo.lastRowHash()?.let {
            Section("Hash chain")
            Text("Newest row hash. Note it in your documentation: it shows later whether rows were removed from the end.",
                style = MaterialTheme.typography.bodySmall)
            Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
        Section("Live copy")
        Text(
            "Every entry is also appended to\n${repo.liveFile().absolutePath}\n" +
                "Reach it with adb pull. It survives an app crash, not a factory reset.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
