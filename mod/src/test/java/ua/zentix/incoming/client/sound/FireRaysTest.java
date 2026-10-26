package ua.zentix.incoming.client.sound;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.FuelBurn;
import ua.zentix.incoming.nuclear.model.FuelPool;
import ua.zentix.incoming.sound.Propagation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fire blocking beams ({@link FireRays}, {@link SoundFilters#reach}): a raid from 300 distant fires cost the client
 *  a third tick - a beam for each audible fire in each tick, ~1800 path blocks each (0,85 → 17,9 ms, WAV-run Sol,
 * final #456). The scene of that run: fires of 2 MW on the ring 1,7–1,9 km, night, ear in the center.
 */
class FireRaysTest {
    private static final Vec3 EAR = new Vec3(0.5, 66.62, 0.5);
    /** Loaded columns of client chunks: drawing 12 chunks around the ear (with an engine reserve of 3). */
    private static final SoundFilters.Loaded LOADED = (cx, cz) -> Math.max(Math.abs(cx), Math.abs(cz)) <= 15;

    /**
     * {@code n} fires by 2 MW on the ring from {@code near} to {@code far} m (angles are in the middle between neighboring ones: none of
     *  stands exactly on the border of sectors), through 15 s after the impact.
     */
    private static List<CraterFire> ring(int n, double near, double far) {
        FuelPool law = new FuelPool(2e6 * 150 / FuelPool.Fuel.PETROL.heatJPerKg, 2, 150, 2e6, FuelPool.Fuel.PETROL);
        List<CraterFire> fires = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * (i + 0.5) / n, r = near + (far - near) * i / Math.max(1, n - 1);
            fires.add(new CraterFire(FuelBurn.at(law, BlockPos.containing(0.5 + r * Math.cos(a), 65, 0.5 + r * Math.sin(a))), null, 300));
        }
        return fires;
    }

    /** The world for rays: openness of the path to the point - {@code open}; counts rays, checks chunks and reads block and liquid. */
    private static final class World implements SoundFilters.Path {
        final ToDoubleFunction<Vec3> open;
        final Map<Vec3, List<Integer>> rayed = new HashMap<>();
        int tick, rays;
        long chunks, reads;

        World(ToDoubleFunction<Vec3> open) {
            this.open = open;
        }

        @Override
        public float open(Vec3 from, Vec3 to) {
            rayed.computeIfAbsent(to, k -> new ArrayList<>()).add(tick);
            rays++;
            reads += reads(from, from.add(to.subtract(from).scale(SoundFilters.reach(from, to, (cx, cz) -> {
                chunks++;
                return LOADED.at(cx, cz);
            }))));
            return (float) open.applyAsDouble(to);
        }

        @Override
        public float air(double distance) {
            return 1;
        }

        /** Reading the world from the {@code from → to} beam in an empty way: {@code Level.clip} reads a block and a liquid in each cell. */
        static long reads(Vec3 from, Vec3 to) {
            long[] n = {0};
            BlockGetter.traverseBlocks(from, to, null, (c, pos) -> {
                n[0] += 2;
                return null;
            }, c -> null);
            return n[0];
        }
    }

    /** Power of the sum: as much as all audible fires give through the real openness of their path (to the ceiling). */
    private static double law(List<CraterFire> fires, World world) {
        double p = 0;
        for (CraterFire f : fires) {
            double v = CraterFire.volume(Sources.BLAST_FIRE, f.wattsNow(EAR), f.burnCentre(), EAR, Propagation.Weather.NIGHT);
            double g = SoundFilters.blockedGain((float) world.open.applyAsDouble(f.burnCentre().add(0, 1.5, 0)));
            p += v * v * g * g;
        }
        return Math.min(1, p);
    }

    private static double power(Quarters q) {
        double p = 0;
        for (int k = 0; k < Quarters.N; k++) p += q.volume(k) * q.volume(k);
        return p;
    }

    /**
     * Rays into the world per tick (all access paths: both from the tick fire and from the budget) - no more than {@link FireRays#PER_TICK} and not
     *  grows with the number of fires: 30, 300 and 3000 audible fires have the same thing; each fire receives a beam no later than
     * {@link FireRays#lag} tick and is then updated no less frequently. Reading the world per tick (block and liquid in each ray cell,
     *  like {@code Level.clip}, and checking loaded columns): rays end on the last loaded chunk - there are not
     *  more of them than {@code PER_TICK} rays through the loaded square, against the ray before each fire before.
     */
    @Test
    void raysPerTickStayBoundedAsFiresGrow() {
        for (int n : new int[]{30, 300, 3000}) {
            List<CraterFire> fires = ring(n, 1700, 1900);
            FireRays rays = new FireRays();
            World world = new World(to -> 1);
            int lag = FireRays.lag(n), ticks = 3 * lag + 20;
            long before = 0, worstReads = 0, worstChunks = 0;
            for (world.tick = 0; world.tick < ticks; world.tick++) {
                Quarters q = new Quarters();
                int heard = 0;
                for (CraterFire f : fires) {
                    assertTrue(f.hear(EAR, Propagation.Weather.NIGHT));
                    if (f.heard) heard++;
                }
                assertEquals(n, heard, n + " fires: all audible (night, 2 MW, ≤ 1,9 km)");
                if (world.tick == 0) for (CraterFire f : fires) before += World.reads(EAR, f.burnCentre().add(0, 1.5, 0));
                world.rays = 0;
                world.chunks = world.reads = 0;
                int cast = rays.tick(fires, EAR, world, q);
                assertEquals(Math.min(n, FireRays.PER_TICK), cast, n + " fires, tick " + world.tick + ": rays");
                assertEquals(cast, world.rays, n + " fires, tick " + world.tick + ": rays into the world");
                worstReads = Math.max(worstReads, world.reads);
                worstChunks = Math.max(worstChunks, world.chunks);
            }
            assertEquals(n, world.rayed.size(), n + " fires: everyone has a ray");
            for (List<Integer> at : world.rayed.values()) {
                assertTrue(at.get(0) < lag, n + " fires: first beam to tick " + at.get(0) + ", not later " + lag);
                for (int k = 1; k < at.size(); k++) assertTrue(at.get(k) - at.get(k - 1) <= lag, n + " fires: updates less frequently " + lag + " tick");
            }
            System.out.printf(Locale.ROOT, "%d far fires: rays per tick %d (before: %d), block+fluid reads per tick %d (before: %d), chunk checks %d, every fire refreshed within %d ticks%n",
                    n, Math.min(n, FireRays.PER_TICK), n, worstReads, before, worstChunks, lag);
            // ray through the loaded square 31×31 columns: no more than 2 × (|dx| + |dy| + |dz| + 1) cells
            assertTrue(worstReads <= FireRays.PER_TICK * 2 * (3 * 31 * 16 + 1), n + " fires: world readings per tick " + worstReads);
        }
    }

    /**
     * Audible fire - in total from the first tick, and not from the first ray (review R12: before the ray the fire was silent, and 300 new fires
     *  gained power 19 tick - 0 % in the first tick, 5 % in the second). The budget specifies the obstacle, but does not decide whether the fire sounds: the fire reaches its ray through the openness known in its direction. The power of the sum with tick 0 and in each tick
     *  until the end of the queue is like all fires through their real path: everything is open (reviewer scene: 300 fires in 40 m), everything is
     *  behind the wall, and the wall is half a circle (east: the path is closed, where x is growing) - the first 16 rays go one at a time to each
     *  sector, and in each sector the openness is already known.
     */
    @Test
    void heardFiresJoinTheMixOnTheirFirstTick() {
        Map<String, ToDoubleFunction<Vec3>> worlds = Map.of(
                "all open", to -> 1,
                "all behind a wall", to -> 0,
                "wall to the east", to -> to.x > EAR.x ? 0 : 1);
        for (var w : worlds.entrySet()) {
            boolean wall = w.getKey().equals("wall to the east");
            for (double[] r : new double[][]{{40, 40}, {1700, 1900}}) {
                // the fire is exactly on the border of sectors (for 30 on the ring - at 270°) or in 40 m in a cage near the wall (x = x ear)
                //  stands on the other side of the wall from its sector: the wall is half a circle - far away and without such
                if (wall && r[0] == 40) continue;
                for (int n : r[0] == 40 ? new int[]{1, 300} : wall ? new int[]{32, 300, 3000} : new int[]{1, 32, 300, 3000}) {
                    List<CraterFire> fires = ring(n, r[0], r[1]);
                    FireRays rays = new FireRays();
                    World world = new World(w.getValue());
                    for (world.tick = 0; world.tick <= FireRays.lag(n) + 1; world.tick++) {
                        Quarters q = new Quarters();
                        for (CraterFire f : fires) f.hear(EAR, Propagation.Weather.NIGHT);
                        rays.tick(fires, EAR, world, q);
                        double want = law(fires, world), got = power(q);
                        assertEquals(want, got, 1e-6 * want, String.format(Locale.ROOT, "%s, %d fires in %.0f–%.0f m, tick %d: power sum %.8f of %.8f",
                                w.getKey(), n, r[0], r[1], world.tick, got, want));
                    }
                }
            }
        }
    }

    /**
     * Openness in the direction of a fire without a beam: average over the rays of audible fires in its sector, in the empty sector - the nearest
     *  sector with beams (two at an equal distance - the average of both).
     */
    @Test
    void knownOpennessIsTheNearestSectorWithRays() {
        List<CraterFire> fires = ring(FireRays.SECTORS * 2, 100, 100);
        for (CraterFire f : fires) f.hear(EAR, Propagation.Weather.NIGHT);
        // rays - only for two fires in the 0 sector (one is open, the other is closed) and for two in the 8 sector (closed)
        for (CraterFire f : fires) {
            int s = FireRays.sector(f.azimuth);
            if (s == 0 || s == 8) f.ray(EAR, new World(to -> FireRays.sector(Math.atan2(to.z - EAR.z, to.x - EAR.x)) == 0 && to.z > EAR.z + 20 ? 1 : 0), 1);
        }
        float[] known = FireRays.known(fires);
        assertEquals(0.5f, known[0], 1e-6, "sector 0: average of its rays");
        assertEquals(0f, known[8], 1e-6, "sector 8: its beam");
        assertEquals(0.5f, known[3], 1e-6, "sector 3: closer to 0");
        assertEquals(0f, known[6], 1e-6, "sector 6: closer to 8");
        assertEquals(0.25f, known[4], 1e-6, "sector 4: 0 and 8 on an equal basis - the average of both");
    }

    /**
     * The ray ends at the last loaded column of chunks along the path, and only there: searching the path in steps of a quarter block
     *  finds the same last loaded column (within the column), and with the loaded chunk far beyond the empty ones - the ray
     *  reaches it; target in a busy area - beam to the target.
     */
    @Test
    void reachEndsAtTheLastLoadedChunk() {
        Random r = new Random(456);
        for (int i = 0; i < 2000; i++) {
            Vec3 from = new Vec3(r.nextDouble() * 64 - 32, 66, r.nextDouble() * 64 - 32);
            double a = r.nextDouble() * 2 * Math.PI, d = 5 + r.nextDouble() * 2500;
            Vec3 to = from.add(d * Math.cos(a), r.nextDouble() * 40 - 20, d * Math.sin(a));
            boolean island = i % 3 == 0;
            int ix = Math.floorDiv((int) Math.floor(from.x + (to.x - from.x) * 0.8), 16), iz = Math.floorDiv((int) Math.floor(from.z + (to.z - from.z) * 0.8), 16);
            SoundFilters.Loaded loaded = (cx, cz) -> LOADED.at(cx, cz) || island && cx == ix && cz == iz;
            double got = SoundFilters.reach(from, to, loaded), brute = 0, len = Math.hypot(to.x - from.x, to.z - from.z);
            for (double t = 0; t <= 1; t += 0.25 / Math.max(1, len)) {
                if (loaded.at(Math.floorDiv((int) Math.floor(from.x + (to.x - from.x) * t), 16), Math.floorDiv((int) Math.floor(from.z + (to.z - from.z) * t), 16))) brute = t;
            }
            assertTrue(got >= brute - 1e-9 && got <= brute + 23 / Math.max(1, len) + 1e-9, "beam " + i + ": to " + got + ", overkill " + brute);
            if (island) assertTrue(got >= 0.8 - 23 / len, "beam " + i + ": chunk behind empty ones - beam to it");
        }
        assertEquals(1, SoundFilters.reach(EAR, EAR.add(100, 0, 50), LOADED), 1e-12, "target in a busy area - beam to target");
        assertEquals(1, SoundFilters.reach(EAR, EAR.add(0, 30, 0), LOADED), 1e-12, "vertically above the ear");
    }
}
