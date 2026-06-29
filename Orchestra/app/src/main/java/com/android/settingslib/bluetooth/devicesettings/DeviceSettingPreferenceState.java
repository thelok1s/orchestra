package com.android.settingslib.bluetooth.devicesettings;

import android.os.Parcel;

/**
 * Base for preference *state* objects (sent by the system in updateDeviceSettings).
 * Wire-compatible with AOSP's DeviceSettingPreferenceState: a single int type tag.
 *   type tags (from decompiled ezp DeviceSettingState CREATOR): 1=ActionSwitchState, 2=MultiToggleState.
 */
public class DeviceSettingPreferenceState {
    public static final int TYPE_ACTION_SWITCH = 1;
    public static final int TYPE_MULTI_TOGGLE = 2;

    private final int type;

    protected DeviceSettingPreferenceState(int type) {
        this.type = type;
    }

    public int getType() {
        return type;
    }

    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(type);
    }
}
