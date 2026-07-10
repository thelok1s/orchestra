package io.github.thelok1s.orchestra.ui

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.style.TextAlign
import androidx.graphics.shapes.Morph
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Earbuds
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.thelok1s.orchestra.AacpEngine
import io.github.thelok1s.orchestra.AapState
import io.github.thelok1s.orchestra.BuildConfig
import io.github.thelok1s.orchestra.DeviceDef
import io.github.thelok1s.orchestra.DeviceStore
import io.github.thelok1s.orchestra.Logbook
import io.github.thelok1s.orchestra.ManifestRepository
import io.github.thelok1s.orchestra.ManifestUpdater
import io.github.thelok1s.orchestra.R
import io.github.thelok1s.orchestra.XposedSelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException
import androidx.core.graphics.toColorInt

class MainActivity : ComponentActivity() {

    private val refresh = mutableIntStateOf(0)

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
        setContent { OrchestraApp(refresh.intValue) }
    }

    override fun onResume() {
        super.onResume()
        refresh.intValue++
    }
}

// Fixed, non-dynamic semantic status colors (ignore Monet so meaning stays constant).
private val StatusGood = Color(0xFF2E7D32)   // green 800
private val StatusBad = Color(0xFFC62828)    // red 700
private val StatusIdle = Color(0xFFF9A825)   // amber 800
private val BluetoothBlue = Color("#00B2FC".toColorInt())

// Display-flag pref keys.
private const val FLAG_STATUSES = "show_statuses"
private const val FLAG_UNVERIFIED = "show_unverified"
private const val FLAG_INAPP = "show_inapp"
private const val FLAG_MAC = "show_mac"
private const val FLAG_ACT_AS_APPLE = "act_as_apple"

private enum class Dest(val label: String, val icon: ImageVector, val iconOutlined: ImageVector) {
    STATUS("Status", Icons.Filled.CheckCircle, Icons.Outlined.CheckCircle),
    DEVICES("Devices", Icons.Filled.Headphones, Icons.Outlined.Headphones),
    SETTINGS("Settings", Icons.Filled.Tune, Icons.Outlined.Tune),
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OrchestraApp(refreshKey: Int) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colorScheme = remember(dark) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    // Kick off a background TTL-check for the manifest index on first composition.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { ManifestUpdater.refreshIndexIfStale() }
    }
    MaterialExpressiveTheme(colorScheme = colorScheme) {
        var openDeviceMac by rememberSaveable { mutableStateOf<String?>(null) }
        var dest by rememberSaveable { mutableStateOf(Dest.STATUS) }
        var debugOpen by rememberSaveable { mutableStateOf(false) }
        val opened = openDeviceMac
        // System/gesture back on the device-controls screen must return to the tabs, not exit the
        // activity — plain (non-predictive) since this screen replaces content wholesale rather
        // than animating over it like the Debug overlay below.
        BackHandler(enabled = opened != null) { openDeviceMac = null }
        if (opened != null) {
            DeviceControlsScreen(opened, onBack = { openDeviceMac = null })
        } else {
            Box(Modifier.fillMaxSize()) {
                // Screen content is recorded into a layer so the floating nav bar can re-draw a
                // blurred copy of whatever scrolls beneath it (backdrop blur, no library).
                val contentLayer = rememberGraphicsLayer()
                Scaffold(
                    topBar = { TopAppBar(title = { Text("Orchestra") }) },
                    modifier = Modifier.drawWithContent {
                        contentLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(contentLayer)
                    },
                ) { padding ->
                    AnimatedContent(
                        targetState = dest,
                        transitionSpec = {
                            (fadeIn(tween(220)) + slideInVertically { it / 14 })
                                .togetherWith(fadeOut(tween(120)))
                        },
                        label = "tab",
                        modifier = Modifier.padding(padding)
                    ) { d ->
                        when (d) {
                            Dest.STATUS -> StatusScreen(refreshKey)
                            Dest.DEVICES -> DevicesScreen(refreshKey, onOpenDevice = { openDeviceMac = it })
                            Dest.SETTINGS -> SettingsScreen(onOpenDebug = { debugOpen = true })
                        }
                    }
                }
                BottomBlurStrip(
                    contentLayer = contentLayer,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
                FloatingNavBar(
                    dest = dest,
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) { dest = it }

                // Debug as an overlay screen, dismissed by the predictive back gesture (animated).
                AnimatedVisibility(
                    visible = debugOpen,
                    enter = slideInHorizontally(tween(260)) { it } + fadeIn(tween(260)),
                    exit = slideOutHorizontally(tween(220)) { it } + fadeOut(tween(220)),
                ) {
                    var backProgress by remember { mutableFloatStateOf(0f) }
                    PredictiveBackHandler(enabled = debugOpen) { flow ->
                        try {
                            flow.collect { e -> backProgress = e.progress }
                            debugOpen = false; backProgress = 0f
                        } catch (_: CancellationException) {
                            backProgress = 0f
                        }
                    }
                    Surface(
                        Modifier.fillMaxSize().graphicsLayer {
                            val s = 1f - 0.08f * backProgress
                            scaleX = s; scaleY = s
                            translationX = size.width * 0.14f * backProgress
                            alpha = 1f - 0.25f * backProgress
                        },
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        DebugScreen(refreshKey, onBack = { debugOpen = false })
                    }
                }
            }
        }
    }
}

/**
 * Full-width blur strip pinned to the bottom edge (behind the floating nav pill): re-draws
 * [contentLayer] (the recorded screen content) through a [BlurEffect], alpha-masked by a
 * vertical gradient so the blur fades in toward the screen bottom — Telegram-style. Covers
 * the gesture-navigation area too. Draw-only; never intercepts touches.
 */
