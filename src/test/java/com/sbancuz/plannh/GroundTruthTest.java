package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.data.flowchart.AutoBalancer;
import com.sbancuz.plannh.data.flowchart.AutoBalancer.External;
import com.sbancuz.plannh.data.flowchart.AutoBalancer.PortRef;
import com.sbancuz.plannh.data.flowchart.AutoBalancer.Result;
import com.sbancuz.plannh.data.flowchart.AutoBalancer.Solution;
import com.sbancuz.plannh.data.flowchart.Node;
import com.sbancuz.plannh.harness.GtnhFlowLoader;
import com.sbancuz.plannh.harness.GtnhFlowLoader.LoadedChart;
import com.sbancuz.plannh.harness.GtnhFlowLoader.Pin;
import com.sbancuz.plannh.harness.TestIngredients;

/**
 * The flowv2 corpus ground truths, asserted against {@link AutoBalancer} (phase 3). Every
 * expected number comes from the flowv2 research handoff and is independently derivable from the
 * chart YAML by hand. Vocabulary: a port with no edges is a free terminal; a connected port may
 * get a GATED external (binary cost). "Gates" counts open gated externals only.
 */
class GroundTruthTest {

    private static final double EPS = 1e-4;

    @Test
    void loopGraph_oneSourceInjectingThirdOfLoopDemand() {
        // DT (pinned number:1) consumes 100/s diluted sulfuric acid; the LCR loop returns only
        // 2/3 of it. Expect exactly ONE open gate: a source on the DT's diluted-acid input
        // injecting exactly 1/3 of the pinned demand. The tied alternative (source sulfuric at
        // the LCR instead) must lose at stage 3 on internal flow. All machines run.
        final LoadedChart chart = GtnhFlowLoader.load("loopGraph");
        final Solution s = solve(chart);

        assertEquals(1, s.openGates(), "exactly one gated external");
        assertEquals(
            1,
            s.gatedSources()
                .size(),
            "the gate is a source");
        final External source = s.gatedSources()
            .get(0);
        assertEquals(
            chart.machine(0).id,
            source.port()
                .nodeId(),
            "source sits on the DT (diluted acid input)");
        assertTrue(
            source.port()
                .input());
        assertEquals(100.0 / 3.0, source.ratePerSecond(), EPS, "injects exactly 1/3 of the DT's demand");
        assertAllMachinesRun(chart, s);
        assertEquals(
            1.0,
            s.machineCounts()
                .get(chart.machine(0).id),
            EPS,
            "pinned DT stays at 1");
        assertEquals(
            8.0 / 15.0,
            s.machineCounts()
                .get(chart.machine(1).id),
            EPS,
            "LCR runs at 0.533 machines");
    }

    @Test
    void mk1_exactlyOneGate_sinkExcessPreferred() {
        // Two genuinely tied optima exist: {sink heavy naquadah} and {source light naquadah}.
        // The 1025/1024 source/sink weights must make the deterministic default the SINK
        // (discard excess beats supplying an intermediate). Optima enumeration must find exactly
        // these two.
        final LoadedChart chart = GtnhFlowLoader.load("mk1");
        final Map<UUID, Double> pins = targetPins(chart);
        final Result result = AutoBalancer.solve(chart.graph(), pins);
        assertTrue(result.isSuccess(), () -> "solve failed: " + result.failure());
        final Solution s = result.solution();

        assertEquals(1, s.openGates(), "exactly one gated external");
        assertEquals(
            1,
            s.gatedSinks()
                .size(),
            "the deterministic default is the sink");
        final External sink = s.gatedSinks()
            .get(0);
        assertEquals(
            chart.machine(1).id,
            sink.port()
                .nodeId(),
            "sink sits on the DT's heavy naquadah output");
        assertEquals(0.25, sink.ratePerSecond(), EPS, "0.25/s heavy naquadah discarded");

        final List<Set<PortRef>> alternatives = AutoBalancer.enumerateAlternatives(chart.graph(), pins);
        assertEquals(2, alternatives.size(), "exactly two tied optima: sink heavy, source light");
    }

    @Test
    void lightFuel_zeroGates() {
        // Straight-line chart: oil 25/s in, light fuel 25/s out (plus O2, H2S byproducts as
        // free terminals). No gated external may open, and the sub-unity machine counts must be
        // returned fractionally (chemical reactor at 1/60).
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        final Solution s = solve(chart);

        assertEquals(0, s.openGates(), "no gated external may open");
        assertEquals(25.0, terminalRate(chart, s.terminalInputs(), "oil"), EPS, "oil in at 25/s");
        assertEquals(25.0, terminalRate(chart, s.terminalOutputs(), "light fuel"), EPS, "light fuel out at 25/s");
        assertEquals(25.0 / 12.0, terminalRate(chart, s.terminalOutputs(), "oxygen"), EPS);
        assertEquals(25.0 / 12.0, terminalRate(chart, s.terminalOutputs(), "hydrogen sulfide"), EPS);
        assertEquals(
            1.0 / 60.0,
            s.machineCounts()
                .get(chart.machine(0).id),
            EPS,
            "chemical reactor at 1/60");
    }

    @Test
    void lightFuelHydrogenLoop_fullyRecycles() {
        // The hydrogen-loop variant must fully recycle its hydrogen: still zero gates, and the
        // loop's free circulation must be pinned by stage 3 (minimize total internal flow) to a
        // finite value.
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel_hydrogen_loop");
        final Solution s = solve(chart);

        assertEquals(0, s.openGates(), "hydrogen fully recycles, no gates");
        assertAllMachinesRun(chart, s);
        assertTrue(
            s.totalInternalFlow() < 1e7,
            "loop circulation pinned finite by stage 3, got " + s.totalInternalFlow());
    }

