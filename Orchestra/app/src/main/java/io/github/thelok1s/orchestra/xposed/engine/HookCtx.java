package io.github.thelok1s.orchestra.xposed.engine;

/**
 * Unified call context passed to both {@link HookHandler#before} and {@link HookHandler#after},
 * so a hook body reads args/fields and sets results the same way on the legacy de.robv API and the
 * modern libxposed API.
 *
 * <p>In the <em>before</em> phase {@link #getResult()} is null unless {@link #setResult} was called;
 * calling {@link #setResult} cancels the original method and forces that return value. In the
 * <em>after</em> phase {@link #getResult()} is the original return value; {@link #setResult}
 * overrides it.</p>
 */
public interface HookCtx {
    /** The {@code this} pointer of the hooked method, or null for static methods. */
    Object getThis();

    /** The argument at position {@code index} (0-based). */
    Object getArg(int index);

    /** Before phase: null unless {@link #setResult} was called. After phase: the (possibly overridden) return value. */
    Object getResult();

    /** Before phase: cancel the original and force this return value. After phase: override the return value. */
    void setResult(Object value);

    /** Read an instance field from {@link #getThis()} by name (e.g. a synthetic lambda capture {@code f$0}). */
    Object getObjectField(String fieldName);

    /** Read an {@code int} instance field from {@link #getThis()} by name. */
    int getIntField(String fieldName);
}
