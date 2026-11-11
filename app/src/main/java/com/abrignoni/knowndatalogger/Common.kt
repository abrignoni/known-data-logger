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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Shared pieces of the interface: pickers, the screenshot thumbnail and the edit or label dialog. */

internal const val NO_SECRETS = "No passwords or passcodes here. Exports get shared."

internal fun toast(ctx: android.content.Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

internal fun hhmmss(s: Stamp): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneOffset.ofTotalSeconds(s.tzOffsetMin * 60))
        .format(Instant.ofEpochMilli(s.utcMs))

@Composable
internal fun <T> Picker(value: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(value) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { onPick(v); open = false }) }
        }
    }
}

@Composable
internal fun AppPicker(repo: Repo, onDone: () -> Unit) {
    val all = remember { repo.launcherApps() }
    var chosen by remember { mutableStateOf<Pair<String, String>?>(null) }
    var account by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (chosen == null) "Pick an app" else chosen!!.second) },
        text = {
            if (chosen == null) {
                Column {
                    OutlinedTextField(query, { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    val shown = filterApps(all, query)
                    if (shown.isEmpty()) Text("No app matches.", color = Kdl.Muted, modifier = Modifier.padding(vertical = 12.dp))
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(shown, key = { it.first }) { p ->
                            Column(Modifier.fillMaxWidth().clickable { chosen = p }.padding(vertical = 8.dp)) {
                                Text(p.second); Text(p.first, style = MaterialTheme.typography.bodySmall)
                            }
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

internal fun serviceEnabled(ctx: android.content.Context): Boolean {
    val enabled = android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
    return enabled.split(':').any { it.equals("${ctx.packageName}/${CaptureService::class.java.name}", true) || it.equals("${ctx.packageName}/.CaptureService", true) }
}

@Composable
internal fun ShotThumb(repo: Repo, shot: Shot?) {
    if (shot?.file == null) { shot?.let { Text("Screenshot: ${it.status}", style = MaterialTheme.typography.bodySmall) }; return }
    val bmp by produceState<android.graphics.Bitmap?>(null, shot.file) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            android.graphics.BitmapFactory.decodeFile(java.io.File(repo.screensDir(), shot.file).path,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })
        }
    }
    bmp?.let { androidx.compose.foundation.Image(it.asImageBitmap(), contentDescription = "Screenshot",
        modifier = Modifier.height(180.dp).padding(vertical = 4.dp)) }
    if (shot.status != "ok") Text("Screenshot: ${shot.status}", style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun EditDialog(repo: Repo, e: Entry, shot: Shot?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val labellingMark = e.isMark
    val rawEvent = e.kind == Kind.AUTO_EVENT && !e.isMark
    var action by remember { mutableStateOf(if (labellingMark || rawEvent) "" else e.action) }
    // The app can be corrected here: removing and logging again would lose the entry's time.
    val apps = remember { repo.db.apps() }
    var app by remember { mutableStateOf(e.appPackage) }
    // KDL's own rows (session start and stop, snapshots) keep their fields as written.
    val ownRow = e.kind != Kind.ACTION && e.kind != Kind.AUTO_EVENT
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
    val inferred = e.source == Source.AUTO_RULE && !e.reviewed
    fun accept() {
        repo.revise(e, Draft(appPackage = e.appPackage, action = e.action, content = e.content, counterpart = e.counterpart,
            direction = e.direction, conversationId = e.conversationId, timeQuality = e.timeQuality,
            expectedArtifacts = e.expectedArtifacts, detail = e.detail))
        onDone()
    }
    fun save() {
        if (labellingMark || rawEvent) {
            if (action.isBlank()) { toast(ctx, "Pick or type an action first"); return }
            repo.log(Draft(appPackage = app, action = action.trim(), content = content, counterpart = counterpart,
                direction = directionFor(action), conversationId = conversation, timeQuality = quality, expectedArtifacts = expectedList(), detail = note,
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
                appPackage = app, action = action.trim(), content = content, counterpart = counterpart,
                // Rows inferred before directions were set on rule matches have none; fill it from the action.
                direction = if (ownRow) e.direction else directionAfterEdit(e.action, e.direction, action),
                conversationId = conversation, timeQuality = quality,
                expectedArtifacts = expectedList(), detail = note,
            ))
        }
        onDone()
    }
    val title = when {
        labellingMark -> "Label this mark"
        e.isWithheldNotification -> "Label this notification"
        rawEvent -> "Label this event"
        inferred -> "Check this entry"
        else -> "Edit entry"
    }
    ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = onDone, containerColor = Kdl.Surface) {
        Column(Modifier.navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                when {
                    labellingMark || rawEvent -> "${hms(e.stamp)} · saving adds an action at this moment, pointing at the captured event"
                    inferred -> "${hms(e.stamp)} · inferred by ${e.detail?.removePrefix("auto rule ")?.removePrefix("auto: ") ?: "a rule"}"
                    else -> "${hms(e.stamp)} · edits are kept as revisions; the time stays fixed"
                },
                color = Kdl.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
            if (rawEvent && !e.isWithheldNotification) Text("Captured: ${templateLabel(e.action)} ${e.content?.let { "\"$it\"" } ?: ""}",
                color = Kdl.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
            ShotThumb(repo, shot)
            // The common actions one tap away; the full list is in the field below.
            if (labellingMark || rawEvent || e.needsDetail) FlowRow(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QUICK.forEach { (key, label) -> FilterChip(selected = action == key, onClick = { action = key }, label = { Text(label) }) }
            }
            FieldPicker("Action", value = action.ifBlank { "Pick an action" }.let { templateLabel(it) },
                options = ACTION_TEMPLATES.map { it to templateLabel(it) }, onPick = { action = it })
            OutlinedTextField(action, { action = it }, label = { Text("Or type an action") }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            if (!ownRow) {
                // An app that is not on the Setup list (System UI, or one seen under Capture all apps) stays pickable.
                val known = apps.map { it.pkg to it.label }
                val extra = listOfNotNull(e.appPackage?.takeIf { p -> known.none { it.first == p } }?.let { it to (BUILTIN_LABELS[it] ?: it) })
                FieldPicker("App", value = (known + extra).firstOrNull { it.first == app }?.second ?: "No app (device)",
                    options = listOf<Pair<String?, String>>(null to "No app (device)") + known + extra, onPick = { app = it })
            }
            val shownDirection = if (labellingMark || rawEvent) directionFor(action) else directionAfterEdit(e.action, e.direction, action)
            OutlinedTextField(content, { content = it }, label = { Text("Content") }, supportingText = { Text(NO_SECRETS) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(counterpart, { counterpart = it }, label = { Text(if (ownRow) "Counterpart" else counterpartLabel(shownDirection)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(conversation, { conversation = it }, label = { Text("Conversation") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(expected, { expected = it }, label = { Text("Expected artifacts") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            OutlinedTextField(note, { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            Text("The time is", color = Kdl.Muted, modifier = Modifier.padding(top = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TimeQuality.ALL.forEach { q -> FilterChip(selected = quality == q, onClick = { quality = q }, label = { Text(q) }) }
            }
            teachSource?.let { src ->
                val what = if (src.action == AutoAction.FIELD_CLEARED) "cleared field" else "tap"
                val target = viewIdOf(src) ?: "\"${src.content}\""
                Row(Modifier.fillMaxWidth().clickable { teach = !teach }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(teach, { teach = it }); Text("Make a rule from this $what ($target)")
                }
                if (teach) Row(Modifier.fillMaxWidth().clickable { teachUseTyped = !teachUseTyped }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(teachUseTyped, { teachUseTyped = it }); Text("Use the text typed before it as content")
                }
            }
            if (inferred) Button(onClick = { accept() }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Accept as is") }
            val saveLabel = if (labellingMark || rawEvent) "Save label" else if (inferred) "Save with changes" else "Save revision"
            if (inferred) OutlinedButton(onClick = { save() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(saveLabel) }
            else Button(onClick = { save() }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(saveLabel) }
            if (!labellingMark && !rawEvent) TextButton(onClick = {
                repo.log(Draft(appPackage = e.appPackage, action = "deleted_item", refersTo = e.entryId, counterpart = e.counterpart, conversationId = e.conversationId))
                toast(ctx, "Deletion logged now, pointing at this entry"); onDone()
            }, modifier = Modifier.fillMaxWidth()) { Text("I deleted this, log the deletion") }
            TextButton(onClick = { repo.tombstone(e); toast(ctx, "Removed (kept as a tombstone)"); onDone() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (labellingMark) "Discard mark" else "Remove entry", color = Kdl.Mark)
            }
            TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}
