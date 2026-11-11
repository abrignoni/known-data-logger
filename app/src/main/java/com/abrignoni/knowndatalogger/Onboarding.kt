package com.abrignoni.knowndatalogger

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp

/** On when every listed app is checked, Off when none is, Indeterminate in between. */
internal fun checkAllState(listed: List<String>, chosen: Collection<String>): ToggleableState {
    val n = listed.count { it in chosen }
    return when {
        listed.isEmpty() || n == 0 -> ToggleableState.Off
        n == listed.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
}

/** First run: test-phone notice and scenario, apps, capture service, done. */
@Composable
fun Onboarding(repo: Repo, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var scenario by remember { mutableStateOf(repo.scenarioId) }
    var device by remember { mutableStateOf(repo.deviceLabel) }
    val launcher = remember { repo.launcherApps() }
    val chosen = remember { mutableStateListOf<String>().apply { addAll(repo.db.apps().map { it.pkg }) } }
    var enabled by remember { mutableStateOf(serviceEnabled(ctx)) }
    var query by remember { mutableStateOf("") }
    // Coming back from Accessibility settings re-checks the service.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { enabled = serviceEnabled(ctx); onPauseOrDispose { } }

    val titles = listOf("Get started", "Pick apps", "Turn on capture", "Ready")
    Scaffold(containerColor = Kdl.Bg, bottomBar = {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step > 0) OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f)) { Text("Back") }
            val canNext = when (step) {
                0 -> scenario.isNotBlank() && device.isNotBlank()
                1 -> chosen.isNotEmpty()
                2 -> enabled
                else -> true
            }
            Button(enabled = canNext, modifier = Modifier.weight(1f), onClick = {
                when (step) {
                    0 -> { repo.scenarioId = scenario; repo.deviceLabel = device; step = 1 }
                    1 -> {
                        val have = repo.db.apps().map { it.pkg }.toSet()
                        launcher.filter { it.first in chosen && it.first !in have }.forEach { repo.addApp(it.first, it.second, null) }
                        have.filter { it !in chosen }.forEach { repo.db.removeApp(it) }
                        step = 2
                    }
                    2 -> step = 3
                    else -> {
                        if (repo.db.profiles().isEmpty()) repo.refreshProfile(null, null)
                        repo.onboarded = true
                        onDone()
                    }
                }
            }) { Text(if (step == 3) "Start using KDL" else "Next") }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            AppBar(titles[step])
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(4) { i -> Box(Modifier.weight(1f).height(4.dp).background(if (i <= step) Kdl.Primary else Kdl.Line, RoundedCornerShape(2.dp))) }
            }
            Column(Modifier.padding(horizontal = 16.dp).fillMaxSize()) {
                when (step) {
                    0 -> {
                        Card(colors = CardDefaults.cardColors(containerColor = Kdl.Surface), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Test phones only", style = MaterialTheme.typography.titleMedium)
                                Text("KDL records what you type and see in the apps you pick. Use test accounts, never an evidence device.",
                                    color = Kdl.Muted, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                        OutlinedTextField(scenario, { scenario = it }, label = { Text("Scenario id, same on every phone") },
                            modifier = Modifier.fillMaxWidth().padding(top = 20.dp))
                        OutlinedTextField(device, { device = it }, label = { Text("Device label, e.g. phone A") },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    1 -> {
                        Text("Capture records only these apps, plus System UI and Settings.", color = Kdl.Muted, modifier = Modifier.padding(bottom = 8.dp))
                        OutlinedTextField(query, { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        // Check all works on the apps shown, so a search narrows what it ticks.
                        val shown = filterApps(launcher, query)
                        val pkgs = shown.map { it.first }
                        val state = checkAllState(pkgs, chosen)
                        val toggleAll = {
                            if (state == ToggleableState.On) chosen.removeAll(pkgs.toSet())
                            else chosen.addAll(pkgs.filter { it !in chosen })
                        }
                        Row(Modifier.fillMaxWidth().clickable { toggleAll() }.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                            TriStateCheckbox(state, { toggleAll() })
                            Text(if (query.isBlank()) "Check all" else "Check all shown", style = MaterialTheme.typography.bodyLarge)
                        }
                        HorizontalDivider(color = Kdl.Line)
                        if (shown.isEmpty()) Text("No app matches.", color = Kdl.Muted, modifier = Modifier.padding(vertical = 12.dp))
                        LazyColumn {
                            items(shown, key = { it.first }) { (pkg, label) ->
                                Row(Modifier.fillMaxWidth().clickable { if (pkg in chosen) chosen.remove(pkg) else chosen.add(pkg) }
                                    .heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(pkg in chosen, { if (it) chosen.add(pkg) else chosen.remove(pkg) })
                                    Text(label, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                    2 -> {
                        Text("Android lists KDL under Accessibility. Turn it on there, then come back here.", color = Kdl.Muted,
                            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 20.dp))
                        Button(onClick = { ctx.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Open Accessibility settings") }
                        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (enabled) Icons.Outlined.CheckCircle else Icons.Outlined.Schedule, null, tint = if (enabled) Kdl.Green else Kdl.Amber)
                            Text(if (enabled) "  Capture is on" else "  Waiting for capture to turn on", color = Kdl.Muted)
                        }
                        Text("If Android says the app was denied access (an APK installed from a file): Settings, Apps, KDL, the three-dot menu, Allow restricted settings, then switch it on again.",
                            color = Kdl.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 20.dp))
                    }
                    else -> {
                        Text("You're set.", style = MaterialTheme.typography.headlineSmall)
                        Text("Start a session on the Session tab and use the phone. Tap the red MARK button to save a moment with a screenshot. " +
                            "After a session, Check shows anything KDL inferred or could not label.",
                            color = Kdl.Muted, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}
