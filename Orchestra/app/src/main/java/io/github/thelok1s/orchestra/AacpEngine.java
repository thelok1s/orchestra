package io.github.thelok1s.orchestra;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.ParcelUuid;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AirPods AAP control over an L2CAP socket (PSM 4097). Mirrors {@link RfcommEngine}'s pooled,
 * lock-guarded session, but the socket stays open for the session and a reader thread pushes
 * inbound notifications into {@link AapState} (AAP is a push protocol). Framing is delegated to
 * {@link AapCodec}. Per-device option values come from the manifest ({@link DeviceDef.Func}).
 *
 * Plan 1 scope: bring-up + noise-control (ANC) set/read. Battery / ear-detection / toggles / the
 * provider listener arrive in later plans.
 */
final class AacpEngine {
    private static final String TAG = DeviceDef.TAG;
    private static final int PSM = 4097, TYPE_L2CAP = 3;
    private static final ParcelUuid AAP_UUID =
            ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a");

    private AacpEngine() {}

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private static final class Session {
        final String mac;
        final Object lock = new Object();
        BluetoothSocket socket;
        InputStream in;
        OutputStream out;
        Thread reader;
        volatile boolean running;
        Session(String mac) { this.mac = mac; }
    }

    /** Open + bring up the session and start the reader if not already running. Idempotent.
     *  No DeviceDef needed — PSM/UUID are protocol constants. */
    static void ensureConnected(BluetoothAdapter adapter, String mac) {
        String key = mac.toUpperCase(Locale.ROOT);
        Session s = SESSIONS.computeIfAbsent(key, Session::new);
        synchronized (s.lock) {
            if (s.socket != null && s.socket.isConnected()
                    && s.reader != null && s.reader.isAlive()) return;
            close(s);
            try {
                s.socket = createL2capSocket(adapter, mac);
                s.socket.connect();
                s.in = s.socket.getInputStream();
                s.out = s.socket.getOutputStream();
                // bring-up: handshake -> feature-flags -> notification-request
                s.out.write(AapCodec.handshake());
                s.out.write(AapCodec.setFeatureFlags());
                s.out.write(AapCodec.notificationRequest());
                s.out.flush();
                startReader(s);
                Log.i(TAG, "AACP connected + brought up " + key);
                Logbook.add("AACP connected " + key);
            } catch (Exception e) {
                Log.w(TAG, "AACP connect failed for " + key + ": " + e);
                Logbook.add("AACP " + key + " connect failed: " + e);
                close(s);
            }
        }
    }

