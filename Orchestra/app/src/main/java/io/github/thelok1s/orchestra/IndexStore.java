package io.github.thelok1s.orchestra;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Pattern;

/** Parsed manifest index: device matching (for bonded-device eligibility) + revision lookup. */
public final class IndexStore {
    private IndexStore() {}

    public static final class Entry {
        public final String id, name, url, sha256, slug;
        public final int revision, schemaVersion;
        public final JSONObject match;
        Entry(String id, String name, String url, String sha256, String slug,
              int revision, int schemaVersion, JSONObject match) {
            this.id = id; this.name = name; this.url = url; this.sha256 = sha256; this.slug = slug;
            this.revision = revision; this.schemaVersion = schemaVersion; this.match = match;
        }
    }

    static File indexFile() { return new File(App.context().getFilesDir(), "index.json"); }

    public static JSONObject cached() {
        try {
            File f = indexFile();
            if (f.exists()) return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) { Log.w(DeviceDef.TAG, "index read failed: " + e); }
        // bundled seed
        try (java.io.InputStream is = App.context().getAssets().open("index.json")) {
            byte[] b = new byte[is.available()]; int n = is.read(b);
            return new JSONObject(new String(b, 0, Math.max(n, 0), StandardCharsets.UTF_8));
        } catch (Exception e) { return null; }
    }

    private static void forEachDevice(DeviceConsumer c) {
        JSONObject idx = cached();
        if (idx == null) return;
        JSONArray mfrs = idx.optJSONArray("manufacturers");
        if (mfrs == null) return;
        for (int i = 0; i < mfrs.length(); i++) {
            JSONObject m = mfrs.optJSONObject(i);
            String slug = m != null ? m.optString("slug", "") : "";
            JSONArray devs = m != null ? m.optJSONArray("devices") : null;
            if (devs == null) continue;
            for (int j = 0; j < devs.length(); j++) {
                JSONObject d = devs.optJSONObject(j);
                if (d != null) c.accept(slug, d);
            }
        }
    }
    private interface DeviceConsumer { void accept(String slug, JSONObject device); }

    private static Entry toEntry(String slug, JSONObject d) {
        return new Entry(d.optString("id"), d.optString("name"), d.optString("url"),
                d.optString("sha256"), slug, d.optInt("revision", 1),
                d.optInt("schema_version", 0), d.optJSONObject("match"));
    }

    public static Entry entryById(String id) {
        Entry[] found = new Entry[1];
        forEachDevice((slug, d) -> { if (id != null && id.equals(d.optString("id"))) found[0] = toEntry(slug, d); });
        return found[0];
    }

    /** First index device whose match rules accept the bonded device, or null. */
    public static Entry match(String name, List<String> uuids, String modelName) {
        Entry[] found = new Entry[1];
        forEachDevice((slug, d) -> {
            if (found[0] != null) return;
            JSONObject m = d.optJSONObject("match");
            if (m == null) return;
            String rx = m.optString("name_regex", "");
            if (!rx.isEmpty() && name != null) {
                try { if (Pattern.compile(rx).matcher(name).find()) { found[0] = toEntry(slug, d); return; } }
                catch (Exception ignore) {}
            }
            JSONArray us = m.optJSONArray("service_uuids_any");
            if (us != null && uuids != null) {
                for (int i = 0; i < us.length(); i++)
                    if (uuids.contains(us.optString(i).toLowerCase())) { found[0] = toEntry(slug, d); return; }
            }
            String prefix = m.optString("model_name_prefix", "");
            if (!prefix.isEmpty() && modelName != null && modelName.startsWith(prefix)) found[0] = toEntry(slug, d);
        });
        return found[0];
    }
}
