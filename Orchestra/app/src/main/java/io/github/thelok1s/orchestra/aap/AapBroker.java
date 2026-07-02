package io.github.thelok1s.orchestra.aap;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.ParcelUuid;
import android.util.Log;
import android.view.KeyEvent;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.thelok1s.orchestra.AacpEngine;
import io.github.thelok1s.orchestra.AapCodec;
import io.github.thelok1s.orchestra.AapState;

/**
 * SystemUI-resident AAP connection broker (Plan 6). SystemUI is always alive, so it becomes the
 * SOLE owner of the AAP L2CAP socket ({@link AacpEngine}); the app process is a broadcast client
 * ({@code io.github.thelok1s.orchestra.AacpClientBridge}). Loaded into SystemUI by the LSPosed
 * hook, which calls {@link #start(Context)} once a SystemUI Context is available.
 *
 * Wiring:
 *   • app -> broker: {@code AAP_CMD} (mac, op ∈ {anc,ca}, value) drives the socket off-thread.
 *   • broker -> app: {@code AAP_STATE} (mac + anc/ca/battery/ear, -1 = null) published from the
 *     engine's per-device change listener.
 *
 * NOTE: {@code App.context()} is null in the SystemUI process, so broker code always uses the
 * SystemUI Context passed to {@link #start}. The broker also owns the AAP ACL lifecycle (mirrors
 * the pre-broker app-side fix, commit {@code 1ba33bc}, now re-homed here since the socket lives in
 * this process): on {@code ACTION_ACL_DISCONNECTED} it tears the session down and publishes an
 * explicit cleared state; on {@code ACTION_ACL_CONNECTED} it forces a fresh session so a remote
 * L2CAP drop can never leave a stale/half-open socket in play.
 */
public final class AapBroker {
    private static final String TAG = "Orchestra";
    public static final String ACTION_CMD   = "io.github.thelok1s.orchestra.AAP_CMD";
    public static final String ACTION_STATE = "io.github.thelok1s.orchestra.AAP_STATE";

    private static final UUID AAP_UUID =
            UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a");

    private static volatile boolean started = false;

    /** Last-known ear state per MAC; used to detect worn-state transitions. */
    private static final Map<String, AapCodec.Ear> lastEar = new ConcurrentHashMap<>();

    /** Per-MAC behavior controller (auto-pause / resume). */
    private static final Map<String, AapBehaviorController> controllers = new ConcurrentHashMap<>();

    /**
     * Task 3+5: per-device LOCAL BEHAVIOR enable cache (auto_pause, ca_duck, ...), keyed
     * {@code "<MAC>|<behaviorId>"}. Populated by the app's push
     * ({@code AAP_CMD op="autopause"/"caduck"} -> {@link #handleCommand}) and, on a cache miss
     * (e.g. after a SystemUI restart), by a pull query against the app's {@code StateProvider}
     * ({@link #behaviorEnabled}). The broker cannot read the app's SharedPreferences directly
     * (different uid), hence the push+pull design.
     */
    private static final Map<String, Boolean> behaviorCache = new ConcurrentHashMap<>();

    private AapBroker() {}

