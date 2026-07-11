package io.github.thelok1s.orchestra.xposed;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.thelok1s.orchestra.xposed.engine.HookCtx;
import io.github.thelok1s.orchestra.xposed.engine.HookEngine;
import io.github.thelok1s.orchestra.xposed.engine.HookHandler;

/**
 * All Orchestra hook logic, written once against {@link HookEngine} so it runs unchanged whether the
 * caller is the legacy {@code de.robv} entry ({@link LegacyModuleEntry}) or the modern libxposed
 * entry ({@link ModernModuleEntry}). Method-installation goes through the engine; process-side work
 * (the AAP broker, the ShadowHook DID hook, battery-metadata writes, dynamic receivers, the
 * bundled-index device gate, hooked-state gating) is plain Android code and logs via
 * {@code android.util.Log} (tag {@code OrchestraMX}) so it has no dependency on either framework API.
 *
 * <p>The legacy {@link OrchestraHooks} is intentionally left in place and active during the additive
 * rollout; both it and this class carry the same behavior until the modern path is validated on a
 * framework that lacks the legacy bridge.</p>
 *
 * <p>{@link #HOOKED_PKGS} guards against double-application if both entry points fire in one process
 * (e.g. Vector, where the legacy bridge fires the legacy entry and a modern entry could also load) —
 * both entries share the APK ClassLoader in the target process, so the static is shared.</p>
 */
public final class OrchestraHookBodies {

    private static final String TAG = "OrchestraMX";

    // Set by whichever entry knows it: legacy via initZygote(sp.modulePath), modern via
    // getModuleApplicationInfo().sourceDir. Used to read the bundled assets/index.json + native libs.
    static volatile String modulePath;

    // ---- double-load guard (package+process, so SystemUI main vs a helper are distinct) ----
    private static final Set<String> HOOKED_KEYS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // ---- SystemUI class/method names ----
    private static final String PIXEL_DEVICE_INTERACTOR =
            "com.google.android.systemui.volume.panel.domain.interactor.PixelDeviceInteractor";
    private static final String ANC_GOOGLE_CRITERIA =
            "com.google.android.systemui.volume.panel.component.anc.domain.AncAvailabilityGoogleCriteria";
    private static final String TOGGLE_CLICK_LAMBDA =
            "com.android.systemui.volume.panel.component.devicesetting.ui.composable.DeviceSettingPopup$$ExternalSyntheticLambda7";

    // ---- metadata key 25 (self-contained; no app Context/DeviceStore dependency) ----
    private static final int KEY25 = 25;
    private static final String VAL_PACKAGE = "io.github.thelok1s.orchestra";
    private static final String VAL_CLASS   = "io.github.thelok1s.orchestra.ConfigProviderService";
    private static final String VAL_ACTION  = "io.github.thelok1s.orchestra.BIND_DEVICE_SETTINGS_CONFIG_PROVIDER";
    private static final Pattern FALLBACK   = Pattern.compile("(?i)soundcore|airpods");
    private static volatile List<Pattern> gatePatterns;

    private static final UUID AAP_UUID = UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a");

    private OrchestraHookBodies() {}

    /**
     * Apply Orchestra's hooks for {@code packageName}/{@code processName} using {@code engine}, once
     * per package+process per process-lifetime (the guard prevents re-application when both entry
     * points fire). Never throws to the caller.
     */
    public static void applyIfAbsent(String packageName, String processName, HookEngine engine) {
        String key = packageName + "/" + processName;
        if (!HOOKED_KEYS.add(key)) {
            engine.log("[MX] hooks already applied for " + key + " (guard)");
            return;
        }
        try {
            applyHooks(packageName, processName, engine);
        } catch (Throwable t) {
            engine.log("[MX] applyHooks(" + key + ") threw: " + t);
        }
    }

