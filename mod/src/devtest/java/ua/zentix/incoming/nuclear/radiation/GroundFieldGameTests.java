package ua.zentix.incoming.nuclear.radiation;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.model.FalloutModel;
import ua.zentix.incoming.util.WorldClock;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Fallout on the ground by its law to the end ({@code docs/design/time.md} §8.3 rules 4 and 7, §9 change 8, §10 #14):
 * against a live server, a detonation forgotten at day 7 of its clock hands its fallout to a ground field with no step in the
 * dosimeter; fallout that landed after the show reads its landing age; a field reads nothing beyond its reach, is saved and
 * cleared; a night someone stayed awake for moves the dose on the ground, not the air. Detached event lists: no world work.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GroundFieldGameTests {
    private static final BlockPos SPOT = new BlockPos(44, 12, 44);

    private GroundFieldGameTests() {}

    /** A 15 kt surface burst without fires (only the plume) {@code age} real ticks ago, wind 6.7 m/s toward +x. */
    private static Detonation burst(ServerLevel level, NuclearEvents events, Vec3 at, long age) {
        Detonation d = new Detonation(NuclearEvents.get(level).nextId(), at, at.y, 15, true, level.getGameTime() - age, 0, 6.7f,
                20_000, 7, 1f, true, 0.35f, Detonation.NO_DAY_TIME, false);
        events.add(d, level);
        events.endShows(level);
        return d;
    }

    private static ServerPlayer player(GameTestHelper h, String name, Vec3 at) {
        ServerPlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
        p.setGameMode(GameType.SURVIVAL);
        p.setPos(at.x, at.y, at.z);
        RadiationTicker.clear(p);
        return p;
    }

    private static boolean near(double got, double want, double rel) {
        return want != 0 && Math.abs(got / want - 1) <= rel;
    }

    /**
     * Day 7 of a detonation 13.9 real hours old on a vanilla day (S 41 days, all fallout landed, its history compacted):
     * forgetting it leaves the dosimeter where it was; 13 km downwind, where the plume landed after the show, the dose rate
     * is the law's at its landing age plus the ground clock since; beyond its reach a field reads nothing; saved and loaded
     * it reads the same; cleared, it is gone.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "nuke_ground_field", skyAccess = true)
    public static void falloutOutlivesItsDetonation(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(SPOT));
        ServerPlayer p = player(h, "ground_field", at);
        try {
            NuclearEvents events = new NuclearEvents();
            Detonation d = burst(level, events, at, 1_000_000);
            h.assertTrue(events.history(d) != null && events.history(d).compacted(), "everything landed, yet the history is not compacted");
            double ground = events.ground(d, level);
            h.assertTrue(ground > NuclearEvents.FORGET_AFTER_S, "not past day 7: " + ground);
            RadiationTicker.step(level, events, List.of(p), List.of(d), List.of());
            double live = RadiationTicker.dose(p).rate();
            // at ground zero the plume landed in the show (no lead): its ground age is the ground clock
            double law = FalloutModel.groundRate(d.falloutH1(at.x, at.z), ground / 3600) * Shielding.fallout(level, RadiationTicker.middle(p));
            h.assertTrue(near(live, law, 1e-5), String.format(Locale.ROOT, "ground zero reads %.4g R/h, the law %.4g", live, law));
            events.prune(x -> events.seconds(x, level), x -> events.groundAbove(x, level));
            h.assertTrue(events.detonations().isEmpty() && events.groundFields().contains(d), "day 7 did not hand the fallout to a ground field");
            RadiationTicker.step(level, events, List.of(p), List.of(), events.groundFields());
            double field = RadiationTicker.dose(p).rate();
            h.assertTrue(near(field, live, 1e-6), String.format(Locale.ROOT, "the dosimeter stepped at day 7: %.4g → %.4g R/h", live, field));
            // 13 km downwind the plume landed at M 0.54 h, S then about 15 h: its ground age is that M plus the clock since
            double x = at.x + 13_000, m = d.falloutArrivalSeconds(x, at.z);
            double landedAt = events.secondsAt(d, level, d.gameTime() + Math.round(m * 20));
            h.assertTrue(landedAt > 10 * 3600, "S at the landing " + landedAt);
            GroundFallout.Source src = GroundFallout.source(events, level, d, ground, ground);
            double read = GroundFallout.at(events, level, src, x, at.z).rate(ground);
            double want = FalloutModel.groundRate(d.falloutH1(x, at.z), (m + ground - landedAt) / 3600);
            h.assertTrue(near(read, want, 1e-4), String.format(Locale.ROOT, "13 km reads %.4g R/h, landing age gives %.4g", read, want));
            h.assertTrue(src.reach() > 13_000 && GroundFallout.at(events, level, src, at.x + src.reach() + 1, at.z) == GroundFallout.Point.NONE,
                    "reach " + src.reach());
            NuclearEvents back = NuclearEvents.load(events.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
            h.assertTrue(back.groundFields().contains(d) && back.history(d).compacted(), "the field did not survive the save");
            h.assertTrue(back.ground(d, level) == ground, "the field's ground clock moved over a save: " + back.ground(d, level));
            GroundFallout.Source again = GroundFallout.source(back, level, d, ground, ground);
            h.assertTrue(near(GroundFallout.at(back, level, again, x, at.z).rate(ground), read, 1e-9), "the field reads differently after a save");
            events.clear();
            h.assertTrue(events.groundFields().isEmpty() && events.falloutSources().isEmpty(), "clear left a ground field");
        } finally {
            RadiationTicker.clear(p);
        }
        h.succeed();
    }

    /**
     * Rule 7 on the ground: a night someone stays awake for moves the strike's ground clock by its hours, so the fallout down
     * decays by them, while the strike clock and the motion age stand; fallout still in the air does not land for it.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "nuke_ground_unseen", skyAccess = true)
    public static void anAwakeNightAgesTheGround(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        WorldClock clock = WorldClock.get(server);
        Map<String, double[]> saved = new HashMap<>();
        for (ServerLevel l : server.getAllLevels()) saved.put(l.dimension().location().toString(), clock.jumpsForTest(l.dimension().location().toString()));
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(SPOT));
        FakePlayer awake = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "awake"));
        awake.setPos(at.x, at.y, at.z);
        level.players().add(awake);
        try {
            clock.recordAwakeForTest(server);
        } finally {
            level.players().remove(awake);
        }
        double night = 13_000;
        try {
            NuclearEvents events = new NuclearEvents();
            Detonation d = burst(level, events, at, 100_000);
            double g0 = events.ground(d, level), s0 = events.seconds(d, level), m0 = events.motion(d, level);
            double far = at.x + 100_000;
            h.assertTrue(d.falloutArrivalSeconds(far, at.z) > m0, "100 km landed already");
            GroundFallout.Source before = GroundFallout.source(events, level, d, g0, g0);
            double r0 = GroundFallout.at(events, level, before, at.x, at.z).rate(g0);
            clock.jumpForTest(server, night);
            double g1 = events.ground(d, level);
            h.assertTrue(events.seconds(d, level) == s0 && events.motion(d, level) == m0, "the night moved the strike clock or the air");
            h.assertTrue(Math.abs(g1 - g0 - night * WorldClock.SECONDS_PER_TICK) < 1e-6, String.format(Locale.ROOT, "ground clock %.1f → %.1f s", g0, g1));
            GroundFallout.Source after = GroundFallout.source(events, level, d, g1, g0);
            double r1 = GroundFallout.at(events, level, after, at.x, at.z).rate(g1);
            double want = r0 * Math.pow(g1 / g0, -FalloutModel.DECAY_EXP);
            h.assertTrue(near(r1, want, 1e-6), String.format(Locale.ROOT, "ground zero after the night %.4g R/h, the law %.4g", r1, want));
            h.assertTrue(GroundFallout.at(events, level, after, far, at.z) == GroundFallout.Point.NONE, "the night landed fallout still in the air");
        } finally {
            saved.forEach((dim, j) -> clock.restoreJumpsForTest(dim, j));
        }
        h.succeed();
    }
}
