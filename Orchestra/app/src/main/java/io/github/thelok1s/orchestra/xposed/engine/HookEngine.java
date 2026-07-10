package io.github.thelok1s.orchestra.xposed.engine;

import java.lang.reflect.Method;
import java.util.function.Predicate;

/**
 * API-agnostic hook engine. Implementations back this with either the legacy {@code de.robv} API
 * ({@link LegacyHookEngine}) or the modern {@code io.github.libxposed.api} API
 * ({@link ModernHookEngine}). Hook bodies (see {@code OrchestraHookBodies}) are written once against
 * this interface and run unchanged on both.
 */
public interface HookEngine {

    /** Find a class by name in the target package's ClassLoader. */
    Class<?> findClass(String name) throws ClassNotFoundException;

    /** Hook every declared method on {@code clazz} that passes {@code filter}. */
    void hookMatching(Class<?> clazz, Predicate<Method> filter, HookHandler handler);

    /** Hook every declared method on {@code clazz} named {@code methodName} (handles overloads). */
    void hookExact(Class<?> clazz, String methodName, HookHandler handler);

    /** Write a log line in the framework's log channel. */
    void log(String msg);

    /**
     * The current framework's Xposed API level. Legacy: {@code XposedBridge.getXposedVersion()}
     * (101 on Vector). Modern: {@code XposedInterface.getApiVersion()} (101 or 102).
     */
    int apiLevel();
}
