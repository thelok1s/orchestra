package io.github.thelok1s.orchestra;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.Map;

/**
 * Re-asserts metadata key 25 so the system binds OUR config provider:
 *  - BOOT_COMPLETED / io.github.thelok1s.orchestra.APPLY: (re)write tags for every enabled device.
 *  - ACL_CONNECTED: write tags for the just-connected device if it's enabled (clobber guard
 *    against GMS Fast Pair, which may rewrite key 25 around connection time).
 */
public class ConnectReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (action == null) return;
        final PendingResult pending = goAsync();
        final Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                BluetoothManager bm = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
                BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
                if (adapter == null) { Log.w(DeviceDef.TAG, "receiver: no adapter"); return; }

                if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device == null) return;
                    String mac = device.getAddress() != null ? device.getAddress().toUpperCase() : null;
                    DeviceDef def = mac != null ? DeviceDef.enabled().get(mac) : null;
                    if (def != null) {
                        Metadata.assertConfigTags(device, def.id);
                    }
                } else { // BOOT_COMPLETED or APPLY
                    Map<String, DeviceDef> enabled = DeviceDef.enabled();
                    Log.i(DeviceDef.TAG, "receiver " + action + ": asserting " + enabled.size() + " device(s)");
                    for (Map.Entry<String, DeviceDef> e : enabled.entrySet()) {
                        try {
                            Metadata.assertConfigTags(adapter.getRemoteDevice(e.getKey()), e.getValue().id);
                        } catch (Exception ex) {
                            Log.w(DeviceDef.TAG, "assert " + e.getKey() + " failed: " + ex);
                        }
                    }
                }
            } finally {
                pending.finish();
            }
        }).start();
    }
}
