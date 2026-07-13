package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * The flowv2 corpus ground truths, ported from the research handoff. Each test documents the
 * exact expected outcome of the 4-stage lexicographic solver on a known-good chart. They are
 * disabled until that solver lands (phase 3) - enabling them one by one IS the accuracy ratchet.
 *
 * <p>
 * Vocabulary (port-level adaptation of the flowv2 formulation): a port with no edges is a
 * terminal and gets a free external; a connected port may get a GATED external (binary cost).
 * "Gates" below counts open gated externals only.
 */
class GroundTruthTest {

    @Test
    @Disabled("phase 3: needs the lexicographic solver with gated externals")
    void loopGraph_oneSourceInjectingThirdOfLoopDemand() {
        // DT (pinned number:1) consumes 3000 diluted sulfuric acid; the LCR loop supplies only
        // 1000 per cycle. Expect exactly ONE open gate: a source on diluted sulfuric acid
        // injecting exactly 1/3 of the pinned loop demand. All machines run.
        fail("expect: 1 gated source (diluted sulfuric acid) at exactly 1/3 of DT demand");
    }

    @Test
    @Disabled("phase 3: needs the lexicographic solver with gated externals")
    void mk1_exactlyOneGate_sinkExcessPreferred() {
        // Two genuinely tied optima exist: {sink heavy naquadah fuel} and {source light
        // naquadah fuel}. The 1025/1024 source/sink weights must make the deterministic default
        // the SINK (discard excess beats supplying an intermediate). Optima enumeration must
        // find exactly these two.
        fail("expect: exactly 1 open gate = sink on 'heavy naquadah fuel'; enumeration finds 2 optima");
    }

    @Test
    @Disabled("phase 3: needs the lexicographic solver with gated externals")
    void lightFuel_zeroGates() {
        // Straight-line chart: oil 25/s in, light fuel 25/s out (plus O2, H2S byproducts as
        // free terminals). No gated external may open.
        fail("expect: 0 gates; oil terminal source at 25/s, light fuel terminal sink at 25/s");
    }

    @Test
    @Disabled("phase 3: needs the lexicographic solver with gated externals")
    void lightFuelHydrogenLoop_fullyRecycles() {
        // The hydrogen-loop variant must fully recycle its hydrogen: still zero gates, and the
        // loop's free circulation must be pinned by stage 3 (minimize total internal flow).
        fail("expect: 0 gates; hydrogen loop flow finite and minimal");
    }

    @Test
    @Disabled("phase 3: needs the lexicographic solver with gated externals")
    void palladiumLine_elevenGates_allMachinesRun_within15s() {
        // 56 machines. All must run (stage 0 floors). Exactly 11 auto-placed gated externals -
        // this matches the historical hand-picked whitelist from gtnh-flow. flowv2 solved it in
        // ~1.3s; budget is 15s/stage.
        fail("expect: 56/56 machines running, exactly 11 open gates, solve <= 15s");
    }

    @Test
    @Disabled("phase 3: needs the lexicographic solver with gated externals")
    void nanocircuits_zeroGates_subSecond() {
        // 394 machines, fully balanced chain: zero gates, and flowv2 solved it sub-second.
        fail("expect: 0 gates on 394 machines, solve well under budget");
    }

    @Test
    @Disabled("phase 3: solution validation gate")
    void everySolutionValidatesIndependently() {
        // flowv2 rule: never trust solver status codes (CBC returned conservation-VIOLATING
        // "solutions"). Every accepted solution must pass ConservationValidator with residuals
        // ~1e-13 relative, or be rejected.
        fail("expect: max |residual| <= 1e-9 relative on every accepted corpus solution");
    }
}
