package io.github.thelok1s.orchestra;

import android.bluetooth.BluetoothAdapter;
import android.util.Log;

/**
 * Bose BMAP (Bose Mobile Application Protocol) SPP/RFCOMM control engine (framing "bose_v1"),
 * selected via a channel's {@code protocol.framing}. Runs over the shared {@link SppTransport}.
 *
 * <p><b>Wire format</b>: {@code [functionBlock:1] [function:1] [operator:1] [payloadLength:1] [payload:n]}.
 * There is no CRC or start/end marker. Manifest commands are {@code "block:function:operator"} in hex,
 * e.g. {@code "01:06:02"} = set ANC ({@code SET}=0x02), read {@code "01:06:01"} ({@code GET}=0x01).
 * The reply carries the same block+function with a result operator (commonly {@code STATUS}=0x03),
 * given per function by the manifest {@code read.response_command}.
 *
 * <p>All Bose manifests ship {@code _verified:false}: this is implemented from the BoseConnect
 * reference, not confirmed on hardware.
 */
final class BoseEngine {
    private static final String TAG = DeviceDef.TAG;
    private static final int OP_SET = 0x02; // default write operator when a command omits one

    private BoseEngine() {}

    // ---- framing ----

    /** Parse a "block:function:operator" (or 2-/3-byte hex) command into its 3 header bytes. */
    private static int[] parseCommand(String cmdStr, int defaultOperator) {
        int block = 0, function = 0, operator = defaultOperator;
        if (cmdStr.contains(":")) {
            String[] parts = cmdStr.split(":");
            if (parts.length > 0 && !parts[0].isEmpty()) block = Integer.parseInt(parts[0], 16) & 0xFF;
            if (parts.length > 1) function = Integer.parseInt(parts[1], 16) & 0xFF;
            if (parts.length > 2) operator = Integer.parseInt(parts[2], 16) & 0xFF; // else keep default
        } else {
            byte[] raw = HexUtil.unhex(cmdStr);
            if (raw.length >= 1) block = raw[0] & 0xFF;
            if (raw.length >= 2) function = raw[1] & 0xFF;
            if (raw.length >= 3) operator = raw[2] & 0xFF;
        }
        return new int[]{block, function, operator};
    }

    /** Build a BMAP frame from a "block:function:operator" command + hex payload. Package-private for tests. */
    static byte[] buildFrame(String cmdStr, String payloadHex) {
        int[] h = parseCommand(cmdStr, OP_SET);
        byte[] payload = payloadHex == null ? new byte[0] : HexUtil.unhex(payloadHex);
        byte[] frame = new byte[4 + payload.length];
        frame[0] = (byte) h[0];
        frame[1] = (byte) h[1];
        frame[2] = (byte) h[2];
        frame[3] = (byte) (payload.length & 0xFF);
        System.arraycopy(payload, 0, frame, 4, payload.length);
        return frame;
    }

    /**
     * Locate a reply for {@code respCmd} (or, if null, {@code reqCmd}'s block+function with any
     * operator other than a bare SET echo) in {@code acc[0..len)}, validating the length byte.
     * Returns the frame start index, or -1. Package-private for tests.
     */
    static int findResponse(byte[] acc, int len, String reqCmd, String respCmd) {
        int block, function, operator;
        boolean matchOperator;
        if (respCmd != null) {
            int[] h = parseCommand(respCmd, -1);
            block = h[0]; function = h[1]; operator = h[2];
            matchOperator = operator >= 0;
        } else {
            int[] h = parseCommand(reqCmd, -1);
            block = h[0]; function = h[1]; operator = -1; matchOperator = false;
        }
        for (int s = 0; s + 4 <= len; s++) {
            if ((acc[s] & 0xFF) != block || (acc[s + 1] & 0xFF) != function) continue;
            int op = acc[s + 2] & 0xFF;
            if (matchOperator ? (op != operator) : (op == OP_SET)) continue; // skip our own SET echo
            int payLen = acc[s + 3] & 0xFF;
            if (s + 4 + payLen > len) continue; // declared payload not fully buffered
            return s;
        }
        return -1;
    }

