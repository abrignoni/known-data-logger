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
        val repo = Repo(applicationContext)
        setContent {
            MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                App(repo)
            }
        }
    }
}

private val TABS = listOf("Scenario", "Log", "Timeline", "Export")
private const val NO_SECRETS = "No passwords or passcodes here. Exports get shared."

@Composable
fun App(repo: Repo) {
    var tab by rememberSaveable { mutableIntStateOf(1) }
    // Bumped after every write so screens re-read the database.
    var version by remember { mutableIntStateOf(0) }
    val bump: () -> Unit = { version++ }
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
                    1 -> LogScreen(repo, bump, onExportNeeded = { tab = 3 })
                    2 -> TimelineScreen(repo, bump)
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
                windowStart = Pending(repo.stamp()); toast(ctx, "Start stamped. Tap Done when it ends.")
            } else {
                val end = repo.stamp()
                val d = draft()
                report(repo.log(d, start.stamp, windowS = (end.elapsedMs - start.stamp.elapsedMs) / 1000.0))
                windowStart = null; lastDraft = d; bump()
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

// ---------------- Timeline ----------------

@Composable
fun TimelineScreen(repo: Repo, bump: () -> Unit) {
    val rows = remember { repo.db.entries() }
    val current = remember(rows) { Timeline.current(rows).reversed() }
    val pending = current.count { it.needsDetail }
    var editing by remember { mutableStateOf<Entry?>(null) }
    val labels = remember { repo.db.apps().associate { it.pkg to it.label } }

    Column {
        Section("Timeline, newest first")
        if (pending > 0) Text("$pending entr${if (pending == 1) "y needs" else "ies need"} detail", color = MaterialTheme.colorScheme.error)
        LazyColumn {
            items(current, key = { it.entryId }) { e ->
                Column(Modifier.fillMaxWidth().clickable { editing = e }.padding(vertical = 8.dp)) {
                    Text(
                        "${hhmmss(e.stamp)}  ${if (e.needsDetail) "NEEDS DETAIL" else templateLabel(e.action)}" +
                            (if (e.revisionN > 0) "  (rev ${e.revisionN})" else ""),
                        fontWeight = FontWeight.Medium,
                    )
                    val sub = listOfNotNull(
                        e.kind.takeIf { it != Kind.ACTION },
                        e.appPackage?.let { labels[it] ?: it },
                        e.counterpart?.let { (if (e.direction == "in") "from " else "to ") + it },
                        e.windowS?.let { "window ${"%.0f".format(it)} s" },
                        e.content?.let { "\"$it\"" },
                        e.refersTo?.let { "refers to an earlier entry" },
                        e.detail,
                    ).joinToString(", ")
                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
    editing?.let { e -> EditDialog(repo, e, onDone = { editing = null; bump() }) }
}

@Composable
private fun EditDialog(repo: Repo, e: Entry, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var action by remember { mutableStateOf(e.action) }
    var content by remember { mutableStateOf(e.content ?: "") }
    var counterpart by remember { mutableStateOf(e.counterpart ?: "") }
    var conversation by remember { mutableStateOf(e.conversationId ?: "") }
    var note by remember { mutableStateOf(e.detail ?: "") }
    var quality by remember { mutableStateOf(e.timeQuality) }
    var expected by remember { mutableStateOf(e.expectedArtifacts.joinToString(", ")) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Edit ${hhmmss(e.stamp)}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Edits are saved as a new revision. The action time stays fixed.", style = MaterialTheme.typography.bodySmall)
                Picker(value = action.ifBlank { "Pick a template" }, options = ACTION_TEMPLATES.map { it to templateLabel(it) }, onPick = { action = it })
                OutlinedTextField(action, { action = it }, label = { Text("Action") })
                OutlinedTextField(content, { content = it }, label = { Text("Content") }, supportingText = { Text(NO_SECRETS) })
                OutlinedTextField(counterpart, { counterpart = it }, label = { Text("Counterpart") })
                OutlinedTextField(conversation, { conversation = it }, label = { Text("Conversation") })
                Row { TimeQuality.ALL.forEach { q -> FilterChip(selected = quality == q, onClick = { quality = q }, label = { Text(q) }) } }
                OutlinedTextField(expected, { expected = it }, label = { Text("Expected artifacts") })
                OutlinedTextField(note, { note = it }, label = { Text("Note") }, supportingText = { Text(NO_SECRETS) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        repo.log(Draft(appPackage = e.appPackage, action = "deleted_item", refersTo = e.entryId, counterpart = e.counterpart, conversationId = e.conversationId))
                        toast(ctx, "Deletion logged now, pointing at this entry"); onDone()
                    }) { Text("I deleted this, log it") }
                    TextButton(onClick = { repo.tombstone(e); toast(ctx, "Entry removed (kept as a tombstone)"); onDone() }) { Text("Remove entry") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                repo.revise(e, Draft(
                    appPackage = e.appPackage, action = action.trim(), content = content, counterpart = counterpart,
                    direction = e.direction, conversationId = conversation, timeQuality = quality,
                    expectedArtifacts = expected.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }, detail = note,
                ))
                onDone()
            }) { Text("Save revision") }
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
            try {
                lastExport = repo.exportTo(uri); toast(ctx, "Exported"); bump()
            } catch (e: Exception) {
                toast(ctx, "Export failed: ${e.message}")
            }
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section("Export")
        Text("${current.size} entries, ${rows.size} rows including revisions and system rows.")
        Text("Writes a new folder with manifest.json, actions.jsonl and actions.csv. The export is logged as its own entry.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
        Button(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Export to folder") }
        lastExport?.let { Text("Last export: $it", modifier = Modifier.padding(top = 8.dp)) }
        Section("Live copy")
        Text(
            "Every entry is also appended to\n${repo.liveFile().absolutePath}\n" +
                "Reach it with adb pull. It survives an app crash, not a factory reset.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
