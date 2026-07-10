package io.github.thelok1s.orchestra;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * The Milestone-3 core fix: a failed/unknown device read must never clobber a known value with 0.
 * {@link SettingProviderService#resolveIndex} encodes the precedence live-read > session cache >
 * persisted last-known > unset.
 */
public class ResolveIndexTest {

    @Test public void liveReadAlwaysWins() {
        // Even with a stale cached/persisted value, a fresh authoritative read is used.
        assertEquals(Integer.valueOf(2), SettingProviderService.resolveIndex(2, 0, 1));
        assertEquals(Integer.valueOf(0), SettingProviderService.resolveIndex(0, 1, 3)); // 0 is a real read here
    }

    @Test public void failedReadKeepsSessionCache() {
        // The old bug: unknown -> 0. Now a known session value survives a failed read.
        assertEquals(Integer.valueOf(1), SettingProviderService.resolveIndex(null, 1, -1));
        assertEquals(Integer.valueOf(3), SettingProviderService.resolveIndex(null, 3, 0));
    }

    @Test public void failedReadWithoutSessionCacheFallsBackToPersisted() {
        assertEquals(Integer.valueOf(2), SettingProviderService.resolveIndex(null, null, 2));
    }

    @Test public void trulyUnknownStaysUnset() {
        // No read, no cache, no persisted -> null (caller leaves it unset, push applies the default).
        assertNull(SettingProviderService.resolveIndex(null, null, -1));
    }

    @Test public void sessionCacheBeatsPersisted() {
        // Within a session the freshest optimistic value (cache) wins over the older persisted one.
        assertEquals(Integer.valueOf(1), SettingProviderService.resolveIndex(null, 1, 0));
    }
}
