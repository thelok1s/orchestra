package com.android.settingslib.bluetooth.devicesettings;

import android.os.Parcel;

/**
 * Action attached to an {@link ActionSwitchPreference} (tap target / intent). Wire-compatible with
 * AOSP's DeviceSettingAction: a single int action-type, then per-type fields. We only need the
 * EMPTY action (type 0) — a plain switch with no click action — so we just write the type int.
 *   action types (from decompiled DeviceSettingAction.readFromParcel): 0=empty, 1=intent, 2=pendingIntent.
 */
public class DeviceSettingAction {
    public static final int TYPE_EMPTY = 0;

    /** A no-op action (switch only, no row click target). */
    public static final DeviceSettingAction EMPTY = new DeviceSettingAction(TYPE_EMPTY);

    private final int actionType;

    protected DeviceSettingAction(int actionType) {
        this.actionType = actionType;
    }

    public int getActionType() {
        return actionType;
    }

    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(actionType);
    }
}
