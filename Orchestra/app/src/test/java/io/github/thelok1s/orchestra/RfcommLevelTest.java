package io.github.thelok1s.orchestra;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Verifies the RFCOMM level/slider path: a {@code slider} function routes to the RFCOMM engine, its
 * {@code range} min/max/step parse, and the value is framed into soundcore_v1 correctly (value byte
 * substituted into the payload template, then wrapped with the standard prefix/len/crc).
 */
public class RfcommLevelTest {

    private static DeviceDef.Func sliderFunc(String cmd, String template) throws Exception {
        JSONObject set = new JSONObject().put("command", cmd);
        if (template != null) set.put("payload_template", template);
        JSONObject fn = new JSONObject()
            .put("id", "test_level").put("type", "slider").put("title", "Level")
            .put("range", new JSONObject().put("min", 1).put("max", 5).put("step", 1))
            .put("set", set)
            .put("ui", new JSONObject().put("setting_id", 4001));
        return DeviceDef.parseFuncForTest(fn);
    }

    @Test public void sliderRangeParsesFromRangeObject() throws Exception {
        DeviceDef.Func f = sliderFunc("0281", "{value}");
        assertEquals(1, f.min);
        assertEquals(5, f.max);
        assertEquals(1, f.step);
    }

    @Test public void levelFrameSubstitutesValueAndFrames() throws Exception {
        DeviceDef.Func f = sliderFunc("0281", "{value}");
        byte[] got = RfcommEngine.levelFrame(f, 0x0a);
        // Expected: prefix 08 ee 00 00 00, cmd 02 81, len(2 LE = total 11 = 0x0b 00), value 0a, crc.
        byte[] want = RfcommEngine.buildFrame("0281", "0a");
        assertArrayEquals(want, got);
        assertEquals(0x0a, got[9] & 0xff);              // value byte lands after the 2-byte len
        assertEquals(11, got.length);                    // 5 prefix + 2 cmd + 2 len + 1 value + 1 crc
        int sum = 0;
        for (int i = 0; i < got.length - 1; i++) sum += got[i] & 0xff;
        assertEquals("crc = sum8 of preceding bytes", sum & 0xff, got[got.length - 1] & 0xff);
    }

    @Test public void levelFrameSubstitutesWithinTemplate() throws Exception {
        DeviceDef.Func f = sliderFunc("0681", "00{value}0000");
        byte[] got = RfcommEngine.levelFrame(f, 0x50);
        assertArrayEquals(RfcommEngine.buildFrame("0681", "00500000"), got);
    }

    @Test public void levelFrameNullWithoutCommand() throws Exception {
        JSONObject fn = new JSONObject().put("id", "x").put("type", "slider").put("title", "X");
        assertNull(RfcommEngine.levelFrame(DeviceDef.parseFuncForTest(fn), 3));
    }
}
