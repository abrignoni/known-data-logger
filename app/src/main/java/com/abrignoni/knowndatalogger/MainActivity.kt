package com.abrignoni.knowndatalogger

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // KDL is always dark, so the system bars always get light icons, whatever the phone's theme.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val repo = Repo.get(applicationContext)
        windowStart = repo.loadWindowStart()?.let { Pending(it) }
        setContent { KdlTheme { App(repo) } }
    }

    override fun onResume() { super.onResume(); kdlInFront = true; CaptureService.instance?.kdlVisibilityChanged(true) }
    override fun onPause() { kdlInFront = false; CaptureService.instance?.kdlVisibilityChanged(false); super.onPause() }
}

internal data class Pending(val stamp: Stamp)

/** True while KDL's own screen is in front; the capture service reads it when it shows MARK. */
@Volatile internal var kdlInFront = false

/** Start stamp of an open Start/Done timing; kept in the database so a killed app keeps it. */
internal var windowStart: Pending? by mutableStateOf(null)
internal var lastDraft: Draft? = null
internal var lastExport: String? by mutableStateOf(null)

/** Which part of Check is showing: 0 to check, 1 timeline. Kept across refreshes. */
internal val checkSegment = mutableIntStateOf(0)

/** Time of day with seconds, in the offset recorded with the stamp. */
internal fun hms(s: Stamp): String =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.ofTotalSeconds(s.tzOffsetMin * 60))
        .format(Instant.ofEpochMilli(s.utcMs))

internal fun duration(ms: Long): String {
    val t = ms / 1000
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

/** "Today", "Yesterday" or the date, for a session's start. */
internal fun dayWord(s: Stamp): String {
    val zone = ZoneOffset.ofTotalSeconds(s.tzOffsetMin * 60)
    val day = Instant.ofEpochMilli(s.utcMs).atOffset(zone).toLocalDate()
    val today = Instant.now().atOffset(zone).toLocalDate()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "MMM d" else "MMM d, yyyy"))
    }
}

/** Time of day, with the day in front when the stamp is not from today: "Yesterday 14:02:11". */
internal fun whenWords(s: Stamp): String = dayWord(s).let { if (it == "Today") hms(s) else "$it ${hms(s)}" }

/** A labelled field that opens a menu, used where a value is picked from a list. */
@Composable
internal fun <T> FieldPicker(label: String, value: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        OutlinedTextField(value, {}, readOnly = true, label = { Text(label) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null) }, modifier = Modifier.fillMaxWidth())
        // The text field is read-only; this transparent layer takes the tap and opens the menu.
        Box(Modifier.matchParentSize().clip(RoundedCornerShape(4.dp)).clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { onPick(v); open = false }) }
        }
    }
}

internal fun durationWords(ms: Long): String {
    val t = ms / 1000
    return when {
        t >= 3600 -> "${t / 3600} h ${t / 60 % 60} min"
        t >= 60 -> "${t / 60} min ${t % 60} s"
        else -> "$t s"
    }
}

@Composable
fun App(repo: Repo) {
    var version by remember { mutableIntStateOf(0) }
    val bump: () -> Unit = { version++ }
    // Re-read when the app comes back to the front: capture writes while it is away.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { version++; onPauseOrDispose { } }

    var onboarded by remember { mutableStateOf(repo.onboarded) }
    if (!onboarded) {
        Onboarding(repo, onDone = { onboarded = true; bump() })
        return
    }

    var tab by remember { mutableIntStateOf(0) }
    val pending = remember(version) { repo.pendingCounts() }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val onAccepted: (List<String>) -> Unit = { ids ->
        scope.launch {
            val r = snack.showSnackbar("Accepted ${plural(ids.size, "entry", "entries")}", actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) {
                Timeline.current(repo.db.entries()).filter { it.entryId in ids }.forEach { repo.unaccept(it) }
                bump()
            }
        }
    }
    Scaffold(
        containerColor = Kdl.Bg,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar(containerColor = Kdl.Surface) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Outlined.RadioButtonChecked, null) }, label = { Text("Session") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = {
                        BadgedBox(badge = { if (pending.total > 0) Badge(containerColor = Kdl.Mark) { Text("${pending.total}") } }) {
                            Icon(Icons.Outlined.Checklist, null)
                        }
                    }, label = { Text("Check") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Outlined.Settings, null) }, label = { Text("Setup") })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            key(version) {
                when (tab) {
                    0 -> SessionScreen(repo, bump, openCheck = { seg -> checkSegment.intValue = seg; tab = 1 })
                    1 -> CheckScreen(repo, bump, onAccepted)
                    else -> SetupScreen(repo, bump)
                }
            }
        }
    }
}

@Composable
internal fun AppBar(title: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
internal fun SectionLabel(text: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Kdl.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        trailing()
    }
}

