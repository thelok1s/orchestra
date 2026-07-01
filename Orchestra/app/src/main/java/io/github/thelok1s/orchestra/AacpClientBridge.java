package io.github.thelok1s.orchestra;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import io.github.thelok1s.orchestra.aap.AapBroker;

/**
 * App-process client half of the AAP connection broker (Plan 6). The socket is owned by the
 * SystemUI broker ({@link AapBroker}); this app process never opens an L2CAP socket for an AAP
 * device. Instead it:
 *   • consumes {@code AAP_STATE} broadcasts (broker -> app), writing them into {@link AapState}
 *     and firing the app-side listeners so the device-settings provider + Devices-tab UI refresh;
 *   • sends {@code AAP_CMD} broadcasts (app -> broker) to drive ANC / CA.
 *
 * Guard choice (Plan 5 T3 lesson): SystemUI is a different signer and cannot hold our signature
 * permission, so the {@code AAP_STATE} receiver is registered EXPORTED with NO permission and the
 * broker sends state plain. The payload is non-sensitive device state (a MAC + battery/ANC levels).
 */
public final class AacpClientBridge {
    private static volatile boolean started = false;
    private static volatile Context appCtx;

    private AacpClientBridge() {}

    /** Register the AAP_STATE receiver once. Call from {@link App#onCreate}. */
    public static synchronized void init(Context ctx) {
        if (started) return;
        started = true;
        appCtx = ctx.getApplicationContext();
        BroadcastReceiver state = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { applyState(i); }
        };
        // EXPORTED, no permission: the sender is the SystemUI broker (a different signer).
        appCtx.registerReceiver(state, new IntentFilter(AapBroker.ACTION_STATE),
                Context.RECEIVER_EXPORTED);
        Log.i(DeviceDef.TAG, "AacpClientBridge: AAP_STATE receiver registered");
    }

    private static void applyState(Intent i) {
        String mac = i.getStringExtra("mac");
        if (mac == null) return;
        AapState s = AapState.forMac(mac);
        int anc = i.getIntExtra("anc", -1);
        if (anc >= 0) s.setAncMode(anc);
        int ca = i.getIntExtra("ca", -1);
        if (ca >= 0) s.setCaEnabled(ca == 1);
        Integer bl = nz(i.getIntExtra("bl", -1)),  bls = nz(i.getIntExtra("bls", -1)),
                br = nz(i.getIntExtra("br", -1)),  brs = nz(i.getIntExtra("brs", -1)),
                bc = nz(i.getIntExtra("bc", -1)),  bcs = nz(i.getIntExtra("bcs", -1));
        if (bl != null || br != null || bc != null || bls != null || brs != null || bcs != null) {
            s.setBattery(new AapCodec.Battery(bl, bls, br, brs, bc, bcs));
        }
        int ep = i.getIntExtra("ep", -1), es = i.getIntExtra("es", -1);
        if (ep >= 0 && es >= 0) s.setEar(new AapCodec.Ear(ep, es));
        // Refresh the device-settings provider + any Devices-tab UI subscribed via the app-side
        // keyed listener registry (the broker's own "broker" key lives in the SystemUI process).
        AacpEngine.fireListener(mac);
    }

    private static Integer nz(int v) { return v < 0 ? null : v; }

    /** Send a control command to the broker. op is {@code "anc"} (value 1..4) or {@code "ca"} (0/1). */
    public static void sendCommand(String mac, String op, int value) {
        Context ctx = appCtx != null ? appCtx : App.context();
        if (ctx == null) { Log.w(DeviceDef.TAG, "AacpClientBridge.sendCommand: no context"); return; }
        Intent i = new Intent(AapBroker.ACTION_CMD)
                .putExtra("mac", mac).putExtra("op", op).putExtra("value", value);
        ctx.sendBroadcast(i); // plain; the broker's receiver is EXPORTED (no permission)
        Log.i(DeviceDef.TAG, "AAP_CMD -> " + mac + " " + op + "=" + value);
    }
}
