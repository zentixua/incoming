package ua.zentix.incoming.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.screen.IncomingConfigScreen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Optional real-widget check in an isolated integrated world, before the command scene starts. */
@Mod(value = Incoming.MOD_ID, dist = Dist.CLIENT)
public final class ConfigScreenCheck {
    private int tick, stage;
    private final Path directory;

    public ConfigScreenCheck() {
        String dir = System.getProperty("incoming.check.config");
        directory = dir == null ? null : Path.of(dir);
        if (directory != null) NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.hasSingleplayerServer() || stage > 12 || ++tick % 20 != 0) return;
        try {
            switch (stage++) {
                case 0 -> mc.setScreen(new IncomingConfigScreen(ModList.get().getModContainerById(Incoming.MOD_ID).orElseThrow(), null));
                case 1 -> { log("EN root " + messages(mc.screen)); shot("config-en-root"); }
                case 2 -> press(mc.screen, "neoforge.configuration.uitext.type.server");
                case 3 -> { log("EN server " + messages(mc.screen)); pressRow(mc.screen, "incoming.config.world"); }
                case 4 -> { log("EN world " + messages(mc.screen)); shot("config-en-world"); pressRow(mc.screen, "incoming.config.early_warning"); }
                case 5 -> mc.screen.onClose();
                case 6 -> mc.screen.onClose();
                case 7 -> {
                    if (IncomingConfig.SERVER.earlyWarning.get()) throw new IllegalStateException("widget did not change early_warning to false");
                    Path file = net.neoforged.fml.config.ModConfigs.getConfigSet(net.neoforged.fml.config.ModConfig.Type.SERVER).stream()
                            .filter(config -> config.getModId().equals(Incoming.MOD_ID)).findFirst().orElseThrow().getFullPath();
                    String config = Files.readString(file);
                    if (!config.contains("early_warning = false")) throw new IllegalStateException("editor did not persist false");
                    log("PASS nested editor persisted early_warning=false");
                    mc.getLanguageManager().setSelected("ru_ru");
                    mc.options.languageCode = "ru_ru";
                    stage = 50;
                    mc.reloadResourcePacks().thenRun(() -> mc.execute(() -> stage = 8));
                }
                case 8 -> mc.setScreen(new IncomingConfigScreen(ModList.get().getModContainerById(Incoming.MOD_ID).orElseThrow(), null));
                case 9 -> { log("RU root " + messages(mc.screen)); shot("config-ru-root"); press(mc.screen, "neoforge.configuration.uitext.type.server"); }
                case 10 -> pressRow(mc.screen, "incoming.config.world");
                case 11 -> { log("RU world " + messages(mc.screen)); shot("config-ru-world"); }
                case 12 -> {
                    mc.screen.onClose(); mc.screen.onClose(); mc.setScreen(null);
                    log("PASS config screens complete");
                    if (Boolean.getBoolean("incoming.check.configOnly")) mc.stop();
                }
                default -> { }
            }
        } catch (Exception error) {
            log("FAIL " + error);
            stage = 100;
            mc.setScreen(null);
        }
    }

    private void shot(String name) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), ignored -> {});
    }

    private static List<GuiEventListener> all(GuiEventListener root) {
        List<GuiEventListener> result = new ArrayList<>();
        result.add(root);
        if (root instanceof ContainerEventHandler container) for (var child : container.children()) result.addAll(all(child));
        return result;
    }

    private static String messages(GuiEventListener root) {
        return all(root).stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                .map(w -> w.getMessage().getString() + " active=" + w.active).toList().toString();
    }

    private static void press(GuiEventListener root, String key) {
        for (var item : all(root)) if (item instanceof AbstractButton button && button.getMessage().toString().contains(key)) {
            if (!button.active) throw new IllegalStateException("disabled " + key);
            button.onPress(); return;
        }
        throw new IllegalStateException("missing button " + key + " " + messages(root));
    }

    private static void pressRow(GuiEventListener root, String key) {
        for (var item : all(root)) if (item instanceof ContainerEventHandler container) {
            var children = container.children();
            boolean label = children.stream().anyMatch(c -> c instanceof AbstractWidget w && w.getMessage().toString().contains(key));
            if (label) for (var child : children) if (child instanceof AbstractButton button) { button.onPress(); return; }
        }
        throw new IllegalStateException("missing row " + key + " " + messages(root));
    }

    private void log(String line) {
        Incoming.LOG.info("CONFIG_CHECK {}", line);
        try { Files.createDirectories(directory); Files.writeString(directory.resolve("config-screen.log"), line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
}
