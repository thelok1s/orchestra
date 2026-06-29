package com.android.settingslib.bluetooth.devicesettings;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * The config returned to the system. Wire (from decompiled DeviceSettingsConfig.writeToParcel):
 *   TypedList&lt;DeviceSettingItem&gt; mainContentItems,
 *   TypedList&lt;DeviceSettingItem&gt; moreSettingsItems,
 *   Parcelable moreSettingsHelpItem (we pass null),
 *   Bundle processedExtras (= extras, plus "settingGroups" if any — we use none).
 * We never pass settingGroups, so we avoid the DeviceSettingGroup dependency entirely.
 */
public class DeviceSettingsConfig implements Parcelable {
    private final List<DeviceSettingItem> mainContentItems;
    private final List<DeviceSettingItem> moreSettingsItems;
    private final DeviceSettingItem moreSettingsHelpItem;
    private final Bundle processedExtras;

    public DeviceSettingsConfig(List<DeviceSettingItem> mainContentItems,
                                List<DeviceSettingItem> moreSettingsItems,
                                DeviceSettingItem moreSettingsHelpItem,
                                Bundle extras) {
        this.mainContentItems = mainContentItems;
        this.moreSettingsItems = moreSettingsItems;
        this.moreSettingsHelpItem = moreSettingsHelpItem;
        this.processedExtras = new Bundle(extras != null ? extras : new Bundle());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeTypedList(mainContentItems);
        parcel.writeTypedList(moreSettingsItems);
        parcel.writeParcelable(moreSettingsHelpItem, flags);
        parcel.writeBundle(processedExtras);
    }

    public static final Parcelable.Creator<DeviceSettingsConfig> CREATOR =
            new Parcelable.Creator<DeviceSettingsConfig>() {
                @Override
                public DeviceSettingsConfig createFromParcel(Parcel in) {
                    ArrayList<DeviceSettingItem> main = new ArrayList<>();
                    in.readTypedList(main, DeviceSettingItem.CREATOR);
                    ArrayList<DeviceSettingItem> more = new ArrayList<>();
                    in.readTypedList(more, DeviceSettingItem.CREATOR);
                    DeviceSettingItem help = in.readParcelable(DeviceSettingItem.class.getClassLoader());
                    Bundle extras = in.readBundle(Bundle.class.getClassLoader());
                    return new DeviceSettingsConfig(main, more, help, extras);
                }

                @Override
                public DeviceSettingsConfig[] newArray(int size) {
                    return new DeviceSettingsConfig[size];
                }
            };
}
