package com.abrignoni.knowndatalogger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Which Setup page is open; kept across refreshes. */
private val setupPage = mutableStateOf<String?>(null)

/**
 * Rules read from a picked file, waiting for a yes: what each would do, how many were unusable, the
 * file's name. Kept outside the page because coming back from the file picker refreshes the screens.
 */
private val incomingRules = mutableStateOf<Triple<List<RuleImportItem>, Int, String?>?>(null)

@Composable
fun SetupScreen(repo: Repo, bump: () -> Unit) {
    var page by setupPage
    if (page != null) BackHandler { page = null }
    when (page) {
        "scenario" -> ScenarioPage(repo, back = { page = null; bump() })
        "apps" -> AppsPage(repo, bump, back = { page = null; bump() })
        "rules" -> RulesPage(repo, bump, back = { page = null; bump() })
        "device" -> DevicePage(repo, bump, back = { page = null; bump() })
        else -> SetupHome(repo, bump, open = { page = it })
    }
}

@Composable
private fun NavRow(title: String, sub: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            sub?.let { Text(it, color = Kdl.Muted, style = MaterialTheme.typography.bodyMedium) }
        }
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Kdl.Muted) }
    }
    HorizontalDivider(color = Kdl.Line)
}

@Composable
private fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.heightIn(min = 56.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            sub?.let { Text(it, color = Kdl.Muted, style = MaterialTheme.typography.bodyMedium) }
        }
        Switch(checked, onChange)
    }
    HorizontalDivider(color = Kdl.Line)
}

@Composable
private fun SetupHome(repo: Repo, bump: () -> Unit, open: (String) -> Unit) {
    val ctx = LocalContext.current
    var maskCodes by remember { mutableStateOf(repo.flag(Repo.KEY_MASK_CODES, true)) }
    var shotAuto by remember { mutableStateOf(repo.flag(Repo.KEY_SHOT_AUTO, true)) }
    var shotTap by remember { mutableStateOf(repo.flag(Repo.KEY_SHOT_TAP, false)) }
    var saveTree by remember { mutableStateOf(repo.flag(Repo.KEY_TREE, true)) }
    val apps = remember { repo.db.apps() }
    val profile = remember { repo.db.profiles().lastOrNull() }
    val hash = remember { repo.lastRowHash() }
    val enabled = serviceEnabled(ctx)
    var startOver by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppBar("Setup")
        Column(Modifier.padding(horizontal = 16.dp)) {
            NavRow("Capture", if (enabled) "On" else "Off · open Accessibility settings") {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            NavRow("Scenario", "${repo.scenarioId} · ${repo.deviceLabel}") { open("scenario") }
            NavRow("Apps", (if (repo.flag(Repo.KEY_ALL_APPS, false)) "All apps" else apps.joinToString(", ") { it.label }).ifBlank { "None picked" }) { open("apps") }
            SwitchRow("Hide verification codes", "In notifications, screens and taps", maskCodes) { maskCodes = it; repo.setFlag(Repo.KEY_MASK_CODES, it) }
            SwitchRow("Screenshot inferred actions", null, shotAuto) { shotAuto = it; repo.setFlag(Repo.KEY_SHOT_AUTO, it) }
            SwitchRow("Screenshot every tap", null, shotTap) { shotTap = it; repo.setFlag(Repo.KEY_SHOT_TAP, it) }
            SwitchRow("Save the view tree with screenshots", "The screen's text as the app reports it. Password fields are left empty; other text is kept as shown", saveTree) { saveTree = it; repo.setFlag(Repo.KEY_TREE, it) }
            if (repo.sessionActive) Text("Changes apply when the next session starts.", color = Kdl.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            NavRow("Rules", "${repo.loadRules().size}") { open("rules") }
            NavRow("Device", profile?.let { "${it.manufacturer?.replaceFirstChar { c -> c.uppercase() }} ${it.model} · Android ${it.release}" } ?: "No snapshot yet") { open("device") }
            NavRow("Start over", if (repo.sessionActive) "Stop the session first" else "Erase the log. Apps, rules and options stay") {
                when {
                    repo.sessionActive -> toast(ctx, "Stop the session first")
                    exportProgress != null -> toast(ctx, "Wait for the export to finish")
                    else -> startOver = true
                }
            }
            hash?.let { h ->
                SectionLabel("Latest row hash")
                Row(verticalAlignment = Alignment.Top) {
                    Text(h, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f).padding(top = 12.dp))
                    IconButton(onClick = {
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("KDL row hash", h))
                        toast(ctx, "Hash copied")
                    }) { Icon(Icons.Outlined.ContentCopy, "Copy hash", tint = Kdl.Muted) }
                }
                Text("Note it with your documentation; it shows later whether rows were removed.",
                    color = Kdl.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, bottom = 24.dp))
            }
        }
    }
    if (startOver) StartOverDialog(repo, onDone = { erased -> startOver = false; if (erased) bump() })
}

