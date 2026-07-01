package io.github.thelok1s.orchestra.aap;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
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
 * SystemUI Context passed to {@link #start}. ACL lifecycle ownership arrives in Task 5.
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
        connectKnown(ctx);
    }

    static void connectKnown(Context ctx) {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null || a.getBondedDevices() == null) return;
        for (BluetoothDevice d : a.getBondedDevices()) {
            if (!isAap(d)) continue;
            final String mac = d.getAddress().toUpperCase(Locale.ROOT);
            AacpEngine.registerListener(mac, "broker", () -> publishState(ctx, mac));
            new Thread(() -> AacpEngine.ensureConnected(a, mac), "aap-conn").start();
        }
    }

    static void handleCommand(String mac, String op, int value) {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) return;
        if ("anc".equals(op)) AacpEngine.setAncByte(a, mac, value);
        else if ("ca".equals(op)) AacpEngine.setCa(a, mac, value == 1);
    }

    /**
     * Auto-pause enable gate. Always {@code true} for now.
     * TODO Task 3: replace with a real DeviceStore / per-device preference lookup.
     */
    private static boolean autoPauseEnabled(String mac) {
        return true;
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
        if (now != null && autoPauseEnabled(mac)) {
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
