package io.github.thelok1s.orchestra;
import static org.junit.Assert.*;
import org.junit.Test; import org.json.JSONObject;

public class DeviceDefFeatureByteTest {
    private static final String JSON = "{"
        + "\"schema_version\":4,\"id\":\"x\",\"revision\":1,\"match\":{\"name_regex\":\"x\"},"
        + "\"channels\":{\"c\":{\"transport\":\"aacp\",\"psm\":4097,\"protocol\":{\"framing\":\"aap_v1\"}}},"
        + "\"default_channel\":\"c\",\"functions\":["
        + "{\"id\":\"sound_mode\",\"type\":\"multitoggle\",\"feature\":\"0d\",\"set\":{\"option_values\":{\"anc\":\"02\"}}},"
        + "{\"id\":\"ca\",\"type\":\"toggle\",\"feature\":\"28\",\"set\":{\"option_values\":{\"on\":\"01\",\"off\":\"02\"}}}"
        + "]}";
    @Test public void parsesFeatureForNonLevelTypes() throws Exception {
        DeviceDef d = DeviceDef.parse(new JSONObject(JSON));
        int sm = -1, ca = -1;
        for (DeviceDef.Func f : d.funcs()) {
            if ("sound_mode".equals(f.id)) sm = f.getFeatureByte();
            if ("ca".equals(f.id)) ca = f.getFeatureByte();
        }
        assertEquals(0x0d, sm);   // multitoggle
        assertEquals(0x28, ca);   // toggle
    }
}
