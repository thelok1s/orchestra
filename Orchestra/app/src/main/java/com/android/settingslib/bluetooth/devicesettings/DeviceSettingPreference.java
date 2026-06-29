package com.android.settingslib.bluetooth.devicesettings;

import android.os.Parcel;

/**
 * Base for the device-settings "preference" hierarchy. Wire-compatible with AOSP's
 * DeviceSettingPreference: writeToParcel writes a single int type tag. Subclasses call
 * super(type) and the DeviceSetting CREATOR reads this tag to dispatch.
 *   type tags (from decompiled ezp / DeviceSetting CREATOR): 1=ActionSwitch, 2=MultiToggle.
 */
public class DeviceSettingPreference {
    public static final int TYPE_UNKNOWN = 0;
    public static final int TYPE_ACTION_SWITCH = 1;
    public static final int TYPE_MULTI_TOGGLE = 2;

    private final int type;

    protected DeviceSettingPreference(int type) {
        this.type = type;
    }

    public int getType() {
        return type;
    }

    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(type);
    }
}
