package ua.zentix.incoming.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.Keys;
import ua.zentix.incoming.client.aim.Designator;
import ua.zentix.incoming.client.screen.RemoteScreen;
import ua.zentix.incoming.registry.ModCreativeTabs;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.item.DesignatorItem;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.registry.ModDataComponents;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.ServerActions;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.Optional;
import java.util.OptionalInt;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A brand-new player's first strike, without the guide ({@code tools/client_scenario.sh newbie}): a survival world
 * without cheats (the player is not an operator), recipe book, first hold of the designator, its tooltip, binoculars,
 * launch without and with munitions, the sky, the console and its settings, the ICBM, the targeting map, then the
 * creative tab; the same path again in Russian. Every step leaves a frame {@code newbie-<lang>-<step>.png} and a
 * {@code NEWBIE} log line with what the player was told (chat, action bar, tooltip, recipe book), so the walkthrough
 * before and after a UX change compares frame by frame. Steps are client actions, as the key handlers perform them
 * (no window: keys cannot be pressed).
 */
final class NewbieWalk {
    private static final String WORLD = "incoming-newbie-scenario";

    private final Minecraft mc = Minecraft.getInstance();
    private final ArrayDeque<Step> steps = new ArrayDeque<>();
    private int stepTick, frames;
    private boolean started;
    private String lang = "en";
    private Vec3 ground;

    private interface Step {
        boolean done(int t);
    }

