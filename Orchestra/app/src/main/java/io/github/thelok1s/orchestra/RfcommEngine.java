package io.github.thelok1s.orchestra;

import android.bluetooth.BluetoothAdapter;
import android.util.Log;

import java.util.Map;

/**
 * Soundcore-style RFCOMM control. Framing (soundcore_v1), verified live on Space One Pro:
 *   host->device: 08 ee 00 00 00 <cmd:2> <len:2 LE total> <payload..> <crc(sum8)>
 *   device->host: 09 ff 00 00 01 <cmd:2> <len:2 LE> <payload..> <crc>
 * len = total packet byte count (LE); crc = sum(all preceding bytes) & 0xff.
 *
 * <p>The socket lifecycle (one pooled, serialized, reconnecting control socket per device) lives in
 * {@link SppTransport}, shared with the other framed-over-RFCOMM engines (Shokz/Samsung/Bose).
 */
final class RfcommEngine {
    private static final String TAG = DeviceDef.TAG;
    private static final byte[] CMD_PREFIX = {0x08, (byte) 0xee, 0x00, 0x00, 0x00};
    private static final byte[] RESP_PREFIX = {0x09, (byte) 0xff, 0x00, 0x00, 0x01};

    private RfcommEngine() {}

    // ---- framing ----

    /** Build a host->device frame from a 2-byte command + payload (both hex). */
    static byte[] buildFrame(String cmdHex, String payloadHex) {
        byte[] cmd = unhex(cmdHex);
        byte[] payload = payloadHex == null ? new byte[0] : unhex(payloadHex);
        int total = CMD_PREFIX.length + cmd.length + 2 + payload.length + 1; // +len(2)+crc(1)
        byte[] frame = new byte[total];
        int p = 0;
        System.arraycopy(CMD_PREFIX, 0, frame, p, CMD_PREFIX.length); p += CMD_PREFIX.length;
        System.arraycopy(cmd, 0, frame, p, cmd.length); p += cmd.length;
        frame[p++] = (byte) (total & 0xff);
        frame[p++] = (byte) ((total >> 8) & 0xff);
        System.arraycopy(payload, 0, frame, p, payload.length); p += payload.length;
        int sum = 0;
        for (int i = 0; i < p; i++) sum += (frame[i] & 0xff);
        frame[p] = (byte) (sum & 0xff);
        return frame;
    }

    // ---- set / read (multitoggle) ----

    /** Send a set command for the chosen option id of the default (ANC) function. */
    static boolean applyMode(BluetoothAdapter adapter, String mac, DeviceDef def, String optId) {
        return applyMode(adapter, mac, def, def.soundMode, optId);
    }

    /** Send a set command for the chosen option id of a SPECIFIC function. */
    static boolean applyMode(BluetoothAdapter adapter, String mac, DeviceDef def,
                             DeviceDef.Func f, String optId) {
        if (f == null || f.setCommand == null) return false;
        String valueHex = f.optionValues.get(optId);
        if (valueHex == null) { Log.w(TAG, "no option_value for " + optId); return false; }
        String payload = f.payloadTemplate.replace("{mode}", valueHex);
        final byte[] frame = buildFrame(f.setCommand, payload);
        return Boolean.TRUE.equals(SppTransport.withSession(adapter, mac, def, Boolean.FALSE, (in, out) -> {
            Log.i(TAG, "TX set " + optId + ": " + hex(frame));
            Logbook.add("set " + f.id + " → " + optId + "  (" + hex(frame) + ")");
            out.write(frame);
            out.flush();
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            SppTransport.drain(in); // consume the ACK so it doesn't leak into a later read
            return Boolean.TRUE;
        }));
    }

    /**
     * Debug/test: send an arbitrary command + payload (both hex) on the device's pooled socket and
     * read back any reply (returned as hex, or null). Used by {@link DebugSendReceiver} and the
     * in-app console to probe composite/unverified control sequences.
     */
    static String sendRaw(BluetoothAdapter adapter, String mac, DeviceDef def,
                          String cmdHex, String payloadHex) {
        final byte[] frame = buildFrame(cmdHex, payloadHex);
        return SppTransport.withSession(adapter, mac, def, null, (in, out) -> {
            SppTransport.drain(in);
            Log.i(TAG, "TX raw " + cmdHex + " " + (payloadHex == null ? "" : payloadHex) + ": " + hex(frame));
            out.write(frame);
            out.flush();
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            byte[] buf = new byte[1024];
            int len = 0;
            long deadline = System.currentTimeMillis() + 1500;
            byte[] tmp = new byte[512];
            while (System.currentTimeMillis() < deadline && len < buf.length) {
                if (in.available() > 0) {
                    int n = in.read(tmp);
                    if (n < 0) break;
                    int copy = Math.min(n, buf.length - len);
                    System.arraycopy(tmp, 0, buf, len, copy);
                    len += copy;
                } else {
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                }
            }
            byte[] reply = new byte[len];
            System.arraycopy(buf, 0, reply, 0, len);
            String hexReply = hex(reply);
            Log.i(TAG, "RX raw (" + len + "B): " + hexReply);
            Logbook.add("probe " + cmdHex + " → " + hexReply);
            return hexReply;
        });
    }

