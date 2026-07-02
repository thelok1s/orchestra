package io.github.thelok1s.orchestra;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DeviceDefTextTest {

    @Test public void parsesTextFunction() throws Exception {
        org.json.JSONObject fn = new org.json.JSONObject()
            .put("id", "rename").put("type", "text").put("title", "Rename")
            .put("capability", "rename").put("local", true).put("inject", false)
            .put("ui", new org.json.JSONObject().put("setting_id", 1008)
                 .put("surfaces", new org.json.JSONArray().put("app")));
        DeviceDef.Func f = DeviceDef.parseFuncForTest(fn);
        assertTrue(f.isText());
        assertFalse(f.isLevel());
        assertTrue(f.local);
        assertFalse(f.injectable);
        assertFalse(f.implemented);
    }
}
