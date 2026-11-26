package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.MushroomModel;
import ua.zentix.incoming.util.StrikeClock;
import ua.zentix.incoming.util.WorldClock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The air reads the motion age M ({@code docs/design/time.md} §8.3 rule 2): a detonation's dust, spray, their drift and what
 * falls from them, and its mushroom age by real time plus the jumps counted in their dimension, never by the jumps someone
 * was awake for; fire keeps its own clock. A night everyone slept through (8 h) clears a 15 kt detonation's dust and mushroom.
 */
class AirMotionAgeTest {
    /** 15 kt surface burst at noon, calm (the dust stays over the place it rose from), scale 1, no fires: the dust alone. */
    private static final Detonation DUST = new Detonation(4, new Vec3(0, 64, 0), 64, 15, true, 0, 0, 0, 20_000, 7, 1f, false, 0.42f,
            Detonation.NO_DAY_TIME, false);
    /** The same with fires: the haze over them is on the strike clock, not on M. */
    private static final Detonation FIRES = new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, 0, 0.5f, 8, 20_000, 7, 1f, false, 0.42f);
    /** 8 hours of the day, world-clock ticks, and the same in real ticks of the air. */
    private static final double NIGHT = 8 * WorldClock.HOUR, NIGHT_TICKS = NIGHT * ClientClock.AIR_TICKS_PER_CLOCK_TICK;

    private static S2C.NukeClock packet(double counted, double unseen) {
        return new S2C.NukeClock(1000, List.of(), new S2C.NukeClock.Clocks(900, 72, counted, unseen, List.of()));
    }

    /** The client's counter path: a rise of the counted jumps in one level is a jump; the unseen ones, another level and a drop are not. */
    @Test
    void onlyACountedRiseInOneLevelIsAJump() {
        Object overworld = new Object(), nether = new Object();
        assertEquals(0, ClientClock.set(packet(500, 0), overworld), "the first count in a level is adopted");
        assertEquals(0, ClientClock.set(packet(500, 8000), overworld), "a night someone was awake for moves no air");
        assertEquals(NIGHT_TICKS, ClientClock.set(packet(500 + NIGHT, 8000), overworld), 1e-6, "a counted night");
        assertEquals(576_000, NIGHT_TICKS, 1e-9, "8 h of the air are 576 000 real ticks");
        assertEquals(0, ClientClock.set(packet(0, 0), nether), "another dimension's count is adopted, not a jump");
        assertEquals(0, ClientClock.set(packet(500 + NIGHT, 8000), overworld), "back in the overworld: adopted");
        assertEquals(0, ClientClock.set(packet(500, 8000), overworld), "a count that drops (a replay seek back) moves nothing");
        assertEquals(0, ClientClock.set(new S2C.NukeClock(1000, List.of(), S2C.NukeClock.Clocks.NONE), overworld),
                "a recording made before the counter: no jumps");
        assertEquals(0, ClientClock.counted());
    }

    /** The motion age is real time plus counted jumps since the detonation (the anchor's count at the strike), never the unseen ones. */
    @Test
    void motionAgeIsRealPlusCountedOnly() {
        StrikeClock c = StrikeClock.start(500);
        double real = 30 * 60 * 20;
        // a night with someone awake: the counted jumps stay, the air keeps its real age
        assertEquals(real, c.motion(real, 500) * 20, 1e-9);
        assertEquals(real + NIGHT_TICKS, c.motion(real, 500 + NIGHT) * 20, 1e-6);
        // the strike clock of the same detonation also moves by a counted jump, by the same amount
        assertEquals(c.strike(real, 900, 500) + NIGHT * WorldClock.SECONDS_PER_TICK, c.strike(real, 900, 500 + NIGHT), 1e-6);
    }

    /** Dust, spray, drift and ash read M through the field's motion mapping; the fire haze keeps the strike clock. */
    @Test
    void theDustAgesOnTheMotionAgeTheFiresDoNot() {
        double jump = 2 * 60 * 20;
        PallField real = new PallField(FIRES), moved = new PallField(FIRES);
        moved.motion(t -> t + jump);
        double[] a = new double[2], b = new double[2];
        for (double t : new double[]{600, 3000, 12_000, 30_000}) {
            for (double r : new double[]{300, 1500, 3000}) {
                assertEquals(real.dustTau(r, 200, t + jump, PallField.Water.DRY), moved.dustTau(r, 200, t, PallField.Water.DRY), 1e-9, "dust at " + r + ", " + t);
                assertEquals(real.ashRate(r, 0, t + jump, PallField.Water.DRY) > 0, moved.ashRate(r, 0, t, PallField.Water.DRY) > 0, "ash at " + r + ", " + t);
                real.source(r, 0, t + jump, a);
                moved.source(r, 0, t, b);
                assertEquals(a[0], b[0], 1e-9, "drift at " + r);
            }
            // the fires: what the haze draws is on the strike clock, untouched by the motion mapping
            assertEquals(real.fireLight(t), moved.fireLight(t), 1e-12);
            assertEquals(real.hazeVisible(t), moved.hazeVisible(t));
        }
    }

    /**
     * An 8-hour night everyone slept through, half an hour after a 15 kt burst: the dust is on its table and the mushroom up before
     * it, both gone after it; a night with someone awake leaves both as they were.
     */
    @Test
    void aCountedNightClearsFifteenKilotonDustAndMushroom() {
        PallField pall = new PallField(DUST);
        pall.waveTableNow();
        BlastWall wall = new BlastWall(DUST, false);
        StrikeClock c = StrikeClock.start(0);
        double real = 25 * 60 * 20, r = 1500;
        // someone awake: the count stays; M is the real age
        double awake = c.motion(real, 0) * 20 / (20.0 * DUST.scale());
        assertTrue(DustSources.onTable(DUST, wall, pall, awake), "the dust is on its table at 25 min");
        assertTrue(pall.dustTau(r, 0, real, PallField.Water.DRY) > 0.05, "the dust at 1.5 km at 25 min: " + pall.dustTau(r, 0, real, PallField.Water.DRY));
        assertTrue(pall.visible(real));
        assertNotNull(MushroomModel.shape(20 * 60, 15, 0, true, 3000), "the mushroom is up at 20 min");
        // everyone slept: M has the night
        double offset = c.motion(0, NIGHT) * 20;
        assertEquals(NIGHT_TICKS, offset, 1e-6);
        pall.motion(t -> t + offset);
        double asleep = (real + offset) / (20.0 * DUST.scale());
        assertFalse(DustSources.onTable(DUST, wall, pall, asleep), "the dust is still on its table after the night");
        assertEquals(0, pall.dustTau(r, 0, real, PallField.Water.DRY), 1e-12, "dust at 1.5 km after the night");
        assertFalse(pall.visible(real), "the pall still shows after the night");
        assertNull(MushroomModel.shape(20 * 60 + offset / 20, 15, 0, true, 3000), "the mushroom after the night");
    }
}
