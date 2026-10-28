package ua.zentix.incoming.gametest;

import com.mojang.serialization.JsonOps;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reproducible whole-strike measurements on houses; this file also runs unchanged against the pre-thickness baseline. */
@GameTestHolder(Incoming.MOD_ID)
public final class ThicknessHouseGameTests {
    private ThicknessHouseGameTests() {}

    private enum House { BRICK, CONCRETE, THICK_BRICK }
    private enum Hit { ROOF, WALL, GROUND }
    private static int holders;
    private static boolean debris, fire;

    @GameTestGenerator
    public static Collection<TestFunction> houses() {
        List<TestFunction> tests = new ArrayList<>();
        for (WeaponType weapon : List.of(WeaponType.LOITER, WeaponType.ROCKET, WeaponType.DRONE, WeaponType.MISSILE)) {
            for (House house : House.values()) for (Hit hit : Hit.values()) {
                String id = (weapon + "." + house + "." + hit).toLowerCase(Locale.ROOT);
                tests.add(new TestFunction("thickness_house_" + weapon.name().toLowerCase(Locale.ROOT), "thicknesshouse." + id,
                        "incoming:range", Rotation.NONE, 600, 0, true, false, 1, 1, true,
                        h -> strike(h, weapon, house, hit)));
            }
        }
        return tests;
    }

    private static void noAftermath(GameTestHelper h) {
        if (holders++ == 0) {
            debris = IncomingConfig.SERVER.debrisStay.get();
            fire = IncomingConfig.SERVER.fire.get();
            IncomingConfig.SERVER.debrisStay.set(false);
            IncomingConfig.SERVER.fire.set(false);
        }
        StrikeGameTests.afterTest(h, () -> {
            if (--holders == 0) {
                IncomingConfig.SERVER.debrisStay.set(debris);
                IncomingConfig.SERVER.fire.set(fire);
            }
        });
    }

    /** Read the optional field through the codec, so the exact same fixture compiles on the baseline without that field. */
    private static double thickness(BlockState block) {
        var json = Penetrability.CODEC.encodeStart(JsonOps.INSTANCE, Penetrability.of(block)).getOrThrow().getAsJsonObject();
        return json.has("thickness") ? json.get("thickness").getAsDouble() : 1;
    }

    private static Entity striker(ServerLevel level, WeaponType weapon) {
        EntityType<? extends Entity> type = switch (weapon) {
            case LOITER -> ModEntities.LOITER.get();
            case ROCKET -> ModEntities.ROCKET.get();
            case DRONE -> ModEntities.DRONE.get();
            case MISSILE -> ModEntities.CRUISE_MISSILE.get();
            default -> throw new IllegalArgumentException(weapon.name());
        };
        Entity e = type.create(level);
        Article.Impact impact = weapon.spec().striker().article().impact();
        double angle = Math.toRadians(impact.angle());
        e.setDeltaMovement(new Vec3(Math.cos(angle), -Math.sin(angle), 0).scale(impact.speed() / 20));
        return e;
    }

    private static void strike(GameTestHelper h, WeaponType weapon, House house, Hit hit) {
        noAftermath(h);
        ServerLevel level = h.getLevel();
        BlockState wall = (house == House.CONCRETE ? Blocks.GRAY_CONCRETE : Blocks.BRICKS).defaultBlockState();
        int layers = house == House.THICK_BRICK ? 3 : 1;
        // Seven by seven clear outer house footprint; the thick variant adds two wall layers outward on the struck side.
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        for (int x = 22; x <= 42; x++) for (int z = 22; z <= 42; z++) for (int y = 8; y <= 16; y++) {
            BlockState state = y <= 11 ? Blocks.DIRT.defaultBlockState() : Blocks.AIR.defaultBlockState();
            if (x >= 29 && x <= 35 && z >= 29 && z <= 35) {
                if (y == 16) state = Blocks.OAK_PLANKS.defaultBlockState();
                else if (y >= 12 && (x == 29 || x == 35 || z == 29 || z == 35)) state = wall;
                // Windows are away from the impact axis and identical in all three variants.
                if (y == 14 && ((z == 29 || z == 35) && x == 32 || x == 35 && z == 32)) state = Blocks.GLASS.defaultBlockState();
            }
            if (layers == 3 && x >= 27 && x <= 28 && z >= 29 && z <= 35 && y >= 12 && y <= 15) state = wall;
            BlockPos p = h.absolutePos(new BlockPos(x, y, z));
            level.setBlock(p, state, Block.UPDATE_CLIENTS);
            if (!state.isAir()) before.put(p, state);
        }
        int face = 30 - layers;
        Vec3 relative = switch (hit) {
            case ROOF -> new Vec3(32.5, 17, 32.5);
            case WALL -> new Vec3(face - 1e-5, 14.5, 32.5);
            case GROUND -> new Vec3(face - 3, 12, 32.5);
        };
        Vec3 at = h.absoluteVec(relative);
        Entity source = striker(level, weapon);
        double charge = weapon.spec().charge();
        BlockState barrier = hit == Hit.ROOF ? Blocks.OAK_PLANKS.defaultBlockState() : wall;
        double path = (hit == Hit.ROOF ? 1 : layers) * thickness(barrier);
        double radius = Penetrability.of(barrier).breaching().radius(charge, BreachModel.UNTAMPED);
        boolean breach = hit != Hit.GROUND && path < radius;
        var support = Warheads.support(level, at, charge, source.getDeltaMovement().normalize(), source);
        if (hit != Hit.GROUND) h.assertTrue(support.isPresent() && (support.get().slab() > 0) == breach,
                "normal contact decision differs from material path / FM radius: " + path + " / " + radius + ", " + support);
        Warheads.testDetonate(level, weapon, at, source, 0x544849434bL);
        boolean[] logged = {false};
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).idle(), "strike unfinished");
            int walls = 0, roof = 0, glass = 0, ground = 0;
            for (var entry : before.entrySet()) {
                if (level.getBlockState(entry.getKey()).is(entry.getValue().getBlock())) continue;
                if (entry.getValue().is(Blocks.DIRT)) ground++;
                else if (entry.getValue().is(Blocks.OAK_PLANKS)) roof++;
                else if (entry.getValue().is(Blocks.GLASS)) glass++;
                else walls++;
            }
            if (!logged[0]) {
                logged[0] = true;
                Incoming.LOG.info("THICKBENCH {} | {} | {} | walls={} roof={} glass={} ground={} | materialPath={} FM={}",
                        weapon, house, hit, walls, roof, glass, ground, path, radius);
            }
            // Law-derived lower bound: a normally penetrable charge must open its contact cell through every layer.
            // This tests the whole strike, not only its support probe. A nonpenetrable thick wall retains the axial rear cell.
            if (hit == Hit.ROOF && breach) h.assertTrue(!h.getBlockState(new BlockPos(32, 16, 32)).is(Blocks.OAK_PLANKS), "breachable roof stayed shut");
            if (hit == Hit.WALL) {
                for (int x = face; x <= 29; x++) {
                    if (breach) h.assertTrue(!h.getBlockState(new BlockPos(x, 14, 32)).is(wall.getBlock()), "breachable wall stayed shut at " + x);
                }
                if (!breach && layers == 3) h.assertTrue(h.getBlockState(new BlockPos(29, 14, 32)).is(wall.getBlock()), "charge crossed a wall thicker than its FM radius");
            }
        });
    }
}