    @Test
    void mk1Tiberium_zeroGates_bathConsumesTheExcessHeavy() {
        // The in-game chart from the discussion screenshots. Adding the tiberium bath (a
        // consumer for the excess heavy naquadah) REMOVES mk1's sink-vs-source ambiguity: the
        // zero-gate support is unique - light pins the DT at 3.12 machines, heavy then pins the
        // bath at 1/6 machines - so the solver must find it with no externals and no prompt.
        final LoadedChart chart = GtnhFlowLoader.load("mk1_tiberium");
        final Solution s = solve(chart);

        assertEquals(0, s.openGates(), "no external heavy naquadah - the bath eats the excess");
        assertEquals(
            1.0,
            s.machineCounts()
                .get(chart.machine(0).id),
            EPS,
            "fusion pinned at 1");
        assertEquals(
            3.12,
            s.machineCounts()
                .get(chart.machine(1).id),
            EPS,
            "DT at 3.12 machines");
        assertEquals(
            1.0 / 6.0,
            s.machineCounts()
                .get(chart.machine(2).id),
            EPS,
            "bath at 1/6 machines");
        assertEquals(62.4, terminalRate(chart, s.terminalInputs(), "naquadah solution"), EPS);
    }

    @Test
    void palladiumLine_atMostElevenGates_allMachinesRun_withinBudget() {
        // 56 machines. All must run (stage 0 floors). flowv2's HiGHS answer was 11 gated
        // externals, matching the historical hand-picked whitelist - but research.md itself
        // flags gate counts on floored charts as floor-sensitive and HiGHS-certified only to
        // +-1. The hard requirements: a validated solution, every machine running, no MORE
        // externals than the historical whitelist, and inside the interactive budget. (The
        // current deletion-filter answer is 9 gates, strictly better than the whitelist.)
        final LoadedChart chart = GtnhFlowLoader.load("palladium_line");
        final Solution s = solve(chart);

        assertAllMachinesRun(chart, s);
        assertTrue(s.openGates() > 0, "palladium line cannot balance gate-free");
        assertTrue(s.openGates() <= 11, "at most the historical whitelist's 11 externals, got " + s.openGates());
        assertTrue(s.wallMillis() < 60_000, "total wall " + s.wallMillis() + "ms");
    }

    @Test
    void nanocircuits_zeroGates_fastPath() {
        // 394 machines, fully balanced chain: zero gates. The zero-gate LP fast path must keep
        // this well under budget despite the model size.
        final LoadedChart chart = GtnhFlowLoader.load("nanocircuits");
        final Solution s = solve(chart);

        assertEquals(0, s.openGates(), "0 gates on 394 machines");
        assertTrue(s.wallMillis() < 15_000, "wall " + s.wallMillis() + "ms");
    }

    @Test
    void everySolutionValidatesIndependently() {
        // flowv2 rule: never trust solver status codes. AutoBalancer.solve validates every
        // solution against its own port-conservation rows and rejects on residuals; a corpus
        // chart coming back as failure here means either a solver bug or a validation bug.
        for (final String name : new String[] { "mk1", "mk1_tiberium", "loopGraph", "light_fuel",
            "light_fuel_hydrogen_loop", "230_platline", "palladium_line", "nanocircuits" }) {
            final LoadedChart chart = GtnhFlowLoader.load(name);
            final Result result = AutoBalancer.solve(chart.graph(), targetPins(chart));
            assertTrue(result.isSuccess(), () -> name + " failed: " + result.failure());
        }
    }

    // ---------------------------------------------------------------------------------------

    private static Solution solve(final LoadedChart chart) {
        final Result result = AutoBalancer.solve(chart.graph(), targetPins(chart));
        assertTrue(result.isSuccess(), () -> chart.name() + " solve failed: " + result.failure());
        return result.solution();
    }

    /** Converts the loader's target-rate pins (ingredient/s) into extent pins (crafts/s). */
    private static Map<UUID, Double> targetPins(final LoadedChart chart) {
        final Map<UUID, Double> pins = new HashMap<>();
        for (final Pin pin : chart.pins()) {
            if (!"target".equals(pin.kind())) continue;
            final Node node = chart.machine(pin.machineIndex());
            for (int i = 0; i < node.outputs.size(); i++) {
                if (TestIngredients.nameOf(node.outputs.get(i))
                    .equals(pin.ingredient())) {
                    pins.put(node.id, pin.value() / TestIngredients.quantityOf(node.outputs.get(i)));
                }
            }
        }
        return pins;
    }

    private static void assertAllMachinesRun(final LoadedChart chart, final Solution s) {
        for (final Node machine : chart.machines()) {
            assertTrue(
                s.extentsPerSecond()
                    .get(machine.id) > 1e-9,
                machine.machineName + " must run, extent="
                    + s.extentsPerSecond()
                        .get(machine.id));
        }
    }

    /** Summed rate of terminals on ports carrying the named ingredient. */
    private static double terminalRate(final LoadedChart chart, final List<External> terminals,
        final String ingredient) {
        double total = 0;
        for (final External t : terminals) {
            final Node node = chart.graph().nodes.get(
                t.port()
                    .nodeId());
            final var ports = t.port()
                .input() ? node.inputs : node.outputs;
            if (TestIngredients.nameOf(
                ports.get(
                    t.port()
                        .portIndex()))
                .equals(ingredient)) {
                total += t.ratePerSecond();
            }
        }
        return total;
    }
}