    // ---- send helper ----

    private static boolean send(BluetoothAdapter adapter, String mac, DeviceDef def,
                                byte[] frame, String logWhat) {
        return Boolean.TRUE.equals(SppTransport.withSession(adapter, mac, def, Boolean.FALSE, (in, out) -> {
            Log.i(TAG, "[Bose] TX " + logWhat + ": " + HexUtil.hex(frame));
            Logbook.add("[Bose] " + logWhat);
            out.write(frame);
            out.flush();
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            SppTransport.drain(in);
            return Boolean.TRUE;
        }));
    }

    // ---- apply / read ----

    static boolean applyMode(BluetoothAdapter adapter, String mac, DeviceDef def,
                             DeviceDef.Func f, String optId) {
        if (f == null || f.setCommand == null) return false;
        String valueHex = f.optionValues.get(optId);
        if (valueHex == null) { Log.w(TAG, "[Bose] no option_value for " + optId); return false; }
        String payload = f.payloadTemplate != null ? f.payloadTemplate.replace("{mode}", valueHex) : valueHex;
        return send(adapter, mac, def, buildFrame(f.setCommand, payload), "set " + f.id + " -> " + optId);
    }

    static String readMode(BluetoothAdapter adapter, String mac, DeviceDef def, DeviceDef.Func f) {
        if (f == null || f.readCommand == null || f.stateByteIndex < 0) return null;
        final String reqCmd = f.readCommand;
        final String respCmd = f.responseCommand;
        final int sbi = f.stateByteIndex;
        final byte[] frame = buildFrame(reqCmd, null);
        return SppTransport.withSession(adapter, mac, def, null, (in, out) -> {
            SppTransport.Rx rx = SppTransport.sendAndAwait(in, out, frame, 1500,
                    (acc, len) -> findResponse(acc, len, reqCmd, respCmd));
            if (rx == null) { Log.w(TAG, "[Bose] no state frame for " + f.id); return null; }
            int v = rx.at(sbi);
            if (v < 0) return null;
            String optId = f.valueMap.get(String.format("%02x", v));
            Log.i(TAG, "[Bose] readMode " + f.id + " val=" + String.format("%02x", v) + " -> " + optId);
            return optId;
        });
    }

    static boolean applyToggle(BluetoothAdapter adapter, String mac, DeviceDef def,
                               DeviceDef.Func f, boolean on) {
        if (f == null || f.setCommand == null) return false;
        String valueHex = f.stateValues.get(on ? "on" : "off");
        if (valueHex == null) { Log.w(TAG, "[Bose] no state_value for " + f.id + " on=" + on); return false; }
        String payload = f.payloadTemplate != null ? f.payloadTemplate.replace("{state}", valueHex) : valueHex;
        return send(adapter, mac, def, buildFrame(f.setCommand, payload), "toggle " + f.id + " -> " + on);
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
        return send(adapter, mac, def, buildFrame(f.setCommand, payload), "level " + f.id + " -> " + value);
    }

    static Integer readLevel(BluetoothAdapter adapter, String mac, DeviceDef def, DeviceDef.Func f) {
        if (f == null || f.readCommand == null || f.stateByteIndex < 0) return null;
        final String reqCmd = f.readCommand;
        final String respCmd = f.responseCommand;
        final int sbi = f.stateByteIndex;
        final byte[] frame = buildFrame(reqCmd, null);
        return SppTransport.withSession(adapter, mac, def, null, (in, out) -> {
            SppTransport.Rx rx = SppTransport.sendAndAwait(in, out, frame, 1500,
                    (acc, len) -> findResponse(acc, len, reqCmd, respCmd));
            if (rx == null) return null;
            int v = rx.at(sbi);
            return v >= 0 ? v : null;
        });
    }
}
