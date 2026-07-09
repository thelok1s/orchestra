package io.github.thelok1s.orchestra;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Verifies SamsungEngine framing against timschneeb/GalaxyBudsClient: CRC-16/XMODEM (init 0x0000),
 * little-endian header + CRC, size = msgId + payload + crc, SOM/EOM bracketing.
 */
public class SamsungFrameTest {

    @Test public void crc16XmodemKnownVector() {
        // Canonical CRC-16/XMODEM check value: CRC of ASCII "123456789" == 0x31C3.
        byte[] data = "123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals(0x31C3, SamsungEngine.crc16Xmodem(data, 0, data.length));
    }

    @Test public void frameLayoutSomHeaderMsgIdPayloadCrcEom() {
        // msgId 0x77 (set noise controls), payload 0x01, default SOM/EOM.
        byte[] f = SamsungEngine.buildFrame("77", "01", 0xFD, 0xDD);
        // SOM + header(2) + [msgId + payload(1) + crc(2)] + EOM = 1+2+4+1 = 8
        assertEquals(8, f.length);
        assertEquals(0xFD, f[0] & 0xFF);              // SOM
        int size = 1 + 1 + 2;                          // msgId + payload + crc
        assertEquals(size, f[1] & 0xFF);               // header low byte (LE)
        assertEquals(0x00, f[2] & 0xFF);               // header high byte (no flags, size < 256)
        assertEquals(0x77, f[3] & 0xFF);               // msgId
        assertEquals(0x01, f[4] & 0xFF);               // payload
        // CRC over msgId+payload, little-endian at [5],[6]
        int crc = SamsungEngine.crc16Xmodem(new byte[]{0x77, 0x01}, 0, 2);
        assertEquals(crc & 0xFF, f[5] & 0xFF);         // CRC low
        assertEquals((crc >> 8) & 0xFF, f[6] & 0xFF);  // CRC high
        assertEquals(0xDD, f[7] & 0xFF);               // EOM
    }

    @Test public void emptyPayloadReadRequest() {
        byte[] f = SamsungEngine.buildFrame("61", null, 0xFD, 0xDD);
        // size = 1(msgId)+0+2(crc) = 3; total = 1+2+3+1 = 7
        assertEquals(7, f.length);
        assertEquals(3, f[1] & 0xFF);
        assertEquals(0x61, f[3] & 0xFF);
        assertEquals(0xDD, f[f.length - 1] & 0xFF);
    }

    @Test public void customSomEomFromManifest() {
        byte[] f = SamsungEngine.buildFrame("77", "00", 0x7F, 0xEE);
        assertEquals(0x7F, f[0] & 0xFF);
        assertEquals(0xEE, f[f.length - 1] & 0xFF);
    }

    @Test public void framingRoutesToSamsungEngineAndProtocolBytesParse() throws Exception {
        DeviceDef def = DeviceDef.parse(manifest("samsung_v1",
                new JSONObject().put("framing", "samsung_v1").put("som", "7f").put("eom", "ee")));
        DeviceDef.Func f = def.funcById("sound_mode");
        assertSame(ControlEngine.SAMSUNG, ControlEngine.forFunc(f));
        assertEquals(0x7F, def.protocolByte("som", 0xFD)); // read from manifest
        assertEquals(0xEE, def.protocolByte("eom", 0xDD));
        assertEquals(0xAB, def.protocolByte("missing", 0xAB)); // default when absent
    }

    /** A minimal Samsung-style manifest with the given framing/protocol block on its one channel. */
    static JSONObject manifest(String framing, JSONObject protocol) throws Exception {
        JSONObject ch = new JSONObject()
                .put("transport", "rfcomm")
                .put("uuid", "00001101-0000-1000-8000-00805f9b34fb")
                .put("protocol", protocol);
        JSONObject fn = new JSONObject()
                .put("id", "sound_mode").put("type", "multitoggle").put("title", "ANC")
                .put("set", new JSONObject().put("command", "77")
                        .put("payload_template", "{mode}")
                        .put("option_values", new JSONObject().put("anc", "01").put("off", "00")))
                .put("options", new JSONArray()
                        .put(new JSONObject().put("id", "anc").put("label", "ANC"))
                        .put(new JSONObject().put("id", "off").put("label", "Off")))
                .put("ui", new JSONObject().put("setting_id", 5001));
        return new JSONObject().put("schema_version", 3).put("revision", 1)
                .put("id", "x").put("name", "X")
                .put("channels", new JSONObject().put("spp-main", ch))
                .put("default_channel", "spp-main")
                .put("functions", new JSONArray().put(fn));
    }
}
