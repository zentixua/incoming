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
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
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

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

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
    /** Written into the scenario's own world: only a world carrying it is ever deleted (a player's save never is). */
    private static final String MARK = "incoming-newbie-scenario.mark";

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
        Path dir = mc.gameDirectory.toPath().resolve("saves").resolve(WORLD);
        if (Files.isDirectory(dir) && !Files.exists(dir.resolve(MARK))) {
            Incoming.LOG.error("NEWBIE saves/{} exists and is not the scenario's world: not touching it", WORLD);
            mc.stop();
            return;
        }
        if (Files.isDirectory(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ex) {
                Incoming.LOG.warn("NEWBIE old world not deleted", ex);
            }
        }
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        LevelSettings settings = new LevelSettings(WORLD, GameType.SURVIVAL, false, Difficulty.PEACEFUL, false, rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(20260927L, true, false),
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
            try {
                Files.writeString(mc.gameDirectory.toPath().resolve("saves").resolve(WORLD).resolve(MARK), "scenario world, safe to delete\n");
            } catch (IOException ex) {
                Incoming.LOG.warn("NEWBIE mark not written", ex);
            }
            // The run directory keeps options.txt: a previous run ended in Russian.
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
            Incoming.LOG.info("NEWBIE start: operator {}, game mode {}", p.hasPermissions(2), p.gameMode.getGameModeForPlayer());
        }));
        until("chunks drawn", 2400, () -> mc.levelRenderer.hasRenderedAllSections() && mc.levelRenderer.countRenderedSections() > 50);
        walk(true);
        run(() -> {
            lang = "ru";
            mc.options.languageCode = "ru_ru";
            mc.getLanguageManager().setSelected("ru_ru");
            mc.options.save();
            mc.reloadResourcePacks();
        });
        await(200);
        until("resources reloaded", 2400, () -> mc.getOverlay() == null);
        walk(false);
        run(() -> {
            Incoming.LOG.info("NEWBIE done");
            Incoming.LOG.info("SCENARIO done");
            mc.stop();
        });
    }

    /** One pass: the full path in the first language, the parts with text again in the second. */
    private void walk(boolean full) {
        // Survival: inventory empty, recipe book open on the crafting grid; is the designator's recipe there?
        run(() -> onServer(server -> {
            ServerPlayer p = player(server);
            p.getInventory().clearContent();
            p.setGameMode(GameType.SURVIVAL);
            // what a player has in hand when they look for a way to call a strike: a spyglass, iron, redstone, compass
            p.getInventory().add(new ItemStack(Items.SPYGLASS));
            p.getInventory().add(new ItemStack(Items.IRON_INGOT, 2));
            p.getInventory().add(new ItemStack(Items.REDSTONE));
            p.getInventory().add(new ItemStack(Items.COMPASS));
        }));
        await(40);
        run(() -> {
            mc.player.getRecipeBook().setOpen(RecipeBookType.CRAFTING, true);
            mc.setScreen(new InventoryScreen(mc.player));
        });
        await(10);
        run(() -> search("designator"));
        settle();
        shot("recipe-book", () -> "known " + mc.player.getRecipeBook().contains(ResourceLocation.fromNamespaceAndPath(Incoming.MOD_ID, "strike_designator")));
        // The designator in hand, first time: what does the screen say?
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
            aimAtGround();
        });
        await(60);
        shot("hold", () -> "");
        // Tooltip: hover over it in the inventory.
        run(() -> {
            mc.player.getRecipeBook().setOpen(RecipeBookType.CRAFTING, false);
            mc.setScreen(new InventoryScreen(mc.player));
        });
        await(5);
        run(this::hoverHotbar0);
        settle();
        shot("tooltip", () -> String.join(" | ", mc.player.getInventory().getItem(0)
                .getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.NORMAL).stream().map(Component::getString).toList()));
        run(() -> mc.setScreen(null));
        await(5);
        // RMB held: binoculars.
        run(() -> {
            aimAtGround();
            mc.options.keyUse.setDown(true);
        });
        await(30);
        shot("scope", () -> "");
        // LMB: launch, no munitions in survival.
        run(Designator::fire);
        await(8);
        shot("fire-empty", this::overlay);
        // Wheel: next weapon.
        run(() -> Designator.scroll(-1));
        await(8);
        shot("scroll", this::overlay);
        run(() -> Designator.scroll(1));
        await(30);
        // Crafted munition in the inventory: launch.
        run(() -> onServer(server -> player(server).getInventory().add(new ItemStack(ModItems.CRUISE_MISSILE.get()))));
        await(10);
        run(Designator::fire);
        await(10);
        shot("launched", this::overlay);
        if (full) {
            await(100);
            shot("in-flight", this::overlay);
            // Looking at the sky: nothing under the crosshair.
            run(() -> mc.player.setXRot(-60));
            await(10);
            shot("sky", this::overlay);
            run(Designator::fire);
            await(8);
            shot("sky-fire", this::overlay);
        }
        run(() -> mc.options.keyUse.setDown(false));
        await(10);
        // Shift+RMB: the console.
        run(() -> {
            aimAtGround();
            mc.options.keyShift.setDown(true);
        });
        await(3);
        run(() -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND));
        await(3);
        run(() -> mc.options.keyShift.setDown(false));
        until("console", 100, () -> mc.screen instanceof RemoteScreen);
        run(() -> mouse(5, 5));
        settle();
        shot("console", () -> String.valueOf(mc.screen));
        run(() -> hover(() -> widgetWith(WeaponType.NUKE.displayName().getString())));
        settle();
        shot("console-icbm-tip", () -> "");
        // Player target with nobody picked: what does FIRE do?
        run(() -> click(widgetWith(ua.zentix.incoming.strike.TargetMode.PLAYER.displayName().getString())));
        await(5);
        run(() -> hover(() -> widgetWith(Component.translatable("incoming.remote.fire").getString())));
        settle();
        shot("console-player-fire", () -> "fire active " + (firstWidgetContaining(Component.translatable("incoming.remote.fire").getString()) instanceof AbstractWidget w && w.active));
        run(() -> click(widgetWith(ua.zentix.incoming.strike.TargetMode.LOOK.displayName().getString())));
        await(5);
        run(() -> hover(() -> widgetWith(Component.translatable("incoming.remote.settings").getString())));
        settle();
        shot("console-settings-hover", () -> "");
        // Settings button.
        run(() -> click(widgetWith(Component.translatable("incoming.remote.settings").getString())));
        await(10);
        run(() -> mouse(5, 5));
        settle();
        shot("settings", () -> String.valueOf(mc.screen) + " buttons " + buttons());
        if (full) {
            // Server settings page, as a non-operator owner of a single-player world.
            run(() -> {
                AbstractWidget server = firstWidgetContaining("Server", "Сервер", "сервер");
                Incoming.LOG.info("NEWBIE {} server settings button: {}", lang, server == null ? "none" : server.getMessage().getString() + " active " + server.active);
                if (server != null && server.active) click(new double[]{server.getX() + 2, server.getY() + 2});
            });
            await(10);
            settle();
            shot("settings-server", () -> String.valueOf(mc.screen) + " buttons " + buttons());
        }
        run(() -> mc.setScreen(null));
        await(5);
        // ICBM from the console, with the munition: non-operator in their own single-player world.
        run(() -> onServer(server -> player(server).getInventory().add(new ItemStack(ModItems.ICBM.get()))));
        await(5);
        run(() -> mc.setScreen(new RemoteScreen()));
        await(5);
        run(() -> click(widgetWith(WeaponType.NUKE.displayName().getString())));
        await(5);
        run(() -> mouse(5, 5));
        settle();
        shot("console-icbm", () -> "");
        run(() -> click(widgetWith(Component.translatable("incoming.remote.fire").getString())));
        await(20);
        shot("icbm-fire", () -> String.valueOf(mc.screen) + " " + overlay());
        run(() -> {
            if (mc.screen != null) mc.setScreen(null);
        });
        await(5);
        if (full) {
            // Targeting map key.
            run(() -> net.minecraft.client.KeyMapping.click(Keys.MAP.getKey()));
            await(40);
            settle();
            shot("map", () -> String.valueOf(mc.screen));
            run(() -> {
                if (mc.screen != null) mc.setScreen(null);
            });
            await(5);
        }
        // Creative: the mod's tab.
        run(() -> onServer(server -> player(server).setGameMode(GameType.CREATIVE)));
        await(10);
        run(() -> mc.setScreen(new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), false)));
        await(5);
        run(this::selectIncomingTab);
        run(() -> mouse(5, 5));
        settle();
        shot("creative-tab", () -> "");
        run(() -> mc.setScreen(null));
        await(5);
        run(() -> onServer(server -> player(server).setGameMode(GameType.SURVIVAL)));
        await(10);
    }

    // ---------------------------------------------------------------- actions

    private void aimAtGround() {
        Vec3 eye = mc.player.getEyePosition();
        if (ground == null) {
            int x = (int) Math.floor(eye.x), z = (int) Math.floor(eye.z) - 40;
            ground = new Vec3(x + 0.5, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z + 0.5);
        }
        Vec3 d = ground.subtract(eye);
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
    }

    private void search(String text) {
        if (!(mc.screen instanceof InventoryScreen s)) return;
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