/**
 * Export actions: [run] exports to the folder picked last, or opens the picker when there is none;
 * [pick] always opens the picker; [folder] names the remembered folder.
 */
internal class Exporter(val run: () -> Unit, val pick: () -> Unit, val folder: String?)

/** Exports off the main thread, into the remembered folder or one picked in the system folder picker. */
@Composable
internal fun rememberExporter(repo: Repo, bump: () -> Unit): Exporter {
    val ctx = LocalContext.current
    fun export(uri: android.net.Uri) {
        toast(ctx, "Exporting")
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val result = runCatching { repo.exportTo(uri) }
            main.post {
                result.onSuccess { lastExport = it; toast(ctx, "Exported") }
                    .onFailure { toast(ctx, "Export failed: ${it.message}") }
                bump()
            }
        }.start()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { repo.rememberExportTree(uri); export(uri) }
    }
    val saved = remember { repo.exportTree() }
    return Exporter(
        // Checked again at the tap: the folder can be deleted while this screen is open.
        run = { repo.exportTree()?.let { export(it) } ?: picker.launch(null) },
        pick = { picker.launch(null) },
        folder = saved?.let { treeFolderName(android.provider.DocumentsContract.getTreeDocumentId(it)) },
    )
}

// ---------------- Session ----------------

