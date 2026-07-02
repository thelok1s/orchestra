package io.github.thelok1s.orchestra;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.function.LongSupplier;

import io.github.thelok1s.orchestra.aap.AapBehaviorController;

/**
 * Unit tests for {@link AapBehaviorController}: pure transition/debounce logic driven by a fake
 * clock — no Thread.sleep, no Android dependencies.
 *
 * Ear values: 0 = in-ear, 1 = out-of-ear, 2 = in-case.
 * Model: in-ear COUNT (0..2). Any decrease (a pod removed) → pause; any increase (a pod
 * inserted) → play; unchanged count → no action. This covers both-pods use AND single-pod use
 * (hardware-found bug: wearing one pod and removing it must pause).
 */
public class AapBehaviorControllerTest {

    private static final int IN   = 0;
    private static final int OUT  = 1;
    private static final int CASE = 2;

    private static final class FakeActions implements AapBehaviorController.MediaActions {
        int pauseCount = 0;
        int playCount  = 0;
        int duckCount    = 0;
        int restoreCount = 0;
        @Override public void pause() { pauseCount++; }
        @Override public void play()  { playCount++;  }
        @Override public void duck()    { duckCount++;    }
        @Override public void restore() { restoreCount++; }
    }

    /** Mutable fake clock driven by the test. */
    private long fakeTime = 0;
    private final LongSupplier clock = () -> fakeTime;

    // ---------------------------------------------------------------------------
    // 1. First-ever frame (prev == null) establishes baseline — no action
    // ---------------------------------------------------------------------------

    @Test
    public void firstFrame_prevNull_noAction() {
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onEar(null, new AapCodec.Ear(IN, IN)); // worn baseline

        assertEquals("pause should not fire on first frame", 0, actions.pauseCount);
        assertEquals("play should not fire on first frame",  0, actions.playCount);
    }

    @Test
    public void firstFrame_notWorn_prevNull_noAction() {
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onEar(null, new AapCodec.Ear(OUT, OUT)); // not-worn baseline

        assertEquals(0, actions.pauseCount);
        assertEquals(0, actions.playCount);
    }

    // ---------------------------------------------------------------------------
    // 2. Pause on any pod removal (in-ear count decreases)
    // ---------------------------------------------------------------------------

    @Test
    public void bothIn_oneRemoved_pause() {
        // Ear(IN, IN) → Ear(OUT, IN): primary removed (count 2→1) → pause.
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onEar(null, new AapCodec.Ear(IN, IN));                           // baseline: worn
        ctrl.onEar(new AapCodec.Ear(IN, IN), new AapCodec.Ear(OUT, IN));      // primary removed

        assertEquals("removing one bud must pause (worn → not-worn)", 1, actions.pauseCount);
        assertEquals(0, actions.playCount);
    }

    @Test
    public void bothIn_secondaryRemoved_pause() {
        // Symmetric: Ear(IN, IN) → Ear(IN, OUT): secondary removed (count 2→1) → pause.
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onEar(null, new AapCodec.Ear(IN, IN));                           // baseline: worn
        ctrl.onEar(new AapCodec.Ear(IN, IN), new AapCodec.Ear(IN, OUT));      // secondary removed

        assertEquals("removing secondary bud must pause (worn → not-worn)", 1, actions.pauseCount);
        assertEquals(0, actions.playCount);
    }

    @Test
    public void bothIn_bothOut_pause() {
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onEar(null, new AapCodec.Ear(IN, IN));
        ctrl.onEar(new AapCodec.Ear(IN, IN), new AapCodec.Ear(OUT, OUT));

        assertEquals(1, actions.pauseCount);
        assertEquals(0, actions.playCount);
    }

    @Test
    public void bothIn_inCase_pause() {
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onEar(null, new AapCodec.Ear(IN, IN));
        ctrl.onEar(new AapCodec.Ear(IN, IN), new AapCodec.Ear(CASE, CASE));

        assertEquals(1, actions.pauseCount);
        assertEquals(0, actions.playCount);
    }

    // ---------------------------------------------------------------------------
    // 2b. Single-pod use (hardware-found bug): wearing ONE pod and removing it must pause
    // ---------------------------------------------------------------------------

    @Test
    public void singlePodWorn_removed_pause() {
        // Ear(IN,OUT) → Ear(OUT,OUT): the only in-ear pod removed (count 1→0) → pause.
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onEar(null, new AapCodec.Ear(IN, OUT));                          // baseline: one pod worn
        ctrl.onEar(new AapCodec.Ear(IN, OUT), new AapCodec.Ear(OUT, OUT));    // that pod removed

        assertEquals("removing the only worn pod must pause (count 1→0)", 1, actions.pauseCount);
        assertEquals(0, actions.playCount);
    }

