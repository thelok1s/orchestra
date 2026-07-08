package io.github.thelok1s.orchestra;

import java.util.Map;

/**
 * Pure AAP (Apple Accessory Protocol, {@code aap_v1}) byte framing — no Android dependencies, so it
 * is unit-testable on the JVM. Per the design's Assumption 1 the protocol framing (header, opcodes,
 * fixed bring-up packets) lives here in the engine layer; only per-device option values come from
 * the manifest. Frames are the raw L2CAP payloads written to / read from PSM 4097.
 *
 * AAP control/notify packets are {@code 04 00 04 00 | <opcode:2> | <data..>}.
 * Noise control is the control command {@code 09 00 | 0D | <mode> 00 00 00}, mode 1..4.
 */
public final class AapCodec {
    private static final byte[] HEADER = {0x04, 0x00, 0x04, 0x00};

    private AapCodec() {}

    /** The fixed 16-byte handshake that must be sent before AirPods respond to anything. */
    static byte[] handshake() {
        return new byte[]{0,0,4,0,1,0,2,0,0,0,0,0,0,0,0,0};
    }

    /** Header + opcode(2) + data. */
    private static byte[] dataPacket(int op0, int op1, byte[] data) {
        byte[] f = new byte[HEADER.length + 2 + data.length];
        System.arraycopy(HEADER, 0, f, 0, HEADER.length);
        f[4] = (byte) op0;
        f[5] = (byte) op1;
        System.arraycopy(data, 0, f, 6, data.length);
        return f;
    }

