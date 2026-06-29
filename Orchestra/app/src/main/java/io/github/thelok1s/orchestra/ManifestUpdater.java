package io.github.thelok1s.orchestra;

import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** OTA: refresh the index on a TTL, download/verify manifests by sha256 + revision. */
public final class ManifestUpdater {
    private static final long TTL_MS = 12L * 60 * 60 * 1000; // 12h
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private ManifestUpdater() {}

    public static void refreshIndexIfStale() {
        IO.execute(() -> {
            File f = IndexStore.indexFile();
            long age = f.exists() ? System.currentTimeMillis() - f.lastModified() : Long.MAX_VALUE;
            if (age < TTL_MS) return;
            fetchIndex();
        });
    }

    public static void checkNow(Runnable onDone) {
        IO.execute(() -> { fetchIndex(); if (onDone != null) onDone.run(); });
    }

    private static void fetchIndex() {
        String body = ManifestRepository.httpGet(ManifestRepository.REPO_RAW_BASE + "index.json");
        if (body == null) return;
        try {
            new JSONObject(body); // validate parseable before persisting
            Files.write(IndexStore.indexFile().toPath(), body.getBytes(StandardCharsets.UTF_8));
            Log.i(DeviceDef.TAG, "index refreshed");
        } catch (Exception e) { Log.w(DeviceDef.TAG, "index refresh rejected: " + e); }
    }

    /** True if an update is available for an id (index revision > local manifest's). */
    public static boolean updateAvailable(String id) {
        IndexStore.Entry e = IndexStore.entryById(id);
        if (e == null) return false;
        DeviceDef def = DeviceDef.loadById(id);
        int local = def != null ? def.revision : -1;
        return e.revision > local;
    }

    /**
     * Returns int[2] { localRevision, indexRevision } when an update is available, else null.
     * Allows the UI to display "rev N → M" without touching the package-private DeviceDef.
     */
    public static int[] updateRevisions(String id) {
        IndexStore.Entry e = IndexStore.entryById(id);
        if (e == null) return null;
        DeviceDef def = DeviceDef.loadById(id);
        int local = def != null ? def.revision : 0;
        if (e.revision <= local) return null;
        return new int[]{local, e.revision};
    }

    /** Download (if missing or stale-by-revision), sha256-verify, persist. Blocking. */
    public static boolean ensureManifest(String id) {
        IndexStore.Entry e = IndexStore.entryById(id);
        if (e == null) return false;
        // already current?
        DeviceDef cur = DeviceDef.loadById(id);
        if (cur != null && cur.revision >= e.revision && !"bundled".equals(ManifestRepository.sourceOf(id))) {
            return true;
        }
        String body = ManifestRepository.httpGet(ManifestRepository.REPO_RAW_BASE + e.url);
        if (body == null) return false;
        if (!sha256(body).equalsIgnoreCase(e.sha256)) {
            Log.w(DeviceDef.TAG, "sha256 mismatch for " + id + " — rejecting download");
            return false;
        }
        ManifestRepository.saveDownloaded(id, body);
        Log.i(DeviceDef.TAG, "downloaded manifest " + id + " rev " + e.revision);
        return true;
    }

    public static void ensureManifestAsync(String id, Runnable onDone) {
        IO.execute(() -> { ensureManifest(id); if (onDone != null) onDone.run(); });
    }

    /**
     * Returns the revision of the highest-precedence local manifest for {@code id}, or -1 if not
     * present. Safe to call from Kotlin (DeviceDef is package-private; this helper bridges it).
     */
    public static int revisionOf(String id) {
        DeviceDef def = DeviceDef.loadById(id);
        return def != null ? def.revision : -1;
    }

    private static String sha256(String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }
}
