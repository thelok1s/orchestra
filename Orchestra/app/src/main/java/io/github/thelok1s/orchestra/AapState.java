package io.github.thelok1s.orchestra;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-device last-known AAP state, updated by {@link AacpEngine}'s reader thread and read by the
 * engine's (non-blocking) accessors. AAP is a push protocol, so reads return the cache rather than
 * issuing I/O.
 */
final class AapState {
    private static final Map<String, AapState> CACHE = new ConcurrentHashMap<>();

    private volatile Integer ancMode;       // 1..4, null = unknown
    private volatile Boolean caEnabled;      // null = unknown
    private volatile AapCodec.Battery battery;
    private volatile AapCodec.Ear ear;

    private AapState() {}

    static AapState forMac(String mac) {
        return CACHE.computeIfAbsent(mac.toUpperCase(Locale.ROOT), k -> new AapState());
    }

    void setAncMode(int modeByte) { this.ancMode = modeByte; }
    Integer getAncMode() { return ancMode; }

    void setCaEnabled(boolean on) { this.caEnabled = on; }
    Boolean getCaEnabled() { return caEnabled; }

    void setBattery(AapCodec.Battery b) { this.battery = b; }
    /** "L 100% · R 99% · Case 17%" over known components, or null if none known. */
    String batterySummary() {
        AapCodec.Battery b = battery;
        if (b == null) return null;
        StringBuilder sb = new StringBuilder();
        if (b.left != null)      append(sb, "L " + b.left + "%");
        if (b.right != null)     append(sb, "R " + b.right + "%");
        if (b.caseLevel != null) append(sb, "Case " + b.caseLevel + "%");
        return sb.length() == 0 ? null : sb.toString();
    }

    void setEar(AapCodec.Ear e) { this.ear = e; }
    /** Positional in-ear summary, or null if unknown. */
    String earSummary() {
        AapCodec.Ear e = ear;
        if (e == null) return null;
        boolean pIn = e.primary == 0, sIn = e.secondary == 0;
        if (pIn && sIn) return "Both in ear";
        if (!pIn && !sIn) {
            return (e.primary == 2 || e.secondary == 2) ? "In case" : "Out of ear";
        }
        return "One in ear";
    }

    private static void append(StringBuilder sb, String part) {
        if (sb.length() > 0) sb.append(" · ");
        sb.append(part);
    }
}
