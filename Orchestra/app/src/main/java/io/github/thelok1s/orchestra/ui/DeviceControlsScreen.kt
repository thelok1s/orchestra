package io.github.thelok1s.orchestra.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
    var liveTick by remember(mac) { mutableIntStateOf(0) }
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
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            var rise = 0
            val ownership = remember(mac, liveTick) { AapState.forMac(mac).ownershipSummary() }
            if (ownership != null) {
                Rise(rise++) {
                    InfoCard(icon = Icons.Filled.Link, seed = "ownership",
                        title = "Ownership", value = ownership)
                }
            }
            val funcs = def?.appFunctions(mac) ?: emptyList()
            if (funcs.isEmpty()) {
                Rise(rise++) {
                    InfoCard(icon = Icons.Filled.Info, seed = "none", title = "In-app controls",
                        value = "No in-app controls for this device.", dim = true)
                }
            } else {
                for (f in funcs) {
                    Rise(rise++) { DeviceControlCard(mac, f, liveTick) }
                }
            }
        }
    }
}

private fun iconFor(f: DeviceDef.Func): ImageVector = when {
    f.id.contains("batt", ignoreCase = true) -> Icons.Filled.BatteryFull
    f.id.contains("ear", ignoreCase = true) -> Icons.Filled.Hearing
    f.isLevel || f.isSlider -> Icons.Filled.GraphicEq
    f.isText -> Icons.Filled.Edit
    else -> Icons.Filled.Tune
}

/** Filled expressive card container shared by every control row (28dp, surface-container-high). */
@Composable
private fun ControlCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content)
    }
}

/** Read-only row: shape chip + small title + value (dim = muted body style for hints). */
@Composable
private fun InfoCard(
    icon: ImageVector,
    seed: String,
    title: String,
    value: String,
    dim: Boolean = false,
) {
    ControlCard {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ShapeChip(
                shape = remember(seed) { shapeForSeed(seed) },
                size = 48.dp,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(icon, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value,
                    style = if (dim) MaterialTheme.typography.bodyMedium
                    else MaterialTheme.typography.titleLarge,
                    color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun DeviceControlCard(mac: String, f: DeviceDef.Func, liveTick: Int) {
    // The engine is resolved per-function from its transport (RFCOMM / AACP / …), so this screen is
    // device-agnostic — it drives Soundcore level/slider controls exactly like AirPods ones.
    val def = remember(mac) { DeviceDef.forAddress(mac) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val adapter = remember(context) {
        val bm = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        bm?.adapter
    }
    val engine = remember(f.id) { io.github.thelok1s.orchestra.ControlEngine.forFunc(f) }
    when {
        f.isInfoRow -> {
            // Read-only rows (battery / ear detection). readInfo is engine-specific; RFCOMM returns
            // null today (no info rows) so those show "—".
            val summary = remember(mac, f.id, liveTick) {
                engine?.readInfo(adapter, mac, def, f) ?: "—"
            }
            InfoCard(icon = iconFor(f), seed = f.id, title = f.title, value = summary)
        }
        f.isLevel || f.isSlider -> {
            // AAP adaptive strength only applies while ANC = Adaptive (mode 4); AAP-specific guard.
            val ancMode = if (f.id == "adaptive_strength") AapState.forMac(mac).ancMode else null
            when {
                engine == null ->
                    InfoCard(icon = iconFor(f), seed = f.id, title = f.title,
                        value = "Unavailable on this device", dim = true)
                f.id == "adaptive_strength" && ancMode != 4 ->
                    InfoCard(icon = iconFor(f), seed = f.id, title = f.title,
                        value = "Set Noise Control to Adaptive to use this", dim = true)
                else -> {
                    var pos by remember(mac, f.id) {
                        mutableStateOf((engine.readLevel(adapter, mac, def, f) ?: f.min).toFloat())
                    }
                    ControlCard {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            ShapeChip(
                                shape = remember(f.id) { shapeForSeed(f.id) },
                                size = 48.dp,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Icon(iconFor(f), contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                            Text(f.title, style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f))
                            Text("${pos.toInt()}", style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
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
            ControlCard {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ShapeChip(
                        shape = remember(f.id) { shapeForSeed(f.id) },
                        size = 48.dp,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Text(f.title, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it; status = null }, singleLine = true,
                    label = { Text("Name") }, isError = false,
                    supportingText = { status?.let { Text(it) } },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        if (text.isBlank()) {
                            status = "Name can't be empty"
                        } else {
                            io.github.thelok1s.orchestra.AacpClientBridge.sendRename(mac, text.trim())
                            status = "Renamed"
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                ) { Text("Rename") }
            }
        }
        else -> InfoCard(icon = iconFor(f), seed = f.id, title = f.title,
            value = "Coming soon", dim = true)
    }
}
