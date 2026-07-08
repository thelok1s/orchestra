package io.github.thelok1s.orchestra;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.Map;

public class AapCodecGenericTest {
    private static byte[] hx(String s) {
        s = s.replace(" ", ""); byte[] b = new byte[s.length()/2];
        for (int i=0;i<b.length;i++) b[i]=(byte)Integer.parseInt(s.substring(2*i,2*i+2),16);
        return b;
    }

    // --- generic write builder (Lane A outbound) ---
    @Test public void featureSetBuildsExactFrames() {
        assertArrayEquals(hx("04 00 04 00 09 00 28 01 00 00 00"), AapCodec.featureSet(0x28, 0x01)); // CA on
        assertArrayEquals(hx("04 00 04 00 09 00 0d 02 00 00 00"), AapCodec.featureSet(0x0d, 0x02)); // ANC=anc
        assertArrayEquals(hx("04 00 04 00 09 00 2e 32 00 00 00"), AapCodec.featureSet(0x2e, 0x32)); // level 50
    }

    // --- generic read router (Lane A inbound) ---
    @Test public void parseFeatureRoutesByFeatureByte() {
        byte[] ca = hx("04 00 04 00 09 00 28 01 00 00 00");
        assertEquals(Integer.valueOf(1), AapCodec.parseFeature(ca, ca.length, 0x28));
        assertNull(AapCodec.parseFeature(ca, ca.length, 0x0d)); // wrong feature -> miss
        assertNull(AapCodec.parseFeature(hx("04 00 04 00 09 00"), 6, 0x28)); // short -> no crash, miss
    }

    // --- battery layout parameterization (Lane B) ---
    @Test public void parseBatteryHonorsLayout() {
        // frame: 04 00 04 00 04 00 <count=1> <comp=08 (default:"case")> 01 <level=0x3c=60> <status=01> 01
        // layout remaps comp 0x08 to "right" instead of the hardcoded default ("case"), proving the
        // layout parameter -- not the hardcoded switch -- drives the component->slot assignment.
        byte[] f = hx("04 00 04 00 04 00 01 08 01 3c 01 01");
        Map<Integer,String> layout = new java.util.LinkedHashMap<>();
        layout.put(0x08, "right");
        AapCodec.Battery b = AapCodec.parseBattery(f, f.length, layout);
        assertNotNull(b);
        assertEquals(Integer.valueOf(60), b.right); // field name per existing Battery struct (public final Integer right)
        assertNull(b.caseLevel); // default mapping (comp 0x08 -> case) must NOT apply once layout overrides it
    }

    // --- zero-regression: no-arg overload must still use the hardcoded Pro-2 default mapping ---
    @Test public void parseBatteryNoLayoutStillUsesDefaultMapping() {
        // count 3: Left(0x04) 100 discharging, Right(0x02) 99 charging, Case(0x08) 17 discharging
        byte[] f = {4,0,4,0,4,0, 3, 4,1,100,2,1, 2,1,99,1,1, 8,1,17,2,1};
        AapCodec.Battery b = AapCodec.parseBattery(f, f.length);
        assertNotNull(b);
        assertEquals(Integer.valueOf(100), b.left);
        assertEquals(Integer.valueOf(99), b.right);
        assertEquals(Integer.valueOf(17), b.caseLevel);
        assertEquals(Integer.valueOf(1), b.rightStatus); // charging

        // and the 3-arg overload with an empty/null layout must reproduce this byte-for-byte
        AapCodec.Battery b2 = AapCodec.parseBattery(f, f.length, null);
        assertEquals(b.left, b2.left);
        assertEquals(b.right, b2.right);
        assertEquals(b.caseLevel, b2.caseLevel);
        assertEquals(b.rightStatus, b2.rightStatus);

        AapCodec.Battery b3 = AapCodec.parseBattery(f, f.length, new java.util.HashMap<>());
        assertEquals(b.left, b3.left);
        assertEquals(b.right, b3.right);
        assertEquals(b.caseLevel, b3.caseLevel);
    }
}
