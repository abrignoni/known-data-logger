package com.abrignoni.knowndatalogger

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Show raw captured taps and screens in the timeline; kept across refreshes. */
private val showRaw = mutableStateOf(false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckScreen(repo: Repo, bump: () -> Unit, onAccepted: (List<String>) -> Unit) {
    val rows = remember { repo.db.entries() }
    val shots = remember { repo.db.shots() }
    val pending = remember(rows) { Review.pending(rows) }
    val labels = remember { repo.db.apps().associate { it.pkg to it.label } }
    var editing by remember { mutableStateOf<Entry?>(null) }
    var seg by checkSegment
    var showAllInferred by remember { mutableStateOf(false) }
    fun acceptOne(e: Entry) = repo.revise(e, Draft(appPackage = e.appPackage, action = e.action, content = e.content,
        counterpart = e.counterpart, direction = e.direction, conversationId = e.conversationId,
        timeQuality = e.timeQuality, expectedArtifacts = e.expectedArtifacts, detail = e.detail))
    fun shotFor(e: Entry) = shots[e.entryId] ?: e.refersTo?.let { shots[it] }

    Column(Modifier.fillMaxSize()) {
        AppBar("Check")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            SegmentedButton(selected = seg == 0, onClick = { seg = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                Text("To check · ${pending.size}")
            }
            SegmentedButton(selected = seg == 1, onClick = { seg = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                Text("Timeline")
            }
        }
        if (seg == 0) {
            val inferred = pending.filter { it.kind == Kind.ACTION && it.source == Source.AUTO_RULE && !it.reviewed }
            val marks = pending.filter { it.isMark }
            val locked = pending.filter { it.isWithheldNotification }
            val stamped = pending.filter { it.needsDetail }
            if (pending.isEmpty()) {
                Text("Nothing to check.", color = Kdl.Muted, modifier = Modifier.padding(16.dp))
            }
            // Items only a person can resolve come first; inferred entries, often many, come last.
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                if (marks.isNotEmpty()) {
                    item { SectionLabel("Marks · ${marks.size}") }
                    items(marks, key = { it.entryId }) { e ->
                        CheckCard(repo, e, shotFor(e), labels, icon = null, title = "Mark", body = null,
                            primary = "Label" to { editing = e }, secondary = null)
                    }
                }
                if (locked.isNotEmpty()) {
                    item { SectionLabel("Locked notifications · ${locked.size}") }
                    items(locked, key = { it.entryId }) { e ->
                        CheckCard(repo, e, null, labels, icon = Icons.Outlined.Lock, title = e.content?.let { "\"$it\"" } ?: "Notification",
                            body = null, extraSub = "locked, no details", primary = "Label" to { editing = e }, secondary = null)
                    }
                }
                if (stamped.isNotEmpty()) {
                    item { SectionLabel("No action yet · ${stamped.size}") }
                    items(stamped, key = { it.entryId }) { e ->
                        CheckCard(repo, e, shotFor(e), labels, icon = null, title = "Stamped, no action", body = e.windowS?.let { "Timed ${"%.0f".format(it)} s" },
                            primary = "Describe" to { editing = e }, secondary = null)
                    }
                }
                if (inferred.isNotEmpty()) {
                    item {
                        SectionLabel("Inferred · ${inferred.size}") {
                            TextButton(onClick = {
                                inferred.forEach { acceptOne(it) }
                                onAccepted(inferred.map { it.entryId })
                                bump()
                            }) { Text(if (inferred.size == 1) "Accept" else "Accept all ${inferred.size}") }
                        }
                    }
                    val shown = if (showAllInferred) inferred else inferred.take(3)
                    items(shown, key = { it.entryId }) { e ->
                        CheckCard(repo, e, shotFor(e), labels, icon = null,
                            title = displayLabel(e),
                            body = e.content?.let { "\"$it\"" } ?: e.counterpart,
                            primary = "Accept" to { acceptOne(e); onAccepted(listOf(e.entryId)); bump() },
                            secondary = "Change" to { editing = e })
                    }
                    if (inferred.size > 3) item {
                        TextButton(onClick = { showAllInferred = !showAllInferred }) {
                            Text(if (showAllInferred) "Show fewer" else "Show ${inferred.size - 3} more inferred")
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        } else {
            TimelineList(repo, rows, shots, labels, onEdit = { editing = it })
        }
    }
    editing?.let { e -> EditDialog(repo, e, shotFor(e), onDone = { editing = null; bump() }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckCard(
    repo: Repo, e: Entry, shot: Shot?, labels: Map<String, String>, icon: androidx.compose.ui.graphics.vector.ImageVector?,
    title: String, body: String?, extraSub: String? = null,
    primary: Pair<String, () -> Unit>, secondary: Pair<String, () -> Unit>?,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Kdl.Surface), shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(Modifier.padding(12.dp)) {
            Thumb(repo, shot, icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(e.appPackage?.let { labels[it] ?: BUILTIN_LABELS[it] ?: it }, hms(e.stamp), extraSub).joinToString(" · "),
                    color = Kdl.Muted, style = MaterialTheme.typography.bodySmall)
                body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
                // Buttons sit side by side and stack when there is not room (narrow phones, large text).
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = primary.second, modifier = Modifier.weight(1f).widthIn(min = 100.dp)) { Text(primary.first) }
                    secondary?.let { (label, act) ->
                        OutlinedButton(onClick = act, modifier = Modifier.weight(1f).widthIn(min = 100.dp)) { Text(label) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumb(repo: Repo, shot: Shot?, icon: androidx.compose.ui.graphics.vector.ImageVector?) {
    // Decoded off the main thread so long lists scroll smoothly.
    val bmp by produceState<android.graphics.Bitmap?>(null, shot?.file) {
        value = shot?.file?.let {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                android.graphics.BitmapFactory.decodeFile(java.io.File(repo.screensDir(), it).path,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })
            }
        }
    }
    Box(Modifier.size(width = 50.dp, height = 90.dp).clip(RoundedCornerShape(8.dp)).background(Kdl.Surface2),
        contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), contentDescription = "Screenshot", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(icon ?: Icons.Outlined.Image, null, tint = Kdl.Muted)
    }
}

/** Labels for packages the logger always captures, which are not in the apps list. */
internal val BUILTIN_LABELS = mapOf("com.android.settings" to "Settings", "com.android.systemui" to "System UI")

@Composable
private fun TimelineList(repo: Repo, rows: List<Entry>, shots: Map<String, Shot>, labels: Map<String, String>, onEdit: (Entry) -> Unit) {
    var raw by showRaw
    val current = remember(rows, raw) {
        Timeline.current(rows).filter {
            raw || ((it.kind != Kind.AUTO_EVENT || it.isMark || it.isWithheldNotification) && !it.isCapturePause)
        }.reversed()
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            TextButton(onClick = { raw = !raw }, contentPadding = PaddingValues(0.dp)) {
                Icon(if (raw) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null)
                Text(if (raw) "  Hide captured taps, screens and pauses" else "  Show captured taps, screens and pauses")
            }
        }
        if (current.isEmpty()) item { Text("Nothing recorded yet. Start a session on the Session tab.", color = Kdl.Muted, modifier = Modifier.padding(vertical = 12.dp)) }
        items(current, key = { it.entryId }) { e ->
            val tag = when {
                e.isWithheldNotification -> "locked"
                e.kind == Kind.ACTION && e.source == Source.AUTO_RULE -> if (e.reviewed) "accepted" else "inferred"
                e.kind == Kind.ACTION && e.source in setOf(Source.MANUAL, Source.MARK) -> "hand"
                else -> null
            }
            val title = displayLabel(e)
            val sub = listOfNotNull(
                e.appPackage?.let { labels[it] ?: BUILTIN_LABELS[it] ?: it.substringAfterLast('.') },
                e.counterpart, e.content?.let { "\"$it\"" },
                if (e.revisionN > 0 && e.source != Source.AUTO_RULE) "edited" else null,
                (shots[e.entryId] ?: e.refersTo?.let { shots[it] })?.let { if (it.file != null) "screenshot" else null },
            ).joinToString(" · ")
            Row(Modifier.fillMaxWidth().clickable { onEdit(e) }.heightIn(min = 48.dp).padding(vertical = 10.dp)) {
                Text(hms(e.stamp), fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Kdl.Muted, softWrap = false,
                    modifier = Modifier.padding(top = 2.dp, end = 12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                        tag?.let {
                            Text(it, fontSize = 12.sp, color = Kdl.Muted, modifier = Modifier.padding(start = 6.dp)
                                .background(Kdl.Surface2, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                        }
                    }
                    if (sub.isNotEmpty()) Text(sub, color = Kdl.Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(color = Kdl.Line)
        }
    }
}
