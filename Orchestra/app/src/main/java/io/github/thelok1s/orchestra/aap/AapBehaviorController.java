package io.github.thelok1s.orchestra.aap;

import java.util.function.LongSupplier;

import io.github.thelok1s.orchestra.AapCodec;

/**
 * Pure-Java (no android imports) ear-detection behavior controller: fires {@link MediaActions#pause}
 * when a pod is removed from the ear, and {@link MediaActions#play} when a pod is inserted.
 *
 * <p>Model: the in-ear COUNT (0..2 pods with value 0). Any decrease (a pod removed) pauses; any
 * increase (a pod inserted) plays; an unchanged count (positional swaps, battery/noise-only frames)
 * does nothing. The count model covers both-pods use AND single-pod use — a worn-boolean model
 * ("worn = both in-ear") misses removing the only pod in single-pod use (hardware-found bug).
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
        /** Lower media volume for the duration of a Conversational Awareness speech episode. */
        void duck();
        /** Restore the volume {@link #duck()} lowered. */
        void restore();
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

    /** Whether {@link MediaActions#duck()} has fired without a matching {@link MediaActions#restore()} yet. */
    private boolean ducked = false;

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

        int p = inEarCount(prev);
        int n = inEarCount(now);

        if (n == p) return; // count unchanged; ignore positional swaps / noise-only changes

        long nowMs = clock.getAsLong();
        if (lastActedAt >= 0 && (nowMs - lastActedAt) < DEBOUNCE_MS) return; // flap within window

        lastActedAt = nowMs;

        if (n < p) {
            actions.pause(); // a pod was removed
        } else {
            actions.play();  // a pod was inserted
        }
    }

    /** Number of pods currently in-ear (value 0): 0, 1, or 2. */
    private static int inEarCount(AapCodec.Ear e) {
        return (e.primary == 0 ? 1 : 0) + (e.secondary == 0 ? 1 : 0);
    }

    /**
     * Called by the broker on every Conversational Awareness speech-level notification
     * ({@link AapCodec#parseCaSpeech}). Per the Task 5 spike, levels 01/02 mean speech is
     * ACTIVE, 08/09 mean speech has ENDED, and 03/04/0b are intermediate ramp values that are
     * ignored. This is a state machine driven by the level protocol itself, not a debounced
     * transition like {@link #onEar}, so {@link #DEBOUNCE_MS} / {@link #lastActedAt} do not apply
     * here: duck/restore only fire on the edges (active while not yet ducked; ended while ducked),
     * so repeated same-direction levels (e.g. 01 then 02) are no-ops.
     */
    public void onCaSpeech(int level) {
        boolean active = level == 0x01 || level == 0x02;
        boolean ended  = level == 0x08 || level == 0x09;
        if (!active && !ended) return; // intermediate levels (03/04/0b): ignore

        if (active && !ducked) {
            actions.duck();
            ducked = true;
        } else if (ended && ducked) {
            actions.restore();
            ducked = false;
        }
    }
}
