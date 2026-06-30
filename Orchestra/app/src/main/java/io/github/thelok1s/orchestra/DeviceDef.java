package io.github.thelok1s.orchestra;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parsed device definition (data-driven framework core, schema v3). The device CATALOG is bundled
 * in assets/devices/&lt;id&gt;.json; the enabled MAC→device_id mapping and per-capability enable
 * flags live in app prefs. Both are accessed through {@link DeviceStore} (no root, no /data/adb).
 *
 * <p>{@link #functions} holds the FULL reverse-engineered capability catalog (every {@code type}:
 * multitoggle / toggle / list / slider / info). Each {@link Func} carries whether it is
 * {@link Func#injectable} on the Pixel About page and whether it is {@link Func#implemented}
 * (actually renderable by the current provider). Provider services only emit
 * {@link #injectedFuncs} (implemented + user-enabled + conflict-resolved); the UI lists everything
 * and greys out the rest with {@link Func#injectReason}.
 */
final class DeviceDef {
    static final String TAG = "Orchestra";

    static final int SUPPORTED_SCHEMA_MIN = 3;
    static final int SUPPORTED_SCHEMA_MAX = 4;

    final String id;
    final String name;
    // transport (back-compat: derived from default_channel for RfcommEngine)
    final String transportUuid;
    final boolean secure;
    // v3 schema fields
    final int schemaVersion;
    final int revision;
    final Map<String, Channel> channels;   // channelId -> transport+protocol
    final String defaultChannel;
    final org.json.JSONObject platforms;    // raw platforms block (HostProfile reads it), may be null
    // The full capability catalog in declared order. soundMode = the ANC function (settingId 1001)
    // used by the volume-panel path.
    final List<Func> functions;
    final Func soundMode;

    /** A named transport+protocol bundle. Today rfcomm + aacp are driven; others parse but degrade. */
    static final class Channel {
        final String id;
        final String transport;             // rfcomm | ble_gatt | aacp
        final String uuid;                  // rfcomm
        final boolean secure;
        Channel(String id, String transport, String uuid, boolean secure) {
            this.id = id; this.transport = transport; this.uuid = uuid; this.secure = secure;
        }
    }

    private DeviceDef(String id, String name, int schemaVersion, int revision,
                      Map<String, Channel> channels, String defaultChannel,
                      org.json.JSONObject platforms, List<Func> functions) {
        this.id = id;
        this.name = name;
        this.schemaVersion = schemaVersion;
        this.revision = revision;
        this.channels = channels;
        this.defaultChannel = defaultChannel;
        this.platforms = platforms;
        Channel def = channels.get(defaultChannel);
        this.transportUuid = def != null ? def.uuid : null;
        this.secure = def != null && def.secure;
        this.functions = functions;
        Func anc = null, first = null;
        for (Func f : functions) {
            if (first == null && f.implemented) first = f;
            if (f.settingId == 1001) anc = f;
        }
        this.soundMode = anc != null ? anc : first;
    }

    /** Look up a function by its device-setting id (for routing updates). */
    Func funcBySettingId(int settingId) {
        for (Func f : functions) if (f.settingId == settingId) return f;
        return null;
    }

    /** Look up a function by its slug id. */
    Func funcById(String fid) {
        for (Func f : functions) if (f.id.equals(fid)) return f;
        return null;
    }

    /**
     * The functions to actually inject for {@code address}: implemented (renderable by the current
     * provider), user-enabled, and not suppressed by an active conflict. This is what both provider
     * services iterate, so adding catalog-only capabilities can never break the live page.
     */
    List<Func> injectedFuncs(String address) {
        List<Func> out = new ArrayList<>();
        HostProfile host = HostProfile.resolve(this);
        for (Func f : functions) {
            if (!f.implemented) continue;
            if (!DeviceStore.isCapabilityEnabled(address, f.id, f.verified)) continue;
            if (conflictSuppressed(address, f)) continue;
            if (!f.surfaces.isEmpty() && !surfaceAllowed(host, f)) continue;
            out.add(f);
        }
        return out;
    }

    /** A function is allowed if the active platform supports at least one of its declared surfaces. */
    private boolean surfaceAllowed(HostProfile host, Func f) {
        for (String s : f.surfaces) if (host.surfaceSupported(s)) return true;
        return false;
    }

    /** True if an earlier-declared, enabled capability conflicts with {@code f} (so f yields). */
    boolean conflictSuppressed(String address, Func f) {
        for (Func other : functions) {
            if (other == f) break; // only earlier-declared capabilities win
            if (!DeviceStore.isCapabilityEnabled(address, other.id, other.verified)) continue;
            if (f.conflictsWith.contains(other.id) || other.conflictsWith.contains(f.id)) return true;
        }
        return false;
    }

    /** A single capability + its UI mapping + injectability metadata. */
    static final class Func {
        final String id;
        final String type;                                  // multitoggle|toggle|list|slider|info
        final String title;                                 // already localized
        final String capability;                            // RE provenance
        String iconName;                                    // logical icon for switch/row controls
        String summary;                                     // localized subtitle (switch/row), or null
        final List<Opt> options = new ArrayList<>();        // ordered (multitoggle/list)
        // set
        final String setCommand;                            // hex, e.g. "0681" (may be null for info)
        final String payloadTemplate;                       // e.g. "{mode}5000000005"
        final Map<String, String> optionValues = new LinkedHashMap<>(); // optId -> hex (multitoggle/list)
        final Map<String, String> stateValues = new LinkedHashMap<>();  // on/off -> hex (toggle)
        // read
        final String readCommand;                           // hex, e.g. "0101"
        final int stateByteIndex;                           // -1 if unknown
        final Map<String, String> valueMap = new LinkedHashMap<>();     // hex byte -> optId / on/off
        // Optional multi-byte readback match (for composite controls like 4-mode ANC where one
        // option needs >1 byte to identify, e.g. adaptive = B0=00 AND B3=01). First match wins;
        // each entry is (optionId, {byteIndexRelativeToResponseStart -> expectedHexByte}).
        final List<ReadMatch> readMatch = new ArrayList<>();
        // ui
        final int settingId;
        final List<String> surfaces = new ArrayList<>();
        // relations + injectability (assigned during parse)
        final List<String> conflictsWith = new ArrayList<>();
        final Map<String, String> requires = new LinkedHashMap<>();
        String channelId;       // which channel this function uses (defaults to defaultChannel)
        String transport;       // resolved from the channel (rfcomm | ble_gatt | aacp)
        boolean injectable;     // could render natively on the About page (per schema rules)
        boolean implemented;    // the current provider can actually render it (today: multitoggle)
        String injectReason;    // shown in UI when not injectable
        boolean verified;       // set/read bytes confirmed on hardware

        Func(String id, String type, String title, String capability, String setCommand,
             String payloadTemplate, String readCommand, int stateByteIndex, int settingId) {
            this.id = id;
            this.type = type;
            this.title = title;
            this.capability = capability;
            this.setCommand = setCommand;
            this.payloadTemplate = payloadTemplate;
            this.readCommand = readCommand;
            this.stateByteIndex = stateByteIndex;
            this.settingId = settingId;
        }

        boolean isMultitoggle() { return "multitoggle".equals(type); }
        boolean isToggle() { return "toggle".equals(type); }

        int indexOfOption(String optId) {
            for (int i = 0; i < options.size(); i++) {
                if (options.get(i).id.equals(optId)) return i;
            }
            return -1;
        }
    }

    static final class Opt {
        final String id;
        final String label;
        final String icon;
        Opt(String id, String label, String icon) {
            this.id = id; this.label = label; this.icon = icon;
        }
    }

    /** One readback-match rule: this option iff every (byteIndex -> hex) holds in the response. */
    static final class ReadMatch {
        final String option;
        final Map<Integer, String> bytes = new LinkedHashMap<>();
        ReadMatch(String option) { this.option = option; }
    }

    // ---- loading ----

    /** Returns the def for an enabled device address, or null if not enabled / unknown. */
    static DeviceDef forAddress(String address) {
        if (address == null) return null;
        return loadById(DeviceStore.enabledId(address));
    }

    /** Lists MAC→def for all enabled devices (used by ConnectReceiver to re-assert metadata). */
    static Map<String, DeviceDef> enabled() {
        Map<String, DeviceDef> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : DeviceStore.enabledMap().entrySet()) {
            DeviceDef def = loadById(e.getValue());
            if (def != null) out.put(e.getKey(), def);
        }
        return out;
    }

    static DeviceDef loadById(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) return null;
        try {
            String raw = DeviceStore.rawDeviceJson(deviceId);
            if (raw == null) return null;
            return parse(new JSONObject(raw));
        } catch (Exception e) {
            Log.w(TAG, "loadById(" + deviceId + ") failed: " + e);
            return null;
        }
    }

    static DeviceDef parse(JSONObject root) throws Exception {
        int schemaVersion = root.optInt("schema_version", root.optInt("schema", 0));
        if (schemaVersion < SUPPORTED_SCHEMA_MIN || schemaVersion > SUPPORTED_SCHEMA_MAX) {
            Log.w(TAG, "ignoring manifest " + root.optString("id", "?")
                    + " schema_version=" + schemaVersion + " (supported "
                    + SUPPORTED_SCHEMA_MIN + ".." + SUPPORTED_SCHEMA_MAX + ")");
            return null;
        }
        String id = root.optString("id", "unknown");
        String name = root.optString("name", id);
        int revision = root.optInt("revision", 1);

        Map<String, Channel> channels = new LinkedHashMap<>();
        JSONObject chs = root.getJSONObject("channels");
        for (java.util.Iterator<String> it = chs.keys(); it.hasNext(); ) {
            String cid = it.next();
            JSONObject c = chs.getJSONObject(cid);
            channels.put(cid, new Channel(cid, c.getString("transport"),
                    c.optString("uuid", null), c.optBoolean("secure", false)));
        }
        String defaultChannel = root.optString("default_channel",
                channels.isEmpty() ? null : channels.keySet().iterator().next());
        JSONObject platforms = root.optJSONObject("platforms");

        List<Func> functions = new ArrayList<>();
        JSONArray funcs = root.optJSONArray("functions");
        if (funcs != null) {
            for (int i = 0; i < funcs.length(); i++) {
                try {
                    Func func = parseFunc(funcs.getJSONObject(i), channels, defaultChannel);
                    if (func != null) functions.add(func);
                } catch (Exception e) {
                    Log.w(TAG, "skipping malformed function #" + i + ": " + e);
                }
            }
        }
        return new DeviceDef(id, name, schemaVersion, revision, channels, defaultChannel,
                platforms, functions);
    }

    private static Func parseFunc(JSONObject fn, Map<String, Channel> channels, String defaultChannel) throws Exception {
        String type = fn.optString("type", "multitoggle");
        JSONObject set = fn.optJSONObject("set");
        JSONObject read = fn.optJSONObject("read");
        JSONObject ui = fn.optJSONObject("ui");

        String setCmd = set != null ? set.optString("command", null) : null;
        String payload = set != null ? set.optString("payload_template", null) : null;
        String readCmd = read != null ? read.optString("command", null) : null;
        int stateByteIndex = read != null ? read.optInt("state_byte_index", -1) : -1;
        int settingId = ui != null ? ui.optInt("setting_id", 0) : 0;

        Func func = new Func(
                fn.optString("id", "unknown"),
                type,
                localized(fn, "title", fn.optString("id", "")),
                fn.optString("capability", ""),
                setCmd, payload, readCmd, stateByteIndex, settingId);
        func.verified = fn.optBoolean("_verified", false);
        func.iconName = fn.optString("icon", null);
        func.channelId = fn.optString("channel", defaultChannel);
        Channel ch = channels.get(func.channelId);
        func.transport = ch != null ? ch.transport : null;
        if (fn.has("summary") || fn.has("summary_i18n")) func.summary = localized(fn, "summary", "");

        // options (multitoggle/list)
        JSONArray opts = fn.optJSONArray("options");
        if (opts != null) {
            for (int j = 0; j < opts.length(); j++) {
                JSONObject o = opts.getJSONObject(j);
                func.options.add(new Opt(o.getString("id"),
                        localized(o, "label", o.getString("id")),
                        o.optString("icon", "")));
            }
        }
        if (set != null) {
            putAll(func.optionValues, set.optJSONObject("option_values"), false);
            putAll(func.stateValues, set.optJSONObject("state_values"), false);
        }
        if (read != null) putAll(func.valueMap, read.optJSONObject("value_map"), true);
        if (read != null) {
            JSONArray match = read.optJSONArray("match");
            if (match != null) for (int j = 0; j < match.length(); j++) {
                JSONObject rule = match.getJSONObject(j);
                ReadMatch rm = new ReadMatch(rule.getString("option"));
                JSONObject bytes = rule.optJSONObject("bytes");
                if (bytes != null) for (java.util.Iterator<String> it = bytes.keys(); it.hasNext(); ) {
                    String k = it.next();
                    rm.bytes.put(Integer.parseInt(k), bytes.getString(k).toLowerCase(Locale.ROOT));
                }
                func.readMatch.add(rm);
            }
        }

        // ui surfaces
        if (ui != null) {
            JSONArray surf = ui.optJSONArray("surfaces");
            if (surf != null) for (int j = 0; j < surf.length(); j++) func.surfaces.add(surf.getString(j));
        }
        // relations
        JSONArray cw = fn.optJSONArray("conflicts_with");
        if (cw != null) for (int j = 0; j < cw.length(); j++) func.conflictsWith.add(cw.getString(j));
        putAll(func.requires, fn.optJSONObject("requires"), false);

        computeInjectability(fn, func);
        return func;
    }

    /** Apply schema injectability rules; sets injectable/implemented/injectReason. */
    private static void computeInjectability(JSONObject fn, Func func) {
        int optCount = func.options.size();
        boolean autoInjectable;
        String autoReason = "";
        switch (func.type) {
            case "multitoggle":
                autoInjectable = optCount <= 4;
                if (!autoInjectable) autoReason = "Too many options for a segmented control (max 4).";
                break;
            case "toggle":
                autoInjectable = true;
                break;
            case "list":
                autoInjectable = false;
                autoReason = "No native list control on the About page — in-app only.";
                break;
            case "slider":
                autoInjectable = false;
                autoReason = "Slider unsupported on the About page — in-app only.";
                break;
            case "info":
            default:
                autoInjectable = false;
                autoReason = "Read-only.";
                break;
        }
        // `inject` may be a JSON boolean or the string "auto".
        Object inj = fn.opt("inject");
        String injStr = inj == null ? "auto" : inj.toString();
        String overrideReason = fn.optString("inject_reason", "");
        if ("true".equals(injStr)) {
            func.injectable = true;
        } else if ("false".equals(injStr)) {
            func.injectable = false;
            func.injectReason = !overrideReason.isEmpty() ? overrideReason
                    : (autoReason.isEmpty() ? "Disabled by definition." : autoReason);
        } else { // auto
            func.injectable = autoInjectable;
            if (!autoInjectable) func.injectReason = !overrideReason.isEmpty() ? overrideReason : autoReason;
        }
        // The provider renders MultiTogglePreference (segmented) and ActionSwitchPreference (switch).
        // list/slider/info are injectable=false; anything else injectable stays catalog-only.
        func.implemented = func.injectable && (func.isMultitoggle() || func.isToggle());
        if (func.injectable && !func.implemented && func.injectReason == null) {
            func.injectReason = "Not yet renderable on the About page.";
        }
        // Graceful degrade: the running app drives rfcomm + aacp today. A function on an
        // unknown/unsupported transport is kept in the catalog but never injected.
        boolean drivenTransport = "rfcomm".equals(func.transport) || "aacp".equals(func.transport);
        if (func.injectable && !drivenTransport) {
            func.injectable = false;
            func.implemented = false;
            func.injectReason = func.transport == null
                    ? "Channel not found in manifest."
                    : "Transport '" + func.transport + "' not supported by this app version.";
        }
    }

    private static void putAll(Map<String, String> dst, JSONObject src, boolean lowerKey) {
        if (src == null) return;
        for (java.util.Iterator<String> it = src.keys(); it.hasNext(); ) {
            String k = it.next();
            dst.put(lowerKey ? k.toLowerCase(Locale.ROOT) : k, src.optString(k));
        }
    }

    /**
     * Localized string for a JSON field: returns "&lt;field&gt;_i18n.&lt;lang&gt;" if present for the
     * current system language, else the plain "&lt;field&gt;" value. E.g. title_i18n:{"ru":"…"}.
     */
    private static String localized(JSONObject o, String field, String fallback) {
        String base = o.optString(field, fallback);
        JSONObject i18n = o.optJSONObject(field + "_i18n");
        if (i18n != null) {
            String lang = Locale.getDefault().getLanguage();
            String v = i18n.optString(lang, "");
            if (!v.isEmpty()) return v;
        }
        return base;
    }
}
