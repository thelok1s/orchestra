package com.android.settingslib.bluetooth.devicesettings;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/** Returned by the config provider callback. Wire: boolean success, Bundle. */
public class DeviceSettingsConfigServiceStatus implements Parcelable {
    private final boolean success;
    private final Bundle extras;

    public DeviceSettingsConfigServiceStatus(boolean success, Bundle extras) {
        this.success = success;
        this.extras = extras;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeBoolean(success);
        parcel.writeBundle(extras);
    }

    public static final Parcelable.Creator<DeviceSettingsConfigServiceStatus> CREATOR =
            new Parcelable.Creator<DeviceSettingsConfigServiceStatus>() {
                @Override
                public DeviceSettingsConfigServiceStatus createFromParcel(Parcel in) {
                    return new DeviceSettingsConfigServiceStatus(
                            in.readBoolean(), in.readBundle(Bundle.class.getClassLoader()));
                }

                @Override
                public DeviceSettingsConfigServiceStatus[] newArray(int size) {
                    return new DeviceSettingsConfigServiceStatus[size];
                }
            };
}
