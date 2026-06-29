package io.github.thelok1s.orchestra;

import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves which ROM "platform" binding applies to the running device, from a manifest's optional
 * `platforms` block. ROM identity = a `detect` block matched against the running build; the matched
 * platform's `inject` block supplies the concrete surfaces + reserved setting-ids. Absent block →
 * the built-in pixelos default. `requires` gates whether the device is even supportable here.
 */
public final class HostProfile {
    public final String id;
    public final List<String> surfaces;
    private final JSONObject reservedIds; // name -> int
    public final boolean supported;

    private HostProfile(String id, List<String> surfaces, JSONObject reservedIds, boolean supported) {
        this.id = id; this.surfaces = surfaces; this.reservedIds = reservedIds; this.supported = supported;
    }

    private static final HostProfile PIXEL_DEFAULT;
    static {
        List<String> s = new ArrayList<>();
        s.add("device_details"); s.add("volume_panel");
        JSONObject r = new JSONObject();
        try { r.put("anc", 1001); } catch (Exception ignore) {}
        PIXEL_DEFAULT = new HostProfile("pixelos", s, r, true);
    }

    public boolean surfaceSupported(String surface) { return surfaces.contains(surface); }

    public int reservedId(String name) { return reservedIds.optInt(name, -1); }

    public static HostProfile resolve(DeviceDef def) {
        if (def == null || def.platforms == null) return PIXEL_DEFAULT;
        for (java.util.Iterator<String> it = def.platforms.keys(); it.hasNext(); ) {
            String pid = it.next();
            JSONObject p = def.platforms.optJSONObject(pid);
            if (p == null) continue;
            if (!detectMatches(p.optJSONObject("detect"))) continue;
            List<String> surfaces = new ArrayList<>();
            JSONObject inj = p.optJSONObject("inject");
            if (inj != null) {
                org.json.JSONArray sa = inj.optJSONArray("surfaces");
                if (sa != null) for (int i = 0; i < sa.length(); i++) surfaces.add(sa.optString(i));
            }
            JSONObject reserved = inj != null && inj.optJSONObject("reserved_ids") != null
                    ? inj.optJSONObject("reserved_ids") : new JSONObject();
            boolean supported = requiresMet(p.optJSONArray("requires"));
            return new HostProfile(pid, surfaces.isEmpty() ? PIXEL_DEFAULT.surfaces : surfaces,
                    reserved, supported);
        }
        return PIXEL_DEFAULT;
    }

    private static boolean detectMatches(JSONObject detect) {
        if (detect == null) return true; // no detect = match anything
        String wantMfr = detect.optString("manufacturer", null);
        if (wantMfr != null && !wantMfr.equalsIgnoreCase(Build.MANUFACTURER)) return false;
        JSONObject props = detect.optJSONObject("system_prop");
        if (props != null) for (java.util.Iterator<String> it = props.keys(); it.hasNext(); ) {
            String key = it.next();
            String want = props.optString(key);
            if (!want.equalsIgnoreCase(systemProp(key))) return false;
        }
        return true;
    }

    private static boolean requiresMet(org.json.JSONArray requires) {
        if (requires == null) return true;
        for (int i = 0; i < requires.length(); i++) {
            String req = requires.optString(i);
            if (req.startsWith("pkg:")) {
                if (!pkgInstalled(req.substring(4))) return false;
            }
            // "root" / "lsposed" are environmental; the app already requires them to function,
            // so they're treated as satisfied here (the hook wouldn't run otherwise).
        }
        return true;
    }

    private static boolean pkgInstalled(String pkg) {
        try {
            App.context().getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) { return false; }
    }

    private static String systemProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class).invoke(null, key);
        } catch (Exception e) {
            Log.w(DeviceDef.TAG, "systemProp(" + key + ") failed: " + e);
            return "";
        }
    }
}
