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
    String readInfo(BluetoothAdapter adapter, String mac, DeviceDef def, DeviceDef.Func f);
    boolean applyLevel(BluetoothAdapter a, String mac, DeviceDef def, DeviceDef.Func f, int value);
    Integer readLevel(BluetoothAdapter a, String mac, DeviceDef def, DeviceDef.Func f);
    void registerListener(String mac, String key, Runnable onChange);
    void unregisterListener(String mac, String key);

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
        @Override public String readInfo(BluetoothAdapter a, String mac, DeviceDef d, DeviceDef.Func f) {
            return null; // RFCOMM has no info/push functions today
        }
        @Override public boolean applyLevel(BluetoothAdapter a, String mac, DeviceDef def, DeviceDef.Func f, int value) {
            return RfcommEngine.applyLevel(a, mac, def, f, value); // single-value level/slider over soundcore_v1
        }
        @Override public Integer readLevel(BluetoothAdapter a, String mac, DeviceDef def, DeviceDef.Func f) {
            return RfcommEngine.readLevel(a, mac, def, f);
        }
        @Override public void registerListener(String mac, String key, Runnable onChange) { /* no push channel */ }
        @Override public void unregisterListener(String mac, String key) { /* no-op */ }
    };

    // AAP is brokered: the SystemUI-resident AapBroker owns the socket. This app-process engine
    // NEVER opens an L2CAP socket — sets go out as AAP_CMD broadcasts, reads come from the
    // broadcast-fed AapState cache (populated by AacpClientBridge from AAP_STATE).
    ControlEngine AACP = new ControlEngine() {
        public boolean applyMode(BluetoothAdapter a, String mac, DeviceDef def, DeviceDef.Func f, String optId) {
            if (f == null) return false;
            String valueHex = f.optionValues.get(optId);
            if (valueHex == null) { android.util.Log.w(DeviceDef.TAG, "AACP no option_value for " + optId); return false; }
            int modeByte;
            try { modeByte = Integer.parseInt(valueHex, 16); }
            catch (NumberFormatException e) { android.util.Log.w(DeviceDef.TAG, "AACP bad option_value hex: " + valueHex); return false; }
            AacpClientBridge.sendCommand(mac, "anc", modeByte);
            AapState.forMac(mac).setAncMode(modeByte); // Fix 2: optimistic echo; broker reconciles on next push
            return true;
        }
        public String readMode(BluetoothAdapter a, String mac, DeviceDef def, DeviceDef.Func f) {
            if (f == null) return null;
            Integer mode = AapState.forMac(mac).getAncMode();
            if (mode == null) return null;
            return f.valueMap.get(String.format("%02x", mode & 0xff));
        }
        public boolean applyToggle(BluetoothAdapter a, String mac, DeviceDef def, DeviceDef.Func f, boolean on) {
            // Fix 3: only conversational_awareness is wired; reject any other toggle id (mirrors old AacpEngine guard)
            if (f == null || !"conversational_awareness".equals(f.id)) {
                android.util.Log.w(DeviceDef.TAG, "AACP applyToggle: unsupported toggle " + (f != null ? f.id : "null"));
                return false;
            }
            AacpClientBridge.sendCommand(mac, "ca", on ? 1 : 0);
            AapState.forMac(mac).setCaEnabled(on); // Fix 2: optimistic echo; broker reconciles
            return true;
        }
        public Boolean readToggle(BluetoothAdapter a, String mac, DeviceDef def, DeviceDef.Func f) {
            return AapState.forMac(mac).getCaEnabled();
        }
        @Override public String readInfo(BluetoothAdapter a, String mac, DeviceDef d, DeviceDef.Func f) {
            if (f == null) return null;
            if ("battery".equals(f.id)) return AapState.forMac(mac).batterySummary();
            if ("ear_detection".equals(f.id)) return AapState.forMac(mac).earSummary();
            return null;
        }
        @Override public boolean applyLevel(BluetoothAdapter a, String mac, DeviceDef def, DeviceDef.Func f, int value) {
            if (f == null || f.featureByte < 0) return false;
            int v = Math.max(f.min, Math.min(f.max, value));
            AacpClientBridge.sendFeature(mac, f.featureByte, v);
            // Task 2 spike: 0x2E is NOT echoed on write (buds only report it at bring-up / on
            // physical change), so the optimistic echo here is load-bearing, exactly like ANC/CA.
            if ("adaptive_strength".equals(f.id)) AapState.forMac(mac).setAdaptiveStrength(v);
            return true;
        }
        @Override public Integer readLevel(BluetoothAdapter a, String mac, DeviceDef def, DeviceDef.Func f) {
            if (f == null) return null;
            if ("adaptive_strength".equals(f.id)) return AapState.forMac(mac).getAdaptiveStrength();
            return null;
        }
        @Override public void registerListener(String mac, String key, Runnable onChange) {
            AacpEngine.registerListener(mac, key, onChange);
        }
        @Override public void unregisterListener(String mac, String key) {
            AacpEngine.unregisterListener(mac, key);
        }
    };

    /** @return the engine for a transport, or null if this app build can't drive it. */
    static ControlEngine forTransport(String transport) {
        if ("rfcomm".equals(transport)) return RFCOMM;
        if ("aacp".equals(transport)) return AACP;
        return null;
    }

    /**
     * Resolve the engine for a specific function (its channel's transport). The single entry point
     * the provider + in-app screen should use, so control routing stays device-agnostic instead of
     * hard-coding one engine.
     */
    static ControlEngine forFunc(DeviceDef.Func f) {
        return f == null ? null : forTransport(f.transport);
    }
}
