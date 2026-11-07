package ua.zentix.incoming.nuclear.world;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Changes between plan and wave that keep a ready plan current: fire after the flash ({@link RuinPlan#unburnt}), daylight
 * detector power following the sky ({@link RuinPlan#normal}).
 */
class PlanCurrentTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @Test
    void burnedOrBurningFlammableBlockMatchesItsSnapshot() {
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        assertSame(planks, RuinPlan.unburnt(Blocks.AIR.defaultBlockState(), planks));
        assertSame(planks, RuinPlan.unburnt(Blocks.FIRE.defaultBlockState(), planks));
    }

    @Test
    void fireInAirMatchesAir() {
        BlockState air = Blocks.AIR.defaultBlockState();
        assertSame(air, RuinPlan.unburnt(Blocks.FIRE.defaultBlockState(), air));
    }

    @Test
    void otherChangesStayChanges() {
        // stone does not burn: a mined or placed block makes the plan stale
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        assertSame(air, RuinPlan.unburnt(air, stone));
        assertSame(Blocks.FIRE.defaultBlockState(), RuinPlan.unburnt(Blocks.FIRE.defaultBlockState(), stone));
        assertSame(stone, RuinPlan.unburnt(stone, air));
        assertSame(Blocks.OAK_PLANKS.defaultBlockState(), RuinPlan.unburnt(Blocks.OAK_PLANKS.defaultBlockState(), air));
    }

    @Test
    void daylightDetectorAtAnyPowerIsTheSameDetector() {
        BlockState dark = Blocks.DAYLIGHT_DETECTOR.defaultBlockState(), sunny = dark.setValue(net.minecraft.world.level.block.DaylightDetectorBlock.POWER, 13);
        assertSame(RuinPlan.normal(dark), RuinPlan.normal(sunny));
        BlockState inverted = sunny.setValue(net.minecraft.world.level.block.DaylightDetectorBlock.INVERTED, true);
        org.junit.jupiter.api.Assertions.assertNotSame(RuinPlan.normal(dark), RuinPlan.normal(inverted));
    }
}
