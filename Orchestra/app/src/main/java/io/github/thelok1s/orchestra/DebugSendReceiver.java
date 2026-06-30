package io.github.thelok1s.orchestra;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Debug helper to probe arbitrary RFCOMM control sequences against an enabled device, e.g. to
 * verify composite sound-mode sub-fields (ANC level, wind, transparency level) that have no native
 * UI yet. Fire with:
 *   adb shell am broadcast -a io.github.thelok1s.orchestra.DEBUG_SEND \
 *       --es mac F4:9D:8A:63:E5:F7 --es cmd 0681 --es payload 003000000005
 * Logs the TX frame + any reply to logcat tag "Orchestra". The device must be enabled in Devices.
 */
public class DebugSendReceiver extends BroadcastReceiver {
    static final String ACTION = "io.github.thelok1s.orchestra.DEBUG_SEND";
    static final String ACTION_AACP = "io.github.thelok1s.orchestra.AACP_TEST";
    static final String ACTION_AACP_MF = "io.github.thelok1s.orchestra.AACP_MF";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        final String action = intent.getAction();
        if (ACTION_AACP.equals(action)) { handleAacpTest(context, intent); return; }
        if (ACTION_AACP_MF.equals(action)) { handleAacpManifest(context, intent); return; }
        if (!ACTION.equals(action)) return;
        final String mac = intent.getStringExtra("mac");
        final String cmd = intent.getStringExtra("cmd");
        final String payload = intent.getStringExtra("payload"); // may be null
        if (mac == null || cmd == null) {
            Log.w(DeviceDef.TAG, "DEBUG_SEND: need --es mac and --es cmd");
            return;
        }
        final String address = mac.toUpperCase();
        final Context app = context.getApplicationContext();
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                DeviceDef def = DeviceDef.forAddress(address);
                if (def == null) {
                    Log.w(DeviceDef.TAG, "DEBUG_SEND: no enabled def for " + address);
                    return;
                }
                BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
                BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
                if (adapter == null) return;
                Log.i(DeviceDef.TAG, "DEBUG_SEND " + address + " cmd=" + cmd + " payload=" + payload);
                String reply = RfcommEngine.sendRaw(adapter, address, def, cmd, payload);
                Log.i(DeviceDef.TAG, "DEBUG_SEND reply=" + reply);
            } finally {
                pr.finish();
            }
        }).start();
    }

    private void handleAacpManifest(Context context, Intent intent) {
        final String mac = intent.getStringExtra("mac");
        if (mac == null) { Log.w(DeviceDef.TAG, "AACP_MF: need --es mac"); return; }
        final String address = mac.toUpperCase();
        final String optId = intent.getStringExtra("mode"); // off|anc|transparency|adaptive, or absent = read
        final Context app = context.getApplicationContext();
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
                BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
                if (adapter == null) return;
                // Ensure the device is enabled so DeviceDef.forAddress() resolves it.
                if (DeviceStore.enabledId(address) == null) {
                    android.bluetooth.BluetoothDevice dev = adapter.getRemoteDevice(address);
                    java.util.List<String> uuids = new java.util.ArrayList<>();
                    android.os.ParcelUuid[] pu = dev.getUuids();
                    if (pu != null) for (android.os.ParcelUuid p : pu) uuids.add(p.getUuid().toString());
                    String id = DeviceStore.idForBonded(dev.getName(), uuids, null);
                    if (id == null) { Log.w(DeviceDef.TAG, "AACP_MF: no manifest matches " + address); return; }
                    DeviceStore.setEnabled(address, id, true);
                    Log.i(DeviceDef.TAG, "AACP_MF: enabled " + address + " -> " + id);
                }
                DeviceDef def = DeviceDef.forAddress(address);
                if (def == null || def.soundMode == null) {
                    Log.w(DeviceDef.TAG, "AACP_MF: forAddress null / no soundMode for " + address);
                    return;
                }
                DeviceDef.Func anc = def.soundMode;
                ControlEngine engine = ControlEngine.forTransport(anc.transport);
                if (engine == null) { Log.w(DeviceDef.TAG, "AACP_MF: no engine for transport " + anc.transport); return; }
                Log.i(DeviceDef.TAG, "AACP_MF: def=" + def.id + " func=" + anc.id
                        + " transport=" + anc.transport + " injectable=" + anc.injectable);
                if (optId == null) {
                    String cur = engine.readMode(adapter, address, def, anc);
                    Log.i(DeviceDef.TAG, "AACP_MF read = " + cur);
                } else {
                    boolean ok = engine.applyMode(adapter, address, def, anc, optId);
                    Log.i(DeviceDef.TAG, "AACP_MF set " + optId + " ok=" + ok);
                }
            } finally {
                pr.finish();
            }
        }).start();
    }

    private void handleAacpTest(Context context, Intent intent) {
        final String mac = intent.getStringExtra("mac");
        if (mac == null) { Log.w(DeviceDef.TAG, "AACP_TEST: need --es mac"); return; }
        final String address = mac.toUpperCase();
        final String mode = intent.getStringExtra("mode"); // off|anc|transparency|adaptive, or absent = read
        final Context app = context.getApplicationContext();
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
                BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
                if (adapter == null) return;
                if (mode == null) {
                    AacpEngine.ensureConnected(adapter, address);
                    try { Thread.sleep(1500); } catch (InterruptedException ignored) {} // let a notify land
                    Integer b = AacpEngine.getAncByte(address);
                    Log.i(DeviceDef.TAG, "AACP_TEST read mode byte = " + b);
                } else {
                    int b;
                    switch (mode) {
                        case "off": b = 1; break;
                        case "anc": b = 2; break;
                        case "transparency": b = 3; break;
                        case "adaptive": b = 4; break;
                        default: Log.w(DeviceDef.TAG, "AACP_TEST: bad mode " + mode); return;
                    }
                    boolean ok = AacpEngine.setAncByte(adapter, address, b);
                    Log.i(DeviceDef.TAG, "AACP_TEST set " + mode + " (byte " + b + ") ok=" + ok);
                }
            } finally {
                pr.finish();
            }
        }).start();
    }
}
