package io.github.thelok1s.orchestra.aap;

import java.util.function.LongSupplier;

import io.github.thelok1s.orchestra.AapCodec;

/**
 * Pure-Java (no android imports) ear-detection behavior controller: fires {@link MediaActions#pause}
 * when the user transitions from worn to not-worn, and {@link MediaActions#play} on the reverse.
 *
 * <p>Worn = BOTH buds in-ear ({@code primary == 0 && secondary == 0}). Removing even one bud
 * transitions to not-worn and triggers pause; playback resumes only when both are back in-ear.
 *
 * <p>Debounce: a transition that arrives within {@link #DEBOUNCE_MS} ms of the last acted transition
 * is silently dropped, guarding against brief flaps (e.g. re-seating a bud).
 *
 * <p>The injected {@code clock} (millis) makes debounce deterministically testable on the JVM
 * without {@code Thread.sleep}. Production code passes {@code System::currentTimeMillis}; tests
 * pass a mutable fake.
 *
 * <p>The broker holds one instance per MAC and passes the previous and current {@link AapCodec.Ear}
 * on every state-change notification. A {@code null} {@code prev} (first-ever frame for a MAC)
 * establishes the baseline without triggering pause or play.
 */
public final class AapBehaviorController {

    /** Seam for media-control side-effects; the real impl lives in the broker (android side). */
    public interface MediaActions {
        void pause();
        void play();
    }

    /** Transitions occurring within this many ms of the last acted transition are dropped. */
    public static final long DEBOUNCE_MS = 700;

    private final MediaActions actions;
    private final LongSupplier clock;

    /**
     * Timestamp (from {@code clock}) of the last transition we acted on.
     * {@code -1} means we have never acted (no debounce for the first real transition).
     */
    private long lastActedAt = -1;

    public AapBehaviorController(MediaActions actions, LongSupplier clock) {
        this.actions = actions;
        this.clock   = clock;
    }

    /**
     * Called by the broker on every ear-state change notification.
     *
     * @param prev the previous {@link AapCodec.Ear} for this MAC, or {@code null} on the very
     *             first frame (no prior observation). When {@code null}, this call only establishes
     *             the baseline and takes no action.
     * @param now  the current ear state. If {@code null}, the call is a no-op (safety guard).
     */
    public void onEar(AapCodec.Ear prev, AapCodec.Ear now) {
        if (now == null) return;
        if (prev == null) return; // first frame: establish baseline in caller's lastEar map; no action here

        boolean prevWorn = isWorn(prev);
        boolean nowWorn  = isWorn(now);

        if (prevWorn == nowWorn) return; // worn-state unchanged; ignore positional / noise-only changes

        long nowMs = clock.getAsLong();
        if (lastActedAt >= 0 && (nowMs - lastActedAt) < DEBOUNCE_MS) return; // flap within window

        lastActedAt = nowMs;

        if (!nowWorn) {
            actions.pause();
        } else {
            actions.play();
        }
    }

    /** Worn = BOTH buds in-ear (value 0). Removing even one bud is not-worn. */
    private static boolean isWorn(AapCodec.Ear e) {
        return e.primary == 0 && e.secondary == 0;
    }
}
