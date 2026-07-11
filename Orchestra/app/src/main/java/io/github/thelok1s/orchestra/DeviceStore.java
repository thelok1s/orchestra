package io.github.thelok1s.orchestra;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * App-internal replacement for the KSU /data/adb storage:
 *  - the device CATALOG is bundled read-only in assets/devices/&lt;id&gt;.json
 *  - the ENABLED MAC→device_id mapping lives in SharedPreferences (user toggles it in the UI)
 * No root, no privileged paths.
 */
public final class DeviceStore {
    private static final String PREFS = "maestro";
    // UI/behavior flags live in Device-Protected Storage so they're readable BEFORE the user
    // unlocks (credential-encrypted "maestro" prefs are not). The BT-process DID hook reads
    // act_as_apple at cold boot — before the lock screen is dismissed — so that flag must be here.
    private static final String FLAGS_DE = "flags_de";
    private static final String KEY_ENABLED = "enabled"; // JSON object { "<MAC>": "<device_id>" }
    // Per-device explicit capability enable OVERRIDES. JSON { "<MAC>": { "funcId": true|false } }.
    // When a funcId is absent the DEFAULT applies: verified controls inject by default, unverified
    // ones default OFF (so we never push guessed RFCOMM bytes until the user opts in).
    private static final String KEY_CAPS = "caps_override";
    // Per-device LOCAL BEHAVIOR enables (distinct from caps_override, which governs whether a
    // control is injected/shown on the About page). A behavior is something a privileged process
    // (e.g. the SystemUI AAP broker) gates at runtime rather than an AAP/RFCOMM command — auto-pause
    // on ear removal is the first one. JSON { "<MAC>": { "auto_pause": true|false } }. Default FALSE.
    private static final String KEY_BEHAVIORS = "behaviors";
    // Last known/applied setting index per control, an optimistic fallback shown when a live device
    // read isn't available (unverified/unsupported reads). JSON { "<MAC>": { "<funcId>": <index> } }.
    private static final String KEY_LAST_STATE = "last_state";

    private DeviceStore() {}

    /** A catalog entry (the supported-device list, parsed lightly from assets). */
    public static final class Catalog {
        public final String id;
        public final String name;
        public final Pattern nameRegex; // may be null
        Catalog(String id, String name, Pattern nameRegex) {
            this.id = id; this.name = name; this.nameRegex = nameRegex;
        }
    }

    private static Context ctx() { return App.context(); }

    private static SharedPreferences prefs() {
        return ctx().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- UI display flags (Settings screen) ----
    // show_statuses: show live device statuses (battery) on the card. default on.
    // show_unverified: reveal unverified controls (debug). default off.
    // show_mac: show the device MAC on the card (debug). default off.
    /** Device-Protected Storage prefs for flags — available before the user unlocks the device. */
    private static SharedPreferences flagPrefs() {
        return ctx().createDeviceProtectedStorageContext()
                .getSharedPreferences(FLAGS_DE, Context.MODE_PRIVATE);
    }

    public static boolean flag(String key, boolean def) {
        String k = "flag_" + key;
        SharedPreferences de = flagPrefs();
        if (de.contains(k)) return de.getBoolean(k, def);
        // Lazy one-time migration from the legacy credential-encrypted "maestro" prefs. Only works
        // after unlock (the CE store is inaccessible before it); before unlock we return the default,
        // which is why act_as_apple must have been written/migrated to DE in a prior unlocked session.
        try {
            SharedPreferences ce = prefs();
            if (ce.contains(k)) {
                boolean v = ce.getBoolean(k, def);
                de.edit().putBoolean(k, v).apply();
                return v;
            }
        } catch (Exception ignore) {}
        return def;
    }

    public static void setFlag(String key, boolean value) {
        flagPrefs().edit().putBoolean("flag_" + key, value).apply();
    }

    // ---- catalog (assets) ----

    public static List<Catalog> catalog() {
        List<Catalog> out = new ArrayList<>();
        try {
            String[] files = ctx().getAssets().list("devices");
            if (files == null) return out;
            for (String fn : files) {
                if (!fn.endsWith(".json")) continue;
                String raw = readAsset("devices/" + fn);
                if (raw == null) continue;
                JSONObject root = new JSONObject(raw);
                String id = root.optString("id", fn.replace(".json", ""));
                String name = root.optString("name", id);
                Pattern p = null;
                JSONObject match = root.optJSONObject("match");
                if (match != null) {
                    String rx = match.optString("name_regex", null);
                    if (rx != null && !rx.isEmpty()) {
                        try { p = Pattern.compile(rx); } catch (Exception ignore) {}
                    }
                }
                out.add(new Catalog(id, name, p));
            }
        } catch (Exception e) {
            Log.w(DeviceDef.TAG, "catalog() failed: " + e);
        }
        return out;
    }

    /** Best-effort device_id for a BT name (used by the metadata hook / auto-detect). */
    public static String idForName(String deviceName) {
        if (deviceName == null) return null;
        for (Catalog c : catalog()) {
            if (c.nameRegex != null && c.nameRegex.matcher(deviceName).find()) return c.id;
            if (deviceName.equalsIgnoreCase(c.name)) return c.id;
        }
        return null;
    }

    /**
     * device_id for a bonded device, consulting the live index first, then the bundled catalog.
     * Returns null only when neither source matches.
     */
    public static String idForBonded(String name, java.util.List<String> uuids, String modelName) {
        IndexStore.Entry e = IndexStore.match(name, uuids, modelName);
        if (e != null) return e.id;
        return idForName(name);
    }

    static String rawDeviceJson(String deviceId) {
        return ManifestRepository.rawDeviceJson(deviceId);
    }

    private static String readAsset(String path) {
        try (InputStream is = ctx().getAssets().open(path);
             BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ---- enabled mapping (prefs) ----

    /** MAC(upper) → device_id for every enabled device. */
    public static Map<String, String> enabledMap() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            String json = prefs().getString(KEY_ENABLED, "{}");
            JSONObject o = new JSONObject(json);
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String mac = it.next();
                out.put(mac.toUpperCase(Locale.ROOT), o.getString(mac));
            }
        } catch (Exception e) {
            Log.w(DeviceDef.TAG, "enabledMap() failed: " + e);
        }
        return out;
    }

