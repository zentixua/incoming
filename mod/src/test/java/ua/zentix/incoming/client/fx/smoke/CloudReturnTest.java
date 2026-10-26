package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.LocalWind;

import java.util.ArrayList;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CloudReturnTest {
    private static final SmokeGrid.Sink DISCARD = (x,y,z,ax,ay,az,sh,sv,s,d,w,r,g,b,k,rise) -> {};
    private static final WeaponType[] WEAPONS = {WeaponType.MISSILE, WeaponType.DRONE, WeaponType.ROCKET, WeaponType.LOITER};

    static final class Blast implements SmokeGrid.Source {
        final CloudModel model;
        final double born, x;
        final long[] clock;
        long reads;
        double sampled = Double.NEGATIVE_INFINITY;
        boolean drawn;

        Blast(WeaponType weapon, int terrain, double born, double x, long[] clock) {
            this.born = born; this.x = x; this.clock = clock;
            var ground = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0),
                    GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), (gx,gz) -> {
                reads++;
                // Deterministic work clock, not a wall-clock assertion in CI. Includes uncached terrain cost.
                clock[0] += 20;
                return terrain == 0 ? 0 : terrain == 1 ? (Math.abs(gx) % 30 < 8 ? 0 : 20)
                        : 20 * Math.sin(gx / 40) * Math.cos(gz / 40);
            });
            var site = terrain == 1 ? new LocalWind(0, 15, 20, 10, 1, 2, .6, 30) : LocalWind.OPEN;
            model = new CloudModel(BlastSource.of(weapon, false), new CloudModel.Air(.01, 5, -2, .2, 3, .6, site), false, 0, ground);
        }
        @Override public void maintain(double seconds) { model.maintain(seconds - born); }
        @Override public double age(double seconds) { return seconds - born; }
        @Override public boolean bounds(double seconds, SmokeBounds out) {
            model.bounds(age(seconds), -64, 320, out);
            model.condensationBounds(age(seconds), out);
            out.move(x, 0, 0); return true;
        }
        @Override public boolean emit(double seconds, SmokeGrid.Sink out) {
            clock[0] += 30_000; // fixed model work in addition to counted terrain reads
            model.blobs(age(seconds), (bx,by,bz,sh,sv,s,d,w,k,rise) ->
                    out.blob(x+bx,by,bz,sh,sv,s,d,w,1,1,1,k,rise));
            return age(seconds) < model.life;
        }
    }

    @Test void youngInvisibleRealCloudNeverEmitsOrReadsTerrain() {
        long[] clock = {0};
        var blast = new Blast(WeaponType.MISSILE, 1, 0, 100_000, clock);
        var sources = new ArrayList<SmokeGrid.Source>(); sources.add(blast);
        var prep = new SmokePreparation();
        blast.reads = 0; long before = clock[0];
        for (int f = 0; f < 60; f++) prep.draw(sources, Set.of(), f / 60.0, 0, 0, 0,
                b -> b.minX < 50_000, (s,t) -> { fail("hidden young cloud was drawn"); return DISCARD; }, () -> clock[0]);
        assertEquals(0, blast.reads);
        assertEquals(before, clock[0], "real CloudModel.emit must not be called for a hidden young source");
    }

    @Test void maintainedHistoryHasBoundedReturnWorkAndIsComplete() {
        for (var weapon : WEAPONS) for (int terrain = 0; terrain < 3; terrain++) {
            for (double hidden : new double[]{10, 60, 120, 300, 600, 1800}) {
                for (boolean seen : new boolean[]{false, true}) {
                    var blast = new Blast(weapon, terrain, 0, 0, new long[1]);
                    if (seen) for (double t : GroundDust.PATH) {
                        if (t > 120) break;
                        blast.emit(t, DISCARD);
                    }
                    double t = (seen ? 120 : 0) + hidden;
                    // Hidden paths are maintained at their existing fine nodes, without CloudModel emission.
                    for (double at : GroundDust.PATH) {
                        if (at > t) break;
                        if (!seen || at > 120) blast.maintain(at);
                    }
                    blast.reads = 0;
                    var first = new ArrayList<double[]>();
                    blast.model.blobs(t, (x,y,z,sh,sv,s,d,w,k,rise) -> first.add(new double[]{x,y,z,sh,sv,s,d,w}));
                    assertTrue(blast.reads <= 5_000,
                            weapon + " terrain=" + terrain + " hidden=" + hidden + " seen=" + seen + " reads=" + blast.reads);
                    var again = new ArrayList<double[]>();
                    blast.model.blobs(t, (x,y,z,sh,sv,s,d,w,k,rise) -> again.add(new double[]{x,y,z,sh,sv,s,d,w}));
                    assertEquals(first.size(), again.size());
                    for (int i = 0; i < first.size(); i++) assertArrayEquals(first.get(i), again.get(i), 1e-9,
                            "the first return must already be current, with no catch-up left for later frames");
                }
            }
        }
    }

    @Test void unpreparedLongHistoryCannotBypassTheFrameSlice() {
        for (double age : new double[]{60, 600, 1800}) {
            long[] clock = {0};
            var blast = new Blast(WeaponType.MISSILE, 2, 0, 0, clock);
            var sources = new ArrayList<SmokeGrid.Source>(); sources.add(blast);
            var prep = new SmokePreparation();
            for (int f = 0; f < 8; f++) {
                long before = blast.reads;
                prep.draw(sources, Set.of(), age, 0, 0, 0, b -> true, (s,t) -> DISCARD, () -> clock[0]);
                // A completed fine path node can finish just beyond the cooperative boundary.
                assertTrue(blast.reads - before <= 30_000, "unbounded return work at age " + age);
            }
        }
    }

    @Test void hundredCloudTurnReturnsWithinQuarterSecondWithoutFreezingVisibleClouds() {
        var prep = new SmokePreparation();
        var sources = new ArrayList<SmokeGrid.Source>();
        var blasts = new ArrayList<Blast>();
        long[] clock = {0};
        for (int i = 0; i < 100; i++) {
            var b = new Blast(WEAPONS[i % 4], i % 2 + 1, i, i % 2 == 0 ? 0 : 100_000, clock);
            sources.add(b); blasts.add(b);
        }
        SmokePreparation.Draw draw = (source, at) -> {
            var b = (Blast)source; b.sampled = at; b.drawn = true; return DISCARD;
        };
        for (int f = 0; f < 250 * 60; f++) {
            double t = f / 60.0;
            prep.draw(sources, Set.of(), t, 0, 0, 0, b -> t < 130 || b.minX < 50_000, draw, () -> clock[0]);
        }
        var fresh = new Blast(WeaponType.MISSILE, 2, 250, 0, clock);
        sources.add(fresh); blasts.add(fresh);
        for (int f = 0; f < 180; f++) {
            double t = 250 + f / 60.0;
            blasts.forEach(b -> b.drawn = false);
            prep.draw(sources, Set.of(), t, 0, 0, 0, b -> true, draw, () -> clock[0]);
            assertTrue(fresh.drawn, "new strike must be shown immediately");
            for (var b : blasts) {
                if (b.drawn) assertTrue(t - b.sampled <= SmokePreparation.MAX_SAMPLE_AGE);
                if (b.x == 0 || f >= 14) assertTrue(b.drawn, "visible cloud froze at frame " + f);
            }
        }
    }
}
