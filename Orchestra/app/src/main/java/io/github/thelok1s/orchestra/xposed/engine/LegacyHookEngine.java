package io.github.thelok1s.orchestra.xposed.engine;

import java.lang.reflect.Method;
import java.util.function.Predicate;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * {@link HookEngine} backed by the legacy {@code de.robv.android.xposed.*} API. Constructed by
 * {@code LegacyModuleEntry} with the target package's ClassLoader.
 */
public class LegacyHookEngine implements HookEngine {

    private final ClassLoader classLoader;
    private final int cachedApiLevel;

    public LegacyHookEngine(ClassLoader classLoader) {
        this.classLoader = classLoader;
        this.cachedApiLevel = XposedBridge.getXposedVersion();
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        return XposedHelpers.findClass(name, classLoader);
    }

    @Override
    public void hookMatching(Class<?> clazz, Predicate<Method> filter, HookHandler handler) {
        XC_MethodHook xhook = makeXHook(handler);
        for (Method m : clazz.getDeclaredMethods()) {
            if (filter.test(m)) XposedBridge.hookMethod(m, xhook);
        }
    }

    @Override
    public void hookExact(Class<?> clazz, String methodName, HookHandler handler) {
        XC_MethodHook xhook = makeXHook(handler);
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) XposedBridge.hookMethod(m, xhook);
        }
    }

    @Override public void log(String msg) { XposedBridge.log(msg); }
    @Override public int  apiLevel()      { return cachedApiLevel; }

    private static XC_MethodHook makeXHook(HookHandler handler) {
        return new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                handler.before(new LegacyCallCtx(p));
            }
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                handler.after(new LegacyCallCtx(p));
            }
        };
    }
}