/** Chips for logging by hand: template key and label. */
internal val QUICK = listOf(
    "sent_message" to "Sent message", "received_message" to "Received message",
    "outgoing_call" to "Outgoing call", "incoming_call" to "Incoming call",
    "took_photo" to "Took photo", "deleted_message" to "Deleted message",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionScreen(repo: Repo, bump: () -> Unit, openCheck: (Int) -> Unit) {
    val ctx = LocalContext.current
    val active = repo.sessionActive
    var enabled by remember { mutableStateOf(serviceEnabled(ctx)) }
    val apps = remember { repo.db.apps() }
    val appNames = apps.joinToString(", ") { it.label }.ifBlank { "no apps picked" }
    var last by remember { mutableStateOf(repo.lastSession()) }
    var stopSheet by remember { mutableStateOf<Pair<SessionSummary?, PendingCounts>?>(null) }
    var logSheet by remember { mutableStateOf<Pair<String, Stamp>?>(null) }
    val export = rememberExporter(repo, bump)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppBar("KDL") { Text(repo.scenarioId, color = Kdl.Muted, style = MaterialTheme.typography.bodyMedium) }
        Column(Modifier.padding(horizontal = 16.dp)) {
            if (active) {
                val start = remember { repo.sessionStart() }
                var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
                LaunchedEffect(Unit) {
                    var n = 0
                    while (true) {
                        delay(1000); now = android.os.SystemClock.elapsedRealtime()
                        if (++n % 3 == 0) { last = repo.lastSession(); enabled = serviceEnabled(ctx) }   // event count and capture state, every 3 s
                    }
                }
                Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (enabled) Kdl.Mark else Kdl.Amber))
                        Spacer(Modifier.width(8.dp))
                        Text(if (enabled) "Recording" else "Session running, capture is off", color = if (enabled) Kdl.Muted else Kdl.Amber)
                    }
                    val ms = start?.let { if (it.bootCount == Clocks.bootCount(ctx.contentResolver)) now - it.elapsedMs else null }
                    Text(ms?.let { duration(it) } ?: "--:--", fontSize = 57.sp, modifier = Modifier.padding(top = 4.dp))
                    Text(appNames, color = Kdl.Muted)
                }
                if (!enabled) {
                    // The session flag outlives the service: without this, the timer keeps running over nothing.
                    Card(colors = CardDefaults.cardColors(containerColor = Kdl.Surface), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Capture is off", color = Kdl.Amber, style = MaterialTheme.typography.titleMedium)
                            Text("KDL is switched off in Accessibility settings. Nothing is being captured and MARK is hidden. " +
                                "Log by hand still works.", color = Kdl.Muted, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                            FilledTonalButton(onClick = { ctx.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                                modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Outlined.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Open Accessibility settings")
                            }
                        }
                    }
                }
                Button(
                    onClick = {
                        repo.stopSession(); CaptureService.instance?.syncSession()
                        last = repo.lastSession()
                        stopSheet = last to repo.pendingCounts()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Kdl.Stop, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(56.dp),
                ) { Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop session", fontSize = 16.sp) }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(plural(last?.events ?: 0, "event"), color = Kdl.Muted, modifier = Modifier.weight(1f))
                    TextButton(onClick = { openCheck(1) }) { Text("Timeline"); Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) }
                }
                windowStart?.let { w ->
                    Card(colors = CardDefaults.cardColors(containerColor = Kdl.Surface), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Timing since ${hms(w.stamp)}", modifier = Modifier.weight(1f))
                            Button(onClick = { logSheet = "window_done" to w.stamp }) { Text("Done") }
                        }
                    }
                }
                SectionLabel("Log by hand")
                FlowRow {
                    QUICK.forEach { (key, label) ->
                        AssistChip(onClick = { logSheet = key to repo.stamp() }, label = { Text(label) }, modifier = Modifier.padding(end = 8.dp))
                    }
                    AssistChip(onClick = { logSheet = "" to repo.stamp() }, label = { Text("Other") },
                        leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)) }, modifier = Modifier.padding(end = 8.dp))
                    lastDraft?.let { d ->
                        AssistChip(onClick = {
                            repo.log(d); toast(ctx, "Logged again: ${templateLabel(d.action)}"); bump()
                        }, label = { Text("Repeat ${templateLabel(d.action)}") },
                            leadingIcon = { Icon(Icons.Outlined.Replay, null, Modifier.size(18.dp)) })
                    }
                }
            } else {
                Text(
                    if (enabled) "Capture is on · $appNames" else "Capture is off. Turn on KDL in Accessibility settings.",
                    color = if (enabled) Kdl.Muted else Kdl.Amber, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 20.dp),
                )
                if (enabled) {
                    Button(
                        onClick = { repo.startSession(); CaptureService.instance?.syncSession(); bump() },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(56.dp),
                    ) { Icon(Icons.Filled.FiberManualRecord, null); Spacer(Modifier.width(8.dp)); Text("Start session", fontSize = 16.sp) }
                } else {
                    Button(onClick = { ctx.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(56.dp)) {
                        Icon(Icons.Outlined.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Open Accessibility settings", fontSize = 16.sp)
                    }
                }
                last?.let { s ->
                    SectionLabel("Last session")
                    Card(colors = CardDefaults.cardColors(containerColor = Kdl.Surface), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("${dayWord(s.start)} ${hms(s.start).dropLast(3)} to ${s.end?.let { hms(it).dropLast(3) } ?: "now"}", style = MaterialTheme.typography.titleMedium)
                            Text(listOfNotNull(s.durationMs?.let { durationWords(it) }, plural(s.events, "event"), plural(s.screenshots, "screenshot")).joinToString(" · "),
                                color = Kdl.Muted, style = MaterialTheme.typography.bodyMedium)
                            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val p = repo.pendingCounts().total
                                FilledTonalButton(onClick = { openCheck(0) }, modifier = Modifier.weight(1f)) { Text(if (p > 0) "Check $p" else "Check") }
                                FilledTonalButton(onClick = { openCheck(1) }, modifier = Modifier.weight(1f)) { Text("Timeline") }
                            }
                        }
                    }
                }
                SectionLabel("Export")
                OutlinedButton(onClick = export.run, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.FileUpload, null); Spacer(Modifier.width(8.dp)); Text("Export everything")
                }
                Text("Exports every session on this phone, with screenshots and the hash chain." +
                    (lastExport?.let { "\nLast export: $it" } ?: ""),
                    color = Kdl.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                export.folder?.let { f ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Goes to $f", color = Kdl.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = export.pick) { Text("Change folder") }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    stopSheet?.let { (s, p) ->
        ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = { stopSheet = null; bump() }, containerColor = Kdl.Surface) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
                Text("Session stopped", style = MaterialTheme.typography.headlineSmall)
                Text(listOfNotNull(s?.durationMs?.let { durationWords(it) }, s?.let { plural(it.events, "event") }, s?.let { plural(it.screenshots, "screenshot") })
                    .joinToString(" · "), color = Kdl.Muted, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
                if (p.total > 0) {
                    Text("${plural(p.total, "item")} to check", style = MaterialTheme.typography.titleMedium)
                    val fromThis = s?.let { ss -> repo.pendingInSession(ss.start) } ?: p.total
                    if (fromThis < p.total) Text("$fromThis from this session, ${p.total - fromThis} from earlier sessions",
                        color = Kdl.Muted, style = MaterialTheme.typography.bodyMedium)
                    Text(listOfNotNull(
                        p.inferred.takeIf { it > 0 }?.let { "$it inferred" }, p.marks.takeIf { it > 0 }?.let { "$it ${if (it == 1) "mark" else "marks"}" },
                        p.locked.takeIf { it > 0 }?.let { "$it locked ${if (it == 1) "notification" else "notifications"}" },
                        p.stamped.takeIf { it > 0 }?.let { "$it without an action" },
                    ).joinToString(", "), color = Kdl.Muted, modifier = Modifier.padding(top = 2.dp, bottom = 20.dp))
                    Button(onClick = { stopSheet = null; openCheck(0) }, modifier = Modifier.fillMaxWidth()) { Text("Check now") }
                } else {
                    Text("Nothing to check.", color = Kdl.Muted, modifier = Modifier.padding(bottom = 20.dp))
                }
                OutlinedButton(onClick = { stopSheet = null; export.run() }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Icon(Icons.Outlined.FileUpload, null); Spacer(Modifier.width(8.dp)); Text("Export")
                }
                export.folder?.let { Text("Goes to $it", color = Kdl.Muted, style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) }
                TextButton(onClick = { stopSheet = null; bump() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("Later") }
            }
        }
    }

    logSheet?.let { (key, stamp) ->
        LogSheet(repo, key, stamp, onDone = { logSheet = null; bump() })
    }
}

