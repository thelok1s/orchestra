package io.github.thelok1s.orchestra;

import android.bluetooth.BluetoothAdapter;
import android.util.Log;

/**
 * Samsung Galaxy Buds SPP/RFCOMM control engine (framing "samsung_v1"), selected via a channel's
 * {@code protocol.framing}. Runs over the shared {@link SppTransport} socket.
 *
 * <p><b>Wire format</b> (host → buds), matching timschneeb/GalaxyBudsClient {@code SppMessage}:
 * <pre>
 *   [SOM:1] [header:2 LE] [msgId:1] [payload:n] [crc16:2 LE] [EOM:1]
 * </pre>
 * <ul>
 *   <li>{@code header} is a 16-bit little-endian value: bits 0–9 = size, bit 12 = isResponse,
 *       bit 13 = isFragment. A host request sets no flag bits, so the header is just the size.</li>
 *   <li>{@code size} = msgId(1) + payload + crc(2).</li>
 *   <li>{@code crc16} is <b>CRC-16/XMODEM</b> (poly 0x1021, <b>init 0x0000</b>, MSB-first, no
 *       reflection) over {@code msgId + payload}, written little-endian.</li>
 *   <li>{@code SOM}/{@code EOM} vary by model; read from the manifest {@code protocol.som}/{@code eom}
 *       (default 0xFD/0xDD, correct for Buds+/Live/Pro/Buds2/Buds2 Pro).</li>
 * </ul>
 *
 * <p>All Samsung manifests ship {@code _verified:false}: this framing is implemented from the
 * reference client, not confirmed on hardware. The TX CRC must be correct for the buds to accept a
 * command; RX validation checks SOM/EOM/length/msgId but is lenient on CRC so a subtly different
 * device CRC can't silently drop otherwise-valid replies before anyone can verify on hardware.
 */
final class SamsungEngine {
    private static final String TAG = DeviceDef.TAG;
    private static final int DEFAULT_SOM = 0xFD;
    private static final int DEFAULT_EOM = 0xDD;

    private SamsungEngine() {}

    // ---- framing ----

    /** Build a host→buds frame. Package-private + explicit som/eom for tests. */
    static byte[] buildFrame(String msgIdHex, String payloadHex, int som, int eom) {
        byte msgId = HexUtil.unhex(msgIdHex)[0];
        byte[] payload = payloadHex == null ? new byte[0] : HexUtil.unhex(payloadHex);
        int size = 1 + payload.length + 2; // msgId + payload + crc16
        byte[] frame = new byte[1 + 2 + size + 1]; // SOM + header(2) + size + EOM
        int p = 0;
        frame[p++] = (byte) som;
        frame[p++] = (byte) (size & 0xFF);          // header low byte
        frame[p++] = (byte) ((size >> 8) & 0x03);   // header high (10-bit size; host sets no flags)
        int crcStart = p;
        frame[p++] = msgId;
        System.arraycopy(payload, 0, frame, p, payload.length);
        p += payload.length;
        int crc = crc16Xmodem(frame, crcStart, 1 + payload.length); // over msgId + payload
        frame[p++] = (byte) (crc & 0xFF);           // CRC low (little-endian)
        frame[p++] = (byte) ((crc >> 8) & 0xFF);    // CRC high
        frame[p] = (byte) eom;
        return frame;
    }

