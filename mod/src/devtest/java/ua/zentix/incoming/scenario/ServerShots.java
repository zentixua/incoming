package ua.zentix.incoming.scenario;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.incoming.Incoming;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Still frames on an existing multiplayer server ({@code tools/prod_client.py shots --server host:port}): the client
 * joins, switches to spectator, and for each shot of {@code incoming.shots} (JSON list of {@code name, pos "x y z yaw
 * pitch", time}) teleports, holds the time of day, hides the HUD and captures once the picture has settled. Keys do
 * not reach a client in nested KWin, so everything a player would press happens here.
 *
 * <p>"Settled": terrain rebuilt (Sodium reports no pending sections, when Sodium is present) and three consecutive
 * checks 2 s apart change under {@link #STILL} of a 1/16-scale luma grid by more than {@link #DELTA} levels; distant
 * LODs and shader accumulation only show in the picture, so the picture is the test. After {@link #MAX_WAIT} ticks the
 * frame is taken anyway and marked unsettled. The time of day found at join is set back at the end (plus the ticks
 * elapsed if the daylight cycle runs).
 */
final class ServerShots {
    private static final int CHECK = 40, STILL_CHECKS = 3, DELTA = 10;
    private static final double STILL = 0.006;
    private static final int MIN_WAIT_FIRST = 1200, MIN_WAIT = 300, MAX_WAIT = 6000;

    private final String server = System.getProperty("incoming.server");
    private final Path out = Path.of(System.getProperty("incoming.shots.out", "silo-shots"));
    private final List<JsonObject> shots = new ArrayList<>();
    private final JsonArray report = new JsonArray();
    private int tick, joinedAt = -1, lastConnect = -10_000, index = -1, shotStart, stillRuns;
    private long day0 = -1, game0;
    private boolean cycle;
    private byte[] prev;
    private double lastChange = 1;
    private boolean capture, done;
    private int lastTimeSet = -1000;

    ServerShots() {
        try {
            for (var e : JsonParser.parseString(Files.readString(Path.of(System.getProperty("incoming.shots")))).getAsJsonArray()) {
                shots.add(e.getAsJsonObject());
            }
            Files.createDirectories(out);
        } catch (IOException ex) {
            throw new IllegalStateException("SHOTS cannot read incoming.shots", ex);
        }
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onFrame);
    }

    private void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        tick++;
        if (done) return;
        if (mc.player == null || mc.level == null) {
            if (joinedAt >= 0) finish(mc, "disconnected");
            else if (mc.screen != null && !(mc.screen instanceof ConnectScreen) && tick - lastConnect > 600 && tick > 100) {
                if (lastConnect > 0 && tick > 12_000) finish(mc, "did not join " + server);
                else connect(mc);
            }
            return;
        }
        if (joinedAt < 0) {
            joinedAt = tick;
            Incoming.LOG.info("SHOTS joined {} as {}", server, mc.player.getGameProfile().getName());
            return;
        }
        int t = tick - joinedAt;
        if (t == 100) {
            day0 = mc.level.getDayTime();
            game0 = mc.level.getGameTime();
            Incoming.LOG.info("SHOTS time at join: daytime {} gametime {}", day0, game0);
            cmd("gamemode spectator");
            cmd("weather clear");
        }
        if (t == 200) {
            // the daylight cycle runs if the day advanced with the game in the first 5 s
            cycle = mc.level.getDayTime() - day0 >= 50;
            day0 = mc.level.getDayTime() - (mc.level.getGameTime() - game0);
            Incoming.LOG.info("SHOTS daylight cycle {}", cycle);
            next(mc);
        }
        if (index < 0 || index >= shots.size() || capture) return;
        JsonObject s = shots.get(index);
        int age = tick - shotStart;
        long want = s.get("time").getAsLong();
        // the server answers with its time every 20 ticks: resend only after that, not every tick of the wait
        if (Math.abs(Math.floorMod(mc.level.getDayTime(), 24000L) - want) > 20 && tick - lastTimeSet > 30) {
            lastTimeSet = tick;
            cmd("time set " + want);
        }
        if (age % CHECK != 0) return;
        Boolean terrain = terrainDone();
        int min = index == 0 ? MIN_WAIT_FIRST : s.has("wait") ? s.get("wait").getAsInt() : MIN_WAIT;
        boolean still = stillRuns >= STILL_CHECKS && !Boolean.FALSE.equals(terrain);
        if ((age >= min && still) || age >= MAX_WAIT) capture = true;
    }

    private void next(Minecraft mc) {
        index++;
        prev = null;
        stillRuns = 0;
        capture = false;
        shotStart = tick;
        if (index >= shots.size()) {
            mc.options.hideGui = false;
            // incoming.shots.restore: the time recorded by an earlier run that was interrupted before restoring it
            long back = Long.getLong("incoming.shots.restore", cycle ? day0 + (mc.level.getGameTime() - game0) : day0);
            cmd("time set " + Math.floorMod(back, 24000L));
            write();
            finishLater(mc);
            return;
        }
        JsonObject s = shots.get(index);
        mc.options.hideGui = true;
        // the server's own weather cycle ends a clear spell at random: rain started 11 minutes into the first run
        cmd("weather clear");
        lastTimeSet = tick;
        cmd("time set " + s.get("time").getAsLong());
        cmd("tp @s " + s.get("pos").getAsString());
        Incoming.LOG.info("SHOTS {} at {}", s.get("name").getAsString(), s.get("pos").getAsString());
    }

    private void onFrame(RenderFrameEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (done || mc.level == null || index < 0 || index >= shots.size()) return;
        int age = tick - shotStart;
        if (capture) {
            JsonObject s = shots.get(index);
            NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            Path to = out.resolve(s.get("name").getAsString() + ".png");
            try (image) {
                image.writeToFile(to);
            } catch (IOException ex) {
                Incoming.LOG.error("SHOTS frame {} not written", to, ex);
            }
            var cam = mc.gameRenderer.getMainCamera();
            JsonObject r = new JsonObject();
            r.addProperty("file", to.getFileName().toString());
            r.addProperty("pos", s.get("pos").getAsString());
            r.addProperty("camera", String.format(Locale.ROOT, "%.2f %.2f %.2f %.1f %.1f", cam.getPosition().x, cam.getPosition().y,
                    cam.getPosition().z, cam.getYRot(), cam.getXRot()));
            r.addProperty("time_of_day", Math.floorMod(mc.level.getDayTime(), 24000L));
            r.addProperty("waited_s", age / 20.0);
            r.addProperty("settled", stillRuns >= STILL_CHECKS);
            r.addProperty("last_change_fraction", lastChange);
            Boolean terrain = terrainDone();
            if (terrain != null) r.addProperty("terrain_complete", terrain);
            r.addProperty("loaded_chunks", mc.level.getChunkSource().getLoadedChunksCount());
            r.addProperty("fps", mc.getFps());
            r.addProperty("size", image.getWidth() + "x" + image.getHeight());
            report.add(r);
            Incoming.LOG.info("SHOTS captured {}", r);
            write();
            next(mc);
            return;
        }
        // settle check on the tick boundary of each CHECK: whole frame, every 16th pixel, luma
        if (age % CHECK != CHECK - 1 || age == 0) return;
        try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            int w = image.getWidth() / 16, h = image.getHeight() / 16;
            byte[] luma = new byte[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int c = image.getPixelRGBA(x * 16 + 8, y * 16 + 8); // ABGR
                    int r = c & 255, g = c >> 8 & 255, b = c >> 16 & 255;
                    luma[y * w + x] = (byte) ((r * 54 + g * 183 + b * 19) >> 8);
                }
            }
            if (prev != null && prev.length == luma.length) {
                int changed = 0;
                for (int i = 0; i < luma.length; i++) {
                    if (Math.abs((luma[i] & 255) - (prev[i] & 255)) > DELTA) changed++;
                }
                lastChange = changed / (double) luma.length;
                stillRuns = lastChange < STILL ? stillRuns + 1 : 0;
                Incoming.LOG.info("SHOTS {} t={}s change {} still {} terrain {} fps {}", shots.get(index).get("name").getAsString(), age / 20,
                        String.format(Locale.ROOT, "%.4f", lastChange), stillRuns, terrainDone(), mc.getFps());
            }
            prev = luma;
        }
    }

    /** Sodium's "all sections built", by reflection (devtest does not compile against Sodium); null without Sodium. */
    private static Boolean terrainDone() {
        try {
            Class<?> c = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
            Object r = c.getMethod("instanceNullable").invoke(null);
            return r == null ? null : (Boolean) c.getMethod("isTerrainRenderComplete").invoke(r);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    private void write() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject root = new JsonObject();
        root.addProperty("server", server);
        root.addProperty("daytime_at_join", day0);
        root.addProperty("daylight_cycle", cycle);
        root.add("shots", report);
        try {
            Files.writeString(out.resolve("shots.json"), gson.toJson(root));
        } catch (IOException ex) {
            Incoming.LOG.error("SHOTS shots.json not written", ex);
        }
    }

    private void connect(Minecraft mc) {
        lastConnect = tick;
        Incoming.LOG.info("SHOTS connecting to {}", server);
        ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(server),
                new ServerData("shots", server, ServerData.Type.OTHER), false, null);
    }

    private void finishLater(Minecraft mc) {
        int at = tick + 100;
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            if (tick == at) finish(mc, "all shots taken");
        });
    }

    private void finish(Minecraft mc, String why) {
        if (done) return;
        done = true;
        Incoming.LOG.info("SCENARIO done: {}", why);
        mc.stop();
    }

    private static void cmd(String c) {
        Minecraft.getInstance().player.connection.sendCommand(c);
        Incoming.LOG.info("SHOTS /{}", c);
    }
}
