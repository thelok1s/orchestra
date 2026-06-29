package com.android.settingslib.bluetooth.devicesettings;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * State the system sends in updateDeviceSettings when the user flips a switch. Wire (after the
 * type int written by super, type=1): boolean checked, Bundle extras.
 * Verified against decompiled ActionSwitchPreferenceState (settings_re).
 */
public class ActionSwitchPreferenceState extends DeviceSettingPreferenceState implements Parcelable {
    private final boolean checked;
    private final Bundle extras;

    public ActionSwitchPreferenceState(boolean checked, Bundle extras) {
        super(TYPE_ACTION_SWITCH);
        this.checked = checked;
        this.extras = extras;
    }

    public boolean getChecked() { return checked; }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        super.writeToParcel(parcel, flags);
        parcel.writeBoolean(checked);
        parcel.writeBundle(extras);
    }

    public static ActionSwitchPreferenceState readFromParcel(Parcel in) {
        return new ActionSwitchPreferenceState(in.readBoolean(),
                in.readBundle(Bundle.class.getClassLoader()));
    }

    public static final Parcelable.Creator<ActionSwitchPreferenceState> CREATOR =
            new Parcelable.Creator<ActionSwitchPreferenceState>() {
                @Override
                public ActionSwitchPreferenceState createFromParcel(Parcel in) {
                    in.readInt(); // consume the type tag
                    return readFromParcel(in);
                }

                @Override
                public ActionSwitchPreferenceState[] newArray(int size) {
                    return new ActionSwitchPreferenceState[size];
                }
            };
}
