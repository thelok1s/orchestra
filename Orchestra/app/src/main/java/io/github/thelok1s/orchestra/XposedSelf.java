package io.github.thelok1s.orchestra;

/**
 * Module-active sentinel. {@link #active()} returns false normally; when LSPosed has this module
 * enabled with our own package in scope, the hook replaces the return value with true.
 * {@link #apiLevel()} is likewise overwritten with the framework API version, and
 * {@link #engineMode()} with which entry loaded us — {@code "legacy"} (de.robv) or {@code "modern"}
 * (libxposed). The UI calls these to show module state. All are overridden by whichever entry point
 * the framework loaded (legacy {@code OrchestraHooks} or modern {@code OrchestraHookBodies}).
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

    /** Which engine loaded the module: {@code "legacy"} / {@code "modern"}, or {@code ""} if not loaded. */
    public static String engineMode() {
        return "";
    }
}
