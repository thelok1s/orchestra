package io.github.thelok1s.orchestra;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Applies an ANC mode by toggle INDEX, triggered by the LSPosed hook on the volume-panel popup
 * (whose native updateState callback doesn't reach our provider). The hook broadcasts:
 *   am broadcast -a io.github.thelok1s.orchestra.APPLY_INDEX --es mac <MAC> --ei index <i>
 * We look up the device def by MAC and apply options[index] over RFCOMM (reusing RfcommEngine).
 */
public class VolumeApplyReceiver extends BroadcastReceiver {
    static final String ACTION = "io.github.thelok1s.orchestra.APPLY_INDEX";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        final String mac = intent.getStringExtra("mac");
        final int index = intent.getIntExtra("index", -1);
        if (mac == null || index < 0) return;
        final String address = mac.toUpperCase();
        final Context app = context.getApplicationContext();
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                DeviceDef def = DeviceDef.forAddress(address);
                if (def == null || def.soundMode == null
                        || index >= def.soundMode.options.size()) {
                    Log.w(DeviceDef.TAG, "APPLY_INDEX: no def/bad index for " + address + " idx=" + index);
                    return;
                }
                String optId = def.soundMode.options.get(index).id;
                BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
                BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
                if (adapter == null) return;
                Log.i(DeviceDef.TAG, "APPLY_INDEX " + address + " -> " + optId + " (idx " + index + ")");
                RfcommEngine.applyMode(adapter, address, def, optId);
            } finally {
                pr.finish();
            }
        }).start();
    }
}
