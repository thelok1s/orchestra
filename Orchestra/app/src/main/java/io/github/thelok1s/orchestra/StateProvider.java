package io.github.thelok1s.orchestra;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

/**
 * Read-only bridge so the privileged Settings-process hook can read AAP battery (which lives in
 * this app process's {@link AapState}) and write it as untethered-battery metadata keys.
 *   content://io.github.thelok1s.orchestra.state/battery/<MAC>
 * Columns: left,right,case_level (0..100 or -1), left_charging,right_charging,case_charging (1/0).
 * Querying kicks {@link AacpEngine#ensureConnected} so values populate even if nothing else bound.
 */
public class StateProvider extends ContentProvider {
    static final String AUTHORITY = "io.github.thelok1s.orchestra.state";
    private static final String[] COLS = {
            "left", "right", "case_level", "left_charging", "right_charging", "case_charging"};

    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] proj, String sel, String[] selArgs, String sort) {
        java.util.List<String> seg = uri.getPathSegments();
        if (seg.size() != 2 || !"battery".equals(seg.get(0))) return null;
        String mac = seg.get(1).toUpperCase(java.util.Locale.ROOT);
        try {
            Context c = getContext();
            BluetoothManager bm = c != null ? (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE) : null;
            BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
            if (adapter != null) AacpEngine.ensureConnected(adapter, mac); // best-effort; non-blocking enough
        } catch (Throwable t) { Log.w(DeviceDef.TAG, "StateProvider connect: " + t); }
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
