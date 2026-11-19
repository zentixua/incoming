package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.Article;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.nuclear.world.StrikeSupport;
import ua.zentix.incoming.warhead.Warheads;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Conventional collapse ({@code StrikeCollapse}): what a warhead's blast left without support falls by the nuclear ruin
 * law. The air layer under the platform is filled with stone (a real building stands on ground; on the bare platform
 * everything would hang before the strike, see {@code NuclearGameTests.detonation}).
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CollapseGameTests {
    private CollapseGameTests() {}

    /** Platform top (relative): ground blocks up to this height. */
    private static final int GROUND = 11;
    private static int holders;
    private static boolean debris, fire;

    private static void quiet(GameTestHelper h) {
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

    /** Solid ground: the air layer under the platform and the platform up to {@link #GROUND}. */
    private static void ground(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(0, 0, 0), new BlockPos(63, GROUND, 63))) {
            BlockPos a = h.absolutePos(p);
            BlockState was = level.getBlockState(a);
            if (was.isAir() || p.getY() > 1) level.setBlock(a, p.getY() == GROUND ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
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
        e.setDeltaMovement(new Vec3(0, -Math.sin(angle), Math.cos(angle)).scale(impact.speed() / 20));
        return e;
    }

    /**
     * Brick block of {@code storeys} 4-block storeys, concrete floor slabs, optionally one interior cross wall in the middle.
     * Returns its blocks (relative position → state).
     */
    private static Map<BlockPos, BlockState> block(GameTestHelper h, int x0, int x1, int z0, int z1, int storeys, boolean crossWall) {
        ServerLevel level = h.getLevel();
        BlockState wall = Blocks.BRICKS.defaultBlockState(), slab = Blocks.GRAY_CONCRETE.defaultBlockState(),
                glass = Blocks.GLASS.defaultBlockState();
        int mid = (x0 + x1) / 2;
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        int top = GROUND + 4 * storeys;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) for (int y = GROUND + 1; y <= top; y++) {
            boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
            boolean floor = (y - GROUND) % 4 == 0;
            BlockState st = null;
            if (floor) st = slab;
            else if (edge) {
                int level4 = (y - GROUND) % 4;
                boolean window = level4 == 2 && (z == z0 || z == z1) && (x - x0) % 3 == 2 && x != x1;
                st = window ? glass : wall;
            } else if (crossWall && x == mid) st = wall;
            if (st == null) continue;
            BlockPos r = new BlockPos(x, y, z);
            level.setBlock(h.absolutePos(r), st, Block.UPDATE_CLIENTS);
            out.put(r, st);
        }
        return out;
    }

    /** Whether the original block still stands at relative {@code r}. */
    private static boolean stands(GameTestHelper h, BlockPos r, BlockState st) {
        return h.getBlockState(r).is(st.getBlock());
    }

    /** Original blocks gone (relative positions): the blast's and the collapse's. */
    private static Map<BlockPos, BlockState> gone(GameTestHelper h, Map<BlockPos, BlockState> house) {
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        for (var e : house.entrySet()) if (!stands(h, e.getKey(), e.getValue())) out.put(e.getKey(), e.getValue());
        return out;
    }

    /**
     * The law at its fixed point: with everything that went (blast and collapse) as the removals, nothing more loses
     * support. Windows broken by the glass pass after the explosion are not removals (glass carries no load; the collapse
     * sees the explosion's removals). Returns what would still fall (relative positions).
     */
    private static java.util.List<BlockPos> stillHanging(GameTestHelper h, Map<BlockPos, BlockState> gone) throws Exception {
        it.unimi.dsi.fastutil.longs.LongArrayList at = new it.unimi.dsi.fastutil.longs.LongArrayList();
        java.util.List<BlockState> was = new java.util.ArrayList<>();
        for (var e : gone.entrySet()) {
            if (e.getValue().is(Blocks.GLASS)) continue;
            at.add(h.absolutePos(e.getKey()).asLong());
            was.add(e.getValue());
        }
        StrikeSupport s = new StrikeSupport(h.getLevel(), at, was);
        while (!s.started()) s.snapshot(Integer.MAX_VALUE);
        StrikeSupport.Result r = s.result();
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (int i = 0; i < r.at().size(); i++) {
            if (!StrikeSupport.bearing(r.was().get(i))) continue;
            BlockPos p = BlockPos.of(r.at().getLong(i));
            out.add(h.relativePos(p));
        }
        return out;
    }

    /**
     * A drone or missile that flew in through a ground-floor window and burst inside a flat: the gases blow the flat out.
     * The missile takes out more wall than the storeys above can span, and they come down; the drone's hole stays spanned.
     * The far end of the block stands; what stays up is held by the law (nothing left that the law would drop).
     */
    @GameTest(template = "range", batch = "collapse", timeoutTicks = 900, skyAccess = true)
    public static void droneInFlatLeavesNothingHanging(GameTestHelper h) {
        inFlat(h, WeaponType.DRONE);
    }

    @GameTest(template = "range", batch = "collapse", timeoutTicks = 900, skyAccess = true)
    public static void missileInFlatBringsDownStoreysAbove(GameTestHelper h) {
        inFlat(h, WeaponType.MISSILE);
    }

    private static void inFlat(GameTestHelper h, WeaponType weapon) {
        quiet(h);
        ground(h);
        ServerLevel level = h.getLevel();
        Map<BlockPos, BlockState> house = block(h, 24, 39, 28, 35, 4, true);
        Vec3 at = h.absoluteVec(new Vec3(28, GROUND + 2, 31.5));
        Entity source = striker(level, weapon);
        Warheads.testDetonate(level, weapon, at, source, 0x464c4154L);
        boolean[] logged = {false};
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).idle(), "strike unfinished");
            Map<BlockPos, BlockState> gone = gone(h, house);
            int above = 0, aboveGone = 0, far = 0, farGone = 0;
            for (var e : house.entrySet()) {
                BlockPos r = e.getKey();
                boolean g = gone.containsKey(r);
                if (r.getY() > GROUND + 4 && r.getX() <= 31) {
                    above++;
                    if (g) aboveGone++;
                }
                if (r.getX() >= 36) {
                    far++;
                    if (g) farGone++;
                }
            }
            java.util.List<BlockPos> hanging;
            try {
                hanging = stillHanging(h, gone);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            if (!logged[0]) {
                logged[0] = true;
                Incoming.LOG.info("COLLAPSEBENCH {} in flat: gone {}, storeys above the flat {}/{} gone, far end {}/{} gone, still hanging {}",
                        weapon, gone.size(), aboveGone, above, farGone, far, hanging.size());
            }
            h.assertTrue(hanging.isEmpty(), "blocks left hanging: " + hanging.size() + " e.g. " + (hanging.isEmpty() ? "" : hanging.getFirst()));
            // the missile's flat loses walls wider than a slab spans: the storeys above come down; a drone's flat stays spanned
            if (weapon == WeaponType.MISSILE) h.assertTrue(aboveGone * 4 > above, "storeys above the blown-out flat still stand: " + aboveGone + "/" + above + " fell");
            h.assertTrue(farGone * 10 < far, "the far end came down too: " + farGone + "/" + far);
        });
    }

    /**
     * Drone into a roof corner of a one-storey house: the hole and what it held go, the house stands; what stays up is
     * held by the law.
     */
    @GameTest(template = "range", batch = "collapse", timeoutTicks = 900, skyAccess = true)
    public static void roofCornerKeepsTheHouse(GameTestHelper h) {
        quiet(h);
        ground(h);
        ServerLevel level = h.getLevel();
        Map<BlockPos, BlockState> house = block(h, 24, 39, 24, 39, 1, false);
        Vec3 at = h.absoluteVec(new Vec3(25.5, GROUND + 5, 25.5));
        Entity source = striker(level, WeaponType.DRONE);
        Warheads.testDetonate(level, WeaponType.DRONE, at, source, 0x524f4f46L);
        boolean[] logged = {false};
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).idle(), "strike unfinished");
            Map<BlockPos, BlockState> gone = gone(h, house);
            java.util.List<BlockPos> hanging;
            try {
                hanging = stillHanging(h, gone);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            if (!logged[0]) {
                logged[0] = true;
                Incoming.LOG.info("COLLAPSEBENCH DRONE roof corner: gone {}/{}, still hanging {}", gone.size(), house.size(), hanging.size());
            }
            h.assertTrue(hanging.isEmpty(), "blocks left hanging: " + hanging.size());
            h.assertTrue(gone.size() * 4 < house.size(), "the house came down: " + gone.size() + "/" + house.size());
        });
    }
}
