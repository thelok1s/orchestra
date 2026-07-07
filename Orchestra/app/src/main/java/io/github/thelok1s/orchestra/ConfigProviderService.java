package io.github.thelok1s.orchestra;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import com.android.settingslib.bluetooth.devicesettings.DeviceInfo;
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingConfigOptions;
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingItem;
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingsConfig;
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingsConfigServiceStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * IDeviceSettingsConfigProviderService. The system binds this (via metadata key 25:
 * DEVICE_SETTINGS_CONFIG_PACKAGE_NAME/CLASS/ACTION) and calls:
 *   code 2 = getDeviceSettingsConfigWithOptions(DeviceInfo, callback, DeviceSettingConfigOptions)
 *   code 1 = legacy getDeviceSettingsConfig(DeviceInfo, callback)
 * Both are two-way (caller does readException), result delivered via the callback (oneway).
 */
public class ConfigProviderService extends Service {
    static final String ACTION_SETTING_PROVIDER = "io.github.thelok1s.orchestra.BIND_DEVICE_SETTINGS_PROVIDER";
    static final String SETTING_PROVIDER_CLASS = "io.github.thelok1s.orchestra.SettingProviderService";

    private final IBinder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(Binders.DESC_CONFIG);
                return true;
            }
            if (code == 1 || code == 2) {
                data.enforceInterface(Binders.DESC_CONFIG);
                DeviceInfo deviceInfo = data.readTypedObject(DeviceInfo.CREATOR);
                IBinder callback = data.readStrongBinder();
                if (code == 2) {
                    data.readTypedObject(DeviceSettingConfigOptions.CREATOR); // options (unused for now)
                }
                handle(deviceInfo, callback);
                if (reply != null) reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    private void handle(DeviceInfo deviceInfo, IBinder callback) {
        if (callback == null) return;
        String address = deviceInfo != null ? deviceInfo.getAddress() : null;
        DeviceDef def = DeviceDef.forAddress(address);
        try {
            if (def == null) {
                Log.i(DeviceDef.TAG, "config: no def for " + address + " -> success=false");
                Binders.callbackOnResult(callback,
                        new DeviceSettingsConfigServiceStatus(false, new Bundle()), null);
                return;
            }
            List<DeviceDef.Func> injected = def.injectedFuncs(address);
            DeviceSettingsConfig config = buildConfig(injected);
            Log.i(DeviceDef.TAG, "config: returning " + injected.size() + " app item(s) for " + address);
            Binders.callbackOnResult(callback,
                    new DeviceSettingsConfigServiceStatus(true, new Bundle()), config);
        } catch (Exception e) {
            Log.w(DeviceDef.TAG, "config handle failed: " + e);
        }
    }

    // Built-in preference keys hosted by Settings' BluetoothDetailsConfigurableFragment. A
    // DeviceSettingItem with a NON-empty preferenceKey is a BuiltinItem: the fragment does
    // findPreference(key)/map.remove(key) to place the standard preference created by its
    // controllers (header incl. name+battery, footer). Unknown keys are silently skipped.
    private static final String PK_HEADER = "general_bluetooth_device_header";
    private static final String PK_FOOTER = "device_details_footer";

    private DeviceSettingItem builtin(int settingId, String preferenceKey) {
        return new DeviceSettingItem(settingId, null, null, null, preferenceKey,
                false, null, false, new Bundle());
    }

    // Exact ordered builtin preference keys of the native bluetooth_device_details_fragment
    // (dumped from SettingsGoogle res/xml). BluetoothDetailsConfigurableFragment.constructLayout
    // strips the base screen and re-adds ONLY the keys we list (in order); anything omitted is
    // hidden. So to keep the FULL native page we must list every key with the correct spelling —
    // notably the rich Fast-Pair header is "advanced_bluetooth_device_header" (NOT ..._details_).
    // Unknown/unhosted keys (map.remove → null) are silently skipped. We splice our ANC
    // AppProvidedItem (settingId 1001) right after action_buttons, matching the Pixel-Buds layout.
    private static final String[] NATIVE_KEYS_BEFORE_ANC = {
            "bluetooth_details_banner",
            "general_bluetooth_device_header",
            "advanced_bluetooth_device_header",   // Fast-Pair rich header: image + per-bud battery
            "le_audio_bluetooth_device_header",
            "hearing_aid_pair_other_button",
            "hearing_aid_space_layout",
            "action_buttons",                     // connect/disconnect/forget
    };
    private static final String[] NATIVE_KEYS_AFTER_ANC = {
            "device_stylus",
            "audio_sharing_control",
            "bt_device_slice_category",
            "device_companion_apps",
            "hearing_device_group",
            "spatial_audio_group",
            "bluetooth_profiles",
            "bluetooth_audio_device_type_group",
            "bt_extra_options",
            "bluetooth_related_tools",
            "live_caption",
            "data_sync_group",
            "keyboard_settings",
            "game_controller_settings",
            "device_details_footer",              // MAC address footer
    };

    private DeviceSettingItem appItem(int settingId) {
        return new DeviceSettingItem(settingId, getPackageName(), SETTING_PROVIDER_CLASS,
                ACTION_SETTING_PROVIDER, null /* preferenceKey -> AppProvidedItem */,
                false, null, false, new Bundle());
    }

    private DeviceSettingsConfig buildConfig(List<DeviceDef.Func> injected) {
        List<DeviceSettingItem> main = new ArrayList<>();
        int bid = 1;
        for (String key : NATIVE_KEYS_BEFORE_ANC) main.add(builtin(bid++, key));
        // One AppProvidedItem per injected (implemented + user-enabled + conflict-resolved) function
        // — ANC first via settingId 1001 (also the volume-panel tile), then any extras — spliced
        // after the buttons. Catalog-only capabilities (sliders/lists/pending switches) are omitted.
        for (DeviceDef.Func f : injected) main.add(appItem(f.settingId));
        for (String key : NATIVE_KEYS_AFTER_ANC) main.add(builtin(bid++, key));
        return new DeviceSettingsConfig(main, new ArrayList<>(), null, new Bundle());
    }

    @Override
    public IBinder onBind(Intent intent) {
        // DIAGNOSTIC: which process binds us (system uid 1000 = SystemUI/Settings device-settings fw)
        android.util.Log.i(DeviceDef.TAG, "ConfigProvider onBind by uid=" + android.os.Binder.getCallingUid()
                + " intent=" + (intent != null ? intent.getAction() : "null"));
        return binder;
    }
}
