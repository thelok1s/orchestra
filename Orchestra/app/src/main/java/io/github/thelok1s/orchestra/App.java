package io.github.thelok1s.orchestra;

import android.app.Application;
import android.content.Context;

/**
 * Holds a process-wide Context so the headless framework code (providers, receivers,
 * DeviceDef/DeviceStore) can read assets + prefs without threading a Context everywhere.
 * Replaces the KSU /data/adb file storage with app-internal storage.
 */
public class App extends Application {
    private static Context appContext;

    public static Context context() { return appContext; }

    /**
     * Publish a usable process Context as early as possible. {@link #attachBaseContext} runs before
     * any installed ContentProvider's onCreate (and thus before the first cross-process query), so a
     * boot-time reader like the direct-boot StateProvider never sees a null {@link #context()}. Also
     * called from {@link StateProvider#onCreate()} as a belt-and-suspenders guard. Idempotent: never
     * downgrades the canonical application context set in {@link #onCreate}.
     */
    static void attach(Context c) {
        if (appContext == null && c != null) {
            Context app = c.getApplicationContext();
            appContext = app != null ? app : c;
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        attach(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        // Allow reflecting the package-private L2CAP BluetoothSocket constructor used by
        // AacpEngine for AAP. Scoped to android.bluetooth only — not all non-SDK members.
        // No device-global change; per-process exemption.
        org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/");
        // Become the AAP broadcast client: consume AAP_STATE from the SystemUI broker and send
        // AAP_CMD to it. The app process no longer owns the L2CAP socket (Plan 6).
        AacpClientBridge.init(getApplicationContext());
    }
}
