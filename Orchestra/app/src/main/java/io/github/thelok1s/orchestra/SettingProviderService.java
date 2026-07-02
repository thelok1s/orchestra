package io.github.thelok1s.orchestra;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import com.android.settingslib.bluetooth.devicesettings.ActionSwitchPreference;
import com.android.settingslib.bluetooth.devicesettings.ActionSwitchPreferenceState;
import com.android.settingslib.bluetooth.devicesettings.DeviceInfo;
import com.android.settingslib.bluetooth.devicesettings.DeviceSetting;
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingAction;
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingPreference;
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingPreferenceState;
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingState;
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingsProviderServiceStatus;
import com.android.settingslib.bluetooth.devicesettings.MultiTogglePreference;
import com.android.settingslib.bluetooth.devicesettings.MultiTogglePreferenceState;
import com.android.settingslib.bluetooth.devicesettings.ToggleInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * IDeviceSettingsProviderService. Bound per DeviceSettingItem the config returned.
 *   code 1 = getServiceStatus()  (two-way)
 *   code 2 = registerDeviceSettingsListener(DeviceInfo, listener)  (oneway)
 *   code 3 = unregisterDeviceSettingsListener(DeviceInfo, listener) (oneway)
 *   code 4 = updateDeviceSettings(DeviceInfo, DeviceSettingState)   (oneway)
 * RFCOMM I/O runs off the binder thread on a single-thread executor (one control channel).
 */
