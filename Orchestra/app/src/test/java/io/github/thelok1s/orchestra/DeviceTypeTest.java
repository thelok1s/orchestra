package io.github.thelok1s.orchestra;

import static org.junit.Assert.*;
import org.junit.Test;
import org.json.JSONObject;

public class DeviceTypeTest {
    @Test
    public void testExplicitDeviceType() throws Exception {
        String json = "{"
            + "\"schema_version\":4,\"id\":\"test-id\",\"revision\":1,"
            + "\"device_type\":\"speaker\","
            + "\"channels\":{\"c\":{\"transport\":\"aacp\",\"psm\":4097,\"protocol\":{\"framing\":\"aap_v1\"}}},"
            + "\"default_channel\":\"c\""
            + "}";
        DeviceDef d = DeviceDef.parse(new JSONObject(json));
        assertNotNull(d);
        assertEquals("speaker", d.deviceType);
    }

    @Test
    public void testFallbackToTws() throws Exception {
        String json = "{"
            + "\"schema_version\":4,\"id\":\"test-id\",\"revision\":1,"
            + "\"channels\":{\"c\":{\"transport\":\"aacp\",\"psm\":4097,\"protocol\":{\"framing\":\"aap_v1\"}}},"
            + "\"default_channel\":\"c\","
            + "\"functions\":["
            + "  {"
            + "    \"id\":\"battery\","
            + "    \"type\":\"battery\","
            + "    \"battery_layout\":{\"04\":\"left\",\"02\":\"right\"}"
            + "  }"
            + "]"
            + "}";
        DeviceDef d = DeviceDef.parse(new JSONObject(json));
        assertNotNull(d);
        assertEquals("earbuds_2", d.deviceType);
    }

    @Test
    public void testFallbackToDefaultHeadphones() throws Exception {
        String json = "{"
            + "\"schema_version\":4,\"id\":\"test-id\",\"revision\":1,"
            + "\"channels\":{\"c\":{\"transport\":\"aacp\",\"psm\":4097,\"protocol\":{\"framing\":\"aap_v1\"}}},"
            + "\"default_channel\":\"c\""
            + "}";
        DeviceDef d = DeviceDef.parse(new JSONObject(json));
        assertNotNull(d);
        assertEquals("headphones", d.deviceType);
    }

    @Test
    public void testFallbackForShokzId() throws Exception {
        String json = "{"
            + "\"schema_version\":3,\"id\":\"shokz-openswim-pro\",\"revision\":1,"
            + "\"channels\":{\"c\":{\"transport\":\"rfcomm\",\"uuid\":\"00001101-0000-1000-8000-00805f9b34fb\"}},"
            + "\"default_channel\":\"c\""
            + "}";
        DeviceDef d = DeviceDef.parse(new JSONObject(json));
        assertNotNull(d);
        assertEquals("earbuds", d.deviceType);
    }

    @Test
    public void testFallbackForWI600nId() throws Exception {
        String json = "{"
            + "\"schema_version\":3,\"id\":\"sony-wi-c600n\",\"revision\":1,"
            + "\"channels\":{\"c\":{\"transport\":\"rfcomm\",\"uuid\":\"00001101-0000-1000-8000-00805f9b34fb\"}},"
            + "\"default_channel\":\"c\""
            + "}";
        DeviceDef d = DeviceDef.parse(new JSONObject(json));
        assertNotNull(d);
        assertEquals("earbuds", d.deviceType);
    }
}

