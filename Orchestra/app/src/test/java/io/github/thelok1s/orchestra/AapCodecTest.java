package io.github.thelok1s.orchestra;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class AapCodecTest {

    @Test public void handshakeIsTheFixed16ByteSequence() {
        assertEquals("00000400010002000000000000000000",
                HexUtil.hex(AapCodec.handshake()));
    }

    @Test public void notificationRequestHasHeaderOpcodeAndFFFFFFFF() {
        assertEquals("040004000f00ffffffff",
                HexUtil.hex(AapCodec.notificationRequest()));
    }

    @Test public void setFeatureFlagsMatchesKnownEnablePacket() {
        assertEquals("040004004d00d700000000000000",
                HexUtil.hex(AapCodec.setFeatureFlags()));
    }

    @Test public void ancSetWrapsModeByteInNoiseControlFrame() {
        // header 04000400 + 0900 + 0D + mode + 000000
        assertEquals("0400040009000d0200000000", HexUtil.hex(AapCodec.ancSet(2)));
        assertEquals("0400040009000d0400000000", HexUtil.hex(AapCodec.ancSet(4)));
    }

    @Test public void parseAncModeReadsModeByteFromNotification() {
        byte[] f = HexUtil.unhex("0400040009000d0300000000"); // Transparency
        assertEquals(Integer.valueOf(3), AapCodec.parseAncMode(f, f.length));
    }

    @Test public void parseAncModeIgnoresNonNoiseControlFrames() {
        byte[] battery = HexUtil.unhex("0400040004000302016402"); // battery opcode 04
        assertNull(AapCodec.parseAncMode(battery, battery.length));
    }

    @Test public void parseAncModeIgnoresConversationalAwarenessFrame() {
        // 0900 28 is CA, not noise-control 0900 0D
        byte[] ca = HexUtil.unhex("040004000900280100000000");
        assertNull(AapCodec.parseAncMode(ca, ca.length));
    }
}
