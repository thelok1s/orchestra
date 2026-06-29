package io.github.thelok1s.orchestra;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class HexUtilTest {
    @Test public void hexLowercasesWithTwoDigitsPerByte() {
        assertEquals("0400ff", HexUtil.hex(new byte[]{0x04, 0x00, (byte) 0xff}));
    }
    @Test public void unhexParsesPairs() {
        assertArrayEquals(new byte[]{0x04, 0x00, (byte) 0xff}, HexUtil.unhex("0400ff"));
    }
    @Test public void unhexStripsSeparators() {
        assertArrayEquals(new byte[]{0x09, (byte) 0xff}, HexUtil.unhex("09 ff"));
    }
}
