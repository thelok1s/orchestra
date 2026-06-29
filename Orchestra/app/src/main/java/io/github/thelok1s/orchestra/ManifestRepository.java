package io.github.thelok1s.orchestra;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Single resolver for device manifests. Precedence: sideload (filesDir/sideload) > downloaded
 * (filesDir/manifests) > bundled seed (assets/devices). Also the HTTPS fetcher for OTA.
 */
public final class ManifestRepository {
    public static final String REPO_RAW_BASE =
            "https://raw.githubusercontent.com/thelok1s/orchestra-manifests/main/";

    private ManifestRepository() {}

    private static Context ctx() { return App.context(); }
    private static File downloadedDir() { return ensure(new File(ctx().getFilesDir(), "manifests")); }
    private static File sideloadDir() { return ensure(new File(ctx().getFilesDir(), "sideload")); }
    private static File ensure(File d) { if (!d.exists()) d.mkdirs(); return d; }

    public static String sourceOf(String id) {
        if (id == null) return null;
        if (new File(sideloadDir(), id + ".json").exists()) return "sideload";
        if (new File(downloadedDir(), id + ".json").exists()) return "downloaded";
        if (assetExists("devices/" + id + ".json")) return "bundled";
        return null;
    }

    /** Highest-precedence manifest body for an id, or null. */
    public static String rawDeviceJson(String id) {
        if (id == null) return null;
        String s = readFile(new File(sideloadDir(), id + ".json"));
        if (s != null) return s;
        s = readFile(new File(downloadedDir(), id + ".json"));
        if (s != null) return s;
        return readAsset("devices/" + id + ".json");
    }

    /** True when this id came from a sideloaded file (UI badge + verified-gate bypass). */
    public static boolean isSideloaded(String id) {
        return id != null && new File(sideloadDir(), id + ".json").exists();
    }

    public static void saveDownloaded(String id, String json) { writeFile(new File(downloadedDir(), id + ".json"), json); }
    public static void saveSideload(String id, String json) { writeFile(new File(sideloadDir(), id + ".json"), json); }
    public static void clearSideload(String id) { new File(sideloadDir(), id + ".json").delete(); }

    /** IDs of all currently sideloaded manifests (files in filesDir/sideload/). */
    public static java.util.List<String> sideloadedIds() {
        java.util.List<String> out = new java.util.ArrayList<>();
        File[] files = sideloadDir().listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.getName().endsWith(".json")) out.add(f.getName().replace(".json", ""));
        }
        return out;
    }

    /** IDs of all currently downloaded manifests (files in filesDir/manifests/). */
    public static java.util.List<String> downloadedIds() {
        java.util.List<String> out = new java.util.ArrayList<>();
        File[] files = downloadedDir().listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.getName().endsWith(".json")) out.add(f.getName().replace(".json", ""));
        }
        return out;
    }

    /** Blocking HTTPS GET (call off the main thread). Returns body or null. */
    public static String httpGet(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "Orchestra");
            int code = c.getResponseCode();
            if (code != 200) { Log.w(DeviceDef.TAG, "httpGet " + code + " " + url); return null; }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line; while ((line = r.readLine()) != null) sb.append(line).append('\n');
                return sb.toString();
            }
        } catch (Exception e) {
            Log.w(DeviceDef.TAG, "httpGet failed " + url + ": " + e);
            return null;
        } finally { if (c != null) c.disconnect(); }
    }

    private static String readFile(File f) {
        try { return f.exists() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null; }
        catch (Exception e) { return null; }
    }
    private static void writeFile(File f, String body) {
        try { Files.write(f.toPath(), body.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { Log.w(DeviceDef.TAG, "writeFile " + f + " failed: " + e); }
    }
    private static boolean assetExists(String path) {
        try (InputStream is = ctx().getAssets().open(path)) { return true; } catch (Exception e) { return false; }
    }
    private static String readAsset(String path) {
        try (InputStream is = ctx().getAssets().open(path);
             BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) { return null; }
    }
}