/**
 * Log by hand: the time is the moment the chip was tapped. The common fields show first;
 * the rest sit behind More. key "" is Other (pick a template), "window_done" closes a timing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogSheet(repo: Repo, key: String, stamp: Stamp, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val apps = remember { repo.db.apps() }
    val closing = key == "window_done"
    var action by remember { mutableStateOf(if (closing) (lastDraft?.action ?: "") else key) }
    var app by remember { mutableStateOf(defaultLogApp(CaptureService.lastTestApp, lastDraft?.appPackage, apps.map { it.pkg })) }
    var content by remember { mutableStateOf("") }
    var counterpart by remember { mutableStateOf("") }
    var conversation by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var expected by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf(TimeQuality.EXACT) }
    var more by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val direction = directionFor(action)

    fun draft() = Draft(appPackage = app, action = action.trim(), content = content, counterpart = counterpart,
        direction = direction, conversationId = conversation, timeQuality = quality,
        expectedArtifacts = expected.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }, detail = note)

    fun save() {
        val d = draft()
        if (closing) {
            val w = windowStart ?: return
            val end = repo.stamp()
            repo.log(d, w.stamp, windowS = (end.elapsedMs - w.stamp.elapsedMs) / 1000.0, detect = false)
            repo.saveWindowStart(null); windowStart = null
        } else {
            repo.log(d, stamp)
        }
        lastDraft = d
        toast(ctx, "Logged")
        onDone()
    }

    ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = onDone, containerColor = Kdl.Surface) {
        Column(Modifier.navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            Text(if (action.isBlank()) "Log an action" else templateLabel(action).replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleLarge)
            Text(if (closing) "From ${hms(windowStart?.stamp ?: stamp)} to now" else "Time ${hms(stamp)}, stamped when you tapped",
                color = Kdl.Muted, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))
            if (key == "" && !closing) {
                FieldPicker("Action", value = action.ifBlank { "Pick an action" }.let { templateLabel(it) },
                    options = ACTION_TEMPLATES.map { it to templateLabel(it) }, onPick = { action = it })
                OutlinedTextField(action, { action = it }, label = { Text("Or type an action") }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }
            FieldPicker("App", value = apps.firstOrNull { it.pkg == app }?.label ?: "No app (device)",
                options = listOf<Pair<String?, String>>(null to "No app (device)") + apps.map { it.pkg to it.label }, onPick = { app = it })
            OutlinedTextField(content, { content = it }, label = { Text("Content") }, supportingText = { Text(NO_SECRETS) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(counterpart, { counterpart = it }, label = { Text(counterpartLabel(direction)) }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { more = !more }, contentPadding = PaddingValues(0.dp)) {
                Icon(if (more) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                Text(if (more) "Less" else "More: conversation, note, expected artifacts, timing")
            }
            if (more) {
                OutlinedTextField(conversation, { conversation = it }, label = { Text("Conversation") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(expected, { expected = it }, label = { Text("Expected artifacts, e.g. aleapp:key") }, modifier = Modifier.fillMaxWidth())
                Text("The tap was", color = Kdl.Muted, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TimeQuality.ALL.forEach { q -> FilterChip(selected = quality == q, onClick = { quality = q }, label = { Text(q) }) }
                }
                if (!closing && windowStart == null) TextButton(onClick = {
                    val s = repo.stamp(); repo.saveWindowStart(s); windowStart = Pending(s)
                    lastDraft = draft(); toast(ctx, "Timing started. Tap Done on the Session screen when it ends."); onDone()
                }, contentPadding = PaddingValues(0.dp)) { Icon(Icons.Outlined.Timer, null); Text(" Start timing instead (calls, recordings)") }
            }
            Button(enabled = action.isNotBlank(), onClick = { if (action.trim() == FACTORY_RESET) confirmReset = true else save() },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Save") }
            TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Factory reset") },
        text = { Text("A reset erases this log on the phone. Save this entry, then export and copy the export off the phone before resetting.") },
        confirmButton = { TextButton(onClick = { confirmReset = false; save() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
}