    @Test
    public void singlePodWorn_otherInCase_removed_pause() {
        // Ear(IN,CASE) → Ear(OUT,CASE): one pod worn, other in case; the worn pod removed → pause.
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onEar(null, new AapCodec.Ear(IN, CASE));                         // baseline: one pod worn
        ctrl.onEar(new AapCodec.Ear(IN, CASE), new AapCodec.Ear(OUT, CASE));  // that pod removed

        assertEquals("removing the only worn pod must pause (count 1→0)", 1, actions.pauseCount);
        assertEquals(0, actions.playCount);
    }

    // ---------------------------------------------------------------------------
    // 3. Play on any pod insertion (in-ear count increases)
    // ---------------------------------------------------------------------------

    @Test
    public void notWorn_bothIn_play() {
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onEar(null, new AapCodec.Ear(OUT, OUT));                          // baseline: not worn
        ctrl.onEar(new AapCodec.Ear(OUT, OUT), new AapCodec.Ear(IN, IN));

        assertEquals(0, actions.pauseCount);
        assertEquals("play must fire when both buds re-worn", 1, actions.playCount);
    }

    @Test
    public void onePodInserted_play() {
        // Ear(OUT,OUT) → Ear(IN,OUT): a pod inserted (count 0→1) → play (single-pod use resumes).
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onEar(null, new AapCodec.Ear(OUT, OUT));
        ctrl.onEar(new AapCodec.Ear(OUT, OUT), new AapCodec.Ear(IN, OUT));    // one pod inserted

        assertEquals("no pause expected", 0, actions.pauseCount);
        assertEquals("play must fire when a pod is inserted (count 0→1)", 1, actions.playCount);
    }

    @Test
    public void oneIn_bothIn_play() {
        // Ear(IN,OUT) → Ear(IN,IN): second bud inserted (count 1→2) → play.
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onEar(null, new AapCodec.Ear(IN, OUT));                          // baseline: one pod worn
        ctrl.onEar(new AapCodec.Ear(IN, OUT), new AapCodec.Ear(IN, IN));      // second bud inserted

        assertEquals(0, actions.pauseCount);
        assertEquals("play must fire when second bud inserted (count 1→2)", 1, actions.playCount);
    }

    // ---------------------------------------------------------------------------
    // 4. No action when the in-ear count is unchanged
    // ---------------------------------------------------------------------------

    @Test
    public void earUnchanged_noAction() {
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onEar(null, new AapCodec.Ear(IN, IN));   // baseline
        ctrl.onEar(new AapCodec.Ear(IN, IN), new AapCodec.Ear(IN, IN)); // same — simulates battery/noise frames

        assertEquals(0, actions.pauseCount);
        assertEquals(0, actions.playCount);
    }

    @Test
    public void positionalSwap_countUnchanged_noAction() {
        // (IN, OUT) → (OUT, IN): the in-ear count stays 1 (a swap, not a removal/insertion) → no action.
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onEar(null, new AapCodec.Ear(IN, OUT));                           // baseline: one pod worn
        ctrl.onEar(new AapCodec.Ear(IN, OUT), new AapCodec.Ear(OUT, IN));      // swap — count still 1

        assertEquals(0, actions.pauseCount);
        assertEquals(0, actions.playCount);
    }

    // ---------------------------------------------------------------------------
    // 5. Debounce: sub-700ms flap is dropped; ≥700ms transition is honored
    // ---------------------------------------------------------------------------

    @Test
    public void sub700ms_flap_dropped() {
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onEar(null, new AapCodec.Ear(IN, IN));   // baseline: worn

        fakeTime = 0;
        ctrl.onEar(new AapCodec.Ear(IN, IN), new AapCodec.Ear(OUT, OUT));      // pause at t=0
        assertEquals(1, actions.pauseCount);

        fakeTime = 699; // within debounce window
        ctrl.onEar(new AapCodec.Ear(OUT, OUT), new AapCodec.Ear(IN, IN));      // flap: should be dropped
        assertEquals("play must not fire within debounce window", 0, actions.playCount);
        assertEquals("pause count must not change",               1, actions.pauseCount);
    }

    @Test
    public void exactly700ms_honored() {
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onEar(null, new AapCodec.Ear(IN, IN));

        fakeTime = 0;
        ctrl.onEar(new AapCodec.Ear(IN, IN), new AapCodec.Ear(OUT, OUT));      // pause at t=0
        assertEquals(1, actions.pauseCount);

        fakeTime = 700; // exactly at boundary — should be honored
        ctrl.onEar(new AapCodec.Ear(OUT, OUT), new AapCodec.Ear(IN, IN));      // play at t=700
        assertEquals("play must fire at exactly DEBOUNCE_MS boundary", 1, actions.playCount);
    }

