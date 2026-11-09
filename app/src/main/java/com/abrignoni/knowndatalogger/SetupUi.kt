package com.abrignoni.knowndatalogger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
    val apps = remember { repo.db.apps() }
    val profile = remember { repo.db.profiles().lastOrNull() }
    val hash = remember { repo.lastRowHash() }
    val enabled = serviceEnabled(ctx)
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
            if (repo.sessionActive) Text("Changes apply when the next session starts.", color = Kdl.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            NavRow("Rules", "${repo.loadRules().size}") { open("rules") }
            NavRow("Device", profile?.let { "${it.manufacturer?.replaceFirstChar { c -> c.uppercase() }} ${it.model} · Android ${it.release}" } ?: "No snapshot yet") { open("device") }
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageBar("Rules", back)
        Column(Modifier.padding(horizontal = 16.dp)) {
            rules.forEach { r ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${templateLabel(r.action).replaceFirstChar { it.uppercase() }} · ${BUILTIN_LABELS[r.pkg] ?: r.pkg.substringAfterLast('.')}",
                            style = MaterialTheme.typography.bodyLarge)
                        Text("${if (r.event == RuleEvent.TAP) "Tap" else "Field cleared"} · ${if (r.id in local) "taught here" else "built in"}",
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
                "Taught rules live in rules.json in the app's files folder (adb pull to share, adb push to load).",
                color = Kdl.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
        }
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
