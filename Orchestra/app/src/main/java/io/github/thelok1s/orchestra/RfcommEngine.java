package io.github.thelok1s.orchestra;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Soundcore-style RFCOMM control. Framing (soundcore_v1), verified live on Space One Pro:
 *   host->device: 08 ee 00 00 00 <cmd:2> <len:2 LE total> <payload..> <crc(sum8)>
 *   device->host: 09 ff 00 00 01 <cmd:2> <len:2 LE> <payload..> <crc>
 * len = total packet byte count (LE); crc = sum(all preceding bytes) & 0xff.
 *
 * <p><b>Persistent control socket.</b> Each device keeps one pooled {@link Session} (opened on first
 * use, reused across set/read ops, idle-closed after {@link #IDLE_CLOSE_MS}). Every op runs under the
 * session's lock so the two callers (SettingProviderService's single-thread executor and
 * VolumeApplyReceiver's ad-hoc thread) can never interleave writes on one socket. A stale/dropped
 * socket is detected and one reconnect is retried — this removes the per-op connect that used to
 * fail when the device was mid-connection and added ~1s of latency to every tap.
 */
final class RfcommEngine {
    private static final String TAG = DeviceDef.TAG;
    private static final byte[] CMD_PREFIX = {0x08, (byte) 0xee, 0x00, 0x00, 0x00};
    private static final byte[] RESP_PREFIX = {0x09, (byte) 0xff, 0x00, 0x00, 0x01};
    private static final long IDLE_CLOSE_MS = 8000;

    private RfcommEngine() {}

    // ---- persistent session pool ----

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService REAPER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "rfcomm-reaper");
                t.setDaemon(true);
                return t;
            });
    static {
        REAPER.scheduleWithFixedDelay(RfcommEngine::reap,
                IDLE_CLOSE_MS, IDLE_CLOSE_MS, TimeUnit.MILLISECONDS);
    }

    /** One device's reused control socket. All access is under {@link #lock}. */
    private static final class Session {
        final String mac;
        final Object lock = new Object();
        BluetoothSocket socket;
        InputStream in;
        OutputStream out;
        long lastUsed;
        Session(String mac) { this.mac = mac; }
    }

    private interface SocketOp<T> {
        T run(InputStream in, OutputStream out) throws Exception;
    }

    /**
     * Run {@code op} on the device's pooled socket, opening/reconnecting as needed. Returns
     * {@code fail} if the op can't run (e.g. device unreachable) after one reconnect attempt.
     * A null/false-style result returned BY the op is a valid result and is not retried.
     */
    private static <T> T withSession(BluetoothAdapter adapter, String mac, DeviceDef def,
                                     T fail, SocketOp<T> op) {
        String key = mac.toUpperCase();
        Session s = SESSIONS.computeIfAbsent(key, Session::new);
        synchronized (s.lock) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    ensureOpen(adapter, mac, def, s);
                    T result = op.run(s.in, s.out);
                    s.lastUsed = System.currentTimeMillis();
                    return result;
                } catch (Exception e) {
                    Log.w(TAG, "session op failed for " + key + " (attempt " + attempt + "): " + e);
                    Logbook.add("RFCOMM " + key + " failed (try " + attempt + "): " + e);
                    closeSession(s); // discard; the next attempt reconnects fresh
                }
            }
            return fail;
        }
    }

    /** Caller must hold {@code s.lock}. Opens the socket if absent/disconnected. */
    private static void ensureOpen(BluetoothAdapter adapter, String mac, DeviceDef def, Session s)
            throws Exception {
        if (s.socket != null && s.socket.isConnected()) return;
        closeSession(s);
        BluetoothSocket sock = connect(adapter, mac, def);
        s.socket = sock;
        s.in = sock.getInputStream();
        s.out = sock.getOutputStream();
        s.lastUsed = System.currentTimeMillis();
        Log.i(TAG, "session opened for " + mac);
        Logbook.add("RFCOMM connected " + mac);
    }

    /** Caller must hold {@code s.lock}. */
    private static void closeSession(Session s) {
        closeQuietly(s.socket);
        s.socket = null;
        s.in = null;
        s.out = null;
    }

    private static void reap() {
        long now = System.currentTimeMillis();
        for (Session s : SESSIONS.values()) {
            synchronized (s.lock) {
                if (s.socket != null && now - s.lastUsed > IDLE_CLOSE_MS) {
                    closeSession(s);
                    Log.i(TAG, "session idle-closed for " + s.mac);
                }
            }
        }
    }

    /** Discard any buffered inbound bytes so a stale ACK can't pollute the next read. */
    private static void drain(InputStream in) throws Exception {
        byte[] junk = new byte[256];
        while (in.available() > 0) {
            if (in.read(junk) <= 0) break;
        }
    }

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

    /** Open an (insecure) RFCOMM socket to the device's control UUID. */
    static BluetoothSocket connect(BluetoothAdapter adapter, String mac, DeviceDef def) throws Exception {
        BluetoothDevice device = adapter.getRemoteDevice(mac.toUpperCase());
        UUID uuid = UUID.fromString(def.transportUuid);
        BluetoothSocket socket = def.secure
                ? device.createRfcommSocketToServiceRecord(uuid)
                : device.createInsecureRfcommSocketToServiceRecord(uuid);
        socket.connect();
        return socket;
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
        return Boolean.TRUE.equals(withSession(adapter, mac, def, Boolean.FALSE, (in, out) -> {
            Log.i(TAG, "TX set " + optId + ": " + hex(frame));
            Logbook.add("set " + f.id + " → " + optId + "  (" + hex(frame) + ")");
            out.write(frame);
            out.flush();
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            drain(in); // consume the ACK so it doesn't leak into a later read
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
        return withSession(adapter, mac, def, null, (in, out) -> {
            drain(in);
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
        final byte[] frame = buildFrame(f.readCommand, null);
        return withSession(adapter, mac, def, null, (in, out) -> {
            drain(in); // clear any stale bytes before issuing our request
            out.write(frame);
            out.flush();

            byte[] acc = new byte[1024];
            int len = 0;
            long deadline = System.currentTimeMillis() + 2000;
            byte[] buf = new byte[512];
            while (System.currentTimeMillis() < deadline && len < acc.length) {
                if (in.available() > 0) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    int copy = Math.min(n, acc.length - len);
                    System.arraycopy(buf, 0, acc, len, copy);
                    len += copy;
                    int s = findResponseStart(acc, len, f.readCommand);
                    if (s >= 0 && s + needIdx < len) break; // got the packet + the bytes we need
                } else {
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                }
            }
            int s = findResponseStart(acc, len, f.readCommand);
            if (s < 0) { Log.w(TAG, "no state packet (got " + len + " bytes)"); return null; }
            String optId;
            if (useMatch) {
                optId = matchOption(acc, len, s, f);
                Log.i(TAG, "readMode (match) -> " + optId);
            } else {
                int idx = s + f.stateByteIndex;
                if (idx >= len) { Log.w(TAG, "state byte beyond packet"); return null; }
                String valHex = String.format("%02x", acc[idx] & 0xff);
                optId = f.valueMap.get(valHex);
                Log.i(TAG, "readMode value=" + valHex + " -> " + optId);
            }
            return optId;
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
        return Boolean.TRUE.equals(withSession(adapter, mac, def, Boolean.FALSE, (in, out) -> {
            Log.i(TAG, "TX toggle " + f.id + "=" + on + ": " + hex(frame));
            Logbook.add("toggle " + f.id + " → " + (on ? "on" : "off") + "  (" + hex(frame) + ")");
            out.write(frame);
            out.flush();
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            drain(in);
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

    /** Locate the response packet for the read command and return absolute index of the state byte. */
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

    private static int findStateByte(byte[] acc, int len, DeviceDef.Func f) {
        int s = findResponseStart(acc, len, f.readCommand);
        if (s < 0) return -1;
        int idx = s + f.stateByteIndex;
        return idx < len ? idx : -1;
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

    private static void closeQuietly(BluetoothSocket s) {
        try { if (s != null) s.close(); } catch (Exception ignored) {}
    }
}
