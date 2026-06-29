package com.android.settingslib.bluetooth.devicesettings;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/** Options the system passes to the config provider. Wire: Bundle. We read it. */
public class DeviceSettingConfigOptions implements Parcelable {
    private final Bundle options;

    public DeviceSettingConfigOptions(Bundle options) {
        this.options = options;
    }

    public boolean isOptionalItemSupported() {
        return options != null && options.getBoolean("optionalItems", false);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeBundle(options);
    }

    public static final Parcelable.Creator<DeviceSettingConfigOptions> CREATOR =
            new Parcelable.Creator<DeviceSettingConfigOptions>() {
                @Override
                public DeviceSettingConfigOptions createFromParcel(Parcel in) {
                    return new DeviceSettingConfigOptions(in.readBundle(Bundle.class.getClassLoader()));
                }

                @Override
                public DeviceSettingConfigOptions[] newArray(int size) {
                    return new DeviceSettingConfigOptions[size];
                }
            };
}
