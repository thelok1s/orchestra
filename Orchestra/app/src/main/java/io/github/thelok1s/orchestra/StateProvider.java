package io.github.thelok1s.orchestra;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * Read-only bridge so privileged cross-process readers (the Settings-process hook; the SystemUI
 * AAP broker) can read app-process state without app SharedPreferences access.
 *   content://io.github.thelok1s.orchestra.state/battery/<MAC>
 * Columns: left,right,case_level (0..100 or -1), left_charging,right_charging,case_charging (1/0).
 * The socket is owned by the SystemUI broker (Plan 6); this provider only returns the
 * broadcast-fed {@link AapState} cache and never opens an L2CAP socket.
 *
 *   content://io.github.thelok1s.orchestra.state/behavior/<MAC>/<behaviorId>
 * Column: enabled (0|1) = {@link DeviceStore#behaviorEnabled}. Task 3: lets the SystemUI AAP
 * broker pull the per-device auto_pause enable on a cache miss (e.g. after a SystemUI restart),
 * since it cannot read this app's SharedPreferences directly.
 *
 *   content://io.github.thelok1s.orchestra.state/flag/<key>
 * Column: enabled (0|1) = {@link DeviceStore#flag} (default false). Task 4B: lets the
 * com.google.android.bluetooth-process hook read the (default-off) act_as_apple toggle without
 * app SharedPreferences access.
 *
 *   content://io.github.thelok1s.orchestra.state/manifest/<MAC>
 * Column: json = the resolved raw manifest body ({@link ManifestRepository#rawDeviceJson}) for the
 * device hooked to that MAC ({@link DeviceStore#enabledId}), or an empty cursor if the MAC isn't
 * hooked / has no manifest. Plan 8 Task 3: lets the SystemUI AAP broker resolve a {@code DeviceDef}
 * cross-process — the broker's {@code App.context()} is null, so it can't call
 * {@code DeviceDef.forAddress} directly (that resolves via this app's assets/prefs); this provider
 * runs in-process where {@code App.context()} is valid and hands the broker the raw JSON to parse.
 */
public class StateProvider extends ContentProvider {
    static final String AUTHORITY = "io.github.thelok1s.orchestra.state";
    private static final String[] COLS = {
            "left", "right", "case_level", "left_charging", "right_charging", "case_charging"};

    @Override public boolean onCreate() {
        // A boot-time cross-process query can hit this provider before App.onCreate runs (provider
        // onCreate precedes it), leaving App.context() null and DeviceStore.flag() NPE-ing on
        // createDeviceProtectedStorageContext(). Seed the process Context from ours (always set by
        // the time query() can run) so the direct-boot act_as_apple read succeeds under any engine.
        App.attach(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] proj, String sel, String[] selArgs, String sort) {
        java.util.List<String> seg = uri.getPathSegments();
        // content://…/enabled/<MAC> -> column enabled(0|1): is this device "hooked" (has a def)?
        // Lets the Settings-process metadata writer keep key 25 aligned with what we actually serve,
        // so SystemUI never memoizes an empty device-settings layout for an un-hooked device.
        if (seg.size() == 2 && "enabled".equals(seg.get(0))) {
            String emac = seg.get(1).toUpperCase(java.util.Locale.ROOT);
            MatrixCursor ec = new MatrixCursor(new String[]{"enabled"});
            ec.addRow(new Object[]{DeviceStore.enabledId(emac) != null ? 1 : 0});
            return ec;
        }
        if (seg.size() == 3 && "behavior".equals(seg.get(0))) {
            String bmac = seg.get(1).toUpperCase(java.util.Locale.ROOT);
            String behaviorId = seg.get(2);
            MatrixCursor bcur = new MatrixCursor(new String[]{"enabled"});
            bcur.addRow(new Object[]{DeviceStore.behaviorEnabled(bmac, behaviorId) ? 1 : 0});
            return bcur;
        }
        if (seg.size() == 2 && "flag".equals(seg.get(0))) {
            MatrixCursor fc = new MatrixCursor(new String[]{"enabled"});
            fc.addRow(new Object[]{DeviceStore.flag(seg.get(1), false) ? 1 : 0});
            return fc;
        }
        if (seg.size() == 2 && "manifest".equals(seg.get(0))) {
            String id = DeviceStore.enabledId(seg.get(1));            // mac -> device_id (null if not hooked)
            String json = id != null ? ManifestRepository.rawDeviceJson(id) : null;
            MatrixCursor mc = new MatrixCursor(new String[]{"json"});
            if (json != null) mc.addRow(new Object[]{json});
            return mc;                                                // empty cursor => broker treats as miss
        }
        if (seg.size() != 2 || !"battery".equals(seg.get(0))) return null;
        String mac = seg.get(1).toUpperCase(java.util.Locale.ROOT);
        // The SystemUI broker owns the socket and pushes state to this process via AAP_STATE
        // broadcasts (AacpClientBridge -> AapState). Return the cache; never open a socket here.
        AapCodec.Battery b = AapState.forMac(mac).getBattery();
        MatrixCursor cur = new MatrixCursor(COLS);
        if (b != null) {
            cur.addRow(new Object[]{
                    level(b.left, b.leftStatus),
                    level(b.right, b.rightStatus),
                    level(b.caseLevel, b.caseStatus),
                    chargingFlag(b.leftStatus), chargingFlag(b.rightStatus), chargingFlag(b.caseStatus)});
        }
        return cur;
    }

    // -1 (= hidden in the header) when the component is unknown OR reports disconnected (status 04,
    // e.g. case lid open / pod in case). Only present components get a battery key written.
    private static final int STATUS_DISCONNECTED = 4;
    private static int level(Integer lvl, Integer status) {
        if (lvl == null || (status != null && status == STATUS_DISCONNECTED)) return -1;
        return lvl;
    }

    private static int chargingFlag(Integer status) { return status != null && status == 1 ? 1 : 0; }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