/** Confirms an erase: says what goes, what stays and what was not exported yet, and takes the new scenario id. */
@Composable
private fun StartOverDialog(repo: Repo, onDone: (erased: Boolean) -> Unit) {
    val ctx = LocalContext.current
    val rows = remember { repo.db.entries() }
    val shots = remember { repo.db.shots().values.count { it.file != null } }
    val since = remember(rows) { rowsSinceLastExport(rows) }
    var scenario by remember { mutableStateOf(repo.scenarioId) }
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text("Start over") },
        text = {
            Column {
                Text("Erases the log on this phone: ${plural(rows.size, "row")}, ${plural(shots, "screenshot")}, the device snapshots and the live copy. " +
                    "The app list, rules, options and export folder stay. This cannot be undone.")
                Text(
                    when (since) {
                        null -> "This log was never exported."
                        0 -> "Nothing was added since the last export."
                        else -> "${plural(since, "row")} added since the last export."
                    },
                    color = if (since == 0) Kdl.Muted else Kdl.Amber, modifier = Modifier.padding(top = 10.dp),
                )
                OutlinedTextField(scenario, { scenario = it }, label = { Text("Scenario id for the new log") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = scenario.isNotBlank(), onClick = {
                try {
                    repo.startOver(scenario)
                    windowStart = null; lastDraft = null; lastExport = null
                    toast(ctx, "Log erased. A new log started.")
                    onDone(true)
                } catch (ex: IllegalStateException) { toast(ctx, ex.message ?: "Not erased"); onDone(false) }
            }) { Text("Erase the log", color = Kdl.Mark) }
        },
        dismissButton = { TextButton(onClick = { onDone(false) }) { Text("Cancel") } },
    )
}

