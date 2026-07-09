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
 * Shared SPP/RFCOMM transport for every framed-over-RFCOMM engine (Soundcore, Shokz, Samsung, Bose).
 * These protocols all run their own binary framing over the same kind of socket — one insecure/secure
 * RFCOMM channel to the device's control UUID — so the socket lifecycle is identical and lives here
 * once, instead of being copy-pasted per engine.
 *
 * <p><b>Persistent, pooled, serialized.</b> One {@link Session} (reused socket) per device MAC, opened
 * on first use and idle-closed after {@link #IDLE_CLOSE_MS}. Every op runs under the session lock so
 * concurrent callers (the provider's single-thread executor, the volume receiver's ad-hoc thread)
 * can never interleave writes on one socket. A dropped socket is detected and one reconnect retried.
 * The pool is keyed by MAC, so a device is driven by exactly one socket regardless of which engine
 * speaks to it.
 */
final class SppTransport {
    private static final String TAG = DeviceDef.TAG;
    private static final long IDLE_CLOSE_MS = 8000;

    private SppTransport() {}

    /** Work to run on a live socket. */
    interface SocketOp<T> {
        T run(InputStream in, OutputStream out) throws Exception;
    }

    /** Locate a complete, validated response in {@code acc[0..len)}; return its start index, or -1. */
    interface ResponseMatcher {
        int match(byte[] acc, int len);
    }

    /** A matched inbound response: read state bytes at offsets relative to {@link #start}. */
    static final class Rx {
        final byte[] buf;
        final int len;
        final int start;
        Rx(byte[] buf, int len, int start) { this.buf = buf; this.len = len; this.start = start; }
        /** Unsigned byte at {@code start + rel}, or -1 if that index is outside the buffer. */
        int at(int rel) {
            int i = start + rel;
            return (i >= 0 && i < len) ? (buf[i] & 0xff) : -1;
        }
    }

    // ---- session pool ----

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService REAPER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "spp-reaper");
                t.setDaemon(true);
                return t;
            });
    static {
        REAPER.scheduleWithFixedDelay(SppTransport::reap,
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

    /**
     * Run {@code op} on the device's pooled socket, opening/reconnecting as needed. Returns
     * {@code fail} if the op can't run after one reconnect attempt. A null/false result returned BY
     * the op is a valid result and is not retried.
     */
    static <T> T withSession(BluetoothAdapter adapter, String mac, DeviceDef def,
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
                    Log.w(TAG, "[SPP] session op failed for " + key + " (attempt " + attempt + "): " + e);
                    Logbook.add("[SPP] " + key + " failed (try " + attempt + "): " + e);
                    closeSession(s);
                }
            }
            return fail;
        }
    }

    /** Caller holds {@code s.lock}. Opens the RFCOMM socket to the device's control UUID if needed. */
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
        s.in = sock.getInputStream();
        s.out = sock.getOutputStream();
        s.lastUsed = System.currentTimeMillis();
        Log.i(TAG, "[SPP] session opened for " + mac);
        Logbook.add("[SPP] connected " + mac);
    }

    private static void closeSession(Session s) {
        try { if (s.socket != null) s.socket.close(); } catch (Exception ignored) {}
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
                    Log.i(TAG, "[SPP] idle-closed " + s.mac);
                }
            }
        }
    }

    /** Discard any buffered inbound bytes so a stale reply can't pollute the next read. */
    static void drain(InputStream in) throws Exception {
        byte[] junk = new byte[256];
        while (in.available() > 0) {
            if (in.read(junk) <= 0) break;
        }
    }

    /**
     * Drain, send {@code frame}, then accumulate inbound bytes until {@code matcher} finds a complete
     * response or {@code timeoutMs} elapses. Returns the matched {@link Rx}, or null on timeout.
     * Centralizes the read loop every engine's read path used to hand-roll (with subtly different,
     * sometimes-missing validation).
     */
    static Rx sendAndAwait(InputStream in, OutputStream out, byte[] frame,
                           long timeoutMs, ResponseMatcher matcher) throws Exception {
        drain(in);
        out.write(frame);
        out.flush();
        byte[] acc = new byte[1024];
        int len = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] buf = new byte[512];
        while (System.currentTimeMillis() < deadline && len < acc.length) {
            if (in.available() > 0) {
                int n = in.read(buf);
                if (n < 0) break;
                int copy = Math.min(n, acc.length - len);
                System.arraycopy(buf, 0, acc, len, copy);
                len += copy;
                int s = matcher.match(acc, len);
                if (s >= 0) return new Rx(acc, len, s);
            } else {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
        }
        int s = matcher.match(acc, len);
        return s >= 0 ? new Rx(acc, len, s) : null;
    }
}
