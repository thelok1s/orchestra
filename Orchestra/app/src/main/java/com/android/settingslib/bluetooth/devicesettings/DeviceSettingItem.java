package com.android.settingslib.bluetooth.devicesettings;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * One entry in a DeviceSettingsConfig. Tells the system where to bind the setting provider
 * for this id (package/class/action). Wire (from decompiled DeviceSettingItem.writeToParcel):
 *   int settingId, String packageName, String className, String intentAction,
 *   boolean highlighted, String preferenceKey, Bundle processedExtras
 * where processedExtras = extras (+ "groupIndex" int if set) + "isOptional" boolean.
 */
public class DeviceSettingItem implements Parcelable {
    private final int settingId;
    private final String packageName;
    private final String className;
    private final String intentAction;
    private final String preferenceKey;
    private final boolean highlighted;
    private final Integer groupIndex;
    private final boolean isOptional;
    private final Bundle processedExtras;

    public DeviceSettingItem(int settingId, String packageName, String className,
                             String intentAction, String preferenceKey, boolean highlighted,
                             Integer groupIndex, boolean isOptional, Bundle extras) {
        this.settingId = settingId;
        this.packageName = packageName;
        this.className = className;
        this.intentAction = intentAction;
        this.preferenceKey = preferenceKey;
        this.highlighted = highlighted;
        this.groupIndex = groupIndex;
        this.isOptional = isOptional;
        Bundle processed = new Bundle(extras != null ? extras : new Bundle());
        if (groupIndex != null) {
            processed.putInt("groupIndex", groupIndex);
        }
        processed.putBoolean("isOptional", isOptional);
        this.processedExtras = processed;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(settingId);
        parcel.writeString(packageName);
        parcel.writeString(className);
        parcel.writeString(intentAction);
        parcel.writeBoolean(highlighted);
        parcel.writeString(preferenceKey);
        parcel.writeBundle(processedExtras);
    }

    public static final Parcelable.Creator<DeviceSettingItem> CREATOR =
            new Parcelable.Creator<DeviceSettingItem>() {
                @Override
                public DeviceSettingItem createFromParcel(Parcel in) {
                    int settingId = in.readInt();
                    String pkg = in.readString();
                    String cls = in.readString();
                    String action = in.readString();
                    boolean highlighted = in.readBoolean();
                    String prefKey = in.readString();
                    Bundle extras = in.readBundle(Bundle.class.getClassLoader());
                    if (extras == null) extras = new Bundle();
                    int gi = extras.getInt("groupIndex", -1);
                    return new DeviceSettingItem(settingId, pkg, cls, action, prefKey, highlighted,
                            gi == -1 ? null : gi, extras.getBoolean("isOptional", false), extras);
                }

                @Override
                public DeviceSettingItem[] newArray(int size) {
                    return new DeviceSettingItem[size];
                }
            };
}
