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
 * "Worn" = BOTH buds in-ear (primary == 0 && secondary == 0). Removing even one bud → not-worn → pause.
 * Playback resumes only when BOTH buds are back in-ear.
 */
public class AapBehaviorControllerTest {

    private static final int IN   = 0;
    private static final int OUT  = 1;
    private static final int CASE = 2;

    private static final class FakeActions implements AapBehaviorController.MediaActions {
        int pauseCount = 0;
        int playCount  = 0;
        @Override public void pause() { pauseCount++; }
        @Override public void play()  { playCount++;  }
        void reset() { pauseCount = 0; playCount = 0; }
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
    // 2. Pause on worn → not-worn
    //    "worn = BOTH buds in-ear" — removing even ONE bud changes worn-state (worn → not-worn)
    //    and must trigger pause immediately.
    // ---------------------------------------------------------------------------

    @Test
    public void bothIn_oneRemoved_pause() {
        // Ear(IN, IN) → Ear(OUT, IN): primary removed, secondary still in-ear.
        // Under new semantics worn = primary==0 && secondary==0, so (OUT,IN) is not-worn → pause.
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onEar(null, new AapCodec.Ear(IN, IN));                           // baseline: worn
        ctrl.onEar(new AapCodec.Ear(IN, IN), new AapCodec.Ear(OUT, IN));      // primary removed

        assertEquals("removing one bud must pause (worn → not-worn)", 1, actions.pauseCount);
        assertEquals(0, actions.playCount);
    }

    @Test
    public void bothIn_secondaryRemoved_pause() {
        // Symmetric: Ear(IN, IN) → Ear(IN, OUT): secondary removed → not-worn → pause.
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
    // 3. Play on not-worn → worn (BOTH buds back in-ear)
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
    public void notWorn_oneIn_noAction() {
        // Ear(OUT,OUT) → Ear(IN,OUT): only one bud inserted.
        // Under new semantics worn = primary==0 && secondary==0, so (IN,OUT) is still not-worn → no action.
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onEar(null, new AapCodec.Ear(OUT, OUT));
        ctrl.onEar(new AapCodec.Ear(OUT, OUT), new AapCodec.Ear(IN, OUT));    // one bud in — still not worn

        assertEquals("no pause expected", 0, actions.pauseCount);
        assertEquals("play must NOT fire — only one bud in-ear, not yet worn", 0, actions.playCount);
    }

    @Test
    public void oneIn_bothIn_play() {
        // Ear(IN,OUT) → Ear(IN,IN): second bud inserted — transitions from not-worn to worn → play.
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onEar(null, new AapCodec.Ear(IN, OUT));                          // baseline: not worn (only one in)
        ctrl.onEar(new AapCodec.Ear(IN, OUT), new AapCodec.Ear(IN, IN));      // second bud inserted → worn

        assertEquals(0, actions.pauseCount);
        assertEquals("play must fire when second bud inserted (not-worn → worn)", 1, actions.playCount);
    }

    // ---------------------------------------------------------------------------
    // 4. No action on ear-unchanged frames (worn-state does not flip)
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
    public void positionalChange_bothNotWorn_noAction() {
        // (IN, OUT) → (OUT, IN): neither configuration has BOTH buds in-ear.
        // Under new semantics both states are not-worn → worn-state unchanged → no action.
        FakeActions actions = new FakeActions();
        AapBehaviorController ctrl = new AapBehaviorController(actions, clock);

        ctrl.onEar(null, new AapCodec.Ear(IN, OUT));                           // baseline: not worn (primary only)
        ctrl.onEar(new AapCodec.Ear(IN, OUT), new AapCodec.Ear(OUT, IN));      // swap — still not worn (secondary only)

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
}
