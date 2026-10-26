package ua.zentix.stand;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Plan steps by client ticks: fresh flat world from title screen (identical seed/layers/noon every run,
 * no day cycle, weather or mobs; spectator is not pushed by the wave), then {@link Plan} steps in order;
 * salvo commands use their own clock ({@link #scheduled}: waves continue while steps wait); exit at the end.
 * Log everything to {@link Out}; stand.py uses these events to split frames into measurement windows.
 */
final class Director {
    static final String WORLD = "stand";
    /** Flat-world grass top: 1 + 124 + 3 + 1 layers from −64 (like Incoming sandbox). */
    static final int GROUND = 64;
    /** Eye height above feet. */
    private static final double EYE = 1.62;
    private static final long SECOND = 1_000_000_000L;
    /** Maximum lateness when a "since last explosion" step starts; otherwise {@code late}. */
    static final double LATE_S = 2;
    /** Maximum camera travel wait; afterward step proceeds with {@code tp-timeout}. */
    private static final long TP_LIMIT = 30 * SECOND;

    private enum Phase { TITLE, LOADING, SETUP, STEPS, DONE }

    private record Scheduled(long at, String salvo, String command) {}

    private final Plan plan;
    private final Out out;
    private final FrameClock clock;
    private final ServerWatch watch;
    private final Probes probes;
    private Phase phase = Phase.TITLE;
    private long worldStart, lastSecond;
    private final List<Scheduled> scheduled = new ArrayList<>();
    private int index = -1;
    private Plan.Step step;
    /** Step state: start, command sent, arrival time (tp), impact time and frame number (hit-relative window). */
    private long stepStart, reached, reachedFrame, impact;
    private boolean sent;
    private int nextShot;
    private volatile boolean shotDone;

    Director(Plan plan, Out out) {
        this.plan = plan;
        this.out = out;
        this.clock = new FrameClock(out);
        this.watch = new ServerWatch(out, plan.projectiles(), plan.warheads());
        this.probes = new Probes(out, plan.probes(), plan.once());
        NeoForge.EVENT_BUS.addListener(this::onScreen);
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(Terrain::onStage);
        out.event("plan", System.nanoTime(), "steps", plan.steps().size(), "seed", plan.seed());
        Stand.LOG.info("STAND: plan of {} steps", plan.steps().size());
    }

    // ---------------------------------------------------------------- world

    private void onScreen(ScreenEvent.Init.Post e) {
        // Before world entry, log the open screen: otherwise a windowless client stuck on another screen is opaque
        if (phase == Phase.TITLE || phase == Phase.LOADING) Stand.LOG.info("STAND screen {}", e.getScreen().getClass().getName());
        if (phase != Phase.TITLE || !(e.getScreen() instanceof TitleScreen)) return;
        phase = Phase.LOADING;
        out.event("title", System.nanoTime());
        createWorld();
    }

    /** Recreate flat world (remove previous): bedrock, 124 stone, 3 dirt, grass; plains without structures. */
    private void createWorld() {
        Minecraft mc = Minecraft.getInstance();
        Path old = mc.gameDirectory.toPath().resolve("saves").resolve(WORLD);
        if (Files.isDirectory(old)) {
            try (Stream<Path> walk = Files.walk(old)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ex) {
                Stand.LOG.warn("STAND: old world not deleted", ex);
            }
        }
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        LevelSettings settings = new LevelSettings(WORLD, GameType.SPECTATOR, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(plan.seed(), false, false), registries -> {
            var biomes = registries.lookupOrThrow(Registries.BIOME);
            FlatLevelGeneratorSettings flat = FlatLevelGeneratorSettings.getDefault(biomes, registries.lookupOrThrow(Registries.STRUCTURE_SET),
                    registries.lookupOrThrow(Registries.PLACED_FEATURE)).withBiomeAndLayers(List.of(new FlatLayerInfo(1, Blocks.BEDROCK),
                    new FlatLayerInfo(124, Blocks.STONE), new FlatLayerInfo(3, Blocks.DIRT), new FlatLayerInfo(1, Blocks.GRASS_BLOCK)),
                    Optional.empty(), biomes.getOrThrow(Biomes.PLAINS));
            WorldDimensions dims = WorldPresets.createNormalWorldDimensions(registries);
            return dims.replaceOverworldGenerator(registries, new FlatLevelSource(flat));
        }, mc.screen);
    }

    // ---------------------------------------------------------------- tick

    private void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        if (phase == Phase.DONE) return;
        boolean inWorld = mc.player != null && mc.level != null && mc.getSingleplayerServer() != null;
        if (now - lastSecond >= SECOND) {
            lastSecond = now;
            if (phase == Phase.STEPS) probes.tick(now);
            out.flush();
        }
        if (!inWorld) return;
        if (phase == Phase.LOADING) {
            phase = Phase.SETUP;
            worldStart = now;
            setup(mc, now);
        }
        if (now - worldStart > (long) (plan.limitS() * SECOND)) {
            out.event("limit", now, "step", index);
            finish(mc, now);
            return;
        }
        if (phase == Phase.SETUP) {
            if (now - worldStart < (long) (plan.settleS() * SECOND)) return;
            probes.once(now);
            gl(mc, now);
            phase = Phase.STEPS;
            next(now);
        }
        fireDue(now);
        // Non-waiting steps (command, mark, salvo) run consecutively in one tick
        for (int i = 0; i < 64 && phase == Phase.STEPS; i++) {
            if (!advance(mc, now)) return;
            next(now);
        }
    }

    private void setup(Minecraft mc, long now) {
        mc.options.hideGui = true;
        String me = mc.player.getGameProfile().getName();
        server("gamemode spectator " + me, "setup");
        server("time set " + plan.dayTime(), "setup");
        server("weather clear 1000000", "setup");
        server("gamerule doFireTick true", "setup");
        out.event("world", now, "player", me);
    }

    /** GPU, driver and frame size enter cache key and report. */
    private void gl(Minecraft mc, long now) {
        out.event("gl", now, "renderer", GL11.glGetString(GL11.GL_RENDERER), "version", GL11.glGetString(GL11.GL_VERSION),
                "vendor", GL11.glGetString(GL11.GL_VENDOR), "width", mc.getWindow().getWidth(), "height", mc.getWindow().getHeight());
    }

    private void next(long now) {
        index++;
        if (index >= plan.steps().size()) {
            finish(Minecraft.getInstance(), now);
            return;
        }
        step = plan.steps().get(index);
        stepStart = now;
        reached = -1;
        impact = -1;
        sent = false;
        nextShot = 0;
        shotDone = false;
    }

    /** Is the step complete? */
    private boolean advance(Minecraft mc, long now) {
        double elapsed = (now - stepStart) / 1e9;
        return switch (step) {
            case Plan.Tp tp -> teleport(mc, tp, now);
            case Plan.Cmd c -> {
                server(c.command(), "step");
                yield true;
            }
            case Plan.Wait w -> elapsed >= w.seconds();
            case Plan.Shot s -> {
                if (!sent) {
                    sent = true;
                    clock.requestShot(out.shots.resolve(s.name() + ".png"), frame -> {
                        out.event("shot", System.nanoTime(), "name", s.name(), "frame", frame);
                        shotDone = true;
                    });
                }
                yield shotDone;
            }
            case Plan.Window w -> {
                if (!sent) {
                    sent = true;
                    out.event("window", now, "name", w.name(), "edge", "begin", "frame", clock.frame());
                }
                if (elapsed < w.seconds()) yield false;
                out.event("window", now, "name", w.name(), "edge", "end", "frame", clock.frame());
                yield true;
            }
            case Plan.Salvo s -> {
                watch.target(s.id(), s.x(), s.z(), s.radius(), s.expect());
                for (int i = 0; i < s.commands().size(); i++) scheduled.add(new Scheduled(now + (long) (s.delays()[i] * SECOND), s.id(), s.commands().get(i)));
                out.event("salvo", now, "id", s.id(), "expect", s.expect(), "commands", s.commands().size());
                fireDue(now);
                yield true;
            }
            case Plan.AwaitSalvo a -> awaitSalvo(a, now, elapsed);
            case Plan.ImpactWindow w -> impactWindow(w, now, elapsed);
            case Plan.Since w -> since(w, now);
            case Plan.Mark m -> {
                out.event("mark", now, "name", m.name());
                yield true;
            }
        };
    }

    private boolean teleport(Minecraft mc, Plan.Tp tp, long now) {
        double[] eye = tp.eye(), look = tp.look();
        if (!sent) {
            sent = true;
            server(String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f facing %.2f %.2f %.2f", mc.player.getGameProfile().getName(),
                    eye[0], eye[1] - EYE, eye[2], look[0], look[1], look[2]), "tp");
            return false;
        }
        Vec3 at = mc.player.position();
        String wait = at.distanceTo(new Vec3(eye[0], eye[1] - EYE, eye[2])) < 1.5
                ? Terrain.waiting(mc, Mth.floor(eye[0]), Mth.floor(eye[2])) : "camera";
        // Ready must hold through the entire pause and at least two frames: Sodium visibility discovers sections
        // behind new ground a frame later (a pause without frames, e.g. one frame longer than it, cannot prove this)
        if (wait != null) reached = -1;
        else if (reached < 0) {
            reached = now;
            reachedFrame = clock.frame();
        }
        if (reached >= 0 && now - reached >= (long) (tp.settleS() * SECOND) && clock.frame() - reachedFrame >= 2) {
            out.event("tp", now, "eye", eye, "took_s", (reached - stepStart) / 1e9);
            return true;
        }
        if (now - stepStart > TP_LIMIT) {
            out.event("tp-timeout", now, "eye", eye, "at", new double[]{at.x, at.y + EYE, at.z}, "waiting", wait);
            return true;
        }
        return false;
    }

    /**
     * Salvo end: all hits; or all commands sent, some hits occurred, no world projectiles or salvo removals
     * awaiting explosion/rejoin, and last hit {@code quietS} ago; or timeout. Only "all" makes a run valid.
     */
    private boolean awaitSalvo(Plan.AwaitSalvo a, long now, double elapsed) {
        int got = watch.impacts(a.id()), want = watch.expected(a.id());
        String why = null;
        if (got >= want) why = "all";
        else if (got > 0 && watch.alive() == 0 && watch.waiting(a.id()) == 0 && !pendingFor(a.id())
                && now - watch.lastImpact(a.id()) >= (long) (a.quietS() * SECOND)) why = "quiet";
        else if (elapsed >= a.timeoutS()) why = "timeout";
        if (why == null) return false;
        out.event("await", now, "salvo", a.id(), "why", why, "impacts", got, "expect", want, "took_s", elapsed);
        return true;
    }

    /** Deadline since last salvo hit: late on entry gives {@code late}, otherwise wait; output {@code since} includes lateness. */
    private boolean since(Plan.Since w, long now) {
        long last = watch.lastImpact(w.salvo()), due = last + (long) (w.seconds() * SECOND);
        if (last >= 0 && now < due) return false;
        // Check lateness both on entry and after waiting (client tick may stall longer than the remaining time)
        if (last < 0 || now - due > (long) (LATE_S * SECOND)) {
            out.event("late", now, "salvo", w.salvo(), "s", w.seconds(), "late_s", last < 0 ? -1 : (now - due) / 1e9);
            return true;
        }
        out.event("since", now, "salvo", w.salvo(), "s", w.seconds(), "after_s", (now - last) / 1e9);
        return true;
    }

    /** Window from nth salvo hit; frames at specified seconds afterward. */
    private boolean impactWindow(Plan.ImpactWindow w, long now, double elapsed) {
        if (impact < 0) {
            impact = watch.impactTime(w.salvo(), w.n());
            if (impact < 0) {
                if (elapsed < w.timeoutS()) return false;
                out.event("impact-timeout", now, "name", w.name(), "salvo", w.salvo(), "n", w.n());
                return true;
            }
            out.event("window", impact, "name", w.name(), "edge", "begin", "frame", clock.frame());
        }
        double since = (now - impact) / 1e9;
        if (nextShot < w.shots().length && !clock.shotPending() && since >= w.shots()[nextShot]) {
            String name = String.format(Locale.ROOT, "%s_%ss", w.prefix(), trim(w.shots()[nextShot]));
            clock.requestShot(out.shots.resolve(name + ".png"), frame -> out.event("shot", System.nanoTime(), "name", name, "frame", frame));
            nextShot++;
        }
        if (since < w.seconds() || nextShot < w.shots().length || clock.shotPending()) return false;
        out.event("window", impact + (long) (w.seconds() * SECOND), "name", w.name(), "edge", "end", "frame", clock.frame());
        return true;
    }

    private static String trim(double s) {
        return s == Math.rint(s) ? String.valueOf((long) s) : String.format(Locale.ROOT, "%.1f", s);
    }

    // ---------------------------------------------------------------- commands

    private void fireDue(long now) {
        for (Iterator<Scheduled> it = scheduled.iterator(); it.hasNext(); ) {
            Scheduled s = it.next();
            if (s.at() > now) continue;
            it.remove();
            server(s.command(), s.salvo(), s.at());
        }
    }

    private boolean pendingFor(String salvo) {
        return scheduled.stream().anyMatch(s -> s.salvo().equals(salvo));
    }

    private void server(String command, String tag) {
        server(command, tag, -1);
    }

    /**
     * Execute command on server thread as console/operator; {@code cmd} event includes success, result,
     * send time, execution start and deadline (independently timed salvo commands: {@code due} is nanoTime,
     * −1 immediate). Parser verifies wave schedule using execution time: a stalled server would batch commands
     * even when client sends on time.
     */
    private void server(String command, String tag, long due) {
        MinecraftServer s = Minecraft.getInstance().getSingleplayerServer();
        if (s == null) return;
        long sentAt = System.nanoTime();
        Double dueMs = due < 0 ? null : Stand.ms(due);
        s.execute(() -> {
            double ranMs = Stand.ms(System.nanoTime());
            CommandSourceStack source = s.createCommandSourceStack().withCallback((ok, result) ->
                    out.event("cmd", System.nanoTime(), "cmd", command, "tag", tag, "ok", ok, "result", result, "sent_ms", Stand.ms(sentAt),
                            "ran_ms", ranMs, "due_ms", dueMs));
            s.getCommands().performPrefixedCommand(source, command);
        });
    }

    // ---------------------------------------------------------------- end

    private void finish(Minecraft mc, long now) {
        phase = Phase.DONE;
        if (mc.player != null) gl(mc, now);
        out.event("done", now, "frames", clock.frame(), "repeated_stages", clock.repeatedStages, "steps_done", Math.min(index, plan.steps().size()));
        out.close();
        Stand.LOG.info("STAND done: {} frames", clock.frame());
        // Exit stuck at "Saving worlds": wait at most one minute; measurement is already on disk
        Thread guard = new Thread(() -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException ignored) {
                return;
            }
            Stand.LOG.warn("STAND: exit hangs — halt");
            Runtime.getRuntime().halt(0);
        }, "stand-exit-guard");
        guard.setDaemon(true);
        guard.start();
        mc.stop();
    }
}
