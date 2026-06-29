package com.android.settingslib.bluetooth.devicesettings;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/** Returned by the setting provider's getServiceStatus. Wire: boolean enabled, Bundle. */
public class DeviceSettingsProviderServiceStatus implements Parcelable {
    private final boolean enabled;
    private final Bundle extras;

    public DeviceSettingsProviderServiceStatus(boolean enabled, Bundle extras) {
        this.enabled = enabled;
        this.extras = extras;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeBoolean(enabled);
        parcel.writeBundle(extras);
    }

    public static final Parcelable.Creator<DeviceSettingsProviderServiceStatus> CREATOR =
            new Parcelable.Creator<DeviceSettingsProviderServiceStatus>() {
                @Override
                public DeviceSettingsProviderServiceStatus createFromParcel(Parcel in) {
                    return new DeviceSettingsProviderServiceStatus(
                            in.readBoolean(), in.readBundle(Bundle.class.getClassLoader()));
                }

                @Override
                public DeviceSettingsProviderServiceStatus[] newArray(int size) {
                    return new DeviceSettingsProviderServiceStatus[size];
                }
            };
}
