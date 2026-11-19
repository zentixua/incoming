package ua.zentix.incoming.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.fml.loading.FMLPaths;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.fx.layer.PallVariants;
import ua.zentix.incoming.client.nuclear.AgeProbe;
import ua.zentix.incoming.client.nuclear.ClientNuclear;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Measurement only (wave-proposal, not for merge), scenario {@code waverig}: the same script on main and on main before #668/#669.
 * Three 1 Mt surface bursts at fresh plains sites, 0.35 wave speed, camera on the ground facing ground zero:
 * <ol>
 * <li>J3: 3 km, pall detail free (as in the game), no interruption: per-frame clock/front/screen log;</li>
 * <li>J6: the same at 6 km;</li>
 * <li>N3: 3 km again, detail pinned; when the dust head is 1500, 600 and 200 m from the camera the world is frozen and the
 * same instant is shot from three views (ground G, side S, elevated E) with each head-noise variant (two frames 2 ticks
 * apart for temporal noise) and once without the pall.</li>
 * </ol>
 * Logs (game dir {@code logs/}): {@code waverig-frames.txt} every frame, {@code waverig-ticks.txt} every client tick,
 * {@code waverig-server.txt} every integrated-server tick; events as {@code WAVERIG} lines in latest.log with epoch ms.
 */
final class WaveRig {
    private static final double YIELD = 1000, WAVE = 0.35;
    private static final int[] FREEZE_AT = {1500, 600, 200};
    private static final String BIOMES = "minecraft:plains,minecraft:sunflower_plains,minecraft:savanna,minecraft:meadow";

    private final ArrayDeque<BooleanSupplier> steps = new ArrayDeque<>();
    private final List<int[]> sites = new ArrayList<>();
    private BufferedWriter frames, ticks, server;
    private int tick, frameNo;
    private long lastFrameNs;
    private String run = "-";
    private ClientNuclear.Active active;
    private int count;
    private double dist, arrival;
    private Vec3 burst, camera;
    private double groundY;
    private long held = -1;
    private int waitUntil;
    // the frame's matrices and camera (AFTER_LEVEL)
    private final Matrix4f proj = new Matrix4f(), view = new Matrix4f();
    private Vec3 eye = Vec3.ZERO;
    private boolean logging;
    private IncomingConfig.Client.DustQuality quality = IncomingConfig.Client.DustQuality.MEDIUM;

    WaveRig() {
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("logs");
            Files.createDirectories(dir);
            frames = Files.newBufferedWriter(dir.resolve("waverig-frames.txt"));
            ticks = Files.newBufferedWriter(dir.resolve("waverig-ticks.txt"));
            server = Files.newBufferedWriter(dir.resolve("waverig-server.txt"));
            frames.write("epoch_ns frame dt_ms run partial client_game age_drawn anchor since shown framed streamed own head height dist "
                    + "top_x top_y foot_x foot_y rung variant held\n");
            ticks.write("epoch_ns tick run client_game anchor since shown framed streamed own\n");
            server.write("epoch_ns_pre epoch_ns_post tick_count game_time tick_ms\n");
        } catch (IOException e) {
            Incoming.LOG.error("WAVERIG files", e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onStage);
        NeoForge.EVENT_BUS.addListener(this::onFramePre);
        NeoForge.EVENT_BUS.addListener(this::onFramePost);
        NeoForge.EVENT_BUS.addListener(this::onServerPre);
        NeoForge.EVENT_BUS.addListener(this::onServerPost);
        script();
    }

    // ---------------------------------------------------------------- script

