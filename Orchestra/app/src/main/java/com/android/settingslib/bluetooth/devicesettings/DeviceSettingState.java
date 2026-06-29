package com.android.settingslib.bluetooth.devicesettings;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * State the system sends in updateDeviceSettings(deviceInfo, state). We read it. Wire:
 *   int id, Bundle extras, then preferenceState (type tag + fields).
 * Matches decompiled DeviceSettingState + ezp case 11 CREATOR dispatch.
 */
public class DeviceSettingState implements Parcelable {
    private final int settingId;
    private final DeviceSettingPreferenceState preferenceState;
    private final Bundle extras;

    public DeviceSettingState(int settingId, DeviceSettingPreferenceState preferenceState, Bundle extras) {
        this.settingId = settingId;
        this.preferenceState = preferenceState;
        this.extras = extras;
    }

    public int getSettingId() {
        return settingId;
    }

    public DeviceSettingPreferenceState getPreferenceState() {
        return preferenceState;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(settingId);
        parcel.writeBundle(extras);
        preferenceState.writeToParcel(parcel, flags);
    }

    public static final Parcelable.Creator<DeviceSettingState> CREATOR =
            new Parcelable.Creator<DeviceSettingState>() {
                @Override
                public DeviceSettingState createFromParcel(Parcel in) {
                    int id = in.readInt();
                    Bundle extras = in.readBundle(Bundle.class.getClassLoader());
                    int type = in.readInt();
                    DeviceSettingPreferenceState state;
                    if (type == DeviceSettingPreferenceState.TYPE_MULTI_TOGGLE) {
                        state = MultiTogglePreferenceState.readFromParcel(in);
                    } else if (type == DeviceSettingPreferenceState.TYPE_ACTION_SWITCH) {
                        state = ActionSwitchPreferenceState.readFromParcel(in);
                    } else {
                        state = new DeviceSettingPreferenceState(type);
                    }
                    return new DeviceSettingState(id, state, extras);
                }

                @Override
                public DeviceSettingState[] newArray(int size) {
                    return new DeviceSettingState[size];
                }
            };
}
