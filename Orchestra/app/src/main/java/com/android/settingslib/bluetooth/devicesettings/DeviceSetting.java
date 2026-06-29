package com.android.settingslib.bluetooth.devicesettings;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * One setting we push to the system (id + preference). Wire:
 *   int id, Bundle extras, then preference.writeToParcel (which writes the type tag first).
 * Matches decompiled DeviceSetting.writeToParcel + ezp case 5 CREATOR dispatch.
 */
public class DeviceSetting implements Parcelable {
    private final int settingId;
    private final DeviceSettingPreference preference;
    private final Bundle extras;

    public DeviceSetting(int settingId, DeviceSettingPreference preference, Bundle extras) {
        this.settingId = settingId;
        this.preference = preference;
        this.extras = extras;
    }

    public int getSettingId() {
        return settingId;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(settingId);
        parcel.writeBundle(extras);
        preference.writeToParcel(parcel, flags);
    }

    public static final Parcelable.Creator<DeviceSetting> CREATOR = new Parcelable.Creator<DeviceSetting>() {
        @Override
        public DeviceSetting createFromParcel(Parcel in) {
            int id = in.readInt();
            Bundle extras = in.readBundle(Bundle.class.getClassLoader());
            int type = in.readInt();
            DeviceSettingPreference pref;
            if (type == DeviceSettingPreference.TYPE_MULTI_TOGGLE) {
                pref = MultiTogglePreference.readFromParcel(in);
            } else {
                pref = new DeviceSettingPreference(type);
            }
            return new DeviceSetting(id, pref, extras);
        }

        @Override
        public DeviceSetting[] newArray(int size) {
            return new DeviceSetting[size];
        }
    };
}
