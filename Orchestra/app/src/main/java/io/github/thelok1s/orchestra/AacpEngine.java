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
public final class AacpEngine {
    private static final String TAG = DeviceDef.TAG;
    private static final int PSM = 4097, TYPE_L2CAP = 3;
    private static final ParcelUuid AAP_UUID =
            ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a");

    private AacpEngine() {}

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private static final Map<String, Map<String, Runnable>> LISTENERS = new ConcurrentHashMap<>();

    public static void registerListener(String mac, String key, Runnable onChange) {
        LISTENERS.computeIfAbsent(mac.toUpperCase(Locale.ROOT), k -> new ConcurrentHashMap<>())
                 .put(key, onChange);
    }
    public static void unregisterListener(String mac, String key) {
        Map<String, Runnable> m = LISTENERS.get(mac.toUpperCase(Locale.ROOT));
        if (m != null) m.remove(key);
    }
    static void fireListener(String mac) {
        Map<String, Runnable> m = LISTENERS.get(mac.toUpperCase(Locale.ROOT));
        if (m == null) return;
        for (Runnable r : m.values()) {
            try { r.run(); } catch (Throwable t) { Log.w(TAG, "AACP listener threw: " + t); }
        }
    }

    /**
     * Task 5: Conversational Awareness speech-level EVENT dispatch (opcode 0x4B), distinct from
     * {@link #LISTENERS}/{@link AapState}: speech is an edge event (duck/restore), not persisted
     * state, so it does not go through {@code fireListener} or get cached in {@link AapState}.
     * Single listener per mac is fine — only the broker registers.
     */
    private static final Map<String, java.util.function.IntConsumer> SPEECH_LISTENERS = new ConcurrentHashMap<>();

    public static void registerSpeechListener(String mac, java.util.function.IntConsumer l) {
        SPEECH_LISTENERS.put(mac.toUpperCase(Locale.ROOT), l);
    }
    static void fireSpeech(String mac, int level) {
        java.util.function.IntConsumer l = SPEECH_LISTENERS.get(mac.toUpperCase(Locale.ROOT));
        if (l == null) return;
        try { l.accept(level); } catch (Throwable t) { Log.w(TAG, "AACP speech listener threw: " + t); }
    }

    private static final class Session {
        final String mac;
        final Object lock = new Object();
        BluetoothSocket socket;
        InputStream in;
        OutputStream out;
        Thread reader;
        volatile boolean running;
        volatile boolean sawInbound;
        /** Task 3: the resolved manifest for this device, latched at connect time so the reader
         *  can later dispatch generically off it (Lane A / Lane B gating). Nullable — legacy
         *  callers that don't hold a def leave this unset and the reader keeps hard-coded parsing. */
        volatile DeviceDef def;
        Session(String mac) { this.mac = mac; }
    }

