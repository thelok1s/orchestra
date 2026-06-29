package io.github.thelok1s.orchestra;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import com.android.settingslib.bluetooth.devicesettings.DeviceSetting;
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingsConfig;
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingsConfigServiceStatus;

import java.util.List;

/**
 * Hand-written Binder proxies for the two system callback interfaces we invoke.
 * Built from the exact decompiled AIDL stubs (descriptors, transaction codes, marshalling)
 * so we don't depend on the aidl compiler matching the system's wire format.
 */
final class Binders {
    static final String DESC_PROVIDER =
            "com.android.settingslib.bluetooth.devicesettings.IDeviceSettingsProviderService";
    static final String DESC_CONFIG =
            "com.android.settingslib.bluetooth.devicesettings.IDeviceSettingsConfigProviderService";
    static final String DESC_LISTENER =
            "com.android.settingslib.bluetooth.devicesettings.IDeviceSettingsListener";
    static final String DESC_CALLBACK =
            "com.android.settingslib.bluetooth.devicesettings.IGetDeviceSettingsConfigCallback";

    private Binders() {}

    /** Proxy for IDeviceSettingsListener.onDeviceSettingsChanged(List) — code 1, oneway. */
    static void listenerOnChanged(IBinder remote, List<DeviceSetting> settings) throws RemoteException {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_LISTENER);
            data.writeTypedList(settings);
            remote.transact(1, data, null, IBinder.FLAG_ONEWAY);
        } finally {
            data.recycle();
        }
    }

    /** Proxy for IGetDeviceSettingsConfigCallback.onResult(status, config) — code 1, oneway. */
    static void callbackOnResult(IBinder remote, DeviceSettingsConfigServiceStatus status,
                                 DeviceSettingsConfig config) throws RemoteException {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_CALLBACK);
            data.writeTypedObject(status, 0);
            data.writeTypedObject(config, 0);
            remote.transact(1, data, null, IBinder.FLAG_ONEWAY);
        } finally {
            data.recycle();
        }
    }
}
