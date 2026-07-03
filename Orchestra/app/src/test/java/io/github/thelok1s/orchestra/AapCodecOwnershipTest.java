package io.github.thelok1s.orchestra;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class AapCodecOwnershipTest {

    // Captured verbatim during the Plan 9 handoff spike (peer 94:45:60:45:4F:D7).
    @Test public void parsesOwnershipFrame_ownedByOther() {
        byte[] frame = {0x04,0x00,0x04,0x00,0x0e,0x00, (byte)0xd7,0x4f,0x45,0x60,0x45,(byte)0x94, 0x02};
        AapCodec.Ownership o = AapCodec.parseOwnership(frame, frame.length);
        assertNotNull(o);
        assertTrue(o.ownedByOther);
        assertEquals("94:45:60:45:4F:D7", o.otherLabel);
    }

    @Test public void parsesOwnershipFrame_clearedAllZeroMac() {
        byte[] frame = {0x04,0x00,0x04,0x00,0x0e,0x00, 0,0,0,0,0,0, 0x00};
        AapCodec.Ownership o = AapCodec.parseOwnership(frame, frame.length);
        assertNotNull(o);
        assertFalse(o.ownedByOther);
        assertNull(o.otherLabel);
    }

    @Test public void rejectsNonOwnershipFrame() {
        byte[] battery = {0x04,0x00,0x04,0x00,0x04,0x00,0x01,0x04,0x01,0x50,0x00,0x01};
        assertNull(AapCodec.parseOwnership(battery, battery.length));
    }

    @Test public void rejectsShortFrame() {
        byte[] shortFrame = {0x04,0x00,0x04,0x00,0x0e,0x00, (byte)0xd7,0x4f};
        assertNull(AapCodec.parseOwnership(shortFrame, shortFrame.length));
    }
}