    public static synchronized void start(Context ctx) {
        if (started) return;
        started = true;
        BroadcastReceiver cmd = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String mac = i.getStringExtra("mac");
                String op = i.getStringExtra("op");
                int value = i.getIntExtra("value", -1);
                if (mac == null || op == null) return;
                new Thread(() -> handleCommand(mac, op, value), "aap-cmd").start();
            }
        };
        ctx.registerReceiver(cmd, new IntentFilter(ACTION_CMD), Context.RECEIVER_EXPORTED);
        Log.i(TAG, "AapBroker started (AAP_CMD receiver registered)");

        BroadcastReceiver acl = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                try {
                    String action = i.getAction();
                    BluetoothDevice device = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (action == null || device == null || !isAap(device)) return;
                    Log.i(TAG, "AAP ACL event " + action + " for " + device.getAddress());
                    final String mac = device.getAddress().toUpperCase(Locale.ROOT);
                    final String act = action;
                    new Thread(() -> {
                        try {
                            if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(act)) {
                                onAclDisconnected(ctx, mac);
                            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(act)) {
                                onAclConnected(ctx, mac);
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "AapBroker ACL handling failed for " + mac + ": " + t);
                        }
                    }, "aap-acl").start();
                } catch (Throwable t) {
                    Log.w(TAG, "AapBroker ACL receiver dispatch failed: " + t);
                }
            }
        };
        IntentFilter aclFilter = new IntentFilter();
        aclFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        aclFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        ctx.registerReceiver(acl, aclFilter, Context.RECEIVER_EXPORTED);
        Log.i(TAG, "AapBroker started (ACL receiver registered)");

        connectKnown(ctx);
    }

    static void connectKnown(Context ctx) {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null || a.getBondedDevices() == null) return;
        for (BluetoothDevice d : a.getBondedDevices()) {
            if (!isAap(d)) continue;
            registerAndConnect(ctx, a, d.getAddress().toUpperCase(Locale.ROOT));
        }
    }

    /**
     * Registers the broker's engine listener (idempotent — {@link AacpEngine#registerListener}
     * overwrites by key) and kicks off {@link AacpEngine#ensureConnected} on a background thread.
     * Shared by boot-time {@link #connectKnown} and the {@code ACTION_ACL_CONNECTED} path so
     * connect-time registration isn't duplicated.
     */
    private static void registerAndConnect(Context ctx, BluetoothAdapter a, String mac) {
        AacpEngine.registerListener(mac, "broker", () -> publishState(ctx, mac));
        AacpEngine.registerSpeechListener(mac, level -> onSpeechLevel(ctx, mac, level));
        new Thread(() -> AacpEngine.ensureConnected(a, mac), "aap-conn").start();
    }

    /**
     * Mirrors {@code 1ba33bc}'s ACL_DISCONNECTED handling, now in the broker: tear the (possibly
     * half-open) session down so the next connect can't reuse it, reset the auto-pause baseline so
     * the next session's first ear frame is treated as a fresh baseline rather than a phantom
     * transition (which would otherwise fire a spurious pause/play), and tell the app-side bridge
     * to clear its cache via an explicit {@code cleared} extra (see {@link #publishState} — its own
     * all- -1 payload from {@code AacpEngine.disconnect}'s {@code fireListener} is a no-op app-side
     * by design; this explicit broadcast is the authoritative clear).
     */
    private static void onAclDisconnected(Context ctx, String mac) {
        AacpEngine.disconnect(mac);
        lastEar.remove(mac);
        publishCleared(ctx, mac);
    }

    /**
     * Mirrors {@code 1ba33bc}'s ACL_CONNECTED handling: force a fresh session on every connect
     * (drop then reconnect) so a remote drop can never leave stale battery/ear/ANC behind.
     *
     * <p>Timing (hardware-observed): connecting the AAP channel immediately after ACL_CONNECTED
     * (~60ms) yields a PARTIAL bring-up dump — the buds ack ANC/CA but never send battery/ear for
     * the life of the session (their state engines are still initializing while the classic
     * profiles come up). So we wait before connecting, then verify the dump actually delivered
     * battery, and retry with a fresh session if it didn't. Runs on the dedicated "aap-acl"
     * thread — sleeping here blocks nothing else.
     */
    /** Per-MAC serialization for {@link #onAclConnected} — ACL_CONNECTED can fire once per
     *  transport (BR/EDR + LE); two concurrent retry loops would tear down each other's fresh
     *  sessions. The second entrant waits, sees battery already present, and returns fast. */
    private static final Map<String, Object> RECONNECT_LOCKS = new ConcurrentHashMap<>();

    private static void onAclConnected(Context ctx, String mac) {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) return;
        AacpEngine.registerListener(mac, "broker", () -> publishState(ctx, mac));
        AacpEngine.registerSpeechListener(mac, level -> onSpeechLevel(ctx, mac, level));
        synchronized (RECONNECT_LOCKS.computeIfAbsent(mac, k -> new Object())) {
        if (AapState.forMac(mac).getBattery() != null) return; // a concurrent attempt already completed
        for (int attempt = 1; attempt <= 3; attempt++) {
            AacpEngine.disconnect(mac); // always a fresh session (never reuse a half-open socket)
            try { Thread.sleep(2500); } catch (InterruptedException e) { return; }
            AacpEngine.ensureConnected(a, mac);
            // Grace period for the bring-up dump, then check it was complete (battery present).
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline) {
                if (AapState.forMac(mac).getBattery() != null) {
                    Log.i(TAG, "AAP reconnect complete for " + mac + " (attempt " + attempt + ")");
                    return;
                }
                try { Thread.sleep(200); } catch (InterruptedException e) { return; }
            }
            Log.w(TAG, "AAP reconnect attempt " + attempt + " for " + mac
                    + ": bring-up dump incomplete (no battery), retrying");
        }
        Log.w(TAG, "AAP reconnect gave up after 3 attempts for " + mac);
        } // end per-mac lock
    }

    /** Explicit disconnect-clear publish for {@link io.github.thelok1s.orchestra.AacpClientBridge}:
     *  a fully -1 {@code AAP_STATE} is indistinguishable from "nothing new to report" app-side, so
     *  the clear is carried by a dedicated {@code cleared} boolean extra instead. */
    private static void publishCleared(Context ctx, String mac) {
        Intent i = new Intent(ACTION_STATE).putExtra("mac", mac).putExtra("cleared", true);
        ctx.sendBroadcast(i);
    }

    static void handleCommand(String mac, String op, int value) {
        // autopause/caduck are LOCAL cache updates (the app process persisted the enable in
        // DeviceStore and is just pushing it here); they never touch the AAP socket, so handle
        // them before the adapter lookup.
        if ("autopause".equals(op)) {
            behaviorCache.put(mac.toUpperCase(Locale.ROOT) + "|auto_pause", value == 1);
            Log.i(TAG, "auto_pause cache <- " + mac + "=" + (value == 1));
            return;
        }
        if ("caduck".equals(op)) {
            behaviorCache.put(mac.toUpperCase(Locale.ROOT) + "|ca_duck", value == 1);
            Log.i(TAG, "ca_duck cache <- " + mac + "=" + (value == 1));
            return;
        }
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) return;
        if ("anc".equals(op)) AacpEngine.setAncByte(a, mac, value);
        else if ("ca".equals(op)) AacpEngine.setCa(a, mac, value == 1);
    }

    /**
     * LOCAL behavior enable gate (auto_pause, ca_duck, ...): cache lookup, falling back to a pull
     * query against the app's {@code StateProvider} on a miss (e.g. after a SystemUI restart,
     * before any push has arrived). Any failure (app process dead + can't be started, provider not
     * exported yet, etc.) is treated as disabled — matches {@code DeviceStore.behaviorEnabled}'s
     * default-off. Generalized from Task 3's {@code auto_pause}-only cache.
     */
    private static boolean behaviorEnabled(Context ctx, String mac, String behaviorId) {
        String macKey = mac.toUpperCase(Locale.ROOT);
        String cacheKey = macKey + "|" + behaviorId;
        Boolean cached = behaviorCache.get(cacheKey);
        if (cached != null) return cached;
        boolean enabled = queryBehaviorEnabled(ctx, macKey, behaviorId);
        behaviorCache.put(cacheKey, enabled);
        return enabled;
    }

    private static boolean queryBehaviorEnabled(Context ctx, String mac, String behaviorId) {
        try {
            Uri uri = Uri.parse("content://io.github.thelok1s.orchestra.state/behavior/"
                    + mac + "/" + behaviorId);
            try (Cursor cur = ctx.getContentResolver().query(uri, null, null, null, null)) {
                if (cur != null && cur.moveToFirst()) {
                    return cur.getInt(cur.getColumnIndexOrThrow("enabled")) == 1;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "behaviorEnabled: pull query failed for " + mac + "/" + behaviorId + ": " + t);
        }
        return false;
    }

    /** Dispatches a media key (ACTION_DOWN + ACTION_UP) via AudioManager. Fire-and-forget. */
    private static void dispatchKey(Context ctx, int keyCode) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,   keyCode));
        } catch (Throwable t) {
            Log.w(TAG, "dispatchKey keyCode=" + keyCode + " failed: " + t);
        }
    }

    /**
     * {@link AapBehaviorController.MediaActions} impl (SystemUI process, holds audio privileges):
     * pause/play dispatch media keys (Task 2); duck/restore (Task 5) move {@code STREAM_MUSIC} —
     * no UI (flags 0) — remembering the pre-duck volume per instance so {@code restore()} can put
     * it back. One instance lives per MAC (see {@link #controllerFor}), so the remembered volume
     * is naturally per-device.
     *
     * <p>duck()/restore() run on the per-device AAP reader thread and must never sleep on it, so
     * the actual fade is a background {@code aap-volramp} thread stepping the stream index one at
     * a time (duck: ~{@link #DUCK_RAMP_MS} fast fade down; restore: ~{@link #RESTORE_RAMP_MS}
     * gentle fade up — matches Apple's Conversational Awareness). {@code rampGen} cancels a
     * still-animating ramp when a newer duck/restore supersedes it; the ramp thread checks it
     * before every step.
     */
    private static final class MediaActionsImpl implements AapBehaviorController.MediaActions {
        private static final long DUCK_RAMP_MS = 400;
        private static final long RESTORE_RAMP_MS = 1200;

        private final Context ctx;
        private final String mac;
        /** Volume to restore to, or null when not currently ducked. */
        private volatile Integer preDuckVolume;
        /** Bumped on every duck()/restore() call; a ramp thread bails out once it's stale. */
        private final AtomicInteger rampGen = new AtomicInteger();

        MediaActionsImpl(Context ctx, String mac) { this.ctx = ctx; this.mac = mac; }

        @Override public void pause() { dispatchKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE); }
        @Override public void play()  { dispatchKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY);  }

        @Override public void duck() {
            try {
                AudioManager am = ctx.getSystemService(AudioManager.class);
                if (am == null) return;
                int gen = rampGen.incrementAndGet();
                Integer base = preDuckVolume;
                if (base == null) {
                    // only remember the baseline when we're not already ducked/mid-restore, so a
                    // duck arriving while a restore ramp is still animating can't clobber it with
                    // a half-restored value.
                    base = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                    preDuckVolume = base;
                }
                int target = Math.max(1, base / 4);
                Log.i(TAG, "ca_duck: ducking " + mac + " toward " + target);
                ramp(am, target, DUCK_RAMP_MS, gen, false);
            } catch (Throwable t) {
                Log.w(TAG, "ca_duck: duck failed for " + mac + ": " + t);
            }
        }

        @Override public void restore() {
            try {
                Integer v = preDuckVolume;
                if (v == null) return; // nothing to restore
                AudioManager am = ctx.getSystemService(AudioManager.class);
                if (am == null) return;
                int gen = rampGen.incrementAndGet();
                Log.i(TAG, "ca_duck: restoring " + mac + " toward " + v);
                ramp(am, v, RESTORE_RAMP_MS, gen, true);
            } catch (Throwable t) {
                Log.w(TAG, "ca_duck: restore failed for " + mac + ": " + t);
            }
        }

        /**
         * Steps {@code STREAM_MUSIC} from its current index to {@code target}, one index per
         * {@code totalMs / steps}, on a dedicated daemon thread. Bails out (silently, no log) the
         * moment {@code rampGen} moves past {@code gen} — a newer duck/restore took over. When
         * {@code clearOnComplete} the ramp owns clearing {@link #preDuckVolume}, and only does so
         * if it finishes without being superseded (a superseded restore must leave the original
         * baseline in place for whatever duck/restore comes next).
         */
        private void ramp(AudioManager am, int target, long totalMs, int gen, boolean clearOnComplete) {
            Thread t = new Thread(() -> {
                try {
                    int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                    int steps = Math.abs(target - cur);
                    if (steps == 0) {
                        if (clearOnComplete && rampGen.get() == gen) preDuckVolume = null;
                        return;
                    }
                    int dir = target > cur ? 1 : -1;
                    long perStep = totalMs / steps;
                    int v = cur;
                    for (int i = 0; i < steps; i++) {
                        if (rampGen.get() != gen) return; // superseded mid-flight
                        v += dir;
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
                        if (perStep > 0) {
                            try { Thread.sleep(perStep); } catch (InterruptedException ie) { return; }
                        }
                    }
                    if (clearOnComplete && rampGen.get() == gen) preDuckVolume = null;
                } catch (Throwable th) {
                    Log.w(TAG, "aap-volramp failed for " + mac + ": " + th);
                }
            }, "aap-volramp");
            t.setDaemon(true);
            t.start();
        }
    }

    /** The single per-MAC {@link AapBehaviorController} instance, shared by the ear (auto_pause)
     *  and speech (ca_duck) event paths — its {@link MediaActionsImpl} carries both bookkeeping. */
    private static AapBehaviorController controllerFor(Context ctx, String mac) {
        return controllers.computeIfAbsent(mac, k ->
                new AapBehaviorController(new MediaActionsImpl(ctx, mac), System::currentTimeMillis));
    }

    /**
     * Conversational Awareness speech-level dispatch (Task 5): gated on the {@code ca_duck} local
     * behavior toggle, then handed to the per-mac controller's duck/restore bookkeeping. Fail-soft
     * — this runs off {@link io.github.thelok1s.orchestra.AacpEngine}'s reader thread via the
     * speech-listener registry, which already try/Throwable-guards the callback, but we guard here
     * too since this method also does the enable-gate cache/pull query.
     */
    private static void onSpeechLevel(Context ctx, String mac, int level) {
        try {
            if (!behaviorEnabled(ctx, mac, "ca_duck")) return;
            controllerFor(ctx, mac).onCaSpeech(level);
        } catch (Throwable t) {
            Log.w(TAG, "onSpeechLevel failed for " + mac + " level=" + level + ": " + t);
        }
    }

    static void publishState(Context ctx, String mac) {
        AapState s = AapState.forMac(mac);
        AapCodec.Battery b = s.getBattery();
        AapCodec.Ear now = s.getEar();

        // Auto-pause / resume: compare ear to previous and drive the behavior controller.
        if (now != null && behaviorEnabled(ctx, mac, "auto_pause")) {
            AapCodec.Ear prev = lastEar.put(mac, now); // atomic swap; returns old value (null on first call)
            controllerFor(ctx, mac).onEar(prev, now);
        }

        AapCodec.Ear e = now; // reuse for the broadcast below
        Intent i = new Intent(ACTION_STATE).putExtra("mac", mac)
            .putExtra("anc", s.getAncMode() == null ? -1 : s.getAncMode())
            .putExtra("ca", s.getCaEnabled() == null ? -1 : (s.getCaEnabled() ? 1 : 0))
            .putExtra("bl",  b == null || b.left == null        ? -1 : b.left)
            .putExtra("bls", b == null || b.leftStatus == null  ? -1 : b.leftStatus)
            .putExtra("br",  b == null || b.right == null       ? -1 : b.right)
            .putExtra("brs", b == null || b.rightStatus == null ? -1 : b.rightStatus)
            .putExtra("bc",  b == null || b.caseLevel == null   ? -1 : b.caseLevel)
            .putExtra("bcs", b == null || b.caseStatus == null  ? -1 : b.caseStatus)
            .putExtra("ep",  e == null ? -1 : e.primary)
            .putExtra("es",  e == null ? -1 : e.secondary);
        ctx.sendBroadcast(i); // plain; the app receiver is EXPORTED (different-signer asymmetry)
    }

    static boolean isAap(BluetoothDevice d) {
        try {
            ParcelUuid[] uuids = d.getUuids();
            if (uuids != null) for (ParcelUuid p : uuids) {
                if (AAP_UUID.equals(p.getUuid())) return true;
            }
        } catch (Throwable ignore) {}
        return false;
    }
}