    /** Read the current mode option id of the default (ANC) function, or null on failure. */
    static String readMode(BluetoothAdapter adapter, String mac, DeviceDef def) {
        return readMode(adapter, mac, def, def.soundMode);
    }

    /** Read the current mode option id of a SPECIFIC function, or null on failure. */
    static String readMode(BluetoothAdapter adapter, String mac, DeviceDef def, DeviceDef.Func f) {
        if (f == null || f.readCommand == null) return null;
        final boolean useMatch = !f.readMatch.isEmpty();
        final int needIdx = useMatch ? maxMatchIndex(f) : f.stateByteIndex;
        final String readCommand = f.readCommand;
        final byte[] frame = buildFrame(readCommand, null);
        return SppTransport.withSession(adapter, mac, def, null, (in, out) -> {
            SppTransport.Rx rx = SppTransport.sendAndAwait(in, out, frame, 2000, (acc, len) -> {
                int s = findResponseStart(acc, len, readCommand);
                return (s >= 0 && s + needIdx < len) ? s : -1; // packet + the bytes we need have arrived
            });
            if (rx == null) { Log.w(TAG, "no state packet"); return null; }
            String optId;
            if (useMatch) {
                optId = matchOption(rx.buf, rx.len, rx.start, f);
                Log.i(TAG, "readMode (match) -> " + optId);
            } else {
                int idx = rx.start + f.stateByteIndex;
                if (idx >= rx.len) { Log.w(TAG, "state byte beyond packet"); return null; }
                String valHex = String.format("%02x", rx.buf[idx] & 0xff);
                optId = f.valueMap.get(valHex);
                Log.i(TAG, "readMode value=" + valHex + " -> " + optId);
            }
            return optId;
        });
    }

    // ---- set / read (level / slider) ----

    /**
     * Set a single-value level/slider control: substitute the integer {@code value} (as one hex
     * byte) into the function's {@code payload_template} ({@code {value}} placeholder; if there is
     * no template the value byte is the whole payload) and send it framed.
     *
     * <p>Handles SINGLE-VALUE controls only (a plain 0..N level). Composite Soundcore sliders — an
     * 8-band EQ ({@code {value}} carrying 8 gains), or an ANC level that is one byte inside the
     * shared {@code sound_mode} packet — are not single integers and are intentionally not driven
     * here; those need their own per-control encoding. Returns false if there is no set command.
     */
    static boolean applyLevel(BluetoothAdapter adapter, String mac, DeviceDef def,
                              DeviceDef.Func f, int value) {
        final byte[] frame = levelFrame(f, value);
        if (frame == null) return false;
        return Boolean.TRUE.equals(SppTransport.withSession(adapter, mac, def, Boolean.FALSE, (in, out) -> {
            Log.i(TAG, "TX level " + f.id + "=" + value + ": " + hex(frame));
            Logbook.add("level " + f.id + " → " + value + "  (" + hex(frame) + ")");
            out.write(frame);
            out.flush();
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            SppTransport.drain(in);
            return Boolean.TRUE;
        }));
    }

    /**
     * Build the soundcore_v1 frame for a single-value level set (value as one hex byte substituted
     * into {@code payload_template}'s {@code {value}} placeholder). Package-private for tests.
     * Returns null if the function has no set command.
     */
    static byte[] levelFrame(DeviceDef.Func f, int value) {
        if (f == null || f.setCommand == null) return null;
        String valueHex = String.format("%02x", value & 0xff);
        String template = f.payloadTemplate != null ? f.payloadTemplate : "{value}";
        String payload = template.replace("{value}", valueHex);
        return buildFrame(f.setCommand, payload);
    }

