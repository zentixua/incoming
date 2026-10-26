package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.SalvoData;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.target.Target;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A salvo flies as a wave, not a thread ({@link WeaponSpec.Lanes}): projectiles on one operator route each have
 * their own lane beside the path; lanes converge at the target, arrivals are spaced out.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SalvoLaneGameTests {
    private static final BlockPos RUNWAY_TARGET = new BlockPos(16, 3, 200);
    private static final int ROUNDS = 5;
    private static final long SEED = 20261023L;

    private SalvoLaneGameTests() {}

    /**
     * Five Shahed along one operator route (without launcher - from afar, out of the world, to the strip): in the middle of the route, each -
     *  in his own lane, the one that gives the salvo seed (one seed - one path), stripes - the width of the wave; in 60 blocks up to
     *  of its aiming point, the shift decreased to a fifth; everyone got to their point, arrivals - one at a time, not at once.
     *  The route enters from +Z and +X: there are no neighboring batches there yet.
     */
    @GameTest(template = "runway", timeoutTicks = 2400, batch = "salvo_lanes", skyAccess = true)
    public static void salvoFliesAsWave(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 target = Vec3.atCenterOf(h.absolutePos(RUNWAY_TARGET)).add(0, 0.5, 0);
        Vec3 a = h.absoluteVec(new Vec3(616, 0, 1500)), b = h.absoluteVec(new Vec3(16, 0, 900));
        Waypoints via = new Waypoints(List.of(a, b));
        UUID owner = UUID.randomUUID();
        WeaponSpec spec = WeaponType.DRONE.spec();
        WeaponSpec.Lanes lanes = spec.lanes();
        SalvoData.start(level, WeaponType.DRONE, ROUNDS, 0, new Target.Point(target), target, 0, owner, Loadout.Nuke.DEFAULT, via, null, false, SEED);
        StrikeGameTests.afterTest(h, () -> {
            SalvoData.get(level).cancel(level, owner, List.of());
            VirtualFlights.get(level).clear(level, p -> owner.equals(p.ownerId()));
            level.getEntities(EntityTypeTest.forClass(StrikeProjectile.class), p -> owner.equals(p.ownerId())).forEach(StrikeProjectile::discard);
        });
        // in launch order: the trail of each projectile, its aiming point and tick when it is gone
        Map<UUID, Round> rounds = new LinkedHashMap<>();
        int[] tick = {0};
        h.onEachTick(() -> {
            tick[0]++;
            List<StrikeProjectile> mine = new ArrayList<>(VirtualFlights.get(level).flights());
            mine.addAll(level.getEntities(EntityTypeTest.forClass(StrikeProjectile.class), p -> true));
            for (StrikeProjectile p : mine) {
                if (!owner.equals(p.ownerId()) || p.isRemoved()) continue;
                Round r = rounds.computeIfAbsent(p.getUUID(), k -> new Round(p.aimPoint()));
                r.trace.add(p.position());
                r.seen = tick[0];
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(rounds.size() == ROUNDS && SalvoData.get(level).size() == 0, "launched " + rounds.size() + " from " + ROUNDS);
            h.assertTrue(rounds.values().stream().allMatch(r -> r.seen < tick[0] - 1), "still flying");
            List<Round> order = List.copyOf(rounds.values());
            double[] mid = new double[ROUNDS];
            for (int i = 0; i < ROUNDS; i++) {
                Round r = order.get(i);
                // the middle of the section between the operator’s points: far from both the exit to the lane and the convergence
                mid[i] = r.lateral(a, b, a.add(b).scale(0.5));
                double lane = lanes.offset(SEED, i, ROUNDS);
                h.assertTrue(Math.abs(mid[i] - lane) <= 4, String.format(Locale.ROOT,
                        "projectile %d in the middle of the route in %.1f blocks from the path, its seed stripe is %.1f", i, mid[i], lane));
                // at its aiming point: lateral offset is gone (lane converges converge before it)
                Vec3 aim = flat(r.aim);
                Vec3 near = r.at(aim, 60);
                double close = r.lateral(b, aim, near);
                h.assertTrue(Math.abs(close) <= 0.3 * Math.abs(lane) + 3, String.format(Locale.ROOT,
                        "projectile %d in 60 blocks to the aiming point in %.1f blocks from the direct line to it (band %.1f)", i, close, lane));
                Vec3 last = r.trace.getLast();
                h.assertTrue(flat(last).distanceTo(aim) <= 8, "projectile " + i + " ended the flight not at its point: " + h.relativeVec(last)
                        + " against " + h.relativeVec(r.aim));
            }
            double lo = java.util.Arrays.stream(mid).min().orElseThrow(), hi = java.util.Arrays.stream(mid).max().orElseThrow();
            h.assertTrue(hi - lo >= lanes.halfWidth(), String.format(Locale.ROOT, "wave in the middle of the route, width %.1f, lanes ±%.0f",
                    hi - lo, lanes.halfWidth()));
            List<Integer> ends = order.stream().map(r -> r.seen).sorted().toList();
            for (int i = 1; i < ends.size(); i++) {
                h.assertTrue(ends.get(i) - ends.get(i - 1) >= 5, "arrivals at once: tick " + ends);
            }
            h.assertTrue(ends.getLast() - ends.getFirst() >= (ROUNDS - 1) * spec.salvo().gapMin() / 2, "arrivals are not spaced: tick " + ends);
        });
    }

    private static Vec3 flat(Vec3 p) {
        return new Vec3(p.x, 0, p.z);
    }

    /** Volley projectile in check: aiming point, trace by tick, last tick in flight. */
    private static final class Round {
        final Vec3 aim;
        final List<Vec3> trace = new ArrayList<>();
        int seen;

        Round(Vec3 aim) {
            this.aim = aim;
        }

        /**
         * Lateral shift from the straight line {@code from} → {@code to} (towards {@code side} = +1 for {@code Route.plan}, like for stripes) there,
         * , where the trace intersects the perpendicular to it at point {@code across}.
         */
        double lateral(Vec3 from, Vec3 to, Vec3 across) {
            double ux = to.x - from.x, uz = to.z - from.z, len = Math.hypot(ux, uz);
            ux /= len;
            uz /= len;
            Vec3 best = null;
            double bestAlong = Double.MAX_VALUE;
            for (Vec3 p : trace) {
                double along = Math.abs((p.x - across.x) * ux + (p.z - across.z) * uz);
                if (along < bestAlong) {
                    bestAlong = along;
                    best = p;
                }
            }
            return (best.x - across.x) * -uz + (best.z - across.z) * ux;
        }

        /** The trace point at the distance is closest to {@code distance} from {@code aim} horizontally. */
        Vec3 at(Vec3 aim, double distance) {
            Vec3 best = trace.getFirst();
            for (Vec3 p : trace) {
                if (Math.abs(flat(p).distanceTo(aim) - distance) < Math.abs(flat(best).distanceTo(aim) - distance)) best = p;
            }
            return flat(best);
        }
    }
}
