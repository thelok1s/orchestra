package com.android.settingslib.bluetooth.devicesettings;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * State the system sends for a MultiTogglePreference toggle change.
 * Wire (after type int from super): int state, Bundle extras.
 */
public class MultiTogglePreferenceState extends DeviceSettingPreferenceState implements Parcelable {
    private final int state;       // index of the chosen toggle
    private final Bundle extras;

    public MultiTogglePreferenceState(int state, Bundle extras) {
        super(TYPE_MULTI_TOGGLE);
        this.state = state;
        this.extras = extras;
    }

    public int getState() {
        return state;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        super.writeToParcel(parcel, flags);
        parcel.writeInt(state);
        parcel.writeBundle(extras);
    }

    public static MultiTogglePreferenceState readFromParcel(Parcel in) {
        return new MultiTogglePreferenceState(in.readInt(), in.readBundle(Bundle.class.getClassLoader()));
    }

    public static final Parcelable.Creator<MultiTogglePreferenceState> CREATOR =
            new Parcelable.Creator<MultiTogglePreferenceState>() {
                @Override
                public MultiTogglePreferenceState createFromParcel(Parcel in) {
                    in.readInt(); // type tag
                    return readFromParcel(in);
                }

                @Override
                public MultiTogglePreferenceState[] newArray(int size) {
                    return new MultiTogglePreferenceState[size];
                }
            };
}
