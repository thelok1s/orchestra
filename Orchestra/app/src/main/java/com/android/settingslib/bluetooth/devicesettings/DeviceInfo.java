package com.android.settingslib.bluetooth.devicesettings;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/** {address, extras}. We mostly read it (sent to us). Field order: String address, Bundle. */
public class DeviceInfo implements Parcelable {
    private final String address;
    private final Bundle extras;

    public DeviceInfo(String address, Bundle extras) {
        this.address = address;
        this.extras = extras;
    }

    public String getAddress() {
        return address;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(address);
        parcel.writeBundle(extras);
    }

    public static final Parcelable.Creator<DeviceInfo> CREATOR = new Parcelable.Creator<DeviceInfo>() {
        @Override
        public DeviceInfo createFromParcel(Parcel in) {
            return new DeviceInfo(in.readString(), in.readBundle(Bundle.class.getClassLoader()));
        }

        @Override
        public DeviceInfo[] newArray(int size) {
            return new DeviceInfo[size];
        }
    };
}
