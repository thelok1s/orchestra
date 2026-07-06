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
    // Live battery/ear rows update only for AAP devices: the app never owns the AAP socket (the
    // SystemUI broker does), so it subscribes to the app-side AACP listener registry and bumps
    // liveTick on each push. Soundcore/RFCOMM has no push channel, so its rows stay optimistic and
    // we skip the (AAP-specific) listener entirely.
    var liveTick by remember(mac) { mutableStateOf(0) }
    val isAap = remember(mac) { def?.usesAacp() == true }
    DisposableEffect(mac, isAap) {
        if (isAap) io.github.thelok1s.orchestra.AacpEngine.registerListener(mac, "controls-screen") { liveTick++ }
        onDispose { if (isAap) io.github.thelok1s.orchestra.AacpEngine.unregisterListener(mac, "controls-screen") }
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
    // The engine is resolved per-function from its transport (RFCOMM / AACP / …), so this screen is
    // device-agnostic — it drives Soundcore level/slider controls exactly like AirPods ones.
    val def = remember(mac) { DeviceDef.forAddress(mac) }
    val adapter = remember { android.bluetooth.BluetoothAdapter.getDefaultAdapter() }
    val engine = remember(f.id) { io.github.thelok1s.orchestra.ControlEngine.forFunc(f) }
    when {
        f.isInfoRow -> {
            // Read-only rows (battery / ear detection). readInfo is engine-specific; RFCOMM returns
            // null today (no info rows) so those show "—".
            val summary = remember(mac, f.id, liveTick) {
                engine?.readInfo(adapter, mac, def, f) ?: "—"
            }
            ListItem(headlineContent = { Text(f.title) }, supportingContent = { Text(summary) })
        }
        f.isLevel || f.isSlider -> {
            // AAP adaptive strength only applies while ANC = Adaptive (mode 4); AAP-specific guard.
            val ancMode = if (f.id == "adaptive_strength") AapState.forMac(mac).ancMode else null
            when {
                engine == null ->
                    ListItem(headlineContent = { Text(f.title) },
                        supportingContent = { Text("Unavailable on this device") })
                f.id == "adaptive_strength" && ancMode != 4 ->
                    ListItem(headlineContent = { Text(f.title) },
                        supportingContent = { Text("Set Noise Control to Adaptive to use this") })
                else -> {
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
                    if (text.isBlank()) {
                        status = "Name can't be empty"
                    } else {
                        io.github.thelok1s.orchestra.AacpClientBridge.sendRename(mac, text.trim())
                        status = "Renamed"
                    }
                }) { Text("Rename") }
            }
        }
        else -> ListItem(
            headlineContent = { Text(f.title) },
            supportingContent = { Text("Coming soon") })
    }
}
