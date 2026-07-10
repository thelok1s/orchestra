package io.github.thelok1s.orchestra;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * device_type resolution: an explicit valid value wins (real types + legacy aliases); otherwise a
 * per-bud (TWS) battery layout infers tws; otherwise headphones. Drives the Devices-list icon.
 */
public class DeviceTypeTest {

    private static JSONObject base(String id) throws Exception {
        return new JSONObject().put("schema_version", 3).put("revision", 1)
                .put("id", id).put("name", id)
                .put("channels", new JSONObject().put("c",
                        new JSONObject().put("transport", "rfcomm")))
                .put("default_channel", "c")
                .put("functions", new JSONArray());
    }

    @Test public void explicitRealTypeWins() throws Exception {
        assertEquals("neckband", DeviceDef.parse(base("shokz").put("device_type", "neckband")).deviceType);
        assertEquals("tws", DeviceDef.parse(base("buds").put("device_type", "tws")).deviceType);
        assertEquals("speaker", DeviceDef.parse(base("jbl").put("device_type", "speaker")).deviceType);
        assertEquals("headset", DeviceDef.parse(base("g").put("device_type", "headset")).deviceType);
    }

    @Test public void legacyAliasesStillAccepted() throws Exception {
        // Older manifests / cached OTA payloads may still carry the Jetpack-icon names.
        assertEquals("earbuds", DeviceDef.parse(base("x").put("device_type", "earbuds")).deviceType);
        assertEquals("earbuds_2", DeviceDef.parse(base("y").put("device_type", "earbuds_2")).deviceType);
    }

    @Test public void unknownTypeFallsThroughToInference() throws Exception {
        // Garbage device_type is ignored; no TWS battery -> headphones.
        assertEquals("headphones", DeviceDef.parse(base("x").put("device_type", "bogus")).deviceType);
    }

    @Test public void twsBatteryInfersTws() throws Exception {
        JSONObject battery = new JSONObject().put("id", "battery").put("type", "battery")
                .put("title", "Battery")
                .put("battery_layout", new JSONArray().put("left").put("right").put("case"));
        JSONObject m = base("buds");
        m.getJSONArray("functions").put(battery);
        assertEquals("tws", DeviceDef.parse(m).deviceType);
    }

    @Test public void singleBatteryDefaultsHeadphones() throws Exception {
        JSONObject battery = new JSONObject().put("id", "battery").put("type", "battery")
                .put("title", "Battery")
                .put("battery_layout", new JSONArray().put("single"));
        JSONObject m = base("overear");
        m.getJSONArray("functions").put(battery);
        assertEquals("headphones", DeviceDef.parse(m).deviceType);
    }

    @Test public void noTypeNoBatteryDefaultsHeadphones() throws Exception {
        assertEquals("headphones", DeviceDef.parse(base("plain")).deviceType);
    }
}
