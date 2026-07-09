package io.github.thelok1s.orchestra;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Verifies BoseEngine BMAP framing + the crash-safety fix: a 2-part command must not throw, and the
 * response scanner must honor the manifest response operator (not a hardcoded 0x03) and validate the
 * declared payload length.
 */
public class BoseFrameTest {

    @Test public void frameLayoutBlockFunctionOperatorLenPayload() {
        byte[] f = BoseEngine.buildFrame("01:06:02", "01");
        assertArrayEquals(new byte[]{0x01, 0x06, 0x02, 0x01, 0x01}, f);
    }

    @Test public void twoPartCommandDoesNotCrashAndDefaultsToSetOperator() {
        // The old engine did parts[2] unconditionally -> ArrayIndexOutOfBounds. Must default to SET.
        byte[] f = BoseEngine.buildFrame("01:06", "0a");
        assertArrayEquals(new byte[]{0x01, 0x06, 0x02, 0x01, 0x0a}, f);
    }

    @Test public void hexCommandFormAlsoWorks() {
        byte[] f = BoseEngine.buildFrame("010601", null);
        assertArrayEquals(new byte[]{0x01, 0x06, 0x01, 0x00}, f);
    }

    @Test public void findResponseUsesManifestOperatorAndValidatesLength() {
        // On the wire: our SET echo (op 0x02) then the device STATUS reply (op 0x03, 2-byte payload).
        byte[] wire = {
            0x01, 0x06, 0x02, 0x01, 0x01,             // our SET echo — must be skipped
            0x01, 0x06, 0x03, 0x02, (byte) 0x01, 0x00 // STATUS reply, payload len 2
        };
        int s = BoseEngine.findResponse(wire, wire.length, "01:06:01", "01:06:03");
        assertEquals(5, s);                            // matched the STATUS frame, not the echo
        assertEquals(0x03, wire[s + 2] & 0xFF);        // operator is the manifest's response op
    }

    @Test public void findResponseSkipsSetEchoWhenNoResponseCommand() {
        byte[] wire = {
            0x01, 0x06, 0x02, 0x01, 0x01,             // SET echo (skipped: op == SET)
            0x01, 0x06, 0x07, 0x01, (byte) 0x05       // RESULT (op 0x07), accepted
        };
        int s = BoseEngine.findResponse(wire, wire.length, "01:06:01", null);
        assertEquals(5, s);
    }

    @Test public void findResponseWaitsForFullPayload() {
        // Header claims 2 payload bytes but only 1 has arrived -> not a match yet.
        byte[] partial = {0x01, 0x06, 0x03, 0x02, (byte) 0x01};
        assertEquals(-1, BoseEngine.findResponse(partial, partial.length, "01:06:01", "01:06:03"));
    }

    @Test public void framingRoutesToBoseEngineAndResponseCommandParses() throws Exception {
        JSONObject ch = new JSONObject()
                .put("transport", "rfcomm")
                .put("uuid", "00001101-0000-1000-8000-00805f9b34fb")
                .put("protocol", new JSONObject().put("framing", "bose_v1"));
        JSONObject fn = new JSONObject()
                .put("id", "sound_mode").put("type", "multitoggle").put("title", "ANC")
                .put("set", new JSONObject().put("command", "01:06:02")
                        .put("payload_template", "{mode}")
                        .put("option_values", new JSONObject().put("anc", "01").put("off", "00")))
                .put("options", new JSONArray()
                        .put(new JSONObject().put("id", "anc").put("label", "ANC"))
                        .put(new JSONObject().put("id", "off").put("label", "Off")))
                .put("read", new JSONObject().put("command", "01:06:01")
                        .put("response_command", "01:06:03").put("state_byte_index", 4))
                .put("ui", new JSONObject().put("setting_id", 6001));
        JSONObject root = new JSONObject().put("schema_version", 3).put("revision", 1)
                .put("id", "x").put("name", "X")
                .put("channels", new JSONObject().put("spp-main", ch))
                .put("default_channel", "spp-main")
                .put("functions", new JSONArray().put(fn));
        DeviceDef def = DeviceDef.parse(root);
        DeviceDef.Func f = def.funcById("sound_mode");
        assertSame(ControlEngine.BOSE, ControlEngine.forFunc(f));
        assertEquals("01:06:03", f.responseCommand); // parsed from read.response_command
    }
}
