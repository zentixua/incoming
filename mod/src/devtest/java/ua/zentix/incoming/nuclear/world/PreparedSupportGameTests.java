package ua.zentix.incoming.nuclear.world;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/** Detached-snapshot experiments for finite prepared structural decisions. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PreparedSupportGameTests {
    @GameTest(template = "pad", timeoutTicks = 100, batch = "nuke_prepared_support_baseline", skyAccess = true)
    public static void finiteHaloCannotProveTheFarEndOfADetachedRoof(GameTestHelper h) {
        var context = StructuralRuinGameTests.fixture(h.getLevel(), (x, y, z) -> {
            if (z == 8 && y == 70 && x >= 0 && x < 56) return Blocks.STONE.defaultBlockState();
            if (z == 8 && x == 0 && y < 70) return Blocks.GLASS.defaultBlockState();
            return Blocks.AIR.defaultBlockState();
        }, 2);
        var near = Collapse.solve(context.cachedGridForTest(h.getLevel(), new ChunkPos(0, 0)));
        var far = Collapse.solve(context.cachedGridForTest(h.getLevel(), new ChunkPos(2, 0)));
        h.assertTrue(near.isGone(16, 69, 24), "Fixture did not fracture the only supporting column");
        h.assertTrue(near.isGone(16, 70, 24), "Local collapse did not release the cap");
        h.assertFalse(far.isGone(24, 70, 24), "Baseline no longer demonstrates the finite-window gap");
        Incoming.LOG.info("PREPARED_SUPPORT_BASELINE anchorBroken=true nearReleased=true farX40Retained=true halo=16");
        h.succeed();
    }

    @GameTest(template = "pad", timeoutTicks = 100, batch = "nuke_prepared_support_roof", skyAccess = true)
    public static void detachedRoofIsCompleteAcrossEverySection(GameTestHelper h) {
        var result = solve(roof(false, false), pos -> pos.equals(new BlockPos(0, 39, 1)), 0, 0, 0);
        h.assertTrue(result.metrics().detachedCells() == 56 * 3, "Incomplete roof removal");
        h.assertTrue(result.metrics().unresolvedCells() == 0, "Closed fixture remained unresolved");
        h.assertTrue(direct(result).size() == 1, "Direct fracture ownership changed");
        h.assertTrue(Collections.disjoint(detached(result), direct(result)), "Source emitted twice");
        Incoming.LOG.info("PREPARED_SUPPORT_ROOF {}", result.metrics());
        h.succeed();
    }

    @GameTest(template = "pad", timeoutTicks = 100, batch = "nuke_prepared_support_bridge", skyAccess = true)
    public static void bridgeRetainsItsDistantSurvivingFoundation(GameTestHelper h) {
        var one = solve(roof(true, false), pos -> pos.equals(new BlockPos(0, 39, 1)), 0, 0, 0);
        var both = solve(roof(true, false), pos -> pos.getY() == 39 && pos.getZ() == 1
                && (pos.getX() == 0 || pos.getX() == 55), 0, 0, 0);
        h.assertTrue(one.metrics().detachedCells() == 0 && one.metrics().unresolvedCells() == 0,
                "Boundary turned a supported bridge into a detached component");
        h.assertTrue(both.metrics().detachedCells() == 56 * 3, "Two broken foundations left bridge material behind");
        h.succeed();
    }

    @GameTest(template = "pad", timeoutTicks = 100, batch = "nuke_prepared_support_translation", skyAccess = true)
    public static void roofDecisionsDoNotDependOnChunkOrSectionTranslation(GameTestHelper h) {
        var expected = detached(solve(roof(false, false), pos -> pos.equals(new BlockPos(0, 39, 1)), 0, 0, 0));
        for (int shift : new int[]{-17, -1, 7, 15, 16, 31}) {
            int dy = shift & 15;
            var actual = solve(roof(false, false), pos -> pos.equals(new BlockPos(0, 39, 1)), shift, dy, -shift);
            var normalized = new HashSet<BlockPos>();
            for (BlockPos at : detached(actual)) normalized.add(at.offset(-shift, -dy, shift));
            h.assertTrue(normalized.equals(expected), "Support changed with translation " + shift);
            h.assertTrue(actual.metrics().unresolvedCells() == 0, "Translated closed roof remained open");
        }
        h.succeed();
    }

    @GameTest(template = "pad", timeoutTicks = 100, batch = "nuke_prepared_support_floating", skyAccess = true)
    public static void preexistingFloatingBuildKeepsItsMaterial(GameTestHelper h) {
        var result = solve(roof(false, true), pos -> pos.equals(new BlockPos(0, 39, 1)), 0, 0, 0);
        h.assertTrue(result.metrics().detachedCells() == 0, "A broken pendant gave a floating build gravity");
        h.assertTrue(result.metrics().unresolvedCells() == 0 && direct(result).size() == 1,
                "Closed floating fixture did not finish with exactly its direct removal");
        h.succeed();
    }

    @GameTest(template = "pad", timeoutTicks = 100, batch = "nuke_prepared_support_boundary", skyAccess = true)
    public static void missingBoundaryIsAResolvableDependencyNotAnAnchor(GameTestHelper h) {
        var all = sections(roof(true, false), pos -> pos.equals(new BlockPos(0, 39, 1)), 0, 0, 0);
        var partial = new ArrayList<>(all);
        partial.removeIf(section -> section.key.x() == 3 && section.key.y() == 2 && section.key.z() == 0);
        var pending = PreparedSupportPrototype.solve(partial, new PreparedSupportPrototype.Budget(200, 10000));
        h.assertTrue(pending.metrics().detachedCells() == 0 && pending.metrics().unresolvedCells() > 0,
                "Unknown far support was silently assumed present or absent");
        var ready = PreparedSupportPrototype.solve(all, new PreparedSupportPrototype.Budget(200, 10000));
        h.assertTrue(ready.metrics().unresolvedCells() == 0 && ready.metrics().detachedCells() == 0,
                "Adding the real distant foundation did not resolve the bridge");
        Collections.reverse(all);
        var reversed = PreparedSupportPrototype.solve(all, new PreparedSupportPrototype.Budget(200, 10000));
        h.assertTrue(detached(ready).equals(detached(reversed)) && reversed.metrics().unresolvedCells() == 0,
                "Boundary fixed point depends on section visit order");
        h.succeed();
    }

    @GameTest(template = "pad", timeoutTicks = 100, batch = "nuke_prepared_support_budget", skyAccess = true)
    public static void refusedCohortPublishesNoPartialRemoval(GameTestHelper h) {
        var all = sections(roof(false, false), pos -> pos.equals(new BlockPos(0, 39, 1)), 0, 0, 0);
        boolean refused = false;
        try { PreparedSupportPrototype.solve(all, new PreparedSupportPrototype.Budget(200, 1)); }
        catch (IllegalArgumentException expected) { refused = expected.getMessage().contains("component budget"); }
        h.assertTrue(refused, "Component ownership limit was ignored");
        var retry = PreparedSupportPrototype.solve(all, new PreparedSupportPrototype.Budget(200, 10000));
        h.assertTrue(retry.metrics().detachedCells() == 168 && direct(retry).size() == 1,
                "Refusal changed the immutable source or lost a planned removal");
        h.succeed();
    }

    @GameTest(template = "pad", timeoutTicks = 100, batch = "nuke_prepared_support_timed", skyAccess = true)
    public static void bridgeWaitsForItsLastSupportAcrossSeams(GameTestHelper h) {
        Predicate<BlockPos> cuts = pos -> pos.getY() == 39 && pos.getZ() == 1 && (pos.getX() == 0 || pos.getX() == 55);
        var input = sections(roof(true, false), cuts, 0, 0, 0);
        var times = timedSections(input, at -> at.getX() == 0 ? 10 : 30);
        var result = PreparedSupportPrototype.timed(times, 200, 2000);
        assertTimedRoof(h, result, 30);
        long early = result.plans().stream().flatMap(plan -> plan.detached.stream()).filter(release -> release.at() <= 10).count();
        h.assertTrue(early == 0, "First broken support released the bridge before the last support failed");
        var directTimes = result.plans().stream().flatMap(plan -> plan.direct.stream()).map(PreparedSupportPrototype.Release::at).sorted().toList();
        h.assertTrue(directTimes.equals(List.of(10.0, 30.0)), "Direct source times or ownership changed");
        Collections.reverse(times);
        assertTimedRoof(h, PreparedSupportPrototype.timed(times, 200, 2000), 30);
        var shifted = sections(roof(true, false), cuts, 15, 11, -17);
        assertTimedRoof(h, PreparedSupportPrototype.timed(timedSections(shifted, at -> at.getX() == 15 ? 10 : 30), 200, 2000), 30);
        Incoming.LOG.info("PREPARED_SUPPORT_TIMED sections={} visits={} cellPops={} boundaryBytes={} release=30 count=168",
                times.size(), result.sectionVisits(), result.cellPops(), result.boundaryBytes());
        h.succeed();
    }

    @GameTest(template = "pad", timeoutTicks = 100, batch = "nuke_prepared_support_timed_controls", skyAccess = true)
    public static void survivingAnchorAndFloatingBuildDoNotGainTimedRemovals(GameTestHelper h) {
        Predicate<BlockPos> cut = pos -> pos.equals(new BlockPos(0, 39, 1));
        for (var shape : List.of(roof(true, false), roof(false, true))) {
            var result = PreparedSupportPrototype.timed(timedSections(sections(shape, cut, 0, 0, 0), at -> 10), 200, 2000);
            h.assertTrue(result.plans().stream().allMatch(plan -> plan.detached.isEmpty() && plan.unresolved().isEmpty()),
                    "A surviving distant support or closed preexisting floating build acquired gravity");
            h.assertTrue(result.plans().stream().mapToInt(plan -> plan.direct.size()).sum() == 1, "Direct removal lost ownership");
        }
        h.succeed();
    }

    @GameTest(template = "pad", timeoutTicks = 100, batch = "nuke_prepared_support_timed_unknown", skyAccess = true)
    public static void unknownSupportTimeNeverBecomesAnEarlyRelease(GameTestHelper h) {
        var all = sections(roof(true, false), pos -> pos.equals(new BlockPos(0, 39, 1)), 0, 0, 0);
        var partial = new ArrayList<>(all);
        partial.removeIf(section -> section.key.x() == 3 && section.key.y() == 2 && section.key.z() == 0);
        var result = PreparedSupportPrototype.timed(timedSections(partial, at -> 10), 200, 2000);
        h.assertTrue(result.plans().stream().allMatch(plan -> plan.detached.isEmpty()), "Unknown last support became a removal at t10");
        h.assertTrue(result.plans().stream().mapToInt(plan -> plan.unresolved().cardinality()).sum() > 0,
                "Unknown-boundary lower and upper bounds were silently equated");
        var ready = PreparedSupportPrototype.timed(timedSections(all, at -> 10), 200, 2000);
        h.assertTrue(ready.plans().stream().allMatch(plan -> plan.detached.isEmpty() && plan.unresolved().isEmpty()),
                "Real distant support failed to resolve the unknown time");
        h.succeed();
    }

    @GameTest(template = "pad", timeoutTicks = 100, batch = "nuke_prepared_support_timed_budget", skyAccess = true)
    public static void timedWorkRefusalCannotPublishEarlyOrPartialMaterial(GameTestHelper h) {
        var sections = timedSections(sections(roof(false, false), pos -> pos.equals(new BlockPos(0, 39, 1)), 0, 0, 0), at -> 17);
        boolean refused = false;
        try { PreparedSupportPrototype.timed(sections, 200, 1); }
        catch (IllegalArgumentException expected) { refused = expected.getMessage().contains("work budget"); }
        h.assertTrue(refused, "Timed preparation exceeded its explicit work limit");
        assertTimedRoof(h, PreparedSupportPrototype.timed(sections, 200, 2000), 17);
        h.succeed();
    }

    private static void assertTimedRoof(GameTestHelper h, PreparedSupportPrototype.TimedResult result, double time) {
        h.assertTrue(result.plans().stream().mapToInt(plan -> plan.detached.size()).sum() == 168, "Timed roof source count changed");
        h.assertTrue(result.plans().stream().allMatch(plan -> plan.unresolved().isEmpty()
                && plan.detached.stream().allMatch(release -> release.at() == time)), "Wrong or unresolved last-support time");
    }

    private static List<PreparedSupportPrototype.TimedSection> timedSections(List<PreparedSupportPrototype.Section> sections,
                                                                           ToDoubleFunction<BlockPos> time) {
        // Static direct masks come from the same immutable input; no live-world lookup supplies timestamps.
        var staticPlan = PreparedSupportPrototype.solve(sections, new PreparedSupportPrototype.Budget(200, 10000));
        var result = new ArrayList<PreparedSupportPrototype.TimedSection>();
        for (int index = 0; index < sections.size(); index++) {
            var section = sections.get(index);
            BitSet direct = staticPlan.plans().get(index).direct();
            double[] times = new double[direct.cardinality()];
            for (int cell = direct.nextSetBit(0), ordinal = 0; cell >= 0; cell = direct.nextSetBit(cell + 1))
                times[ordinal++] = time.applyAsDouble(new BlockPos(section.key.x() * 16 + (cell & 15),
                        section.key.y() * 16 + (cell >> 8), section.key.z() * 16 + (cell >> 4 & 15)));
            result.add(new PreparedSupportPrototype.TimedSection(section, times));
        }
        return result;
    }

    private static StructuralRuinGameTests.Shape roof(boolean secondFoundation, boolean floating) {
        return (x, y, z) -> {
            if (y == 40 && x >= 0 && x < 56 && z >= 0 && z < 3) return Blocks.STONE.defaultBlockState();
            if (z == 1 && (x == 0 || secondFoundation && x == 55)) {
                if (y == 39) return Blocks.GLASS.defaultBlockState();
                if (!floating && y > 32 && y < 39) return Blocks.STONE.defaultBlockState();
                if (!floating && y == 32) return Blocks.OBSIDIAN.defaultBlockState();
            }
            return Blocks.AIR.defaultBlockState();
        };
    }

    private static PreparedSupportPrototype.Result solve(StructuralRuinGameTests.Shape shape, Predicate<BlockPos> removed,
                                                         int dx, int dy, int dz) {
        return PreparedSupportPrototype.solve(sections(shape, removed, dx, dy, dz), new PreparedSupportPrototype.Budget(200, 10000));
    }

    private static List<PreparedSupportPrototype.Section> sections(StructuralRuinGameTests.Shape shape, Predicate<BlockPos> removed,
                                                                 int dx, int dy, int dz) {
        for (var block : List.of(Blocks.AIR, Blocks.STONE, Blocks.GLASS, Blocks.OBSIDIAN)) Blast.ensure(block.defaultBlockState());
        var properties = Blast.publish();
        var sections = new ArrayList<PreparedSupportPrototype.Section>();
        for (int sy = (32 + dy >> 4) - 1; sy <= (40 + dy >> 4) + 1; sy++)
            for (int sz = (dz >> 4) - 1; sz <= (2 + dz >> 4) + 1; sz++)
                for (int sx = (dx >> 4) - 1; sx <= (55 + dx >> 4) + 1; sx++) {
                    var states = new BlockState[4096];
                    var gone = new BitSet(4096);
                    for (int cell = 0; cell < 4096; cell++) {
                        int x = sx * 16 + (cell & 15), y = sy * 16 + (cell >> 8), z = sz * 16 + (cell >> 4 & 15);
                        states[cell] = shape.at(x - dx, y - dy, z - dz);
                        if (removed.test(new BlockPos(x - dx, y - dy, z - dz))) gone.set(cell);
                    }
                    sections.add(new PreparedSupportPrototype.Section(new PreparedSupportPrototype.Key(sx, sy, sz),
                            states, gone, properties, -64));
                }
        return sections;
    }

    private static Set<BlockPos> detached(PreparedSupportPrototype.Result result) { return positions(result, false); }
    private static Set<BlockPos> direct(PreparedSupportPrototype.Result result) { return positions(result, true); }

    private static Set<BlockPos> positions(PreparedSupportPrototype.Result result, boolean direct) {
        var out = new HashSet<BlockPos>();
        for (var plan : result.plans()) {
            BitSet cells = direct ? plan.direct() : plan.detached();
            for (int cell = cells.nextSetBit(0); cell >= 0; cell = cells.nextSetBit(cell + 1))
                out.add(new BlockPos(plan.key.x() * 16 + (cell & 15), plan.key.y() * 16 + (cell >> 8), plan.key.z() * 16 + (cell >> 4 & 15)));
        }
        return out;
    }

    private PreparedSupportGameTests() {}
}
