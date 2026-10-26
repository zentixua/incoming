package ua.zentix.incoming.gametest.scenario;

import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import ua.zentix.incoming.Incoming;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Flight scenarios (root 5 of the foundation plan): instead of one example per property - weapon × target × disturbance × seed,
 *  and each flight has the same properties ({@link ScenarioRun}). The flight with one seed is repeated exactly, so the
 *  seed scripts 0 are checked against the reference trajectories ({@code trajectoriesMatchBaseline}, {@link Baseline}).
 * <p>
 * In a normal run GameTest (CI) — seed 0 of all combinations. More grains - {@code ./gradlew runScenarioSweep}
 *  ({@code -PscenarioSeeds=N}, up to {@link Scenario#MAX_SEEDS}): a separate template namespace, there are only scripts;
 *  there is also a selection of scripts and actual loading of chunks ({@link ScenarioMode}).
 */
@GameTestHolder(Incoming.MOD_ID)
public final class FlightScenarioTests {
    /** How many grains per combination (usual run - one). */
    private static final int SEEDS = Math.min(Scenario.MAX_SEEDS, Integer.getInteger("incoming.scenario.seeds", 1));
    /** Running scripts alone ({@code runScenarioSweep}): a template in a namespace that is only enabled there. */
    private static final boolean SWEEP = Boolean.getBoolean("incoming.scenario.sweep");
    /**
     * Scenarios in a batch: run simultaneously, each on its own site. Batch memory - site chunks: around each
     *  area of ​​full load, vanilla keeps a ring of under-generated chunks (generation dependencies),
     *  and the script keeps ~2000 chunks, of which ~80% is a ring. 40 scenarios - up to 83 thousand chunks and 2,3 GB
     *  living heap (CI has a heap of 4 GB - ¼ of the runner's memory, GameTest drops OutOfMemoryError), 20 - up to 48 thousand and 1,4 GB;
     *  in time 20 no longer (less than garbage collection). There are still a bunch of NBT unloaded chunks in the recording queue until she
     *  adds: {@link InstantChunks} waits for her once every 100 tick.
     */
    private static final int BATCH = 20;
    /** Test limit, tick: longest flight (Shahed 50 s × 1,5 with chase, B-2 with escape) with a margin. */
    private static final int TIMEOUT = 8000;

    private FlightScenarioTests() {}

    @GameTestGenerator
    public static Collection<TestFunction> flightScenarios() {
        List<Scenario> all = Scenario.all(SEEDS, ModList.get().isLoaded("sable"));
        String template = (SWEEP ? "incoming_sweep" : Incoming.MOD_ID) + ":pad";
        List<TestFunction> out = new ArrayList<>();
        all.removeIf(s -> !ScenarioMode.selected(s));
        if (all.isEmpty()) Incoming.LOG.error("SCENARIO: the selection of scenarios (-PscenarioOnly) did not match any - GameTest will not start");
        for (int i = 0; i < all.size(); i++) {
            Scenario s = all.get(i);
            out.add(new TestFunction("flight_scenarios_" + i / BATCH, "flightscenario." + s.id(), template, Rotation.NONE, TIMEOUT, 0,
                    true, false, 1, 1, true, h -> new ScenarioRun(h, s).start()));
        }
        return out;
    }
}
