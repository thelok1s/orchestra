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

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        // Allow reflecting blocked non-SDK members (the package-private L2CAP BluetoothSocket
        // constructor used by AacpEngine for AAP). No device-global change; per-process exemption.
        org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L");
    }
}
