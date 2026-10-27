package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.nuclear.radiation.RadiationTicker;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.registry.ModDamageTypes;

import java.util.List;

/**
 * Order cases of late exposure ({@link LateExposure}) against the pulse list and the front: each body gets each detonation's
 * pulse and wave once, whichever path comes first, and nothing from detonations before it was born.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LateExposureOrderGameTests {
    private LateExposureOrderGameTests() {}

    private static WorkClock clock(long units) {
        WorkClock c = WorkClock.counting(1_000_000);
        c.start(units * 1_000_000);
        return c;
    }

    private static Detonation det(ServerLevel level, int id, Vec3 burst, long age) {
        return new Detonation(id, burst, burst.y, 15, true, level.getGameTime() - age, 0, 0, 20_000, 7, 0.1f, false, Detonation.OLD_WAVE);
    }

    /** Burns and death aside: the probe counts dose and wave hits only. */
    private static Cow cow(GameTestHelper h) {
        Cow c = h.spawn(EntityType.COW, new BlockPos(40, 12, 32));
        c.setNoAi(true);
        c.setInvulnerable(true);
        // in the world before the detonations: no birth time
        c.removeData(ModAttachments.NUKE_SEEN);
        return c;
    }

    /** Runs a queue to the end, one work unit per round; returns the rounds. */
    private static int drain(ServerLevel level, LateExposure late) {
        int rounds = 0;
        while (late.busy() && rounds < 10_000) {
            late.work(level, clock(1), List.of());
            rounds++;
        }
        return rounds;
    }

    /** The pulse of a later detonation taking the body first does not cancel the queued pulse of an earlier one. */
    @GameTest(template = "range", batch = "nuke_late_order_pulse", skyAccess = true)
    public static void laterPulseKeepsEarlierQueuedPulse(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        NuclearEvents events = NuclearEvents.get(level);
        Cow c = cow(h);
        Detonation older = det(level, events.nextId(), c.position().add(-40, 0, 0), 2);
        Detonation newer = det(level, events.nextId(), c.position().add(-40, 0, 0), 1);
        events.add(older);
        LateExposure late = new LateExposure();
        try {
            late.joined(level, c, true, List.of());
            events.add(newer);
            new PulseJob(level, newer, null).work(level, clock(100));
            float newerDose = RadiationTicker.dose(c).doseGy();
            h.assertTrue(newerDose > 0, "control pulse did not reach the body");
            drain(level, late);
            float total = RadiationTicker.dose(c).doseGy();
            h.assertTrue(total > 1.9f * newerDose, "older pulse skipped after the newer one: newer " + newerDose + ", total " + total + " Gy");
            h.succeed();
        } finally {
            c.discard();
            events.clear();
        }
    }

    /** Counts blast damage calls; the first does not kill it. */
    private static final class BlastProbe extends Cow {
        int hits;

        BlastProbe(ServerLevel level) {
            super(EntityType.COW, level);
            setNoAi(true);
            setInvulnerable(true);
        }

        @Override
        public boolean hurt(DamageSource source, float amount) {
            if (source.is(ModDamageTypes.NUCLEAR_BLAST)) hits++;
            return true;
        }
    }

    /** A body loaded from disk on the tick the front crosses it gets that wave once, from whichever path runs first. */
    @GameTest(template = "range", batch = "nuke_late_order_front", skyAccess = true)
    public static void loadedOnCrossingTickGetsOneWave(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        NuclearEvents events = NuclearEvents.get(level);
        BlastProbe c = new BlastProbe(level);
        c.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(40, 12, 32))));
        level.addFreshEntity(c);
        c.removeData(ModAttachments.NUKE_SEEN);
        Detonation geometry = det(level, events.nextId(), c.position(), 1);
        double r = 0.5 * (geometry.frontRadius(0) + geometry.frontRadius(1));
        Detonation d = det(level, geometry.id(), c.position().add(-r, 0, 0), 1);
        events.add(d);
        LateExposure late = new LateExposure();
        try {
            late.joined(level, c, true, List.of());
            drain(level, late);
            h.assertTrue(c.hits == 1, "late wave hits: " + c.hits);
            BlastFront front = new BlastFront();
            front.advance(level, List.of(d), level.getGameTime());
            front.work(level, clock(100), id -> true);
            h.assertTrue(c.hits == 1, "one detonation's wave hit " + c.hits + " times");
            h.succeed();
        } finally {
            c.discard();
            events.clear();
        }
    }

    /** A body born after a detonation, out of its reach, then brought into the ruins and reloaded, gets nothing from it. */
    @GameTest(template = "range", batch = "nuke_late_order_born", skyAccess = true)
    public static void bornAfterGetsNothingOnReload(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        NuclearEvents events = NuclearEvents.get(level);
        Vec3 burst = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(24, 12, 32)));
        Detonation d = new Detonation(events.nextId(), burst, burst.y, 15, true, level.getGameTime() - 600, 0, 0, 20_000, 7, 0.001f, false,
                Detonation.OLD_WAVE);
        events.add(d);
        Cow c = h.spawn(EntityType.COW, new BlockPos(40, 12, 32));
        c.setNoAi(true);
        c.setInvulnerable(true);
        LateExposure late = new LateExposure();
        try {
            h.assertTrue(c.position().distanceTo(burst) > Math.max(d.blastReach(), NuclearWarhead.exposureRange(d)), "born inside the reach");
            c.setPos(burst.add(0.4, 0, 0));
            late.joined(level, c, true, List.of());
            drain(level, late);
            h.assertTrue(RadiationTicker.dose(c).doseGy() == 0, "born after the detonation, reloaded in the ruins: " + RadiationTicker.dose(c).doseGy() + " Gy");
            h.succeed();
        } finally {
            c.discard();
            events.clear();
        }
    }

    /**
     * A body spawned after the flash but before the front reached its place, then unloaded until the front passed, gets the
     * wave on load and not the flash.
     */
    @GameTest(template = "range", batch = "nuke_late_order_ahead", skyAccess = true)
    public static void bornAheadOfFrontGetsWaveNotFlash(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        NuclearEvents events = NuclearEvents.get(level);
        BlastProbe c = new BlastProbe(level);
        c.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(40, 12, 32))));
        level.addFreshEntity(c);
        // detonation at game time 0 (a birth mark is never negative): the farthest place its front passed since, at least 2 ticks out
        long age = level.getGameTime();
        Detonation geometry = det(level, events.nextId(), c.position(), age);
        double dist = 0;
        for (double r = 150; r >= 20 && dist == 0; r -= 5) {
            if (geometry.arrivalTicks(r) >= 2 && geometry.arrivalTicks(r) < age - 1 && geometry.blastReach() > r) dist = r;
        }
        h.assertTrue(dist > 0, "no place the front reached at least 2 ticks after the burst within " + age + " ticks");
        Detonation d = det(level, geometry.id(), c.position().add(-dist, 0, 0), age);
        events.add(d);
        double arrival = d.arrivalTicks(dist);
        c.setData(ModAttachments.NUKE_SEEN, NukeSeen.NONE.withBorn(d.gameTime() + (long) (arrival / 2)));
        LateExposure late = new LateExposure();
        try {
            late.joined(level, c, true, List.of());
            drain(level, late);
            // the injury law may spare it at this distance: the mark says the wave reached it
            h.assertTrue(c.getData(ModAttachments.NUKE_SEEN).blasted(d.id()) && c.hits <= 1, "wave: " + c.getData(ModAttachments.NUKE_SEEN) + ", hits " + c.hits);
            h.assertTrue(RadiationTicker.dose(c).doseGy() == 0, "flash dose for a body born after it: " + RadiationTicker.dose(c).doseGy() + " Gy");
            h.succeed();
        } finally {
            c.discard();
            events.clear();
        }
    }

    /**
     * Catching up does no work in the join event and yields between detonations: a body that missed many takes one work unit
     * per detonation (and one per reach after a restart), not one unit for all.
     */
    @GameTest(template = "range", batch = "nuke_late_order_budget", skyAccess = true)
    public static void catchUpYieldsPerDetonation(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        NuclearEvents events = NuclearEvents.get(level);
        Cow c = cow(h);
        int n = 32;
        for (int i = 0; i < n; i++) events.add(det(level, events.nextId(), c.position().add(-40, 0, 0), 600 + i));
        LateExposure late = new LateExposure();
        try {
            late.joined(level, c, true, List.of());
            h.assertTrue(late.busy() && RadiationTicker.dose(c).doseGy() == 0, "join event did the work");
            int rounds = drain(level, late);
            h.assertTrue(rounds >= 2 * n, "rounds for " + n + " detonations: " + rounds + ", dose " + RadiationTicker.dose(c).doseGy() + " Gy");
            h.assertTrue(RadiationTicker.dose(c).doseGy() > 0, "no dose after catching up");
            h.succeed();
        } finally {
            c.discard();
            events.clear();
        }
    }
}
