package io.github.thelok1s.orchestra.xposed;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

import io.github.thelok1s.orchestra.xposed.engine.ModernHookEngine;

/**
 * Modern Xposed entry point (registered in {@code META-INF/xposed/java_init.list}), loaded by
 * frameworks that implement the {@code io.github.libxposed.api} 102 API. Delegates all hook logic to
 * {@link OrchestraHookBodies}, shared with the legacy path.
 *
 * <p>The framework instantiates this via the no-arg constructor (from {@code XposedInterfaceWrapper})
 * and wires the interface with {@code attachFramework} before callbacks fire, so {@code this} is a
 * usable {@link io.github.libxposed.api.XposedInterface} inside the callbacks.</p>
 *
 * <p>Uses {@link PackageReadyParam#getClassLoader()} (the post-AppComponentFactory classloader)
 * rather than a default loader, because SystemUI's custom {@code AppComponentFactory} can change the
 * runtime classloader. The module path (for the bundled index gate + native DID libs) comes from
 * {@link #getModuleApplicationInfo()} at load, mirroring what the legacy entry reads in
 * {@code initZygote}.</p>
 */
public class ModernModuleEntry extends XposedModule {

    // Captured in onModuleLoaded (fires first, same process); PackageReadyParam has no process name.
    private volatile String processName = "?";

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        processName = param.getProcessName();
        try {
            android.content.pm.ApplicationInfo ai = getModuleApplicationInfo();
            if (ai != null && ai.sourceDir != null) OrchestraHookBodies.modulePath = ai.sourceDir;
        } catch (Throwable ignore) {}
        log(android.util.Log.INFO, "OrchestraMX",
                "[MX] ModernModuleEntry loaded into " + processName
                        + " (framework=" + getFrameworkName() + " api=" + getApiVersion() + ")");
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        // Additive-rollout coordination: while the legacy de.robv bridge is present, the legacy
        // OrchestraHooks entry (assets/xposed_init) already installs every hook in this process, so
        // this modern entry MUST stay inert to avoid double-hooking (e.g. Vector, which bridges
        // legacy modules at API 101). On a framework that drops the legacy bridge, OrchestraHooks
        // never fires and this entry does all the work. Once the modern path is validated there, the
        // legacy entry can be switched to also delegate to OrchestraHookBodies under a shared guard.
        if (legacyBridgePresent()) {
            log(android.util.Log.INFO, "OrchestraMX",
                    "[MX] ModernModuleEntry deferring to legacy bridge for " + param.getPackageName());
            return;
        }
        ModernHookEngine engine = new ModernHookEngine(this, param.getClassLoader());
        OrchestraHookBodies.applyIfAbsent(param.getPackageName(), processName, engine);
    }

    /** True if the legacy Xposed bridge is provided to this module (so OrchestraHooks is active). */
    private static boolean legacyBridgePresent() {
        try {
            Class.forName("de.robv.android.xposed.XposedBridge", false,
                    ModernModuleEntry.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
