package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.radiation.RadiationTicker;
import ua.zentix.incoming.nuclear.world.BlastBody;
import ua.zentix.incoming.nuclear.world.NuclearWorld;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.registry.ModEffects;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bodies that were not in memory when the flash and the wave crossed their place (a village nobody stood near) receive them
 * when they load ({@code LateExposure}); a body that already received them, or was born afterwards, does not.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LateExposureGameTests {
    /** Pressure at the farthest of the herd, psi: lethal by the injury law (checked below, not assumed). */
    private static final double PSI = 150;
    /** The detonation happened this long before the herd loads, ticks: flash and front long past. */
    private static final long AGO = 600;

    private LateExposureGameTests() {}

    /** 15 kt surface burst {@link #AGO} ticks ago at {@code g}, scaled so that point {@code p} gets {@link #PSI}. */
    private static Detonation burst(ServerLevel level, BlockPos g, Vec3 p) {
        double lo = 0.002, hi = 1;
        for (int i = 0; i < 40; i++) {
            double mid = Math.sqrt(lo * hi);
            if (make(level, g, (float) mid, 0).psi(p) < PSI) lo = mid;
            else hi = mid;
        }
        return make(level, g, (float) hi, NuclearGameTests.IDS.incrementAndGet());
    }

    private static Detonation make(ServerLevel level, BlockPos g, float scale, int id) {
        return new Detonation(id, Vec3.atBottomCenterOf(g), g.getY(), 15, true, level.getGameTime() - AGO, 0, 0, 20_000, 7, scale,
                false, Detonation.OLD_WAVE);
    }

    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_late_bodies", skyAccess = true)
    public static void bodiesLoadedAfterTheDetonationReceiveIt(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        NuclearWorld world = NuclearWorld.get(level);
        List<Mob> late = new ArrayList<>(), born = new ArrayList<>();
        for (int i = 0; i < 4; i++) late.add(h.spawn(EntityType.COW, new BlockPos(40 + 3 * i, 12, 40)));
        // in the world before the detonation (earlier batches' detonations gave them a birth time now)
        for (Mob m : late) m.removeData(ModAttachments.NUKE_SEEN);
        BlockPos g = h.absolutePos(NuclearGameTests.CENTER.west(20));
        Detonation d = burst(level, g, late.getLast().position());
        NuclearEvents.get(level).add(d);
        for (int i = 0; i < 4; i++) born.add(h.spawn(EntityType.COW, new BlockPos(40 + 3 * i, 12, 44)));
        for (Mob m : late) {
            m.setPersistenceRequired();
            var w = BlastBody.wound(level, d, m, null);
            h.assertTrue(w != null && w.death() > 0.999, String.format(Locale.ROOT, "the herd is not in the lethal zone: death %.3f",
                    w == null ? 0 : w.death()));
            // as when its chunk loads: the flash and the front passed this place before the cow was in memory
            world.onEntityJoin(level, m, true);
        }
        for (Mob m : born) m.setPersistenceRequired();
        h.runAfterDelay(40, () -> {
            try {
                for (Mob m : late) {
                    var dose = RadiationTicker.dose(m);
                    h.assertTrue(dose.doseGy() > 1, String.format(Locale.ROOT, "a cow loaded after the detonation got %.2f Gy", dose.doseGy()));
                    h.assertTrue(dose.exposedAt() == d.gameTime(), "sickness runs from " + dose.exposedAt() + ", not from the detonation " + d.gameTime());
                    h.assertFalse(m.isAlive(), "a cow loaded after the detonation survived " + Math.round(BlastBody.wound(level, d, m, null).death() * 100) + " % lethal pressure");
                    h.assertTrue(m.getRemainingFireTicks() <= 0, "clothes caught fire long after the flash");
                }
                for (Mob m : born) {
                    h.assertTrue(m.isAlive() && m.getHealth() == m.getMaxHealth(), "a cow born after the detonation was hit");
                    h.assertTrue(RadiationTicker.dose(m).doseGy() == 0 && !m.hasEffect(ModEffects.BURNS), "a cow born after the detonation got light or dose");
                    // loading it again later brings nothing either: it is marked
                    world.onEntityJoin(level, m, true);
                }
                h.runAfterDelay(20, () -> {
                    try {
                        for (Mob m : born) h.assertTrue(m.isAlive() && m.getHealth() == m.getMaxHealth(), "a born cow was hit on reload");
                        h.succeed();
                    } finally {
                        for (Mob m : born) m.discard();
                        NuclearStrikes.clear(level);
                    }
                });
            } catch (RuntimeException | AssertionError e) {
                for (Mob m : born) m.discard();
                for (Mob m : late) m.discard();
                NuclearStrikes.clear(level);
                throw e;
            }
        });
    }

    /**
     * A body queued twice (its chunk unloaded and loaded again before the queue reached it) receives the detonation once:
     * its dose equals that of a twin at the same distance queued once.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_late_once", skyAccess = true)
    public static void bodyQueuedTwiceReceivesItOnce(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        NuclearWorld world = NuclearWorld.get(level);
        // same distance from the burst on both sides of its axis
        Mob twice = h.spawn(EntityType.COW, new BlockPos(40, 12, 36)), once = h.spawn(EntityType.COW, new BlockPos(40, 12, 28));
        twice.removeData(ModAttachments.NUKE_SEEN);
        once.removeData(ModAttachments.NUKE_SEEN);
        BlockPos g = h.absolutePos(NuclearGameTests.CENTER.west(20));
        Detonation d = burst(level, g, twice.position());
        NuclearEvents.get(level).add(d);
        world.onEntityJoin(level, twice, true);
        world.onEntityJoin(level, twice, true);
        world.onEntityJoin(level, once, true);
        h.runAfterDelay(40, () -> {
            try {
                float a = RadiationTicker.dose(twice).doseGy(), b = RadiationTicker.dose(once).doseGy();
                h.assertTrue(b > 1, String.format(Locale.ROOT, "the twin got %.2f Gy", b));
                h.assertTrue(Math.abs(a - b) <= 0.01 * b, String.format(Locale.ROOT, "queued twice %.2f Gy, once %.2f Gy", a, b));
                h.succeed();
            } finally {
                twice.discard();
                once.discard();
                NuclearStrikes.clear(level);
            }
        });
    }
}
