package io.github.thelok1s.orchestra;

import android.bluetooth.BluetoothAdapter;

/**
 * Transport abstraction seam. Today only rfcomm is implemented (delegates to {@link RfcommEngine});
 * the registry lets future ble_gatt / aacp engines be added without changing the provider.
 */
public interface ControlEngine {
    boolean applyMode(BluetoothAdapter a, String mac, DeviceDef def, DeviceDef.Func f, String optId);
    String readMode(BluetoothAdapter a, String mac, DeviceDef def, DeviceDef.Func f);
    boolean applyToggle(BluetoothAdapter a, String mac, DeviceDef def, DeviceDef.Func f, boolean on);
    Boolean readToggle(BluetoothAdapter a, String mac, DeviceDef def, DeviceDef.Func f);

    ControlEngine RFCOMM = new ControlEngine() {
        public boolean applyMode(BluetoothAdapter a, String mac, DeviceDef def, DeviceDef.Func f, String optId) {
            return RfcommEngine.applyMode(a, mac, def, f, optId);
        }
        public String readMode(BluetoothAdapter a, String mac, DeviceDef def, DeviceDef.Func f) {
            return RfcommEngine.readMode(a, mac, def, f);
        }
        public boolean applyToggle(BluetoothAdapter a, String mac, DeviceDef def, DeviceDef.Func f, boolean on) {
            return RfcommEngine.applyToggle(a, mac, def, f, on);
        }
        public Boolean readToggle(BluetoothAdapter a, String mac, DeviceDef def, DeviceDef.Func f) {
            return RfcommEngine.readToggle(a, mac, def, f);
        }
    };

    /** @return the engine for a transport, or null if this app build can't drive it. */
    static ControlEngine forTransport(String transport) {
        return "rfcomm".equals(transport) ? RFCOMM : null;
    }
}
