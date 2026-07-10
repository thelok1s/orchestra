package io.github.thelok1s.orchestra;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Milestone-3 Phase 2: verifies that many controls decode from ONE shared status buffer. The device
 * reports all state in a single response (Soundcore {@code 0101}); {@link RfcommEngine#decodeOption}
 * extracts each function's value at its own {@code state_byte_index}, so a batched read pre-checks the
 * whole page in one exchange.
 */
public class RfcommStatusDecodeTest {

    /** Build a Func with a single-byte read (state_byte_index + value_map) via the real parser. */
    private static DeviceDef.Func func(String id, String type, int sbi, JSONObject valueMap,
                                       JSONObject setBlock) throws Exception {
        JSONObject fn = new JSONObject().put("id", id).put("type", type).put("title", id)
                .put("set", setBlock)
                .put("read", new JSONObject().put("command", "0101")
                        .put("state_byte_index", sbi).put("value_map", valueMap))
                .put("ui", new JSONObject().put("setting_id", 100 + sbi));
        return DeviceDef.parseFuncForTest(fn);
    }

    @Test public void decodesMultipleFieldsFromOneStatusBuffer() throws Exception {
        // A 09ff...01 response for cmd 0101, then payload. state_byte_index is relative to the
        // response-start (the 09ff prefix), so index N indexes into the whole frame.
        // frame: 09 ff 00 00 01 | 01 01 | <payload...>
        //         0  1  2  3  4    5  6    7 ...
        // Put an ANC mode byte at index 8 and a toggle byte at index 10.
        byte[] status = {
            0x09, (byte) 0xff, 0x00, 0x00, 0x01,   // response prefix
            0x01, 0x01,                             // echoed command 0101
            0x00,                                   // index 7 (filler)
            0x02,                                   // index 8: ANC mode = 0x02 (transparency)
            0x00,                                   // index 9 (filler)
            0x01                                    // index 10: multipoint = 0x01 (on)
        };
        int start = 0;

        DeviceDef.Func anc = func("sound_mode", "multitoggle", 8,
                new JSONObject().put("00", "off").put("01", "anc").put("02", "transparency"),
                new JSONObject().put("command", "8101")
                        .put("payload_template", "{mode}")
                        .put("option_values", new JSONObject().put("off", "00").put("anc", "01").put("transparency", "02")));
        DeviceDef.Func mp = func("multipoint", "toggle", 10,
                new JSONObject().put("00", "off").put("01", "on"),
                new JSONObject().put("command", "8102")
                        .put("state_values", new JSONObject().put("on", "01").put("off", "00")));

        assertEquals("transparency", RfcommEngine.decodeOption(status, status.length, start, anc));
        assertEquals("on", RfcommEngine.decodeOption(status, status.length, start, mp));
    }

    @Test public void unknownByteDecodesToNull() throws Exception {
        byte[] status = {0x09, (byte) 0xff, 0x00, 0x00, 0x01, 0x01, 0x01, 0x00, (byte) 0x7f};
        DeviceDef.Func anc = func("sound_mode", "multitoggle", 8,
                new JSONObject().put("00", "off").put("01", "anc"),
                new JSONObject().put("command", "8101").put("payload_template", "{mode}")
                        .put("option_values", new JSONObject().put("off", "00").put("anc", "01")));
        // byte 0x7f isn't in the value_map -> no option.
        assertNull(RfcommEngine.decodeOption(status, status.length, 0, anc));
    }

    @Test public void stateByteBeyondBufferDecodesToNull() throws Exception {
        byte[] shortBuf = {0x09, (byte) 0xff, 0x00, 0x00, 0x01, 0x01, 0x01};
        DeviceDef.Func anc = func("sound_mode", "multitoggle", 8,
                new JSONObject().put("00", "off"),
                new JSONObject().put("command", "8101").put("payload_template", "{mode}")
                        .put("option_values", new JSONObject().put("off", "00")));
        assertNull(RfcommEngine.decodeOption(shortBuf, shortBuf.length, 0, anc));
    }
}
