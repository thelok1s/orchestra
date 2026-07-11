package io.github.thelok1s.orchestra.xposed.engine;

import java.lang.reflect.Field;

import io.github.libxposed.api.XposedInterface;

/**
 * {@link HookCtx} backed by {@link XposedInterface.Chain}. The modern API has one interception
 * callback per hook (the engine runs before → {@code chain.proceed()} → after around it), so this
 * ctx carries the phase state:
 * <ul>
 *   <li><b>before</b>: {@code result=null}, {@code resultSet=false}. If {@link #setResult} is called,
 *       {@code resultSet=true} and the engine skips {@code proceed()}.</li>
 *   <li><b>after</b>: {@link #enterAfterPhase} stores the original return value; {@link #setResult}
 *       overrides it.</li>
 * </ul>
 */
class ModernCallCtx implements HookCtx {

    private final XposedInterface.Chain chain;
    private boolean resultSet = false;
    private Object  result    = null;

    ModernCallCtx(XposedInterface.Chain chain) { this.chain = chain; }

    /** Enter the after phase with the original method's return value. */
    void enterAfterPhase(Object callResult) { this.result = callResult; }

    /** True iff {@link #setResult} was called during the before phase (skip the original call). */
    boolean isResultCancelledByBefore() { return resultSet; }

    /** The value to return from the interception. */
    Object getFinalResult() { return result; }

    @Override public Object getThis()         { return chain.getThisObject(); }
    @Override public Object getArg(int index) { return chain.getArg(index); }
    @Override public Object getResult()       { return result; }

    @Override public void setResult(Object value) {
        this.result = value;
        this.resultSet = true;
    }

    @Override public Object getObjectField(String fieldName) {
        try {
            return getField(fieldName).get(chain.getThisObject());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("getObjectField(" + fieldName + "): " + e.getMessage(), e);
        }
    }

    @Override public int getIntField(String fieldName) {
        try {
            return getField(fieldName).getInt(chain.getThisObject());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("getIntField(" + fieldName + "): " + e.getMessage(), e);
        }
    }

    private Field getField(String name) throws NoSuchFieldException {
        Field f = chain.getThisObject().getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
