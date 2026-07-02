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
                for (f in funcs) DeviceControlRow(mac, f)
            }
        }
    }
}

@Composable
private fun DeviceControlRow(mac: String, f: DeviceDef.Func) {
    // Read-only rows for now; Task 4 adds the level slider, Task 5 the rename field.
    when {
        f.isInfoRow -> {
            val summary = when (f.id) {
                "battery" -> AapState.forMac(mac).batterySummary()
                "ear_detection" -> AapState.forMac(mac).earSummary()
                else -> null
            } ?: "—"
            ListItem(headlineContent = { Text(f.title) }, supportingContent = { Text(summary) })
        }
        else -> ListItem(
            headlineContent = { Text(f.title) },
            supportingContent = { Text("Coming soon") })
    }
}