    public static String enabledId(String mac) {
        if (mac == null) return null;
        return enabledMap().get(mac.toUpperCase(Locale.ROOT));
    }

    public static void setEnabled(String mac, String deviceId, boolean enabled) {
        if (mac == null) return;
        try {
            Map<String, String> cur = enabledMap();
            String key = mac.toUpperCase(Locale.ROOT);
            if (enabled) cur.put(key, deviceId);
            else cur.remove(key);
            JSONObject o = new JSONObject();
            for (Map.Entry<String, String> e : cur.entrySet()) o.put(e.getKey(), e.getValue());
            prefs().edit().putString(KEY_ENABLED, o.toString()).apply();
        } catch (Exception e) {
            Log.w(DeviceDef.TAG, "setEnabled failed: " + e);
        }
    }

    // ---- per-capability enablement (prefs) ----

    /** Explicit user override for one capability, or null if the user hasn't touched it. */
    private static Boolean capOverride(String mac, String funcId) {
        if (mac == null || funcId == null) return null;
        try {
            JSONObject root = new JSONObject(prefs().getString(KEY_CAPS, "{}"));
            JSONObject dev = root.optJSONObject(mac.toUpperCase(Locale.ROOT));
            if (dev != null && dev.has(funcId)) return dev.getBoolean(funcId);
        } catch (Exception e) {
            Log.w(DeviceDef.TAG, "capOverride failed: " + e);
        }
        return null;
    }

    /**
     * Effective enable state. {@code defaultEnabled} is the verified-based default the caller
     * supplies (verified → true). An explicit user override always wins; sideloaded manifests
     * expose ALL controls for testing (bypass of the verified-gate default).
     */
    public static boolean isCapabilityEnabled(String mac, String funcId, boolean defaultEnabled) {
        Boolean o = capOverride(mac, funcId);
        if (o != null) return o;
        String id = enabledId(mac);
        if (id != null && ManifestRepository.isSideloaded(id)) return true; // testing: expose all
        return defaultEnabled;
    }

    public static void setCapabilityEnabled(String mac, String funcId, boolean enabled) {
        if (mac == null || funcId == null) return;
        try {
            String key = mac.toUpperCase(Locale.ROOT);
            JSONObject root = new JSONObject(prefs().getString(KEY_CAPS, "{}"));
            JSONObject dev = root.optJSONObject(key);
            if (dev == null) dev = new JSONObject();
            dev.put(funcId, enabled);
            root.put(key, dev);
            prefs().edit().putString(KEY_CAPS, root.toString()).apply();
        } catch (Exception e) {
            Log.w(DeviceDef.TAG, "setCapabilityEnabled failed: " + e);
        }
    }

    // ---- per-device behavior enablement (prefs; cross-process pull target for StateProvider) ----

    /** Whether {@code behaviorId} (e.g. "auto_pause") is enabled for {@code mac}. Default FALSE. */
    public static boolean behaviorEnabled(String mac, String behaviorId) {
        if (mac == null || behaviorId == null) return false;
        try {
            JSONObject root = new JSONObject(prefs().getString(KEY_BEHAVIORS, "{}"));
            JSONObject dev = root.optJSONObject(mac.toUpperCase(Locale.ROOT));
            if (dev != null) return dev.optBoolean(behaviorId, false);
        } catch (Exception e) {
            Log.w(DeviceDef.TAG, "behaviorEnabled failed: " + e);
        }
        return false;
    }

