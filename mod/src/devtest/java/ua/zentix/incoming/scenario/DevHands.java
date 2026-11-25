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
        NeoForge.EVENT_BUS.addListener(this::frame);
        Incoming.LOG.info("DEV hands: commands from {}", inbox);
    }

    /** {@code watch on}: every frame's three upper pixels, a jump logged (a one-frame dropout of an effect). */
    private boolean watch;
    private final float[] lastLuma = new float[3];
    private long frames;
    private final java.nio.ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(4);

    private void frame(net.neoforged.neoforge.client.event.RenderFrameEvent.Post e) {
        if (!watch) return;
        Minecraft mc = Minecraft.getInstance();
        var t = mc.getMainRenderTarget();
        frames++;
        int read = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING);
        com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, t.frameBufferId);
        float[] l = new float[3];
        int jumps = 0;
        for (int i = 0; i < 3; i++) {
            px.clear();
            org.lwjgl.opengl.GL11.glReadPixels(t.width * (1 + 3 * i) / 8, t.height * 7 / 10, 1, 1, org.lwjgl.opengl.GL11.GL_RGBA,
                    org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, px);
            l[i] = 0.2126f * (px.get(0) & 255) + 0.7152f * (px.get(1) & 255) + 0.0722f * (px.get(2) & 255);
            if (Math.abs(l[i] - lastLuma[i]) > 40) jumps++;
        }
        com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, read);
        if (jumps >= 2 && frames > 2) {
            Incoming.LOG.info(String.format(Locale.ROOT, "DEV pop frame %d luma %.0f/%.0f/%.0f -> %.0f/%.0f/%.0f; dust %s", frames,
                    lastLuma[0], lastLuma[1], lastLuma[2], l[0], l[1], l[2], ua.zentix.incoming.client.fx.layer.DustWallPass.describe()));
        }
        System.arraycopy(l, 0, lastLuma, 0, 3);
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
            case "age" -> {
                // the newest detonation's shown age, its front's ground radius and the eye's ground distance (a hold at a
                // front distance needs them: /tick step's pace varies with the frame rate)
                var all = ua.zentix.incoming.client.nuclear.ClientNuclear.detonations();
                if (all.isEmpty() || mc.player == null) throw new IllegalStateException("no detonation");
                var a = all.getLast();
                double t = a.seconds(0);
                var e = mc.gameRenderer.getMainCamera().getPosition();
                done(id, String.format(Locale.ROOT, "ok age %.2f front %.0f eye %.0f", t, a.wall.reach(t),
                        Math.hypot(e.x - a.d.burst().x, e.z - a.d.burst().z)));
            }
            case "watch" -> {
                watch = arg.equals("on");
                done(id, "ok " + frames);
            }
            case "dw" -> {
                DustWallSteps.apply(arg);
                done(id, "ok");
            }
            default -> throw new IllegalArgumentException("unknown: " + line + " (cmd, reload, shot, hud, press, dw, age, watch)");
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
