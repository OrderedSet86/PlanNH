package com.sbancuz.plannh.nei;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import org.yaml.snakeyaml.Yaml;

import com.sbancuz.plannh.Compat;
import com.sbancuz.plannh.PlanNH;
import com.sbancuz.plannh.data.Settings;
import com.sbancuz.plannh.data.flowchart.Edge;
import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.data.flowchart.Node;
import com.sbancuz.plannh.data.flowchart.Note;
import com.sbancuz.plannh.data.flowchart.Port;
import com.sbancuz.plannh.data.provider.gregtech.GTHooks;

import codechicken.nei.ItemList;
import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.ICraftingHandler;

/**
 * Imports gtnh-flow style YAML charts (the flowv2 research corpus format) by matching each
 * machine entry to a real NEI recipe. The YAML names ingredients by display name and pools
 * them (any producer feeds any consumer); nodes are created from the matched NEI recipe -
 * the same path as adding one by hand - so ports, duration and EU come from game data, and
 * only tier ({@code tier:}) and pinned counts ({@code number:}) are copied from the YAML.
 *
 * <p>
 * Matching is fail-soft: entries with no matching recipe are skipped and reported, along
 * with every YAML key the importer cannot express (heat, coils, groups, targets), in an
 * on-canvas note. YAML names that resolve to no item or fluid (user aliases like "PMP")
 * still pool edges by name and match ports by quantity alone.
 */
public final class GtnhFlowImporter {

    private static final int COL_W = 340;
    private static final int ROW_H = 220;
    private static final int ORIGIN = 60;

    private GtnhFlowImporter() {}

    /** A YAML machine entry matched to a recipe: the node plus port index per YAML name. */
    private record Matched(Node node, Map<String, Integer> inPorts, Map<String, Integer> outPorts) {}

    /** Returns null when the text is not a gtnh-flow chart; a Graph (plus notes) when it is. */
    @Nullable
    public static Graph tryImport(final String text) {
        if (!text.contains("m:")) return null;
        final Object raw;
        try {
            raw = new Yaml().load(text);
        } catch (final Exception e) {
            return null;
        }
        if (!(raw instanceof final List<?> entries) || entries.isEmpty()) return null;

        final Map<String, ItemStack> itemIndex = buildItemIndex();
        if (itemIndex.isEmpty()) {
            PlanNH.LOG.warn("gtnh-flow import: NEI item list is empty, cannot resolve ingredients");
            return null;
        }
        final Map<String, FluidStack> fluidIndex = buildFluidIndex();

        // problem class -> deduped details, in first-seen order; rendered one line per class
        final Map<String, List<String>> problems = new LinkedHashMap<>();
        final List<Matched> matched = new ArrayList<>();
        // YAML ingredient name -> producing/consuming (matched index, port index)
        final Map<String, List<int[]>> producers = new LinkedHashMap<>();
        final Map<String, List<int[]>> consumers = new LinkedHashMap<>();

        int entryIndex = -1;
        for (final Object o : entries) {
            entryIndex++;
            if (!(o instanceof final Map<?, ?> entry) || entry.get("m") == null) continue;
            final String machine = String.valueOf(entry.get("m"));
            if (machine.startsWith("[")) {
                problem(problems, "Skipped source/sink pseudo-machines", machine);
                continue;
            }
            final Map<String, Double> ins = ioMap(entry.get("I"));
            final Map<String, Double> outs = ioMap(entry.get("O"));

            final Matched m = matchRecipe(machine, ins, outs, entry, itemIndex, fluidIndex);
            if (m == null) {
                problem(problems, "No recipe matched", "#" + entryIndex + " " + machine);
                continue;
            }

            applyConfig(m.node, entry, problems);

            final int mi = matched.size();
            for (final Map.Entry<String, Integer> e : m.inPorts.entrySet()) {
                consumers.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                    .add(new int[] { mi, e.getValue() });
            }
            for (final Map.Entry<String, Integer> e : m.outPorts.entrySet()) {
                producers.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                    .add(new int[] { mi, e.getValue() });
            }
            matched.add(m);
        }

        if (matched.isEmpty()) {
            PlanNH.LOG.warn("gtnh-flow import: no entries matched a recipe");
            return null;
        }

        final Graph graph = new Graph();
        for (final Matched m : matched) graph.addNode(m.node);

        for (final Map.Entry<String, List<int[]>> pool : producers.entrySet()) {
            final List<int[]> sinks = consumers.get(pool.getKey());
            if (sinks == null) continue;
            for (final int[] src : pool.getValue()) {
                for (final int[] dst : sinks) {
                    graph.addEdge(
                        new Edge(
                            UUID.randomUUID(),
                            matched.get(src[0]).node.id,
                            matched.get(dst[0]).node.id,
                            src[1],
                            dst[1]));
                }
            }
        }

        layout(graph, matched);

        if (!problems.isEmpty()) {
            final Note note = new Note();
            note.setHeader("gtnh-flow import");
            final List<String> lines = new ArrayList<>();
            for (final Map.Entry<String, List<String>> e : problems.entrySet()) {
                lines.add(e.getKey() + " (" + e.getValue().size() + "): " + String.join(", ", e.getValue()));
                PlanNH.LOG.info("gtnh-flow import: {}: {}", e.getKey(), String.join(", ", e.getValue()));
            }
            note.setText(lines);
            note.setX(ORIGIN);
            note.setY(ORIGIN - 2 * ROW_H);
            graph.notes.put(note.getId(), note);
        }
        PlanNH.LOG.info(
            "gtnh-flow import: {} machines, {} edges, {} problem classes",
            matched.size(),
            graph.getEdges()
                .size(),
            problems.size());
        return graph;
    }

