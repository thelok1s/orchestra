package io.github.thelok1s.orchestra;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import org.junit.Test;

public class AapStateTest {
    @Test public void ancModeIsNullUntilSet() {
        AapState s = AapState.forMac("AA:BB:CC:DD:EE:01");
        assertNull(s.getAncMode());
    }

    @Test public void ancModeRoundTrips() {
        AapState s = AapState.forMac("AA:BB:CC:DD:EE:02");
        s.setAncMode(2);
        assertEquals(Integer.valueOf(2), s.getAncMode());
    }

    @Test public void forMacReturnsSameInstancePerMac() {
        assertSame(AapState.forMac("AA:BB:CC:DD:EE:03"), AapState.forMac("aa:bb:cc:dd:ee:03"));
    }
}
