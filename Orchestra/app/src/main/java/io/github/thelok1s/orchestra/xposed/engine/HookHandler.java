package io.github.thelok1s.orchestra.xposed.engine;

/**
 * Hook callback with optional before/after phases. Both have empty defaults so a caller overrides
 * only what it needs.
 */
public interface HookHandler {
    /** Before the original method. Call {@link HookCtx#setResult} to cancel it and force a return value. */
    default void before(HookCtx ctx) throws Throwable {}

    /** After the original method (or after {@link #before} if it cancelled the original). {@link HookCtx#setResult} overrides the return value. */
    default void after(HookCtx ctx) throws Throwable {}
}
