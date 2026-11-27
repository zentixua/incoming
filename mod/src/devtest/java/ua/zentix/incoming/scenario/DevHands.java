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
import ua.zentix.incoming.client.fx.layer.FxViews;

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
    /** {@code skip}: answer once the client has rebuilt the detonations at their new age (id, due tick, answer). */
    private final List<Object[]> skips = new ArrayList<>();
    private long tick;

    public DevHands() {
        String dir = System.getProperty("incoming.dev.inbox");
        inbox = dir == null ? null : Path.of(dir);
        // -Dincoming.dev.smoke=true: the smoke log from the start, in any client with the scenario jar (a Flashback export too)
        if (Boolean.getBoolean("incoming.dev.smoke")) {
            smoke = true;
            if (inbox == null) NeoForge.EVENT_BUS.addListener(this::tick);
        }
        if (inbox == null) return;
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::opened);
        NeoForge.EVENT_BUS.addListener(this::frame);
        Incoming.LOG.info("DEV hands: commands from {}", inbox);
    }

    /**
     * {@code watch on}: every frame's three upper pixels (1/8, 1/2, 7/8 of the width at 0.3 of the height from the top), a frame
     * where two of them jump by more than 40 of 255 logged: a whole-frame dropout of an effect against the sky moves them by
     * 60–150, the dust's own flicker by under 5. Three synchronous reads stall the GPU every frame: frame times under watch are
     * not the game's.
     */
    private boolean watch;
    /** {@code smoke on}: every smoke source's state twice a second ({@code DEV smoke}). */
    private boolean smoke;
    private final float[] lastLuma = new float[3];
    private long frames;
    private final java.nio.ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(4);

    private void frame(net.neoforged.neoforge.client.event.RenderFrameEvent.Post e) {
        if (!watch) return;
        Minecraft mc = Minecraft.getInstance();
        var t = mc.getMainRenderTarget();
        frames++;
        int read = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING);
        // into client memory: no pixel pack buffer bound (as SceneDepth reads)
        com.mojang.blaze3d.platform.GlStateManager._glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, 0);
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
        if (smoke && tick % 10 == 0 && mc.level != null)
            Incoming.LOG.info("DEV smoke t {} fps {} | {} | {}", mc.level.getGameTime(), mc.getFps(),
                    ua.zentix.incoming.client.far.FarRenderer.describeSmoke(), ua.zentix.incoming.client.fx.smoke.SmokeGrid.describeSources());
        for (var it = presses.iterator(); it.hasNext(); ) {
            Object[] p = it.next();
            String result = press(mc, (String) p[1]);
            if (result == null && tick - (long) p[2] > 20 * 900) result = "error no button \"" + p[1] + "\" within 900 s";
            if (result != null) {
                it.remove();
                done((String) p[0], result);
            }
        }
        for (var it = skips.iterator(); it.hasNext(); ) {
            Object[] k = it.next();
            if (tick < (long) k[1]) continue;
            it.remove();
            ua.zentix.incoming.client.nuclear.ClientNuclear.rewound();
            done((String) k[0], (String) k[2]);
        }
        if (inbox == null || tick % 4 != 0) return;
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
            case "light" -> done(id, "ok " + ua.zentix.incoming.client.nuclear.NukeLight.describe() + "; view secondary " + FxViews.secondary()
                    + " skipped " + FxViews.skipped() + ", target " + mc.getMainRenderTarget().width + "x" + mc.getMainRenderTarget().height);
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
            case "skip" -> {
                // the newest detonation becomes that many seconds old: the server's game time moves there (no day-time step, so
                // the strike's clocks take the skipped span as real time, as at a 24-hour day) and the client rebuilds every
                // detonation at its new age, as a replay seek does (ClientScenario.fsSkip); answers with the burst and its wind
                var all = ua.zentix.incoming.client.nuclear.ClientNuclear.detonations();
                var server = mc.getSingleplayerServer();
                if (all.isEmpty() || server == null) throw new IllegalStateException("no detonation or not single player");
                var d = all.getLast().d;
                long target = d.gameTime() + Math.round(Double.parseDouble(arg) * 20);
                server.execute(() -> {
                    var level = server.overworld();
                    ((net.minecraft.world.level.storage.ServerLevelData) level.getLevelData()).setGameTime(target);
                    server.getPlayerList().broadcastAll(new net.minecraft.network.protocol.game.ClientboundSetTimePacket(level.getGameTime(),
                            level.getDayTime(), level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DAYLIGHT)));
                });
                skips.add(new Object[] {id, tick + 15, String.format(Locale.ROOT, "ok burst %.0f %.0f %.0f wind %.3f rad %.1f m/s",
                        d.burst().x, d.burst().y, d.burst().z, d.windDir(), d.windSpeed())});
            }
            case "describe" -> {
                // what a client scenario's shot logs before its grab (ClientScenario.shot): the same calls at the same point
                Incoming.LOG.info("DEV describe pall {}", ua.zentix.incoming.client.fx.layer.PallPass.describe());
                if (!ua.zentix.incoming.client.nuclear.ClientNuclear.detonations().isEmpty()) {
                    Incoming.LOG.info("DEV describe nuke {}", ua.zentix.incoming.client.nuclear.NukePall.describe());
                    Incoming.LOG.info("DEV describe wall {}", ua.zentix.incoming.client.nuclear.WallProbe.describe());
                }
                done(id, "ok");
            }
            case "smoke" -> {
                // every smoke source's preparation state, now and twice a second while on (a pop's cause by source)
                smoke = arg.equals("on");
                Incoming.LOG.info("DEV smoke {} | {}", ua.zentix.incoming.client.far.FarRenderer.describeSmoke(),
                        ua.zentix.incoming.client.fx.smoke.SmokeGrid.describeSources());
                done(id, "ok");
            }
            case "watch" -> {
                watch = arg.equals("on");
                frames = 0;
                done(id, "ok " + frames);
            }
            case "dw" -> {
                DustWallSteps.apply(arg);
                done(id, "ok");
            }
            default -> throw new IllegalArgumentException("unknown: " + line + " (cmd, reload, shot, hud, press, dw, age, skip, watch, smoke, describe)");
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