    /** CRC-16/XMODEM: poly 0x1021, init 0x0000, MSB-first, no reflection, no final xor. */
    static int crc16Xmodem(byte[] data, int off, int len) {
        int crc = 0x0000;
        for (int i = 0; i < len; i++) {
            crc ^= (data[off + i] & 0xFF) << 8;
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x8000) != 0) crc = (crc << 1) ^ 0x1021;
                else crc <<= 1;
            }
        }
        return crc & 0xFFFF;
    }

    /**
     * Locate a complete, structurally-valid response frame in {@code acc[0..len)}: SOM at s, header
     * size fits, msgId matches, EOM present at the size-derived end. Returns s (the SOM index), or -1.
     */
    private static int findFrame(byte[] acc, int len, int som, int eom, int msgId) {
        for (int s = 0; s + 4 <= len; s++) {
            if ((acc[s] & 0xFF) != som) continue;
            int size = (acc[s + 1] & 0xFF) | ((acc[s + 2] & 0x03) << 8); // header LE, 10-bit size
            int end = s + 3 + size;              // EOM index = SOM + header(2) + size... at s+3+size
            if (end >= len) continue;            // full frame not yet buffered
            if ((acc[s + 3] & 0xFF) != msgId) continue;
            if ((acc[end] & 0xFF) != eom) continue;
            return s;
        }
        return -1;
    }

    // ---- send helper ----

    private static boolean send(BluetoothAdapter adapter, String mac, DeviceDef def,
                                byte[] frame, String logWhat) {
        return Boolean.TRUE.equals(SppTransport.withSession(adapter, mac, def, Boolean.FALSE, (in, out) -> {
            Log.i(TAG, "[Samsung] TX " + logWhat + ": " + HexUtil.hex(frame));
            Logbook.add("[Samsung] " + logWhat);
            out.write(frame);
            out.flush();
            try { Thread.sleep(250); } catch (InterruptedException ignored) {}
            SppTransport.drain(in);
            return Boolean.TRUE;
        }));
    }

    // ---- apply / read ----

    static boolean applyMode(BluetoothAdapter adapter, String mac, DeviceDef def,
                             DeviceDef.Func f, String optId) {
        if (f == null || f.setCommand == null) return false;
        String valueHex = f.optionValues.get(optId);
        if (valueHex == null) { Log.w(TAG, "[Samsung] no option_value for " + optId); return false; }
        String payload = f.payloadTemplate != null ? f.payloadTemplate.replace("{mode}", valueHex) : valueHex;
        byte[] frame = buildFrame(f.setCommand, payload,
                def.protocolByte("som", DEFAULT_SOM), def.protocolByte("eom", DEFAULT_EOM));
        return send(adapter, mac, def, frame, "set " + f.id + " -> " + optId);
    }

    static String readMode(BluetoothAdapter adapter, String mac, DeviceDef def, DeviceDef.Func f) {
        if (f == null || f.readCommand == null || f.stateByteIndex < 0) return null;
        final int som = def.protocolByte("som", DEFAULT_SOM);
        final int eom = def.protocolByte("eom", DEFAULT_EOM);
        final int msgId = HexUtil.unhex(f.readCommand)[0] & 0xFF;
        final int sbi = f.stateByteIndex;
        final byte[] frame = buildFrame(f.readCommand, null, som, eom);
        return SppTransport.withSession(adapter, mac, def, null, (in, out) -> {
            SppTransport.Rx rx = SppTransport.sendAndAwait(in, out, frame, 1500,
                    (acc, len) -> findFrame(acc, len, som, eom, msgId));
            if (rx == null) { Log.w(TAG, "[Samsung] no state frame for " + f.id); return null; }
            int v = rx.at(sbi);
            if (v < 0) return null;
            String optId = f.valueMap.get(String.format("%02x", v));
            Log.i(TAG, "[Samsung] readMode " + f.id + " val=" + String.format("%02x", v) + " -> " + optId);
            return optId;
        });
    }

    static boolean applyToggle(BluetoothAdapter adapter, String mac, DeviceDef def,
                               DeviceDef.Func f, boolean on) {
        if (f == null || f.setCommand == null) return false;
        String valueHex = f.stateValues.get(on ? "on" : "off");
        if (valueHex == null) { Log.w(TAG, "[Samsung] no state_value for " + f.id + " on=" + on); return false; }
        String payload = f.payloadTemplate != null ? f.payloadTemplate.replace("{state}", valueHex) : valueHex;
        byte[] frame = buildFrame(f.setCommand, payload,
                def.protocolByte("som", DEFAULT_SOM), def.protocolByte("eom", DEFAULT_EOM));
        return send(adapter, mac, def, frame, "toggle " + f.id + " -> " + on);
    }

    static Boolean readToggle(BluetoothAdapter adapter, String mac, DeviceDef def, DeviceDef.Func f) {
        if (f == null || f.readCommand == null || f.stateByteIndex < 0 || f.valueMap.isEmpty()) return null;
        String optId = readMode(adapter, mac, def, f);
        if (optId == null) return null;
        return "on".equalsIgnoreCase(optId);
    }

    static boolean applyLevel(BluetoothAdapter adapter, String mac, DeviceDef def,
                              DeviceDef.Func f, int value) {
        if (f == null || f.setCommand == null) return false;
        String valHex = String.format("%02x", value & 0xff);
        String payload = f.payloadTemplate != null ? f.payloadTemplate.replace("{value}", valHex) : valHex;
        byte[] frame = buildFrame(f.setCommand, payload,
                def.protocolByte("som", DEFAULT_SOM), def.protocolByte("eom", DEFAULT_EOM));
        return send(adapter, mac, def, frame, "level " + f.id + " -> " + value);
    }

    static Integer readLevel(BluetoothAdapter adapter, String mac, DeviceDef def, DeviceDef.Func f) {
        if (f == null || f.readCommand == null || f.stateByteIndex < 0) return null;
        final int som = def.protocolByte("som", DEFAULT_SOM);
        final int eom = def.protocolByte("eom", DEFAULT_EOM);
        final int msgId = HexUtil.unhex(f.readCommand)[0] & 0xFF;
        final int sbi = f.stateByteIndex;
        final byte[] frame = buildFrame(f.readCommand, null, som, eom);
        return SppTransport.withSession(adapter, mac, def, null, (in, out) -> {
            SppTransport.Rx rx = SppTransport.sendAndAwait(in, out, frame, 1500,
                    (acc, len) -> findFrame(acc, len, som, eom, msgId));
            if (rx == null) return null;
            int v = rx.at(sbi);
            return v >= 0 ? v : null;
        });
    }
}