@Composable
private fun PageBar(title: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun ScenarioPage(repo: Repo, back: () -> Unit) {
    val ctx = LocalContext.current
    var scenario by remember { mutableStateOf(repo.scenarioId) }
    var device by remember { mutableStateOf(repo.deviceLabel) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageBar("Scenario", back)
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(scenario, { scenario = it }, label = { Text("Scenario id, same on every phone") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(device, { device = it }, label = { Text("Device label, e.g. phone A") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Button(enabled = scenario.isNotBlank() && device.isNotBlank(), onClick = {
                repo.scenarioId = scenario; repo.deviceLabel = device; toast(ctx, "Saved"); back()
            }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("Save") }
        }
    }
}

@Composable
private fun AppsPage(repo: Repo, bump: () -> Unit, back: () -> Unit) {
    var apps by remember { mutableStateOf(repo.db.apps()) }
    var allApps by remember { mutableStateOf(repo.flag(Repo.KEY_ALL_APPS, false)) }
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageBar("Apps", back)
        Column(Modifier.padding(horizontal = 16.dp)) {
            apps.forEach { a ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.label, style = MaterialTheme.typography.bodyLarge)
                        Text(listOfNotNull(a.versionName, a.account?.let { "account $it" }).joinToString(" · "), color = Kdl.Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = { repo.db.removeApp(a.pkg); apps = repo.db.apps() }) { Text("Remove") }
                }
                HorizontalDivider(color = Kdl.Line)
            }
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Add app") }
            OutlinedButton(onClick = {
                val have = apps.map { it.pkg }.toSet()
                repo.launcherApps().filter { it.first !in have }.forEach { repo.addApp(it.first, it.second, null) }
                apps = repo.db.apps(); bump()
            }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Add all apps") }
            SwitchRow("Capture all apps", "Not only the apps above", allApps) { allApps = it; repo.setFlag(Repo.KEY_ALL_APPS, it) }
            Text("System UI and Settings are always captured. Changes apply when the next session starts.",
                color = Kdl.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
    }
    if (picking) AppPicker(repo, onDone = { picking = false; apps = repo.db.apps(); bump() })
}

@Composable
private fun RulesPage(repo: Repo, bump: () -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    var rules by remember { mutableStateOf(repo.loadRules()) }
    var local by remember { mutableStateOf(repo.localRuleIds()) }
    var incoming by incomingRules
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try {
            ctx.contentResolver.openOutputStream(uri, "w")!!.use { it.write(repo.localRulesJson().toByteArray(Charsets.UTF_8)) }
            toast(ctx, "Rules exported")
        } catch (ex: Exception) { toast(ctx, ex.message ?: "Rules not exported") }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            // A rules file is small. Anything past the cap is not read into memory.
            val cap = 512 * 1024
            val bytes = ctx.contentResolver.openInputStream(uri)!!.use { inp ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
                while (out.size() <= cap) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                out.toByteArray()
            }
            require(bytes.size <= cap) { "That file is too large to be a rules file" }
            val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
            val read = readRulesForImport(String(bytes, Charsets.UTF_8))
            if (read.rules.isEmpty()) toast(ctx, "No rule in that file can be used")
            else incoming = Triple(planRuleImport(read.rules, repo.loadRules()), read.skipped, name)
        } catch (ex: IllegalArgumentException) { toast(ctx, ex.message ?: "Not a KDL rules file") }
        catch (ex: Exception) { toast(ctx, "Could not read that file") }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageBar("Rules", back)
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) { Text("Import rules") }
                OutlinedButton(enabled = local.isNotEmpty(), onClick = { exporter.launch("kdl-rules.json") }, modifier = Modifier.weight(1f)) { Text("Export rules") }
            }
            rules.forEach { r ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${templateLabel(r.action).replaceFirstChar { it.uppercase() }} · ${BUILTIN_LABELS[r.pkg] ?: r.pkg.substringAfterLast('.')}",
                            style = MaterialTheme.typography.bodyLarge)
                        Text("${if (r.event == RuleEvent.TAP) "Tap" else "Field cleared"} · ${if (r.id in local) "taught or imported" else "built in"}",
                            color = Kdl.Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (r.id in local) TextButton(onClick = {
                        try { repo.removeLocalRule(r.id); CaptureService.instance?.reloadRules() }
                        catch (ex: IllegalStateException) { toast(ctx, ex.message ?: "Rule not removed") }
                        rules = repo.loadRules(); local = repo.localRuleIds(); bump()
                    }) { Text("Remove") }
                }
                HorizontalDivider(color = Kdl.Line)
            }
            Text("Teach a rule from Check: label a captured tap, cleared field or mark and tick Make a rule. " +
                "Export rules writes the taught and imported rules to a file you can share; built-in rules are not in it. " +
                "They live in rules.json in the app's files folder.",
                color = Kdl.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
        }
    }
    incoming?.let { (items, skipped, name) ->
        AlertDialog(
            onDismissRequest = { incoming = null },
            title = { Text("Import ${plural(items.size, "rule")}?") },
            text = {
                Column {
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(items, key = { it.rule.id }) { item ->
                            val r = item.rule
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text("${templateLabel(r.action).replaceFirstChar { it.uppercase() }} · ${BUILTIN_LABELS[r.pkg] ?: r.pkg.substringAfterLast('.')}",
                                    style = MaterialTheme.typography.bodyLarge)
                                Text("${if (r.event == RuleEvent.TAP) "Tap" else "Field cleared"} · ${r.pkg} · ${if (item.replaces) "replaces a rule in effect" else "new"}",
                                    color = if (item.replaces) Kdl.Amber else Kdl.Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (skipped > 0) Text("${plural(skipped, "rule")} in the file could not be used and ${if (skipped == 1) "is" else "are"} left out.",
                        color = Kdl.Amber, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    Text("A rule decides what KDL writes as an inferred action. Import rules only from someone you trust, and check what they infer.",
                        color = Kdl.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        repo.importRules(items.map { it.rule }, name); CaptureService.instance?.reloadRules()
                        toast(ctx, "Imported ${plural(items.size, "rule")}")
                    } catch (ex: IllegalStateException) { toast(ctx, ex.message ?: "Rules not imported") }
                    incoming = null; rules = repo.loadRules(); local = repo.localRuleIds(); bump()
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { incoming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DevicePage(repo: Repo, bump: () -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val profiles = remember { repo.db.profiles() }
    val p = profiles.lastOrNull()
    var carrier by remember { mutableStateOf(p?.carrier ?: "") }
    var notes by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageBar("Device", back)
        Column(Modifier.padding(horizontal = 16.dp)) {
            if (p == null) Text("No snapshot yet.", color = Kdl.Muted)
            else Text(
                "${p.manufacturer} ${p.model}\nAndroid ${p.release} (SDK ${p.sdkInt}), build ${p.incremental}\nSecurity patch ${p.securityPatch}\n" +
                    "Baseband ${p.baseband ?: "none"}\n${profiles.size} snapshot(s)",
                style = MaterialTheme.typography.bodyMedium)
            p?.fingerprint?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Kdl.Muted, modifier = Modifier.padding(top = 6.dp)) }
            OutlinedTextField(carrier, { carrier = it }, label = { Text("Carrier") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, supportingText = { Text(NO_SECRETS) }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { repo.refreshProfile(carrier, notes); toast(ctx, "Snapshot added"); back() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(if (p == null) "Take snapshot" else "Add new snapshot") }
            Text("Add a snapshot after an OS update. Earlier snapshots are kept.", color = Kdl.Muted,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
