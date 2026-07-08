package io.github.thelok1s.orchestra;

/** JNI bridge to the self-installing native DID hook (libl2c_fcr_hook.so, ShadowHook-based).
 *  Split into a precompute phase (slow, disk-only ELF/.gnu_debugdata symbol resolution, safe to
 *  call before the target library is loaded) and a cheap arm phase (poll until the library is
 *  loaded, then hook it) so the install wins the race against the Bluetooth stack's own DI-record
 *  write instead of racing the ~380ms decompress against it.
 *  precomputeDidOffset() — resolves BTA_DmSetLocalDiRecord's offset once; safe to call repeatedly
 *    (no-op after the first success).
 *  armDidHook(true) — sets the gate + inline-hooks BTA_DmSetLocalDiRecord (at most once) the
 *    instant the target library is mapped; returns false (keep polling) until then.
 *  armDidHook(false) — gate only, no hook install.
 *  setDidGate(enable) — flips the gate at runtime without touching the hook install (used for a
 *    toggle-off after the hook is already armed).
 *  Takes effect at the next BT-stack DI-record write (BT restart). */
public final class NativeBridge {
    private NativeBridge() {}
    public static native boolean precomputeDidOffset();
    public static native boolean armDidHook(boolean enable);
    public static native void    setDidGate(boolean enable);
}