    NewbieWalk() {
        NeoForge.EVENT_BUS.addListener(this::onTitle);
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre e) -> frames++);
        NeoForge.EVENT_BUS.addListener(this::onChat);
        plan();
    }

    /** Survival, no cheats: a new player's world; the scenario acts on the integrated server directly, not by command. */
    private void onTitle(ScreenEvent.Init.Post e) {
        if (started || !(e.getScreen() instanceof TitleScreen)) return;
        started = true;
        // A fresh world directory each run, next to whatever saves exist: nothing is ever deleted.
        String world = WORLD + "-" + System.currentTimeMillis();
        // The view distance is sent at login: targets 280 blocks out must be in the chunks the server delivers.
        mc.options.renderDistance().set(20);
        mc.options.save();
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        LevelSettings settings = new LevelSettings(world, GameType.SURVIVAL, false, Difficulty.PEACEFUL, false, rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(20260927L, true, false),
                WorldPresets::createNormalWorldDimensions, e.getScreen());
    }

    private void onChat(ClientChatReceivedEvent e) {
        boolean overlay = e instanceof ClientChatReceivedEvent.System s && s.isOverlay();
        Incoming.LOG.info("NEWBIE {} {}: {}", lang, overlay ? "actionbar" : "chat", e.getMessage().getString());
    }

    // ---------------------------------------------------------------- the walk

    private void plan() {
        until("world", 6000, () -> mc.player != null && mc.level != null);
        run(() -> {
            // The run directory keeps options.txt, which may hold the second pass's Russian.
            if (!"en_us".equals(mc.options.languageCode)) {
                mc.options.languageCode = "en_us";
                mc.getLanguageManager().setSelected("en_us");
                mc.reloadResourcePacks();
            }
        });
        await(100);
        until("resources reloaded", 2400, () -> mc.getOverlay() == null);
        run(() -> onServer(server -> {
            ServerPlayer p = player(server);
            server.overworld().setDayTime(5000);
            // A glass platform 30 blocks above the ground: open sight to every target, out of every blast.
            int x = p.getBlockX(), z = p.getBlockZ();
            int y = server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 30;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
                server.overworld().setBlockAndUpdate(new BlockPos(x + dx, y - 1, z + dz), Blocks.GLASS.defaultBlockState());
            p.teleportTo(server.overworld(), x + 0.5, y, z + 0.5, 180, 0);
            Incoming.LOG.info("NEWBIE start: operator {}, game mode {}, platform {} {} {}", p.hasPermissions(2), p.gameMode.getGameModeForPlayer(), x, y, z);
        }));
        await(100);
        until("chunks drawn", 3600, () -> mc.levelRenderer.hasRenderedAllSections() && mc.levelRenderer.countRenderedSections() > 200);
        pass();
        run(() -> {
            lang = "ru";
            mc.options.languageCode = "ru_ru";
            mc.getLanguageManager().setSelected("ru_ru");
            mc.options.save();
            mc.reloadResourcePacks();
        });
        await(200);
        until("resources reloaded", 2400, () -> mc.getOverlay() == null);
        pass();
        run(() -> {
            Incoming.LOG.info("NEWBIE done");
            Incoming.LOG.info("SCENARIO done");
            mc.stop();
        });
    }

    /** One language: a non-operator's path, every weapon's first strike, every refusal we can cause alone, the operator's nuke. */
    private void pass() {
        basics();
        firstStrikes();
        refusals();
        operator();
        creative();
        // nothing of this pass still flying into the next one
        until("strikes over", 3600, () -> ua.zentix.incoming.client.hud.ClientFlights.all().isEmpty());
        run(() -> {
            if (mc.player.isDeadOrDying()) {
                Incoming.LOG.warn("NEWBIE {} the player died", lang);
                mc.player.respawn();
            }
            if (mc.screen != null) mc.setScreen(null);
        });
        await(40);
    }

    private void basics() {
        // Survival: what a player has when they look for a way to call a strike; the recipe book on a crafting table.
        run(() -> onServer(server -> {
            ServerPlayer p = player(server);
            p.getInventory().clearContent();
            p.setGameMode(GameType.SURVIVAL);
            p.getInventory().add(new ItemStack(Items.SPYGLASS));
            p.getInventory().add(new ItemStack(Items.IRON_INGOT, 2));
            p.getInventory().add(new ItemStack(Items.REDSTONE));
            p.getInventory().add(new ItemStack(Items.COMPASS));
        }));
        await(40);
        BlockPos[] table = new BlockPos[1];
        run(() -> {
            table[0] = mc.player.blockPosition().east();
            onServer(server -> server.overworld().setBlockAndUpdate(table[0], Blocks.CRAFTING_TABLE.defaultBlockState()));
        });
        await(10);
        run(() -> {
            mc.player.getRecipeBook().setOpen(RecipeBookType.CRAFTING, true);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(table[0]), Direction.UP, table[0], false));
        });
        until("crafting table", 200, () -> mc.screen instanceof CraftingScreen);
        await(10);
        run(() -> search(ModItems.DESIGNATOR.get().getDescription().getString()));
        shot("recipe-book", () -> "known " + mc.player.getRecipeBook().contains(ResourceLocation.fromNamespaceAndPath(Incoming.MOD_ID, "strike_designator"))
                + ", listed " + listed());
        run(() -> {
            mc.player.closeContainer();
            onServer(server -> server.overworld().removeBlock(table[0], false));
        });
        await(5);
        // The designator in hand.
        run(() -> onServer(server -> {
            ServerPlayer p = player(server);
            p.getInventory().clearContent();
            p.getInventory().setItem(0, new ItemStack(ModItems.DESIGNATOR.get()));
            p.getInventory().selected = 0;
            p.inventoryMenu.broadcastChanges();
        }));
        run(() -> {
            mc.setScreen(null);
            mc.player.getInventory().selected = 0;
            aimAt(280, 0);
        });
        await(60);
        shot("hold", () -> "");
        run(() -> mc.setScreen(new InventoryScreen(mc.player)));
        await(5);
        run(this::hoverHotbar0);
        shot("tooltip", () -> String.join(" | ", mc.player.getInventory().getItem(0)
                .getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.NORMAL).stream().map(Component::getString).toList()));
        run(() -> mc.setScreen(null));
        await(5);
        // Binoculars on a far target, then on the ground below: danger close.
        run(() -> mc.options.keyUse.setDown(true));
        await(30);
        shot("scope", this::overlay);
        run(() -> aimAt(40, 0));
        await(10);
        shot("danger-close", this::overlay);
        // LMB without munitions.
        run(() -> aimAt(280, 0));
        await(5);
        run(Designator::fire);
        await(8);
        shot("fire-empty", this::overlay);
        // Wheel: Grad and back.
        run(() -> Designator.scroll(-1));
        await(8);
        shot("scroll", this::overlay);
        run(() -> Designator.scroll(1));
        await(8);
        shot("scroll-back", this::overlay);
        // The sky.
        run(() -> mc.player.setXRot(-60));
        await(10);
        shot("sky", this::overlay);
        run(() -> mc.options.keyUse.setDown(false));
        await(10);
    }

    /** Each weapon's first strike from the binoculars, survival, non-operator, its munition crafted. */
    private void firstStrikes() {
        WeaponType[] order = {WeaponType.DRONE, WeaponType.LOITER, WeaponType.MISSILE, WeaponType.ROCKET, WeaponType.BUNKER};
        run(() -> onServer(server -> {
            ServerPlayer p = player(server);
            for (WeaponType w : order) p.getInventory().add(new ItemStack(w.spec().munition().item().get()));
        }));
        until("target chunks", 2400, () -> {
            for (int i = 0; i < order.length; i++) if (!targetLoaded(280, (i - 2) * 25)) return false;
            return true;
        });
        await(10);
        for (int i = 0; i < order.length; i++) {
            WeaponType w = order[i];
            double yaw = (i - 2) * 25;
            run(() -> {
                select(w, 1, 0);
                aimAt(280, yaw);
                mc.options.keyUse.setDown(true);
            });
            await(20);
            run(Designator::fire);
            await(12);
            shot("first-" + w.getSerializedName(), this::overlay);
            run(() -> mc.options.keyUse.setDown(false));
            await(40);
        }
        run(() -> aimAt(280, 0));
        await(200);
        shot("first-in-flight", this::overlay);
        until("first strikes land", 3600, () -> ua.zentix.incoming.client.hud.ClientFlights.all().isEmpty());
        await(40);
        shot("first-landed", () -> "dead " + mc.player.isDeadOrDying());
    }

    /** Refusals a player meets alone: console states, settings, permissions and limits (server settings set as an owner would). */
    private void refusals() {
        // Console: the default order, a player target with nobody picked, the settings.
        run(() -> {
            select(WeaponType.MISSILE, 1, 0);
            aimAt(280, 0);
        });
        openConsole();
        shot("console", () -> buttons());
        run(() -> click(widgetWith(ua.zentix.incoming.strike.TargetMode.PLAYER.displayName().getString())));
        await(5);
        run(() -> hover(() -> widgetWith(Component.translatable("incoming.remote.fire").getString())));
        shot("console-player-fire", () -> "fire active " + (firstWidgetContaining(Component.translatable("incoming.remote.fire").getString()) instanceof AbstractWidget w && w.active));
        run(() -> click(widgetWith(ua.zentix.incoming.strike.TargetMode.LOOK.displayName().getString())));
        await(5);
        run(() -> click(widgetWith(Component.translatable("incoming.remote.settings").getString())));
        await(10);
        run(() -> mouse(5, 5));
        shot("settings", this::buttons);
        run(() -> {
            AbstractWidget server = firstWidgetContaining(Component.translatable("neoforge.configuration.uitext.type.server", "Incoming").getString(), "Server", "Сервер", "сервер");
            if (server != null && server.active) click(new double[]{server.getX() + 2, server.getY() + 2});
        });
        await(10);
        shot("settings-server", this::buttons);
        run(() -> mc.setScreen(null));
        await(5);
        // ICBM from the console as a non-operator.
        run(() -> onServer(server -> player(server).getInventory().add(new ItemStack(ModItems.ICBM.get()))));
        openConsole();
        run(() -> click(widgetWith(WeaponType.NUKE.displayName().getString())));
        await(5);
        run(() -> mouse(5, 5));
        shot("console-icbm", this::buttons);
        run(() -> click(widgetWith(Component.translatable("incoming.remote.fire").getString())));
        await(20);
        shot("refuse-nuke-ops-only", this::overlay);
        run(() -> {
            if (mc.screen != null) mc.setScreen(null);
        });
        // The world owner limited the designator to operators.
        refuse("refuse-designator-ops", () -> IncomingConfig.SERVER.designatorForEveryone.set(false), () -> IncomingConfig.SERVER.designatorForEveryone.set(true),
                () -> scopedFire(WeaponType.MISSILE));
        // One strike in progress at a time: the second order is refused while the first flies.
        run(() -> onServer(server -> player(server).getInventory().add(new ItemStack(ModItems.CRUISE_MISSILE.get(), 2))));
        refuse("refuse-too-many", () -> IncomingConfig.SERVER.maxActivePerPlayer.set(1), () -> IncomingConfig.SERVER.maxActivePerPlayer.set(0), () -> {
            scopedFire(WeaponType.MISSILE);
            await(20);
            run(Designator::fire);
        });
        // A map site beyond map_range.
        refuse("refuse-map-range", () -> IncomingConfig.SERVER.mapRange.set(256), () -> IncomingConfig.SERVER.mapRange.set(10_000), () -> run(() -> {
            Loadout l = DesignatorItem.loadout(mc.player.getMainHandItem()).withMode(ua.zentix.incoming.strike.TargetMode.MAP);
            PacketDistributor.sendToServer(new C2S.Fire(l, Optional.of(C2S.AimHint.ground(mc.player.getX(), mc.player.getZ() - 2000, OptionalInt.empty())),
                    Optional.empty(), ua.zentix.incoming.strike.Waypoints.NONE, Optional.empty()));
        }));
        until("refusal strikes land", 3600, () -> ua.zentix.incoming.client.hud.ClientFlights.all().isEmpty());
        // Targeting map.
        run(() -> net.minecraft.client.KeyMapping.click(Keys.MAP.getKey()));
        await(40);
        shot("map", () -> String.valueOf(mc.screen));
        run(() -> {
            if (mc.screen != null) mc.setScreen(null);
        });
        await(5);
    }

    /** The operator (the host with cheats, or /op): the nuclear review and launch, then the nuclear limits of the world. */
    private void operator() {
        run(() -> onServer(server -> {
            ServerPlayer p = player(server);
            server.getPlayerList().op(p.getGameProfile());
            p.getInventory().add(new ItemStack(ModItems.ICBM.get(), 3));
            p.getInventory().add(new ItemStack(ModItems.CRUISE_MISSILE.get(), 2));
            p.getInventory().add(new ItemStack(ModItems.NUCLEAR_WARHEAD.get(), 2));
        }));
        await(20);
        run(() -> {
            select(WeaponType.NUKE, 1, 0);
            aimAt(280, 0);
        });
        openConsole();
        // A yield the world does not allow: Fire waits for a valid one.
        run(() -> {
            EditBox yield = editBox();
            if (yield != null) yield.setValue("5000");
            hover(() -> widgetWith(Component.translatable("incoming.remote.fire").getString()));
        });
        shot("op-yield-invalid", () -> "fire active " + (firstWidgetContaining(Component.translatable("incoming.remote.fire").getString()) instanceof AbstractWidget w && w.active));
        run(() -> {
            EditBox yield = editBox();
            if (yield != null) yield.setValue("1");
            mouse(5, 5);
        });
        await(5);
        shot("op-console-icbm", this::buttons);
        run(() -> click(widgetWith(Component.translatable("incoming.remote.fire").getString())));
        until("nuclear review", 100, () -> mc.screen instanceof ua.zentix.incoming.client.screen.NuclearReviewScreen);
        shot("op-nuke-review", () -> String.valueOf(mc.screen));
        // Hold to launch: the screen polls the real key, which a windowless client cannot press; send what it sends.
        java.util.UUID[] review = new java.util.UUID[1];
        run(() -> {
            review[0] = reviewId();
            if (review[0] != null) PacketDistributor.sendToServer(new C2S.NuclearAction(review[0], C2S.NuclearAction.BEGIN));
        });
        await(ua.zentix.incoming.strike.NuclearReview.HOLD_TICKS + 5);
        run(() -> {
            if (review[0] != null) PacketDistributor.sendToServer(new C2S.NuclearAction(review[0], C2S.NuclearAction.COMMIT));
        });
        await(20);
        shot("op-nuke-launched", this::overlay);
        // Abort before it arrives (the platform is inside a 1 kt blast's reach).
        run(() -> onServer(server -> ServerActions.clearAll(server, true, "newbie scenario")));
        await(20);
        shot("op-nuke-aborted", this::overlay);
        // Nuclear limits of the world, each refused with its own reason.
        refuse("refuse-nuke-disabled", () -> IncomingConfig.SERVER.nukeEnabled.set(false), () -> IncomingConfig.SERVER.nukeEnabled.set(true),
                () -> reviewNuclear(new Loadout(WeaponType.NUKE, 1, 0, ua.zentix.incoming.strike.TargetMode.LOOK, "", new Loadout.Nuke(1, false, false))));
        // The console sends its order at once (Fire), before the server saves the designator's settings: as here.
        refuse("refuse-nuke-carrier", () -> IncomingConfig.SERVER.carrierNukes.set(false), () -> IncomingConfig.SERVER.carrierNukes.set(true),
                () -> reviewNuclear(new Loadout(WeaponType.MISSILE, 1, 0, ua.zentix.incoming.strike.TargetMode.LOOK, "", new Loadout.Nuke(1, false, true))));
        refuse("refuse-nuke-cap", () -> IncomingConfig.SERVER.nukeMaxYield.set(10), () -> IncomingConfig.SERVER.nukeMaxYield.set(Loadout.Nuke.DEFAULT_MAX_YIELD), () -> reviewNuclear(new Loadout(WeaponType.NUKE, 1, 0, ua.zentix.incoming.strike.TargetMode.LOOK, "", new Loadout.Nuke(15, false, false))));
        run(() -> {
            if (mc.screen != null) mc.setScreen(null);
            setLoadout(Loadout.DEFAULT);
            onServer(server -> {
                server.getPlayerList().deop(player(server).getGameProfile());
                ServerActions.clearAll(server, true, "newbie scenario");
            });
        });
        await(20);
    }

    private void creative() {
        run(() -> onServer(server -> player(server).setGameMode(GameType.CREATIVE)));
        await(10);
        run(() -> mc.setScreen(new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), false)));
        await(5);
        run(this::selectIncomingTab);
        run(() -> mouse(5, 5));
        shot("creative-tab", () -> "");
        run(() -> mc.setScreen(null));
        await(5);
        run(() -> onServer(server -> player(server).setGameMode(GameType.SURVIVAL)));
        await(10);
    }

    // ---------------------------------------------------------------- actions

    /** A server setting as the world owner sets it, the action, the frame of what the player was told, the setting back. */
    private void refuse(String name, Runnable set, Runnable restore, Runnable action) {
        run(() -> onServer(server -> set.run()));
        await(5);
        action.run();
        await(10);
        shot(name, this::overlay);
        run(() -> {
            mc.options.keyUse.setDown(false);
            if (mc.screen != null) mc.setScreen(null);
            onServer(server -> restore.run());
        });
        await(10);
    }

    /** Binoculars on the far target with {@code w} (null: the loadout as set) and LMB. */
    private void scopedFire(WeaponType w) {
        run(() -> {
            if (w != null) select(w, 1, 0);
            aimAt(280, 0);
            mc.options.keyUse.setDown(true);
        });
        await(15);
        run(Designator::fire);
    }

    private void openConsole() {
        run(() -> mc.options.keyShift.setDown(true));
        await(3);
        run(() -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND));
        await(3);
        run(() -> mc.options.keyShift.setDown(false));
        until("console", 100, () -> mc.screen instanceof RemoteScreen);
        run(() -> mouse(5, 5));
    }

    /** The designator's weapon, as the wheel or the console sets it (with the console's count and spread). */
    private void select(WeaponType w, int count, int spread) {
        Loadout l = DesignatorItem.loadout(mc.player.getMainHandItem());
        setLoadout(new Loadout(w, count, spread, ua.zentix.incoming.strike.TargetMode.LOOK, "", l.nuke()).withWeapon(w));
    }

    private void setLoadout(Loadout l) {
        mc.player.getMainHandItem().set(ModDataComponents.LOADOUT.get(), l);
        PacketDistributor.sendToServer(new C2S.SetLoadout(InteractionHand.MAIN_HAND, l));
    }

    /** The console's Fire for a nuclear order at the far target: the review request, as {@code RemoteScreen.fire} sends it. */
    private void reviewNuclear(Loadout l) {
        run(() -> {
            aimAt(280, 0);
            C2S.AimHint hint = new C2S.AimHint(C2S.AimHint.POINT, ground, 0, Vec3.ZERO);
            PacketDistributor.sendToServer(new C2S.ReviewNuclear(new C2S.Fire(l, Optional.of(hint), Optional.empty(), ua.zentix.incoming.strike.Waypoints.NONE, Optional.empty())));
        });
    }

    private java.util.UUID reviewId() {
        if (!(mc.screen instanceof ua.zentix.incoming.client.screen.NuclearReviewScreen s)) return null;
        try {
            Field f = s.getClass().getDeclaredField("review");
            f.setAccessible(true);
            return ((ua.zentix.incoming.net.S2C.NuclearReview) f.get(s)).id();
        } catch (ReflectiveOperationException ex) {
            Incoming.LOG.warn("NEWBIE review id", ex);
            return null;
        }
    }

    private boolean targetLoaded(double distance, double yaw) {
        Vec3 eye = mc.player.getEyePosition();
        double a = Math.toRadians(yaw);
        int x = (int) Math.floor(eye.x + Math.sin(a) * distance), z = (int) Math.floor(eye.z - Math.cos(a) * distance);
        return mc.level.getChunkSource().getChunk(x >> 4, z >> 4, false) != null;
    }

    private EditBox editBox() {
        if (mc.screen == null) return null;
        for (var c : mc.screen.children()) if (c instanceof EditBox b) return b;
        return null;
    }

    /** Look from the platform at the ground {@code distance} blocks away, {@code yaw} degrees off north. */
    private void aimAt(double distance, double yaw) {
        Vec3 eye = mc.player.getEyePosition();
        double a = Math.toRadians(yaw);
        int x = (int) Math.floor(eye.x + Math.sin(a) * distance), z = (int) Math.floor(eye.z - Math.cos(a) * distance);
        if (mc.level.getChunkSource().getChunk(x >> 4, z >> 4, false) == null) Incoming.LOG.warn("NEWBIE target chunk {} {} not loaded", x, z);
        ground = new Vec3(x + 0.5, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z + 0.5);
        Vec3 d = ground.subtract(eye);
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
    }

    /** Recipe collections the open book shows for the current search. */
    private int listed() {
        if (!(mc.screen instanceof CraftingScreen s)) return -1;
        try {
            RecipeBookComponent book = s.getRecipeBookComponent();
            Field f = RecipeBookComponent.class.getDeclaredField("recipeBookPage");
            f.setAccessible(true);
            Object page = f.get(book);
            Field c = page.getClass().getDeclaredField("recipeCollections");
            c.setAccessible(true);
            return ((List<?>) c.get(page)).size();
        } catch (ReflectiveOperationException ex) {
            return -2;
        }
    }

    private void search(String text) {
        if (!(mc.screen instanceof CraftingScreen s)) return;
        try {
            RecipeBookComponent book = s.getRecipeBookComponent();
            Field f = RecipeBookComponent.class.getDeclaredField("searchBox");
            f.setAccessible(true);
            EditBox box = (EditBox) f.get(book);
            if (box == null) return;
            box.setValue(text);
            Method m = RecipeBookComponent.class.getDeclaredMethod("checkSearchStringUpdate");
            m.setAccessible(true);
            m.invoke(book);
        } catch (ReflectiveOperationException ex) {
            Incoming.LOG.warn("NEWBIE recipe search failed", ex);
        }
    }

    private void hoverHotbar0() {
        if (!(mc.screen instanceof InventoryScreen s)) return;
        var slot = s.getMenu().getSlot(36);
        mouse(s.getGuiLeft() + slot.x + 8, s.getGuiTop() + slot.y + 8);
    }

    private void selectIncomingTab() {
        if (!(mc.screen instanceof CreativeModeInventoryScreen s)) return;
        try {
            Method m = CreativeModeInventoryScreen.class.getDeclaredMethod("selectTab", CreativeModeTab.class);
            m.setAccessible(true);
            m.invoke(s, ModCreativeTabs.MAIN.get());
        } catch (ReflectiveOperationException ex) {
            Incoming.LOG.warn("NEWBIE creative tab failed", ex);
        }
    }

    private double[] widgetWith(String text) {
        AbstractWidget w = firstWidgetContaining(text);
        return w == null ? new double[]{5, 5} : new double[]{w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0};
    }

    private AbstractWidget firstWidgetContaining(String... texts) {
        AbstractWidget w = mc.screen == null ? null : find(mc.screen.children(), texts);
        if (w == null) Incoming.LOG.warn("NEWBIE no widget with {}", String.join("/", texts));
        return w;
    }

    /** Depth-first, into option lists and their rows. */
    private static AbstractWidget find(List<? extends GuiEventListener> children, String... texts) {
        for (var c : children) {
            if (c instanceof AbstractWidget w) {
                String m = w.getMessage().getString();
                for (String t : texts) if (m.contains(t)) return w;
            }
            if (c instanceof ContainerEventHandler box) {
                AbstractWidget in = find(box.children(), texts);
                if (in != null) return in;
            }
        }
        return null;
    }

    private String buttons() {
        if (mc.screen == null) return "";
        StringBuilder b = new StringBuilder();
        for (var c : mc.screen.children()) {
            if (c instanceof AbstractWidget w) b.append('[').append(w.getMessage().getString()).append(w.active ? "" : " (off)").append(']');
        }
        return b.toString();
    }

    private void hover(Supplier<double[]> at) {
        double[] p = at.get();
        mouse(p[0], p[1]);
    }

    private void click(double[] at) {
        mouse(at[0], at[1]);
        Screen s = mc.screen;
        if (s == null) return;
        s.mouseClicked(at[0], at[1], 0);
        if (mc.screen == s) s.mouseReleased(at[0], at[1], 0);
    }

    private void mouse(double guiX, double guiY) {
        var w = mc.getWindow();
        try {
            Field x = MouseHandler.class.getDeclaredField("xpos"), y = MouseHandler.class.getDeclaredField("ypos");
            x.setAccessible(true);
            y.setAccessible(true);
            x.setDouble(mc.mouseHandler, guiX * w.getScreenWidth() / w.getGuiScaledWidth());
            y.setDouble(mc.mouseHandler, guiY * w.getScreenHeight() / w.getGuiScaledHeight());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Action-bar text now on screen. */
    private String overlay() {
        try {
            Field f = Gui.class.getDeclaredField("overlayMessageString");
            f.setAccessible(true);
            Object c = f.get(mc.gui);
            Field t = Gui.class.getDeclaredField("overlayMessageTime");
            t.setAccessible(true);
            return c instanceof Component m && t.getInt(mc.gui) > 0 ? "actionbar: " + m.getString() : "actionbar: —";
        } catch (ReflectiveOperationException ex) {
            return "actionbar: ?";
        }
    }

    // ---------------------------------------------------------------- steps

    private void onTick(ClientTickEvent.Post e) {
        while (!steps.isEmpty()) {
            if (!steps.peek().done(stepTick++)) return;
            steps.poll();
            stepTick = 0;
        }
    }

    private void run(Runnable r) {
        steps.add(t -> {
            if (mc.player == null) return false;
            r.run();
            return true;
        });
    }

    private void await(int ticks) {
        steps.add(t -> t >= ticks);
    }

    private void until(String what, int max, BooleanSupplier ok) {
        steps.add(t -> {
            if (ok.getAsBoolean()) return true;
            if (t < max) return false;
            Incoming.LOG.warn("NEWBIE did not wait for {} in {} ticks", what, max);
            return true;
        });
    }

    /** Three rendered frames after a change, so the shot shows it. */
    private void settle() {
        int[] start = new int[1];
        run(() -> start[0] = frames);
        steps.add(t -> frames >= start[0] + 3);
    }

    private void shot(String name, Supplier<String> note) {
        settle();
        run(() -> {
            String file = "newbie-" + lang + "-" + name;
            Screenshot.grab(mc.gameDirectory, file + ".png", mc.getMainRenderTarget(), c -> {});
            Incoming.LOG.info("NEWBIE shot {} — {}", file, note.get());
        });
    }

    private void onServer(Consumer<MinecraftServer> r) {
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> r.accept(server));
    }

    private ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayer(mc.player.getUUID());
    }
}
