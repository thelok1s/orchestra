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

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
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
}