    @Test
    public void after700ms_honored() {
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onEar(null, new AapCodec.Ear(IN, IN));

        fakeTime = 0;
        ctrl.onEar(new AapCodec.Ear(IN, IN), new AapCodec.Ear(OUT, OUT));      // pause at t=0
        assertEquals(1, actions.pauseCount);

        fakeTime = 1500; // well after debounce
        ctrl.onEar(new AapCodec.Ear(OUT, OUT), new AapCodec.Ear(IN, IN));      // play at t=1500
        assertEquals("play must fire after debounce window", 1, actions.playCount);
    }

    @Test
    public void firstAction_alwaysHonored_noDebounce() {
        // First action after baseline must never be debounced even at t=0
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        fakeTime = 0;
        ctrl.onEar(null, new AapCodec.Ear(IN, IN));                            // baseline at t=0
        ctrl.onEar(new AapCodec.Ear(IN, IN), new AapCodec.Ear(OUT, OUT));      // pause at t=0

        assertEquals("first action must always fire (no prior lastActedAt)", 1, actions.pauseCount);
    }

    // ---------------------------------------------------------------------------
    // 6. onCaSpeech: CA speech-level duck/restore bookkeeping
    //    levels 01/02 -> speech ACTIVE (duck); 08/09 -> speech ENDED (restore);
    //    03/04/0b -> ignore (intermediate). Repeated same-direction levels are no-ops.
    // ---------------------------------------------------------------------------

    @Test
    public void onCaSpeech_level01_ducks() {
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onCaSpeech(0x01);

        assertEquals("level 01 must duck", 1, actions.duckCount);
        assertEquals(0, actions.restoreCount);
    }

    @Test
    public void onCaSpeech_01then02_ducksOnce() {
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onCaSpeech(0x01);
        ctrl.onCaSpeech(0x02);

        assertEquals("01 then 02 must duck exactly once", 1, actions.duckCount);
        assertEquals(0, actions.restoreCount);
    }

    @Test
    public void onCaSpeech_level08_restoresAfterDuck() {
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onCaSpeech(0x01); // duck
        ctrl.onCaSpeech(0x08); // restore

        assertEquals(1, actions.duckCount);
        assertEquals("level 08 must restore", 1, actions.restoreCount);
    }

    @Test
    public void onCaSpeech_level09_restoresAfterDuck() {
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onCaSpeech(0x01); // duck
        ctrl.onCaSpeech(0x09); // restore

        assertEquals(1, actions.duckCount);
        assertEquals("level 09 must restore", 1, actions.restoreCount);
    }

    @Test
    public void onCaSpeech_restoreNeverFiresWithoutPriorDuck() {
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onCaSpeech(0x08); // never ducked

        assertEquals(0, actions.duckCount);
        assertEquals("restore must not fire without a prior duck", 0, actions.restoreCount);
    }

    @Test
    public void onCaSpeech_intermediateLevels_ignored() {
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onCaSpeech(0x03);
        ctrl.onCaSpeech(0x04);
        ctrl.onCaSpeech(0x0b);

        assertEquals("03/04/0b must be ignored", 0, actions.duckCount);
        assertEquals(0, actions.restoreCount);
    }

    @Test
    public void onCaSpeech_fullCycle_exactlyOneDuckOneRestore() {
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        // 01 -> 02 -> 03 -> 0b -> 04 -> 08 -> 09 (spike-observed full episode)
        ctrl.onCaSpeech(0x01);
        ctrl.onCaSpeech(0x02);
        ctrl.onCaSpeech(0x03);
        ctrl.onCaSpeech(0x0b);
        ctrl.onCaSpeech(0x04);
        ctrl.onCaSpeech(0x08);
        ctrl.onCaSpeech(0x09);

        assertEquals("full episode must duck exactly once", 1, actions.duckCount);
        assertEquals("full episode must restore exactly once", 1, actions.restoreCount);
    }

    @Test
    public void onCaSpeech_duckBetweenEarFrames_doesNotAffectPausePlay() {
        // A duck occurring between ear-state changes must not alter pause/play behavior.
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onEar(null, new AapCodec.Ear(IN, IN));                      // baseline: worn
        ctrl.onCaSpeech(0x01);                                            // duck (unrelated to ear)
        ctrl.onEar(new AapCodec.Ear(IN, IN), new AapCodec.Ear(OUT, IN)); // pod removed -> pause
        ctrl.onCaSpeech(0x09);                                            // restore (unrelated to ear)

        assertEquals("ear pause logic must be unaffected by an interleaved duck", 1, actions.pauseCount);
        assertEquals(0, actions.playCount);
        assertEquals(1, actions.duckCount);
        assertEquals(1, actions.restoreCount);
    }
}