    private void script() {
        waitTicks(200);
        run(() -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.hideGui = true;
            cmd("gamemode spectator");
            cmd("time set 6000");
            cmd("weather clear");
            mc.getSingleplayerServer().execute(() -> {
                IncomingConfig.SERVER.nukeMaxYield.set((int) YIELD);
                IncomingConfig.SERVER.scalePreset.set(IncomingConfig.ScalePreset.CUSTOM);
                IncomingConfig.SERVER.nukeWaveSpeed.set(WAVE);
            });
            event("setup anchored=" + AgeProbe.anchored() + " variants=" + String.join(",", PallVariants.names()));
        });
        waitTicks(40);
        if ("noise".equals(System.getProperty("incoming.waverig.mode"))) {
            // follow-up run: frozen variants with replayed motion at the first site (J3's site, same in both builds)
            burstRun("N3", 3000, true);
        } else if ("jerk".equals(System.getProperty("incoming.waverig.mode"))) {
            // timing repeat: Artem's game overlapped the first run's J3/J6 on this build
            burstRun("J3", 3000, false);
            burstRun("J6", 6000, false);
        } else {
            burstRun("J3", 3000, false);
            burstRun("J6", 6000, false);
            burstRun("N3", 3000, true);
        }
        run(() -> {
            event("done");
            close();
            Minecraft.getInstance().stop();
        });
    }

    private void burstRun(String name, int d, boolean frozenShots) {
        run(() -> {
            run = name;
            dist = d;
            pickSite();
            // high above the camera spot first: chunks around it generate, then down to the ground
            cmd(String.format(Locale.ROOT, "tp @s %.1f %.1f %.1f 180 -5", camera.x, groundY + 150, camera.z));
            System.setProperty("incoming.pall.free", Boolean.toString(!frozenShots));
        });
        waitTicks(200);
        run(() -> groundCam(camera.x, camera.z, 0, 180, -5));
        waitTicks(300);
        run(() -> {
            count = ClientNuclear.detonations().size();
            event(String.format(Locale.ROOT, "%s detonate at %.0f %.0f %.0f, camera %.0f %.0f", name, burst.x, groundY, burst.z, camera.x, camera.z));
            cmd(String.format(Locale.ROOT, "incoming nuke now at %.1f %.1f %.1f %d ground", burst.x, groundY, burst.z, (int) YIELD));
        });
        until(() -> ClientNuclear.detonations().size() > count);
        run(() -> {
            active = ClientNuclear.detonations().getLast();
            dist = Math.hypot(eye.x - active.d.burst().x, eye.z - active.d.burst().z);
            arrival = active.d.arrivalTicks(dist);
            logging = true;
            event(String.format(Locale.ROOT, "%s detonation id %d, camera at %.0f blocks, eye y %.1f ground %.1f, arrival %.1f ticks; window %.0f..%.0f",
                    name, active.d.id(), dist, eye.y, active.d.groundY(), arrival, arrival - 160, arrival + 40));
        });
        if (frozenShots) {
            for (int x : FREEZE_AT) {
                until(() -> head() >= dist - x);
                frozen(name + "_d" + x);
            }
        }
        until(() -> AgeProbe.shownTicks(active) > arrival + 200);
        run(() -> {
            logging = false;
            event(name + " end");
            cmd("incoming clear");
        });
        waitTicks(200);
    }

    /** Frozen instant: three views × variants × two frames, plus one without the pall. */
    private void frozen(String tag) {
        String[] variants = AgeProbe.anchored() ? new String[]{"V0", "NOROLL", "TILE", "NOTOE", "OLD"} : new String[]{"V0"};
        run(() -> {
            cmd("tick freeze");
            held = AgeProbe.shownTicks(active);
            System.setProperty("incoming.pall.free", "false");
            event(String.format(Locale.ROOT, "%s freeze at shown %d, head %.1f (camera %.0f)", tag, held, head(), dist));
        });
        waitTicks(20);
        for (String v : new String[]{"G", "S", "E"}) {
            run(() -> view(v));
            waitTicks("G".equals(v) ? 30 : 80);
            for (String var : variants) {
                run(() -> PallVariants.apply(var));
                waitTicks(16);
                shot(tag + "_" + v + "_" + var + "_a");
                waitTicks(2);
                shot(tag + "_" + v + "_" + var + "_b");
            }
            if (!"S".equals(v) && !tag.endsWith("d200")) {
                for (String var : variants) {
                    run(() -> PallVariants.apply(var));
                    long[] base = new long[1];
                    run(() -> base[0] = held);
                    for (int i = 0; i < 40; i++) {
                        int k = i;
                        run(() -> held = base[0] - 10 + k);
                        waitTicks(1);
                        if (k >= 10) shot(tag + "_" + v + "_" + var + "_m" + String.format(Locale.ROOT, "%02d", k - 10));
                    }
                    run(() -> held = base[0]);
                    waitTicks(4);
                }
            }
            run(() -> {
                if (!"V0".equals(PallVariants.current())) PallVariants.apply("V0");
                quality = IncomingConfig.CLIENT.nukeDustQuality.get();
                IncomingConfig.CLIENT.nukeDustQuality.set(IncomingConfig.Client.DustQuality.OFF);
            });
            waitTicks(4);
            shot(tag + "_" + v + "_off");
            run(() -> IncomingConfig.CLIENT.nukeDustQuality.set(quality));
            waitTicks(4);
        }
        run(() -> {
            view("G");
            held = -1;
            cmd("tick unfreeze");
            event(tag + " unfreeze");
        });
        waitTicks(2);
        // moving wall, one frame per tick for 3 s: pattern motion (roll) and temporal noise while the wall moves
        for (int i = 0; i < 60; i++) {
            shot(tag + "_seq_" + String.format(Locale.ROOT, "%02d", i));
            waitTicks(1);
        }
    }

    /** G — the camera spot; S — 200 m east of the head's front toward the camera, 25 m up, looking west across it; E — 120 m above the camera spot looking down 12°. */
    private void view(String v) {
        switch (v) {
            case "S" -> {
                double h = head();
                Vec3 dir = new Vec3(camera.x - burst.x, 0, camera.z - burst.z).normalize();
                Vec3 f = burst.add(dir.scale(h));
                // perpendicular toward +X for a camera south of ground zero
                Vec3 side = new Vec3(dir.z, 0, -dir.x);
                if (side.x < 0) side = side.scale(-1);
                Vec3 at = f.add(side.scale(200));
                cmd(String.format(Locale.ROOT, "tp @s %.1f %.1f %.1f 90 3", at.x, Math.max(eye.y, groundY) + 40, at.z));
            }
            case "E" -> cmd(String.format(Locale.ROOT, "tp @s %.1f %.1f %.1f 180 12", camera.x, eye.y + 120, camera.z));
            default -> groundCam(camera.x, camera.z, 0, 180, -5);
        }
        event("view " + v);
    }

    private static void groundCam(double x, double z, double up, float yaw, float pitch) {
        cmd(String.format(Locale.ROOT, "execute positioned %.1f 0 %.1f positioned over motion_blocking_no_leaves run tp @s ~ ~%.1f ~ %.1f %.1f", x, z, up, yaw, pitch));
    }

    private double head() {
        if (active == null) return 0;
        return active.wall.wall(active.seconds(0)).head();
    }

    /** Plains ground zero on a 2 km grid within 120 km (biomes from the generator), 40 km from earlier sites; camera {@link #dist} south. */
    private void pickSite() {
        var srv = Minecraft.getInstance().getSingleplayerServer();
        int d = (int) dist;
        int[] best = srv.submit(() -> {
            var level = srv.overworld();
            var source = level.getChunkSource();
            List<String> want = List.of(BIOMES.split(","));
            java.util.function.BiPredicate<Integer, Integer> match = (x, z) -> {
                var b = source.getGenerator().getBiomeSource().getNoiseBiome(net.minecraft.core.QuartPos.fromBlock(x), net.minecraft.core.QuartPos.fromBlock(64),
                        net.minecraft.core.QuartPos.fromBlock(z), source.randomState().sampler());
                for (String w : want) {
                    if (b.is(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, net.minecraft.resources.ResourceLocation.parse(w))))
                        return true;
                }
                return false;
            };
            int bx = 0, bz = 0;
            double bestScore = -1;
            for (int cx = -120_000; cx <= 120_000; cx += 2000) {
                for (int cz = -120_000; cz <= 120_000; cz += 2000) {
                    boolean near = false;
                    for (int[] o : sites) near |= Math.hypot(o[0] - cx, o[1] - cz) < 40_000;
                    if (near) continue;
                    int hit = 0, all = 0;
                    for (int r = 0; r <= 2000; r += 1000) {
                        int n = r == 0 ? 1 : 8;
                        for (int i = 0; i < n; i++) {
                            double a = 2 * Math.PI * i / n;
                            all++;
                            if (match.test(cx + (int) (r * Math.cos(a)), cz + (int) (r * Math.sin(a)))) hit++;
                        }
                    }
                    for (int s = 500; s <= d + 500; s += 500) {
                        all += 2;
                        if (match.test(cx, cz + s)) hit += 2;
                    }
                    double score = (double) hit / all;
                    if (score > bestScore) {
                        bestScore = score;
                        bx = cx;
                        bz = cz;
                    }
                }
            }
            int ground = source.getGenerator().getBaseHeight(bx, bz, Heightmap.Types.WORLD_SURFACE_WG, level, source.randomState());
            event(String.format(Locale.ROOT, "site %d %d ground %d share %.2f", bx, bz, ground, bestScore));
            return new int[]{bx, bz, ground};
        }).join();
        sites.add(best);
        burst = new Vec3(best[0] + 0.5, best[2], best[1] + 0.5);
        groundY = best[2];
        camera = new Vec3(best[0] + 0.5, best[2], best[1] + 0.5 + d);
    }

    // ---------------------------------------------------------------- sequencing

    private void waitTicks(int n) {
        steps.add(new BooleanSupplier() {
            int end = -1;

            @Override
            public boolean getAsBoolean() {
                if (end < 0) end = tick + n;
                return tick >= end;
            }
        });
    }

    private void run(Runnable r) {
        steps.add(() -> {
            r.run();
            return true;
        });
    }

    private void until(BooleanSupplier c) {
        steps.add(c);
    }

    private void shot(String name) {
        run(() -> {
            Minecraft mc = Minecraft.getInstance();
            event(String.format(Locale.ROOT, "shot %s variant %s rung %d head %.1f age %s", name, PallVariants.current(), PallVariants.rung(), head(),
                    active == null ? "-" : AgeProbe.state(active)));
            Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), c -> {});
        });
    }

    private static void cmd(String c) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.connection.sendCommand(c);
        Incoming.LOG.info("WAVERIG cmd {}", c);
    }

    private static void event(String s) {
        Incoming.LOG.info("WAVERIG {} {}", System.currentTimeMillis(), s);
    }

    // ---------------------------------------------------------------- events

    private void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        tick++;
        hold();
        if (logging && active != null) {
            write(ticks, System.nanoTime() + " " + tick + " " + run + " " + mc.level.getGameTime() + " " + AgeProbe.state(active) + "\n");
        }
        // a step that throws must not stop the rig silently
        for (int guard = 0; guard < 8 && !steps.isEmpty(); guard++) {
            boolean done;
            try {
                done = steps.peek().getAsBoolean();
            } catch (RuntimeException ex) {
                Incoming.LOG.error("WAVERIG step failed", ex);
                done = true;
            }
            if (!done) break;
            steps.poll();
        }
    }

    private void hold() {
        if (held >= 0 && active != null) AgeProbe.hold(active, held);
    }

    private void onStage(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        proj.set(e.getProjectionMatrix());
        view.set(e.getModelViewMatrix());
        eye = e.getCamera().getPosition();
    }

    private void onFramePre(RenderFrameEvent.Pre e) {
        hold();
    }

    private void onFramePost(RenderFrameEvent.Post e) {
        long now = System.nanoTime();
        double dt = lastFrameNs == 0 ? 0 : (now - lastFrameNs) / 1e6;
        lastFrameNs = now;
        frameNo++;
        Minecraft mc = Minecraft.getInstance();
        if (!logging || active == null || mc.level == null) return;
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(false);
        double drawn = AgeProbe.anchored() ? AgeProbe.framed(active) : active.ticks(partial);
        double seconds = active.seconds(partial);
        var w = active.wall.wall(seconds);
        Vec3 b = active.d.burst();
        Vec3 dir = new Vec3(eye.x - b.x, 0, eye.z - b.z).normalize();
        double gy = active.d.groundY();
        Vec3 foot = new Vec3(b.x + dir.x * w.head(), gy, b.z + dir.z * w.head());
        double[] ft = px(foot), tp = px(foot.add(0, w.height(), 0));
        write(frames, String.format(Locale.ROOT, "%d %d %.2f %s %.3f %d %.3f %s %.1f %.1f %.0f %.1f %.1f %.1f %.1f %d %s %d\n",
                now, frameNo, dt, run, partial, mc.level.getGameTime(), drawn, AgeProbe.state(active), w.head(), w.height(),
                Math.hypot(eye.x - b.x, eye.z - b.z), tp[0], tp[1], ft[0], ft[1], PallVariants.rung(), PallVariants.current(), held >= 0 ? 1 : 0));
    }

    private double[] px(Vec3 p) {
        Vector4f v = new Vector4f((float) (p.x - eye.x), (float) (p.y - eye.y), (float) (p.z - eye.z), 1);
        view.transform(v);
        proj.transform(v);
        if (v.w <= 0) return new double[]{Double.NaN, Double.NaN};
        var win = Minecraft.getInstance().getWindow();
        return new double[]{(v.x / v.w * 0.5 + 0.5) * win.getWidth(), (0.5 - v.y / v.w * 0.5) * win.getHeight()};
    }

    private long serverPre;

    private void onServerPre(ServerTickEvent.Pre e) {
        serverPre = System.nanoTime();
    }

    private void onServerPost(ServerTickEvent.Post e) {
        long now = System.nanoTime();
        write(server, serverPre + " " + now + " " + e.getServer().getTickCount() + " " + e.getServer().overworld().getGameTime() + " "
                + String.format(Locale.ROOT, "%.2f", (now - serverPre) / 1e6) + "\n");
    }

    private static synchronized void write(BufferedWriter w, String s) {
        if (w == null) return;
        try {
            w.write(s);
        } catch (IOException ignored) {
            // measurement file
        }
    }

    private synchronized void close() {
        for (BufferedWriter w : new BufferedWriter[]{frames, ticks, server}) {
            try {
                if (w != null) w.flush();
            } catch (IOException ignored) {
                // exit
            }
        }
    }
}
