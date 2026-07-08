package io.github.thelok1s.orchestra;

import android.bluetooth.BluetoothDevice;
import android.util.Log;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes BluetoothDevice metadata key 25 (METADATA_FAST_PAIR_CUSTOMIZED_FIELDS), an XML-tagged
 * string parsed by the system as &lt;TAG&gt;value&lt;/TAG&gt;. We set the three config-provider tags
 * and preserve any others (BATT, HEARABLE_CONTROL_SLICE_WITH_WIDTH, ...). get/setMetadata are
 * hidden SystemApi (require BLUETOOTH_PRIVILEGED), so we call them via reflection.
 */
final class Metadata {
    static final int KEY_FAST_PAIR_CUSTOMIZED_FIELDS = 25;

    static final String TAG_PACKAGE = "DEVICE_SETTINGS_CONFIG_PACKAGE_NAME";
    static final String TAG_CLASS = "DEVICE_SETTINGS_CONFIG_CLASS";
    static final String TAG_ACTION = "DEVICE_SETTINGS_CONFIG_ACTION";
    static final String TAG_HEARABLE_SLICE = "HEARABLE_CONTROL_SLICE_WITH_WIDTH";

    static final String VAL_PACKAGE = "io.github.thelok1s.orchestra";
    static final String VAL_CLASS = "io.github.thelok1s.orchestra.ConfigProviderService";
    static final String VAL_ACTION = "io.github.thelok1s.orchestra.BIND_DEVICE_SETTINGS_CONFIG_PROVIDER";

    private Metadata() {}

    /** Set/replace our device-settings config tags in key 25, preserving any others. */
    static boolean assertConfigTags(BluetoothDevice device, String deviceId) {
        try {
            String existing = readKey25(device);
            if (existing == null) existing = "";
            String updated = upsert(existing, TAG_PACKAGE, VAL_PACKAGE);
            updated = upsert(updated, TAG_CLASS, VAL_CLASS);
            updated = upsert(updated, TAG_ACTION, VAL_ACTION);
            // The volume-panel ANC tile is device-settings driven (availability forced via the
            // SystemUI hook), so the HEARABLE_CONTROL_SLICE_WITH_WIDTH slice is not used; strip any
            // stale tag a previous build wrote.
            updated = updated.replaceAll(
                    "<" + Pattern.quote(TAG_HEARABLE_SLICE) + ">.*?</" + Pattern.quote(TAG_HEARABLE_SLICE) + ">", "");
            if (updated.equals(existing)) {
                return true; // already correct
            }
            boolean ok = writeKey25(device, updated);
            Log.i(DeviceDef.TAG, "metadata key25 write " + (ok ? "ok" : "FAILED")
                    + " for " + device.getAddress());
            return ok;
        } catch (Throwable t) {
            Log.w(DeviceDef.TAG, "assertConfigTags failed: " + t);
            return false;
        }
    }

    private static String upsert(String src, String tag, String value) {
        String stripped = src.replaceAll("<" + Pattern.quote(tag) + ">.*?</" + Pattern.quote(tag) + ">", "");
        return stripped + "<" + tag + ">" + value + "</" + tag + ">";
    }

    static String getTag(String src, String tag) {
        if (src == null) return null;
        Matcher m = Pattern.compile("<" + Pattern.quote(tag) + ">(.*?)</" + Pattern.quote(tag) + ">").matcher(src);
        return m.find() ? m.group(1) : null;
    }

    private static String readKey25(BluetoothDevice device) {
        try {
            Method get = (Method) org.lsposed.hiddenapibypass.HiddenApiBypass.getDeclaredMethod(BluetoothDevice.class, "getMetadata", int.class);
            Object res = get.invoke(device, KEY_FAST_PAIR_CUSTOMIZED_FIELDS);
            if (res instanceof byte[]) {
                return new String((byte[]) res, StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            Log.w(DeviceDef.TAG, "getMetadata failed: " + c.getClass().getName() + ": " + c.getMessage(), c);
        }
        return null;
    }

    private static boolean writeKey25(BluetoothDevice device, String value) {
        try {
            Method set = (Method) org.lsposed.hiddenapibypass.HiddenApiBypass.getDeclaredMethod(BluetoothDevice.class, "setMetadata", int.class, byte[].class);
            Object res = set.invoke(device, KEY_FAST_PAIR_CUSTOMIZED_FIELDS,
                    value.getBytes(StandardCharsets.UTF_8));
            return !(res instanceof Boolean) || (Boolean) res;
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            Log.w(DeviceDef.TAG, "setMetadata failed: " + c.getClass().getName() + ": " + c.getMessage(), c);
            return false;
        }
    }
}
