package io.github.thelok1s.orchestra;

import android.bluetooth.BluetoothAdapter;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BBK (OnePlus / OPPO / realme) "opo_v1" control engine, selected via a channel's
 * {@code protocol.framing}. Runs over the shared {@link SppTransport} socket.
 *
 * <p>On Android these devices expose the OPO control service
 * ({@code 0000079a-d102-11e1-9b23-00025b00a5a5}) in their <b>BR/EDR</b> SDP record and the vendor app
 * drives them over RFCOMM, so this is an SPP engine. The same UUID appears as a BLE GATT service on
 * hosts with no SPP — that is the path the public macOS write-ups use — but it is not what we speak.
 *
 * <p><b>Wire format</b>, verified against the vendor app's own serializer
 * ({@code tl/protocol/packet/Packet.java} in realme Link) and against live capture:
 * <pre>
 *   AA | LEN u16 LE | FLAG | cmdId u16 LE | transferId u8 | dataLen u16 LE | payload[dataLen]
 *    0    1    2       3      4    5         6              7    8           9..
 * </pre>
 * <ul>
 *   <li>{@code LEN} = {@code total_frame_len - 2} — every byte after {@code LEN} itself.</li>
 *   <li>{@code FLAG} is 0x00 in all observed traffic.</li>
 *   <li>{@code cmdId} is a single little-endian u16, {@code (opcode << 8) | feature}. Manifests
 *       carry the <b>logical</b> id (e.g. {@code "0109"} = GET feature 0x09, noise control); this
 *       codec writes it LSB-first. Opcodes: 0x01 GET, 0x02 NOTIFY, 0x04 SET, 0x05 REGISTER.</li>
 *   <li>A reply is the request id with the response bit set ({@code cmdId | 0x8000}) and the same
 *       {@code transferId} echoed back — mirroring {@code Packet.c()} / {@code Packet.j()}.</li>
 *   <li>There is <b>no checksum</b>.</li>
 * </ul>
 *
 * <p>{@code state_byte_index} in a manifest {@code read} block is <b>frame-relative</b> (as for the
 * other SPP engines), so the first payload byte is index 9.
 *
 * <p><b>Verification status.</b> The framing, the GET path and the response convention are confirmed
 * on a realme Buds Air8 Pro. The SET path is not yet hardware-confirmed: the vendor app refuses to
 * send any noise-control write while the buds are out of the ear, so no {@code 0x04xx} frame could
 * be captured. Accordingly a SET logs, but does not fail, when no {@code 0x84xx} ack arrives — an
 * unverified device must not look broken in the UI. Tighten this once acks are confirmed. See
 * {@code docs/opov1-protocol.md}.
 */
final class OpoEngine {
    private static final String TAG = DeviceDef.TAG;

    static final int MAGIC = 0xAA;
    static final int RESPONSE_BIT = 0x8000;
    /** AA + LEN(2) + FLAG + cmdId(2) + transferId + dataLen(2). */
    static final int HEADER_LEN = 9;
    private static final long REPLY_TIMEOUT_MS = 1500;
    private static final long ACK_TIMEOUT_MS = 800;

    private OpoEngine() {}

    // ---- transfer ids ----

    private static final Map<String, AtomicInteger> TRANSFER_IDS = new ConcurrentHashMap<>();

    /** Next transfer id for a device: rolls 1..0xFF (0 is avoided so an unset byte can't collide). */
    static int nextTransferId(String mac) {
        AtomicInteger c = TRANSFER_IDS.computeIfAbsent(mac.toUpperCase(), k -> new AtomicInteger());
        return (c.getAndIncrement() & 0xFF) % 0xFF + 1;
    }

    // ---- framing ----

