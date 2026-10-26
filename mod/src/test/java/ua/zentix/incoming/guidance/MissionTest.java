package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissionTest {
    /** tick until the end of the stock at a constant speed: the stock is checked at the beginning of the tick, the flow rate at the end. */
    private static int ticksToExhaust(Mission m, double speed) {
        int t = 0;
        while (!m.exhausted()) {
            m.spend(speed);
            t++;
        }
        return t;
    }

    /**
     * Plan: path × 1.5 and reserve 600 tick at cruising speed - at cruising speed the flight ends through
     * path / cruising × 1.5 + 600 tick.
     */
    @Test
    void planOnCruiseMatchesOldLifetime() {
        for (double cruise : new double[]{1.6, 2.1, 4, 12}) {
            for (double path : new double[]{0, 100, 1234.5, 6000}) {
                Mission m = Mission.plan(path, cruise);
                assertEquals(path * 1.5 + 600 * cruise, m.range(), 1e-9);
                // time limit in tick: (int) (path / march × 1.5) + 600 (no start here)
                int old = (int) (path / cruise * 1.5) + 600;
                assertEquals(old, ticksToExhaust(m, cruise), 1, "marching " + cruise + ", path " + path);
            }
        }
    }

    /**
     * Parking on the launcher and waiting for the target area does not waste the reserve: the projectile that is waiting (speed 0) will not self-destruct - waiting has its limit. The slow section (accelerator acceleration, rocket launch)
     *  spends exactly the distance traveled: the reserve is enough for the path according to plan, no matter how slowly it goes.
     */
    @Test
    void waitsAndSlowLegsSpendOnlyTheirPath() {
        Mission m = Mission.plan(1000, 4);
        for (int t = 0; t < 100_000; t++) m.spend(0);
        assertEquals(1000 * 1.5 + 600 * 4, m.range(), 1e-9);
        // rocket: 40 tick accelerator to 3.4 block/tick, then turbine + 0.09 to sustainer 4, path 1000 blocks according to plan
        Mission slow = Mission.plan(1000, 4);
        double flown = 0, speed = 0;
        int t = 0;
        while (flown < 1000) {
            speed = t < 40 ? Math.min(3.4, speed + 0.09) : Math.min(4, speed + 0.09);
            assertFalse(slow.exhausted(), "stock ran out at " + flown + " blocks from 1000");
            slow.spend(speed);
            flown += speed;
            t++;
        }
        assertEquals(1000 * 1.5 + 600 * 4 - flown, slow.range(), 1e-6);
    }

    /**
     * MLRS outside the world: while the target area is not ready, the trajectory time is extended (tempo < 1) - the projectile passes the same arc
     *  for a larger number of world ticks and spends exactly its length: the “arc + reserve” reserve does not end until the point of impact.
     */
    @Test
    void stretchedBallisticsSpendsTheArc() {
        Random rnd = new Random(7);
        for (int i = 0; i < 200; i++) {
            Vec3 from = new Vec3(0, 70, 0);
            Vec3 to = new Vec3(200 + rnd.nextDouble() * 600, 60 + rnd.nextDouble() * 20, rnd.nextDouble() * 200 - 100);
            int n = 60 + rnd.nextInt(200);
            Vec3 v0 = Ballistics.launchVelocity(from, to, n);
            double arc = 0;
            for (int k = 0; k < n; k++) arc += Ballistics.at(from, v0, k + 1).subtract(Ballistics.at(from, v0, k)).length();
            Mission m = Mission.of(arc + 200 * 4);
            double t = 0;
            int worldTicks = 0;
            while (t < n) {
                // the pace floats between almost stopping and 1, like the RocketEntity.stretch stretch
                double rate = Math.max(0.02, Math.min(1, 0.5 + 0.5 * Math.sin(worldTicks * 0.05)));
                double step = Math.min(rate, n - t);
                assertFalse(m.exhausted(), "stock has run out on the arc, t = " + t + " from " + n);
                m.spend(Ballistics.at(from, v0, t + step).subtract(Ballistics.at(from, v0, t)).length());
                t += step;
                worldTicks++;
            }
            assertTrue(worldTicks > n, "the trajectory did not stretch");
            // fractional steps along an arc - chords, their sum is not longer than the arc itself (up to the difference between the chords of the whole tick and the arc)
            assertEquals(200 * 4, m.range(), 1, "the extended flight did not waste the length of the arc");
        }
    }

    /** Pursuit: target shift × 1.5 - no fractions or rounding. Shift 0 or negative does not change anything. */
    @Test
    void chaseAddsMovedPath() {
        Mission m = Mission.plan(100, 2.1);
        double before = m.range();
        for (int i = 0; i < 1000; i++) m.chase(0.01);
        assertEquals(before + 1000 * 0.01 * 1.5, m.range(), 1e-9);
        m.chase(0);
        m.chase(-5);
        assertEquals(before + 15, m.range(), 1e-9);
    }

    /**
     * Loss of goal: reserve - on the way to the last point × 1.5 and 200 tick on the march, once;
     * pursuit after that the reserve does not increase beyond the cut. The stock does not grow below this. Retargeting removes the flag.
     */
    @Test
    void loseCapsOnce() {
        Mission m = Mission.plan(3000, 2.1);
        m.chase(2000);
        assertTrue(m.lose(100, 2.1));
        double capped = 100 * 1.5 + 200 * 2.1;
        assertEquals(capped, m.range(), 1e-9);
        // on the main line - ceil(time before impact × 1.5) + 200 tick
        assertEquals((int) Math.ceil(100 / 2.1 * 1.5) + 200, ticksToExhaust(Mission.load(m.save()), 2.1), 1);
        assertFalse(m.lose(10, 2.1), "cut for the second time");
        assertEquals(capped, m.range(), 1e-9);

        Mission low = Mission.of(50);
        assertTrue(low.lose(1000, 2.1));
        assertEquals(50, low.range(), 1e-9);

        m.retarget(40);
        assertEquals(capped + 60, m.range(), 1e-9);
        assertTrue(m.lose(10, 2.1), "after retargeting, the new target is not yet lost");
    }

    @Test
    void saveRoundTrip() {
        Mission m = Mission.plan(777, 4).extend(123);
        m.spend(55.5);
        m.lose(10_000, 4);
        Mission back = Mission.load(m.save());
        assertEquals(m.range(), back.range());
        assertFalse(back.lose(1, 4), "the “cut down” flag was not preserved");
    }

    /**
     * Saving with a lifespan (without a power reserve): the remainder of the period (waiting for an area in it does not count) and fractional ticks
     * pursuits - at the marching speed of saving; the rocket has 11.5 block/tick the rest of the way - 11.5 block/tick.
     */
    @Test
    void migratesLifetime() {
        // term 400, age 100, of which 20 - waiting for the area: 320 tick and half a chase
        Mission m = Mission.fromLifetime(400 - (100 - 20), 0.5, false, 4);
        assertEquals(320.5 * 4, m.range(), 1e-9);
        assertEquals(320.5 * 11.5, Mission.fromLifetime(320, 0.5, false, 11.5).range(), 1e-9);
        Mission capped = Mission.fromLifetime(320, 0, true, 4);
        assertFalse(capped.lose(1, 4), "the “truncated” flag is not moved");
        assertTrue(Mission.fromLifetime(-3, 0, false, 4).exhausted(), "expired shell received stock");
    }
}
