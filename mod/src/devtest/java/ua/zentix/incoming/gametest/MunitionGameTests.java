package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.entity.LauncherEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.registry.ModDataComponents;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.ChunkTickets;
import ua.zentix.incoming.strike.LaunchOrigin;
import ua.zentix.incoming.strike.LaunchPlaces;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.Munitions;
import ua.zentix.incoming.strike.SalvoData;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.TargetMode;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Ammunition from inventory ({@link Munitions}): the order is paid in full when returned, if there is not enough - refusal without launch,
 *  creative mode and operator command do not pay, unsuccessful launch and not fired from a canceled salvo are returned,
 *  ammunition recipes are loaded. The shooter is {@code FakePlayer} NeoForge, who remembers the lines for himself: he is not included in the list of
 *  players, so the list “online” itself receives the return ({@link SalvoData#cancel}).
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MunitionGameTests {
    private static final BlockPos RANGE_CENTER = new BlockPos(32, 11, 32);

    private MunitionGameTests() {}

    /**
     * The salvo is paid for immediately and in full; on the second there is not enough - refusal, the line “need 3, there is 2”, nothing was removed and there is no salvo
     * . The "Grad" package begins (12 from 40 - the remainder of 28 in the same item). The nuclear warhead on the carrier is a separate
     *  item: without it, the missile cannot be removed (all or nothing). The release of volleys returns the unfired: Shahed - with items,
     *  "Grad" shells - to the started package, and it is whole again.
     */
    @GameTest(template = "range", batch = "munitions", skyAccess = true)
    public static void orderPaysUpFrontOrRefuses(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        boolean opsOnly = IncomingConfig.SERVER.nukeOpsOnly.get();
        StrikeGameTests.afterTest(h, () -> IncomingConfig.SERVER.nukeOpsOnly.set(opsOnly));
        Listener p = player(h, "munitions_buyer");
        // a fallen test must not leave a volley that will fire across the court after it
        StrikeGameTests.afterTest(h, () -> SalvoData.get(level).cancel(level, p.getUUID(), List.of()));
        ServerActions.Aim aim = aim(h);
        Item shahed = ModItems.SHAHED.get();
        give(p, shahed, 5);

        h.assertTrue(ServerActions.strike(p, WeaponType.DRONE, 3, 20, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, true), "salvo from 3 Shahed was not accepted");
        h.assertValueEqual(held(p, shahed), 2, "shahed after a volley from 3");
        h.assertValueEqual(SalvoData.get(level).remaining(p.getUUID()), 3, "unreleased in salvo");

        h.assertFalse(ServerActions.strike(p, WeaponType.DRONE, 3, 20, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, true), "salvo without ammunition accepted");
        h.assertValueEqual(held(p, shahed), 2, "refusal removed by Shahed");
        h.assertValueEqual(SalvoData.get(level).remaining(p.getUUID()), 3, "the refusal still began to fire");
        h.assertValueEqual(args(p.last("incoming.munitions.short")), List.of(shahed.getDescription(), 3, 2), "failure line");

        Item grad = ModItems.GRAD_ROCKETS.get();
        give(p, grad, 1);
        h.assertTrue(ServerActions.strike(p, WeaponType.ROCKET, 12, 15, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, true), "\"Grad\" salvo was not accepted");
        h.assertValueEqual(held(p, grad), 28, "shells in a package after a salvo from 12");
        h.assertValueEqual(count(p, grad), 1, "started package is not the same item");

        // nuclear warhead on a rocket: without it the rocket cannot be removed
        IncomingConfig.SERVER.nukeOpsOnly.set(false);
        Item missile = ModItems.CRUISE_MISSILE.get(), warhead = Munitions.warhead();
        give(p, missile, 1);
        Loadout.Nuke onCarrier = new Loadout.Nuke(15, true, true);
        h.assertFalse(ServerActions.strike(p, WeaponType.MISSILE, 1, 0, aim, onCarrier, Waypoints.NONE, null, true), "nuclear missile without warhead accepted");
        h.assertValueEqual(held(p, missile), 1, "the missile was removed without a warhead");
        h.assertValueEqual(args(p.last("incoming.munitions.short")), List.of(warhead.getDescription(), 1, 0), "refusal line without warhead");
        // with a warhead, the account is a missile and a warhead (the launch of a nuclear missile itself is not needed in the test world)
        give(p, warhead, 1);
        Loadout nuclear = new Loadout(WeaponType.MISSILE, 1, 0, TargetMode.LOOK, "", onCarrier);
        h.assertTrue(Munitions.pay(p, Munitions.Bill.of(nuclear)), "missile with warhead not paid for");
        h.assertTrue(held(p, missile) == 0 && held(p, warhead) == 0, "payment for nuclear missile: missiles " + held(p, missile) + ", warhead " + held(p, warhead));

        // salvo release: unfired - to the owner
        h.assertValueEqual(SalvoData.get(level).cancel(level, p.getUUID(), List.of(p)), 2, "volleys canceled");
        h.assertValueEqual(held(p, shahed), 5, "shahed after lights out");
        h.assertValueEqual(held(p, grad), 40, "\"Grad\" shells after lights out");
        ItemStack pack = find(p, grad);
        h.assertTrue(pack.getCount() == 1 && !pack.has(ModDataComponents.ROUNDS.get()), "the package is not intact after lights out: " + pack + " " + pack.getComponents());
        h.assertTrue(p.last("incoming.munitions.refunded") != null && p.last("incoming.munitions.refunded.rounds") != null, "no return line");
        h.succeed();
    }

    /**
     * Free: creative mode (and remote control) and operator command ({@code rules = false}); in survival from the remote control without
     *  ammunition - failure. An unpaid salvo upon clearing returns nothing.
     */
    @GameTest(template = "range", batch = "munitions", skyAccess = true)
    public static void creativeAndOperatorCommandAreFree(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerActions.Aim aim = aim(h);
        Listener creative = player(h, "munitions_creative");
        creative.setGameMode(GameType.CREATIVE);
        h.assertTrue(creative.hasInfiniteMaterials(), "creative mode without endless materials");
        h.assertTrue(ServerActions.strike(creative, WeaponType.DRONE, 2, 10, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, true), "creative salvo is not accepted");
        Listener op = player(h, "munitions_op");
        StrikeGameTests.afterTest(h, () -> {
            SalvoData.get(level).cancel(level, creative.getUUID(), List.of());
            SalvoData.get(level).cancel(level, op.getUUID(), List.of());
        });
        h.assertTrue(ServerActions.strike(op, WeaponType.DRONE, 2, 10, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, false), "operator command not accepted");
        Listener survival = player(h, "munitions_survival");
        h.assertFalse(ServerActions.strike(survival, WeaponType.DRONE, 2, 10, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, true), "without ammunition the salvo was accepted");

        SalvoData salvos = SalvoData.get(level);
        h.assertTrue(salvos.cancel(level, creative.getUUID(), List.of(creative)) == 1 && salvos.cancel(level, op.getUUID(), List.of(op)) == 1, "no volleys");
        h.assertTrue(creative.getInventory().isEmpty() && op.getInventory().isEmpty(), "unpaid salvo returned ammunition");
        h.assertTrue(creative.last("incoming.munitions.refunded") == null && op.last("incoming.munitions.refunded") == null, "line about return without payment");
        h.succeed();
    }

    /**
     * A salvo canceled in the middle: those fired are not returned, those not fired are returned to the owner. Shahed come from afar
     *  (the shooter is not on the list of players - he does not have a launcher), and they are removed before they can reach them.
     */
    @GameTest(template = "range", timeoutTicks = 300, batch = "munitions_salvo", skyAccess = true)
    public static void cancelledSalvoRefundsUnlaunched(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Listener p = player(h, "munitions_salvo");
        UUID id = p.getUUID();
        StrikeGameTests.afterTest(h, () -> {
            SalvoData.get(level).cancel(level, id, List.of());
            removeFlights(level, id);
        });
        Item shahed = ModItems.SHAHED.get();
        give(p, shahed, 4);
        h.assertTrue(ServerActions.strike(p, WeaponType.DRONE, 4, 20, aim(h), Loadout.Nuke.DEFAULT, Waypoints.NONE, null, true), "the salvo was not received");
        h.assertValueEqual(held(p, shahed), 0, "a salvo from 4 did not remove all 4");
        boolean[] done = {false};
        h.onEachTick(() -> {
            if (done[0] || SalvoData.get(level).remaining(id) > 2) return;
            done[0] = true;
            // out of the world and in the world: Shahed, included in other people's downloaded chunks, is also released
            int launched = (int) StrikeWorld.projectiles(level).stream().filter(f -> id.equals(f.ownerId())).count();
            int cancelled = SalvoData.get(level).cancel(level, id, List.of(p));
            removeFlights(level, id);
            if (launched != 2 || cancelled != 1) throw new GameTestAssertException("released " + launched + ", canceled volleys " + cancelled);
            if (held(p, shahed) != 2) throw new GameTestAssertException("after lights out Shahed " + held(p, shahed) + " instead of 2 unreleased");
            if (!List.of(shahed.getDescription(), 2).equals(args(p.last("incoming.munitions.refunded")))) {
                throw new GameTestAssertException("return line: " + p.last("incoming.munitions.refunded"));
            }
            h.succeed();
        });
    }

    /**
     * The owner died and has not yet been reborn (death screen: in the list of players, but inventory without {@code keepInventory} is not transferred during
     *  rebirth) - the release of the salvo puts the unreleased item not in the inventory, but at the place of death.
     */
    @GameTest(template = "range", batch = "munitions", skyAccess = true)
    public static void deadOwnerRefundDropsAtDeathSpot(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Listener p = player(h, "munitions_dead");
        StrikeGameTests.afterTest(h, () -> SalvoData.get(level).cancel(level, p.getUUID(), List.of()));
        Item shahed = ModItems.SHAHED.get();
        give(p, shahed, 3);
        h.assertTrue(ServerActions.strike(p, WeaponType.DRONE, 3, 20, aim(h), Loadout.Nuke.DEFAULT, Waypoints.NONE, null, true), "the salvo was not received");
        h.assertTrue(!level.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY), "in the world GameTest keepInventory");
        p.setHealth(0);
        h.assertFalse(p.isAlive(), "the owner is alive");
        h.assertValueEqual(SalvoData.get(level).cancel(level, p.getUUID(), List.of(p)), 1, "volleys canceled");
        h.assertValueEqual(count(p, shahed), 0, "the deceased is given ammunition in his inventory");
        List<ItemEntity> dropped = level.getEntitiesOfClass(ItemEntity.class,
                p.getBoundingBox().inflate(4), e -> e.getItem().is(shahed));
        int n = dropped.stream().mapToInt(e -> e.getItem().getCount()).sum();
        dropped.forEach(Entity::discard);
        h.assertValueEqual(n, 3, "shahed at the scene of death");
        h.assertTrue(p.last("incoming.munitions.refunded.dead") != null, "there is no line about the place of death");
        h.succeed();
    }

    /**
     * An unsuccessful launch returns what was paid for: the projectile from the shooter’s launcher did not enter the world (the entry event was canceled -
     *  this is what territory defense mods do) - Shahed and ICBM are again in the inventory, there is no nuclear strike. launch, which crashed with error
     *  (here is a handler for the same event), was also unsuccessful: Shahed returns. ticket chunks taken by the projectile before entering
     *  into the world, those who did not enter are released in both cases.
     */
    @GameTest(template = "runway", timeoutTicks = 20, batch = "munitions_failed", skyAccess = true)
    public static void failedLaunchRefunds(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        boolean opsOnly = IncomingConfig.SERVER.nukeOpsOnly.get();
        Listener p = player(h, "munitions_failed");
        p.moveTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 4, 60))), 0, 0);
        int[] vetoed = {0};
        boolean[] crash = {false};
        List<UUID> tried = new ArrayList<>();
        int[] heldOnEntry = {0};
        Consumer<EntityJoinLevelEvent> veto = e -> {
            if (e.getEntity() instanceof StrikeProjectile proj && p.getUUID().equals(proj.ownerId())) {
                vetoed[0]++;
                tried.add(proj.getUUID());
                if (ChunkTickets.held(level, proj.getUUID()) > 0) heldOnEntry[0]++;
                if (crash[0]) throw new IllegalStateException("check: launch crashes with an error");
                e.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(veto);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(veto);
            IncomingConfig.SERVER.nukeOpsOnly.set(opsOnly);
            removeFlights(level, p.getUUID());
            level.getEntitiesOfClass(LauncherEntity.class, h.getBounds().inflate(64)).forEach(Entity::discard);
            for (NuclearEvents.ScheduledStrike s : List.copyOf(NuclearEvents.get(level).scheduled())) {
                if (s.owner().map(p.getUUID()::equals).orElse(false)) NuclearEvents.get(level).unschedule(s);
            }
        });
        Vec3 point = Vec3.atCenterOf(h.absolutePos(new BlockPos(16, 3, 200))).add(0, 0.5, 0);
        ServerActions.Aim aim = new ServerActions.Aim(new Target.Point(point), point, null);

        Item shahed = ModItems.SHAHED.get();
        give(p, shahed, 1);
        h.assertFalse(ServerActions.strike(p, WeaponType.DRONE, 1, 0, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, true), "launch without entering the world was a success");
        h.assertValueEqual(vetoed[0], 1, "shahed did not try to enter the world at the launcher");
        h.assertValueEqual(held(p, shahed), 1, "shahed after a failed launch");
        h.assertTrue(p.last("incoming.launch_failed") != null && p.last("incoming.munitions.refunded") != null, "no lines about failure and return");

        IncomingConfig.SERVER.nukeOpsOnly.set(false);
        Item icbm = ModItems.ICBM.get();
        give(p, icbm, 1);
        int scheduled = NuclearEvents.get(level).scheduled().size();
        h.assertFalse(ServerActions.strike(p, WeaponType.NUKE, 1, 0, aim, new Loadout.Nuke(15, true), Waypoints.NONE, null, true), "launch ICBM without entering the world was a success");
        h.assertValueEqual(vetoed[0], 2, "ICBM did not try to enter the world");
        h.assertValueEqual(held(p, icbm), 1, "ICBM after unsuccessful launch");
        h.assertValueEqual(NuclearEvents.get(level).scheduled().size(), scheduled, "failed launch recorded nuclear strike");

        crash[0] = true;
        p.said.clear();
        h.assertFalse(ServerActions.strike(p, WeaponType.DRONE, 1, 0, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, true), "launch failed with an error");
        h.assertValueEqual(vetoed[0], 3, "shahed didn't try to enter the world");
        h.assertValueEqual(held(p, shahed), 1, "shahed after launch with an error");
        h.assertTrue(p.last("incoming.munitions.refunded") != null, "no return line after error");
        // the projectile takes the ticket of its chunks before entering the world (both Shahed; ICBM - no): the one who did not enter lets them go, and the one who fell also
        h.assertValueEqual(heldOnEntry[0], 2, "shahed with ticket chunks upon entering the world");
        for (UUID id : tried) h.assertValueEqual(ChunkTickets.held(level, id), 0, "ticket chunks of a projectile not included in the world " + id);
        h.succeed();
    }

    /**
     * The operator's nuclear missile from a distant launch site awaits him with a salvo of one projectile ({@link LaunchPlaces}): the salvo of
     *  carries its nuclear warhead (salvos of several projectiles do not have one); It’s not the nuclear shutdown that doesn’t remove it, but the nuclear shutdown, while the space
     *  is loading, returns both the missile and the warhead - both are paid for in items.
     */
    @GameTest(template = "range", batch = "munitions_place", skyAccess = true)
    public static void nuclearMissileWaitingForPlaceRefundsWarhead(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        boolean carrier = IncomingConfig.SERVER.carrierNukes.get();
        IncomingConfig.SERVER.carrierNukes.set(true);
        Listener p = new Listener(level, "munitions_place", 2);
        p.moveTo(Vec3.atBottomCenterOf(h.absolutePos(RANGE_CENTER.above())));
        StrikeGameTests.afterTest(h, () -> {
            IncomingConfig.SERVER.carrierNukes.set(carrier);
            SalvoData.get(level).cancel(level, p.getUUID(), List.of());
        });
        Item missile = ModItems.CRUISE_MISSILE.get(), warhead = Munitions.warhead();
        give(p, missile, 1);
        give(p, warhead, 1);
        LaunchOrigin far = new LaunchOrigin.Place(p.position().add(-20_000, 0, 0));
        h.assertTrue(LaunchPlaces.waits(level, WeaponType.MISSILE, far), "the distant launch site is ready");
        h.assertTrue(ServerActions.strike(p, WeaponType.MISSILE, 1, 0, aim(h), new Loadout.Nuke(15, true, true), Waypoints.NONE, far, true),
                "nuclear missile from a distant launch site was not accepted");
        h.assertTrue(held(p, missile) == 0 && held(p, warhead) == 0, "payment: rockets " + held(p, missile) + ", warhead " + held(p, warhead));
        h.assertValueEqual(SalvoData.get(level).remaining(p.getUUID()), 1, "the rocket does not wait for a place in one gulp");
        CompoundTag saved = SalvoData.get(level).save(new CompoundTag(), level.registryAccess());
        h.assertTrue(saved.getList("salvos", Tag.TAG_COMPOUND).stream().map(t -> ((CompoundTag) t).getCompound("nuke"))
                .anyMatch(n -> n.getBoolean("on_carrier")), "a salvo from one missile lost its nuclear warhead: " + saved);
        // the player's release and the normal release of a nuclear salvo are not removed, just like a nuclear projectile
        h.assertValueEqual(SalvoData.get(level).cancel(level, p.getUUID(), List.of(p), false), 0, "not a nuclear release removed a nuclear salvo");
        h.assertValueEqual(SalvoData.get(level).cancel(level, p.getUUID(), List.of(p)), 1, "volleys canceled");
        h.assertTrue(held(p, missile) == 1 && held(p, warhead) == 1, "return: rockets " + held(p, missile) + ", warhead " + held(p, warhead));
        h.succeed();
    }

    /**
     * Ammo recipes are loaded - one per item: with Create - with its exact mechanism and screw, without it -
     *  vanilla replacement (conditions NeoForge {@code neoforge:mod_loaded}); each one is assembled from its own ingredients on a workbench.
     */
    @GameTest(template = "range", batch = "munitions")
    public static void munitionRecipesLoad(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var recipes = level.getServer().getRecipeManager();
        boolean create = ModList.get().isLoaded("create");
        // item → does the recipe have an option without Create
        Map<Item, Boolean> munitions = Map.of(ModItems.SHAHED.get(), true, ModItems.LANCET.get(), true, ModItems.CRUISE_MISSILE.get(), true,
                ModItems.GRAD_ROCKETS.get(), false, ModItems.BUNKER_BUSTER.get(), false, ModItems.ICBM.get(), true, ModItems.NUCLEAR_WARHEAD.get(), false);
        for (WeaponType w : WeaponType.values()) h.assertTrue(munitions.containsKey(w.spec().munition().item().get()), "ammunition " + w + " without checking a prescription");
        for (Map.Entry<Item, Boolean> e : munitions.entrySet()) {
            Item item = e.getKey();
            List<RecipeHolder<CraftingRecipe>> found = new ArrayList<>();
            for (RecipeHolder<CraftingRecipe> r : recipes.getAllRecipesFor(RecipeType.CRAFTING)) {
                if (r.value().getResultItem(level.registryAccess()).is(item)) found.add(r);
            }
            String name = BuiltInRegistries.ITEM.getKey(item).getPath();
            ResourceLocation expected = Incoming.id(e.getValue() && !create ? name + "_vanilla" : name);
            h.assertTrue(found.size() == 1 && found.getFirst().id().equals(expected), name + ": recipes " + found.stream().map(RecipeHolder::id).toList()
                    + " instead of " + expected);
            if (!(found.getFirst().value() instanceof ShapedRecipe shaped)) throw new GameTestAssertException(name + ": the recipe is out of shape");
            List<ItemStack> grid = new ArrayList<>();
            for (Ingredient i : shaped.getIngredients()) {
                if (!i.isEmpty() && i.hasNoItems()) throw new GameTestAssertException(name + ": ingredient without items");
                grid.add(i.isEmpty() ? ItemStack.EMPTY : i.getItems()[0].copy());
            }
            var crafted = recipes.getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(shaped.getWidth(), shaped.getHeight(), grid), level);
            h.assertTrue(crafted.isPresent() && crafted.get().id().equals(expected), name + ": the workbench does not collect its recipe: " + crafted.map(RecipeHolder::id));
        }
        h.succeed();
    }

    // ---------------------------------------------------------------- assistants

    /** {@code FakePlayer}, which remembers lines above the hotbar and in chat (NeoForge mutes them). */
    static final class Listener extends FakePlayer {
        final List<Component> said = new ArrayList<>();
        /** Rights (operator - 2). */
        private final int permission;

        Listener(ServerLevel level, String name) {
            this(level, name, 0);
        }

        Listener(ServerLevel level, String name, int permission) {
            super(level, new GameProfile(UUID.randomUUID(), name));
            this.permission = permission;
        }

        @Override
        protected int getPermissionLevel() {
            return permission;
        }

        @Override
        public void displayClientMessage(Component message, boolean actionBar) {
            said.add(message);
        }

        @Override
        public void sendSystemMessage(Component message, boolean bypassHiddenChat) {
            said.add(message);
        }

        /** The last line with the translation key is {@code key}, or null. */
        @Nullable
        TranslatableContents last(String key) {
            for (int i = said.size() - 1; i >= 0; i--) {
                if (said.get(i).getContents() instanceof TranslatableContents t && t.getKey().equals(key)) return t;
            }
            return null;
        }
    }

    static Listener player(GameTestHelper h, String name) {
        Listener p = new Listener(h.getLevel(), name);
        p.moveTo(Vec3.atBottomCenterOf(h.absolutePos(RANGE_CENTER.above())));
        return p;
    }

    /**
     * The target is the middle of the site (its area is already loaded: shells do not load fresh areas); the shooter is not on the list of
     *  players, so the released Shahed come from afar, 2 km, and are removed long before approaching.
     */
    private static ServerActions.Aim aim(GameTestHelper h) {
        Vec3 point = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 0.5, 0);
        return new ServerActions.Aim(new Target.Point(point), point, null);
    }

    static void give(FakePlayer p, Item item, int n) {
        p.getInventory().placeItemBackInInventory(new ItemStack(item, n));
    }

    /** Shells in inventory (for a package - in packages). */
    static int held(FakePlayer p, Item item) {
        return Munitions.held(Munitions.of(p).items(), item, Munitions.perItem(item));
    }

    private static int count(FakePlayer p, Item item) {
        return p.getInventory().countItem(item);
    }

    private static ItemStack find(FakePlayer p, Item item) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            if (p.getInventory().getItem(i).is(item)) return p.getInventory().getItem(i);
        }
        return ItemStack.EMPTY;
    }

    /** String arguments: components as is, numbers as numbers. */
    @Nullable
    static List<Object> args(@Nullable TranslatableContents t) {
        return t == null ? null : List.of(t.getArgs());
    }

    /** The owner's shells - out of the world and in the world - can be removed without exploding. */
    static void removeFlights(ServerLevel level, UUID owner) {
        VirtualFlights.get(level).clear(level, f -> owner.equals(f.ownerId()));
        for (StrikeProjectile e : level.getEntities(EntityTypeTest.forClass(StrikeProjectile.class), f -> owner.equals(f.ownerId()))) {
            e.discard();
        }
    }
}
