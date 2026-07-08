package io.github.thelok1s.orchestra;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-device last-known AAP state, updated by {@link AacpEngine}'s reader thread and read by the
 * engine's (non-blocking) accessors. AAP is a push protocol, so reads return the cache rather than
 * issuing I/O.
 */
public final class AapState {
    private static final Map<String, AapState> CACHE = new ConcurrentHashMap<>();

    private volatile Integer ancMode;       // 1..4, null = unknown
    private volatile Boolean caEnabled;      // null = unknown
    private volatile AapCodec.Battery battery;
    private volatile AapCodec.Ear ear;
    private volatile Integer adaptiveStrength;   // 0..100, null = unknown

    private AapState() {}

    public static AapState forMac(String mac) {
        return CACHE.computeIfAbsent(mac.toUpperCase(Locale.ROOT), k -> new AapState());
    }

    /** Drop a device's cached state (on disconnect) so stale values aren't served until the next
     *  session delivers fresh frames. The next {@link #forMac} call yields a clean instance. */
    public static void clear(String mac) {
        CACHE.remove(mac.toUpperCase(Locale.ROOT));
    }

    void setAncMode(int modeByte) { this.ancMode = modeByte; }
    public Integer getAncMode() { return ancMode; }

    void setCaEnabled(boolean on) { this.caEnabled = on; }
    public Boolean getCaEnabled() { return caEnabled; }

    void setAdaptiveStrength(int v) { this.adaptiveStrength = v; }
    public Integer getAdaptiveStrength() { return adaptiveStrength; }

    /** Task 4: generic manifest-function-id -> last-written-byte cache, keyed by {@code Func.id}.
     *  Populated optimistically by {@link AacpEngine}'s generalized Lane-A writes
     *  ({@code applyToggle}/{@code applyMode}/{@code setLevel}) so any function can round-trip its
     *  own value without a dedicated field like {@link #ancMode}/{@link #caEnabled}/
     *  {@link #adaptiveStrength} above. Task 5 wires the generic reader to consume this same map. */
    private final java.util.Map<String,Integer> values = new java.util.concurrent.ConcurrentHashMap<>();
    void setValue(String id, int b) { values.put(id, b); }
    public Integer getValue(String id) { return values.get(id); }

    private volatile AapCodec.Ownership ownership;
    void setOwnership(AapCodec.Ownership o) { this.ownership = o; }
    public AapCodec.Ownership getOwnership() { return ownership; }
    /** "Also connected to <label>" / "Only this phone" / null if unknown. */
    public String ownershipSummary() {
        AapCodec.Ownership o = ownership;
        if (o == null) return null;
        if (!o.ownedByOther) return "Only this phone";
        return "Also connected to " + (o.otherLabel != null ? o.otherLabel : "another device");
    }

    void setBattery(AapCodec.Battery b) { this.battery = b; }
    public AapCodec.Battery getBattery() { return battery; }
    /** "L 100% · R 99% · Case 17%" over known components, or null if none known. */
    public String batterySummary() {
        AapCodec.Battery b = battery;
        if (b == null) return null;
        StringBuilder sb = new StringBuilder();
        if (b.left != null)      append(sb, "L " + b.left + "%");
        if (b.right != null)     append(sb, "R " + b.right + "%");
        if (b.caseLevel != null) append(sb, "Case " + b.caseLevel + "%");
        return sb.length() == 0 ? null : sb.toString();
    }

    void setEar(AapCodec.Ear e) { this.ear = e; }
    public AapCodec.Ear getEar() { return ear; }
    /** Positional in-ear summary, or null if unknown. */
    public String earSummary() {
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
