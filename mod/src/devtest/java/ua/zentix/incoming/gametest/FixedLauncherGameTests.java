package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;
import ua.zentix.incoming.launcher.LauncherLinks;
import ua.zentix.incoming.launcher.LauncherOrders;
import ua.zentix.incoming.launcher.Mission;
import ua.zentix.incoming.registry.ModBlocks;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.LaunchOrigin;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.Munitions;
import ua.zentix.incoming.strike.SalvoData;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.TargetMode;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.target.Target;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Fixed launcher ({@link FixedLauncherBlockEntity}): loaded through a hopper and item capability (cannot extract
 * that way), drops its stock when broken; right-click with the remote binds it, "Fire" on the bound remote assigns
 * a task ({@link LauncherOrders#assign}); a rising redstone edge gives one order: a projectile on its rack rail,
 * owned by the owner, using one munition from its stock per projectile. Refusals: no task, insufficient stock,
 * busy, blocked sector, owner forbidden to use the remote, target beyond map range. Launches use the "runway"
 * (ground y 3) along +Z: empty terrain and a clear launch sector.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FixedLauncherGameTests {
    /** launcher at the beginning of the strip "runway": launch course - +Z along the strip. */
    private static final BlockPos PAD = new BlockPos(16, 4, 8);
    /** The middle of the site “range” (first air - y 11). */
    private static final BlockPos RANGE_CENTER = new BlockPos(32, 11, 32);

    private FixedLauncherGameTests() {}

    // ---------------------------------------------------------------- launch on signal
/**
     * Redstone signal (red stone block nearby) - Shahed on the launcher package rail via 4 tick, on behalf of the
     *  owner, one Shahed left the stock; status bar - “order given”. Remove and put on the signal again while the
     *  projectile is on the rail - “busy” failure, the margin is the same. The package looks at the target.
     */
    @GameTest(template = "runway", timeoutTicks = 100, batch = "fixed_fire", skyAccess = true)
    public static void redstoneFiresMission(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = UUID.randomUUID();
        FixedLauncherBlockEntity be = launcher(h, PAD, owner);
        Vec3 far = Vec3.atBottomCenterOf(h.absolutePos(PAD)).add(0, -1, 1000);
        be.setMission(new Mission(WeaponType.DRONE, 1, 0, new Target.Point(far), far, Waypoints.NONE));
        h.assertTrue(be.hasRack() && be.weapon() == WeaponType.DRONE, "there is no Shahed package");
        h.assertTrue(Math.abs(be.yaw()) < 1, "package not on target: " + be.yaw());
        load(h, be, new ItemStack(ModItems.SHAHED.get(), 2));
        h.setBlock(PAD.east(), Blocks.REDSTONE_BLOCK);
        h.runAfterDelay(6, () -> {
            List<StrikeProjectile> onRails = ours(level, be, owner);
            h.assertTrue(onRails.size() == 1 && onRails.getFirst().flightPhase().onLauncher(), "not exactly one Shahed on the rail: " + onRails);
            h.assertTrue(be.stockCount() == 1, "in stock " + be.stockCount() + " instead of 1");
            h.assertValueEqual(key(be.lastReport()), "incoming.fixed_launcher.fired", "status bar");
            h.setBlock(PAD.east(), Blocks.AIR);
        });
        h.runAfterDelay(8, () -> h.setBlock(PAD.east(), Blocks.REDSTONE_BLOCK));
        h.runAfterDelay(14, () -> {
            h.assertValueEqual(key(be.lastReport()), "incoming.fixed_launcher.busy", "second signal while the projectile is on the rail");
            h.assertTrue(be.stockCount() == 1 && ours(level, be, owner).size() == 1, "the second signal fired a projectile");
            h.succeed();
        });
    }

    /**
     * Failures without launch and without stock consumption: no task; the stock is less than for the entire order (line - how much is needed and
     *  how much is there); target further than {@code map_range} from launcher; the owner is not allowed a remote control (the remote control is for operators only), and a draw
     *  launcher (set by the team) is without the owner’s rules; launch sector is closed by walls on all sides - launch failed
     *  with the reason “sector is closed”, the removed shell was returned to stock.
     */
    @GameTest(template = "runway", timeoutTicks = 20, batch = "fixed_refuse", skyAccess = true)
    public static void refusesWithoutSpending(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = UUID.randomUUID();
        FixedLauncherBlockEntity be = launcher(h, PAD, owner);
        Vec3 site = Vec3.atBottomCenterOf(h.absolutePos(PAD));
        Vec3 far = site.add(0, -1, 1000);
        load(h, be, new ItemStack(ModItems.SHAHED.get(), 2));
        h.assertValueEqual(key(LauncherOrders.fire(level, be)), "incoming.fixed_launcher.no_mission.short", "no task");
        be.setMission(new Mission(WeaponType.DRONE, 3, 10, new Target.Point(far), far, Waypoints.NONE));
        h.assertValueEqual(key(LauncherOrders.fire(level, be)), "incoming.munitions.short", "stock for 2 of 3");
        int range = IncomingConfig.SERVER.mapRange.get();
        Vec3 beyond = site.add(0, -1, range + 100);
        be.setMission(new Mission(WeaponType.DRONE, 1, 0, new Target.Point(beyond), beyond, Waypoints.NONE));
        h.assertValueEqual(key(LauncherOrders.fire(level, be)), "incoming.fixed_launcher.out_of_range", "target further than map range");
        boolean everyone = IncomingConfig.SERVER.designatorForEveryone.get();
        StrikeGameTests.afterTest(h, () -> IncomingConfig.SERVER.designatorForEveryone.set(everyone));
        IncomingConfig.SERVER.designatorForEveryone.set(false);
        be.setMission(new Mission(WeaponType.DRONE, 1, 0, new Target.Point(far), far, Waypoints.NONE));
        h.assertValueEqual(key(LauncherOrders.fire(level, be)), "incoming.fixed_launcher.no_rights", "the owner can't control the remote control");
        // walls around the launcher in 4 blocks, above the Shahed set before cocking: any course runs into them
        List<BlockPos> walls = new java.util.ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != 4) continue;
                for (int y = PAD.getY(); y <= 50; y++) walls.add(new BlockPos(PAD.getX() + dx, y, PAD.getZ() + dz));
            }
        }
        walls.forEach(p -> h.setBlock(p, Blocks.STONE));
        StrikeGameTests.afterTest(h, () -> walls.forEach(p -> h.setBlock(p, Blocks.AIR)));
        be.setOwner(null);
        h.assertValueEqual(key(LauncherOrders.fire(level, be)), "incoming.fixed_launcher.sector", "draw launcher in the walls");
        h.assertTrue(be.stockCount() == 2 && ours(level, be, null).isEmpty(), "failure spent reserve or launched a projectile");
        h.succeed();
    }

    /**
     * "Grad" salvo with launcher: supply - one package (40 shells), task - 3; the salvo comes from her pipes, while it is going on -
     *  the launch pad is busy: no order, no new task from the console; at the end in package 37. The owner's dismissal in the middle of a volley of suicide bombers
     *  cancels it: what did not fit on the rail remains in stock.
     */
    @GameTest(template = "runway", timeoutTicks = 600, batch = "fixed_salvo", skyAccess = true)
    public static void salvoPaysPerShot(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer player = player(level, "Owner", Vec3.atBottomCenterOf(h.absolutePos(PAD)).add(3, 0, 0));
        UUID owner = player.getUUID();
        FixedLauncherBlockEntity be = launcher(h, PAD, owner);
        Vec3 site = Vec3.atBottomCenterOf(h.absolutePos(PAD));
        Vec3 target = site.add(0, -1, 400);
        be.setMission(new Mission(WeaponType.ROCKET, 3, 10, new Target.Point(target), target, Waypoints.NONE));
        load(h, be, new ItemStack(ModItems.GRAD_ROCKETS.get()));
        LaunchOrigin.Fixed origin = new LaunchOrigin.Fixed(h.absolutePos(PAD));
        h.assertTrue(LauncherOrders.fire(level, be) == null, "the salvo has not started");
        h.assertTrue(SalvoData.busy(level, origin), "salvo launcher is not in the list");
        h.assertValueEqual(key(LauncherOrders.fire(level, be)), "incoming.fixed_launcher.busy", "second order in the middle of the salvo");
        // a new task in the middle of a salvo would change the package from which its shells are launched
        Mission grad = be.mission();
        ItemStack designator = new ItemStack(ModItems.DESIGNATOR.get());
        LauncherLinks.toggle(player, designator, be);
        Loadout drones = new Loadout(WeaponType.DRONE, 1, 0, TargetMode.LOOK, "", Loadout.Nuke.DEFAULT);
        LauncherOrders.assign(player, designator, drones, new ServerActions.Aim(new Target.Point(target), target, null), Waypoints.NONE);
        h.assertTrue(be.mission() == grad, "the task changed in the middle of the salvo: " + be.mission());
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(!LauncherOrders.busy(level, be), "the salvo is still going on or the package is not silent"))
                .thenExecute(() -> {
                    int left = Munitions.held(be.store().items(), ModItems.GRAD_ROCKETS.get(), 40);
                    h.assertTrue(left == 37, "in the package " + left + " instead of 37");
                    discard(level, be, owner);
                    // shahed salvo (task - from the remote control, now the launcher is free) and hang up after the first
                    Vec3 far = site.add(0, -1, 1000);
                    Loadout salvo = new Loadout(WeaponType.DRONE, 4, 20, TargetMode.LOOK, "", Loadout.Nuke.DEFAULT);
                    LauncherOrders.assign(player, designator, salvo, new ServerActions.Aim(new Target.Point(far), far, null), Waypoints.NONE);
                    h.assertTrue(be.mission() != null && be.mission().weapon() == WeaponType.DRONE && be.mission().count() == 4,
                            "the task after the salvo was not set: " + be.mission());
                    load(h, be, new ItemStack(ModItems.SHAHED.get(), 4));
                })
                .thenWaitUntil(() -> h.assertTrue(be.queue().silent(level.getGameTime()), "the package is not silent yet"))
                .thenExecute(() -> h.assertTrue(LauncherOrders.fire(level, be) == null, "shahed salvo has not started"))
                .thenWaitUntil(() -> h.assertTrue(SalvoData.get(level).remaining(owner) == 3, "the first Shahed didn't get up"))
                .thenExecute(() -> {
                    // in stock there is still a started package “Grad”: Shahed - separately
                    h.assertTrue(shaheds(be) == 3, "after the first Shahed in stock " + shaheds(be));
                    ServerActions.recall(level.getServer(), owner, "check");
                    h.assertTrue(!SalvoData.busy(level, origin) && SalvoData.get(level).remaining(owner) == 0, "the all-clear did not cancel the salvo");
                    h.assertTrue(shaheds(be) == 3, "the lights out touched the stock: " + shaheds(be));
                })
                .thenSucceed();
    }

    // ---------------------------------------------------------------- remote control

    /**
     * RMB remote control: your own launcher binds, the second launcher unbinds; someone else's - no, but your own team - yes. “Fire”
     *  of the linked remote control sets the task: weapon, number, spread, location; the package turns towards the target. Nuclear - no;
     * target beyond the map range from the launcher - no.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "fixed_assign", skyAccess = true)
    public static void designatorAssignsMission(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 c = Vec3.atBottomCenterOf(h.absolutePos(RANGE_CENTER));
        FakePlayer owner = player(level, "Owner", c.add(3, 0, 0));
        FakePlayer stranger = player(level, "Stranger", c.add(-3, 0, 0));
        FixedLauncherBlockEntity be = launcher(h, RANGE_CENTER, owner.getUUID());
        ItemStack designator = new ItemStack(ModItems.DESIGNATOR.get());
        LauncherLinks.toggle(owner, designator, be);
        h.assertTrue(LauncherLinks.of(designator).size() == 1, "didn't get attached to mine");
        LauncherLinks.toggle(owner, designator, be);
        h.assertTrue(LauncherLinks.of(designator).isEmpty(), "repeated RMB did not unfasten");
        ItemStack theirs = new ItemStack(ModItems.DESIGNATOR.get());
        LauncherLinks.toggle(stranger, theirs, be);
        h.assertTrue(LauncherLinks.of(theirs).isEmpty(), "someone else's got attached");
        // host team for UUID - by name from the list of players (in the game - also from the profile cache, the server GameTest does not have it)
        online(h, owner);
        online(h, stranger);
        Scoreboard board = level.getScoreboard();
        PlayerTeam team = board.addPlayerTeam("fixed_" + UUID.randomUUID().toString().substring(0, 8));
        StrikeGameTests.afterTest(h, () -> board.removePlayerTeam(team));
        board.addPlayerToTeam(owner.getScoreboardName(), team);
        board.addPlayerToTeam(stranger.getScoreboardName(), team);
        LauncherLinks.toggle(stranger, theirs, be);
        h.assertTrue(LauncherLinks.of(theirs).size() == 1, "launcher of your team is not attached");
        LauncherLinks.toggle(owner, designator, be);

        Vec3 east = c.add(400, 0, 0);
        Loadout drones = new Loadout(WeaponType.DRONE, 2, 5, TargetMode.LOOK, "", Loadout.Nuke.DEFAULT);
        LauncherOrders.assign(owner, designator, drones, new ServerActions.Aim(new Target.Point(east), east, null), Waypoints.NONE);
        Mission m = be.mission();
        h.assertTrue(m != null && m.weapon() == WeaponType.DRONE && m.count() == 2 && m.spread() == 5 && m.point().equals(east), "task: " + m);
        h.assertTrue(be.hasRack() && Math.abs(be.yaw() - be.yawTo(east)) < 1, "package not to the target: " + be.yaw());
        Loadout icbm = new Loadout(WeaponType.NUKE, 1, 0, TargetMode.LOOK, "", Loadout.Nuke.DEFAULT);
        LauncherOrders.assign(owner, designator, icbm, new ServerActions.Aim(new Target.Point(east), east, null), Waypoints.NONE);
        h.assertTrue(be.mission() == m, "iCBM task accepted");
        h.assertTrue(IncomingConfig.SERVER.carrierNukes.get(), "the nuclear warhead on the missile is disabled in the settings - the check below is empty");
        Loadout carrier = new Loadout(WeaponType.MISSILE, 1, 0, TargetMode.LOOK, "", new Loadout.Nuke(5, true, true));
        LauncherOrders.assign(owner, designator, carrier, new ServerActions.Aim(new Target.Point(east), east, null), Waypoints.NONE);
        h.assertTrue(be.mission() == m, "missile with nuclear warhead accepted");
        Vec3 beyond = c.add(0, 0, IncomingConfig.SERVER.mapRange.get() + 100);
        owner.moveTo(beyond.add(0, 0, -50));
        LauncherOrders.assign(owner, designator, drones, new ServerActions.Aim(new Target.Point(beyond), beyond, null), Waypoints.NONE);
        h.assertTrue(be.mission() == m, "target beyond map range from launcher accepted");
        // a broken launcher is untied at the next “Fire”, otherwise the remote control would remain in task mode
        owner.moveTo(c.add(3, 0, 0));
        level.removeBlock(h.absolutePos(RANGE_CENTER), false);
        LauncherOrders.assign(owner, designator, drones, new ServerActions.Aim(new Target.Point(east), east, null), Waypoints.NONE);
        h.assertTrue(LauncherLinks.of(designator).isEmpty(), "broken launcher remained tied");
        h.assertTrue(LauncherLinks.of(theirs).size() == 1, "got loose at someone else's remote control");
        h.succeed();
    }

    // ---------------------------------------------------------------- block

    /**
     * The stockpile is full of stacks of "Grad" packages (crater keeps it full): a package from the stack is opened, and the remainder goes
     *  into the spare cell, not on the ground; the next projectiles are from it, the return is into it.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "fixed_block", skyAccess = true)
    public static void fullStoreKeepsOpenedPack(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos at = new BlockPos(12, 11, 12);
        FixedLauncherBlockEntity be = launcher(h, at, null);
        BlockPos abs = h.absolutePos(at);
        IItemHandler cap = level.getCapability(Capabilities.ItemHandler.BLOCK, abs, Direction.UP);
        ItemStack grad = new ItemStack(ModItems.GRAD_ROCKETS.get());
        int per = 40, full = FixedLauncherBlockEntity.SLOTS * grad.getMaxStackSize() * per;
        h.assertTrue(cap != null && insert(cap, grad.copyWithCount(FixedLauncherBlockEntity.SLOTS * grad.getMaxStackSize())).isEmpty(), "stock not filled");
        h.assertTrue(!insert(cap, grad.copy()).isEmpty(), "the full supply included more");
        AABB around = new AABB(abs).inflate(4);
        int[] paid = {0};
        java.util.function.IntConsumer pay = n -> {
            h.assertTrue(Munitions.pay(be.store(), new Munitions.Bill(WeaponType.ROCKET, n, 0)) == null, "not paid " + n);
            paid[0] += n;
        };
        java.util.function.Consumer<String> check = when -> {
            int left = Munitions.held(be.store().items(), grad.getItem(), per);
            h.assertTrue(left == full - paid[0], when + ": shells " + left + " instead of " + (full - paid[0]));
            h.assertTrue(dropped(level, around, grad) == 0, when + ": the package fell to the ground");
        };
        pay.accept(1);
        check.accept("first shell");
        Munitions.refund(be.store(), new Munitions.Bill(WeaponType.ROCKET, 1, 0));
        paid[0] -= 1;
        check.accept("refund");
        pay.accept(1);
        check.accept("shell again");
        pay.accept(39);
        check.accept("the opened package is empty");
        pay.accept(1);
        check.accept("second package opened");
        h.succeed();
    }

    /**
     * Stock: Any side and no side item ability takes ammo, not ground or ICBM; It is impossible to remove
     *  with it; hopper puts on top, does not pull from below; the comparator sees the margin. Saving: master, task, stock,
     * course; the client receives only the package, course and ascent. Broken - falls out on its own and drops the stock. Pickaxe, stone and better;
     *  recipe - according to whether Create is worth it.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "fixed_block", skyAccess = true)
    public static void blockLoadsSavesAndBreaks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = UUID.randomUUID();
        BlockPos at = new BlockPos(20, 11, 20);
        FixedLauncherBlockEntity be = launcher(h, at, owner);
        BlockPos abs = h.absolutePos(at);
        h.assertTrue(level.getBlockState(abs).is(BlockTags.MINEABLE_WITH_PICKAXE) && level.getBlockState(abs).is(BlockTags.NEEDS_STONE_TOOL),
                "launcher not for stone pickaxe");
        for (Direction side : new Direction[]{Direction.UP, Direction.NORTH, null}) {
            IItemHandler cap = level.getCapability(Capabilities.ItemHandler.BLOCK, abs, side);
            h.assertTrue(cap != null && cap.getSlots() == FixedLauncherBlockEntity.SLOTS, "outside ability " + side);
        }
        IItemHandler cap = level.getCapability(Capabilities.ItemHandler.BLOCK, abs, Direction.UP);
        h.assertTrue(insert(cap, new ItemStack(Items.DIRT, 5)).getCount() == 5, "launcher took the ground");
        h.assertTrue(insert(cap, new ItemStack(ModItems.ICBM.get())).getCount() == 1, "launcher took ICBM");
        h.assertTrue(insert(cap, new ItemStack(ModItems.CRUISE_MISSILE.get(), 3)).isEmpty(), "the missiles did not enter");
        for (int i = 0; i < cap.getSlots(); i++) h.assertTrue(cap.extractItem(i, 64, false).isEmpty(), "ammunition taken out by ability");
        h.assertTrue(level.getBlockState(abs).getAnalogOutputSignal(level, abs) > 0, "the comparator does not see the reserve");
        Vec3 east = Vec3.atBottomCenterOf(abs).add(300, 0, 0);
        be.setMission(new Mission(WeaponType.MISSILE, 2, 0, new Target.Point(east), east, Waypoints.NONE));

        CompoundTag saved = be.saveWithFullMetadata(level.registryAccess());
        BlockEntity copy = BlockEntity.loadStatic(abs, level.getBlockState(abs), saved, level.registryAccess());
        h.assertTrue(copy instanceof FixedLauncherBlockEntity f && owner.equals(f.owner()) && be.mission().equals(f.mission())
                && f.stockCount() == 3 && f.yaw() == be.yaw() && f.weapon() == WeaponType.MISSILE, "saving: " + saved);
        CompoundTag update = be.getUpdateTag(level.registryAccess());
        h.assertTrue(!update.contains("owner") && !update.contains("mission") && !update.contains("stock") && update.contains("rack"),
                "the client lost extra money: " + update);

        BlockPos below = at.below(), above = at.above();
        h.setBlock(below, Blocks.HOPPER);
        h.setBlock(above, Blocks.HOPPER);
        HopperBlockEntity top = h.getBlockEntity(above);
        top.setItem(0, new ItemStack(ModItems.SHAHED.get(), 2));
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(top.getItem(0).isEmpty(), "hopper from above did not give Shahed"))
                .thenIdle(20)
                .thenExecute(() -> {
                    HopperBlockEntity bottom = h.getBlockEntity(below);
                    h.assertTrue(bottom.isEmpty(), "hopper under the launcher pulled out the ammunition");
                    h.assertTrue(be.stockCount() == 5, "in stock " + be.stockCount() + " instead of 5");
                    level.setBlock(h.absolutePos(above), Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(h.absolutePos(below), Blocks.STONE.defaultBlockState(), 3);
                    AABB box = new AABB(abs).inflate(3);
                    level.getEntitiesOfClass(ItemEntity.class, box).forEach(ItemEntity::discard);
                    level.destroyBlock(abs, true);
                    h.assertTrue(level.getBlockState(abs).isAir(), "launcher is not broken");
                    h.assertTrue(dropped(level, box, ModItems.FIXED_LAUNCHER.get().getDefaultInstance()) == 1, "broken launcher did not fall out");
                    h.assertTrue(dropped(level, box, ModItems.SHAHED.get().getDefaultInstance()) == 2
                            && dropped(level, box, ModItems.CRUISE_MISSILE.get().getDefaultInstance()) == 3, "the stock did not drop out");
                    level.getEntitiesOfClass(ItemEntity.class, box).forEach(ItemEntity::discard);
                    var recipes = level.getServer().getRecipeManager();
                    boolean create = ModList.get().isLoaded("create");
                    h.assertTrue(recipes.byKey(Incoming.id("fixed_launcher")).isPresent() == create, "recipe with Create: " + create);
                    h.assertTrue(recipes.byKey(Incoming.id("fixed_launcher_vanilla")).isPresent() == !create, "recipe without Create: " + !create);
                })
                .thenSucceed();
    }

    /**
     * Task by the leader’s team ({@code /incoming launcher … mission}): “x z” - a place on the ground, the height is found by the server;
     *  “x y z” - the point as it is, and above the ground too.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "fixed_command", skyAccess = true)
    public static void commandMissionTakesPlaceOrPoint(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FixedLauncherBlockEntity be = launcher(h, RANGE_CENTER, null);
        BlockPos at = h.absolutePos(RANGE_CENTER);
        Vec3 east = Vec3.atBottomCenterOf(at).add(12, 0, 0);
        String mission = String.format(Locale.ROOT, "incoming launcher %d %d %d mission drone 2 5 ", at.getX(), at.getY(), at.getZ());
        h.assertTrue(StrikeGameTests.command(h, mission + String.format(Locale.ROOT, "%.2f %.2f", east.x, east.z)) == 1, "task \"x z\" not accepted");
        double ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(east.x), Mth.floor(east.z)) - 0.5;
        Mission m = be.mission();
        h.assertTrue(m != null && m.target() instanceof Target.Ground && m.point().y == ground, "task \"x z\" is not on the ground (y " + ground + "): " + m);
        double air = ground + 30;
        h.assertTrue(StrikeGameTests.command(h, mission + String.format(Locale.ROOT, "%.2f %.2f %.2f", east.x, air, east.z)) == 1, "task \"x y z\" not accepted");
        m = be.mission();
        h.assertTrue(m != null && m.target() instanceof Target.Point p && p.pos().y == air, "the task “x y z” is not a point at its height: " + m);
        h.succeed();
    }

    // ---------------------------------------------------------------- assistants

    /**
     * Install the launcher with the host {@code owner}, the package rate is +Z. After checking, remove it without falling out, and that’s it,
     *  that left it: shells, volleys.
     */
    private static FixedLauncherBlockEntity launcher(GameTestHelper h, BlockPos at, @Nullable UUID owner) {
        ServerLevel level = h.getLevel();
        h.setBlock(at, ModBlocks.FIXED_LAUNCHER.get());
        FixedLauncherBlockEntity be = h.getBlockEntity(at);
        BlockPos abs = h.absolutePos(at);
        be.setOwner(owner);
        be.setYaw(0);
        StrikeGameTests.afterTest(h, () -> {
            SalvoData.get(level).cancel(level, owner, List.of());
            discard(level, be, owner);
            VirtualFlights.get(level).clear(level, p -> owner != null && owner.equals(p.ownerId()));
            level.removeBlockEntity(abs);
            level.setBlock(abs, Blocks.AIR.defaultBlockState(), 3);
        });
        return be;
    }

    private static void load(GameTestHelper h, FixedLauncherBlockEntity be, ItemStack stack) {
        h.assertTrue(be.load(stack).isEmpty(), "ammunition was not included in the reserve");
    }

    /** The host's shells are {@code owner} at the launcher (on the rails and just dropped). */
    private static List<StrikeProjectile> ours(ServerLevel level, FixedLauncherBlockEntity be, @Nullable UUID owner) {
        AABB box = new AABB(be.getBlockPos()).inflate(12);
        return StrikeWorld.projectiles(level).stream().filter(p -> box.contains(p.position()) && java.util.Objects.equals(owner, p.ownerId())).toList();
    }

    private static void discard(ServerLevel level, FixedLauncherBlockEntity be, @Nullable UUID owner) {
        for (StrikeProjectile p : StrikeWorld.projectiles(level)) if (java.util.Objects.equals(owner, p.ownerId())) p.discard();
    }

    /** Translation string key (null - no string). */
    @Nullable
    private static String key(@Nullable Component c) {
        return c != null && c.getContents() instanceof TranslatableContents t ? t.getKey() : null;
    }

    private static int shaheds(FixedLauncherBlockEntity be) {
        return Munitions.held(be.store().items(), ModItems.SHAHED.get(), 1);
    }

    /** Player “online” for {@link ua.zentix.incoming.target.Sides}: in the server players map for UUID; removed after inspection. */
    private static void online(GameTestHelper h, FakePlayer p) {
        Map<UUID, ServerPlayer> byUuid = SamGameTests.playersByUuid(h.getLevel().getServer().getPlayerList());
        byUuid.put(p.getUUID(), p);
        StrikeGameTests.afterTest(h, () -> byUuid.remove(p.getUUID(), p));
    }

    private static FakePlayer player(ServerLevel level, String name, Vec3 at) {
        FakePlayer p = new FakePlayer(level, new GameProfile(UUID.randomUUID(), name));
        p.moveTo(at);
        return p;
    }

    private static ItemStack insert(IItemHandler cap, ItemStack stack) {
        for (int i = 0; i < cap.getSlots() && !stack.isEmpty(); i++) stack = cap.insertItem(i, stack, false);
        return stack;
    }

    private static int dropped(ServerLevel level, AABB box, ItemStack like) {
        int n = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) if (e.getItem().is(like.getItem())) n += e.getItem().getCount();
        return n;
    }
}