    public static void setBehaviorEnabled(String mac, String behaviorId, boolean enabled) {
        if (mac == null || behaviorId == null) return;
        try {
            String key = mac.toUpperCase(Locale.ROOT);
            JSONObject root = new JSONObject(prefs().getString(KEY_BEHAVIORS, "{}"));
            JSONObject dev = root.optJSONObject(key);
            if (dev == null) dev = new JSONObject();
            dev.put(behaviorId, enabled);
            root.put(key, dev);
            prefs().edit().putString(KEY_BEHAVIORS, root.toString()).apply();
        } catch (Exception e) {
            Log.w(DeviceDef.TAG, "setBehaviorEnabled failed: " + e);
        }
    }

    // ---- last known/applied control state (optimistic fallback across process restarts) ----

    /** Last known/applied setting index for {@code funcId} on {@code mac}, or {@code dflt} if none. */
    public static int lastIndex(String mac, String funcId, int dflt) {
        if (mac == null || funcId == null) return dflt;
        try {
            JSONObject root = new JSONObject(prefs().getString(KEY_LAST_STATE, "{}"));
            JSONObject dev = root.optJSONObject(mac.toUpperCase(Locale.ROOT));
            if (dev != null && dev.has(funcId)) return dev.optInt(funcId, dflt);
        } catch (Exception e) {
            Log.w(DeviceDef.TAG, "lastIndex failed: " + e);
        }
        return dflt;
    }

    /** Persist the last known/applied index for {@code funcId} on {@code mac}. */
    public static void setLastIndex(String mac, String funcId, int index) {
        if (mac == null || funcId == null) return;
        try {
            String key = mac.toUpperCase(Locale.ROOT);
            JSONObject root = new JSONObject(prefs().getString(KEY_LAST_STATE, "{}"));
            JSONObject dev = root.optJSONObject(key);
            if (dev == null) dev = new JSONObject();
            dev.put(funcId, index);
            root.put(key, dev);
            prefs().edit().putString(KEY_LAST_STATE, root.toString()).apply();
        } catch (Exception e) {
            Log.w(DeviceDef.TAG, "setLastIndex failed: " + e);
        }
    }

    // ---- UI bridge: capability listing for a hooked device ----

    /**
     * UI-facing view of one capability (Kotlin can't see the package-private {@link DeviceDef}).
     * {@code status} is derived for display: ACTIVE (injected now), AVAILABLE (implemented but the
     * user switched it off), CONFLICT (suppressed by a conflicting enabled capability), PENDING
     * (injectable per schema but not yet rendered — e.g. switches), UNAVAILABLE (slider/list/info).
     */
    public static final class Capability {
        public final String id;
        public final String title;
        public final String type;
        public final String status;   // ACTIVE | AVAILABLE | CONFLICT | PENDING | UNAVAILABLE
        public final String reason;   // why not injectable, or ""
        public final boolean toggleable; // user can flip the enable switch
        public final boolean enabled;    // current user enable state
        public final boolean verified;   // bytes confirmed on hardware
        Capability(String id, String title, String type, String status, String reason,
                   boolean toggleable, boolean enabled, boolean verified) {
            this.id = id; this.title = title; this.type = type; this.status = status;
            this.reason = reason; this.toggleable = toggleable; this.enabled = enabled;
            this.verified = verified;
        }
    }

    public static List<Capability> capabilities(String mac) {
        List<Capability> out = new ArrayList<>();
        DeviceDef def = DeviceDef.forAddress(mac);
        if (def == null) return out;
        for (DeviceDef.Func f : def.functions) {
            boolean enabled = isCapabilityEnabled(mac, f.id, f.verified);
            boolean conflicted = f.implemented && enabled && def.conflictSuppressed(mac, f);
            String status;
            if (!f.injectable) status = "UNAVAILABLE";
            else if (!f.implemented) status = "PENDING";
            else if (conflicted) status = "CONFLICT";
            else if (enabled) status = "ACTIVE";
            else status = "AVAILABLE";
            String reason = f.injectReason != null ? f.injectReason : "";
            if (conflicted) reason = "Conflicts with another enabled control.";
            // Toggle is meaningful for anything that could be injected (implemented or pending), and
            // also for app-surfaced controls that will never be injectable (e.g. sliders/lists) —
            // those still need a Devices-tab opt-in gate before DeviceControlsScreen shows them.
            boolean toggleable = (f.injectable && (f.implemented || "PENDING".equals(status)))
                    || (!f.injectable && f.surfaces.contains("app"));
            out.add(new Capability(f.id, f.title, f.type, status, reason, toggleable, enabled,
                    f.verified));
        }
        return out;
    }
}
