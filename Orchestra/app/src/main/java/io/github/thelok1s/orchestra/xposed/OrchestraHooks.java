package io.github.thelok1s.orchestra.xposed;

import android.app.AndroidAppHelper;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import org.json.JSONArray;
import org.json.JSONObject;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Single LSPosed entry point, dispatched by target package:
 *
 *  • com.android.systemui — clears the Pixel-UUID gate + forces ANC availability so the native
 *    volume-panel Noise Control tile renders (fed by our device-settings provider, setting id
 *    1001), and routes the popup's per-cell taps to our proxy over RFCOMM (the native popup's
 *    updateState callback doesn't reach our provider on the volume path).
 *
 *  • com.android.settings — writes BluetoothDevice metadata key 25 (DEVICE_SETTINGS_CONFIG_*)
 *    from this privileged process, pointing the system at our config provider. This replaces the
 *    KSU priv-app: Settings holds BLUETOOTH_PRIVILEGED, so setMetadata succeeds here without root.
 */
public class OrchestraHooks implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static volatile String modulePath;

    @Override
    public void initZygote(StartupParam sp) {
        modulePath = sp.modulePath;
    }

    private static final String TAG = "Orchestra";

    /**
     * Diagnostic log that reaches BOTH the Xposed/Vector module log AND logcat. On Vector,
     * {@link XposedBridge#log} is routed to a root-only store, so an {@code android.util.Log} call
     * (emitted from the host process's uid) is the only line we can read back over adb
     * ({@code adb logcat -s OrchestraMX}). Used to trace whether the volume-panel hooks install and
     * fire at runtime.
     */
    private static void mx(String s) {
        XposedBridge.log("[MX] " + s);
        android.util.Log.i("OrchestraMX", s);
    }

    private static final String PIXEL_DEVICE_INTERACTOR =
            "com.google.android.systemui.volume.panel.domain.interactor.PixelDeviceInteractor";
    private static final String ANC_GOOGLE_CRITERIA =
            "com.google.android.systemui.volume.panel.component.anc.domain.AncAvailabilityGoogleCriteria";
    private static final String TOGGLE_CLICK_LAMBDA =
            "com.android.systemui.volume.panel.component.devicesetting.ui.composable.DeviceSettingPopup$$ExternalSyntheticLambda7";

    // --- metadata key 25 (mirror of io.github.thelok1s.orchestra.Metadata; kept self-contained so the
    //     Settings-process hook has no dependency on the app's Context/DeviceStore) ---
    private static final int KEY25 = 25;
    private static final String VAL_PACKAGE = "io.github.thelok1s.orchestra";
    private static final String VAL_CLASS = "io.github.thelok1s.orchestra.ConfigProviderService";
    private static final String VAL_ACTION = "io.github.thelok1s.orchestra.BIND_DEVICE_SETTINGS_CONFIG_PROVIDER";
    // Device-name gate, sourced from the bundled assets/index.json (name_regex per manifest), read
    // from the module APK. Fail-soft fallback keeps known families tagged if the asset can't be read.
    private static final Pattern FALLBACK = Pattern.compile("(?i)soundcore|airpods");
    private static volatile List<Pattern> gatePatterns;

    private static boolean nameSupported(String name) {
        if (name == null) return false;
        List<Pattern> pats = gatePatterns;
        if (pats == null) { pats = loadGatePatterns(); gatePatterns = pats; }
        if (pats.isEmpty()) return FALLBACK.matcher(name).find();
        for (Pattern p : pats) if (p.matcher(name).find()) return true;
        return false;
    }

    private static List<Pattern> loadGatePatterns() {
        List<Pattern> out = new ArrayList<>();
        String path = modulePath;
        if (path == null) return out;
        try (ZipFile zip = new ZipFile(path)) {
            java.util.zip.ZipEntry e = zip.getEntry("assets/index.json");
            if (e == null) return out;
            String json;
            try (InputStream in = zip.getInputStream(e)) {
                byte[] buf = new byte[in.available() > 0 ? in.available() : 8192];
                int n;
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
                json = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
            JSONObject root = new JSONObject(json);
            JSONArray mans = root.optJSONArray("manufacturers");
            if (mans != null) for (int i = 0; i < mans.length(); i++) {
                JSONArray devs = mans.getJSONObject(i).optJSONArray("devices");
                if (devs == null) continue;
                for (int j = 0; j < devs.length(); j++) {
                    String rx = devs.getJSONObject(j).optString("name_regex", null);
                    if (rx != null && !rx.isEmpty()) {
                        try { out.add(Pattern.compile(rx)); } catch (Throwable ignore) {}
                    }
                }
            }
            XposedBridge.log("[MX] device gate: " + out.size() + " pattern(s) from bundled index");
        } catch (Throwable t) {
            XposedBridge.log("[MX] device gate load failed, using fallback: " + t);
        }
        return out;
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        switch (lp.packageName) {
            case "com.android.systemui":
                // LSPosed fires handleLoadPackage for EVERY process of the package — including
                // on-demand helpers like com.android.systemui:screenshot. A broker started in a
                // helper would fight the main broker for the single-owner AAP socket (observed
                // live: taking a screenshot spawned a second broker whose connect storm starved
                // battery/ear from the real session). Main process only.
                if (!"com.android.systemui".equals(lp.processName)) {
                    XposedBridge.log("[MX] skipping SystemUI helper process " + lp.processName);
                    return;
                }
                mx("loaded into SystemUI (proc=" + lp.processName + ")");
                hookPixelDevice(lp.classLoader);
                forceAncAvailable(lp.classLoader);
                hookToggleApply(lp.classLoader);
                startBroker();
                break;
            case "com.android.settings":
                // Same helper-process guard as SystemUI (the BT settings UI + metadata writer live
                // in the main process; a duplicate battery receiver in a helper is useless churn).
                if (!"com.android.settings".equals(lp.processName)) {
                    XposedBridge.log("[MX] skipping Settings helper process " + lp.processName);
                    return;
                }
                XposedBridge.log("[MX] loaded into Settings (metadata writer)");
                hookSettingsMetadataWriter(lp.classLoader);
                break;
            case "io.github.thelok1s.orchestra":
                // Module-active sentinel for our own UI.
                try {
                    XposedHelpers.findAndHookMethod("io.github.thelok1s.orchestra.XposedSelf", lp.classLoader,
                            "active", new XC_MethodHook() {
                                @Override protected void afterHookedMethod(MethodHookParam p) { p.setResult(Boolean.TRUE); }
                            });
                    final int api = XposedBridge.getXposedVersion();
                    XposedHelpers.findAndHookMethod("io.github.thelok1s.orchestra.XposedSelf", lp.classLoader,
                            "apiLevel", new XC_MethodHook() {
                                @Override protected void afterHookedMethod(MethodHookParam p) { p.setResult(api); }
                            });
                    XposedBridge.log("[MX] self-active sentinel set (api " + api + ")");
                } catch (Throwable t) { XposedBridge.log("[MX] self sentinel failed: " + t); }
                break;
            case "com.google.android.bluetooth":
                // DID spoof: load the ShadowHook-based native lib into the BT stack + install the
                // BTA_DmSetLocalDiRecord hook (Plan 10 / Path B). Main process only.
                if (!"com.google.android.bluetooth".equals(lp.processName)) return;
                XposedBridge.log("[MX] loaded into Bluetooth stack (DID hook)");
                startDidHook();
                break;
            default:
                break;
        }
    }

    private static volatile boolean didHookStarted = false;

    /** Load the ShadowHook-based DID lib into the Bluetooth stack process, precompute the
     *  BTA_DmSetLocalDiRecord symbol offset (slow, disk-only, doesn't need the module loaded), then
     *  — only if the user opted in via the default-off "Act as Apple device" toggle — tight-poll
     *  {@code armDidHook} so the inline hook installs the INSTANT libbluetooth_jni is mapped,
     *  before the stack's own DI-record write can run. This wins the race that the old
     *  precompute-at-hook-time approach lost ~2/3 of the time. Fail-soft: any failure logs and
     *  leaves the real DID untouched (default-off is a no-op path with zero native calls beyond
     *  precompute + a single setDidGate(false)). */
    private void startDidHook() {
        if (didHookStarted) return;
        didHookStarted = true;
        final String mp = modulePath;
        new Thread(() -> {
            try {
                if (mp == null) { XposedBridge.log("[MX] didhook: no modulePath"); return; }
                String libDir = new java.io.File(mp).getParent() + "/lib/arm64";
                System.load(libDir + "/libshadowhook.so");
                System.load(libDir + "/libl2c_fcr_hook.so");
                XposedBridge.log("[MX] didhook: libs loaded from " + libDir);

                long t0 = System.currentTimeMillis();
                io.github.thelok1s.orchestra.NativeBridge.precomputeDidOffset();
                XposedBridge.log("[MX] didhook: precompute done in " + (System.currentTimeMillis() - t0) + "ms");

                boolean enable = readActAsApple();
                if (!enable) {
                    io.github.thelok1s.orchestra.NativeBridge.setDidGate(false);
                    XposedBridge.log("[MX] didhook: act_as_apple OFF (real DID)");
                    return;
                }

                boolean armed = false;
                for (int i = 0; i < 4000 && !armed; i++) { // tight poll: hook the instant the module loads
                    armed = io.github.thelok1s.orchestra.NativeBridge.armDidHook(true);
                    if (!armed) { try { Thread.sleep(1); } catch (InterruptedException ie) { return; } }
                }
                XposedBridge.log("[MX] didhook: armed=" + armed + " at +" + (System.currentTimeMillis() - t0) + "ms");
            } catch (Throwable t) {
                XposedBridge.log("[MX] didhook start failed: " + t);
            }
        }, "orchestra-didhook").start();
    }

    /** Cross-process read of the (default-off) act_as_apple pref via {@code StateProvider}, since
     *  this hook runs in com.google.android.bluetooth and can't touch the app's SharedPreferences
     *  directly. Mirrors {@link io.github.thelok1s.orchestra.aap.AapBroker}'s behavior-pull pattern:
     *  retry for a Context (currentApplication() is null very early), any failure -> false
     *  (default-off, zero behavior change). */
    private static boolean readActAsApple() {
        try {
            android.app.Application app = null;
            for (int i = 0; i < 20 && app == null; i++) {
                app = AndroidAppHelper.currentApplication();
                if (app == null) { try { Thread.sleep(100); } catch (InterruptedException ignored) { return false; } }
            }
            if (app == null) { XposedBridge.log("[MX] didhook: no Context for act_as_apple read"); return false; }
            android.net.Uri uri = android.net.Uri.parse(
                    "content://io.github.thelok1s.orchestra.state/flag/act_as_apple");
            try (android.database.Cursor cur =
                         app.getContentResolver().query(uri, null, null, null, null)) {
                if (cur != null && cur.moveToFirst()) {
                    return cur.getInt(cur.getColumnIndexOrThrow("enabled")) == 1;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[MX] didhook: readActAsApple failed: " + t);
        }
        return false;
    }

    // ---------------- SystemUI: AAP connection broker (single-owner socket) ----------------

    private static volatile boolean brokerStarted = false;

    /**
     * Start the SystemUI-resident AAP broker once a SystemUI Context is available. SystemUI is
     * always alive, so it owns the L2CAP socket; the app process is a broadcast client. Reuses the
     * lazy-context pattern (currentApplication() is null very early), retried off the main thread.
     */
    private void startBroker() {
        if (brokerStarted) return;
        new Thread(() -> {
            try {
                android.app.Application app = null;
                for (int i = 0; i < 50 && app == null; i++) {
                    app = AndroidAppHelper.currentApplication();
                    if (app == null) { try { Thread.sleep(200); } catch (InterruptedException ignored) {} }
                }
                if (app == null) { XposedBridge.log("[MX] broker: no SystemUI context, giving up"); return; }
                if (brokerStarted) return;
                brokerStarted = true;
                io.github.thelok1s.orchestra.aap.AapBroker.start(app);
                XposedBridge.log("[MX] AAP broker started in SystemUI");
            } catch (Throwable t) {
                XposedBridge.log("[MX] broker start failed: " + t);
            }
        }, "mx-broker-start").start();
    }

    // ---------------- SystemUI: volume-panel ANC ----------------

    private void hookPixelDevice(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass(PIXEL_DEVICE_INTERACTOR, cl);
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().toLowerCase().contains("ispixeldevice")
                        && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) { p.setResult(Boolean.TRUE); }
                    });
                    n++;
                }
            }
            mx("pixelDevice: hooked " + n + " method(s) on " + c.getName());
        } catch (Throwable t) { mx("pixelDevice hook FAILED: " + t); }
    }

    private void forceAncAvailable(ClassLoader cl) {
        try {
            Class<?> flowKt = XposedHelpers.findClass("kotlinx.coroutines.flow.FlowKt", cl);
            final Method flowOf1 = singleFlowOf(flowKt);
            if (flowOf1 == null) mx("forceAncAvailable: flowOf(Object) NOT found on FlowKt");
            Class<?> crit = XposedHelpers.findClass(ANC_GOOGLE_CRITERIA, cl);
            int n = 0;
            for (Method m : crit.getDeclaredMethods()) {
                if (m.getName().equals("isAvailable") && m.getParameterCount() == 0) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                            if (flowOf1 != null) p.setResult(flowOf1.invoke(null, Boolean.TRUE));
                        }
                    });
                    n++;
                }
            }
            mx("AncGoogleCriteria.isAvailable: hooked " + n + " method(s) on " + crit.getName());
        } catch (Throwable t) { mx("forceAncAvailable FAILED: " + t); }
    }

    private void hookToggleApply(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass(TOGGLE_CLICK_LAMBDA, cl);
            int hooked = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("invoke")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                Object model = XposedHelpers.getObjectField(p.thisObject, "f$0");
                                int index = XposedHelpers.getIntField(p.thisObject, "f$1");
                                Object cached = XposedHelpers.getObjectField(model, "cachedDevice");
                                BluetoothDevice dev = (BluetoothDevice) XposedHelpers.getObjectField(cached, "mDevice");
                                String mac = dev.getAddress();
                                Context ctx = AndroidAppHelper.currentApplication();
                                Intent i = new Intent("io.github.thelok1s.orchestra.APPLY_INDEX")
                                        .setClassName("io.github.thelok1s.orchestra", "io.github.thelok1s.orchestra.VolumeApplyReceiver")
                                        .putExtra("mac", mac).putExtra("index", index);
                                ctx.sendBroadcast(i);
                                XposedBridge.log("[MX] toggle tap -> APPLY_INDEX " + mac + " idx=" + index);
                            } catch (Throwable t) {
                                XposedBridge.log("[MX] toggle apply failed: " + t);
                            }
                        }
                    });
                    hooked++;
                }
            }
            mx("hooked popup toggle click (Lambda7): " + hooked + " method(s)");
        } catch (Throwable t) { mx("hookToggleApply FAILED (class not found is expected if popup lazy): " + t); }
    }

    private static Method singleFlowOf(Class<?> flowKt) {
        for (Method m : flowKt.getDeclaredMethods())
            if (m.getName().equals("flowOf") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == Object.class) { m.setAccessible(true); return m; }
        return null;
    }

    // ---------------- Settings: privileged metadata writer ----------------

    /**
     * Settings holds BLUETOOTH_PRIVILEGED, so getMetadata/setMetadata succeed here. We assert the
     * config tags whenever a Settings screen resumes (cheap + idempotent; the user always passes
     * through Settings before opening a device's "About device" page).
     */
    private void hookSettingsMetadataWriter(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl, "onResume",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            assertTagsForBondedDevices();
                        }
                    });
            XposedBridge.log("[MX] Settings metadata writer armed (Activity.onResume)");
        } catch (Throwable t) { XposedBridge.log("[MX] settings hook failed: " + t); }
    }

    private void writeBattery(BluetoothDevice device) {
        try {
            android.app.Application app = AndroidAppHelper.currentApplication();
            if (app == null) return;
            android.net.Uri uri = android.net.Uri.parse(
                    "content://io.github.thelok1s.orchestra.state/battery/" + device.getAddress());
            Integer left = null, right = null, caseLvl = null;
            boolean lc = false, rc = false, cc = false;
            try (android.database.Cursor cur =
                         app.getContentResolver().query(uri, null, null, null, null)) {
                if (cur != null && cur.moveToFirst()) {
                    left = nz(cur.getInt(cur.getColumnIndexOrThrow("left")));
                    right = nz(cur.getInt(cur.getColumnIndexOrThrow("right")));
                    caseLvl = nz(cur.getInt(cur.getColumnIndexOrThrow("case_level")));
                    lc = cur.getInt(cur.getColumnIndexOrThrow("left_charging")) == 1;
                    rc = cur.getInt(cur.getColumnIndexOrThrow("right_charging")) == 1;
                    cc = cur.getInt(cur.getColumnIndexOrThrow("case_charging")) == 1;
                }
            }
            XposedHelpers.callMethod(device, "setMetadata", 6, "true".getBytes(StandardCharsets.UTF_8));
            XposedHelpers.callMethod(device, "setMetadata", 17, "Untethered Headset".getBytes(StandardCharsets.UTF_8));
            // Always write 10/11/12: a valid 0-100 string shows the component; an empty (invalid)
            // value clears a previously-written key so a disconnected component HIDES (the keys are
            // persistent — skipping a stale key would leave the old value on the header).
            XposedHelpers.callMethod(device, "setMetadata", 10, battBytes(left));
            XposedHelpers.callMethod(device, "setMetadata", 11, battBytes(right));
            XposedHelpers.callMethod(device, "setMetadata", 12, battBytes(caseLvl));
            XposedHelpers.callMethod(device, "setMetadata", 13, (lc ? "true" : "false").getBytes(StandardCharsets.UTF_8));
            XposedHelpers.callMethod(device, "setMetadata", 14, (rc ? "true" : "false").getBytes(StandardCharsets.UTF_8));
            XposedHelpers.callMethod(device, "setMetadata", 15, (cc ? "true" : "false").getBytes(StandardCharsets.UTF_8));
            XposedBridge.log("[MX] battery write " + device.getAddress()
                    + " L=" + left + " R=" + right + " C=" + caseLvl);
        } catch (Throwable t) {
            XposedBridge.log("[MX] battery write failed: " + t);
        }
    }

    /** -1 sentinel from the provider -> null (component unknown). */
    private static Integer nz(int v) { return v >= 0 && v <= 100 ? v : null; }

    /** Valid battery -> "0".."100"; null (unknown/disconnected) -> empty = invalid -> header hides it. */
    private static byte[] battBytes(Integer v) {
        return (v != null ? v.toString() : "").getBytes(StandardCharsets.UTF_8);
    }

    private static volatile boolean batteryReceiverRegistered = false;

    /**
     * Lazily registers a BroadcastReceiver in the Settings process that listens for
     * {@code io.github.thelok1s.orchestra.BATTERY_CHANGED} and calls {@link #writeBattery} so the
     * native Fast-Pair header updates live without requiring a Settings screen resume.
     *
     * Security model: the receiver is registered with
     * {@code io.github.thelok1s.orchestra.permission.BATTERY_BROADCAST} as the broadcastPermission,
     * requiring the sender to hold that permission. The Orchestra app self-holds its own
     * signature-level permission, so only Orchestra can poke this receiver. Plain
     * {@code sendBroadcast(intent)} (no receiver-permission arg) is used by the sender because
     * Settings (platform-signed) cannot hold an app-defined signature permission.
     */
    private void ensureBatteryReceiver() {
        if (batteryReceiverRegistered) return;
        try {
            android.app.Application app = AndroidAppHelper.currentApplication();
            if (app == null) return;
            android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                    try {
                        String mac = i.getStringExtra("mac");
                        if (mac == null) return;
                        BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
                        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
                        if (adapter == null) return;
                        BluetoothDevice d = adapter.getRemoteDevice(mac);
                        if (isAapDevice(d)) writeBattery(d); // re-query ContentProvider + write keys
                    } catch (Throwable t) { XposedBridge.log("[MX] battery receiver: " + t); }
                }
            };
            android.content.IntentFilter f =
                    new android.content.IntentFilter("io.github.thelok1s.orchestra.BATTERY_CHANGED");
            // broadcastPermission: only Orchestra (same signer, self-holds it) can deliver.
            // RECEIVER_EXPORTED: allow delivery from a different uid (Orchestra app process).
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(r, f, "io.github.thelok1s.orchestra.permission.BATTERY_BROADCAST",
                        null, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(r, f, "io.github.thelok1s.orchestra.permission.BATTERY_BROADCAST",
                        null);
            }
            batteryReceiverRegistered = true;
            XposedBridge.log("[MX] battery-changed receiver registered");
        } catch (Throwable t) {
            XposedBridge.log("[MX] battery receiver register failed: " + t);
        }
    }

    private static final java.util.UUID AAP_UUID =
            java.util.UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a");

    private static boolean isAapDevice(BluetoothDevice d) {
        try {
            android.os.ParcelUuid[] uuids = d.getUuids();
            if (uuids != null) for (android.os.ParcelUuid p : uuids) {
                if (AAP_UUID.equals(p.getUuid())) return true;
            }
        } catch (Throwable ignore) {}
        return false;
    }

    private void assertTagsForBondedDevices() {
        ensureBatteryReceiver(); // idempotent; currentApplication() is non-null here (onResume)
        try {
            android.app.Application app = AndroidAppHelper.currentApplication();
            if (app == null) return;
            BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
            if (adapter == null || !adapter.isEnabled()) return;
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) return;
            for (BluetoothDevice d : bonded) {
                String name = safeName(d);
                if (!nameSupported(name)) continue;
                // FAILSAFE: only advertise our device-settings provider (key 25) for devices we
                // actually SERVE (hooked). SystemUI's volume panel fetches the device-settings
                // layout ONCE per device per session and memoizes it; if it fetches for a device we
                // don't serve, it caches an EMPTY layout and the ANC tile stays gone until a SystemUI
                // restart — even after the user hooks the device. Keeping key 25 aligned with the
                // hooked-state (write our tags when hooked, strip them when not) prevents that.
                if (isDeviceHooked(d.getAddress())) {
                    assertConfigTags(d);
                    if (isAapDevice(d)) writeBattery(d);
                } else {
                    clearConfigTags(d);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[MX] assertTags failed: " + t);
        }
    }

    private static String safeName(BluetoothDevice d) {
        try { return d.getName(); } catch (Throwable t) { return null; }
    }

    private void assertConfigTags(BluetoothDevice device) {
        try {
            String existing = readKey25(device);
            if (existing == null) existing = "";
            String updated = upsert(existing, "DEVICE_SETTINGS_CONFIG_PACKAGE_NAME", VAL_PACKAGE);
            updated = upsert(updated, "DEVICE_SETTINGS_CONFIG_CLASS", VAL_CLASS);
            updated = upsert(updated, "DEVICE_SETTINGS_CONFIG_ACTION", VAL_ACTION);
            // The volume-panel ANC tile is device-settings driven and we force its availability via
            // the SystemUI hook, so the HEARABLE_CONTROL_SLICE_WITH_WIDTH slice is NOT needed; strip
            // any stale tag a previous build wrote (its provider app is being retired).
            updated = updated.replaceAll(
                    "<HEARABLE_CONTROL_SLICE_WITH_WIDTH>.*?</HEARABLE_CONTROL_SLICE_WITH_WIDTH>", "");
            if (updated.equals(existing)) return; // already correct
            Object res = XposedHelpers.callMethod(device, "setMetadata", KEY25, updated.getBytes(StandardCharsets.UTF_8));
            boolean ok = !(res instanceof Boolean) || (Boolean) res;
            mx("key25 write " + (ok ? "ok" : "FAILED") + " (hooked) for " + device.getAddress());
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            XposedBridge.log("[MX] setMetadata failed: " + c);
        }
    }

    /**
     * Is {@code mac} currently "hooked" (has a served def) in the app? Read cross-process from the
     * app's {@link StateProvider} ({@code content://…/enabled/<MAC>}), like the battery reader.
     * Fails OPEN (assume hooked) on any error, so a transient query failure never strips a working
     * device's config.
     */
    private boolean isDeviceHooked(String mac) {
        if (mac == null) return false;
        try {
            android.app.Application app = AndroidAppHelper.currentApplication();
            if (app == null) return true;
            android.net.Uri uri = android.net.Uri.parse(
                    "content://io.github.thelok1s.orchestra.state/enabled/" + mac);
            try (android.database.Cursor c =
                         app.getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    return c.getInt(c.getColumnIndexOrThrow("enabled")) == 1;
                }
                return false; // provider reachable but no row -> definitively not hooked
            }
        } catch (Throwable t) {
            XposedBridge.log("[MX] isDeviceHooked query failed, assuming hooked: " + t);
            return true; // fail-open
        }
    }

    /** Remove our DEVICE_SETTINGS_CONFIG_* tags from key 25 for an un-hooked device, so SystemUI
     *  stops binding our (empty) provider for it (no poisoned/empty layout cache). No-op if absent. */
    private void clearConfigTags(BluetoothDevice device) {
        try {
            String existing = readKey25(device);
            if (existing == null || existing.isEmpty()) return;
            String updated = existing
                    .replaceAll("<DEVICE_SETTINGS_CONFIG_PACKAGE_NAME>.*?</DEVICE_SETTINGS_CONFIG_PACKAGE_NAME>", "")
                    .replaceAll("<DEVICE_SETTINGS_CONFIG_CLASS>.*?</DEVICE_SETTINGS_CONFIG_CLASS>", "")
                    .replaceAll("<DEVICE_SETTINGS_CONFIG_ACTION>.*?</DEVICE_SETTINGS_CONFIG_ACTION>", "");
            if (updated.equals(existing)) return; // our tags weren't present
            XposedHelpers.callMethod(device, "setMetadata", KEY25, updated.getBytes(StandardCharsets.UTF_8));
            mx("key25 cleared (un-hooked) for " + device.getAddress());
        } catch (Throwable t) {
            XposedBridge.log("[MX] clearConfigTags failed: " + t);
        }
    }

    private static String readKey25(BluetoothDevice device) {
        try {
            byte[] res = (byte[]) XposedHelpers.callMethod(device, "getMetadata", KEY25);
            if (res != null) return new String(res, StandardCharsets.UTF_8);
        } catch (Throwable ignore) {}
        return null;
    }

    private static String upsert(String src, String tag, String value) {
        String stripped = src.replaceAll("<" + Pattern.quote(tag) + ">.*?</" + Pattern.quote(tag) + ">", "");
        return stripped + "<" + tag + ">" + value + "</" + tag + ">";
    }
}
