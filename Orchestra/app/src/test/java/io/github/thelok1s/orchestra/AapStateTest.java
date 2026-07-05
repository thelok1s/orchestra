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

    @Test public void clearDropsCachedStateForMac() {
        String mac = "AA:BB:CC:DD:EE:04";
        AapState.forMac(mac).setAncMode(3);
        assertEquals(Integer.valueOf(3), AapState.forMac(mac).getAncMode());
        AapState.clear(mac);
        // forMac after clear yields a fresh instance with no carried-over state
        assertNull(AapState.forMac(mac).getAncMode());
    }

    @Test public void clearIsCaseInsensitive() {
        String mac = "AA:BB:CC:DD:EE:05";
        AapState.forMac(mac).setAncMode(4);
        AapState.clear("aa:bb:cc:dd:ee:05");
        assertNull(AapState.forMac(mac).getAncMode());
    }
}
