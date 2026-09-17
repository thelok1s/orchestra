package io.github.thelok1s.orchestra;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Verifies OpoEngine framing for BBK (OnePlus / OPPO / realme) "opo_v1".
 *
 * <p>The golden vectors are real bytes captured from {@code com.realme.link} driving a realme Buds
 * Air8 Pro over RFCOMM on 2026-09-16 (see {@code docs/opov1-protocol.md}), with the RFCOMM UIH
 * header and FCS stripped. The layout matches the app's own serializer in
 * {@code tl/protocol/packet/Packet.java}:
 * <pre>
 *   AA | LEN u16 LE (= total-2) | FLAG | cmdId u16 LE | transferId u8 | dataLen u16 LE | payload
 * </pre>
 */
public class OpoFrameTest {

    /** Captured host->buds noise-control GET: cmdId 0x0109, transferId 0x37, no payload. */
    private static final String GOLDEN_TX = "aa0700000901370000";
    /** Captured buds->host reply: cmdId 0x8109, transferId 0x37, dataLen 8. */
    private static final String GOLDEN_RX = "aa0f0000098137080000030100020003" + "04";

    @Test public void buildsCapturedGetFrameByteForByte() {
        byte[] f = OpoEngine.buildFrame("0109", null, 0x37);
        assertEquals(GOLDEN_TX, HexUtil.hex(f));
    }

    @Test public void commandIdIsLittleEndianOnTheWire() {
        // Logical id 0x0109 (opcode 0x01 GET, feature 0x09 noise control) -> low byte first.
        byte[] f = OpoEngine.buildFrame("0109", null, 0x01);
        assertEquals(0x09, f[4] & 0xFF);
        assertEquals(0x01, f[5] & 0xFF);
    }

    @Test public void headerIsMagicLenFlagAndLenIsTotalMinusTwo() {
        byte[] f = OpoEngine.buildFrame("0409", "010102", 0x42);
        assertEquals(0xAA, f[0] & 0xFF);
        int len = (f[1] & 0xFF) | ((f[2] & 0xFF) << 8);   // u16 LE
        assertEquals(f.length - 2, len);
        assertEquals(0x00, f[3] & 0xFF);                  // flag byte, 0 in all observed traffic
    }

    @Test public void dataLenIsLittleEndianAndMatchesPayload() {
        byte[] f = OpoEngine.buildFrame("0409", "010102", 0x42);
        int dataLen = (f[7] & 0xFF) | ((f[8] & 0xFF) << 8);
        assertEquals(3, dataLen);
        assertEquals(9 + 3, f.length);
        assertArrayEquals(new byte[]{0x01, 0x01, 0x02},
                java.util.Arrays.copyOfRange(f, 9, f.length));
    }

    @Test public void emptyPayloadFrameIsNineBytes() {
        assertEquals(9, OpoEngine.buildFrame("0106", null, 0x01).length);
        assertEquals(9, OpoEngine.buildFrame("0106", "", 0x01).length);
    }

    @Test public void transferIdIsEchoedIntoTheFrame() {
        assertEquals(0x37, OpoEngine.buildFrame("0109", null, 0x37)[6] & 0xFF);
        assertEquals(0xFE, OpoEngine.buildFrame("0109", null, 0xFE)[6] & 0xFF);
    }

    // ---- response matching ----

    @Test public void findsCapturedReplyByResponseBitAndTransferId() {
        byte[] rx = HexUtil.unhex(GOLDEN_RX);
        // A reply is the request id with 0x8000 set: 0x0109 -> 0x8109.
        assertEquals(0, OpoEngine.findFrame(rx, rx.length, 0x0109, 0x37));
    }

    @Test public void skipsLeadingGarbageBeforeTheMagic() {
        byte[] rx = HexUtil.unhex("00ff" + GOLDEN_RX);
        assertEquals(2, OpoEngine.findFrame(rx, rx.length, 0x0109, 0x37));
    }

    @Test public void rejectsMismatchedTransferId() {
        byte[] rx = HexUtil.unhex(GOLDEN_RX);
        assertEquals(-1, OpoEngine.findFrame(rx, rx.length, 0x0109, 0x36));
    }

