package ua.zentix.incoming.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.incoming.Incoming;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The agent's hands in the standard dev client (tools/dev_client.sh): what a person does at the keyboard of a running
 * {@code runClient}. The headless display passes no keyboard or mouse input to the game (XTEST events never reach an
 * Xwayland window inside a nested KWin {@code --virtual}), so the keys come as files: each {@code <id>.cmd} in the folder of
 * {@code incoming.dev.inbox} holds one line, its answer goes to {@code <id>.done} ("ok …" or "error …"):
 * <ul>
 *   <li>{@code cmd <command>} — a chat command, as typed after "/";</li>
 *   <li>{@code reload} — F3+T: resources and the mod's shaders, answered when the reload has finished;</li>
 *   <li>{@code shot <name>} — F2: the frame to {@code screenshots/<name>.png}, answered once the file is written;</li>
 *   <li>{@code hud on|off} — F1;</li>
 *   <li>{@code press <button text>} — a click on that button of the open screen (a one-time world warning); answered
 *       "ok none" once the player is in the world without such a screen.</li>
 * </ul>
 * Off unless the property is set (the {@code client} run sets it with {@code -PhotSwap}).
 */
@Mod(value = Incoming.MOD_ID, dist = Dist.CLIENT)
public final class DevHands {
    private final Path inbox;
    /** Waiting {@code press} lines with their first tick. */
    private final List<Object[]> presses = new ArrayList<>();
    private long tick;

    public DevHands() {
        String dir = System.getProperty("incoming.dev.inbox");
        inbox = dir == null ? null : Path.of(dir);
        if (inbox == null) return;
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::opened);
        Incoming.LOG.info("DEV hands: commands from {}", inbox);
    }

    /** Every screen that opens, by class and title: the script's view of where the game stands. */
    private void opened(ScreenEvent.Opening e) {
        Screen s = e.getNewScreen();
        Incoming.LOG.info("DEV screen {} \"{}\"", s.getClass().getSimpleName(), s.getTitle().getString());
    }

    /** Client tick, paused or not (a pausing screen is exactly where {@code press} is needed). */
    private void tick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        tick++;
        for (var it = presses.iterator(); it.hasNext(); ) {
            Object[] p = it.next();
            String result = press(mc, (String) p[1]);
            if (result == null && tick - (long) p[2] > 20 * 900) result = "error no button \"" + p[1] + "\" within 900 s";
            if (result != null) {
                it.remove();
                done((String) p[0], result);
            }
        }
        if (tick % 4 != 0) return;
        List<Path> files;
        try (Stream<Path> list = Files.list(inbox)) {
            files = list.filter(f -> f.getFileName().toString().endsWith(".cmd")).sorted().toList();
        } catch (IOException ex) {
            return;
        }
        for (Path f : files) {
            String id = f.getFileName().toString().replaceFirst("\\.cmd$", "");
            String line;
            try {
                line = Files.readString(f).strip();
                Files.delete(f);
            } catch (IOException ex) {
                continue;
            }
            Incoming.LOG.info("DEV {} {}", id, line);
            try {
                run(mc, id, line);
            } catch (RuntimeException ex) {
                done(id, "error " + ex);
            }
        }
    }

    private void run(Minecraft mc, String id, String line) {
        int space = line.indexOf(' ');
        String verb = space < 0 ? line : line.substring(0, space), arg = space < 0 ? "" : line.substring(space + 1).strip();
        switch (verb) {
            case "cmd" -> {
                if (mc.player == null) throw new IllegalStateException("not in a world");
                mc.player.connection.sendCommand(arg.startsWith("/") ? arg.substring(1) : arg);
                done(id, "ok");
            }
            case "reload" -> {
                long t0 = System.nanoTime();
                mc.reloadResourcePacks().thenRun(() -> mc.execute(() ->
                        done(id, String.format(Locale.ROOT, "ok reload %.1f s", (System.nanoTime() - t0) / 1e9))));
            }
            case "shot" -> {
                if (!arg.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")) throw new IllegalArgumentException("shot name: letters, digits, _ . -");
                Screenshot.grab(mc.gameDirectory, arg + ".png", mc.getMainRenderTarget(), message -> done(id, "ok " + arg + ".png"));
            }
            case "hud" -> {
                mc.options.hideGui = arg.equals("off");
                done(id, "ok");
            }
            case "press" -> presses.add(new Object[] {id, arg, tick});
            case "dw" -> {
                DustWallSteps.apply(arg);
                done(id, "ok");
            }
            default -> throw new IllegalArgumentException("unknown: " + line + " (cmd, reload, shot, hud, press, dw)");
        }
    }

    /** {@code press}: the button's result, or null while neither the button nor the world is there. */
    private static String press(Minecraft mc, String text) {
        if (mc.screen != null) {
            for (AbstractButton b : buttons(mc.screen)) {
                if (b.getMessage().getString().equals(text) && b.active) {
                    b.onPress();
                    return "ok pressed";
                }
            }
        }
        return mc.screen == null && mc.player != null ? "ok none" : null;
    }

    private static List<AbstractButton> buttons(ContainerEventHandler parent) {
        List<AbstractButton> out = new ArrayList<>();
        for (GuiEventListener c : parent.children()) {
            if (c instanceof AbstractButton b) out.add(b);
            if (c instanceof ContainerEventHandler nested) out.addAll(buttons(nested));
        }
        return out;
    }

    private void done(String id, String result) {
        try {
            Path tmp = inbox.resolve(id + ".done.tmp");
            Files.writeString(tmp, result + "\n");
            Files.move(tmp, inbox.resolve(id + ".done"), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            Incoming.LOG.warn("DEV {}: no answer file", id, ex);
        }
    }
}