public class SettingProviderService extends Service {
    private final ConcurrentHashMap<String, IBinder> listeners = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private final IBinder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(Binders.DESC_PROVIDER);
                return true;
            }
            switch (code) {
                case 1: { // getServiceStatus
                    data.enforceInterface(Binders.DESC_PROVIDER);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeTypedObject(
                                new DeviceSettingsProviderServiceStatus(true, new Bundle()), 0);
                    }
                    return true;
                }
                case 2: { // registerDeviceSettingsListener
                    data.enforceInterface(Binders.DESC_PROVIDER);
                    DeviceInfo info = data.readTypedObject(DeviceInfo.CREATOR);
                    IBinder listener = data.readStrongBinder();
                    onRegister(info, listener);
                    return true;
                }
                case 3: { // unregisterDeviceSettingsListener
                    data.enforceInterface(Binders.DESC_PROVIDER);
                    DeviceInfo info = data.readTypedObject(DeviceInfo.CREATOR);
                    data.readStrongBinder();
                    if (info != null && info.getAddress() != null) {
                        String addr = info.getAddress().toUpperCase();
                        listeners.remove(addr);
                        unregisterEngineListener(addr);
                    }
                    return true;
                }
                case 4: { // updateDeviceSettings
                    data.enforceInterface(Binders.DESC_PROVIDER);
                    DeviceInfo info = data.readTypedObject(DeviceInfo.CREATOR);
                    DeviceSettingState state = data.readTypedObject(DeviceSettingState.CREATOR);
                    onUpdate(info, state);
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    };

    // Last pushed toggle index per device, keyed by settingId. Lets optimistic + full-list pushes
    // stay consistent (we always push EVERY function's setting, in case the listener replaces its
    // whole set rather than merging by id) without re-reading unchanged functions over RFCOMM.
    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, Integer>> stateCache =
            new ConcurrentHashMap<>();

    private ConcurrentHashMap<Integer, Integer> cacheFor(String address) {
        return stateCache.computeIfAbsent(address, a -> new ConcurrentHashMap<>());
    }

    private void onRegister(DeviceInfo info, IBinder listener) {
        if (info == null || info.getAddress() == null || listener == null) return;
        final String address = info.getAddress().toUpperCase();
        listeners.put(address, listener);
        Log.i(DeviceDef.TAG, "register " + address);
        // Live-push: when the buds send a notification (stem press, pod in/out, battery, CA),
        // re-read + push the full list so the page updates without user interaction.
        for (DeviceDef.Func f : defInjectedSafe(address)) {
            ControlEngine engine = ControlEngine.forTransport(f.transport);
            if (engine != null) {
                engine.registerListener(address, "provider", () -> io.execute(() -> {
                    IBinder l = listeners.get(address);
                    if (l != null) readAllAndPush(address, l);
                }));
                break; // one transport per device today
            }
        }
        io.execute(() -> readAllAndPush(address, listener));
    }

    private List<DeviceDef.Func> defInjectedSafe(String address) {
        DeviceDef def = DeviceDef.forAddress(address);
        return def != null ? def.injectedFuncs(address) : new ArrayList<>();
    }

    /** Unconditionally drop the engine listener for this device across all transports. Safe to call
     *  even if none was registered (RFCOMM is a no-op); does not depend on the device still resolving. */
    private void unregisterEngineListener(String address) {
        for (String transport : new String[]{"aacp", "rfcomm"}) {
            ControlEngine engine = ControlEngine.forTransport(transport);
            if (engine != null) engine.unregisterListener(address, "provider");
        }
    }

    private void onUpdate(DeviceInfo info, DeviceSettingState state) {
        if (info == null || info.getAddress() == null || state == null) return;
        final String address = info.getAddress().toUpperCase();
        final int settingId = state.getSettingId();
        final DeviceSettingPreferenceState ps = state.getPreferenceState();
        final DeviceDef def = DeviceDef.forAddress(address);
        if (def == null) return;
        final DeviceDef.Func f = def.funcBySettingId(settingId);
        if (f == null) { Log.w(DeviceDef.TAG, "update: no func for settingId " + settingId); return; }

        // Map the incoming state to our uniform cache index (multitoggle: option index; toggle: 0/1).
        final int chosenIndex;
        if (ps instanceof MultiTogglePreferenceState && f.isMultitoggle()) {
            chosenIndex = ((MultiTogglePreferenceState) ps).getState();
            if (chosenIndex < 0 || chosenIndex >= f.options.size()) {
                Log.w(DeviceDef.TAG, "update: bad index " + chosenIndex + " for setting " + settingId);
                return;
            }
        } else if (ps instanceof ActionSwitchPreferenceState && f.isToggle()) {
            chosenIndex = ((ActionSwitchPreferenceState) ps).getChecked() ? 1 : 0;
        } else {
            Log.w(DeviceDef.TAG, "update: state/func type mismatch for " + f.id);
            return;
        }

        // OPTIMISTIC UI: update cache + push the full list IMMEDIATELY (before the RFCOMM round-trip)
        // so the control re-enables/reflects without the ~1s set+read latency. The confirmed
        // readback below reconciles if the device disagrees.
        cacheFor(address).put(settingId, chosenIndex);
        IBinder l = listeners.get(address);
        if (l != null) pushFromCache(def, address, l);

        // LOCAL behavior toggles (auto_pause, ca_duck) are NOT AAP commands: persist the enable to
        // DeviceStore and push it to the SystemUI broker's cache. They must never reach
        // ControlEngine.applyToggle/readToggle (the AACP impl rejects any toggle id other than
        // conversational_awareness).
        if (isLocalBehavior(f)) {
            boolean on = chosenIndex == 1;
            DeviceStore.setBehaviorEnabled(address, f.id, on);
            AacpClientBridge.sendCommand(address, localBehaviorCmdOp(f.id), on ? 1 : 0);
            Log.i(DeviceDef.TAG, "update " + address + " local behavior " + f.id + " -> " + on);
            return;
        }

        io.execute(() -> {
            BluetoothAdapter adapter = adapter();
            if (adapter == null) return;
            ControlEngine engine = ControlEngine.forTransport(f.transport);
            if (engine == null) { Log.w(DeviceDef.TAG, "no engine for transport " + f.transport); return; }
            int idx;
            if (f.isToggle()) {
                boolean on = chosenIndex == 1;
                Log.i(DeviceDef.TAG, "update " + address + " toggle " + f.id + " -> " + on);
                engine.applyToggle(adapter, address, def, f, on);
                Boolean confirmed = engine.readToggle(adapter, address, def, f);
                idx = confirmed != null ? (confirmed ? 1 : 0) : chosenIndex;
            } else {
                String optId = f.options.get(chosenIndex).id;
                Log.i(DeviceDef.TAG, "update " + address + " setting " + settingId + " -> " + optId
                        + " (idx " + chosenIndex + ")");
                engine.applyMode(adapter, address, def, f, optId);
                String confirmed = engine.readMode(adapter, address, def, f);
                idx = confirmed != null ? f.indexOfOption(confirmed) : chosenIndex;
                if (idx < 0) idx = chosenIndex;
            }
            cacheFor(address).put(settingId, idx);
            IBinder listener = listeners.get(address);
            if (listener != null) pushFromCache(def, address, listener);
        });
    }

    /** Read every function's current mode via RFCOMM, seed the cache, and push the full list. */
    private void readAllAndPush(String address, IBinder listener) {
        DeviceDef def = DeviceDef.forAddress(address);
        if (def == null) return;
        List<DeviceDef.Func> injected = def.injectedFuncs(address);
        if (injected.isEmpty()) return;
        BluetoothAdapter adapter = adapter();
        if (adapter == null) return;
        ConcurrentHashMap<Integer, Integer> cache = cacheFor(address);
        for (DeviceDef.Func f : injected) {
            if (f.isInfoRow()) continue; // info rows carry no cached index; summary read at push
            // LOCAL behavior toggles (no AAP command): read the persisted enable straight from
            // DeviceStore instead of engine.readToggle, which would reject them (only
            // conversational_awareness is a real AACP toggle) and always fall back to 0.
            if (isLocalBehavior(f)) {
                cache.put(f.settingId, DeviceStore.behaviorEnabled(address, f.id) ? 1 : 0);
                continue;
            }
            ControlEngine engine = ControlEngine.forTransport(f.transport);
            if (engine == null) continue;
            int idx;
            if (f.isToggle()) {
                Boolean on = engine.readToggle(adapter, address, def, f);
                // Unknown (unverified read) -> keep any cached value, else default off.
                idx = on != null ? (on ? 1 : 0)
                        : (cache.containsKey(f.settingId) ? cache.get(f.settingId) : 0);
            } else {
                String cur = engine.readMode(adapter, address, def, f);
                idx = cur != null ? f.indexOfOption(cur) : 0;
                if (idx < 0) idx = 0;
            }
            cache.put(f.settingId, idx);
        }
        pushFromCache(def, address, listener);
    }

    /** Build a DeviceSetting for every injected function from the cached indices (one IPC push). */
    private void pushFromCache(DeviceDef def, String address, IBinder listener) {
        ConcurrentHashMap<Integer, Integer> cache = cacheFor(address);
        List<DeviceSetting> list = new ArrayList<>();
        for (DeviceDef.Func f : def.injectedFuncs(address)) {
            if (f.isInfoRow()) {
                ControlEngine engine = ControlEngine.forTransport(f.transport);
                String summary = engine != null
                        ? engine.readInfo(adapter(), address, def, f) : null;
                list.add(buildSetting(f, 0, summary));
            } else {
                Integer idx = cache.get(f.settingId);
                list.add(buildSetting(f, idx != null ? idx : 0, null));
            }
        }
        try {
            Binders.listenerOnChanged(listener, list);
            Log.i(DeviceDef.TAG, "pushed " + list.size() + " setting(s) to " + address + " " + cache);
        } catch (RemoteException e) {
            Log.w(DeviceDef.TAG, "push failed (listener dead?): " + e);
            listeners.remove(address);
            unregisterEngineListener(address);
        }
    }

    private DeviceSetting buildSetting(DeviceDef.Func f, int stateIndex, String infoSummary) {
        DeviceSettingPreference pref;
        if (f.isInfoRow()) {
            String summary = infoSummary != null ? infoSummary : (f.summary != null ? f.summary : "—");
            pref = new ActionSwitchPreference(
                    f.title, summary, f.iconName != null ? Icons.forName(f.iconName) : null,
                    DeviceSettingAction.EMPTY,
                    /*hasSwitch*/ false, /*checked*/ false,
                    /*isAllowedChangingState*/ false, new Bundle());
        } else if (f.isToggle()) {
            pref = new ActionSwitchPreference(
                    f.title, f.summary, f.iconName != null ? Icons.forName(f.iconName) : null,
                    DeviceSettingAction.EMPTY,
                    /*hasSwitch*/ true, /*checked*/ stateIndex == 1,
                    /*isAllowedChangingState*/ true, new Bundle());
        } else {
            List<ToggleInfo> toggles = new ArrayList<>();
            for (DeviceDef.Opt o : f.options) {
                toggles.add(new ToggleInfo(o.label, Icons.forName(o.icon), new Bundle()));
            }
            pref = new MultiTogglePreference(
                    f.title, toggles, stateIndex, /*isActive*/ true,
                    /*isAllowedChangingState*/ true, new Bundle());
        }
        return new DeviceSetting(f.settingId, pref, new Bundle());
    }

    /**
     * True for LOCAL behavior toggles (auto_pause, ca_duck, ...): manifest-declared {@code "local":
     * true} functions that a privileged process (the SystemUI AAP broker) gates at runtime, rather
     * than a real AAP/RFCOMM command routed through {@link ControlEngine}. Prefers {@link
     * DeviceDef.Func#local} (the manifest flag); falls back to the id allowlist for older/sideloaded
     * manifests that predate the flag.
     */
    private static boolean isLocalBehavior(DeviceDef.Func f) {
        return f.local || "auto_pause".equals(f.id) || "ca_duck".equals(f.id);
    }

    /** Maps a local behavior's function id to the {@code AAP_CMD} op the broker's cache expects. */
    private static String localBehaviorCmdOp(String funcId) {
        return "auto_pause".equals(funcId) ? "autopause" : "caduck";
    }

    private BluetoothAdapter adapter() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter a = bm != null ? bm.getAdapter() : null;
        if (a == null) Log.w(DeviceDef.TAG, "no BluetoothAdapter");
        return a;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