    /** Subscribe to ear-detection / battery / noise-control / etc. notifications. */
    static byte[] notificationRequest() {
        return dataPacket(0x0F, 0x00, new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
    }

    /** Optional: unlocks CA / adaptive-transparency response on AirPods Pro 2. */
    static byte[] setFeatureFlags() {
        return dataPacket(0x4D, 0x00,
                new byte[]{(byte) 0xD7, 0, 0, 0, 0, 0, 0, 0});
    }

    /** Generic single-byte feature frame: 04 00 04 00 09 00 <feature> <value> 00 00 00. */
    static byte[] featureSet(int feature, int value) {
        return dataPacket(0x09, 0x00, new byte[]{(byte) feature, (byte) value, 0, 0, 0});
    }

    /** Noise-control set frame for mode 1..4 (Off/ANC/Transparency/Adaptive). */
    static byte[] ancSet(int modeByte) {
        return featureSet(0x0D, modeByte);
    }

    /** Conversational Awareness set frame (on=01, off=02). */
    static byte[] caSet(boolean on) {
        return featureSet(0x28, on ? 1 : 2);
    }

    /**
     * If {@code frame[0..len)} is a feature notification ({@code 04 00 04 00 09 00 <feature> ..}),
     * return its value byte (offset 7); otherwise null. Noise control uses feature 0x0D, CA 0x28.
     */
    static Integer parseFeature(byte[] frame, int len, int feature) {
        byte[] prefix = {0x04, 0x00, 0x04, 0x00, 0x09, 0x00, (byte) feature};
        if (len < prefix.length + 1) return null;
        for (int i = 0; i < prefix.length; i++) if (frame[i] != prefix[i]) return null;
        return frame[7] & 0xff;
    }

    static Integer parseAncMode(byte[] frame, int len) {
        return parseFeature(frame, len, 0x0D);
    }

    /** Adaptive-audio noise strength notification (feature 0x2E), value 0..100; null otherwise. */
    static Integer parseAdaptiveStrength(byte[] frame, int len) {
        return parseFeature(frame, len, 0x2E);
    }

    /** Parsed battery levels/statuses; any missing component is null. */
    public static final class Battery {
        public final Integer left, right, caseLevel;
        public final Integer leftStatus, rightStatus, caseStatus;
        public Battery(Integer l, Integer ls, Integer r, Integer rs, Integer c, Integer cs) {
            left = l; leftStatus = ls; right = r; rightStatus = rs; caseLevel = c; caseStatus = cs;
        }
    }

    /** Hardcoded Pro-2 component->slot mapping, used when a manifest doesn't supply {@code battery_layout}
     *  (or doesn't cover a given component byte). Kept as its own method so behavior is byte-for-byte
     *  identical to the pre-manifest-driven code path. */
    private static String defaultSlot(int comp) {
        switch (comp) {
            case 0x04: return "left";
            case 0x02: return "right";
            case 0x08: return "case";
            default: return null;
        }
    }

    /** Parse a battery notification (04 00 04 00 04 00 <count> (<comp> 01 <level> <status> 01)*),
     *  using {@code layout} (component byte -> slot name "left"/"right"/"case"/"single") to resolve
     *  each component when the manifest supplies one; falls back to {@link #defaultSlot(int)} for any
     *  component {@code layout} doesn't cover (or when {@code layout} is null/empty). */
    static Battery parseBattery(byte[] frame, int len, Map<Integer, String> layout) {
        byte[] prefix = {0x04, 0x00, 0x04, 0x00, 0x04, 0x00};
        if (len < prefix.length + 1) return null;
        for (int i = 0; i < prefix.length; i++) if (frame[i] != prefix[i]) return null;
        int count = frame[6] & 0xff;
        Integer l = null, ls = null, r = null, rs = null, c = null, cs = null;
        int off = 7;
        for (int i = 0; i < count && off + 4 < len; i++, off += 5) {
            int comp = frame[off] & 0xff;
            int level = frame[off + 2] & 0xff;
            int status = frame[off + 3] & 0xff;
            String slot = (layout != null && layout.containsKey(comp)) ? layout.get(comp) : defaultSlot(comp);
            if (slot == null) continue;
            switch (slot) {
                case "left": l = level; ls = status; break;
                case "right": r = level; rs = status; break;
                case "case": c = level; cs = status; break;
                // "single" (mono/one-earbud devices) has no dedicated Battery field yet; skip rather
                // than guess a slot, per the graceful-degrade convention (never crash, never misattribute).
                default: break;
            }
        }
        return new Battery(l, ls, r, rs, c, cs);
    }

    /** Parse a battery notification using the hardcoded Pro-2 default component->slot mapping. */
    static Battery parseBattery(byte[] frame, int len) {
        return parseBattery(frame, len, null);
    }

    /** Parsed ear-detection status (0=in-ear, 1=out-of-ear, 2=in-case). */
    public static final class Ear {
        public final int primary, secondary;
        public Ear(int p, int s) { primary = p; secondary = s; }
    }

    /** Parse an ear-detection notification (04 00 04 00 06 00 <primary> <secondary>). */
    static Ear parseEar(byte[] frame, int len) {
        byte[] prefix = {0x04, 0x00, 0x04, 0x00, 0x06, 0x00};
        if (len < prefix.length + 2) return null;
        for (int i = 0; i < prefix.length; i++) if (frame[i] != prefix[i]) return null;
        return new Ear(frame[6] & 0xff, frame[7] & 0xff);
    }

    /**
     * Parse a Conversational Awareness speech-level notification
     * ({@code 04 00 04 00 4b 00 02 00 01 <level>}); returns the level byte (offset 9), or
     * {@code null} if the frame doesn't match. Per the Task 5 spike: levels 01/02 = speech
     * active, 08/09 = speech ended, 03/04/0b = intermediate (caller decides how to act).
     */
    static Integer parseCaSpeech(byte[] frame, int len) {
        byte[] prefix = {0x04, 0x00, 0x04, 0x00, 0x4b, 0x00, 0x02, 0x00, 0x01};
        if (len < prefix.length + 1) return null;
        for (int i = 0; i < prefix.length; i++) if (frame[i] != prefix[i]) return null;
        return frame[9] & 0xff;
    }

    /** Current-peer / ownership state parsed from opcode 0x0e (Plan 9 spike). */
    public static final class Ownership {
        public final boolean ownedByOther;
        public final String otherLabel; // peer MAC "AA:BB:CC:DD:EE:FF" big-endian, or null when none
        public Ownership(boolean ownedByOther, String otherLabel) {
            this.ownedByOther = ownedByOther; this.otherLabel = otherLabel;
        }
    }

    /** Parse the current-peer notification (04 00 04 00 0e 00 <6-byte peer MAC little-endian>
     *  <state>). An all-zero MAC means no other device (owned by this phone). Returns null if the
     *  frame isn't this opcode. Opcode + layout from the Plan 9 spike findings. */
    static Ownership parseOwnership(byte[] frame, int len) {
        byte[] prefix = {0x04, 0x00, 0x04, 0x00, 0x0e, 0x00};
        if (len < prefix.length + 6 + 1) return null;      // prefix + MAC + state
        for (int i = 0; i < prefix.length; i++) if (frame[i] != prefix[i]) return null;
        boolean allZero = true;
        for (int i = 6; i < 12; i++) if (frame[i] != 0) { allZero = false; break; }
        if (allZero) return new Ownership(false, null);
        StringBuilder mac = new StringBuilder(17);         // little-endian in frame -> big-endian label
        for (int i = 11; i >= 6; i--) {
            if (mac.length() > 0) mac.append(':');
            mac.append(String.format("%02X", frame[i] & 0xff));
        }
        return new Ownership(true, mac.toString());
    }
}
