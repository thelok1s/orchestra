package io.github.thelok1s.orchestra.xposed.engine;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * {@link HookCtx} backed by the legacy {@code XC_MethodHook.MethodHookParam}. One per callback.
 */
class LegacyCallCtx implements HookCtx {

    private final XC_MethodHook.MethodHookParam p;

    LegacyCallCtx(XC_MethodHook.MethodHookParam p) { this.p = p; }

    @Override public Object getThis()           { return p.thisObject; }
    @Override public Object getArg(int index)   { return p.args[index]; }
    @Override public Object getResult()         { return p.getResult(); }
    @Override public void   setResult(Object v) { p.setResult(v); }

    @Override public Object getObjectField(String fieldName) {
        return XposedHelpers.getObjectField(p.thisObject, fieldName);
    }

    @Override public int getIntField(String fieldName) {
        return XposedHelpers.getIntField(p.thisObject, fieldName);
    }
}
