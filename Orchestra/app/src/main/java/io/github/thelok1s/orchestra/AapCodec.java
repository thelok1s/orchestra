package io.github.thelok1s.orchestra;

/**
 * Pure AAP (Apple Accessory Protocol, {@code aap_v1}) byte framing — no Android dependencies, so it
 * is unit-testable on the JVM. Per the design's Assumption 1 the protocol framing (header, opcodes,
 * fixed bring-up packets) lives here in the engine layer; only per-device option values come from
 * the manifest. Frames are the raw L2CAP payloads written to / read from PSM 4097.
 *
 * AAP control/notify packets are {@code 04 00 04 00 | <opcode:2> | <data..>}.
 * Noise control is the control command {@code 09 00 | 0D | <mode> 00 00 00}, mode 1..4.
 */
final class AapCodec {
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

    /** Noise-control set frame for mode 1..4 (Off/ANC/Transparency/Adaptive). */
    static byte[] ancSet(int modeByte) {
        return dataPacket(0x09, 0x00, new byte[]{0x0D, (byte) modeByte, 0, 0, 0, 0});
    }

    /**
     * If {@code frame[0..len)} is a noise-control notification ({@code 04 00 04 00 09 00 0D ..}),
     * return its mode byte (1..4); otherwise null.
     */
    static Integer parseAncMode(byte[] frame, int len) {
        byte[] prefix = {0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D};
        if (len < prefix.length + 1) return null;
        for (int i = 0; i < prefix.length; i++) if (frame[i] != prefix[i]) return null;
        return frame[7] & 0xff;
    }
}
