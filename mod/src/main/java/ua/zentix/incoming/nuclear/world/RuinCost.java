package ua.zentix.incoming.nuclear.world;

import net.minecraft.world.level.ChunkPos;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-chunk ruin cost by ground overpressure band at the chunk centre, per detonation: background plan time, plan size,
 * server-thread snapshot and swap time. Feeds the budget forecast for larger yields: pressure bands keep their shape
 * and scale in area with yield^(2/3), so per-band costs at 15 kt price the chunk counts of 100 kt and 1 Mt.
 */
final class RuinCost {
    /** Lower band edges, psi, descending; the last band takes everything below. */
    private static final double[] EDGES = {20, 10, 5, 3, 2, 1, 0.5, 0};
    static final int BANDS = EDGES.length;
    private static final LongAdder[] PLAN_NS = adders(), COLLAPSE_NS = adders(), PLANS = adders(), PLAN_BYTES = adders(), PLAN_CELLS = adders(),
            SHOT_NS = adders(), SHOTS = adders(), SWAP_NS = adders(), SWAPS = adders();

    private RuinCost() {}

    private static LongAdder[] adders() {
        LongAdder[] a = new LongAdder[BANDS];
        for (int i = 0; i < BANDS; i++) a[i] = new LongAdder();
        return a;
    }

    static int band(Detonation d, ChunkPos pos) {
        double psi = d.psi(new net.minecraft.world.phys.Vec3(pos.getMiddleBlockX() + 0.5, d.groundY() + 1, pos.getMiddleBlockZ() + 0.5));
        int b = 0;
        while (b < BANDS - 1 && psi < EDGES[b]) b++;
        return b;
    }

    static void plan(int band, long nanos, long collapseNanos, RuinPlan plan) {
        PLAN_NS[band].add(nanos);
        COLLAPSE_NS[band].add(collapseNanos);
        PLANS[band].increment();
        if (plan != null) {
            PLAN_BYTES[band].add(plan.bytes());
            PLAN_CELLS[band].add(plan.cells());
        }
    }

    static void shot(int band, long nanos) {
        SHOT_NS[band].add(nanos);
        SHOTS[band].increment();
    }

    static void swap(int band, long nanos) {
        SWAP_NS[band].add(nanos);
        SWAPS[band].increment();
    }

    /** One log line per non-empty band: count and mean cost. */
    static void log(int detonation) {
        for (int b = 0; b < BANDS; b++) {
            long n = PLANS[b].sum(), s = SWAPS[b].sum(), w = SHOTS[b].sum();
            if (n == 0 && s == 0) continue;
            Incoming.LOG.info(String.format(Locale.ROOT,
                    "Ruin cost #%d band %s psi: plans %d avg %.2f ms (collapse %.2f), %.1f KB, %.0f cells; snapshots %d avg %.3f ms; swaps %d avg %.3f ms",
                    detonation, b == 0 ? ">" + EDGES[0] : EDGES[b] + "-" + EDGES[b - 1], n, n == 0 ? 0 : PLAN_NS[b].sum() / 1e6 / n, n == 0 ? 0 : COLLAPSE_NS[b].sum() / 1e6 / n,
                    n == 0 ? 0 : PLAN_BYTES[b].sum() / 1024.0 / n, n == 0 ? 0 : (double) PLAN_CELLS[b].sum() / n,
                    w, w == 0 ? 0 : SHOT_NS[b].sum() / 1e6 / w, s, s == 0 ? 0 : SWAP_NS[b].sum() / 1e6 / s));
        }
        for (LongAdder[] a : new LongAdder[][]{PLAN_NS, COLLAPSE_NS, PLANS, PLAN_BYTES, PLAN_CELLS, SHOT_NS, SHOTS, SWAP_NS, SWAPS}) for (LongAdder x : a) x.reset();
    }
}
