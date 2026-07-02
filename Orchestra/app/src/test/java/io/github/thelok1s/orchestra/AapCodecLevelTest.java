package io.github.thelok1s.orchestra;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class AapCodecLevelTest {

    @Test public void featureSet_buildsStandardFrame() {
        // 04 00 04 00 09 00 <feature> <value> 00 00 00 ; adaptive strength 0x2E = 70
        byte[] f = AapCodec.featureSet(0x2E, 70);
        assertArrayEquals(
            new byte[]{0x04,0x00,0x04,0x00,0x09,0x00,(byte)0x2E,(byte)70,0x00,0x00,0x00}, f);
    }

    @Test public void parseAdaptiveStrength_reads0x2Evalue() {
        byte[] frame = {0x04,0x00,0x04,0x00,0x09,0x00,(byte)0x2E,(byte)55,0x00,0x00,0x00};
        assertEquals(Integer.valueOf(55), AapCodec.parseAdaptiveStrength(frame, frame.length));
    }

    @Test public void parseAdaptiveStrength_rejectsOtherFeatures() {
        byte[] anc = {0x04,0x00,0x04,0x00,0x09,0x00,0x0D,0x02,0x00,0x00,0x00};
        assertNull(AapCodec.parseAdaptiveStrength(anc, anc.length));
    }
}