    /** Files a detail under a problem class, deduplicated (many machines share one cause). */
    private static void problem(final Map<String, List<String>> problems, final String category, final String detail) {
        final List<String> list = problems.computeIfAbsent(category, k -> new ArrayList<>());
        if (!list.contains(detail)) list.add(detail);
    }

    // ── Recipe matching ──

    @Nullable
    private static Matched matchRecipe(final String machine, final Map<String, Double> ins,
        final Map<String, Double> outs, final Map<?, ?> entry, final Map<String, ItemStack> itemIndex,
        final Map<String, FluidStack> fluidIndex) {

        final ItemStack lookup = firstResolvable(outs, itemIndex, fluidIndex);
        if (lookup == null) return null;

        final int wantTicks = (int) Math.round(asDouble(entry.get("dur"), -1) * 20);
        final String wantMachine = norm(machine);

        Matched best = null;
        int bestScore = Integer.MIN_VALUE;
        for (final ICraftingHandler handler : GuiCraftingRecipe.getCraftingHandlers("item", lookup)) {
            final int nameSim = nameSimilarity(wantMachine, norm(handler.getRecipeName()));
            for (int i = 0; i < handler.numRecipes(); i++) {
                final Node node;
                try {
                    node = new Node(handler, i, 0, 0);
                } catch (final Exception e) {
                    // A malformed candidate recipe is a non-match, not an import failure.
                    PlanNH.LOG.debug("gtnh-flow import: candidate {}#{} failed: {}", handler.getRecipeName(), i, e);
                    continue;
                }
                final Map<String, Integer> inMap = mapPorts(ins, node.inputs, itemIndex, fluidIndex);
                if (inMap == null) continue;
                final Map<String, Integer> outMap = mapPorts(outs, node.outputs, itemIndex, fluidIndex);
                if (outMap == null) continue;

                final int extras = (node.inputs.size() - inMap.size()) + (node.outputs.size() - outMap.size());
                final boolean durMatch = wantTicks > 0 && node.durationTicks == wantTicks;
                final int score = nameSim * 100 - extras * 10 + (durMatch ? 1 : 0);
                if (score > bestScore) {
                    bestScore = score;
                    best = new Matched(node, inMap, outMap);
                }
            }
        }
        return best;
    }

    /**
     * Maps every YAML ingredient to a distinct port, or null if any has no counterpart.
     * Resolvable names must match the port's display name and quantity; unresolvable names
     * (chart-local aliases) match by quantity alone.
     */
    @Nullable
    private static Map<String, Integer> mapPorts(final Map<String, Double> wanted, final List<Port<?>> ports,
        final Map<String, ItemStack> itemIndex, final Map<String, FluidStack> fluidIndex) {
        final Map<String, Integer> result = new HashMap<>();
        final boolean[] used = new boolean[ports.size()];
        for (final Map.Entry<String, Double> w : wanted.entrySet()) {
            final String name = norm(w.getKey());
            final boolean resolvable = itemIndex.containsKey(name) || fluidIndex.containsKey(name);
            int found = -1;
            for (int i = 0; i < ports.size(); i++) {
                if (used[i]) continue;
                final Port<?> p = ports.get(i);
                if (!qtyMatches(p, w.getValue())) continue;
                if (resolvable && !norm(p.getDisplayName()).equals(name)) continue;
                found = i;
                break;
            }
            if (found < 0) return null;
            used[found] = true;
            result.put(w.getKey(), found);
        }
        return result;
    }

    private static boolean qtyMatches(final Port<?> port, final double qty) {
        final double effective = port.getAmount() * (double) port.getChance();
        return Math.abs(effective - qty) <= Math.max(0.01, qty * 0.01);
    }

    private static int nameSimilarity(final String want, final String have) {
        if (want.equals(have)) return 2;
        if (want.contains(have) || have.contains(want)) return 1;
        return 0;
    }

    // ── Config from YAML ──