    /**
     * Build a host→device frame. {@code cmdIdHex} is the logical u16 command id (e.g. {@code "0109"});
     * it is written little-endian. Package-private for tests.
     */
    static byte[] buildFrame(String cmdIdHex, String payloadHex, int transferId) {
        int cmdId = Integer.parseInt(cmdIdHex.replaceAll("[^0-9a-fA-F]", ""), 16) & 0xFFFF;
        byte[] payload = (payloadHex == null || payloadHex.isEmpty())
                ? new byte[0] : HexUtil.unhex(payloadHex);
        byte[] frame = new byte[HEADER_LEN + payload.length];
        int len = frame.length - 2;
        frame[0] = (byte) MAGIC;
        frame[1] = (byte) (len & 0xFF);                  // LEN low  (u16 LE)
        frame[2] = (byte) ((len >> 8) & 0xFF);           // LEN high
        frame[3] = 0x00;                                 // FLAG
        frame[4] = (byte) (cmdId & 0xFF);                // cmdId low (u16 LE)
        frame[5] = (byte) ((cmdId >> 8) & 0xFF);         // cmdId high
        frame[6] = (byte) (transferId & 0xFF);
        frame[7] = (byte) (payload.length & 0xFF);       // dataLen low (u16 LE)
        frame[8] = (byte) ((payload.length >> 8) & 0xFF);// dataLen high
        System.arraycopy(payload, 0, frame, HEADER_LEN, payload.length);
        return frame;
    }

    /**
     * Locate the reply to {@code reqCmdId}/{@code transferId} in {@code acc[0..len)}: magic at s, a
     * fully-buffered frame whose declared lengths agree, carrying {@code reqCmdId | 0x8000} and the
     * echoed transfer id. Returns s, or -1. Package-private for tests.
     */
    static int findFrame(byte[] acc, int len, int reqCmdId, int transferId) {
        int want = (reqCmdId | RESPONSE_BIT) & 0xFFFF;
        for (int s = 0; s + HEADER_LEN <= len; s++) {
            if ((acc[s] & 0xFF) != MAGIC) continue;
            int declared = (acc[s + 1] & 0xFF) | ((acc[s + 2] & 0xFF) << 8);
            int dataLen = (acc[s + 7] & 0xFF) | ((acc[s + 8] & 0xFF) << 8);
            if (declared != HEADER_LEN - 2 + dataLen) continue;   // self-consistent lengths
            if (s + HEADER_LEN + dataLen > len) continue;          // not fully buffered yet
            int cmdId = (acc[s + 4] & 0xFF) | ((acc[s + 5] & 0xFF) << 8);
            if (cmdId != want) continue;
            if ((acc[s + 6] & 0xFF) != (transferId & 0xFF)) continue;
            return s;
        }
        return -1;
    }

    /** Payload of the frame starting at {@code start}. Package-private for tests. */
    static byte[] payloadOf(byte[] acc, int start) {
        int dataLen = (acc[start + 7] & 0xFF) | ((acc[start + 8] & 0xFF) << 8);
        byte[] out = new byte[dataLen];
        System.arraycopy(acc, start + HEADER_LEN, out, 0, dataLen);
        return out;
    }

    // ---- exchange helpers ----

    /** Send a SET and look for its {@code 0x84xx} ack. Returns false only on transport failure. */
    private static boolean set(BluetoothAdapter adapter, String mac, DeviceDef def,
                               DeviceDef.Func f, String payloadHex, String logWhat) {
        final int cmdId = Integer.parseInt(f.setCommand.replaceAll("[^0-9a-fA-F]", ""), 16) & 0xFFFF;
        final int tid = nextTransferId(mac);
        final byte[] frame = buildFrame(f.setCommand, payloadHex, tid);
        return Boolean.TRUE.equals(SppTransport.withSession(adapter, mac, def, Boolean.FALSE, (in, out) -> {
            Log.i(TAG, "[OPO] TX " + logWhat + ": " + HexUtil.hex(frame));
            Logbook.add("[OPO] " + logWhat);
            SppTransport.Rx rx = SppTransport.sendAndAwait(in, out, frame, ACK_TIMEOUT_MS,
                    (acc, len) -> findFrame(acc, len, cmdId, tid));
            if (rx == null) {
                // Ack semantics are not hardware-confirmed yet; don't report failure on silence.
                Log.w(TAG, "[OPO] no ack for " + logWhat + " (unverified set path)");
            } else {
                Log.i(TAG, "[OPO] ack " + logWhat + " payload=" + HexUtil.hex(payloadOf(rx.buf, rx.start)));
            }
            return Boolean.TRUE;
        }));
    }

