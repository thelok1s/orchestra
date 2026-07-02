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
     * Task 3: auto_pause per-device enable cache, keyed by MAC. Populated by the app's push
     * ({@code AAP_CMD op="autopause"} -> {@link #handleCommand}) and, on a cache miss (e.g. after a
     * SystemUI restart), by a pull query against the app's {@code StateProvider}
     * ({@link #autoPauseEnabled}). The broker cannot read the app's SharedPreferences directly
     * (different uid), hence the push+pull design.
     */
    private static final Map<String, Boolean> autoPauseCache = new ConcurrentHashMap<>();

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
        // autopause is a LOCAL cache update (the app process persisted the enable in DeviceStore
        // and is just pushing it here); it never touches the AAP socket, so handle it before the
        // adapter lookup.
        if ("autopause".equals(op)) {
            autoPauseCache.put(mac.toUpperCase(Locale.ROOT), value == 1);
            Log.i(TAG, "autopause cache <- " + mac + "=" + (value == 1));
            return;
        }
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) return;
        if ("anc".equals(op)) AacpEngine.setAncByte(a, mac, value);
        else if ("ca".equals(op)) AacpEngine.setCa(a, mac, value == 1);
    }

    /**
     * Auto-pause enable gate: cache lookup, falling back to a pull query against the app's
     * {@code StateProvider} on a miss (e.g. after a SystemUI restart, before any push has arrived).
     * Any failure (app process dead + can't be started, provider not exported yet, etc.) is treated
     * as disabled — matches {@code DeviceStore.behaviorEnabled}'s default-off.
     */
    private static boolean autoPauseEnabled(Context ctx, String mac) {
        String key = mac.toUpperCase(Locale.ROOT);
        Boolean cached = autoPauseCache.get(key);
        if (cached != null) return cached;
        boolean enabled = queryAutoPauseEnabled(ctx, key);
        autoPauseCache.put(key, enabled);
        return enabled;
    }

    private static boolean queryAutoPauseEnabled(Context ctx, String mac) {
        try {
            Uri uri = Uri.parse("content://io.github.thelok1s.orchestra.state/behavior/"
                    + mac + "/auto_pause");
            try (Cursor cur = ctx.getContentResolver().query(uri, null, null, null, null)) {
                if (cur != null && cur.moveToFirst()) {
                    return cur.getInt(cur.getColumnIndexOrThrow("enabled")) == 1;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "autoPauseEnabled: pull query failed for " + mac + ": " + t);
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

    static void publishState(Context ctx, String mac) {
        AapState s = AapState.forMac(mac);
        AapCodec.Battery b = s.getBattery();
        AapCodec.Ear now = s.getEar();

        // Auto-pause / resume: compare ear to previous and drive the behavior controller.
        if (now != null && autoPauseEnabled(ctx, mac)) {
            AapCodec.Ear prev = lastEar.put(mac, now); // atomic swap; returns old value (null on first call)
            AapBehaviorController ctrl = controllers.computeIfAbsent(mac, k ->
                new AapBehaviorController(new AapBehaviorController.MediaActions() {
                    @Override public void pause() { dispatchKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE); }
                    @Override public void play()  { dispatchKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY);  }
                }, System::currentTimeMillis));
            ctrl.onEar(prev, now);
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
