package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sbancuz.plannh.data.flowchart.Balancer;
import com.sbancuz.plannh.data.flowchart.Balancer.BalanceMode;
import com.sbancuz.plannh.data.flowchart.Balancer.BalanceResult;
import com.sbancuz.plannh.data.flowchart.Node;
import com.sbancuz.plannh.harness.GtnhFlowLoader;
import com.sbancuz.plannh.harness.GtnhFlowLoader.LoadedChart;

/**
 * Behavior of the CURRENT balancer over the corpus: it must never crash, never exceed the 15s
 * per-solve budget (flowv2's DNF threshold), and always return a usable result (the ILP falls
 * back to configured counts when infeasible). Accuracy against the flowv2 ground truths is
 * covered by {@link GroundTruthTest} and lands with the lexicographic solver.
 */
class BalancerSmokeTest {

    private static final Duration BUDGET = Duration.ofSeconds(15);

    @ParameterizedTest
    @ValueSource(
        strings = { "mk1", "loopGraph", "light_fuel", "light_fuel_hydrogen_loop", "230_platline", "palladium_line",
            "nanocircuits" })
    void noneModeUsesConfiguredCounts(final String name) {
        final LoadedChart chart = GtnhFlowLoader.load(name);
        final BalanceResult result = Balancer.balance(chart.graph(), BalanceMode.NONE, false);
        assertNotNull(result);
        for (final Node node : chart.machines()) {
            assertTrue(
                result.nodeBalances()
                    .containsKey(node.id),
                "missing balance for " + node.machineName);
        }
    }

    /**
     * AUTO mode through the {@link Balancer} entry point: always returns a usable result inside
     * the interactive budget (worst case: two floor passes of up to three stages each), with the
     * displayed operation count being the ceiling of the fractional machine count.
     */
    @ParameterizedTest
    @ValueSource(
        strings = { "mk1", "mk1_tiberium", "loopGraph", "light_fuel", "light_fuel_hydrogen_loop", "230_platline",
            "palladium_line", "nanocircuits" })
    void autoModeStaysWithinBudget(final String name) {
        final LoadedChart chart = GtnhFlowLoader.load(name);
        final BalanceResult result = assertTimeoutPreemptively(
            Duration.ofSeconds(60),
            () -> Balancer.balance(chart.graph(), BalanceMode.AUTO, false),
            name + " exceeded the auto-balance budget");
        assertNotNull(result);
        for (final Node node : chart.machines()) {
            assertTrue(
                result.nodeBalances()
                    .containsKey(node.id),
                "missing balance for " + node.machineName);
        }
    }

    /**
     * Unlike OUTPUT/INPUT, AUTO must never write solved counts back into the node configs:
     * viewing a chart is not editing it. The fractional solution lives in the balance result's
     * effective rates; the configured counts stay the user's.
     */
    @org.junit.jupiter.api.Test
    void autoModeDoesNotWriteBackMachineCounts() {
        final LoadedChart chart = GtnhFlowLoader.load("loopGraph");
        final Node lcr = chart.machine(1);
        assertTrue(!lcr.isMachineCountFixed());
        lcr.machineConfig.setMachineCount(3);

        final BalanceResult result = Balancer.balance(chart.graph(), BalanceMode.AUTO, false);

        assertTrue(lcr.machineConfig.getMachineCount() == 3, "configured count must survive viewing");
        // The solved LCR count is 8/15 (0.533); the displayed operation count is its ceiling.
        assertTrue(
            result.nodeBalances()
                .get(lcr.id)
                .operations() == 1,
            "ceil(0.533) machines displayed");
    }

    @ParameterizedTest
    @ValueSource(
        strings = { "mk1", "loopGraph", "light_fuel", "light_fuel_hydrogen_loop", "230_platline", "palladium_line",
            "nanocircuits" })
    void outputModeStaysWithinBudget(final String name) {
        final LoadedChart chart = GtnhFlowLoader.load(name);
        final BalanceResult result = assertTimeoutPreemptively(
            BUDGET,
            () -> Balancer.balance(chart.graph(), BalanceMode.OUTPUT, false),
            name + " exceeded the 15s solve budget");
        assertNotNull(result);
        for (final Node node : chart.machines()) {
            final int ops = result.nodeBalances()
                .get(node.id)
                .operations();
            assertTrue(ops >= 1, node.machineName + " solved to " + ops + " machines");
        }
    }
}