    /** Open + bring up the session and start the reader if not already running. Idempotent.
     *  No DeviceDef needed — PSM/UUID are protocol constants. */
    public static void ensureConnected(BluetoothAdapter adapter, String mac) {
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
                s.sawInbound = false;
                startReader(s);
                long deadline = System.currentTimeMillis() + 1500;
                while (!s.sawInbound && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                }
                if (!s.sawInbound) {
                    throw new java.io.IOException("AAP session dead: no inbound after bring-up (stale channel?)");
                }
                Log.i(TAG, "AACP connected + brought up " + key);
                Logbook.add("AACP connected " + key);
            } catch (Exception e) {
                Log.w(TAG, "AACP connect failed for " + key + ": " + e);
                Logbook.add("AACP " + key + " connect failed: " + e);
                close(s);
            }
        }
    }

    /** Task 3: overload that also latches the resolved manifest onto the session so the reader
     *  can later dispatch generically off it. Never overwrites an already-latched def with null —
     *  callers without a def (legacy paths) just leave whatever a prior caller latched in place.
     *  Connect logic itself is unchanged from {@link #ensureConnected(BluetoothAdapter, String)}. */
    public static void ensureConnected(BluetoothAdapter adapter, String mac, DeviceDef def) {
        String key = mac.toUpperCase(Locale.ROOT);
        Session s = SESSIONS.computeIfAbsent(key, Session::new);
        if (def != null) s.def = def; // latch the manifest for the reader (Lane A / Lane B gating)
        ensureConnected(adapter, mac); // existing connect logic unchanged
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
                    s.sawInbound = true;
                    boolean changed = false;
                    Integer anc = AapCodec.parseAncMode(buf, n);
                    if (anc != null) { state.setAncMode(anc); changed = true;
                        Log.i(TAG, "AACP notify ANC mode=" + anc + " for " + s.mac); }
                    Integer ca = AapCodec.parseFeature(buf, n, 0x28);
                    if (ca != null) { state.setCaEnabled(ca == 1); changed = true;
                        Log.i(TAG, "AACP notify CA=" + ca + " for " + s.mac); }
                    Integer adapt = AapCodec.parseAdaptiveStrength(buf, n);
                    if (adapt != null) { state.setAdaptiveStrength(adapt); changed = true;
                        Log.i(TAG, "AACP notify adaptive strength=" + adapt + " for " + s.mac); }
                    AapCodec.Battery bat = AapCodec.parseBattery(buf, n);
                    if (bat != null) { state.setBattery(bat); changed = true;
                        Log.i(TAG, "AACP notify battery " + state.batterySummary() + " for " + s.mac);
                        broadcastBattery(s.mac); }
                    AapCodec.Ear ear = AapCodec.parseEar(buf, n);
                    if (ear != null) { state.setEar(ear); changed = true;
                        Log.i(TAG, "AACP notify ear " + state.earSummary() + " for " + s.mac); }
                    AapCodec.Ownership own = AapCodec.parseOwnership(buf, n);
                    if (own != null) { state.setOwnership(own); changed = true;
                        Log.i(TAG, "AACP notify ownership ownedByOther=" + own.ownedByOther
                                + " label=" + own.otherLabel + " for " + s.mac); }
                    Integer sp = AapCodec.parseCaSpeech(buf, n);
                    if (sp != null) { Log.i(TAG, "AACP notify CA speech level=" + sp + " for " + s.mac);
                        fireSpeech(s.mac, sp); }
                    if (changed) fireListener(s.mac);
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

    /**
     * Tear down a device's session on remote disconnect. {@link BluetoothSocket#isConnected()} and
     * the blocked reader thread do not reliably observe a remote L2CAP drop, so {@code ensureConnected}
     * would otherwise REUSE a half-open socket and serve stale {@link AapState}. Closing + dropping the
     * session here forces the next connect to rebuild fresh; clearing the cache stops stale battery/ear
     * from showing in the gap; notifying refreshes any open UI/header. No-op if no session exists
     * (e.g. an RFCOMM device), so this is safe to call unconditionally on ACL_DISCONNECTED.
     */
    public static void disconnect(String mac) {
        String key = mac.toUpperCase(Locale.ROOT);
        Session s = SESSIONS.remove(key);
        if (s != null) {
            synchronized (s.lock) { close(s); }
            Log.i(TAG, "AACP disconnect teardown " + key);
        }
        AapState.clear(key);
        fireListener(key);     // provider re-push + UI recompose -> reflect "no data"
        broadcastBattery(key); // poke the Settings hook to re-read (cleared) battery -> header hides
    }

    // ---- manifest-free core (used by the standalone adb test in Task 5) ----

    /** Write a noise-control mode byte (1..4). Connects + brings up if needed. */
    public static boolean setAncByte(BluetoothAdapter adapter, String mac, int modeByte) {
        if (modeByte < 1 || modeByte > 4) {
            Log.w(TAG, "AACP ignoring out-of-range ANC mode " + modeByte);
            return false;
        }
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

    /** Send an arbitrary single-byte AAP feature frame (04 00 04 00 09 00 <feature> <value> ..).
     *  Used by manifest-driven "level" controls (e.g. adaptive strength 0x2E). Connects + brings
     *  up if needed, mirrors {@link #setAncByte}'s session/lock/write pattern. */
    public static boolean setFeature(BluetoothAdapter adapter, String mac, int feature, int value) {
        ensureConnected(adapter, mac);
        Session s = SESSIONS.get(mac.toUpperCase(Locale.ROOT));
        if (s == null) return false;
        synchronized (s.lock) {
            if (s.out == null) return false;
            try {
                byte[] frame = AapCodec.featureSet(feature, value);
                Log.i(TAG, "AACP TX set feature " + Integer.toHexString(feature) + "=" + value
                        + ": " + HexUtil.hex(frame));
                Logbook.add("AACP set feature " + Integer.toHexString(feature) + "=" + value);
                s.out.write(frame);
                s.out.flush();
                if (feature == 0x2E) {
                    AapState.forMac(s.mac).setAdaptiveStrength(value); // optimistic; buds don't echo 0x2E, reader reconciles at next bring-up
                }
                return true;
            } catch (Exception e) {
                Log.w(TAG, "AACP set feature failed: " + e);
                close(s);
                return false;
            }
        }
    }

    /** The cached raw noise-control mode byte (1..4), or null if unknown. */
    static Integer getAncByte(String mac) {
        return AapState.forMac(mac).getAncMode();
    }

    /** Write the Conversational Awareness on/off frame. Connects + brings up if needed.
     *  The transmit half of {@link #applyToggle}, callable without a manifest (the broker's path). */
    public static boolean setCa(BluetoothAdapter adapter, String mac, boolean on) {
        ensureConnected(adapter, mac);
        Session s = SESSIONS.get(mac.toUpperCase(Locale.ROOT));
        if (s == null) return false;
        synchronized (s.lock) {
            if (s.out == null) return false;
            try {
                byte[] frame = AapCodec.caSet(on);
                Log.i(TAG, "AACP TX set CA on=" + on + ": " + HexUtil.hex(frame));
                s.out.write(frame); s.out.flush();
                AapState.forMac(s.mac).setCaEnabled(on); // optimistic; reader reconciles
                return true;
            } catch (Exception e) {
                Log.w(TAG, "AACP CA set failed: " + e);
                close(s);
                return false;
            }
        }
    }

    // ---- ControlEngine surface (manifest-driven; exercised from Plan 3 onward) ----

    /** Task 4: generalized off the manifest feature byte (was hardcoded to ANC's 0x0D via
     *  {@link #setAncByte}). Guards {@code featureByte<0} so a manifest that hasn't been given a
     *  {@code feature} byte yet (e.g. ANC/sound_mode pre-Task-6) is correctly inert rather than
     *  sending a frame built from feature -1. */
    static boolean applyMode(BluetoothAdapter adapter, String mac, DeviceDef def,
                             DeviceDef.Func f, String optId) {
        if (f == null || f.getFeatureByte() < 0) {
            Log.w(TAG, "AACP applyMode: no feature byte for " + (f != null ? f.id : "null"));
            return false;
        }
        String valueHex = f.optionValues.get(optId);
        if (valueHex == null) { Log.w(TAG, "AACP no option_value for " + optId); return false; }
        ensureConnected(adapter, mac, def);
        Session s = SESSIONS.get(mac.toUpperCase(Locale.ROOT));
        if (s == null) return false;
        synchronized (s.lock) {
            if (s.out == null) return false;
            try {
                int value = Integer.parseInt(valueHex, 16);
                byte[] frame = AapCodec.featureSet(f.getFeatureByte(), value);
                Log.i(TAG, "AACP TX set mode " + f.id + "=" + optId + " (feature="
                        + Integer.toHexString(f.getFeatureByte()) + "): " + HexUtil.hex(frame));
                Logbook.add("AACP set mode " + f.id + "=" + optId);
                s.out.write(frame);
                s.out.flush();
                AapState.forMac(s.mac).setValue(f.id, value); // optimistic (Task 5 cache); reader reconciles
                return true;
            } catch (Exception e) {
                Log.w(TAG, "AACP mode set failed: " + e);
                close(s);
                return false;
            }
        }
    }

    /** Returns the cached mode option id (mapped via the manifest value_map), or null if unknown. */
    static String readMode(BluetoothAdapter adapter, String mac, DeviceDef def, DeviceDef.Func f) {
        if (f == null) return null;
        ensureConnected(adapter, mac, def);
        Integer mode = getAncByte(mac);
        if (mode == null) return null;
        return f.valueMap.get(String.format("%02x", mode & 0xff));
    }

    /** Task 4: generalized off the manifest feature byte + {@code option_values{on,off}} (was
     *  id-locked to {@code conversational_awareness} via {@link #setCa}). Guards
     *  {@code featureByte<0} so a toggle whose manifest hasn't been given a {@code feature} byte
     *  yet (e.g. CA pre-Task-6) is correctly inert rather than sending a frame built from feature
     *  -1. */
    static boolean applyToggle(BluetoothAdapter adapter, String mac, DeviceDef def,
                               DeviceDef.Func f, boolean on) {
        if (f == null || f.getFeatureByte() < 0) {
            Log.w(TAG, "AACP applyToggle: no feature byte for " + (f != null ? f.id : "null"));
            return false;
        }
        String optId = on ? "on" : "off";
        String valueHex = f.optionValues.get(optId);
        if (valueHex == null) {
            Log.w(TAG, "AACP applyToggle: no option_value " + optId + " for " + f.id);
            return false;
        }
        ensureConnected(adapter, mac, def);
        Session s = SESSIONS.get(mac.toUpperCase(Locale.ROOT));
        if (s == null) return false;
        synchronized (s.lock) {
            if (s.out == null) return false;
            try {
                int value = Integer.parseInt(valueHex, 16);
                byte[] frame = AapCodec.featureSet(f.getFeatureByte(), value);
                Log.i(TAG, "AACP TX set toggle " + f.id + "=" + on + " (feature="
                        + Integer.toHexString(f.getFeatureByte()) + "): " + HexUtil.hex(frame));
                Logbook.add("AACP set toggle " + f.id + "=" + on);
                s.out.write(frame);
                s.out.flush();
                AapState.forMac(s.mac).setValue(f.id, value); // optimistic (Task 5 cache); reader reconciles
                return true;
            } catch (Exception e) {
                Log.w(TAG, "AACP toggle set failed: " + e);
                close(s);
                return false;
            }
        }
    }

    static Boolean readToggle(BluetoothAdapter adapter, String mac, DeviceDef def, DeviceDef.Func f) {
        if (f == null || !"conversational_awareness".equals(f.id)) return null;
        ensureConnected(adapter, mac, def);
        return AapState.forMac(mac).getCaEnabled();
    }

    /** Task 4: manifest-driven "level" write, generalizing {@link #setFeature}'s hardcoded 0x2E
     *  case off the function's own {@code feature} byte. Clamps to the function's declared
     *  min/max (mirrors {@code ControlEngine.AACP.applyLevel}'s clamp) and guards
     *  {@code featureByte<0}. Optimistic update goes only to the generic {@link AapState} cache —
     *  callers that still read the dedicated {@code adaptiveStrength} field (pre-Task-5) are
     *  unaffected since this path is not yet wired to any caller. */
    static boolean setLevel(BluetoothAdapter adapter, String mac, DeviceDef def, DeviceDef.Func f, int value) {
        if (f == null || f.getFeatureByte() < 0) {
            Log.w(TAG, "AACP setLevel: no feature byte for " + (f != null ? f.id : "null"));
            return false;
        }
        int v = Math.max(f.getMin(), Math.min(f.getMax(), value));
        ensureConnected(adapter, mac, def);
        Session s = SESSIONS.get(mac.toUpperCase(Locale.ROOT));
        if (s == null) return false;
        synchronized (s.lock) {
            if (s.out == null) return false;
            try {
                byte[] frame = AapCodec.featureSet(f.getFeatureByte(), v);
                Log.i(TAG, "AACP TX set level " + f.id + "=" + v + " (feature="
                        + Integer.toHexString(f.getFeatureByte()) + "): " + HexUtil.hex(frame));
                Logbook.add("AACP set level " + f.id + "=" + v);
                s.out.write(frame);
                s.out.flush();
                // optimistic (Task 5 cache); some features (e.g. 0x2E) aren't echoed on write, so
                // this is load-bearing rather than a mere UI-latency shortcut, exactly like the
                // dedicated-cache equivalents in setAncByte/setFeature/setCa above.
                AapState.forMac(s.mac).setValue(f.id, v);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "AACP set level failed: " + e);
                close(s);
                return false;
            }
        }
    }

    /** Live display string for an info/battery function ("battery" or "ear_detection"), or null. */
    static String readInfo(BluetoothAdapter adapter, String mac, DeviceDef def, DeviceDef.Func f) {
        if (f == null) return null;
        ensureConnected(adapter, mac, def);
        AapState st = AapState.forMac(mac);
        if ("battery".equals(f.id)) return st.batterySummary();
        if ("ear_detection".equals(f.id)) return st.earSummary();
        return null;
    }

    /**
     * Broadcasts a battery-changed poke to the Settings-process receiver.
     * Sent with plain sendBroadcast (no receiver-permission arg) because Settings is
     * platform-signed and cannot hold our signature-level permission. Security is enforced
     * on the receiver side: the receiver is registered with our signature permission as the
     * broadcastPermission, so only the Orchestra app (which self-holds it) can deliver.
     */
    private static void broadcastBattery(String mac) {
        try {
            android.content.Context ctx = App.context();
            if (ctx == null) return;
            android.content.Intent i = new android.content.Intent(
                    "io.github.thelok1s.orchestra.BATTERY_CHANGED");
            i.setPackage(null); // implicit; received by the dynamically-registered Settings receiver
            i.putExtra("mac", mac);
            ctx.sendBroadcast(i); // no permission arg — Settings cannot hold our signature permission
        } catch (Throwable t) { Log.w(TAG, "battery broadcast failed: " + t); }
    }
}
