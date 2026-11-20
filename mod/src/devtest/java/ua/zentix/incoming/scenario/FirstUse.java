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
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.aim.Designator;
import ua.zentix.incoming.client.screen.RemoteScreen;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.TargetMode;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.registry.ModDataComponents;
import ua.zentix.incoming.net.C2S;
import net.neoforged.neoforge.network.PacketDistributor;
import ua.zentix.incoming.strike.WeaponType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What a new player meets before the first strike ({@code tools/client_scenario.sh firstuse|firstuse-ru}), as GUI
 * frames on the CPU: survival on a flat world without cheats, the host alone; no strike is launched. One language per
 * launch, set in options.txt by the script before the client starts (the recipe-book search index is built in it; a
 * language reload after a world outgrows the CPU client's memory ceiling); a fresh world and fresh first-use hints, so
 * the recipe unlock and the toast happen as for a new player. Frames {@code firstuse-<lang>-<step>.png}, each with a
 * {@code FIRSTUSE shot} log line holding the state behind it (recipe known and listed, tooltip lines, Fire's state,
 * the action bar), and every chat/action-bar line as {@code FIRSTUSE <lang> chat|actionbar}. A state the walk cannot
 * reach is logged {@code FIRSTUSE FAIL}.
 */
final class FirstUse {
    private static final String WORLD = "incoming-firstuse";

    private final Minecraft mc = Minecraft.getInstance();
    private final ArrayDeque<Step> steps = new ArrayDeque<>();
    private int stepTick, frames, failures;

    private interface Step {
        boolean done(int t);
    }

    FirstUse() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre e) -> frames++);
        NeoForge.EVENT_BUS.addListener((ClientChatReceivedEvent e) -> {
            boolean overlay = e instanceof ClientChatReceivedEvent.System s && s.isOverlay();
            Incoming.LOG.info("FIRSTUSE {} {}: {}", lang(), overlay ? "actionbar" : "chat", e.getMessage().getString());
        });
        start();
    }

    private String lang() {
        return mc.options.languageCode.substring(0, 2);
    }

    // ---------------------------------------------------------------- the walk

    private void start() {
        until("title screen", 6000, () -> mc.screen instanceof TitleScreen && mc.getOverlay() == null, true);
        any(() -> {
            // a new player: no hint shown yet on this client
            IncomingConfig.CLIENT.shownHints.set(List.of());
            createWorld();
        });
        until("world", 6000, () -> mc.player != null && mc.level != null, true);
        await(40);
        until("chunks drawn", 2400, () -> mc.levelRenderer.hasRenderedAllSections() && mc.levelRenderer.countRenderedSections() > 50, false);
        walk();
        any(() -> {
            Incoming.LOG.info("FIRSTUSE {} done, {} failures", lang(), failures);
            Incoming.LOG.info("SCENARIO done");
            mc.stop();
        });
    }

    /** Survival without cheats; a fresh directory each run beside whatever saves exist, nothing deleted. */
    private void createWorld() {
        String name = WORLD + "-" + mc.options.languageCode + "-" + System.currentTimeMillis();
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        LevelSettings settings = new LevelSettings(name, GameType.SURVIVAL, false, Difficulty.PEACEFUL, false, rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(20261120L, false, false), registries -> {
            var biomes = registries.lookupOrThrow(Registries.BIOME);
            FlatLevelGeneratorSettings flat = FlatLevelGeneratorSettings.getDefault(biomes, registries.lookupOrThrow(Registries.STRUCTURE_SET),
                    registries.lookupOrThrow(Registries.PLACED_FEATURE)).withBiomeAndLayers(List.of(new FlatLayerInfo(1, Blocks.BEDROCK),
                    new FlatLayerInfo(3, Blocks.STONE), new FlatLayerInfo(2, Blocks.DIRT), new FlatLayerInfo(1, Blocks.GRASS_BLOCK)),
                    Optional.empty(), biomes.getOrThrow(Biomes.PLAINS));
            WorldDimensions dims = WorldPresets.createNormalWorldDimensions(registries);
            return dims.replaceOverworldGenerator(registries, new FlatLevelSource(flat));
        }, mc.screen);
    }

    private void walk() {
        run(() -> onServer(server -> {
            server.overworld().setDayTime(6000);
            ServerPlayer p = player(server);
            Incoming.LOG.info("FIRSTUSE {} start: operator {}, host {}, game mode {}", lang(), p.hasPermissions(2),
                    server.isSingleplayerOwner(p.getGameProfile()), p.gameMode.getGameModeForPlayer());
        }));
        // 1. Recipe book on a crafting table, after picking up the designator's ingredients.
        run(() -> onServer(server -> {
            ServerPlayer p = player(server);
            p.getInventory().clearContent();
            p.getInventory().add(new ItemStack(Items.SPYGLASS));
            p.getInventory().add(new ItemStack(Items.IRON_INGOT, 2));
            p.getInventory().add(new ItemStack(Items.REDSTONE));
            p.getInventory().add(new ItemStack(Items.COMPASS));
        }));
        ResourceLocation recipe = ResourceLocation.fromNamespaceAndPath(Incoming.MOD_ID, "strike_designator");
        until("designator recipe unlocked", 200, () -> mc.player.getRecipeBook().contains(recipe), false);
        BlockPos[] table = new BlockPos[1];
        run(() -> {
            table[0] = mc.player.blockPosition().south(2);
            mc.player.setYRot(0);
            mc.player.setXRot(30);
            onServer(server -> server.overworld().setBlockAndUpdate(table[0], Blocks.CRAFTING_TABLE.defaultBlockState()));
        });
        await(10);
        run(() -> {
            mc.player.getRecipeBook().setOpen(RecipeBookType.CRAFTING, true);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(table[0]), Direction.UP, table[0], false));
        });
        until("crafting table", 200, () -> mc.screen instanceof CraftingScreen, true);
        // the search index is built off-thread: search again until the book lists the recipe
        until("recipe found by its " + "name", 400, () -> {
            search(ModItems.DESIGNATOR.get().getDescription().getString());
            return listed() > 0;
        }, true);
        run(() -> mouse(5, 5));
        shot("recipe-book", () -> "search \"" + ModItems.DESIGNATOR.get().getDescription().getString() + "\", known "
                + mc.player.getRecipeBook().contains(recipe) + ", listed " + listed());
        run(() -> {
            mc.player.closeContainer();
            onServer(server -> server.overworld().removeBlock(table[0], false));
        });
        await(5);
        // 2. First hold: the toast, and the tooltip in the inventory while the toast is still up.
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
            mc.player.setXRot(10);
        });
        until("designator in hand", 100, () -> mc.player.getMainHandItem().is(ModItems.DESIGNATOR.get()), true);
        await(40);
        run(() -> mc.setScreen(new InventoryScreen(mc.player)));
        await(5);
        run(this::hoverHotbar0);
        shot("hold", () -> "toast " + hintToast() + ", tooltip: " + String.join(" | ", mc.player.getInventory().getItem(0)
                .getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.NORMAL).stream().map(Component::getString).toList()));
        run(() -> mc.setScreen(null));
        await(5);
        // 3. Console, target "a player" with nobody picked: Fire cannot send the order.
        openConsole();
        run(() -> click(widgetWith(TargetMode.PLAYER.displayName().getString())));
        await(5);
        run(() -> hover(() -> widgetWith(Component.translatable("incoming.remote.fire").getString())));
        shot("fire-incomplete", () -> "fire active " + fireActive() + ", " + buttons());
        // 4. Back to "where I look", the default missile, no munitions: the console's munitions line, then Fire.
        run(() -> click(widgetWith(TargetMode.LOOK.displayName().getString())));
        await(5);
        run(() -> hover(() -> widgetWith(Component.translatable("incoming.remote.fire").getString())));
        shot("console", () -> "fire active " + fireActive() + ", " + buttons());
        run(() -> click(widgetWith(Component.translatable("incoming.remote.fire").getString())));
        await(10);
        shot("fire-refused", this::overlay);
        // 5. The host's ICBM: the nuclear review opens, then the same order in a world with nuclear weapons off.
        run(() -> {
            if (mc.screen != null) mc.setScreen(null);
            onServer(server -> player(server).getInventory().add(new ItemStack(ModItems.ICBM.get())));
        });
        openConsole();
        run(() -> click(widgetWith(WeaponType.NUKE.displayName().getString())));
        await(5);
        run(() -> click(widgetWith(Component.translatable("incoming.remote.fire").getString())));
        await(20);
        shot("nuke-host", () -> "screen " + (mc.screen == null ? "none" : mc.screen.getClass().getSimpleName()) + ", " + overlay());
        run(() -> {
            if (mc.screen != null) mc.screen.onClose();
            if (mc.screen != null) mc.setScreen(null);
            onServer(server -> IncomingConfig.SERVER.nukeEnabled.set(false));
        });
        await(10);
        openConsole();
        run(() -> click(widgetWith(WeaponType.NUKE.displayName().getString())));
        await(5);
        run(() -> click(widgetWith(Component.translatable("incoming.remote.fire").getString())));
        await(10);
        shot("nuke-off", () -> "screen " + (mc.screen == null ? "none" : mc.screen.getClass().getSimpleName()) + ", " + overlay());
        run(() -> {
            if (mc.screen != null) mc.setScreen(null);
            onServer(server -> IncomingConfig.SERVER.nukeEnabled.set(true));
        });
        await(10);
        // 6. Binoculars at the sky above the horizon (the ray ends past the loaded chunks, nothing under it), LMB, with the default missile (a nuclear order would open its review instead).
        run(() -> {
            Loadout l = Loadout.DEFAULT;
            mc.player.getMainHandItem().set(ModDataComponents.LOADOUT.get(), l);
            PacketDistributor.sendToServer(new C2S.SetLoadout(InteractionHand.MAIN_HAND, l));
        });
        // the mouse positions the walk set while screens were open turn the camera once the mouse is grabbed again:
        // let that happen, then aim
        await(10);
        run(() -> {
            mc.player.setXRot(-5);
            mc.options.keyUse.setDown(true);
        });
        await(20);
        run(() -> {
            mc.player.setXRot(-5);
            Incoming.LOG.info("FIRSTUSE {} scope at pitch {}, scoping {}", lang(), mc.player.getXRot(), Designator.isScoping());
        });
        await(5);
        run(Designator::fire);
        await(5);
        shot("scope-sky", this::overlay);
        run(() -> mc.options.keyUse.setDown(false));
        await(10);
    }

    // ---------------------------------------------------------------- actions

    private void openConsole() {
        run(() -> {
            mc.player.setXRot(20);
            mc.options.keyShift.setDown(true);
        });
        await(3);
        run(() -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND));
        await(3);
        run(() -> mc.options.keyShift.setDown(false));
        until("console", 100, () -> mc.screen instanceof RemoteScreen, true);
        run(() -> mouse(5, 5));
    }

    private String hintToast() {
        try {
            Field f = Class.forName("ua.zentix.incoming.client.hud.FirstUseHints").getDeclaredField("toast");
            f.setAccessible(true);
            return f.get(null) != null ? "shown" : "none";
        } catch (ReflectiveOperationException ex) {
            return "?";
        }
    }

    private String fireActive() {
        AbstractWidget w = firstWidgetContaining(Component.translatable("incoming.remote.fire").getString());
        return w == null ? "?" : String.valueOf(w.active);
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
            // checkSearchStringUpdate compares with the last search: forget it so each call searches again
            Field last = RecipeBookComponent.class.getDeclaredField("lastSearch");
            last.setAccessible(true);
            last.set(book, "");
            Method m = RecipeBookComponent.class.getDeclaredMethod("checkSearchStringUpdate");
            m.setAccessible(true);
            m.invoke(book);
        } catch (ReflectiveOperationException ex) {
            Incoming.LOG.warn("FIRSTUSE recipe search failed", ex);
        }
    }

    private void hoverHotbar0() {
        if (!(mc.screen instanceof InventoryScreen s)) return;
        var slot = s.getMenu().getSlot(36);
        mouse(s.getGuiLeft() + slot.x + 8, s.getGuiTop() + slot.y + 8);
    }

    private double[] widgetWith(String text) {
        AbstractWidget w = firstWidgetContaining(text);
        return w == null ? new double[]{5, 5} : new double[]{w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0};
    }

    private AbstractWidget firstWidgetContaining(String text) {
        AbstractWidget w = mc.screen == null ? null : find(mc.screen.children(), text, true);
        if (w == null && mc.screen != null) w = find(mc.screen.children(), text, false);
        if (w == null) Incoming.LOG.warn("FIRSTUSE {} no widget with {}", lang(), text);
        return w;
    }

    /** Depth-first, into option lists and their rows: the label equal to {@code text}, or containing it. */
    private static AbstractWidget find(List<? extends GuiEventListener> children, String text, boolean exact) {
        for (var c : children) {
            if (c instanceof AbstractWidget w && (exact ? w.getMessage().getString().equals(text) : w.getMessage().getString().contains(text))) return w;
            if (c instanceof ContainerEventHandler box) {
                AbstractWidget in = find(box.children(), text, exact);
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

    /** A step that needs the player in the world. */
    private void run(Runnable r) {
        steps.add(t -> {
            if (mc.player == null) return false;
            r.run();
            return true;
        });
    }

    /** A step that runs anywhere (title screen included). */
    private void any(Runnable r) {
        steps.add(t -> {
            r.run();
            return true;
        });
    }

    private void await(int ticks) {
        steps.add(t -> t >= ticks);
    }

    /** Wait for {@code ok}; on timeout a required state is a failure of the run, an optional one a warning. */
    private void until(String what, int max, BooleanSupplier ok, boolean required) {
        steps.add(t -> {
            if (ok.getAsBoolean()) return true;
            if (t < max) return false;
            if (required) failures++;
            Incoming.LOG.warn("FIRSTUSE {}{} did not reach {} in {} ticks", required ? "FAIL " : "", lang(), what, max);
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
            String file = "firstuse-" + lang() + "-" + name;
            Screenshot.grab(mc.gameDirectory, file + ".png", mc.getMainRenderTarget(), c -> {});
            Incoming.LOG.info("FIRSTUSE shot {} — {}", file, note.get());
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
