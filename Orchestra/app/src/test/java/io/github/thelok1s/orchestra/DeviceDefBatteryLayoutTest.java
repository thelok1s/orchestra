package io.github.thelok1s.orchestra;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class DeviceDefBatteryLayoutTest {
    private static final String JSON = "{"
        + "\"schema_version\":4,\"id\":\"x\",\"revision\":1,"
        + "\"match\":{\"name_regex\":\"x\"},"
        + "\"channels\":{\"c\":{\"transport\":\"aacp\",\"psm\":4097,\"protocol\":{\"framing\":\"aap_v1\"}}},"
        + "\"default_channel\":\"c\","
        + "\"functions\":[{"
        + "  \"id\":\"battery\",\"type\":\"battery\","
        + "  \"battery_layout\":{\"02\":\"single\",\"04\":\"case\",\"08\":\"right\",\"01\":\"left\"}"
        + "}]}";

    @Test public void parsesBatteryLayout() throws Exception {
        DeviceDef d = DeviceDef.parse(new JSONObject(JSON));
        DeviceDef.Func f = null;
        for (DeviceDef.Func x : d.funcs()) if ("battery".equals(x.id)) f = x;
        assertNotNull(f);
        assertEquals("case", f.batteryLayout.get(0x04));
        assertEquals("right", f.batteryLayout.get(0x08));
        assertTrue(d.hasFunc("battery"));
        assertFalse(d.hasFunc("ownership"));
    }
}
