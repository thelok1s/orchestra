package io.github.thelok1s.orchestra;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
        assertEquals("0400040009000d02000000", HexUtil.hex(AapCodec.ancSet(2)));
        assertEquals("0400040009000d04000000", HexUtil.hex(AapCodec.ancSet(4)));
    }

    @Test public void parseAncModeReadsModeByteFromNotification() {
        byte[] f = HexUtil.unhex("0400040009000d03000000"); // Transparency
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

    @Test public void caSet_isFeature28_on() {
        // 04 00 04 00 09 00 28 01 00 00 00
        assertArrayEquals(new byte[]{4,0,4,0,9,0,0x28,1,0,0,0}, AapCodec.caSet(true));
    }
    @Test public void caSet_off_is02() {
        assertEquals(2, AapCodec.caSet(false)[7]);
    }
    @Test public void ancSet_stillDelegatesToFeature0D() {
        assertArrayEquals(new byte[]{4,0,4,0,9,0,0x0D,3,0,0,0}, AapCodec.ancSet(3));
    }
    @Test public void parseFeature_caState_enabled() {
        byte[] f = {4,0,4,0,9,0,0x28,1,0,0,0};
        assertEquals(Integer.valueOf(1), AapCodec.parseFeature(f, f.length, 0x28));
        assertNull(AapCodec.parseFeature(f, f.length, 0x0D)); // wrong feature
    }
    @Test public void parseBattery_leftRightCase() {
        // count 3: Left 100 discharging, Right 99 charging, Case 17 discharging
        byte[] f = {4,0,4,0,4,0, 3, 4,1,100,2,1, 2,1,99,1,1, 8,1,17,2,1};
        AapCodec.Battery b = AapCodec.parseBattery(f, f.length);
        assertNotNull(b);
        assertEquals(Integer.valueOf(100), b.left);
        assertEquals(Integer.valueOf(99), b.right);
        assertEquals(Integer.valueOf(17), b.caseLevel);
        assertEquals(Integer.valueOf(1), b.rightStatus); // charging
    }
    @Test public void parseBattery_rejectsNonBattery() {
        byte[] f = {4,0,4,0,9,0,0x0D,2,0,0,0};
        assertNull(AapCodec.parseBattery(f, f.length));
    }
    @Test public void parseEar_bothInEar() {
        byte[] f = {4,0,4,0,6,0, 0, 0};
        AapCodec.Ear e = AapCodec.parseEar(f, f.length);
        assertNotNull(e);
        assertEquals(0, e.primary);
        assertEquals(0, e.secondary);
    }
}
