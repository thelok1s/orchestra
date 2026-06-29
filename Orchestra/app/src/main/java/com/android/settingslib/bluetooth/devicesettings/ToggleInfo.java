package com.android.settingslib.bluetooth.devicesettings;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/** One toggle option (label + mandatory bitmap icon). Wire: String label, Parcelable bitmap, Bundle. */
public class ToggleInfo implements Parcelable {
    private final String label;
    private final Bitmap icon;
    private final Bundle extras;

    public ToggleInfo(String label, Bitmap icon, Bundle extras) {
        this.label = label;
        this.icon = icon;
        this.extras = extras;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(label);
        parcel.writeParcelable(icon, flags);
        parcel.writeBundle(extras);
    }

    public static final Parcelable.Creator<ToggleInfo> CREATOR = new Parcelable.Creator<ToggleInfo>() {
        @Override
        public ToggleInfo createFromParcel(Parcel in) {
            return new ToggleInfo(
                    in.readString(),
                    (Bitmap) in.readParcelable(Bitmap.class.getClassLoader()),
                    in.readBundle(Bundle.class.getClassLoader()));
        }

        @Override
        public ToggleInfo[] newArray(int size) {
            return new ToggleInfo[size];
        }
    };
}
