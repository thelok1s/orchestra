package io.github.thelok1s.orchestra;

/** JNI bridge to the self-installing native DID hook (libl2c_fcr_hook.so, ShadowHook-based).
 *  installDidHook(true) inline-hooks BTA_DmSetLocalDiRecord (once) + enables the vendor spoof;
 *  installDidHook(false) leaves the DID untouched (passthrough). Takes effect at the next
 *  BT-stack DI-record write (BT restart). */
public final class NativeBridge {
    private NativeBridge() {}
    public static native boolean installDidHook(boolean enable);
}