    /**
     * Read a single-value level/slider control's current value (the state byte at
     * {@code stateByteIndex}), or null if it can't be determined. Mirrors {@link #readMode}'s framing.
     */
    static Integer readLevel(BluetoothAdapter adapter, String mac, DeviceDef def, DeviceDef.Func f) {
        if (f == null || f.readCommand == null || f.stateByteIndex < 0) return null;
        final String readCommand = f.readCommand;
        final int sbi = f.stateByteIndex;
        final byte[] frame = buildFrame(readCommand, null);
        return SppTransport.withSession(adapter, mac, def, null, (in, out) -> {
            SppTransport.Rx rx = SppTransport.sendAndAwait(in, out, frame, 2000, (acc, len) -> {
                int s = findResponseStart(acc, len, readCommand);
                return (s >= 0 && s + sbi < len) ? s : -1;
            });
            if (rx == null) return null;
            int v = rx.at(sbi);
            return v >= 0 ? v : null;
        });
    }

    // ---- set / read (toggle) ----

    /** Send a boolean toggle for a SPECIFIC switch function (uses state_values on/off + {state}). */
    static boolean applyToggle(BluetoothAdapter adapter, String mac, DeviceDef def,
                               DeviceDef.Func f, boolean on) {
        if (f == null || f.setCommand == null) return false;
        String valueHex = f.stateValues.get(on ? "on" : "off");
        if (valueHex == null) { Log.w(TAG, "no state_value for " + f.id + " on=" + on); return false; }
        String template = f.payloadTemplate != null ? f.payloadTemplate : "{state}";
        String payload = template.replace("{state}", valueHex);
        final byte[] frame = buildFrame(f.setCommand, payload);
        return Boolean.TRUE.equals(SppTransport.withSession(adapter, mac, def, Boolean.FALSE, (in, out) -> {
            Log.i(TAG, "TX toggle " + f.id + "=" + on + ": " + hex(frame));
            Logbook.add("toggle " + f.id + " → " + (on ? "on" : "off") + "  (" + hex(frame) + ")");
            out.write(frame);
            out.flush();
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            SppTransport.drain(in);
            return Boolean.TRUE;
        }));
    }

    /**
     * Read a switch function's current state, or null if it can't be determined (no state byte /
     * unverified read). The caller keeps the optimistic value when this returns null.
     */
    static Boolean readToggle(BluetoothAdapter adapter, String mac, DeviceDef def, DeviceDef.Func f) {
        if (f == null || f.readCommand == null || f.stateByteIndex < 0 || f.valueMap.isEmpty()) {
            return null; // unknown — most unverified toggles land here
        }
        String optId = readMode(adapter, mac, def, f); // value_map maps byte -> "on"/"off"
        if (optId == null) return null;
        return "on".equalsIgnoreCase(optId);
    }

    /** Absolute index where the response packet for {@code readCommand} starts, or -1. */
    private static int findResponseStart(byte[] acc, int len, String readCommand) {
        byte[] cmd = unhex(readCommand);
        for (int s = 0; s + RESP_PREFIX.length + cmd.length <= len; s++) {
            boolean match = true;
            for (int i = 0; i < RESP_PREFIX.length; i++) {
                if (acc[s + i] != RESP_PREFIX[i]) { match = false; break; }
            }
            if (!match) continue;
            for (int i = 0; i < cmd.length; i++) {
                if (acc[s + RESP_PREFIX.length + i] != cmd[i]) { match = false; break; }
            }
            if (match) return s;
        }
        return -1;
    }

    /** Highest byte offset referenced by any match rule (so we know when enough has arrived). */
    private static int maxMatchIndex(DeviceDef.Func f) {
        int max = 0;
        for (DeviceDef.ReadMatch rm : f.readMatch)
            for (int k : rm.bytes.keySet()) max = Math.max(max, k);
        return max;
    }

    /** Evaluate multi-byte match rules against the response at {@code s}; first full match wins. */
    private static String matchOption(byte[] acc, int len, int s, DeviceDef.Func f) {
        for (DeviceDef.ReadMatch rm : f.readMatch) {
            boolean all = true;
            for (Map.Entry<Integer, String> e : rm.bytes.entrySet()) {
                int idx = s + e.getKey();
                if (idx >= len || !String.format("%02x", acc[idx] & 0xff).equals(e.getValue())) {
                    all = false; break;
                }
            }
            if (all) return rm.option;
        }
        return null;
    }

    static byte[] unhex(String s) { return HexUtil.unhex(s); }

    static String hex(byte[] a) { return HexUtil.hex(a); }
}
