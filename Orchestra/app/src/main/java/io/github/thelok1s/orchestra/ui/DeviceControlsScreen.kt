package io.github.thelok1s.orchestra.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thelok1s.orchestra.AapState
import io.github.thelok1s.orchestra.DeviceDef

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceControlsScreen(mac: String, onBack: () -> Unit) {
    val def = remember(mac) { DeviceDef.forAddress(mac) }
    // Live battery/ear rows: the app process never owns the AAP socket (the SystemUI broker
    // does), so just subscribe to the app-side listener registry the same way HookedDeviceCard
    // does — AacpEngine fires it on every AAP notification, we bump liveTick, rows recompose.
    var liveTick by remember(mac) { mutableStateOf(0) }
    DisposableEffect(mac) {
        io.github.thelok1s.orchestra.AacpEngine.registerListener(mac, "controls-screen") { liveTick++ }
        onDispose { io.github.thelok1s.orchestra.AacpEngine.unregisterListener(mac, "controls-screen") }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(def?.name ?: "Device") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            })
    }) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val funcs = def?.appFunctions() ?: emptyList()
            if (funcs.isEmpty()) {
                Text("No in-app controls for this device.")
            } else {
                for (f in funcs) DeviceControlRow(mac, f, liveTick)
            }
        }
    }
}

@Composable
private fun DeviceControlRow(mac: String, f: DeviceDef.Func, liveTick: Int) {
    // Read-only rows for now; Task 4 adds the level slider, Task 5 the rename field.
    when {
        f.isInfoRow -> {
            val summary = remember(mac, f.id, liveTick) {
                when (f.id) {
                    "battery" -> AapState.forMac(mac).batterySummary()
                    "ear_detection" -> AapState.forMac(mac).earSummary()
                    else -> null
                } ?: "—"
            }
            ListItem(headlineContent = { Text(f.title) }, supportingContent = { Text(summary) })
        }
        else -> ListItem(
            headlineContent = { Text(f.title) },
            supportingContent = { Text("Coming soon") })
    }
}
