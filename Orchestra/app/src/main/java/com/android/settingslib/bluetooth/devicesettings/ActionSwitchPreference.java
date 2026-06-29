package com.android.settingslib.bluetooth.devicesettings;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A switch (or plain row) control on the About-device page. With {@code hasSwitch=true} and an
 * EMPTY action it renders as a SwitchPreferenceCompat; flipping it sends back an
 * {@link ActionSwitchPreferenceState}. Wire (after the type int written by super, type=1):
 *   String title, String summary, Parcelable icon(Bitmap, nullable), DeviceSettingAction action,
 *   boolean hasSwitch, boolean checked, boolean isAllowedChangingState, Bundle extras.
 * Verified against decompiled ActionSwitchPreference (settings_re).
 */
public class ActionSwitchPreference extends DeviceSettingPreference implements Parcelable {
    private final String title;
    private final String summary;
    private final Bitmap icon;
    private final DeviceSettingAction action;
    private final boolean hasSwitch;
    private final boolean checked;
    private final boolean isAllowedChangingState;
    private final Bundle extras;

    public ActionSwitchPreference(String title, String summary, Bitmap icon,
                                  DeviceSettingAction action, boolean hasSwitch, boolean checked,
                                  boolean isAllowedChangingState, Bundle extras) {
        super(TYPE_ACTION_SWITCH);
        this.title = title;
        this.summary = summary;
        this.icon = icon;
        this.action = action != null ? action : DeviceSettingAction.EMPTY;
        this.hasSwitch = hasSwitch;
        this.checked = checked;
        this.isAllowedChangingState = isAllowedChangingState;
        this.extras = extras;
    }

    public boolean getChecked() { return checked; }
    public boolean hasSwitch() { return hasSwitch; }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        super.writeToParcel(parcel, flags);
        parcel.writeString(title);
        parcel.writeString(summary);
        parcel.writeParcelable(icon, flags);
        action.writeToParcel(parcel, flags);
        parcel.writeBoolean(hasSwitch);
        parcel.writeBoolean(checked);
        parcel.writeBoolean(isAllowedChangingState);
        parcel.writeBundle(extras);
    }

    public static ActionSwitchPreference readFromParcel(Parcel in) {
        String title = in.readString();
        String summary = in.readString();
        Bitmap icon = in.readParcelable(Bitmap.class.getClassLoader());
        DeviceSettingAction action = new DeviceSettingAction(in.readInt());
        return new ActionSwitchPreference(title, summary, icon, action,
                in.readBoolean(), in.readBoolean(), in.readBoolean(),
                in.readBundle(Bundle.class.getClassLoader()));
    }

    // Provided for completeness; DeviceSetting CREATOR dispatches by type.
    public static final Parcelable.Creator<ActionSwitchPreference> CREATOR =
            new Parcelable.Creator<ActionSwitchPreference>() {
                @Override
                public ActionSwitchPreference createFromParcel(Parcel in) {
                    in.readInt(); // consume the type tag
                    return readFromParcel(in);
                }

                @Override
                public ActionSwitchPreference[] newArray(int size) {
                    return new ActionSwitchPreference[size];
                }
            };
}
