package io.github.thelok1s.orchestra;

/**
 * Module-active sentinel. {@link #active()} returns false normally; when LSPosed has this module
 * enabled with our own package in scope, the hook (OrchestraHooks, for io.github.thelok1s.orchestra)
 * replaces the return value with true. {@link #apiLevel()} is likewise overwritten with the LSPosed
 * framework API version (XposedBridge.getXposedVersion()). The UI calls these to show module state.
 */
public final class XposedSelf {
    private XposedSelf() {}

    public static boolean active() {
        return false;
    }

    /** LSPosed/Xposed framework API level, or -1 when the module isn't loaded. */
    public static int apiLevel() {
        return -1;
    }
}
