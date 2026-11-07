package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.*;
import java.util.Optional;

@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class IcbmArrivalGameTests {
    private IcbmArrivalGameTests() {}

    @GameTest(template = "range", batch = "icbm_arrival", timeoutTicks = 100, skyAccess = true)
    public static void committedEndpointSurvivesTerrainEditAndEndsOnTime(GameTestHelper h) {
        var level = h.getLevel();
        var c = IncomingConfig.SERVER;
        double scale = c.nukeEffectsScale.get();
        var preset = c.scalePreset.get();
        boolean fires = c.nukeFires.get();
        c.scalePreset.set(IncomingConfig.ScalePreset.CUSTOM);
        c.nukeEffectsScale.set(0.01);
        c.nukeFires.set(false);
        StrikeGameTests.afterTest(h, () -> {
            NuclearStrikes.clear(level);
            c.nukeEffectsScale.set(scale); c.scalePreset.set(preset); c.nukeFires.set(fires);
        });
        BlockPos at = h.absolutePos(new BlockPos(24, 3, 24));
        level.setBlock(at, Blocks.STONE.defaultBlockState(), 3);
        Vec3 target = Vec3.atBottomCenterOf(at.above());
        long now = level.getGameTime(), end = now + 30;
        var events = NuclearEvents.get(level);
        int id = events.nextId();
        events.schedule(new NuclearEvents.ScheduledStrike(id, target, 1, true, now + 10 - IcbmArrival.BOOST, end,
                target.add(-100, 0, 0), Optional.empty(), true));
        final IcbmArrival[] committed = {null};
        h.onEachTick(() -> {
            var s = events.scheduled().stream().filter(x -> x.id() == id).findFirst();
            if (s.isPresent() && s.get().arrival().isPresent() && committed[0] == null) {
                committed[0] = s.get().arrival().orElseThrow();
                // Build above the now-locked endpoint. Arrival must not jump up to the new roof at the last tick.
                level.setBlock(at.above(10), Blocks.STONE.defaultBlockState(), 3);
                var encoded = NuclearEvents.ScheduledStrike.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, s.get()).getOrThrow();
                var restored = NuclearEvents.ScheduledStrike.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, encoded).getOrThrow();
                h.assertTrue(restored.equals(s.get()), "committed arrival lost across save/load");
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(level.getGameTime() > end, "waiting for scheduled burst");
            h.assertTrue(committed[0] != null, "no authoritative arrival on ready ground");
            var d = events.detonations().stream().filter(x -> x.gameTime() == end).findFirst().orElseThrow();
            h.assertTrue(d.burst().equals(committed[0].at(end)), "burst moved away from arrival endpoint");
            h.assertTrue(d.groundY() == committed[0].geometry().groundY(), "ground geometry changed during terminal flight");
            h.assertTrue(events.scheduled().stream().noneMatch(x -> x.id() == id), "head's scheduled event survived burst");
        });
    }
}