@Composable
private fun BottomBlurStrip(contentLayer: GraphicsLayer, modifier: Modifier = Modifier) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val navPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(
        modifier
            .fillMaxWidth()
            .height(navPad + 116.dp)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .graphicsLayer {
                renderEffect = BlurEffect(28f, 28f, TileMode.Clamp)
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawBehind {
                translate(-bounds.left, -bounds.top) {
                    drawLayer(contentLayer)
                }
                // Fade the blur out toward the top of the strip (DstIn alpha mask).
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Black,
                        1f to Color.Black,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
    )
}

/**
 * Detached, fully-rounded, elevated bottom nav (M3 Expressive floating pill).
 *
 * One SHARED active indicator translates between slots (spring — retargeting mid-flight
 * redirects with preserved velocity instead of restarting), while each item crossfades its
 * outlined/filled icon and tints in lockstep with the 220ms screen crossfade. Frame-hot values
 * are deferred out of composition: the pill offset via the lambda [offset] overload (layout
 * phase) and icon alphas via [graphicsLayer] blocks (draw phase), so animation frames don't
 * recompose the navbar tree at all.
 */
@Composable
private fun FloatingNavBar(
    dest: Dest,
    modifier: Modifier = Modifier,
    onSelect: (Dest) -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = modifier
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            .fillMaxWidth(),
    ) {
        BoxWithConstraints(Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
            val slot = maxWidth / Dest.entries.size
            val indicatorX by animateDpAsState(
                targetValue = slot * dest.ordinal + (slot - 64.dp) / 2,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "navPillX",
            )
            // The single moving pill, behind the items. Lambda offset = placement-only updates.
            Box(
                Modifier
                    .offset { IntOffset(indicatorX.roundToPx(), 0) }
                    .width(64.dp).height(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
            )
            Row(Modifier.fillMaxWidth()) {
                Dest.entries.forEach { d ->
                    val selected = dest == d
                    // 0 → 1 selection progress; duration matches the screen crossfade (220ms).
                    val progress by animateFloatAsState(
                        targetValue = if (selected) 1f else 0f,
                        animationSpec = tween(220, easing = FastOutSlowInEasing),
                        label = "navSel",
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onSelect(d) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val tint = lerp(
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            MaterialTheme.colorScheme.onSecondaryContainer,
                            progress,
                        )
                        Box(
                            Modifier.width(64.dp).height(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            // Outlined ↔ filled crossfade; alphas resolve at draw time.
                            Icon(
                                d.iconOutlined,
                                contentDescription = d.label,
                                tint = tint,
                                modifier = Modifier.graphicsLayer { alpha = 1f - progress },
                            )
                            Icon(
                                d.icon,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.graphicsLayer { alpha = progress },
                            )
                        }
                        Text(
                            d.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = lerp(
                                MaterialTheme.colorScheme.onSurfaceVariant,
                                MaterialTheme.colorScheme.onSurface,
                                progress,
                            ),
                        )
                    }
                }
            }
        }
    }
}

// ---------- model ----------

/**
 * Eligibility state for one bonded device, computed off the main thread.
 * [matchedId] — the resolved device_id (index or catalog), null if unresolved.
 * [source]    — "bundled" | "downloaded" | "sideload" | null (null = index-only, no local manifest).
 * [updateRev] — Pair(localRevision, indexRevision) when an update is available, else null.
 * [sideloaded] — true when the manifest came from a sideloaded file.
 */
internal data class DeviceEligibility(
    val matchedId: String?,
    val source: String?,
    val updateRev: Pair<Int, Int>?,
    val sideloaded: Boolean,
)

internal data class Batt(val left: Int, val right: Int, val case: Int, val main: Int,
                         val leftChg: Boolean, val rightChg: Boolean, val caseChg: Boolean,
                         val mainChg: Boolean, val tws: Boolean) {
    val any: Boolean get() = left in 0..100 || right in 0..100 || case in 0..100 || main in 0..100
}

internal data class BondedDevice(
    val name: String,
    val mac: String,
    val connected: Boolean,
    val batt: Batt,
    val deviceId: String,          // always non-null: either local-catalog id or index id
    val hasLocalManifest: Boolean, // true when ManifestRepository.sourceOf(deviceId) != null
)

private const val META_MODEL_NAME = 3

internal fun bondedSupported(context: Context): List<BondedDevice> {
    return try {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        adapter?.bondedDevices.orEmpty().mapNotNull { d ->
            val name = d.name ?: "(unknown)"
            val uuids = d.uuids?.map { it.uuid.toString().lowercase() }
            val modelName = meta(d, META_MODEL_NAME)
            val id = DeviceStore.idForBonded(name, uuids, modelName) ?: return@mapNotNull null
            val hasLocal = ManifestRepository.sourceOf(id) != null
            BondedDevice(name, d.address, isConnected(d), readBattery(d), id, hasLocal)
        }.sortedBy { it.name }
    } catch (_: Throwable) {
        emptyList()
    }
}

private fun isConnected(device: BluetoothDevice): Boolean = try {
    (device.javaClass.getMethod("isConnected").invoke(device) as? Boolean) ?: false
} catch (_: Throwable) { false }

// BluetoothDevice metadata keys (per-bud battery is what the native Fast-Pair header reads).
private const val META_IS_UNTETHERED = 6
private const val META_L_BATT = 10
private const val META_R_BATT = 11
private const val META_CASE_BATT = 12
private const val META_L_CHG = 13
private const val META_R_CHG = 14
private const val META_CASE_CHG = 15
private const val META_MAIN_BATT = 18
private const val META_MAIN_CHG = 19

private fun meta(device: BluetoothDevice, key: Int): String? = try {
    val m = device.javaClass.getMethod("getMetadata", Int::class.javaPrimitiveType)
    (m.invoke(device, key) as? ByteArray)?.let { String(it) }
} catch (_: Throwable) { null }

private fun metaInt(d: BluetoothDevice, key: Int): Int =
    meta(d, key)?.trim()?.toIntOrNull()?.takeIf { it in 0..100 } ?: -1

private fun metaBool(d: BluetoothDevice, key: Int): Boolean =
    meta(d, key)?.trim().equals("true", ignoreCase = true)

private fun batteryLevel(device: BluetoothDevice): Int = try {
    (device.javaClass.getMethod("getBatteryLevel").invoke(device) as? Int)?.takeIf { it in 0..100 } ?: -1
} catch (_: Throwable) { -1 }

private fun readBattery(d: BluetoothDevice): Batt {
    val tws = meta(d, META_IS_UNTETHERED)?.trim().equals("true", ignoreCase = true)
    val l = metaInt(d, META_L_BATT); val r = metaInt(d, META_R_BATT); val c = metaInt(d, META_CASE_BATT)
    var main = metaInt(d, META_MAIN_BATT)
    if (main < 0) main = batteryLevel(d)
    return Batt(l, r, c, main,
        metaBool(d, META_L_CHG), metaBool(d, META_R_CHG), metaBool(d, META_CASE_CHG),
        metaBool(d, META_MAIN_CHG), tws || l >= 0 || r >= 0 || c >= 0)
}

internal fun btEnabled(context: Context): Boolean = try {
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true
} catch (_: Throwable) { false }

private const val FEATURE_SUPPORTED = 10 // BluetoothStatusCodes.FEATURE_SUPPORTED

/** Detected radio capabilities → list of (label, supported). */
internal fun btTech(context: Context): Pair<String, List<Pair<String, Boolean>>> {
    val pm = context.packageManager
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    fun ok(block: () -> Any?): Boolean = try {
        when (val v = block()) { is Boolean -> v; is Int -> v == FEATURE_SUPPORTED; else -> false }
    } catch (_: Throwable) { false }

    val classic = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
    val ble = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    val leAudio = adapter != null && ok { adapter.javaClass.getMethod("isLeAudioSupported").invoke(adapter) }
    val auracast = adapter != null && ok { adapter.javaClass.getMethod("isLeAudioBroadcastSourceSupported").invoke(adapter) }
    val phy2m = adapter != null && ok { adapter.isLe2MPhySupported }
    val coded = adapter != null && ok { adapter.isLeCodedPhySupported }
    val extAdv = adapter != null && ok { adapter.isLeExtendedAdvertisingSupported }

    val tech = listOf(
        "Classic (RFCOMM / SPP)" to classic,
        "BLE / GATT" to ble,
        "LE Audio" to leAudio,
        "Auracast broadcast" to auracast,
        "2M PHY" to phy2m,
        "Coded PHY (long range)" to coded,
        "Extended advertising" to extAdv,
    )
    // Heuristic core-version estimate from features (no public API exposes the HCI version).
    val version = when {
        leAudio || auracast -> "Bluetooth 5.2+ (estimated)"
        extAdv || coded -> "Bluetooth 5.0+ (estimated)"
        ble -> "Bluetooth 4.x+ (estimated)"
        else -> "Bluetooth Classic"
    }
    return version to tech
}

// ---------- Status ----------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusScreen(refreshKey: Int) {
    val context = LocalContext.current
    val moduleActive = remember(refreshKey) { runCatching { XposedSelf.active() }.getOrDefault(false) }
    val apiLevel = remember(refreshKey) { runCatching { XposedSelf.apiLevel() }.getOrDefault(-1) }
    val btState = rememberBluetoothState()
    val bt = btState == BtState.ON
    val supported = remember(refreshKey, bt) { bondedSupported(context) }
    val hookedCount = remember(refreshKey) { DeviceStore.enabledMap().size }
    val (btVersion, btTechList) = remember(refreshKey) { btTech(context) }

    val circlePoly = remember { MaterialShapes.Circle }
    val pillPoly = remember { MaterialShapes.Pill }
    val morph = remember { Morph(circlePoly, pillPoly) }
    val shapeProgress by animateFloatAsState(
        targetValue = if (btState == BtState.ON) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "btShape"
    )
    val animatedShape = remember(shapeProgress) { MorphShape(morph, shapeProgress) }

    val enableBluetooth = {
        val intent = Intent("io.github.thelok1s.orchestra.ENABLE_BLUETOOTH")
        context.sendBroadcast(intent)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Rise(0) { HeroCard(moduleActive, hookedCount) }

        Rise(1) {
            Row(
                Modifier.height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatTile(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    icon = if (moduleActive) Icons.Filled.Extension else Icons.Filled.Cancel,
                    iconTint = null,
                    shape = MaterialShapes.Square.asShape(),
                    label = if (apiLevel > 0) "LSPosed · API $apiLevel" else "LSPosed module",
                    value = if (moduleActive) "Active" else "Off",
                    good = moduleActive,
                )
                StatTile(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    icon = if (btState == BtState.OFF) Icons.Filled.BluetoothDisabled else Icons.Filled.Bluetooth,
                    iconTint = if (btState != BtState.OFF) BluetoothBlue else null,
                    shape = animatedShape,
                    pillColor = Color(0xFF7EC4CF),
                    label = when (btState) {
                        BtState.ON -> "${supported.count { it.connected }}/${supported.size} device(s) connected"
                        BtState.TURNING_ON -> "Enabling Bluetooth..."
                        BtState.TURNING_OFF -> "Disabling Bluetooth..."
                        BtState.OFF -> "Tap here to enable"
                    },
                    value = if (btState == BtState.ON) "Bluetooth On" else "Bluetooth Off",
                    good = btState == BtState.ON,
                    showLoading = btState == BtState.TURNING_ON,
                    onClick = if (btState == BtState.OFF) { { enableBluetooth() } } else null
                )
            }
        }

        Rise(2) {
            StatusCard(
                icon = Icons.Filled.Headphones,
                statusColor = if (hookedCount > 0) StatusGood else StatusIdle,
                shape = MaterialShapes.Cookie4Sided.asShape(),
                title = "Hooked devices",
                value = hookedCount.toString(),
                detail = "Devices you've switched on in Devices. Each gets native controls."
            )
        }
        // Android tile — tap to expand radio capabilities.
        var androidExpanded by rememberSaveable { mutableStateOf(false) }
        Rise(3) {
            StatusCard(
                icon = Icons.Filled.Android,
                statusColor = StatusGood,
                shape = MaterialShapes.Sunny.asShape(),
                iconTint = Color(0xFF3DDC84), // Android green
                title = "Android",
                value = "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
                detail = "Orchestra ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) · tap for radio info",
                onClick = { androidExpanded = !androidExpanded },
                trailing = {
                    Icon(if (androidExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                expanded = androidExpanded,
                expandedContent = {
                    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(btVersion, style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        btTechList.forEach { (label, supp) ->
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(
                                    if (supp) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (supp) StatusGood else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(label, style = MaterialTheme.typography.bodyMedium,
                                    color = if (supp) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            )
        }
    }
}

/** Big "all systems go" hero: morphing cog badge + oversized decorative shape off the corner. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HeroCard(moduleActive: Boolean, hookedCount: Int) {
    val container = if (moduleActive) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.errorContainer
    val onContainer = if (moduleActive) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onErrorContainer
    val accent = if (moduleActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val onAccent = if (moduleActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError

    val earbuds2 = ImageVector.vectorResource(id = R.drawable.outline_earbuds_2_24)
    val availableIcons = remember(earbuds2) {
        listOf(
            Icons.Filled.Speaker,
            Icons.Filled.Earbuds,
            earbuds2,
            Icons.Filled.HeadsetMic,
            Icons.Filled.Headphones
        )
    }
    var iconIndex by rememberSaveable { mutableIntStateOf(-1) }
    val defaultIcon = if (moduleActive) Icons.Filled.Check else Icons.Filled.Close
    val iconToShow = if (iconIndex == -1) defaultIcon else availableIcons[iconIndex]

    // Slow breathe: gentle rotate + scale on the badge (design: orch-breathe 5s).
    val breathe = rememberInfiniteTransition(label = "breathe")
    val angle by breathe.animateFloat(0f, 180f,
        infiniteRepeatable(tween(2500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "angle")
    val scale by breathe.animateFloat(1f, 1.06f,
        infiniteRepeatable(tween(2500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale")

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(container)
            .combinedClickable(
                onDoubleClick = {
                    val eligibleIndices = availableIcons.indices.filter { availableIcons[it] != iconToShow }
                    if (eligibleIndices.isNotEmpty()) {
                        iconIndex = eligibleIndices.random()
                    }
                },
                onClick = {}
            )
    ) {
        // Oversized decorative shape bleeding off the top-right corner. Wrapped in a
        // matchParentSize box so its 150dp doesn't participate in the card's measurement —
        // the card is sized by the content row alone.
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 38.dp, y = (-38).dp).size(150.dp)
                    .clip(MaterialShapes.Sunny.asShape())
                    .background(accent.copy(alpha = 0.22f))
            )
        }
        Row(
            Modifier.padding(horizontal = 22.dp, vertical = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(72.dp)
                    .graphicsLayer { rotationZ = angle; scaleX = scale; scaleY = scale }
                    .clip(MaterialShapes.Cookie12Sided.asShape())
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    iconToShow,
                    contentDescription = null, tint = onAccent, modifier = Modifier.size(36.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (moduleActive) "All systems go" else "Module not active",
                    style = MaterialTheme.typography.headlineSmall, color = onContainer,
                )
                Text(
                    if (moduleActive) {
                        "Module active · $hookedCount device${if (hookedCount == 1) "" else "s"} hooked"
                    } else {
                        "Enable Orchestra in LSPosed with scope: System UI, Settings, Orchestra. " +
                            "Then restart System UI."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContainer.copy(alpha = 0.78f),
                )
            }
        }
    }
}

/** Compact bento tile: big shape chip, bold value, small label. */
@Composable
private fun StatTile(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    iconTint: Color?,
    shape: Shape,
    pillColor: Color? = null,
    label: String,
    value: String,
    good: Boolean,
    showLoading: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val chipBg = pillColor?.copy(alpha = 0.22f)
            ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
            .compositeOver(MaterialTheme.colorScheme.surfaceContainerHighest)
        val tint = iconTint ?: pillColor
            ?: if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(62.dp)) {
            if (showLoading) {
                CircularWavyProgressIndicator(
                    modifier = Modifier.size(62.dp),
                    color = tint
                )
            }
            ShapeChip(shape = shape, size = 52.dp, color = chipBg) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Status tile: container tinted by status color (not Monet), optional custom icon tint, optional
 *  expandable content. */
@Composable
private fun StatusCard(
    icon: ImageVector,
    statusColor: Color,
    shape: Shape,
    title: String,
    value: String,
    detail: String,
    iconTint: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    expanded: Boolean = false,
    expandedContent: (@Composable () -> Unit)? = null,
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.10f)),
        modifier = Modifier.fillMaxWidth().animateContentSize(
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ShapeChip(shape = shape, size = 48.dp, color = statusColor.copy(alpha = 0.20f)) {
                    Icon(icon, contentDescription = null, tint = iconTint ?: statusColor)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.titleLarge)
                    Text(detail, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (trailing != null) trailing()
            }
            if (expanded && expandedContent != null) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = statusColor.copy(alpha = 0.25f))
                Spacer(Modifier.height(10.dp))
                expandedContent()
            }
        }
    }
}

// ---------- Devices ----------

@Composable
private fun DevicesScreen(refreshKey: Int, onOpenDevice: (String) -> Unit) {
    val context = LocalContext.current
    val btState = rememberBluetoothState()
    var tick by remember { mutableIntStateOf(0) }
    val supported = remember(refreshKey, tick, btState) { bondedSupported(context) }
    val enabled = remember(refreshKey, tick) { DeviceStore.enabledMap() }
    val showStatuses = DeviceStore.flag(FLAG_STATUSES, true)
    val showUnverified = DeviceStore.flag(FLAG_UNVERIFIED, false)
    val showInApp = DeviceStore.flag(FLAG_INAPP, false)
    val showMac = DeviceStore.flag(FLAG_MAC, false)

    val enableBluetooth = {
        val intent = Intent("io.github.thelok1s.orchestra.ENABLE_BLUETOOTH")
        context.sendBroadcast(intent)
    }

    // Hooked: enabled + has a local manifest (can load DeviceDef safely).
    val hooked = supported.filter { enabled.containsKey(it.mac.uppercase()) && it.hasLocalManifest }
    // Available: not currently hooked. Includes both local-catalog devices and index-only devices.
    val available = supported.filter { !enabled.containsKey(it.mac.uppercase()) }

    AnimatedContent(
        targetState = btState == BtState.ON,
        transitionSpec = {
            (fadeIn(tween(300)) + slideInVertically { it / 8 })
                .togetherWith(fadeOut(tween(200)) + slideOutVertically { -it / 8 })
        },
        label = "devicesTransition",
        modifier = Modifier.fillMaxSize()
    ) { isBluetoothOn ->
        if (isBluetoothOn) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 108.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (supported.isEmpty()) {
                    Text(
                        "No supported devices paired. Pair your headphones, then return here.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                if (hooked.isNotEmpty()) {
                    SectionHeader("Hooked", "${hooked.size}")
                    hooked.forEach { d ->
                        HookedDeviceCard(
                            d, refreshKey, tick, showStatuses, showUnverified, showInApp, showMac,
                            onUnhook = { DeviceStore.setEnabled(d.mac, d.deviceId, false); tick++ },
                            onChange = { tick++ },
                            onManifestUpdated = { tick++ },
                            onOpen = { onOpenDevice(d.mac) })
                    }
                }
                if (available.isNotEmpty()) {
                    SectionHeader("Available to hook", "${available.size}")
                    Card(shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column {
                            available.forEach { d ->
                                AvailableRow(
                                    d = d,
                                    showStatuses = showStatuses,
                                    showMac = showMac,
                                    onHook = { DeviceStore.setEnabled(d.mac, d.deviceId, true); tick++ },
                                    onManifestDownloaded = { tick++ },
                                )
                            }
                        }
                    }
                }
            }
        } else {
            BluetoothDisabledPlaceholder(btState, onEnable = { enableBluetooth() })
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BluetoothDisabledPlaceholder(
    btState: BtState,
    onEnable: () -> Unit,
    modifier: Modifier = Modifier
) {
    val circlePoly = remember { MaterialShapes.Circle }
    val pillPoly = remember { MaterialShapes.Pill }
    val morph = remember { Morph(circlePoly, pillPoly) }
    val shapeProgress by animateFloatAsState(
        targetValue = if (btState == BtState.ON) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "placeholderShape"
    )
    val animatedShape = remember(shapeProgress) { MorphShape(morph, shapeProgress) }

    Box(
        modifier = modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            val chipBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
            val tint = if (btState == BtState.TURNING_ON) BluetoothBlue else MaterialTheme.colorScheme.onSurfaceVariant

            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(84.dp)) {
                if (btState == BtState.TURNING_ON) {
                    CircularWavyProgressIndicator(
                        modifier = Modifier.size(84.dp),
                        color = tint
                    )
                }
                ShapeChip(shape = animatedShape, size = 72.dp, color = chipBg) {
                    Icon(
                        if (btState == BtState.TURNING_ON) Icons.Filled.Bluetooth else Icons.Filled.BluetoothDisabled,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = if (btState == BtState.TURNING_ON) "Enabling Bluetooth..." else "Bluetooth is disabled",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = if (btState == BtState.TURNING_ON) "Please wait a moment" else "Orchestra requires Bluetooth to scan and manage devices",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Button(
                onClick = onEnable,
                enabled = btState == BtState.OFF,
                modifier = Modifier.height(48.dp),
                shape = CircleShape
            ) {
                Text(if (btState == BtState.TURNING_ON) "Enabling..." else "Enable")
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        if (count.isNotEmpty()) Text(count, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- battery display ----------

private fun battColor(pct: Int): Color = when {
    pct < 0 -> Color.Gray
    pct <= 15 -> StatusBad
    pct <= 35 -> StatusIdle
    else -> StatusGood
}



private fun battIcon(pct: Int, charging: Boolean): ImageVector = when {
    charging -> Icons.Filled.BatteryChargingFull
    pct < 0 -> Icons.Filled.BatteryAlert
    pct <= 15 -> Icons.Filled.BatteryAlert
    pct <= 60 -> Icons.Filled.Battery3Bar
    pct <= 90 -> Icons.Filled.Battery5Bar
    else -> Icons.Filled.BatteryFull
}

/** Map a manifest device_type to its Devices-list icon. Unknown/null -> Headphones. */
@Composable
fun iconForType(type: String?): ImageVector = when (type) {
    "speaker" -> Icons.Filled.Speaker
    "earbuds" -> Icons.Filled.Earbuds
    "earbuds_2" -> ImageVector.vectorResource(id = R.drawable.outline_earbuds_2_24)
    "headset_mic" -> Icons.Filled.HeadsetMic
    else -> Icons.Filled.Headphones
}

/** Resolve a hooked/eligible device's icon from its manifest device_type (loaded once per id). */
@Composable
private fun iconForDevice(deviceId: String): ImageVector =
    iconForType(DeviceDef.loadById(deviceId)?.deviceType)

/** One battery chip: icon (colored by level) + percentage. */
@Composable
private fun BatteryChip(label: String?, pct: Int, charging: Boolean) {
    if (pct < 0) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(battIcon(pct, charging), contentDescription = label,
            tint = battColor(pct), modifier = Modifier.size(18.dp))
        Column {
            if (label != null) Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$pct%", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** TWS → L / Case / R chips; otherwise a single battery chip. */
@Composable
private fun BatteryRow(b: Batt) {
    if (b.tws && (b.left >= 0 || b.right >= 0 || b.case >= 0)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BatteryChip("Left", b.left, b.leftChg)
            BatteryChip("Case", b.case, b.caseChg)
            BatteryChip("Right", b.right, b.rightChg)
        }
    } else {
        BatteryChip(null, b.main, b.mainChg)
    }
}

// ---------- device cards ----------

private fun statusLine(d: BondedDevice, showMac: Boolean, activeCount: Int?, controlCount: Int?): String {
    val parts = mutableListOf(if (d.connected) "Connected" else "Disconnected")
    if (activeCount != null && controlCount != null) parts += "$activeCount/$controlCount controls"
    if (showMac) parts += d.mac
    return parts.joinToString("  ·  ")
}

@Composable
private fun HookedDeviceCard(
    d: BondedDevice,
    refreshKey: Int,
    tick: Int,
    showStatuses: Boolean,
    showUnverified: Boolean,
    showInApp: Boolean,
    showMac: Boolean,
    onUnhook: () -> Unit,
    onChange: () -> Unit,
    onManifestUpdated: () -> Unit = {},
    onOpen: () -> Unit = {},
) {
    // Eligibility: update-available + sideload badge. Computed off the main thread.
    val eligibility by produceState<DeviceEligibility?>(initialValue = null, d, tick) {
        value = withContext(Dispatchers.IO) {
            val source = ManifestRepository.sourceOf(d.deviceId)
            val sideloaded = ManifestRepository.isSideloaded(d.deviceId)
            // ManifestUpdater.updateRevisions returns int[]{local, index} or null.
            val revs = ManifestUpdater.updateRevisions(d.deviceId)
            val updateRev: Pair<Int, Int>? = revs?.let { Pair(it[0], it[1]) }
            DeviceEligibility(
                matchedId = d.deviceId,
                source = source,
                updateRev = updateRev,
                sideloaded = sideloaded,
            )
        }
    }

    var expanded by rememberSaveable(d.mac) { mutableStateOf(false) }
    val caps = remember(d.mac, refreshKey, tick) { DeviceStore.capabilities(d.mac) }
    val controls = caps.filter { it.type != "info" }
    // Buckets: in-app-only (not injectable) split out; the rest split verified vs unverified.
    val inApp = controls.filter { it.status == "UNAVAILABLE" }
    val rest = controls.filter { it.status != "UNAVAILABLE" }
    val verified = rest.filter { it.verified }
    val unverified = rest.filter { !it.verified }
    // In-app-only controls (status UNAVAILABLE) never inject, but they work in-app — count them
    // as enabled unless the user toggled one off, so the header reads 7/7 rather than 4/7.
    val activeCount = controls.count { c ->
        c.status == "ACTIVE" || (c.status == "UNAVAILABLE" && (!c.toggleable || c.enabled))
    }

    // Ear-detection: only for AAP devices (those with an ear_detection capability in their manifest).
    val isAacpDevice = caps.any { it.id == "ear_detection" }
    var liveTick by remember(d.mac) { mutableIntStateOf(0) }
    val earStatus = remember(refreshKey, tick, liveTick, d.mac) {
        if (isAacpDevice) AapState.forMac(d.mac).earSummary() ?: "—"
        else null
    }
    if (isAacpDevice) {
        // The SystemUI broker owns the AAP socket (Plan 6); the app process never opens one. Just
        // subscribe to the app-side listener registry — AacpClientBridge fires it on AAP_STATE, so
        // the live ear-detection preview still updates.
        DisposableEffect(d.mac) {
            AacpEngine.registerListener(d.mac, "ui-ear") { liveTick++ }
            onDispose { AacpEngine.unregisterListener(d.mac, "ui-ear") }
        }
    }

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().animateContentSize(
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ShapeChip(
                    shape = remember(d.mac) { shapeForSeed(d.mac) },
                    size = 48.dp,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(iconForDevice(d.deviceId), contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(d.name, style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(
                            if (d.connected) StatusGood else MaterialTheme.colorScheme.onSurfaceVariant))
                        Text(statusLine(d, showMac, activeCount, controls.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Battery row (when connected + enabled).
                    if (showStatuses && d.connected && d.batt.any) {
                        Spacer(Modifier.height(2.dp))
                        BatteryRow(d.batt)
                    }
                    // Ear-detection row (AAP devices only).
                    if (earStatus != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Ear detection: $earStatus",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Eligibility chips: update-available + sideload badge.
                    val elig = eligibility
                    if (elig != null) {
                        val updateRev = elig.updateRev
                        if (updateRev != null) {
                            Spacer(Modifier.height(4.dp))
                            var updating by remember { mutableStateOf(false) }
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(BluetoothBlue.copy(alpha = 0.12f))
                                    .clickable(enabled = !updating) {
                                        updating = true
                                        ManifestUpdater.ensureManifestAsync(d.deviceId) { onManifestUpdated() }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (updating) {
                                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp,
                                        color = BluetoothBlue)
                                } else {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null,
                                        tint = BluetoothBlue, modifier = Modifier.size(14.dp))
                                }
                                Text(
                                    "Update available  rev ${updateRev.first} → ${updateRev.second}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BluetoothBlue,
                                )
                            }
                        }
                        if (elig.sideloaded) {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(StatusIdle.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(Icons.Filled.BugReport, contentDescription = null,
                                    tint = StatusIdle, modifier = Modifier.size(14.dp))
                                Text("local / testing", style = MaterialTheme.typography.labelSmall,
                                    color = StatusIdle)
                            }
                        }
                    }
                }
                Switch(checked = true, onCheckedChange = { onUnhook() })
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = "Expand")
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(),
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    verified.forEach { c ->
                        CapabilityRow(c, onToggle = {
                            DeviceStore.setCapabilityEnabled(d.mac, c.id, !c.enabled); onChange()
                        })
                    }
                    if (showUnverified && unverified.isNotEmpty()) {
                        Accordion("Unverified", unverified.size, StatusIdle, d.mac + "_unv") {
                            unverified.forEach { c ->
                                CapabilityRow(c, onToggle = {
                                    DeviceStore.setCapabilityEnabled(d.mac, c.id, !c.enabled); onChange()
                                })
                            }
                        }
                    }
                    if (showInApp && inApp.isNotEmpty()) {
                        Accordion("In-app only", inApp.size,
                            MaterialTheme.colorScheme.onSurfaceVariant, d.mac + "_inapp") {
                            inApp.forEach { c ->
                                CapabilityRow(c, onToggle = {
                                    DeviceStore.setCapabilityEnabled(d.mac, c.id, !c.enabled); onChange()
                                })
                            }
                        }
                    }
                    // Only AAP (AirPods-protocol) devices have a dedicated in-app control screen
                    // today. Lives here (not in the header) to keep the header row uncluttered.
                    if (isAacpDevice) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen() }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Device controls", style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.ChevronRight,
                                contentDescription = "Open device controls",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** Collapsible sub-section inside a device card. */
@Composable
private fun Accordion(
    title: String, count: Int, accent: Color, key: String, content: @Composable () -> Unit,
) {
    var open by rememberSaveable(key) { mutableStateOf(false) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        Modifier.fillMaxWidth().clickable { open = !open }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = accent)
        Text("$count", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
    }
    AnimatedVisibility(
        visible = open,
        enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
        exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(),
    ) { Column { content() } }
}

@Composable
private fun CapabilityRow(c: DeviceStore.Capability, onToggle: () -> Unit) {
    data class S(val icon: ImageVector, val color: Color, val label: String)
    val s = when (c.status) {
        "ACTIVE" -> S(Icons.Filled.CheckCircle, StatusGood, "Active")
        "AVAILABLE" -> S(Icons.Filled.RadioButtonUnchecked, MaterialTheme.colorScheme.onSurfaceVariant, "Off")
        "CONFLICT" -> S(Icons.Filled.ErrorOutline, StatusBad, "Conflict")
        "PENDING" -> S(Icons.AutoMirrored.Filled.HelpOutline, StatusIdle, "Pending")
        else -> S(Icons.AutoMirrored.Filled.OpenInNew, MaterialTheme.colorScheme.onSurfaceVariant, "In-app only")
    }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(s.color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) { Icon(s.icon, contentDescription = null, tint = s.color, modifier = Modifier.size(20.dp)) }
        },
        headlineContent = { Text(c.title) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${s.label} · ${c.type}", style = MaterialTheme.typography.bodySmall,
                        color = s.color)
                    if (!c.verified) Text("· unverified",
                        style = MaterialTheme.typography.bodySmall, color = StatusIdle)
                }
                if (c.reason.isNotEmpty()) Text(
                    c.reason, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = {
            if (c.toggleable) Switch(checked = c.enabled, onCheckedChange = { onToggle() })
        }
    )
}

@Composable
private fun AvailableRow(
    d: BondedDevice,
    showStatuses: Boolean,
    showMac: Boolean,
    onHook: () -> Unit,
    onManifestDownloaded: () -> Unit = {},
) {
    // Compute eligibility off the main thread (ManifestRepository I/O).
    // Keyed only on d: when download completes, tick++ in DevicesScreen causes supported to
    // recompute, d.hasLocalManifest changes, and a new BondedDevice instance is passed in.
    val eligibility by produceState<DeviceEligibility?>(initialValue = null, d) {
        value = withContext(Dispatchers.IO) {
            val source = ManifestRepository.sourceOf(d.deviceId)
            val sideloaded = ManifestRepository.isSideloaded(d.deviceId)
            DeviceEligibility(
                matchedId = d.deviceId,
                source = source,
                updateRev = null, // update chips belong to HookedDeviceCard, not AvailableRow
                sideloaded = sideloaded,
            )
        }
    }

    val isIndexOnly = !d.hasLocalManifest
    var downloading by remember { mutableStateOf(false) }

    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            ShapeChip(
                shape = remember(d.mac) { shapeForSeed(d.mac) },
                size = 40.dp,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(iconForDevice(d.deviceId), contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        },
        headlineContent = { Text(d.name) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(
                        if (d.connected) StatusGood else MaterialTheme.colorScheme.onSurfaceVariant))
                    Text(statusLine(d, showMac, null, null), style = MaterialTheme.typography.bodySmall)
                    if (showStatuses && d.connected && d.batt.main >= 0)
                        Text("· ${d.batt.main}%", style = MaterialTheme.typography.bodySmall)
                }
                // Sideload badge (for available, not-yet-hooked devices with a sideloaded manifest).
                if (eligibility?.sideloaded == true) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(StatusIdle.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Filled.BugReport, contentDescription = null,
                            tint = StatusIdle, modifier = Modifier.size(14.dp))
                        Text("local / testing", style = MaterialTheme.typography.labelSmall,
                            color = StatusIdle)
                    }
                }
            }
        },
        trailingContent = {
            if (isIndexOnly) {
                // Index-only device — show "Supported · Download" button.
                if (downloading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    OutlinedButton(
                        onClick = {
                            downloading = true
                            ManifestUpdater.ensureManifestAsync(d.deviceId) { onManifestDownloaded() }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("Supported · Download", style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else {
                Switch(checked = false, onCheckedChange = { onHook() })
            }
        }
    )
}

// ---------- Settings ----------

/** Connected-corner position of a row within its settings group. */
private enum class RowPos { Single, First, Middle, Last }

private fun rowShape(pos: RowPos): RoundedCornerShape {
    val o = 28.dp
    val i = 6.dp
    return when (pos) {
        RowPos.Single -> RoundedCornerShape(o)
        RowPos.First -> RoundedCornerShape(o, o, i, i)
        RowPos.Middle -> RoundedCornerShape(i)
        RowPos.Last -> RoundedCornerShape(i, i, o, o)
    }
}

@Composable
private fun SettingsScreen(onOpenDebug: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp)
    ) {
        SettingsGroup(null) {
            SettingToggle("Show device statuses", "Battery and connection on each device card",
                RowPos.Single,
                DeviceStore.flag(FLAG_STATUSES, true)) { DeviceStore.setFlag(FLAG_STATUSES, it); tick++ }
        }

        SettingsGroup("Bluetooth identity") {
            SettingToggle("Act as Apple device",
                "Advertise the phone as Apple so AirPods stay connected to it and other devices at " +
                    "once. Takes effect after Bluetooth is restarted. While on, the phone identifies " +
                    "as Apple to all Bluetooth devices.",
                RowPos.Single,
                DeviceStore.flag(FLAG_ACT_AS_APPLE, false)) { DeviceStore.setFlag(FLAG_ACT_AS_APPLE, it); tick++ }
        }

        SettingsGroup("Debug") {
            SettingToggle("Show unverified controls", "Reveal controls whose bytes aren't hardware-confirmed",
                RowPos.First,
                DeviceStore.flag(FLAG_UNVERIFIED, false)) { DeviceStore.setFlag(FLAG_UNVERIFIED, it); tick++ }
            SettingToggle("Show in-app-only controls", "Controls with no native page surface (sliders, composite)",
                RowPos.Middle,
                DeviceStore.flag(FLAG_INAPP, false)) { DeviceStore.setFlag(FLAG_INAPP, it); tick++ }
            SettingToggle("Show device MAC", "Display the Bluetooth address on device cards",
                RowPos.Middle,
                DeviceStore.flag(FLAG_MAC, false)) { DeviceStore.setFlag(FLAG_MAC, it); tick++ }
            SettingNav("Debug & logs", "Live event log, catalog and runtime info",
                RowPos.Last, onOpenDebug)
        }
        if (tick < 0) Text("")
    }
}

/** Titled group of connected settings rows (primary-colored header, 3dp inner gaps). */
@Composable
private fun SettingsGroup(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 6.dp, bottom = 13.dp))
        }
        content()
    }
}

@Composable
private fun SettingRowScaffold(
    title: String,
    subtitle: String,
    pos: RowPos,
    onClick: (() -> Unit)?,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(rowShape(pos))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 22.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 20.sp, lineHeight = 26.sp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
private fun SettingToggle(
    title: String, subtitle: String, pos: RowPos,
    checked: Boolean, onChange: (Boolean) -> Unit,
) {
    SettingRowScaffold(title, subtitle, pos, onClick = { onChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingNav(title: String, subtitle: String, pos: RowPos, onClick: () -> Unit) {
    SettingRowScaffold(title, subtitle, pos, onClick = onClick) {
        Icon(Icons.Filled.ChevronRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- Debug screen ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugScreen(refreshKey: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val moduleActive = remember(refreshKey, tick) { runCatching { XposedSelf.active() }.getOrDefault(false) }
    val apiLevel = remember(refreshKey, tick) { runCatching { XposedSelf.apiLevel() }.getOrDefault(-1) }
    val supported = remember(refreshKey, tick) { bondedSupported(context) }
    val enabled = remember(refreshKey, tick) { DeviceStore.enabledMap() }
    val catalog = remember(refreshKey, tick) { DeviceStore.catalog() }
    val log = remember(tick) { Logbook.lines().asReversed() }

    // Snackbar for SAF load results.
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // SAF picker: ACTION_OPEN_DOCUMENT for application/json.
    val pickManifest = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val body = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (body != null) {
                runCatching {
                    val obj = JSONObject(body)
                    val id = obj.optString("id").ifEmpty { error("manifest has no id") }
                    require(obj.optInt("schema_version") in 3..3) { "unsupported schema_version" }
                    ManifestRepository.saveSideload(id, body)
                    id
                }.onSuccess { id ->
                    withContext(Dispatchers.Main) { tick++ }
                    snackbarHostState.showSnackbar("Loaded $id (testing)")
                }.onFailure { e ->
                    snackbarHostState.showSnackbar("Invalid manifest: ${e.message}")
                }
            }
        }
    }

    // Manifest sources: enumerate across sideload + downloaded + bundled catalog.
    // Computed off main thread on tick changes.
    data class ManifestSource(val id: String, val source: String, val revision: Int)
    val manifestSources by produceState(initialValue = emptyList(), refreshKey, tick) {
        value = withContext(Dispatchers.IO) {
            val seen = linkedSetOf<String>()
            // Sideloaded (highest precedence).
            seen += ManifestRepository.sideloadedIds()
            // Downloaded.
            seen += ManifestRepository.downloadedIds()
            // Bundled catalog.
            seen += DeviceStore.catalog().map { it.id }
            seen.map { id ->
                val src = ManifestRepository.sourceOf(id) ?: "bundled"
                val rev = ManifestUpdater.revisionOf(id)
                ManifestSource(id, src, rev)
            }.sortedBy { it.id }
        }
    }

    val info = buildString {
        appendLine("Orchestra ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
        appendLine("applicationId: ${BuildConfig.APPLICATION_ID}")
        appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("module active: $moduleActive  (LSPosed api $apiLevel)")
        appendLine("bluetooth: ${btEnabled(context)}")
        appendLine()
        appendLine("catalog (${catalog.size}):")
        catalog.forEach { appendLine("  ${it.id}  [${it.name}]") }
        appendLine()
        appendLine("hooked (${enabled.size}):")
        enabled.forEach { (mac, id) -> appendLine("  $mac -> $id") }
        appendLine()
        appendLine("bonded supported (${supported.size}):")
        supported.forEach {
            appendLine("  ${it.mac}  ${it.name}  conn=${it.connected}  " +
                "batt L${it.batt.left}/C${it.batt.case}/R${it.batt.right}/M${it.batt.main}")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug & logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ----- Load manifest from file -----
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Sideload manifest", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(
                    onClick = { pickManifest.launch(arrayOf("application/json")) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Filled.BugReport, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Load manifest from file", style = MaterialTheme.typography.labelMedium)
                }
            }

            // ----- Manifest sources -----
            Text("Manifest sources (${manifestSources.size})", style = MaterialTheme.typography.titleMedium)
            if (manifestSources.isEmpty()) {
                DebugCard("(loading…)")
            } else {
                val sourcesText = buildString {
                    manifestSources.forEach { ms ->
                        val revStr = if (ms.revision >= 0) "rev ${ms.revision}" else "rev ?"
                        appendLine("${ms.id}  [${ms.source}  $revStr]")
                    }
                }
                DebugCard(sourcesText.trimEnd())
            }

            // ----- Event log -----
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Event log (${log.size})", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { tick++ }) { Text("Refresh") }
                    TextButton(onClick = { Logbook.clear(); tick++ }) { Text("Clear") }
                }
            }
            DebugCard(if (log.isEmpty()) "(no events yet — tap a control)" else log.joinToString("\n"))
            Text("Runtime info", style = MaterialTheme.typography.titleMedium)
            DebugCard(info)
        }
    }
}

@Composable
private fun DebugCard(text: String) {
    Card(colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(28.dp)) {
        SelectionContainer {
            Text(text, modifier = Modifier.padding(16.dp),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private enum class BtState {
    OFF, TURNING_ON, ON, TURNING_OFF
}

@Composable
private fun rememberBluetoothState(): BtState {
    val context = LocalContext.current
    var state by remember {
        mutableStateOf(if (btEnabled(context)) BtState.ON else BtState.OFF)
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_OFF -> state = BtState.OFF
                        BluetoothAdapter.STATE_TURNING_ON -> state = BtState.TURNING_ON
                        BluetoothAdapter.STATE_ON -> state = BtState.ON
                        BluetoothAdapter.STATE_TURNING_OFF -> state = BtState.TURNING_OFF
                    }
                }
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(receiver, filter)
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
    return state
}