    private static void applyHooks(String pkg, String proc, HookEngine engine) {
        switch (pkg) {
            case "com.android.systemui":
                // Main process only — a helper (e.g. :screenshot) starting a second broker would
                // fight the single-owner AAP socket.
                if (!"com.android.systemui".equals(proc)) {
                    engine.log("[MX] skipping SystemUI helper process " + proc);
                    return;
                }
                engine.log("[MX] loaded into SystemUI (proc=" + proc + ", api=" + engine.apiLevel() + ")");
                hookPixelDevice(engine);
                forceAncAvailable(engine);
                hookToggleApply(engine);
                startBroker();
                break;
            case "com.android.settings":
                if (!"com.android.settings".equals(proc)) {
                    engine.log("[MX] skipping Settings helper process " + proc);
                    return;
                }
                engine.log("[MX] loaded into Settings (metadata writer, api=" + engine.apiLevel() + ")");
                hookSettingsMetadataWriter(engine);
                break;
            case "io.github.thelok1s.orchestra":
                hookSelfSentinel(engine);
                break;
            case "com.google.android.bluetooth":
                if (!"com.google.android.bluetooth".equals(proc)) return;
                engine.log("[MX] loaded into Bluetooth stack (DID hook)");
                startDidHook();
                break;
            default:
                break;
        }
    }

    // ================= SystemUI: volume-panel ANC =================