    private static void applyConfig(final Node node, final Map<?, ?> entry, final Map<String, List<String>> problems) {
        final Object tier = entry.get("tier");
        if (tier != null) {
            final String option = voltageOption(String.valueOf(tier));
            if (option != null) {
                node.machineConfig.setString(Settings.VOLTAGE.key(), option);
            } else {
                problem(problems, "Unknown tier \"" + tier + "\"", node.machineName);
            }
        }
        if (entry.containsKey("number")) {
            node.machineConfig.setMachineCount((int) asDouble(entry.get("number"), 1));
            node.setMachineCountFixed(true);
        }
        for (final Object key : entry.keySet()) {
            final String k = String.valueOf(key);
            switch (k) {
                case "m", "tier", "I", "O", "eut", "dur", "number" -> {}
                default -> problem(problems, "\"" + k + "\" not applied", node.machineName);
            }
        }
    }

    @Nullable
    private static String voltageOption(final String tier) {
        final List<String> options = Settings.VOLTAGE.def().options;
        if (options == null) return null;
        for (final String o : options) {
            if (o.equalsIgnoreCase(tier)) return o;
        }
        return null;
    }

    // ── Ingredient resolution ──

    @Nullable
    private static ItemStack firstResolvable(final Map<String, Double> outs, final Map<String, ItemStack> itemIndex,
        final Map<String, FluidStack> fluidIndex) {
        for (final String name : outs.keySet()) {
            final String key = norm(name);
            final ItemStack item = itemIndex.get(key);
            if (item != null) return item;
            final FluidStack fluid = fluidIndex.get(key);
            if (fluid != null) {
                final ItemStack display = fluidLookupStack(fluid);
                if (display != null) return display;
            }
        }
        return null;
    }

    @Nullable
    private static ItemStack fluidLookupStack(final FluidStack fluid) {
        if (Compat.GREGTECH.isLoaded) {
            final ItemStack display = GTHooks.fluidDisplayStack(fluid);
            if (display != null) return display;
        }
        return FluidContainerRegistry.fillFluidContainer(
            new FluidStack(fluid.getFluid(), FluidContainerRegistry.BUCKET_VOLUME),
            FluidContainerRegistry.EMPTY_BUCKET.copy());
    }

    private static Map<String, ItemStack> buildItemIndex() {
        final Map<String, ItemStack> index = new HashMap<>();
        for (final ItemStack stack : ItemList.items) {
            if (stack == null) continue;
            try {
                index.putIfAbsent(norm(stack.getDisplayName()), stack);
            } catch (final Exception ignored) {
                // Broken display names on modded items must not kill the whole import.
            }
        }
        return index;
    }

    private static Map<String, FluidStack> buildFluidIndex() {
        final Map<String, FluidStack> index = new HashMap<>();
        for (final Fluid fluid : FluidRegistry.getRegisteredFluids()
            .values()) {
            final FluidStack fs = new FluidStack(fluid, 1000);
            index.putIfAbsent(norm(fs.getLocalizedName()), fs);
        }
        return index;
    }

    /** Lowercased, color codes stripped, whitespace collapsed. */
    private static String norm(final String s) {
        return s.replaceAll("§.", "")
            .toLowerCase(Locale.ROOT)
            .trim()
            .replaceAll("\\s+", " ");
    }

    // ── YAML helpers ──

    private static Map<String, Double> ioMap(final Object raw) {
        final Map<String, Double> result = new LinkedHashMap<>();
        if (raw instanceof final Map<?, ?> map) {
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                final double quantity = asDouble(entry.getValue(), 0);
                // Bracketed prefixes ("[recycle] chlorine") split pools to keep gtnh-flow's
                // weaker solver away from recycling loops; PlanNH's solver wants the real
                // loop, so strip them and merge the quantities.
                final String name = String.valueOf(entry.getKey())
                    .replaceFirst("^\\[[^\\]]*\\]\\s*", "");
                if (quantity > 0) result.merge(name, quantity, Double::sum);
            }
        }
        return result;
    }

    private static double asDouble(final Object value, final double fallback) {
        if (value instanceof final Number n) return n.doubleValue();
        if (value instanceof final String s) {
            try {
                return Double.parseDouble(s);
            } catch (final NumberFormatException ignored) {}
        }
        return fallback;
    }

    // ── Layout ──

    /** Longest-path columns (relaxation capped for cycles), nodes stacked within a column. */
    private static void layout(final Graph graph, final List<Matched> matched) {
        final Map<UUID, Integer> depth = new HashMap<>();
        for (final Matched m : matched) depth.put(m.node.id, 0);
        for (int pass = 0; pass < matched.size(); pass++) {
            boolean changed = false;
            for (final Edge e : graph.getEdges()) {
                final Integer src = depth.get(e.sourceNodeId);
                final Integer dst = depth.get(e.targetNodeId);
                if (src == null || dst == null) continue;
                if (src + 1 > dst) {
                    depth.put(e.targetNodeId, src + 1);
                    changed = true;
                }
            }
            if (!changed) break;
        }
        final Map<Integer, Integer> rows = new HashMap<>();
        for (final Matched m : matched) {
            final int col = depth.get(m.node.id);
            final int row = rows.merge(col, 1, Integer::sum) - 1;
            m.node.x = ORIGIN + col * COL_W;
            m.node.y = ORIGIN + row * ROW_H;
        }
    }
}
