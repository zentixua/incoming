package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    static final int GROUND = 11;
    private static int holders;
    private static boolean debris, fire;

    static void quiet(GameTestHelper h) {
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
    static void ground(GameTestHelper h) {
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
        if (!StrikeSupport.tryReserve()) throw new IllegalStateException("no collapse solve slot");
        StrikeSupport s = new StrikeSupport(h.getLevel(), at, was);
        while (!s.started()) s.snapshot(System::nanoTime, Long.MAX_VALUE);
        StrikeSupport.Result r;
        try {
            r = s.result();
        } finally {
            s.release();
        }
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (int i = 0; i < r.at().length; i++) {
            if (!StrikeSupport.bearing(r.was()[i])) continue;
            BlockPos p = BlockPos.of(r.at()[i]);
            out.add(h.relativePos(p));
        }
        return out;
    }

    /**
     * A drone or missile that flew in through a ground-floor window and burst inside a flat: the gases blow the flat out.
     * The missile takes out more wall than the storeys above can span, and they come down; the drone's hole stays spanned.
     * The far end of the block stands; what stays up is held by the law (nothing left that the law would drop).
     */
    @GameTest(template = "range", batch = "collapse", timeoutTicks = 2400, skyAccess = true)
    public static void droneInFlatLeavesNothingHanging(GameTestHelper h) {
        inFlat(h, WeaponType.DRONE);
    }

    @GameTest(template = "range", batch = "collapse", timeoutTicks = 2400, skyAccess = true)
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
    @GameTest(template = "range", batch = "collapse", timeoutTicks = 2400, skyAccess = true)
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

    /**
     * The fall is portioned by time like the explosion's blocks ({@code WorkGameTests.blockPortionsByTime}): on a clock
     * that advances 1 ms per block, every portion stops at the 5 ms deadline, so a whole storey does not come down in one
     * tick's work. Removals made by the test: the ground-floor walls of half the block.
     */
    @GameTest(template = "range", batch = "collapse", timeoutTicks = 2400, skyAccess = true)
    public static void fallPortionsByTime(GameTestHelper h) {
        quiet(h);
        ground(h);
        ServerLevel level = h.getLevel();
        Map<BlockPos, BlockState> house = block(h, 24, 39, 28, 35, 4, true);
        it.unimi.dsi.fastutil.longs.LongArrayList at = new it.unimi.dsi.fastutil.longs.LongArrayList();
        java.util.List<BlockState> was = new java.util.ArrayList<>();
        blowWestGroundFloor(h, house, at, was);
        long[] now = {0};
        Warheads.CollapseProbe probe = Warheads.testCollapse(level, h.absoluteVec(new Vec3(28, GROUND + 2, 31.5)), 16, at, was,
                () -> now[0] += 1_000_000);
        h.succeedWhen(() -> {
            h.assertTrue(probe.done(), "collapse unfinished");
            h.assertTrue(probe.fell() > 100, "the storeys above the removed walls stayed up: " + probe.fell());
            h.assertTrue(probe.largestPortion() == 5, "portion at 1 ms per block: " + probe.largestPortion() + " blocks");
            h.assertTrue(probe.portions() * 5 >= probe.fell(), "portions " + probe.portions() + " for " + probe.fell());
        });
    }

    /**
     * Supports built under the floor after the solve, before anything falls (pillars in the blown-out flat): the plan is
     * stale, a new one from fresh snapshots decides, and the storeys above stay up on the new pillars.
     */
    @GameTest(template = "range", batch = "collapse", timeoutTicks = 2400, skyAccess = true)
    public static void braceBuiltDuringSolveHolds(GameTestHelper h) {
        quiet(h);
        ground(h);
        ServerLevel level = h.getLevel();
        Map<BlockPos, BlockState> house = block(h, 24, 39, 28, 35, 4, true);
        it.unimi.dsi.fastutil.longs.LongArrayList at = new it.unimi.dsi.fastutil.longs.LongArrayList();
        java.util.List<BlockState> was = new java.util.ArrayList<>();
        blowWestGroundFloor(h, house, at, was);
        Warheads.CollapseProbe probe = Warheads.testCollapse(level, h.absoluteVec(new Vec3(28, GROUND + 2, 31.5)), 16, at, was, System::nanoTime);
        probe.beforeFall(() -> {
            // pillars inside the flat, beside what falls and none in a blown wall: only the cells beside the plan tell
            for (int x = 25; x <= 28; x += 3) for (int z = 29; z <= 32; z += 3) for (int y = GROUND + 1; y <= GROUND + 3; y++) {
                h.setBlock(new BlockPos(x, y, z), Blocks.BRICKS);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(probe.done(), "collapse unfinished");
            int above = 0, aboveGone = 0;
            for (var e : house.entrySet()) {
                BlockPos r = e.getKey();
                if (r.getY() <= GROUND + 4 || r.getX() > 31 || e.getValue().is(Blocks.GLASS)) continue;
                above++;
                if (!stands(h, r, e.getValue())) aboveGone++;
            }
            h.assertTrue(probe.replans() >= 1, "the plan was not made again after the pillars went in");
            h.assertTrue(aboveGone * 10 < above, "storeys fell onto new pillars: " + aboveGone + "/" + above);
        });
    }

    /** Removes the ground-floor walls of the block's west half (x ≤ 31) as a test's own blast; returns them. */
    private static Map<BlockPos, BlockState> blowWestGroundFloor(GameTestHelper h, Map<BlockPos, BlockState> house,
                                                                 it.unimi.dsi.fastutil.longs.LongArrayList at, java.util.List<BlockState> was) {
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        for (var e : house.entrySet()) {
            BlockPos r = e.getKey();
            if (r.getY() > GROUND + 3 || r.getX() > 31) continue;
            BlockPos a = h.absolutePos(r);
            h.getLevel().setBlock(a, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            at.add(a.asLong());
            was.add(e.getValue());
            out.put(r, e.getValue());
        }
        return out;
    }

    /**
     * The hole was filled again (walls rebuilt) before the collapse looked: no support was lost, nothing falls (review
     * a repaired support is no removal).
     */
    @GameTest(template = "range", batch = "collapse", timeoutTicks = 2400, skyAccess = true)
    public static void rebuiltSupportHolds(GameTestHelper h) {
        quiet(h);
        ground(h);
        ServerLevel level = h.getLevel();
        Map<BlockPos, BlockState> house = block(h, 24, 39, 28, 35, 4, true);
        it.unimi.dsi.fastutil.longs.LongArrayList at = new it.unimi.dsi.fastutil.longs.LongArrayList();
        java.util.List<BlockState> was = new java.util.ArrayList<>();
        Map<BlockPos, BlockState> blown = blowWestGroundFloor(h, house, at, was);
        for (var e : blown.entrySet()) level.setBlock(h.absolutePos(e.getKey()), e.getValue(), Block.UPDATE_CLIENTS);
        Warheads.CollapseProbe probe = Warheads.testCollapse(level, h.absoluteVec(new Vec3(28, GROUND + 2, 31.5)), 16, at, was, System::nanoTime);
        h.succeedWhen(() -> {
            h.assertTrue(probe.done(), "collapse unfinished");
            h.assertTrue(probe.fell() == 0, "storeys fell onto rebuilt walls: " + probe.fell());
            // windows: the missile strikes in this batch shatter glass far away
            for (var e : house.entrySet()) h.assertTrue(e.getValue().is(Blocks.GLASS) || stands(h, e.getKey(), e.getValue()), "fell: " + e.getKey());
        });
    }

    /**
     * Every kind of storage on a floor that falls keeps all it holds: a chest, a light container (brewing
     * stand), a shulker box (its contents stay in the dropped box), and Create's item vault, depot, basin, deployer and
     * saw: not vanilla containers, cleared by {@code Clearable}, and the saw's item handler gives nothing out (extracting
     * through the handler and then clearing loses its log). Contents are counted in the dropped items,
     * shulker contents inside the dropped box.
     */
    @GameTest(template = "range", batch = "collapse", timeoutTicks = 2400, skyAccess = true)
    public static void storageContentsSurvive(GameTestHelper h) {
        quiet(h);
        ground(h);
        ServerLevel level = h.getLevel();
        Map<BlockPos, BlockState> house = block(h, 24, 39, 28, 35, 4, true);
        Block vault = create("item_vault"), depot = create("depot"), basin = create("basin"), deployer = create("deployer"), saw = create("mechanical_saw");
        h.assertTrue(vault != Blocks.AIR && depot != Blocks.AIR && basin != Blocks.AIR && deployer != Blocks.AIR && saw != Blocks.AIR, "Create blocks missing — Create not loaded");
        int y = GROUND + 5;
        BlockPos chest = new BlockPos(26, y, 30), stand = new BlockPos(26, y, 33), shulker = new BlockPos(29, y, 33),
                vaultAt = new BlockPos(28, y, 30), depotAt = new BlockPos(29, y, 31), basinAt = new BlockPos(27, y, 32),
                deployerAt = new BlockPos(25, y, 31), sawAt = new BlockPos(28, y, 33);
        h.setBlock(chest, Blocks.CHEST);
        h.setBlock(stand, Blocks.BREWING_STAND);
        h.setBlock(shulker, Blocks.SHULKER_BOX);
        h.setBlock(vaultAt, vault);
        h.setBlock(depotAt, depot);
        h.setBlock(basinAt, basin);
        h.setBlock(deployerAt, deployer);
        h.setBlock(sawAt, saw);
        net.minecraft.world.item.Item[] kinds = {net.minecraft.world.item.Items.DIAMOND, net.minecraft.world.item.Items.GUNPOWDER,
                net.minecraft.world.item.Items.GOLD_INGOT, net.minecraft.world.item.Items.IRON_INGOT, net.minecraft.world.item.Items.EMERALD,
                net.minecraft.world.item.Items.COPPER_INGOT, net.minecraft.world.item.Items.BRICK, net.minecraft.world.item.Items.OAK_LOG};
        int[] want = {7, 3, 10, 200, 16, 32, 5, 1};
        // the vault becomes its own controller on its first tick; its item handler exists from then on
        h.runAfterDelay(10, () -> {
            ((net.minecraft.world.Container) h.getBlockEntity(chest)).setItem(0, new net.minecraft.world.item.ItemStack(kinds[0], want[0]));
            // the ingredient slot: blaze powder in the fuel slot would burn as the stand's fuel
            ((net.minecraft.world.Container) h.getBlockEntity(stand)).setItem(3, new net.minecraft.world.item.ItemStack(kinds[1], want[1]));
            ((net.minecraft.world.Container) h.getBlockEntity(shulker)).setItem(0, new net.minecraft.world.item.ItemStack(kinds[2], want[2]));
            insert(h, vaultAt, kinds[3], want[3]);
            insert(h, depotAt, kinds[4], want[4]);
            insert(h, basinAt, kinds[5], want[5]);
            insert(h, deployerAt, kinds[6], want[6]);
            insert(h, sawAt, kinds[7], want[7]);
            it.unimi.dsi.fastutil.longs.LongArrayList at = new it.unimi.dsi.fastutil.longs.LongArrayList();
            java.util.List<BlockState> was = new java.util.ArrayList<>();
            blowWestGroundFloor(h, house, at, was);
            Warheads.CollapseProbe probe = Warheads.testCollapse(level, h.absoluteVec(new Vec3(28, GROUND + 2, 31.5)), 16, at, was, System::nanoTime);
            h.succeedWhen(() -> {
                h.assertTrue(probe.done() && probe.fell() > 100, "collapse " + probe.done() + ", fell " + probe.fell());
                for (BlockPos p : new BlockPos[]{chest, stand, shulker, vaultAt, depotAt, basinAt, deployerAt, sawAt}) {
                    h.assertTrue(h.getBlockState(p).isAir() || !h.getBlockState(p).hasBlockEntity(), "storage at " + p + " stayed: its floor fell");
                }
                int[] got = new int[kinds.length];
                for (var item : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(h.absolutePos(chest)).inflate(24))) {
                    count(item.getItem(), kinds, got);
                    var inside = item.getItem().get(net.minecraft.core.component.DataComponents.CONTAINER);
                    if (inside != null) inside.nonEmptyItems().forEach(st -> count(st, kinds, got));
                }
                for (int k = 0; k < kinds.length; k++) {
                    h.assertTrue(got[k] == want[k], kinds[k] + " after the collapse: " + got[k] + " of " + want[k]);
                }
            });
        });
    }

    /**
     * An unopened loot chest on a floor that falls keeps its loot unrolled: the dropped chest carries its table and seed
     * ({@code container_loot}) and rolls them when placed and opened, and nothing is rolled into loose items during the fall.
     */
    @GameTest(template = "range", batch = "collapse", timeoutTicks = 2400, skyAccess = true)
    public static void lootChestKeepsLoot(GameTestHelper h) {
        quiet(h);
        ground(h);
        ServerLevel level = h.getLevel();
        Map<BlockPos, BlockState> house = block(h, 24, 39, 28, 35, 4, true);
        BlockPos chest = new BlockPos(26, GROUND + 5, 30);
        h.setBlock(chest, Blocks.CHEST);
        var table = net.minecraft.world.level.storage.loot.BuiltInLootTables.SIMPLE_DUNGEON;
        h.runAfterDelay(10, () -> {
            ((net.minecraft.world.RandomizableContainer) h.getBlockEntity(chest)).setLootTable(table, 42L);
            it.unimi.dsi.fastutil.longs.LongArrayList at = new it.unimi.dsi.fastutil.longs.LongArrayList();
            java.util.List<BlockState> was = new java.util.ArrayList<>();
            blowWestGroundFloor(h, house, at, was);
            Warheads.CollapseProbe probe = Warheads.testCollapse(level, h.absoluteVec(new Vec3(28, GROUND + 2, 31.5)), 16, at, was, System::nanoTime);
            h.succeedWhen(() -> {
                h.assertTrue(probe.done() && probe.fell() > 100, "collapse " + probe.done() + ", fell " + probe.fell());
                h.assertTrue(!h.getBlockState(chest).is(Blocks.CHEST), "the chest stayed: its floor did not fall");
                int chests = 0, withTable = 0, loose = 0;
                for (var item : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(h.absolutePos(chest)).inflate(24))) {
                    var st = item.getItem();
                    if (st.is(net.minecraft.world.item.Items.CHEST)) {
                        chests += st.getCount();
                        var loot = st.get(net.minecraft.core.component.DataComponents.CONTAINER_LOOT);
                        if (loot != null && loot.lootTable().equals(table) && loot.seed() == 42L) withTable++;
                    } else if (!(st.getItem() instanceof net.minecraft.world.item.BlockItem)) loose++;
                }
                h.assertTrue(chests == 1 && withTable == 1 && loose == 0, "loot chest after the collapse: " + chests + " chest item(s), "
                        + withTable + " carrying its table and seed, " + loose + " loose loot stacks");
            });
        });
    }

    private static Block create(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create", id));
    }

    /** Put {@code count} of {@code item} into the block's item handler (side-less), all of it. */
    private static void insert(GameTestHelper h, BlockPos r, net.minecraft.world.item.Item item, int count) {
        var handler = h.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, h.absolutePos(r), null);
        h.assertTrue(handler != null, "no item handler at " + r);
        while (count > 0) {
            int n = Math.min(count, item.getDefaultMaxStackSize());
            net.minecraft.world.item.ItemStack rest = net.neoforged.neoforge.items.ItemHandlerHelper.insertItem(handler, new net.minecraft.world.item.ItemStack(item, n), false);
            h.assertTrue(rest.isEmpty(), "the item handler at " + r + " took only part: " + rest);
            count -= n;
        }
    }

    private static void count(net.minecraft.world.item.ItemStack st, net.minecraft.world.item.Item[] kinds, int[] got) {
        for (int k = 0; k < kinds.length; k++) if (st.is(kinds[k])) got[k] += st.getCount();
    }

    /**
     * A snapshot step is bounded by its time slice on the worst-case chunk (one whole dense chunk as a step takes
     * 34–138 ms): 4×4 chunks whose top sections hold 4096 distinct states each (global palette), snapshotted on a
     * counting clock (1 ms a read, 5 ms slices). Every call stops after at most 5 units, and a dense section is never
     * part of a larger unit, so a call covers at most 5 of them.
     */
    @GameTest(template = "range", batch = "collapse", timeoutTicks = 2400, skyAccess = true)
    public static void snapshotStepsStayInTheirSlice(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos o = h.absolutePos(BlockPos.ZERO);
        int cx = (o.getX() >> 4) + 1, cz = (o.getZ() >> 4) + 1, sections = level.getSectionsCount();
        // removal at a chunk's middle: the solve takes its chunk and the next on each axis, the snapshot their neighbors
        BlockPos removal = new BlockPos(cx * 16 + 8, level.getMaxBuildHeight() - 24, cz * 16 + 8);
        boolean[] done = {false};
        h.succeedWhen(() -> {
            if (!done[0]) {
                // the batch's other collapses may hold every slot: wait for one before touching the world
                h.assertTrue(StrikeSupport.tryReserve(), "waiting for a collapse solve slot");
                done[0] = true;
                // a failed bound is final (not an assertion succeedWhen would retry and then skip)
                String failed = snapshotInSlices(level, cx, cz, sections, removal);
                if (failed != null) throw new IllegalStateException(failed);
            }
        });
    }

    /**
     * {@link #snapshotStepsStayInTheirSlice} with a slot held: dense sections in, snapshot, sections back, in one call.
     * Returns the failed bound, or null.
     */
    @org.jetbrains.annotations.Nullable
    private static String snapshotInSlices(ServerLevel level, int cx, int cz, int sections, BlockPos removal) {
        StrikeSupport support = new StrikeSupport(level, new it.unimi.dsi.fastutil.longs.LongArrayList(new long[]{removal.asLong()}),
                java.util.List.of(Blocks.STONE.defaultBlockState()));
        ua.zentix.incoming.nuclear.world.DenseChunks dense = null;
        int calls = 0, most = 0;
        try {
            dense = new ua.zentix.incoming.nuclear.world.DenseChunks(level, cx - 1, cz - 1, cx + 2, cz + 2, sections - 4, 4096);
            long[] now = {0};
            int[] reads = {0};
            java.util.function.LongSupplier clock = () -> {
                reads[0]++;
                return now[0] += 1_000_000;
            };
            while (!support.started() && calls < 1_000_000) {
                reads[0] = 0;
                support.snapshot(clock, 5_000_000);
                most = Math.max(most, reads[0] - 1);
                calls++;
            }
        } finally {
            support.cancel();
            if (dense != null) dense.restore();
        }
        Incoming.LOG.info("COLLAPSEBENCH snapshot of {} dense sections ({} chunks solved): {} units in {} calls, at most {} a call",
                dense.sections, support.chunks(), support.units(), calls, most);
        if (!support.started()) return "snapshot never finished";
        if (dense.sections != 64) return "dense sections " + dense.sections + " of 64";
        if (most > 5) return "a snapshot call ran " + most + " units past its 5 ms slice";
        if (calls * 5 < dense.sections) return "a call covered more than 5 dense sections: " + calls + " calls for " + dense.sections;
        return null;
    }

    /**
     * Conventional impact work holds the Sable origin chunk (Sable reads block-entity shapes at
     * {@code BlockPos.ZERO}, which loads chunk (0, 0) synchronously when nothing holds it), as nuclear work does.
     */
    @GameTest(template = "range", batch = "collapse", timeoutTicks = 2400, skyAccess = true)
    public static void impactWorkHoldsSableOrigin(GameTestHelper h) {
        quiet(h);
        ground(h);
        ServerLevel level = h.getLevel();
        h.assertTrue(ua.zentix.incoming.compat.SableTerrain.present(), "Sable not loaded");
        Warheads.testDetonate(level, WeaponType.DRONE, h.absoluteVec(new Vec3(40, GROUND + 1, 40)), striker(level, WeaponType.DRONE), 0x4f524947L);
        boolean[] held = {false};
        h.onEachTick(() -> {
            if (!StrikeWorld.get(level).impacts().isEmpty() && ua.zentix.incoming.nuclear.world.NuclearTickets.holdsOrigin(level)) held[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).idle(), "strike unfinished");
            h.assertTrue(held[0], "the Sable origin chunk was not held while impact work ran");
        });
    }

    /**
     * Probe (bench, log only): wall time of a chunk snapshot on dense chunks, old publication against new, and the
     * slowest collapse snapshot unit. Runs with the salvo bench ({@code -PgametestOnly=collapse_salvo}).
     */
    @GameTest(template = "range", batch = "collapse_salvo", timeoutTicks = 100, skyAccess = true)
    public static void snapshotProbe(GameTestHelper h) {
        if (!System.getProperty("incoming.gametest.only", "").equals("collapse_salvo")) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        BlockPos o = h.absolutePos(BlockPos.ZERO);
        int cx = (o.getX() >> 4) + 1, cz = (o.getZ() >> 4) + 1;
        // every section above the test's ground and houses
        int from = (o.getY() + 48 - level.getMinBuildHeight()) >> 4;
        BlockPos removal = new BlockPos(cx * 16 + 8, level.getMaxBuildHeight() - 24, cz * 16 + 8);
        for (int distinct : new int[]{1024, 4096}) {
            ua.zentix.incoming.nuclear.world.DenseChunks dense = new ua.zentix.incoming.nuclear.world.DenseChunks(level, cx - 1, cz - 1, cx + 2, cz + 2, from, distinct);
            try {
                Incoming.LOG.info("COLLAPSEBENCH snapshot probe, {} distinct states a section: {}", distinct, dense.probe(removal));
            } finally {
                dense.restore();
            }
        }
        h.succeed();
    }

    /** Salvo: houses on a grid, one per chunk, a missile into the ground floor of each. */
    private static final int SALVO_SIDE = 10, SALVO_STEP = 16;
    private static final int HOUSE_WIDTH = 8;

    record SalvoHouse(BlockPos corner, Map<BlockPos, BlockState> upper) {
        Vec3 target() {
            return new Vec3(corner.getX() + 3.5, corner.getY() + 2, corner.getZ() + 3.5);
        }

        boolean contains(Vec3 base) {
            return base.x >= corner.getX() && base.x < corner.getX() + HOUSE_WIDTH
                    && base.z >= corner.getZ() && base.z < corner.getZ() + HOUSE_WIDTH;
        }

        int fallenUpperBlocks(ServerLevel level) {
            int fallen = 0;
            for (var entry : upper.entrySet()) {
                if (!level.getBlockState(entry.getKey()).is(entry.getValue().getBlock())) fallen++;
            }
            return fallen;
        }
    }

    /**
     * 100 missiles at once into 100 two-storey houses (one per chunk): the collapse adds impact work per strike, not per
     * tick. Logs the impact lane per tick against the budget, with collapse ({@code collapse_salvo}) and without
     * ({@code collapse_salvo_off}, the same strikes before this change). A bench, not a CI check: the houses cover 160 × 160
     * blocks beyond the platform, where the next batches' platforms are placed, so it runs only when selected alone by name
     * ({@code -PgametestOnly=collapse_salvo} or {@code collapse_salvo_off}) and otherwise passes at once.
     */
    @GameTest(template = "range", batch = "collapse_salvo", timeoutTicks = 6000, skyAccess = true)
    public static void salvoCollapseStaysInBudget(GameTestHelper h) {
        salvo(h, true);
    }

    @GameTest(template = "range", batch = "collapse_salvo_off", timeoutTicks = 6000, skyAccess = true)
    public static void salvoWithoutCollapse(GameTestHelper h) {
        salvo(h, false);
    }

    private static void salvo(GameTestHelper h, boolean collapse) {
        if (!System.getProperty("incoming.gametest.only", "").equals(collapse ? "collapse_salvo" : "collapse_salvo_off")) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        List<SalvoHouse> town = salvoTown(h, h.absolutePos(BlockPos.ZERO), collapse);
        strikeSalvo(level, town);
        net.minecraft.server.MinecraftServer server = level.getServer();
        long cap = ua.zentix.incoming.work.WorkScheduler.totalNanos();
        long[] lane = {0, 0, 0};
        int[] ticks = {0};
        h.onEachTick(() -> {
            long ns = ua.zentix.incoming.work.WorkScheduler.ownNanos(server.getTickCount() - 1);
            ticks[0]++;
            lane[0] = Math.max(lane[0], ns);
            lane[1] += ns;
            if (ns > cap) lane[2]++;
        });
        boolean[] logged = {false};
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).idle(), "strikes unfinished");
            int fell = 0, houses = 0;
            for (SalvoHouse house : town) {
                int gone = house.fallenUpperBlocks(level);
                fell += gone;
                if (gone * 2 > house.upper().size()) houses++;
            }
            if (!logged[0]) {
                logged[0] = true;
                Incoming.LOG.info(String.format(java.util.Locale.ROOT,
                        "COLLAPSEBENCH salvo %d missiles, collapse %s: %d ticks, impact lane max %.1f ms, mean %.1f ms, ticks over the %.0f ms budget %d; upper storeys gone %d blocks, mostly down in %d houses",
                        town.size(), collapse ? "on" : "off", ticks[0], lane[0] / 1e6, lane[1] / 1e6 / Math.max(1, ticks[0]), cap / 1e6, lane[2], fell, houses));
            }
            if (collapse) h.assertTrue(houses * 10 >= town.size() * 8, "upper storeys stayed up in most houses: " + houses + "/" + town.size());
        });
    }

    static List<SalvoHouse> salvoTown(GameTestHelper h, BlockPos origin, boolean collapse) {
        quiet(h);
        boolean was = IncomingConfig.SERVER.collapse.get();
        IncomingConfig.SERVER.collapse.set(collapse);
        ServerLevel level = h.getLevel();
        List<ChunkPos> forced = new ArrayList<>();
        StrikeGameTests.afterTest(h, () -> {
            IncomingConfig.SERVER.collapse.set(was);
            for (var c : forced) level.setChunkForced(c.x, c.z, false);
        });
        int cx0 = (origin.getX() >> 4) - 1, cz0 = (origin.getZ() >> 4) - 1, n = SALVO_SIDE * SALVO_STEP / 16 + 2;
        for (int cx = cx0; cx <= cx0 + n; cx++) {
            for (int cz = cz0; cz <= cz0 + n; cz++) {
                level.setChunkForced(cx, cz, true);
                forced.add(new ChunkPos(cx, cz));
            }
        }
        BlockState stone = Blocks.STONE.defaultBlockState(), wall = Blocks.BRICKS.defaultBlockState(), slab = Blocks.GRAY_CONCRETE.defaultBlockState(),
                air = Blocks.AIR.defaultBlockState();
        List<SalvoHouse> town = new ArrayList<>();
        for (int i = 0; i < SALVO_SIDE; i++) {
            for (int j = 0; j < SALVO_SIDE; j++) {
                // absolute: houses beyond the platform stand on ground filled here (the platform's own layer is one block)
                int x0 = (origin.getX() & ~15) + i * SALVO_STEP + 4;
                int z0 = (origin.getZ() & ~15) + j * SALVO_STEP + 4, g = origin.getY() + GROUND;
                Map<BlockPos, BlockState> up = new LinkedHashMap<>();
                for (int x = x0 - 4; x < x0 + 12; x++) for (int z = z0 - 4; z < z0 + 12; z++) {
                    for (int y = origin.getY() - 8; y <= g + 10; y++) {
                        BlockState st = y <= g ? stone : air;
                        boolean in = x >= x0 && x < x0 + HOUSE_WIDTH && z >= z0 && z < z0 + HOUSE_WIDTH;
                        boolean edge = x == x0 || x == x0 + HOUSE_WIDTH - 1 || z == z0 || z == z0 + HOUSE_WIDTH - 1;
                        if (in && y > g && y <= g + 8) st = (y - g) % 4 == 0 ? slab : edge ? wall : air;
                        level.setBlock(new BlockPos(x, y, z), st, Block.UPDATE_CLIENTS);
                        if (in && y > g + 4 && !st.isAir()) up.put(new BlockPos(x, y, z), st);
                    }
                }
                town.add(new SalvoHouse(new BlockPos(x0, g, z0), up));
            }
        }
        return town;
    }

    static void strikeSalvo(ServerLevel level, List<SalvoHouse> town) {
        for (int k = 0; k < town.size(); k++) {
            Warheads.testDetonate(level, WeaponType.MISSILE, town.get(k).target(), striker(level, WeaponType.MISSILE), 0x53414c56L + k);
        }
    }
}
