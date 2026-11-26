package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.entity.DebrisEntity;
import ua.zentix.incoming.nuclear.model.FuelFireball;
import ua.zentix.incoming.strike.StrikeRules;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.FuelFires;
import ua.zentix.incoming.warhead.Warheads;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rule 11 of the time note (docs/design/time.md §8.3): with {@code doFireTick} off strikes light no block fire, which would
 * stand forever. The fuel ball ({@code Craters.ignite}) and burning debris ({@code DebrisEntity}) each get fuel to light, so
 * their gates are what decides: with the strikes' real fire choice ({@code StrikeRules.lightFrozenFireForTest(false)}) no
 * fire; with the GameTest server's frozen-fire override (fire chosen as with the rule on) the same setup lights it (control:
 * the setup can light fire at all). The crater fuel fire itself: {@code FuelFireGameTests.withoutFireTickNoFireOutlivesTheStrike}.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FireTickGameTests {
    private static final BlockPos CENTER = new BlockPos(32, 12, 32);

    private FireTickGameTests() {}

    @GameTest(template = "range", timeoutTicks = 400, batch = "firetick_ball_off", skyAccess = true)
    public static void fuelBallLightsNoFoliageWithoutFireTick(GameTestHelper h) {
        ballTest(h, false);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "firetick_ball_frozen", skyAccess = true)
    public static void fuelBallLightsFoliageWithFrozenFire(GameTestHelper h) {
        ballTest(h, true);
    }

    private static void ballTest(GameTestHelper h, boolean frozenLit) {
        ServerLevel level = h.getLevel();
        GameRules.BooleanValue tick = level.getGameRules().getRule(GameRules.RULE_DOFIRETICK);
        boolean ticked = tick.get(), debris = IncomingConfig.SERVER.debrisStay.get(), damage = IncomingConfig.SERVER.blockDamage.get();
        tick.set(false, level.getServer());
        // only ignition: the wave would blow the leaves away before the ball, clods would land on them
        IncomingConfig.SERVER.debrisStay.set(false);
        IncomingConfig.SERVER.blockDamage.set(false);
        StrikeRules.lightFrozenFireForTest(frozenLit);
        level.setWeatherParameters(24000, 0, false, false);
        StrikeGameTests.afterTest(h, () -> {
            tick.set(ticked, level.getServer());
            IncomingConfig.SERVER.debrisStay.set(debris);
            IncomingConfig.SERVER.blockDamage.set(damage);
            StrikeRules.lightFrozenFireForTest(true);
        });
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        WeaponType weapon = WeaponType.MISSILE;
        double r = FuelFireball.diameter(FuelFireball.ballKg(weapon.spec().fuelAtImpact())) / 2;
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        List<BlockPos> fuel = new ArrayList<>();
        // 16 leaf columns, 3 blocks high, between 3 m and half the ball radius: outside the crater fuel's cells
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8, d = Math.max(3, 0.5 * r);
            BlockPos p = BlockPos.containing(at.x + Math.cos(a) * d, at.y, at.z + Math.sin(a) * d);
            for (int y = 0; y < 3; y++) {
                level.setBlock(p.above(y), leaves, 2);
                fuel.add(p.above(y).immutable());
            }
        }
        StrikeGameTests.afterTest(h, () -> {
            for (BlockPos p : fuel) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                for (Direction d : Direction.values()) {
                    if (level.getBlockState(p.relative(d)).getBlock() instanceof BaseFireBlock) level.setBlock(p.relative(d), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        });
        Warheads.detonate(level, weapon, at, null, null);
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).idle(), "the strike is not over");
            Set<BlockPos> cells = new HashSet<>(FuelFires.get(level).cells());
            Set<BlockPos> fires = new HashSet<>();
            for (BlockPos p : fuel) {
                for (Direction d : Direction.values()) {
                    BlockPos q = p.relative(d);
                    if (!cells.contains(q) && level.getBlockState(q).getBlock() instanceof BaseFireBlock) fires.add(q.immutable());
                }
            }
            String got = "doFireTick off, frozen-fire override " + frozenLit + ": ball radius " + r + " m, fires beside the leaves (not fuel cells) "
                    + fires.size();
            Incoming.LOG.info("FIRETICK {}", got);
            if (frozenLit) h.assertTrue(fires.size() >= 4, got);
            else h.assertTrue(fires.isEmpty(), got);
        });
    }

    @GameTest(template = "range", timeoutTicks = 100, batch = "firetick_debris_off", skyAccess = true)
    public static void burningDebrisLightsNothingWithoutFireTick(GameTestHelper h) {
        debrisTest(h, false);
    }

    @GameTest(template = "range", timeoutTicks = 100, batch = "firetick_debris_frozen", skyAccess = true)
    public static void burningDebrisLightsFuelWithFrozenFire(GameTestHelper h) {
        debrisTest(h, true);
    }

    /**
     * Burning debris (a fire block thrown by the strike, {@code DebrisSpawner}) falls beside a hay bale, where it would keep
     * burning: with {@code doFireTick} off it lights nothing ({@code frozenLit} false), with fire chosen as with the rule on it
     * stays as fire (control).
     */
    private static void debrisTest(GameTestHelper h, boolean frozenLit) {
        ServerLevel level = h.getLevel();
        GameRules.BooleanValue tick = level.getGameRules().getRule(GameRules.RULE_DOFIRETICK);
        boolean ticked = tick.get();
        tick.set(false, level.getServer());
        StrikeRules.lightFrozenFireForTest(frozenLit);
        level.setWeatherParameters(24000, 0, false, false);
        BlockPos spot = h.absolutePos(CENTER), hay = spot.east();
        level.setBlock(hay, Blocks.HAY_BLOCK.defaultBlockState(), 2);
        StrikeGameTests.afterTest(h, () -> {
            tick.set(ticked, level.getServer());
            StrikeRules.lightFrozenFireForTest(true);
            level.setBlock(hay, Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(spot, Blocks.AIR.defaultBlockState(), 2);
        });
        h.assertTrue(level.getBlockState(spot).isAir(), "the landing spot is not air: " + level.getBlockState(spot));
        DebrisEntity debris = DebrisEntity.create(level, Vec3.atBottomCenterOf(spot).add(0, 2.5, 0), Blocks.FIRE.defaultBlockState(), true, false,
                new Vec3(0, -0.3, 0)).under(StrikeRules.ALL, null);
        level.addFreshEntity(debris);
        h.succeedWhen(() -> {
            h.assertTrue(debris.isRemoved(), "the debris has not landed");
            boolean fire = level.getBlockState(spot).getBlock() instanceof BaseFireBlock;
            String got = "doFireTick off, frozen-fire override " + frozenLit + ": burning debris beside hay left fire " + fire;
            Incoming.LOG.info("FIRETICK {}", got);
            h.assertTrue(fire == frozenLit, got);
        });
    }
}
