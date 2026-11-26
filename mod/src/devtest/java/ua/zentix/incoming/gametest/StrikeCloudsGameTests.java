package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.strike.StrikeClouds;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.util.WorldClock;
import ua.zentix.incoming.warhead.FuelFires;
import ua.zentix.incoming.warhead.Warheads;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Rule 10 of the time note: conventional strikes are recorded on the server and sent to a player who joins, so a friend
 * joining at minute 10 sees minute 10 (the client side, and a replay seek: {@code ClientStrikeCloudsTest}).
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StrikeCloudsGameTests {
    /** The middle of the site “range”: grass, and the first air above it. */
    private static final BlockPos GRASS = new BlockPos(32, 11, 32), CENTER = GRASS.above();
    /** Ten minutes, ticks. */
    private static final long MINUTE_10 = 10 * 60 * 20;

    private StrikeCloudsGameTests() {}

    /** Clouds at {@code at} in {@code p}. */
    private static List<S2C.StrikeClouds.Cloud> at(S2C.StrikeClouds p, Vec3 at) {
        return p.clouds().stream().filter(c -> c.kind() != S2C.StrikeClouds.FUEL && position(c).distanceTo(at) < 1).toList();
    }

    private static Vec3 position(S2C.StrikeClouds.Cloud c) {
        return c.packet() instanceof S2C.Blast b ? b.pos() : Vec3.ZERO;
    }

    /**
     * A Shahed hits; ten minutes later a friend joins next to it. The friend's client gets the very packet the watchers got
     * live, ten minutes old, in the air of its moment, over the ground as it was before the crater; and the crater fire
     * still burning. A friend beyond the packet's reach gets nothing of it. A watcher present has the list within a
     * second, so a recording holds it.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "strike_clouds_join", skyAccess = true)
    public static void aJoinerAtMinuteTenGetsTheStrikeAtItsAge(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        boolean debris = IncomingConfig.SERVER.debrisStay.get();
        IncomingConfig.SERVER.debrisStay.set(false);
        level.setWeatherParameters(24000, 0, false, false);
        StrikeGameTests.afterTest(h, () -> IncomingConfig.SERVER.debrisStay.set(debris));
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        var live = FuelFireGameTests.Watcher.near(h, at.add(20, 0, 0), S2C.Blast.class);
        var lists = FuelFireGameTests.Watcher.near(h, at.add(0, 0, 20), S2C.StrikeClouds.class);
        long born = level.getGameTime();
        Air air = Air.of(level);
        Warheads.detonate(level, WeaponType.DRONE, at, null, null);
        long[] listed = {-1};
        h.onEachTick(() -> {
            if (listed[0] < 0 && lists.got.stream().anyMatch(p -> !at(p, at).isEmpty())) listed[0] = level.getGameTime();
        });
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "the strike is still working");
            h.assertTrue(listed[0] >= 0, "the watcher got no list with the strike");
            List<FuelFires.Burning> fires = FuelFires.burning(level).stream().filter(b -> b.at().distanceTo(at) < 24).toList();
            h.assertTrue(fires.size() == 1, "crater fires: " + fires.size());
            S2C.Blast sent = live.got.stream().filter(b -> b.pos().distanceTo(at) < 1).findFirst().orElse(null);
            h.assertTrue(sent != null, "no live blast packet");

            StrikeClouds.ageForTest(level, at, MINUTE_10);
            try {
                var joiner = FuelFireGameTests.Watcher.near(h, at.add(-30, 0, 5), S2C.StrikeClouds.class);
                StrikeClouds.joinForTest(joiner.player);
                h.assertTrue(joiner.got.size() == 1, "packets to the joiner: " + joiner.got.size());
                List<S2C.StrikeClouds.Cloud> mine = at(joiner.got.getFirst(), at);
                h.assertTrue(mine.size() == 1, "the joiner's clouds at the strike: " + mine);
                S2C.StrikeClouds.Cloud c = mine.getFirst();
                h.assertTrue(c.packet().equals(sent), "not the live packet: " + c.packet() + " against " + sent);
                double age = StrikeClouds.motion(c, level.getGameTime(), WorldClock.counted(level));
                double expected = (level.getGameTime() - born + MINUTE_10) / 20.0;
                Incoming.LOG.info("STRIKECLOUDS joiner: age {} s (expected {} s), surface {} under {}, air {} against {}",
                        String.format(Locale.ROOT, "%.2f", age), String.format(Locale.ROOT, "%.2f", expected), c.surface(), at.y, c.air(), air);
                h.assertTrue(Math.abs(age - expected) < 1e-9, "age " + age + " s, expected " + expected);
                h.assertTrue(age >= 600, "younger than ten minutes: " + age);
                h.assertTrue(c.surface() == (float) at.y, "ground before the crater " + c.surface() + ", the grass top " + at.y);
                h.assertTrue(Math.abs(c.air().wind().direction() - air.wind().direction()) < 1e-3
                        && Math.abs(c.air().wind().speed() - air.wind().speed()) < 1e-3, "not the air of its moment: " + c.air() + " against " + air);
                h.assertTrue(joiner.got.getFirst().clouds().stream().anyMatch(x -> x.kind() == S2C.StrikeClouds.FUEL
                        && x.packet().equals(fires.getFirst().state())), "the burning crater is missing: " + joiner.got.getFirst());

                // the blast packet reaches far_range or the charge's audibility, capped at FAR_RANGE_MAX
                double reach = Math.max(IncomingConfig.SERVER.farRange.get(), IncomingConfig.FAR_RANGE_MAX);
                var far = FuelFireGameTests.Watcher.near(h, at.add(reach + 100, 0, 0), S2C.StrikeClouds.class);
                StrikeClouds.joinForTest(far.player);
                h.assertTrue(far.got.stream().allMatch(p -> at(p, at).isEmpty()), "a player beyond the reach got the strike");
            } finally {
                StrikeClouds.ageForTest(level, at, -MINUTE_10);
            }
            h.assertTrue(listed[0] - born <= StrikeClouds.SYNC_TICKS + 1, "the list came " + (listed[0] - born) + " ticks after the strike");
        });
    }

    /** A strike past its law's life, or a counted hour later, is no longer kept or sent. */
    @GameTest(template = "range", timeoutTicks = 20, batch = "strike_clouds_prune", skyAccess = true)
    public static void aPrunedStrikeIsNotSent(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER)), other = at.add(4, 0, 0);
        StrikeClouds.keepForTest(level, at, 512, blast(at, 1));
        StrikeClouds.ageForTest(level, at, Math.round(StrikeClouds.LIFE_S * 20));
        var joiner = FuelFireGameTests.Watcher.near(h, at.add(10, 0, 0), S2C.StrikeClouds.class);
        StrikeClouds.joinForTest(joiner.player);
        h.assertTrue(joiner.got.stream().allMatch(p -> at(p, at).isEmpty()), "an ended strike was sent: " + joiner.got);
        h.assertTrue(StrikeClouds.heldForTest(level).stream().noneMatch(c -> position(c).distanceTo(at) < 1), "an ended strike is still kept");

        var server = level.getServer();
        String dim = level.dimension().location().toString();
        double[] jumps = WorldClock.get(server).jumpsForTest(dim);
        try {
            StrikeClouds.keepForTest(level, other, 512, blast(other, 2));
            var before = FuelFireGameTests.Watcher.near(h, at.add(10, 0, 0), S2C.StrikeClouds.class);
            StrikeClouds.joinForTest(before.player);
            h.assertTrue(before.got.size() == 1 && at(before.got.getFirst(), other).size() == 1, "a fresh strike was not sent: " + before.got);
            // everyone slept an hour: the counted jump ages the strike past its life
            WorldClock.get(server).restoreJumpsForTest(dim, new double[]{jumps[0] + 1000, jumps[1], jumps[2]});
            var after = FuelFireGameTests.Watcher.near(h, at.add(10, 0, 0), S2C.StrikeClouds.class);
            StrikeClouds.joinForTest(after.player);
            h.assertTrue(after.got.stream().allMatch(p -> at(p, other).isEmpty()), "a strike was sent after a counted hour: " + after.got);
        } finally {
            WorldClock.get(server).restoreJumpsForTest(dim, jumps);
        }
        h.succeed();
    }

    /**
     * A Grad salvo's hits come tick after tick: a watcher gets the list at most once a second, the last one whole, not one
     * packet per rocket.
     */
    @GameTest(template = "range", timeoutTicks = 80, batch = "strike_clouds_pace", skyAccess = true)
    public static void aSalvoSendsAFewLists(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        var watcher = FuelFireGameTests.Watcher.near(h, at, S2C.StrikeClouds.class);
        long start = level.getGameTime();
        int rockets = 30;
        h.onEachTick(() -> {
            long t = level.getGameTime() - start;
            if (t < rockets) StrikeClouds.keepForTest(level, at.add(t, 0, 0), 512, blast(at.add(t, 0, 0), t));
        });
        h.runAtTickTime(rockets + 2 * StrikeClouds.SYNC_TICKS + 2, () -> {
            List<S2C.StrikeClouds> got = watcher.got;
            long mine = got.isEmpty() ? 0 : got.getLast().clouds().stream().filter(c -> c.packet() instanceof S2C.Blast b
                    && b.pos().y == at.y && b.pos().z == at.z && b.pos().x >= at.x && b.pos().x < at.x + rockets).count();
            Incoming.LOG.info("STRIKECLOUDS salvo: {} rockets over {} ticks, {} lists", rockets, rockets, got.size());
            h.assertTrue(got.size() <= (rockets + StrikeClouds.SYNC_TICKS - 1) / StrikeClouds.SYNC_TICKS + 1, "lists: " + got.size());
            h.assertTrue(mine == rockets, "the last list holds " + mine + " of " + rockets);
            h.succeed();
        });
    }

    private static S2C.Blast blast(Vec3 at, long seed) {
        return new S2C.Blast(S2C.Blast.ROCKET, at, 3, (float) at.y, seed, Optional.empty(), WeaponType.ROCKET.id());
    }
}
