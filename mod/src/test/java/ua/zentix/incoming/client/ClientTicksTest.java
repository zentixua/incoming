package ua.zentix.incoming.client;

import net.minecraft.world.TickRateManager;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastTime;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** The conventional strike's clocks run on the client level's tick (time.md §8.3 rule 9). */
class ClientTicksTest {
    /**
     * One client tick as {@code Minecraft.tick} orders it: our pre-tick decides, then the level's tick manager ticks,
     * then the particle engine runs if {@code runsNormally()}. Returns whether our clocks advanced; asserts they agree
     * with vanilla particles.
     */
    private static boolean clientTick(boolean paused, TickRateManager rates) {
        boolean ours = ClientTicks.runs(paused, rates);
        if (!paused) rates.tick();
        boolean particles = !paused && rates.runsNormally();
        assertEquals(particles, ours, "our clocks and vanilla particles disagree");
        if (ours) {
            BlastTime.advance();
            SmokeGrid.tick();
        }
        return ours;
    }

    /** {@code /tick freeze}: no advance; {@code /tick step 5}: exactly five ticks; unfreeze: every tick again. */
    @Test
    void freezeHoldsAndStepMovesByTheStep() {
        TickRateManager rates = new TickRateManager();
        long born = BlastTime.ticks();
        double seconds = SmokeGrid.seconds(0.5f);
        for (int t = 0; t < 20; t++) assertTrue(clientTick(false, rates));
        assertEquals(20, BlastTime.ticks() - born);
        assertEquals(seconds + 1, SmokeGrid.seconds(0.5f), 1e-9, "20 running ticks are one game second");

        rates.setFrozen(true);
        long frozen = BlastTime.ticks();
        double held = SmokeGrid.seconds(1);
        for (int t = 0; t < 200; t++) assertFalse(clientTick(false, rates));
        assertEquals(frozen, BlastTime.ticks(), "frozen: the blast clock stands");
        assertEquals(held, SmokeGrid.seconds(1), 0, "frozen: the cloud clock stands");

        rates.setFrozenTicksToRun(5);
        for (int t = 0; t < 40; t++) clientTick(false, rates);
        assertEquals(frozen + 5, BlastTime.ticks(), "/tick step 5 moves five ticks");

        rates.setFrozen(false);
        for (int t = 0; t < 10; t++) assertTrue(clientTick(false, rates));
        assertEquals(frozen + 15, BlastTime.ticks());
    }

    /** A paused game (single-player menu) holds the clocks; the tick manager is not even ticked. */
    @Test
    void pauseHolds() {
        TickRateManager rates = new TickRateManager();
        long at = BlastTime.ticks();
        for (int t = 0; t < 100; t++) assertFalse(clientTick(true, rates));
        assertEquals(at, BlastTime.ticks());
    }

    /**
     * A Flashback replay pauses by freezing the level's tick rate: the clocks hold like {@code /tick freeze}; its
     * play resumes them. A seek clears the conventional effects (they are not replayed), so a held clock never shows a
     * cloud from another recording position.
     */
    @Test
    void replayPauseHoldsAndSeekClears() {
        TickRateManager rates = new TickRateManager();
        rates.setFrozen(true);
        double held = SmokeGrid.seconds(1);
        for (int t = 0; t < 60; t++) clientTick(false, rates);
        assertEquals(held, SmokeGrid.seconds(1), 0);
        SmokeGrid.add(new SmokeGrid.Source() {
            @Override public boolean emit(double seconds, SmokeGrid.Sink out) { return true; }
            @Override public boolean ended() { return false; }
        });
        assertEquals(1, SmokeGrid.sources());
        SmokeGrid.reset();
        assertEquals(0, SmokeGrid.sources(), "seek: no cloud from before it");
        rates.setFrozen(false);
        assertTrue(clientTick(false, rates));
    }

    /**
     * {@code /tick rate 10}: a client tick lasts 100 ms, so in ten real seconds the clocks advance 100 ticks, five game
     * seconds: half speed, with the world. Wall time converts to ticks at the timer's length, not at 50 ms.
     */
    @Test
    void tickRateTenRunsAtHalfSpeed() {
        TickRateManager rates = new TickRateManager();
        rates.setTickRate(10);
        long tick = ClientTicks.tickNanos(rates.millisecondsPerTick());
        assertEquals(100_000_000L, tick);
        long wall = 10_000_000_000L;
        int ticks = (int) ClientTicks.ticks(wall, tick);
        assertEquals(100, ticks);
        double before = SmokeGrid.seconds(0);
        for (int t = 0; t < ticks; t++) clientTick(false, rates);
        assertEquals(5, SmokeGrid.seconds(0) - before, 1e-9, "10 real seconds at rate 10 are 5 game seconds");
        // a replay at 2x: Flashback's timer answers 25 ms
        assertEquals(40, ClientTicks.ticks(1_000_000_000L, ClientTicks.tickNanos(25f)), 1e-9);
        assertEquals(ClientTicks.NOMINAL_NANOS, ClientTicks.tickNanos(0f));
        assertEquals(ClientTicks.NOMINAL_NANOS, ClientTicks.tickNanos(Float.NaN));
    }

    /**
     * Every conventional moving-picture clock ticks only under the level-runs gate: blast timelines and their birth
     * stamps, clouds, crater and oil smoke, particles, fragments and the camera shake. A call outside the gate brings
     * back the clouds and the shake that ran under Flashback pause and {@code /tick freeze}.
     */
    @Test
    void conventionalClocksTickOnlyWhileTheLevelRuns() throws IOException {
        String src = Files.readString(Path.of("src/main/java/ua/zentix/incoming/client/IncomingClient.java"));
        assertTrue(src.contains("if (ClientTicks.runs()) ua.zentix.incoming.client.fx.BlastTime.advance();"), "BlastTime");
        assertTrue(src.contains("if (runs) ua.zentix.incoming.client.fx.smoke.SmokeGrid.tick();"), "SmokeGrid");
        assertTrue(src.contains("ua.zentix.incoming.client.fx.FireSmoke.tick(mc.level, runs);"), "FireSmoke");
        assertTrue(src.contains("CameraShake.tick(CameraShake.standing(mc.player), runs);"), "CameraShake");
        Matcher block = Pattern.compile("if \\(runs\\) \\{([^}]*)}").matcher(src);
        assertTrue(block.find(), "the particle block");
        for (String call : new String[] {"FxPool.INSTANCE.tick(mc.level);", "Fragments.tick();", "Effects.tick();"}) {
            assertTrue(block.group(1).contains(call), call + " inside the gate");
            assertEquals(src.indexOf(call), src.lastIndexOf(call), call + " only once");
        }
        for (String call : new String[] {"BlastTime.advance()", "SmokeGrid.tick()"})
            assertEquals(src.indexOf(call), src.lastIndexOf(call), call + " only once");
    }
}
