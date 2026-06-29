package com.android.settingslib.bluetooth.devicesettings;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * The ANC-style multi-toggle selector. Wire (after the type int written by super):
 *   String title, TypedList&lt;ToggleInfo&gt;, int state, boolean isActive,
 *   boolean isAllowedChangingState, Bundle extras.
 * Verified against decompiled MultiTogglePreference (set_dex).
 */
public class MultiTogglePreference extends DeviceSettingPreference implements Parcelable {
    private final String title;
    private final List<ToggleInfo> toggleInfos;
    private final int state;            // index of the active toggle
    private final boolean isActive;
    private final boolean isAllowedChangingState;
    private final Bundle extras;

    public MultiTogglePreference(String title, List<ToggleInfo> toggleInfos, int state,
                                 boolean isActive, boolean isAllowedChangingState, Bundle extras) {
        super(TYPE_MULTI_TOGGLE);
        this.title = title;
        this.toggleInfos = toggleInfos;
        this.state = state;
        this.isActive = isActive;
        this.isAllowedChangingState = isAllowedChangingState;
        this.extras = extras;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        super.writeToParcel(parcel, flags);
        parcel.writeString(title);
        parcel.writeTypedList(toggleInfos, flags);
        parcel.writeInt(state);
        parcel.writeBoolean(isActive);
        parcel.writeBoolean(isAllowedChangingState);
        parcel.writeBundle(extras);
    }

    public static MultiTogglePreference readFromParcel(Parcel in) {
        String title = in.readString();
        ArrayList<ToggleInfo> toggles = new ArrayList<>();
        in.readTypedList(toggles, ToggleInfo.CREATOR);
        return new MultiTogglePreference(title, toggles, in.readInt(),
                in.readBoolean(), in.readBoolean(), in.readBundle(Bundle.class.getClassLoader()));
    }

    // Provided for completeness; DeviceSetting CREATOR dispatches by type, so this is rarely used.
    public static final Parcelable.Creator<MultiTogglePreference> CREATOR =
            new Parcelable.Creator<MultiTogglePreference>() {
                @Override
                public MultiTogglePreference createFromParcel(Parcel in) {
                    in.readInt(); // consume the type tag
                    return readFromParcel(in);
                }

                @Override
                public MultiTogglePreference[] newArray(int size) {
                    return new MultiTogglePreference[size];
                }
            };
}
