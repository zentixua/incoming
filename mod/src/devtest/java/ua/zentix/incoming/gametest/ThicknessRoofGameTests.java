package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.registry.ModDataMaps.Penetrability;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.Article;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.BreachModel;
import ua.zentix.incoming.warhead.Warheads;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * A thin represented element is crossed, not travelled along: a contact charge on a wide plank roof opens a hole no farther
 * from the hit than its breach radius plus the element's own cell (and the wave's reach on planks), not across the roof.
 * Without the world reach bound a Lancet opened 485 planks out to 16.6 m and a Grad rocket 841 out to 21 m.
 */
@GameTestHolder(Incoming.MOD_ID)
public final class ThicknessRoofGameTests {
    private ThicknessRoofGameTests() {}

    @GameTestGenerator
    public static Collection<TestFunction> probes() {
        List<TestFunction> tests = new ArrayList<>();
        for (WeaponType weapon : List.of(WeaponType.LOITER, WeaponType.ROCKET)) {
            tests.add(new TestFunction("thickness_roof_" + weapon.name().toLowerCase(Locale.ROOT), "thicknessroof." + weapon.name().toLowerCase(Locale.ROOT),
                    "incoming:range", Rotation.NONE, 600, 0, true, false, 1, 1, true, h -> strike(h, weapon)));
        }
        return tests;
    }

    private static void strike(GameTestHelper h, WeaponType weapon) {
        boolean debris = IncomingConfig.SERVER.debrisStay.get(), fire = IncomingConfig.SERVER.fire.get();
        IncomingConfig.SERVER.debrisStay.set(false);
        IncomingConfig.SERVER.fire.set(false);
        StrikeGameTests.afterTest(h, () -> {
            IncomingConfig.SERVER.debrisStay.set(debris);
            IncomingConfig.SERVER.fire.set(fire);
        });
        ServerLevel level = h.getLevel();
        for (int x = 12; x <= 52; x++) for (int z = 12; z <= 52; z++) for (int y = 8; y <= 17; y++) {
            BlockState s = y <= 11 ? Blocks.DIRT.defaultBlockState() : y == 16 ? Blocks.OAK_PLANKS.defaultBlockState()
                    : y >= 12 && y < 16 && (x == 12 || x == 52 || z == 12 || z == 52) ? Blocks.BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState();
            level.setBlock(h.absolutePos(new BlockPos(x, y, z)), s, Block.UPDATE_CLIENTS);
        }
        EntityType<? extends Entity> type = weapon == WeaponType.LOITER ? ModEntities.LOITER.get() : ModEntities.ROCKET.get();
        Entity e = type.create(level);
        Article.Impact impact = weapon.spec().striker().article().impact();
        double angle = Math.toRadians(impact.angle());
        e.setDeltaMovement(new Vec3(Math.cos(angle), -Math.sin(angle), 0).scale(impact.speed() / 20));
        Warheads.testDetonate(level, weapon, h.absoluteVec(new Vec3(32.5, 17, 32.5)), e, 0x544849434bL);
        boolean[] logged = {false};
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).idle(), "strike unfinished");
            int gone = 0; double far = 0;
            for (int x = 13; x <= 51; x++) for (int z = 13; z <= 51; z++) {
                if (h.getBlockState(new BlockPos(x, 16, z)).is(Blocks.OAK_PLANKS)) continue;
                gone++;
                far = Math.max(far, Math.hypot(x - 32, z - 32));
            }
            double radius = Penetrability.of(Blocks.OAK_PLANKS.defaultBlockState()).breaching().radius(weapon.spec().charge(), BreachModel.UNTAMPED);
            if (!logged[0]) {
                logged[0] = true;
                Incoming.LOG.info("THICKROOF {} charge={} FM={} planks gone={} farthest={} m", weapon, weapon.spec().charge(),
                        String.format(Locale.ROOT, "%.2f", radius), gone, String.format(Locale.ROOT, "%.1f", far));
            }
            h.assertTrue(gone > 0, "roof stayed shut");
            // breach radius, contact tolerance and the element's cell, plus one cell of grid rounding
            h.assertTrue(far <= radius + 0.5 + 2, "hole reaches " + far + " m from the hit, breach radius " + radius + " m");
        });
    }
}