    @Test public void rejectsMismatchedCommandId() {
        byte[] rx = HexUtil.unhex(GOLDEN_RX);
        assertEquals(-1, OpoEngine.findFrame(rx, rx.length, 0x0106, 0x37));
    }

    @Test public void rejectsFrameNotYetFullyBuffered() {
        byte[] full = HexUtil.unhex(GOLDEN_RX);
        // Everything but the last payload byte has arrived.
        assertEquals(-1, OpoEngine.findFrame(full, full.length - 1, 0x0109, 0x37));
    }

    @Test public void extractsPayloadOfCapturedReply() {
        byte[] rx = HexUtil.unhex(GOLDEN_RX);
        int s = OpoEngine.findFrame(rx, rx.length, 0x0109, 0x37);
        assertEquals("0003010002000304", HexUtil.hex(OpoEngine.payloadOf(rx, s)));
    }

    /** Noise control reports (type,value) pairs; type 0x03 held 0x04 = Off while the app showed Off. */
    @Test public void capturedReplyCarriesTheModeAtTheDocumentedOffset() {
        byte[] rx = HexUtil.unhex(GOLDEN_RX);
        int s = OpoEngine.findFrame(rx, rx.length, 0x0109, 0x37);
        assertEquals(0x04, rx[s + 16] & 0xFF);   // frame-relative index used by state_byte_index
    }

    // ---- transfer id allocation ----

    @Test public void transferIdsStayInUnsignedByteRangeAndAdvance() {
        int a = OpoEngine.nextTransferId("AA:BB:CC:DD:EE:FF");
        int b = OpoEngine.nextTransferId("AA:BB:CC:DD:EE:FF");
        assertTrue(a >= 1 && a <= 0xFF);
        assertTrue(b >= 1 && b <= 0xFF);
        assertTrue("transfer id must advance", a != b);
        for (int i = 0; i < 600; i++) {
            int v = OpoEngine.nextTransferId("AA:BB:CC:DD:EE:FF");
            assertTrue("wrapped out of range: " + v, v >= 1 && v <= 0xFF);
        }
    }

    // ---- manifest routing ----

    @Test public void framingRoutesToOpoEngine() throws Exception {
        DeviceDef def = DeviceDef.parse(manifest());
        DeviceDef.Func f = def.funcById("sound_mode");
        assertSame(ControlEngine.OPO, ControlEngine.forFunc(f));
    }

    /** A minimal BBK-style manifest: rfcomm transport carrying the opo_v1 codec. */
    static JSONObject manifest() throws Exception {
        JSONObject ch = new JSONObject()
                .put("transport", "rfcomm")
                .put("uuid", "0000079a-d102-11e1-9b23-00025b00a5a5")
                .put("protocol", new JSONObject().put("framing", "opo_v1"));
        JSONObject fn = new JSONObject()
                .put("id", "sound_mode").put("type", "multitoggle").put("title", "Noise control")
                .put("set", new JSONObject().put("command", "0409")
                        .put("payload_template", "0101{mode}")
                        .put("option_values", new JSONObject()
                                .put("anc", "01").put("transparency", "02").put("off", "04")))
                .put("read", new JSONObject().put("command", "0109")
                        .put("state_byte_index", 16)
                        .put("value_map", new JSONObject()
                                .put("01", "anc").put("02", "transparency").put("04", "off")))
                .put("options", new JSONArray()
                        .put(new JSONObject().put("id", "anc").put("label", "Noise cancellation"))
                        .put(new JSONObject().put("id", "transparency").put("label", "Transparency"))
                        .put(new JSONObject().put("id", "off").put("label", "Off")))
                .put("ui", new JSONObject().put("setting_id", 1001));
        return new JSONObject().put("schema_version", 3).put("revision", 1)
                .put("id", "x").put("name", "X")
                .put("channels", new JSONObject().put("spp-opo", ch))
                .put("default_channel", "spp-opo")
                .put("functions", new JSONArray().put(fn));
    }
}
