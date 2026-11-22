package ua.zentix.incoming.gametest.scenario;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.stress.StressDirector;
import ua.zentix.incoming.target.Target;

import java.util.function.Consumer;

/**
 * A Shahed reaches an open point just beyond a brick building that hides it on the approach (a 60-block building 12
 * blocks before it, a 160-block one 45 before it): the line to an uncovered point opens by overflight, so the dive
 * deadline must not force the attack into the building ({@code Autopilot.Sightline}). Explosion within 8 blocks of the
 * point, recorded from the projectile's own explosion event.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public class ShahedCityGameTests {
    @GameTest(template = "pad", batch = "shahed_city_gap", timeoutTicks = 1100, skyAccess = true)
    public static void openPointBeyondBrickBuilding(GameTestHelper h) {
        checkBuilding(h, 12110, 60, 12);
    }

    @GameTest(template = "pad", batch = "shahed_city_tall", timeoutTicks = 1500, skyAccess = true)
    public static void openPointBeyondTallerBrickBuilding(GameTestHelper h) {
        checkBuilding(h, 12111, 160, 45);
    }

    private static void checkBuilding(GameTestHelper h, int siteId, int height, int gap) {
        var level = h.getLevel();
        ScenarioSite site = new ScenarioSite(level, siteId);
        Vec3 aim = site.origin.add(0, .5, 0);
        Vec3 start = site.origin.add(0, height + 20, -800);
        site.zone(aim, ScenarioSite.TARGET_ZONE);
        site.corridor(start, aim);
        InstantChunks.acquire();
        var drone = ModEntities.DRONE.get().create(level);
        drone.seed(0);
        Vec3[] blast = {null};
        Consumer<ExplosionEvent.Start> recorder = e -> {
            var source = StressDirector.blastProjectile(e.getExplosion());
            if (source != null && drone.getUUID().equals(source.getUUID())) blast[0] = e.getExplosion().center();
        };
        NeoForge.EVENT_BUS.addListener(recorder);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(recorder);
            site.release();
            InstantChunks.release();
        });
        site.load();
        BlockPos origin = BlockPos.containing(site.origin);
        BlockPos low = origin.offset(-20, 0, -gap - 30), high = origin.offset(20, height - 1, -gap);
        for (BlockPos p : BlockPos.betweenClosed(low, high)) {
            if (p.getY() == high.getY() || p.getZ() == low.getZ() || p.getZ() == high.getZ()
                    || p.getX() == low.getX() || p.getX() == high.getX()) {
                level.setBlock(p, Blocks.BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        boolean[] launched = {false};
        h.onEachTick(() -> {
            if (!launched[0] && site.ticking()) {
                drone.launch(start, new Target.Point(aim), aim, null);
                level.addFreshEntity(drone);
                launched[0] = true;
            }
            if (blast[0] != null) {
                Incoming.LOG.info("SHAHED city: blast offset from open point {}", blast[0].subtract(aim));
                h.assertTrue(blast[0].distanceTo(aim) < 8, "Explosion " + blast[0].distanceTo(aim)
                        + " blocks from the selected open point");
                h.succeed();
            }
        });
    }
}
