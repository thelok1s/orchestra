package io.github.thelok1s.orchestra;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * device_type resolution: an explicit valid value wins; otherwise a per-bud (TWS) battery layout
 * infers earbuds_2; otherwise headphones. Drives the Devices-list icon.
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

    @Test public void explicitTypeWins() throws Exception {
        assertEquals("earbuds", DeviceDef.parse(base("shokz").put("device_type", "earbuds")).deviceType);
        assertEquals("speaker", DeviceDef.parse(base("jbl").put("device_type", "speaker")).deviceType);
        assertEquals("headset_mic", DeviceDef.parse(base("g").put("device_type", "headset_mic")).deviceType);
    }

    @Test public void unknownTypeFallsThroughToInference() throws Exception {
        // Garbage device_type is ignored; no TWS battery -> headphones.
        assertEquals("headphones", DeviceDef.parse(base("x").put("device_type", "bogus")).deviceType);
    }

    @Test public void twsBatteryInfersEarbuds2() throws Exception {
        JSONObject battery = new JSONObject().put("id", "battery").put("type", "battery")
                .put("title", "Battery")
                .put("battery_layout", new JSONArray().put("left").put("right").put("case"));
        JSONObject m = base("tws");
        m.getJSONArray("functions").put(battery);
        assertEquals("earbuds_2", DeviceDef.parse(m).deviceType);
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
