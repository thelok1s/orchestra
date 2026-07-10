package io.github.thelok1s.orchestra.xposed.engine;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.function.Predicate;

import io.github.libxposed.api.XposedInterface;

/**
 * {@link HookEngine} backed by the modern {@code io.github.libxposed.api} API (102). Given the
 * module ({@link XposedInterface}, since {@code XposedModule} is one) and the target package's
 * ClassLoader. Each hooked method gets an {@link XposedInterface.Hooker} that runs the handler's
 * before/after around {@code chain.proceed()}, matching {@code XC_MethodHook} semantics.
 */
public class ModernHookEngine implements HookEngine {

    private static final String TAG = "Orchestra";

    private final XposedInterface xposed;
    private final ClassLoader classLoader;

    public ModernHookEngine(XposedInterface xposed, ClassLoader classLoader) {
        this.xposed = xposed;
        this.classLoader = classLoader;
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        return Class.forName(name, true, classLoader);
    }

    @Override
    public void hookMatching(Class<?> clazz, Predicate<Method> filter, HookHandler handler) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (filter.test(m)) {
                xposed.deoptimize(m); // stop JIT from eliding the hook point
                xposed.hook(m).intercept(chain -> intercept(chain, handler));
            }
        }
    }

    @Override
    public void hookExact(Class<?> clazz, String methodName, HookHandler handler) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                xposed.deoptimize(m);
                xposed.hook(m).intercept(chain -> intercept(chain, handler));
            }
        }
    }

    @Override public void log(String msg) { xposed.log(Log.DEBUG, TAG, msg); }
    @Override public int  apiLevel()      { return xposed.getApiVersion(); }

    /** Run {@code handler}'s before/after around {@code chain.proceed()} (XC_MethodHook semantics). */
    private static Object intercept(XposedInterface.Chain chain, HookHandler handler) throws Throwable {
        ModernCallCtx ctx = new ModernCallCtx(chain);
        handler.before(ctx);
        if (ctx.isResultCancelledByBefore()) {
            handler.after(ctx);          // before() forced a result → skip the original
            return ctx.getFinalResult();
        }
        ctx.enterAfterPhase(chain.proceed());
        handler.after(ctx);
        return ctx.getFinalResult();
    }
}
