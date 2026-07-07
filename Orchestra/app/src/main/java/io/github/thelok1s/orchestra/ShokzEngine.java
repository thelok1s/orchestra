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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shokz "shokz_v1" RFCOMM control engine. Verified live on Shokz OpenSwim Pro (btsnoop.pcapng,
 * 2026-07-06). Runs over an SPP/RFCOMM socket (UUID 00001101), same transport as Soundcore, but a
 * completely different framing — hence a separate codec, selected via the channel's
 * {@code protocol.framing = "shokz_v1"} (see {@link ControlEngine#forFunc}).
 *
 * <p><b>Why replay, not reconstruct.</b> shokz_v1 is a multi-variant binary TLV with several
 * incompatible layouts that a single {@code (format, cmd_id, value)} descriptor cannot capture:
 * <ul>
 *   <li>FORMAT A (persistent write, tail {@code 2d}): value region is exactly {@code value_size}
 *       bytes (4 or 8), so total length varies (61 vs 65 B).</li>
 *   <li>FORMAT B "trigger" (play/pause/skip/mp-enable, tail {@code 31}): value inline at [51],
 *       total 56 B, <i>no</i> value_size field.</li>
 *   <li>FORMAT B "typed" (volume, mode→BT): value_size TLV present, value at [55], total 60 B.</li>
 *   <li>Multipoint disable: FORMAT A with the 6-byte host Bluetooth MAC spliced in before the tail.</li>
 * </ul>
 * A per-command 16-bit field (offset 14-15 in A, 13-14 in B) is a checksum/timestamp the device
 * <i>ignores</i> for acceptance (confirmed by the original capture: identical commands were accepted
 * with differing values). Rather than reproduce that taxonomy byte-for-byte, the manifest ships the
 * <b>exact captured frame</b> per option/state ({@code set.frames}) and this engine replays it,
 * patching only (a) a rolling sequence byte, (b) the host MAC for multipoint-disable, and (c) the
 * value bytes for the volume slider (the only control needing values that were not captured).
 *
 * <p><b>State read-back is unsupported.</b> No device→host response layout was confirmed for any
 * command in the capture, so {@link #readMode}/{@link #readToggle}/{@link #readInfo} return
 * {@code null} (unknown) and the UI stays optimistic. If a response format is later reverse-
 * engineered, add it here and populate the manifest {@code read} blocks.
 *
 * <p>Connections are pooled (one reused socket per MAC), with a single reconnect retry per op and
 * idle reaping after {@link #IDLE_CLOSE_MS}.
 */
final class ShokzEngine {
    private static final String TAG = DeviceDef.TAG;
    private static final long IDLE_CLOSE_MS = 8_000;

    // Offsets of the per-command checksum (zeroed when we patch a frame; device ignores it).
    private static final int CKSUM_OFF_A = 14; // FORMAT A: 2 bytes at [14:16]
    private static final int CKSUM_OFF_B = 13; // FORMAT B: 2 bytes at [13:15]
    private static final byte TAIL_A = 0x2d;   // FORMAT A tail — distinguishes A from B for cksum offset

    private static final AtomicInteger SEQ = new AtomicInteger(0x42); // rolling 8-bit seq (byte [2])

    private ShokzEngine() {}

    // ---- session pool ----

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService REAPER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "shokz-reaper");
                t.setDaemon(true);
                return t;
            });
    static {
        REAPER.scheduleWithFixedDelay(ShokzEngine::reap,
                IDLE_CLOSE_MS, IDLE_CLOSE_MS, TimeUnit.MILLISECONDS);
    }

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
                    Log.w(TAG, "[Shokz] session op failed for " + key
                            + " (attempt " + attempt + "): " + e);
                    Logbook.add("[Shokz] " + key + " failed (try " + attempt + "): " + e);
                    closeSession(s);
                }
            }
            return fail;
        }
    }

    private static void ensureOpen(BluetoothAdapter adapter, String mac, DeviceDef def, Session s)
            throws Exception {
        if (s.socket != null && s.socket.isConnected()) return;
        closeSession(s);
        BluetoothDevice device = adapter.getRemoteDevice(mac.toUpperCase());
        UUID uuid = UUID.fromString(def.transportUuid);
        BluetoothSocket sock = def.secure
                ? device.createRfcommSocketToServiceRecord(uuid)
                : device.createInsecureRfcommSocketToServiceRecord(uuid);
        sock.connect();
        s.socket = sock;
        s.in     = sock.getInputStream();
        s.out    = sock.getOutputStream();
        s.lastUsed = System.currentTimeMillis();
        Log.i(TAG, "[Shokz] session opened for " + mac);
        Logbook.add("[Shokz] connected " + mac);
    }

    private static void closeSession(Session s) {
        try { if (s.socket != null) s.socket.close(); } catch (Exception ignored) {}
        s.socket = null; s.in = null; s.out = null;
    }

    private static void reap() {
        long now = System.currentTimeMillis();
        for (Session s : SESSIONS.values()) {
            synchronized (s.lock) {
                if (s.socket != null && now - s.lastUsed > IDLE_CLOSE_MS) {
                    closeSession(s);
                    Log.i(TAG, "[Shokz] idle-closed " + s.mac);
                }
            }
        }
    }

    // ---- frame send ----

    /** Write a fully-formed frame (already MAC/value-patched) with a fresh seq byte, then drain. */
    private static boolean send(BluetoothAdapter adapter, String mac, DeviceDef def,
                                byte[] frame, String logWhat) {
        if (frame == null || frame.length < 3) return false;
        frame[2] = (byte) (SEQ.getAndIncrement() & 0xff); // rolling per-send seq (device ignores value)
        return Boolean.TRUE.equals(withSession(adapter, mac, def, Boolean.FALSE, (in, out) -> {
            Log.i(TAG, "[Shokz] TX " + logWhat + ": " + HexUtil.hex(frame));
            Logbook.add("[Shokz] " + logWhat);
            drain(in);
            out.write(frame);
            out.flush();
            try { Thread.sleep(120); } catch (InterruptedException ignored) {}
            drain(in); // absorb any ACK; we do not parse it (no confirmed response layout)
            return Boolean.TRUE;
        }));
    }

    // ---- apply (multitoggle / list) ----

    static boolean applyMode(BluetoothAdapter adapter, String mac, DeviceDef def,
                             DeviceDef.Func f, String optId) {
        if (f == null) return false;
        String hex = f.frames.get(optId);
        if (hex == null) { Log.w(TAG, "[Shokz] no frame for option " + optId + " on " + f.id); return false; }
        return send(adapter, mac, def, HexUtil.unhex(hex), "set " + f.id + " -> " + optId);
    }

    /** No confirmed read-back layout — state is optimistic. */
    static String readMode(BluetoothAdapter adapter, String mac, DeviceDef def, DeviceDef.Func f) {
        return null;
    }

    // ---- apply (toggle) ----

    static boolean applyToggle(BluetoothAdapter adapter, String mac, DeviceDef def,
                               DeviceDef.Func f, boolean on) {
        if (f == null) return false;
        String hex = f.frames.get(on ? "on" : "off");
        if (hex == null) { Log.w(TAG, "[Shokz] no frame for " + f.id + " on=" + on); return false; }
        byte[] frame = HexUtil.unhex(hex);
        // Multipoint-disable ("off") splices the host Bluetooth MAC into the frame before the tail.
        if (!on && f.hostMacOffsetOff >= 0) {
            byte[] hostMac = hostMacBytes(adapter);
            if (hostMac != null && f.hostMacOffsetOff + 6 <= frame.length) {
                System.arraycopy(hostMac, 0, frame, f.hostMacOffsetOff, 6);
                zeroChecksum(frame);
            } else {
                Log.w(TAG, "[Shokz] " + f.id + ": host MAC unavailable; sending disable frame as-is");
            }
        }
        return send(adapter, mac, def, frame, "toggle " + f.id + " -> " + on);
    }

    static Boolean readToggle(BluetoothAdapter adapter, String mac, DeviceDef def, DeviceDef.Func f) {
        return null; // optimistic; no confirmed read-back layout
    }

    // ---- apply (slider / level) ----

    /**
     * Volume-style slider: patch a u32-LE value into the captured template at the manifest-declared
     * offset. Only the min/max steps were captured live; intermediate steps are best-effort (the
     * checksum is zeroed, which the device ignores).
     */
    static boolean applyLevel(BluetoothAdapter adapter, String mac, DeviceDef def,
                              DeviceDef.Func f, int value) {
        byte[] frame = levelFrame(f, value);
        if (frame == null) { Log.w(TAG, "[Shokz] level unavailable for " + (f == null ? "?" : f.id)); return false; }
        return send(adapter, mac, def, frame, "level " + f.id + " -> " + value);
    }

    /**
     * Build a slider frame by patching a u32-LE {@code value} into the captured template at the
     * manifest-declared offset (checksum zeroed). Package-private for tests. Returns null if the
     * function has no slider template or the offset is out of range.
     */
    static byte[] levelFrame(DeviceDef.Func f, int value) {
        if (f == null || f.frameTemplate == null || f.frameValueOffset < 0) return null;
        byte[] frame = HexUtil.unhex(f.frameTemplate);
        int off = f.frameValueOffset;
        int size = f.frameValueSize > 0 ? f.frameValueSize : 4;
        if (off + size > frame.length) return null;
        for (int i = 0; i < size; i++) frame[off + i] = (byte) ((value >> (8 * i)) & 0xff); // LE
        zeroChecksum(frame);
        return frame;
    }

    static Integer readLevel(BluetoothAdapter adapter, String mac, DeviceDef def, DeviceDef.Func f) {
        return null; // optimistic; no confirmed read-back layout
    }

    // ---- read info ----

    static String readInfo(BluetoothAdapter adapter, String mac, DeviceDef def, DeviceDef.Func f) {
        return null; // no confirmed device->host response layout in the capture
    }

    // ---- raw probe (debug) ----

    /**
     * Send a raw frame (hex) on the device's pooled session and return any reply as hex, or null.
     * Used by {@link DebugSendReceiver} for protocol probing (e.g. hunting a read-back layout).
     */
    static String sendRaw(BluetoothAdapter adapter, String mac, DeviceDef def, String hexFrame) {
        final byte[] frame = HexUtil.unhex(hexFrame);
        return withSession(adapter, mac, def, null, (in, out) -> {
            drain(in);
            out.write(frame);
            out.flush();
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            byte[] acc = new byte[1024];
            int len = 0;
            long deadline = System.currentTimeMillis() + 1500;
            while (System.currentTimeMillis() < deadline && len < acc.length) {
                if (in.available() > 0) {
                    int n = in.read(acc, len, acc.length - len);
                    if (n < 0) break;
                    len += n;
                } else {
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                }
            }
            byte[] reply = new byte[len];
            System.arraycopy(acc, 0, reply, 0, len);
            String hexReply = HexUtil.hex(reply);
            Logbook.add("[Shokz] raw probe -> " + hexReply);
            return hexReply;
        });
    }

    // ---- helpers ----

    /** Zero the 2-byte per-command checksum so a patched frame stays self-consistent (device ignores it). */
    private static void zeroChecksum(byte[] frame) {
        int off = (frame.length > 1 && frame[1] == (byte) 0xff) ? CKSUM_OFF_A : CKSUM_OFF_B;
        if (off + 2 <= frame.length) { frame[off] = 0; frame[off + 1] = 0; }
    }

    /**
     * The local Bluetooth adapter MAC as 6 bytes in on-wire order (matches the captured disable
     * frame: MAC {@code c8:17:ec:7b:33:5c} was spliced as bytes {@code c8 17 ec 7b 33 5c}).
     * Returns null if the address is unavailable or masked ({@code 02:00:00:00:00:00}).
     */
    @SuppressWarnings({"HardwareIds", "deprecation"})
    private static byte[] hostMacBytes(BluetoothAdapter adapter) {
        try {
            String addr = adapter != null ? adapter.getAddress() : null;
            if (addr == null || addr.equalsIgnoreCase("02:00:00:00:00:00")) return null;
            String[] parts = addr.split(":");
            if (parts.length != 6) return null;
            byte[] b = new byte[6];
            for (int i = 0; i < 6; i++) b[i] = (byte) (Integer.parseInt(parts[i], 16) & 0xff);
            return b;
        } catch (Exception e) {
            Log.w(TAG, "[Shokz] host MAC lookup failed: " + e);
            return null;
        }
    }

    private static void drain(InputStream in) throws Exception {
        byte[] junk = new byte[512];
        while (in.available() > 0) {
            if (in.read(junk) <= 0) break;
        }
    }
}
