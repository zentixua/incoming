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
 * Залп летит волной, а не ниткой ({@link WeaponSpec.Lanes}): снаряды одного маршрута оператора — каждый в своей полосе
 * сбоку от пути, к цели полосы сходятся, прилёты разнесены.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SalvoLaneGameTests {
    private static final BlockPos RUNWAY_TARGET = new BlockPos(16, 3, 200);
    private static final int ROUNDS = 5;
    private static final long SEED = 20261023L;

    private SalvoLaneGameTests() {}

    /**
     * Пять шахедов по одному маршруту оператора (без пусковой — издалека, вне мира, к полосе): на середине маршрута каждый —
     * в своей полосе, той, что даёт зерно залпа (одно зерно — одни пути), полосы — на ширину волны; в 60 блоках до
     * своей точки прицеливания сдвиг сошёл до пятой части; каждый попал в свою точку, прилёты — по одному, не разом.
     * Маршрут заходит с +Z и +X: там соседних партий ещё нет.
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
        // по порядку пуска: след каждого снаряда, его точка прицеливания и тик, когда его не стало
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
            h.assertTrue(rounds.size() == ROUNDS && SalvoData.get(level).size() == 0, "пущено " + rounds.size() + " из " + ROUNDS);
            h.assertTrue(rounds.values().stream().allMatch(r -> r.seen < tick[0] - 1), "ещё летят");
            List<Round> order = List.copyOf(rounds.values());
            double[] mid = new double[ROUNDS];
            for (int i = 0; i < ROUNDS; i++) {
                Round r = order.get(i);
                // середина участка между точками оператора: далеко и от выхода в полосу, и от схождения
                mid[i] = r.lateral(a, b, a.add(b).scale(0.5));
                double lane = lanes.offset(SEED, i, ROUNDS);
                h.assertTrue(Math.abs(mid[i] - lane) <= 4, String.format(Locale.ROOT,
                        "снаряд %d на середине маршрута в %.1f блоках от пути, его полоса по зерну — %.1f", i, mid[i], lane));
                // у своей точки прицеливания: сдвиг сошёл (полоса сходится за converge до неё)
                Vec3 aim = flat(r.aim);
                Vec3 near = r.at(aim, 60);
                double close = r.lateral(b, aim, near);
                h.assertTrue(Math.abs(close) <= 0.3 * Math.abs(lane) + 3, String.format(Locale.ROOT,
                        "снаряд %d в 60 блоках до точки прицеливания в %.1f блоках от прямой на неё (полоса %.1f)", i, close, lane));
                Vec3 last = r.trace.getLast();
                h.assertTrue(flat(last).distanceTo(aim) <= 8, "снаряд " + i + " кончил полёт не у своей точки: " + h.relativeVec(last)
                        + " против " + h.relativeVec(r.aim));
            }
            double lo = java.util.Arrays.stream(mid).min().orElseThrow(), hi = java.util.Arrays.stream(mid).max().orElseThrow();
            h.assertTrue(hi - lo >= lanes.halfWidth(), String.format(Locale.ROOT, "волна на середине маршрута шириной %.1f, полосы ±%.0f",
                    hi - lo, lanes.halfWidth()));
            List<Integer> ends = order.stream().map(r -> r.seen).sorted().toList();
            for (int i = 1; i < ends.size(); i++) {
                h.assertTrue(ends.get(i) - ends.get(i - 1) >= 5, "прилёты разом: тики " + ends);
            }
            h.assertTrue(ends.getLast() - ends.getFirst() >= (ROUNDS - 1) * spec.salvo().gapMin() / 2, "прилёты не разнесены: тики " + ends);
        });
    }

    private static Vec3 flat(Vec3 p) {
        return new Vec3(p.x, 0, p.z);
    }

    /** Снаряд залпа в проверке: точка прицеливания, след по тикам, последний тик в полёте. */
    private static final class Round {
        final Vec3 aim;
        final List<Vec3> trace = new ArrayList<>();
        int seen;

        Round(Vec3 aim) {
            this.aim = aim;
        }

        /**
         * Сдвиг вбок от прямой {@code from} → {@code to} (в сторону {@code side} = +1 у {@code Route.plan}, как у полос) там,
         * где след пересекает перпендикуляр к ней в точке {@code across}.
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

        /** Точка следа на расстоянии ближе всего к {@code distance} от {@code aim} по горизонтали. */
        Vec3 at(Vec3 aim, double distance) {
            Vec3 best = trace.getFirst();
            for (Vec3 p : trace) {
                if (Math.abs(flat(p).distanceTo(aim) - distance) < Math.abs(flat(best).distanceTo(aim) - distance)) best = p;
            }
            return flat(best);
        }
    }
}
