package io.github.thelok1s.orchestra;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-device last-known AAP state, updated by {@link AacpEngine}'s reader thread and read by the
 * engine's (non-blocking) accessors. AAP is a push protocol, so reads return the cache rather than
 * issuing I/O. For Plan 1 it holds only the noise-control mode byte; battery / ear-detection fields
 * are added in a later plan.
 */
final class AapState {
    private static final Map<String, AapState> CACHE = new ConcurrentHashMap<>();

    private volatile Integer ancMode; // 1..4, null = unknown

    private AapState() {}

    static AapState forMac(String mac) {
        return CACHE.computeIfAbsent(mac.toUpperCase(Locale.ROOT), k -> new AapState());
    }

    void setAncMode(int modeByte) { this.ancMode = modeByte; }
    Integer getAncMode() { return ancMode; }
}
