package ua.zentix.incoming.client.sound;

import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Barrier rays from ear to crater fires — at most {@link #PER_TICK} per tick regardless of fire count (without
 * Minecraft, covered by a unit test). A ray to each audible fire each tick cost a raid of 300 fires at 1.7–1.9 km
 * 300 rays of ~1800 blocks per tick; client tick in Sol's scene after #456: 0.85 → 17.9 ms. Each fire now remembers
 * its last ray's openness; each tick gives rays to audible fires without one (one per sector while any remain),
 * then the stalest. The ray itself ends at the last loaded chunk ({@link SoundFilters#reach}): client world beyond
 * is empty.
 * <p>
 * Budget decides only when the barrier is refined, not whether fire is audible: an audible fire joins the sum
 * that same tick ({@link #tick}), using openness known in its direction until its own ray ({@link #known}). The
 * budget costs barrier lag: each fire's ray updates every {@link #lag} ticks (300 fires: up to 19 ticks, 0.95 s;
 * 3000: up to 188, 9.4 s); a fire whose wall appeared or disappeared (player rounded a corner) sounds as before
 * until its ray. The loop does not hide this: volume approaches target by 0.05 per tick ({@code AmbientLoop}),
 * a smaller step takes one tick.
 */
final class FireRays {
    /** Rays per tick (world-read budget: 300 fires cycle in ~1 s). */
    static final int PER_TICK = 16;
    /**
     * Direction sectors around the ear for openness known toward a fire: as many as rays per tick — first raid
     * tick gives a ray to every sector containing fires.
     */
    static final int SECTORS = PER_TICK;
    /** Latest first: queue keeps the stalest by last ray tick; without a ray, by order within its sector. */
    private static final Comparator<CraterFire> LATEST_FIRST = Comparator.comparingLong((CraterFire f) -> f.rayAt).thenComparingInt(f -> f.turn).reversed();

    private long tick;

    /**
     * Tick after all {@link CraterFire#hear} calls: rays ({@link #cast}), then every audible fire into {@code loops}
     * via its last ray, or without one, via openness known in its direction ({@link #known}). Returns ray count.
     */
    int tick(List<CraterFire> fires, Vec3 ear, SoundFilters.Path path, Quarters loops) {
        int cast = cast(fires, ear, path);
        float[] known = known(fires);
        for (CraterFire f : fires) f.mix(loops, path, f.rayed() ? f.open : known[sector(f.azimuth)]);
        return cast;
    }

    /**
     * Rays from {@code ear} to audible fires ({@link CraterFire#heard}): first those without a ray (one from each
     * sector first), then the stalest, at most {@link #PER_TICK}. Returns ray count.
     */
    int cast(List<CraterFire> fires, Vec3 ear, SoundFilters.Path path) {
        tick++;
        int[] line = new int[SECTORS];
        PriorityQueue<CraterFire> oldest = new PriorityQueue<>(PER_TICK + 1, LATEST_FIRST);
        for (CraterFire f : fires) {
            if (!f.heard) continue;
            f.turn = f.rayed() ? 0 : line[sector(f.azimuth)]++;
            oldest.add(f);
            if (oldest.size() > PER_TICK) oldest.poll();
        }
        for (CraterFire f : oldest) f.ray(ear, path, tick);
        return oldest.size();
    }

    /**
     * Openness known in each sector: mean of audible fires' latest rays, or nearest sector with rays if none here.
     * The path near the ear determines the barrier (house walls, ridge, forest); beyond loaded world is empty:
     * fires in one direction at one range pass the same blocks near the ear. ⚠ A fire much closer or farther than
     * its sector neighbours may lie across a barrier — this is an estimate until its ray, within {@link #lag}
     * ticks. After {@link #cast}, at least one audible fire has a ray if any fire is audible.
     */
    static float[] known(List<CraterFire> fires) {
        float[] sum = new float[SECTORS], known = new float[SECTORS];
        int[] n = new int[SECTORS];
        for (CraterFire f : fires) {
            if (!f.heard || !f.rayed()) continue;
            int s = sector(f.azimuth);
            sum[s] += f.open;
            n[s]++;
        }
        for (int s = 0; s < SECTORS; s++) {
            known[s] = Float.NaN;
            for (int d = 0; d <= SECTORS / 2 && Float.isNaN(known[s]); d++) {
                int a = Math.floorMod(s - d, SECTORS), b = Math.floorMod(s + d, SECTORS);
                if (n[a] + n[b] > 0) known[s] = (sum[a] + (a != b ? sum[b] : 0)) / (n[a] + (a != b ? n[b] : 0));
            }
        }
        return known;
    }

    /** Sector for direction {@code azimuth} (atan2(dz, dx)). */
    static int sector(double azimuth) {
        return Math.floorMod((int) Math.floor(azimuth / (2 * Math.PI) * SECTORS), SECTORS);
    }

    /** Ticks for rays to reach (and update) each of {@code fires} audible fires. */
    static int lag(int fires) {
        return Math.max(1, (fires + PER_TICK - 1) / PER_TICK);
    }
}