    /** Send a GET and return the matched reply frame, or null. */
    private static SppTransport.Rx get(BluetoothAdapter adapter, String mac, DeviceDef def,
                                       DeviceDef.Func f) {
        final int cmdId = Integer.parseInt(f.readCommand.replaceAll("[^0-9a-fA-F]", ""), 16) & 0xFFFF;
        final int tid = nextTransferId(mac);
        final byte[] frame = buildFrame(f.readCommand, null, tid);
        return SppTransport.withSession(adapter, mac, def, null, (in, out) ->
                SppTransport.sendAndAwait(in, out, frame, REPLY_TIMEOUT_MS,
                        (acc, len) -> findFrame(acc, len, cmdId, tid)));
    }

    // ---- apply / read ----

    static boolean applyMode(BluetoothAdapter adapter, String mac, DeviceDef def,
                             DeviceDef.Func f, String optId) {
        if (f == null || f.setCommand == null) return false;
        String valueHex = f.optionValues.get(optId);
        if (valueHex == null) { Log.w(TAG, "[OPO] no option_value for " + optId); return false; }
        String payload = f.payloadTemplate != null
                ? f.payloadTemplate.replace("{mode}", valueHex) : valueHex;
        return set(adapter, mac, def, f, payload, "set " + f.id + " -> " + optId);
    }

    static String readMode(BluetoothAdapter adapter, String mac, DeviceDef def, DeviceDef.Func f) {
        if (f == null || f.readCommand == null || f.stateByteIndex < 0) return null;
        SppTransport.Rx rx = get(adapter, mac, def, f);
        if (rx == null) { Log.w(TAG, "[OPO] no reply for " + f.id); return null; }
        int v = rx.at(f.stateByteIndex);
        if (v < 0) { Log.w(TAG, "[OPO] state_byte_index outside reply for " + f.id); return null; }
        String optId = f.valueMap.get(String.format("%02x", v));
        Log.i(TAG, "[OPO] readMode " + f.id + " val=" + String.format("%02x", v) + " -> " + optId);
        return optId;
    }

    static boolean applyToggle(BluetoothAdapter adapter, String mac, DeviceDef def,
                               DeviceDef.Func f, boolean on) {
        if (f == null || f.setCommand == null) return false;
        String valueHex = f.stateValues.get(on ? "on" : "off");
        if (valueHex == null) { Log.w(TAG, "[OPO] no state_value for " + f.id + " on=" + on); return false; }
        String payload = f.payloadTemplate != null
                ? f.payloadTemplate.replace("{state}", valueHex) : valueHex;
        return set(adapter, mac, def, f, payload, "toggle " + f.id + " -> " + on);
    }

    static Boolean readToggle(BluetoothAdapter adapter, String mac, DeviceDef def, DeviceDef.Func f) {
        if (f == null || f.readCommand == null || f.stateByteIndex < 0 || f.valueMap.isEmpty()) return null;
        String optId = readMode(adapter, mac, def, f);
        return optId == null ? null : "on".equalsIgnoreCase(optId);
    }

    static boolean applyLevel(BluetoothAdapter adapter, String mac, DeviceDef def,
                              DeviceDef.Func f, int value) {
        if (f == null || f.setCommand == null) return false;
        int v = Math.max(f.min, Math.min(f.max, value));
        String valHex = String.format("%02x", v & 0xFF);
        String payload = f.payloadTemplate != null
                ? f.payloadTemplate.replace("{value}", valHex) : valHex;
        return set(adapter, mac, def, f, payload, "level " + f.id + " -> " + v);
    }

    static Integer readLevel(BluetoothAdapter adapter, String mac, DeviceDef def, DeviceDef.Func f) {
        if (f == null || f.readCommand == null || f.stateByteIndex < 0) return null;
        SppTransport.Rx rx = get(adapter, mac, def, f);
        if (rx == null) return null;
        int v = rx.at(f.stateByteIndex);
        return v >= 0 ? v : null;
    }
}
