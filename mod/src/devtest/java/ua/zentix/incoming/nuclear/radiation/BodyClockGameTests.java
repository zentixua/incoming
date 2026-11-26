package ua.zentix.incoming.nuclear.radiation;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.util.RecoveryClock;
import ua.zentix.incoming.util.WorldClock;

import java.util.Locale;
import java.util.UUID;

/**
 * A body's recovery clock on a live server ({@code docs/design/time.md} §8.3 rule 8, §10 mismatches 5 and 7): real rate
 * while a detonation that acted on it is in its show, the world's rate after it; it moves only while the body is stepped
 * (in the world); old saves keep their exposure times; the ear a client told the server survives a relog. The GameTest
 * server's day is stopped at the vanilla pace: the world clock moves one tick a tick.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BodyClockGameTests {
    private static Cow cow(GameTestHelper h) {
        return h.spawn(EntityType.COW, new BlockPos(4, 2, 4));
    }

    /**
     * During a detonation's show, a body it dosed recovers at real rate while the world runs 72×; once the show is over, at
     * the world's rate. A body stepped once a second ages; one not stepped for minutes (offline, unloaded) ages nothing.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "body_clock", skyAccess = true)
    public static void realInTheShowAndNothingWhileAway(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Cow shown = cow(h), away = cow(h);
        NuclearEvents events = new NuclearEvents();
        Detonation d = new Detonation(NuclearEvents.get(level).nextId(), new Vec3(90_000, 64, 90_000), 64, 7, true,
                level.getGameTime() - 200, 0, 0, 20000, 3, 0.03f, false, 1);
        events.add(d, level);
        RecoveryClock.Shows shows = id -> events.showEnd(id, level);
        h.assertTrue(events.showEnd(d.id(), level) == RecoveryClock.RUNNING, "a detonation 10 s old is not in its show");
        BodyClock.step(shown, shows);
        BodyClock.of(shown).strike(d.id());
        BodyClock.step(away, shows);
        double s0 = BodyClock.of(shown).clock(), a0 = BodyClock.of(away).clock(), w0 = WorldClock.now(level);
        for (int t = 20; t <= 100; t += 20) h.runAtTickTime(h.getTick() + t, () -> BodyClock.step(shown, shows));
        h.runAtTickTime(h.getTick() + 101, () -> {
            double shownRan = BodyClock.of(shown).clock() - s0, world = WorldClock.now(level) - w0;
            // 100 game ticks of real time are 100 / 72 world-clock ticks
            h.assertTrue(Math.abs(shownRan - 100 * WorldClock.REAL_RATE) < 1e-6 && world > 10 * shownRan,
                    String.format(Locale.ROOT, "in the show the body ran %.3f clock ticks while the world ran %.1f", shownRan, world));
            // away for 5 s with a night meanwhile (as a rate step): the first step back counts nothing
            WorldClock.get(level.getServer()).advance(8000);
            BodyClock.step(away, shows);
            h.assertTrue(BodyClock.of(away).clock() == a0, "a body away aged " + (BodyClock.of(away).clock() - a0));
            // a body without a cause in its show, stepped each second, follows the world
            double back = BodyClock.of(away).clock(), rate = WorldClock.rateClock(level);
            h.runAtTickTime(h.getTick() + 20, () -> {
                BodyClock.step(away, shows);
                double ran = BodyClock.of(away).clock() - back, worldRan = WorldClock.rateClock(level) - rate;
                h.assertTrue(Math.abs(ran - worldRan) < 1e-6, "a present body without a show ran " + ran + " clock ticks in a second, the world " + worldRan);
                shown.discard();
                away.discard();
                h.succeed();
            });
        });
    }

    /** A body saved before its recovery clock: exposure times read as world clock, so its sickness stays where it was. */
    @GameTest(template = "range", timeoutTicks = 20, batch = "body_clock", skyAccess = true)
    public static void oldExposureReadsAsWorldClock(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Cow c = cow(h);
        long fiveHoursAgo = WorldClock.ticks(level) - 5000;
        c.setData(ModAttachments.RADIATION, new RadiationDose(3, 0, fiveHoursAgo, 0));
        h.assertFalse(c.hasData(ModAttachments.BODY_CLOCK), "the body already had a clock");
        double hours = RadiationTicker.hoursSinceExposure(c, RadiationTicker.dose(c));
        h.assertTrue(Math.abs(hours - 5) < 0.01, "an exposure saved 5 hours ago reads as " + hours + " hours");
        c.discard();
        h.succeed();
    }

    /** The ear a client tells the server is saved with the player and given back the same; an empty one is dropped. */
    @GameTest(template = "range", timeoutTicks = 20, batch = "body_clock", skyAccess = true)
    public static void theEarSurvivesARelog(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer p = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "ear"));
        CompoundTag ear = new CompoundTag();
        ear.putDouble("ruptured", 1234);
        CompoundTag clock = new CompoundTag();
        clock.putDouble("clock", 5678);
        ear.put("clock", clock);
        Ears.told(p, ear);
        CompoundTag saved = p.saveWithoutId(new CompoundTag());
        FakePlayer again = new FakePlayer(level, new GameProfile(p.getUUID(), "ear"));
        again.load(saved);
        h.assertTrue(again.getExistingData(ModAttachments.EAR).filter(ear::equals).isPresent(),
                "the ear after a relog: " + again.getExistingData(ModAttachments.EAR));
        Ears.told(again, new CompoundTag());
        h.assertFalse(again.hasData(ModAttachments.EAR), "a healed ear is still kept");
        h.succeed();
    }
}
