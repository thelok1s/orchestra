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
            val funcs = def?.appFunctions(mac) ?: emptyList()
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
    // Read-only rows for battery/ear; Task 5 adds the rename field.
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
        f.isLevel -> {
            val engine = remember { io.github.thelok1s.orchestra.ControlEngine.AACP }
            val adapter = remember { android.bluetooth.BluetoothAdapter.getDefaultAdapter() }
            val def = remember(mac) { DeviceDef.forAddress(mac) }
            // Adaptive strength only applies while ANC = Adaptive (mode 4). liveTick isn't read
            // directly here, but the caller reads it before invoking this row, so this composable
            // already recomposes on every AACP change (DeviceDef.Func is Compose-unstable).
            val ancMode = AapState.forMac(mac).ancMode
            if (f.id == "adaptive_strength" && ancMode != 4) {
                ListItem(headlineContent = { Text(f.title) },
                    supportingContent = { Text("Set Noise Control to Adaptive to use this") })
            } else {
                var pos by remember(mac, f.id) {
                    mutableStateOf((engine.readLevel(adapter, mac, def, f) ?: f.min).toFloat())
                }
                Column {
                    Text(f.title, style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = pos,
                        onValueChange = { pos = it },
                        onValueChangeFinished = {
                            engine.applyLevel(adapter, mac, def, f, pos.toInt())
                        },
                        valueRange = f.min.toFloat()..f.max.toFloat(),
                        // A sideloaded manifest bypasses schema checks, so f.step may be 0/negative;
                        // fall back to a plain continuous slider instead of dividing by zero.
                        steps = if (f.step <= 0) 0 else ((f.max - f.min) / f.step - 1).coerceAtLeast(0)
                    )
                    Text("${pos.toInt()}")
                }
            }
        }
        f.isText -> {
            // Rename is routed through the SystemUI broker (AacpClientBridge.sendRename ->
            // AapBroker.handleRename): the app process does NOT hold BLUETOOTH_PRIVILEGED at
            // runtime (it's signature|privileged, held only by the SystemUI/Settings hook
            // processes), so a direct BluetoothDevice.setAlias() call here throws
            // SecurityException on hardware. Reading the current alias for the field's initial
            // value only needs BLUETOOTH_CONNECT, which the app does hold, so that read stays
            // local. The broker applies the rename asynchronously with no ack path back to the
            // app, so the button is optimistic: it fires the broadcast and reports "Renamed"
            // immediately rather than waiting on (or claiming) success/failure.
            val adapter = remember { android.bluetooth.BluetoothAdapter.getDefaultAdapter() }
            var text by remember(mac) {
                mutableStateOf(
                    try {
                        val dev = adapter?.getRemoteDevice(mac)
                        dev?.alias ?: dev?.name ?: ""
                    } catch (e: SecurityException) { "" }
                )
            }
            var status by remember(mac) { mutableStateOf<String?>(null) }
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it; status = null }, singleLine = true,
                    label = { Text(f.title) }, isError = false,
                    supportingText = { status?.let { Text(it) } }
                )
                Button(onClick = {
                    io.github.thelok1s.orchestra.AacpClientBridge.sendRename(mac, text.trim())
                    status = "Renamed"
                }) { Text("Rename") }
            }
        }
        else -> ListItem(
            headlineContent = { Text(f.title) },
            supportingContent = { Text("Coming soon") })
    }
}