    private static void hookPixelDevice(HookEngine engine) {
        try {
            Class<?> c = engine.findClass(PIXEL_DEVICE_INTERACTOR);
            engine.hookMatching(c,
                    m -> m.getName().toLowerCase().contains("ispixeldevice")
                            && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class),
                    new HookHandler() {
                        @Override public void after(HookCtx ctx) { ctx.setResult(Boolean.TRUE); }
                    });
            engine.log("[MX] pixelDevice forced true");
        } catch (Throwable t) { engine.log("[MX] pixelDevice hook FAILED: " + t); }
    }

    private static void forceAncAvailable(HookEngine engine) {
        try {
            Class<?> flowKt = engine.findClass("kotlinx.coroutines.flow.FlowKt");
            final Method flowOf1 = singleFlowOf(flowKt);
            if (flowOf1 == null) engine.log("[MX] forceAncAvailable: flowOf(Object) NOT found");
            Class<?> crit = engine.findClass(ANC_GOOGLE_CRITERIA);
            engine.hookMatching(crit,
                    m -> m.getName().equals("isAvailable") && m.getParameterCount() == 0,
                    new HookHandler() {
                        @Override public void after(HookCtx ctx) throws Throwable {
                            if (flowOf1 != null) ctx.setResult(flowOf1.invoke(null, Boolean.TRUE));
                        }
                    });
            engine.log("[MX] AncGoogleCriteria.isAvailable forced true");
        } catch (Throwable t) { engine.log("[MX] forceAncAvailable FAILED: " + t); }
    }

    private static Method singleFlowOf(Class<?> flowKt) {
        for (Method m : flowKt.getDeclaredMethods())
            if (m.getName().equals("flowOf") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == Object.class) { m.setAccessible(true); return m; }
        return null;
    }

    private static void hookToggleApply(HookEngine engine) {
        try {
            Class<?> c = engine.findClass(TOGGLE_CLICK_LAMBDA);
            engine.hookMatching(c,
                    m -> m.getName().equals("invoke"),
                    new HookHandler() {
                        @Override public void before(HookCtx ctx) {
                            try {
                                Object model = ctx.getObjectField("f$0");
                                int index = ctx.getIntField("f$1");
                                Object cached = getField(model, "cachedDevice");
                                BluetoothDevice dev = (BluetoothDevice) getField(cached, "mDevice");
                                String mac = dev.getAddress();
                                Context appCtx = AndroidAppHelper.currentApplication();
                                Intent i = new Intent("io.github.thelok1s.orchestra.APPLY_INDEX")
                                        .setClassName("io.github.thelok1s.orchestra",
                                                "io.github.thelok1s.orchestra.VolumeApplyReceiver")
                                        .putExtra("mac", mac).putExtra("index", index);
                                appCtx.sendBroadcast(i);
                                Log.i(TAG, "[MX] toggle tap -> APPLY_INDEX " + mac + " idx=" + index);
                            } catch (Throwable t) {
                                Log.e(TAG, "[MX] toggle apply failed: " + t);
                            }
                        }
                    });
            engine.log("[MX] hooked popup toggle click (Lambda7)");
        } catch (Throwable t) { engine.log("[MX] hookToggleApply FAILED (class not found is expected if popup lazy): " + t); }
    }

    // ================= SystemUI: AAP broker =================

    private static volatile boolean brokerStarted = false;

    private static void startBroker() {
        if (brokerStarted) return;
        new Thread(() -> {
            try {
                Application app = awaitApplication(50, 200);
                if (app == null) { Log.w(TAG, "[MX] broker: no SystemUI context, giving up"); return; }
                if (brokerStarted) return;
                brokerStarted = true;
                ensureBluetoothReceiver(app);
                io.github.thelok1s.orchestra.aap.AapBroker.start(app);
                Log.i(TAG, "[MX] AAP broker started in SystemUI");
            } catch (Throwable t) { Log.e(TAG, "[MX] broker start failed: " + t); }
        }, "mx-broker-start").start();
    }

    // ================= Bluetooth stack: DID hook =================

    private static volatile boolean didHookStarted = false;

    private static void startDidHook() {
        if (didHookStarted) return;
        didHookStarted = true;
        final String mp = modulePath;
        new Thread(() -> {
            try {
                if (mp == null) { Log.w(TAG, "[MX] didhook: no modulePath"); return; }
                String libDir = new File(mp).getParent() + "/lib/arm64";
                System.load(libDir + "/libshadowhook.so");
                System.load(libDir + "/libl2c_fcr_hook.so");
                Log.i(TAG, "[MX] didhook: libs loaded from " + libDir);

                long t0 = System.currentTimeMillis();
                io.github.thelok1s.orchestra.NativeBridge.precomputeDidOffset();
                Log.i(TAG, "[MX] didhook: precompute done in " + (System.currentTimeMillis() - t0) + "ms");

                if (!readActAsApple()) {
                    io.github.thelok1s.orchestra.NativeBridge.setDidGate(false);
                    Log.i(TAG, "[MX] didhook: act_as_apple OFF (real DID)");
                    return;
                }
                boolean armed = false;
                for (int i = 0; i < 4000 && !armed; i++) {
                    armed = io.github.thelok1s.orchestra.NativeBridge.armDidHook(true);
                    if (!armed) { try { Thread.sleep(1); } catch (InterruptedException ie) { return; } }
                }
                Log.i(TAG, "[MX] didhook: armed=" + armed + " at +" + (System.currentTimeMillis() - t0) + "ms");
            } catch (Throwable t) { Log.e(TAG, "[MX] didhook start failed: " + t); }
        }, "orchestra-didhook").start();
    }

    private static boolean readActAsApple() {
        try {
            Application app = awaitApplication(20, 100);
            if (app == null) { Log.w(TAG, "[MX] didhook: no Context for act_as_apple read"); return false; }
            Uri uri = Uri.parse("content://io.github.thelok1s.orchestra.state/flag/act_as_apple");
            try (Cursor cur = app.getContentResolver().query(uri, null, null, null, null)) {
                if (cur != null && cur.moveToFirst())
                    return cur.getInt(cur.getColumnIndexOrThrow("enabled")) == 1;
            }
        } catch (Throwable t) { Log.e(TAG, "[MX] didhook: readActAsApple failed: " + t); }
        return false;
    }

    // ================= Settings: privileged metadata writer =================

    private static void hookSettingsMetadataWriter(HookEngine engine) {
        try {
            Class<?> activity = engine.findClass("android.app.Activity");
            engine.hookExact(activity, "onResume", new HookHandler() {
                @Override public void after(HookCtx ctx) { assertTagsForBondedDevices(); }
            });
            engine.log("[MX] Settings metadata writer armed (Activity.onResume)");
        } catch (Throwable t) { engine.log("[MX] settings hook failed: " + t); }
    }

    private static void assertTagsForBondedDevices() {
        ensureBatteryReceiver();
        try {
            Application app = AndroidAppHelper.currentApplication();
            if (app == null) return;
            ensureBluetoothReceiver(app);
            BluetoothAdapter adapter = adapterFrom(app);
            if (adapter == null || !adapter.isEnabled()) return;
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) return;
            for (BluetoothDevice d : bonded) {
                if (!nameSupported(safeName(d))) continue;
                // Only advertise key 25 for devices we actually serve (hooked); otherwise SystemUI
                // caches an empty device-settings layout and the ANC tile stays gone until restart.
                if (isDeviceHooked(d.getAddress())) {
                    assertConfigTags(d);
                    if (isAapDevice(d)) writeBattery(d);
                } else {
                    clearConfigTags(d);
                }
            }
        } catch (Throwable t) { Log.e(TAG, "[MX] assertTags failed: " + t); }
    }

    private static void assertConfigTags(BluetoothDevice device) {
        try {
            String existing = readKey25(device);
            if (existing == null) existing = "";
            String updated = upsert(existing, "DEVICE_SETTINGS_CONFIG_PACKAGE_NAME", VAL_PACKAGE);
            updated = upsert(updated, "DEVICE_SETTINGS_CONFIG_CLASS",  VAL_CLASS);
            updated = upsert(updated, "DEVICE_SETTINGS_CONFIG_ACTION", VAL_ACTION);
            updated = updated.replaceAll(
                    "<HEARABLE_CONTROL_SLICE_WITH_WIDTH>.*?</HEARABLE_CONTROL_SLICE_WITH_WIDTH>", "");
            if (updated.equals(existing)) return;
            Object res = setMetadata(device, KEY25, updated.getBytes(StandardCharsets.UTF_8));
            boolean ok = !(res instanceof Boolean) || (Boolean) res;
            Log.i(TAG, "[MX] key25 write " + (ok ? "ok" : "FAILED") + " (hooked) for " + device.getAddress());
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            Log.e(TAG, "[MX] setMetadata failed: " + c);
        }
    }

    private static void clearConfigTags(BluetoothDevice device) {
        try {
            String existing = readKey25(device);
            if (existing == null || existing.isEmpty()) return;
            String updated = existing
                    .replaceAll("<DEVICE_SETTINGS_CONFIG_PACKAGE_NAME>.*?</DEVICE_SETTINGS_CONFIG_PACKAGE_NAME>", "")
                    .replaceAll("<DEVICE_SETTINGS_CONFIG_CLASS>.*?</DEVICE_SETTINGS_CONFIG_CLASS>", "")
                    .replaceAll("<DEVICE_SETTINGS_CONFIG_ACTION>.*?</DEVICE_SETTINGS_CONFIG_ACTION>", "");
            if (updated.equals(existing)) return;
            setMetadata(device, KEY25, updated.getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "[MX] key25 cleared (un-hooked) for " + device.getAddress());
        } catch (Throwable t) { Log.e(TAG, "[MX] clearConfigTags failed: " + t); }
    }

    // ---- battery metadata (AAP header) ----

    private static void writeBattery(BluetoothDevice device) {
        try {
            Application app = AndroidAppHelper.currentApplication();
            if (app == null) return;
            Uri uri = Uri.parse("content://io.github.thelok1s.orchestra.state/battery/" + device.getAddress());
            Integer left = null, right = null, caseLvl = null;
            boolean lc = false, rc = false, cc = false;
            try (Cursor cur = app.getContentResolver().query(uri, null, null, null, null)) {
                if (cur != null && cur.moveToFirst()) {
                    left = nz(cur.getInt(cur.getColumnIndexOrThrow("left")));
                    right = nz(cur.getInt(cur.getColumnIndexOrThrow("right")));
                    caseLvl = nz(cur.getInt(cur.getColumnIndexOrThrow("case_level")));
                    lc = cur.getInt(cur.getColumnIndexOrThrow("left_charging")) == 1;
                    rc = cur.getInt(cur.getColumnIndexOrThrow("right_charging")) == 1;
                    cc = cur.getInt(cur.getColumnIndexOrThrow("case_charging")) == 1;
                }
            }
            setMetadata(device, 6, "true".getBytes(StandardCharsets.UTF_8));
            setMetadata(device, 17, "Untethered Headset".getBytes(StandardCharsets.UTF_8));
            setMetadata(device, 10, battBytes(left));
            setMetadata(device, 11, battBytes(right));
            setMetadata(device, 12, battBytes(caseLvl));
            setMetadata(device, 13, (lc ? "true" : "false").getBytes(StandardCharsets.UTF_8));
            setMetadata(device, 14, (rc ? "true" : "false").getBytes(StandardCharsets.UTF_8));
            setMetadata(device, 15, (cc ? "true" : "false").getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "[MX] battery write " + device.getAddress() + " L=" + left + " R=" + right + " C=" + caseLvl);
        } catch (Throwable t) { Log.e(TAG, "[MX] battery write failed: " + t); }
    }

    private static Integer nz(int v) { return v >= 0 && v <= 100 ? v : null; }
    private static byte[] battBytes(Integer v) {
        return (v != null ? v.toString() : "").getBytes(StandardCharsets.UTF_8);
    }

    // ---- dynamic receivers (Settings/SystemUI process) ----

    private static volatile boolean batteryReceiverRegistered = false;

    private static void ensureBatteryReceiver() {
        if (batteryReceiverRegistered) return;
        try {
            Application app = AndroidAppHelper.currentApplication();
            if (app == null) return;
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    try {
                        String mac = i.getStringExtra("mac");
                        if (mac == null) return;
                        BluetoothAdapter adapter = adapterFrom(c);
                        if (adapter == null) return;
                        BluetoothDevice d = adapter.getRemoteDevice(mac);
                        if (isAapDevice(d)) writeBattery(d);
                    } catch (Throwable t) { Log.e(TAG, "[MX] battery receiver: " + t); }
                }
            };
            registerGuarded(app, r, "io.github.thelok1s.orchestra.BATTERY_CHANGED");
            batteryReceiverRegistered = true;
            Log.i(TAG, "[MX] battery-changed receiver registered");
        } catch (Throwable t) { Log.e(TAG, "[MX] battery receiver register failed: " + t); }
    }

    private static volatile boolean btReceiverRegistered = false;

    private static void ensureBluetoothReceiver(Application app) {
        if (btReceiverRegistered) return;
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    try {
                        BluetoothAdapter adapter = adapterFrom(c);
                        if (adapter != null) {
                            @SuppressWarnings("deprecation")
                            boolean ok = adapter.enable();
                            Log.i(TAG, "[MX] bt receiver trigger enable, ok=" + ok);
                        }
                    } catch (Throwable t) { Log.e(TAG, "[MX] bt receiver: " + t); }
                }
            };
            registerGuarded(app, r, "io.github.thelok1s.orchestra.ENABLE_BLUETOOTH");
            btReceiverRegistered = true;
            Log.i(TAG, "[MX] bluetooth-enable receiver registered");
        } catch (Throwable t) { Log.e(TAG, "[MX] bluetooth receiver register failed: " + t); }
    }

    /** Register {@code r} for {@code action}, gated by Orchestra's signature permission (only the
     *  same-signer app can deliver). RECEIVER_EXPORTED so the app process (different uid) can reach it. */
    private static void registerGuarded(Application app, BroadcastReceiver r, String action) {
        IntentFilter f = new IntentFilter(action);
        String perm = "io.github.thelok1s.orchestra.permission.BATTERY_BROADCAST";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(r, f, perm, null, Context.RECEIVER_EXPORTED);
        } else {
            app.registerReceiver(r, f, perm, null);
        }
    }

    // ================= self sentinel =================

    private static void hookSelfSentinel(HookEngine engine) {
        try {
            Class<?> self = engine.findClass("io.github.thelok1s.orchestra.XposedSelf");
            engine.hookExact(self, "active", new HookHandler() {
                @Override public void after(HookCtx ctx) { ctx.setResult(Boolean.TRUE); }
            });
            final int api = engine.apiLevel();
            engine.hookExact(self, "apiLevel", new HookHandler() {
                @Override public void after(HookCtx ctx) { ctx.setResult(api); }
            });
            engine.log("[MX] self-active sentinel set (api=" + api + ")");
        } catch (Throwable t) { engine.log("[MX] self sentinel failed: " + t); }
    }

    // ================= helpers =================

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
            ZipEntry e = zip.getEntry("assets/index.json");
            if (e == null) return out;
            String json;
            try (InputStream in = zip.getInputStream(e)) {
                byte[] buf = new byte[in.available() > 0 ? in.available() : 8192];
                int n;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
                json = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
            JSONArray mans = new JSONObject(json).optJSONArray("manufacturers");
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
            Log.i(TAG, "[MX] device gate: " + out.size() + " pattern(s) from bundled index");
        } catch (Throwable t) { Log.w(TAG, "[MX] device gate load failed, using fallback: " + t); }
        return out;
    }

    private static boolean isDeviceHooked(String mac) {
        if (mac == null) return false;
        try {
            Application app = AndroidAppHelper.currentApplication();
            if (app == null) return true; // fail-open
            Uri uri = Uri.parse("content://io.github.thelok1s.orchestra.state/enabled/" + mac);
            try (Cursor c = app.getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst())
                    return c.getInt(c.getColumnIndexOrThrow("enabled")) == 1;
                return false;
            }
        } catch (Throwable t) {
            Log.w(TAG, "[MX] isDeviceHooked query failed, assuming hooked: " + t);
            return true; // fail-open
        }
    }

    private static boolean isAapDevice(BluetoothDevice d) {
        try {
            ParcelUuid[] uuids = d.getUuids();
            if (uuids != null) for (ParcelUuid p : uuids) if (AAP_UUID.equals(p.getUuid())) return true;
        } catch (Throwable ignore) {}
        return false;
    }

    private static String safeName(BluetoothDevice d) {
        try { return d.getName(); } catch (Throwable t) { return null; }
    }

    private static String readKey25(BluetoothDevice device) {
        try {
            Method get = BluetoothDevice.class.getMethod("getMetadata", int.class);
            Object res = get.invoke(device, KEY25);
            if (res instanceof byte[]) return new String((byte[]) res, StandardCharsets.UTF_8);
        } catch (Throwable ignore) {}
        return null;
    }

    private static Object setMetadata(BluetoothDevice device, int key, byte[] value) throws Exception {
        Method set = BluetoothDevice.class.getMethod("setMetadata", int.class, byte[].class);
        return set.invoke(device, key, value);
    }

    private static String upsert(String src, String tag, String value) {
        String stripped = src.replaceAll("<" + Pattern.quote(tag) + ">.*?</" + Pattern.quote(tag) + ">", "");
        return stripped + "<" + tag + ">" + value + "</" + tag + ">";
    }

    private static Object getField(Object obj, String name) throws ReflectiveOperationException {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    private static BluetoothAdapter adapterFrom(Context c) {
        BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm != null ? bm.getAdapter() : null;
    }

    /** Poll {@link AndroidAppHelper#currentApplication()} (null very early) up to {@code tries}×{@code sleepMs}. */
    private static Application awaitApplication(int tries, int sleepMs) {
        Application app = null;
        for (int i = 0; i < tries && app == null; i++) {
            app = AndroidAppHelper.currentApplication();
            if (app == null) { try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) { return null; } }
        }
        return app;
    }
}