    private static void startReader(Session s) {
        s.running = true;
        s.reader = new Thread(() -> {
            byte[] buf = new byte[1024];
            AapState state = AapState.forMac(s.mac);
            final java.io.InputStream in = s.in;
            if (in == null) return;
            try {
                while (s.running) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    Integer mode = AapCodec.parseAncMode(buf, n);
                    if (mode != null) {
                        state.setAncMode(mode);
                        Log.i(TAG, "AACP notify ANC mode=" + mode + " for " + s.mac);
                    }
                }
            } catch (Exception e) {
                Log.i(TAG, "AACP reader ended for " + s.mac + ": " + e);
            }
        }, "aacp-reader-" + s.mac);
        s.reader.setDaemon(true);
        s.reader.start();
    }

    /** Reflected L2CAP BluetoothSocket constructor (no public API). Tries known signatures. */
    private static BluetoothSocket createL2capSocket(BluetoothAdapter adapter, String mac)
            throws Exception {
        BluetoothDevice dev = adapter.getRemoteDevice(mac.toUpperCase(Locale.ROOT));
        Object[][] specs = {
            {adapter, dev, TYPE_L2CAP, true, true, PSM, AAP_UUID},
            {dev, TYPE_L2CAP, true, true, PSM, AAP_UUID},
            {dev, TYPE_L2CAP, 1, true, true, PSM, AAP_UUID},
            {TYPE_L2CAP, 1, true, true, dev, PSM, AAP_UUID},
            {TYPE_L2CAP, true, true, dev, PSM, AAP_UUID},
        };
        Exception last = null;
        int idx = 0;
        for (Object[] args : specs) {
            try {
                Class<?>[] types = new Class<?>[args.length];
                for (int i = 0; i < args.length; i++) {
                    Object a = args[i];
                    if (a instanceof Integer) types[i] = int.class;
                    else if (a instanceof Boolean) types[i] = boolean.class;
                    else if (a instanceof BluetoothAdapter) types[i] = BluetoothAdapter.class;
                    else if (a instanceof BluetoothDevice) types[i] = BluetoothDevice.class;
                    else if (a instanceof ParcelUuid) types[i] = ParcelUuid.class;
                    else types[i] = a.getClass();
                }
                Constructor<?> ctor = BluetoothSocket.class.getDeclaredConstructor(types);
                ctor.setAccessible(true);
                BluetoothSocket sock = (BluetoothSocket) ctor.newInstance(args);
                Log.i(TAG, "AACP L2CAP socket via ctor spec #" + (idx + 1) + " " + java.util.Arrays.toString(types));
                return sock;
            } catch (Exception e) {
                last = e;
            }
            idx++;
        }
        throw last != null ? last : new IllegalStateException("no L2CAP BluetoothSocket ctor");
    }

    private static void close(Session s) {
        s.running = false;
        try { if (s.socket != null) s.socket.close(); } catch (Exception ignored) {}
        s.socket = null; s.in = null; s.out = null; s.reader = null;
    }

    // ---- manifest-free core (used by the standalone adb test in Task 5) ----

    /** Write a noise-control mode byte (1..4). Connects + brings up if needed. */
    static boolean setAncByte(BluetoothAdapter adapter, String mac, int modeByte) {
        ensureConnected(adapter, mac);
        Session s = SESSIONS.get(mac.toUpperCase(Locale.ROOT));
        if (s == null) return false;
        synchronized (s.lock) {
            if (s.out == null) return false;
            try {
                byte[] frame = AapCodec.ancSet(modeByte);
                Log.i(TAG, "AACP TX set ANC mode=" + modeByte + ": " + HexUtil.hex(frame));
                Logbook.add("AACP set ANC mode=" + modeByte);
                s.out.write(frame);
                s.out.flush();
                AapState.forMac(s.mac).setAncMode(modeByte); // optimistic; reader reconciles
                return true;
            } catch (Exception e) {
                Log.w(TAG, "AACP set failed: " + e);
                close(s);
                return false;
            }
        }
    }

    /** The cached raw noise-control mode byte (1..4), or null if unknown. */
    static Integer getAncByte(String mac) {
        return AapState.forMac(mac).getAncMode();
    }

    // ---- ControlEngine surface (manifest-driven; exercised from Plan 3 onward) ----

    static boolean applyMode(BluetoothAdapter adapter, String mac, DeviceDef def,
                             DeviceDef.Func f, String optId) {
        if (f == null) return false;
        String valueHex = f.optionValues.get(optId);
        if (valueHex == null) { Log.w(TAG, "AACP no option_value for " + optId); return false; }
        return setAncByte(adapter, mac, Integer.parseInt(valueHex, 16));
    }

    /** Returns the cached mode option id (mapped via the manifest value_map), or null if unknown. */
    static String readMode(BluetoothAdapter adapter, String mac, DeviceDef def, DeviceDef.Func f) {
        if (f == null) return null;
        ensureConnected(adapter, mac);
        Integer mode = getAncByte(mac);
        if (mode == null) return null;
        return f.valueMap.get(String.format("%02x", mode & 0xff));
    }

    static boolean applyToggle(BluetoothAdapter a, String mac, DeviceDef def,
                               DeviceDef.Func f, boolean on) {
        return false; // toggles land in a later plan
    }

    static Boolean readToggle(BluetoothAdapter a, String mac, DeviceDef def, DeviceDef.Func f) {
        return null; // toggles land in a later plan
    }
}
