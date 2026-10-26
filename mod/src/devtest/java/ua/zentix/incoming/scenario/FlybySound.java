package ua.zentix.incoming.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.flight.FlightTrack;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.guidance.Route;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.util.Local;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flybys to check sound without listening: the viewer stands on the ground and projectiles pass as each
 * sound case specifies. Log everything audible: each engine layer (without a volume threshold), each loop
 * start and stop, mod one-shots, server launch and impact marks, then {@code SCENARIO check} results per case.
 * <p>
 * Cases in order (each waits for its projectiles to finish sounding):
 * <ul>
 * <li>{@code approach}: cruise missile comes straight at the viewer from 1,8 km (viewer 30 blocks to the side
 * of the target): fan whine (carried forward) is audible from the far edge (emitted at ~1,5 km), engine grows
 * until impact;</li>
 * <li>{@code dogleg}: missile on a route detour (launch 2 km before the target, detour point 580 blocks to
 * the side): viewer 40 blocks from the middle of the first leg, missile passes heading away from the target;
 * engine is audible;</li>
 * <li>{@code pass}: missile flies at a target 700 blocks behind the viewer, passing 60 blocks to the side:
 * whine on approach, smooth fade after passing; engine only grows on approach, only fades after passing,
 * and sounds until the explosion reaches the ear;</li>
 * <li>{@code grad}: night MLRS salvo of 32 rockets, launcher 300 blocks behind, targets 300 ± 150 ahead:
 * whine of nearby passing rockets within the derived audible range, channels no more than {@code VoiceBudget.CAP};</li>
 * <li>{@code far}: missile and Shahed approach the viewer from afar: first off-world (server packet track,
 * {@code s}), then as an entity ({@code e}); sound transfers without restarting or a volume jump.</li>
 * </ul>
 * Log lines (time: {@code t} is case ticks, {@code ms} is system time for alignment with audio.wav; each case
 * starts with a short UI "pling" to align the WAV with {@code SCENARIO mark … sync}):
 * <pre>
 * SCENARIO mark &lt;case&gt; sync|fire|launch|enter-world|leave-world|stall|move|gone|end …
 * SCENARIO loop start|stop &lt;case&gt; &lt;weapon&gt;#&lt;id&gt; &lt;layer&gt; src=e|s d=… vol=… (stop: lived=… peak=…)
 * SCENARIO gain &lt;case&gt; t=… ms=… live=N | &lt;weapon&gt;#&lt;id&gt;(e|s d=…) &lt;layer&gt; volume×pitch …
 * SCENARIO play &lt;case&gt; &lt;sound&gt; d=… t=… ms=…
 * SCENARIO check &lt;case&gt; &lt;what&gt; PASS|FAIL|WARN …
 * </pre>
 * Layer {@code d} is distance to the point whose sound is audible now (retarded emission time), not to the projectile.
 * Run: {@code tools/client_scenario.sh flyby-all} (or one case: {@code flyby-grad});
 * in the full modpack: {@code tools/prod_client.py flyby-all}.
 */
final class FlybySound {
    private static final List<String> ALL = List.of("approach", "dogleg", "pass", "grad", "far");
    /** How many tick the sound can still travel to the ear after impact (the farthest is ~1500 blocks, 90 tick). */
    private static final int RINGOUT = 140;
    /** Longer than this, the case does not wait for its shells (the server in the cloud lags behind). */
    private static final int TIMEOUT = 4000;
    /**
     * The volume of the layer at which it is considered audible in tests: the layer takes the channel from it ({@code VoiceBudget.AUDIBLE}) -
     *  the volume of the sound at the background level of a quiet night ({@code Loudness}).
     */
    private static final double HEARD = 0.005;
    /** Shells in a MLRS salvo. */
    private static final int GRAD = 32;

    private enum Stage { WAIT, LOAD, CASE_SETTLE, RUN, RINGOUT, DONE }

    /** Layer of the motor in this tick: projectile, which layer, where the path is from, volume, tone, distance to the audible point. */
    private record Sample(int t, UUID id, String weapon, String layer, boolean server, double vol, double pitch, double d) {}

    /** What the server knows about the projectile: for launch, world entry, and impact marks. */
    private record Seen(String weapon, boolean virtual, Vec3 pos) {}

    private final List<String> cases;
    private int index = -1;
    private Stage stage = Stage.WAIT;
    private int tick, stageTick, caseTick;
    private Vec3 ear = Vec3.ZERO;
    private String current = "-";

    // --- server (server thread) ---
    private final Map<UUID, Seen> seen = new HashMap<>();
    /** Projectiles first seen during the event: UUID → case. */
    private final Map<UUID, String> caseOf = new ConcurrentHashMap<>();
    /** Live projectiles on occasion; hits - tick of the case when the projectile is missing. */
    private final Map<UUID, Boolean> alive = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> goneAt = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> enteredAt = new ConcurrentHashMap<>();
    /** Game tick when the projectile last moved; lasting longer than {@link #STALL} tick (server only). */
    private final Map<UUID, Long> movedAt = new HashMap<>();
    private final Set<UUID> stalled = new HashSet<>();
    private static final int STALL = 10;
    private volatile String serverCase = "-";
    private volatile int serverCaseTick;

    // --- client ---
    private Map<SoundInstance, Loop> loops = new IdentityHashMap<>();
    private final List<Sample> samples = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    /** tick when the engine was told to play an explosion. */
    private final List<Integer> blasts = new ArrayList<>();
    private int maxLive;

    /** The motor loop is still sounding. */
    private static final class Loop {
        final UUID id;
        final String weapon, layer;
        final int started;
        double peak;

        Loop(UUID id, String weapon, String layer, int started) {
            this.id = id;
            this.weapon = weapon;
            this.layer = layer;
            this.started = started;
        }
    }

    FlybySound(String which) {
        cases = "all".equals(which) ? ALL : List.of(which.split(","));
        for (String c : cases) {
            if (!ALL.contains(c)) throw new IllegalArgumentException("SCENARIO flyby: no case " + c + " (there is " + ALL + ")");
        }
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onPlay);
    }

    private void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        tick++;
        stageTick++;
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> scan(server.overworld()));
        switch (stage) {
            case WAIT -> {
                if (stageTick < 40) return;
                server("time set 6000");
                server("weather clear");
                server("gamerule doDaylightCycle false");
                server("gamerule doWeatherCycle false");
                server("gamerule doMobSpawning false");
                IncomingConfig.SERVER.launchNearPlayer.set(false);
                IncomingConfig.SERVER.maxSalvo.set(GRAD); // by default the salvo is up to 30
                server(String.format(Locale.ROOT, "tp %s 0.5 200 0.5", name()));
                next(Stage.LOAD);
            }
            case LOAD -> {
                // viewer - on the ground: height according to the client’s height map when his chunk arrived
                int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0);
                if (stageTick < 100 || y <= mc.level.getMinBuildHeight() && stageTick < 1200) return;
                ear = new Vec3(0.5, y, 0.5);
                Incoming.LOG.info("SCENARIO flyby viewer on {}", xyz(ear));
                nextCase();
            }
            case CASE_SETTLE -> {
                if (stageTick < 60) return;
                caseTick = 0;
                mark("sync", "");
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 2.0f, 0.6f));
                fire(server);
                next(Stage.RUN);
            }
            case RUN -> {
                caseTick++;
                probe(mc);
                boolean launched = caseOf.containsValue(current);
                boolean flying = caseOf.entrySet().stream().anyMatch(en -> en.getValue().equals(current) && alive.containsKey(en.getKey()));
                if (stageTick > 40 && launched && !flying) next(Stage.RINGOUT);
                else if (stageTick > TIMEOUT) {
                    check("timeout", "FAIL", "the shells of chance did not ring out for " + TIMEOUT + " tick");
                    next(Stage.RINGOUT);
                }
            }
            case RINGOUT -> {
                caseTick++;
                probe(mc);
                if (stageTick < RINGOUT) return;
                finish();
                nextCase();
            }
            case DONE -> {
                // the client closes on the wrong tick: the results are already in the log
            }
        }
        if (tick % 100 == 0) Incoming.LOG.info("SCENARIO fps {}", mc.getFps());
    }

    private void next(Stage s) {
        stage = s;
        stageTick = 0;
    }

    private void nextCase() {
        index++;
        samples.clear();
        events.clear();
        blasts.clear();
        loops = new IdentityHashMap<>();
        maxLive = 0;
        if (index >= cases.size()) {
            serverCase = "-";
            Incoming.LOG.info("SCENARIO done");
            next(Stage.DONE);
            Minecraft.getInstance().stop();
            return;
        }
        current = cases.get(index);
        serverCase = current;
        Vec3 from = geometry().listenerFacing();
        server(String.format(Locale.ROOT, "tp %s %.1f %.1f %.1f facing %.1f %.1f %.1f", name(), ear.x, ear.y, ear.z, from.x, ear.y + 10, from.z));
        next(Stage.CASE_SETTLE);
    }

    // ---------------------------------------------------------------- cases

    /**
     * Where is the goal and where to look from. {@code yaw} — approach course (each case has its own, so that the crater does not fall in one
     *  place); {@code target} - only x and z, the server knows the altitude.
     */
    private record Geometry(float yaw, Vec3 target, Vec3 listenerFacing) {}

    private Geometry geometry() {
        return switch (current) {
            case "approach" -> {
                // target in 20 blocks in front and 30 on the side: the missile comes towards the viewer and passes nearby
                float yaw = 0;
                Vec3 dir = Local.horizontal(yaw), side = perp(dir);
                yield new Geometry(yaw, ear.add(dir.scale(20)).add(side.scale(30)), ear.subtract(dir.scale(1000)));
            }
            case "dogleg" -> {
                // route with a bypass (like a launch with a launcher, but without a turn on the spot): launch in 2000 blocks to the target on
                //  direct approach, entry point at 500, path 2400 - bypass point in ~580 blocks on the side, shoulders at ~40° to straight;
                // spectator in 40 blocks from the middle of the first shoulder (it goes away from the target)
                float yaw = 90;
                Vec3 dir = Local.horizontal(yaw);
                Vec3 start = Vec3.ZERO.subtract(dir.scale(DOGLEG_START));
                Vec3 m = doglegRoute(start, Vec3.ZERO, dir).points().getFirst();
                Vec3 leg = m.subtract(start.x, 0, start.z);
                Vec3 mid = start.add(leg.scale(0.5)).add(perp(leg.normalize()).scale(40));
                Vec3 target = ear.subtract(mid.x, 0, mid.z);
                yield new Geometry(yaw, target, target.add(start));
            }
            case "pass" -> {
                // target at 700 blocks in front and 60 to the side: the missile passes by the viewer, not at him
                float yaw = 180;
                Vec3 dir = Local.horizontal(yaw), side = perp(dir);
                yield new Geometry(yaw, ear.add(dir.scale(700)).add(side.scale(-60)), ear.subtract(dir.scale(1000)));
            }
            case "grad" -> {
                // launcher without a shooter nearby stands 600 blocks to the target: the viewer is in the middle of the arc, 20 blocks on the side
                float yaw = 270;
                Vec3 dir = Local.horizontal(yaw), side = perp(dir);
                yield new Geometry(yaw, ear.add(dir.scale(300)).add(side.scale(20)), ear.subtract(dir.scale(300)));
            }
            case "far" -> {
                // rocket and Shahed due to the range of the entities directly at the viewer, the target is 150 blocks behind him
                float yaw = 45;
                Vec3 dir = Local.horizontal(yaw), side = perp(dir);
                yield new Geometry(yaw, ear.add(dir.scale(150)).add(side.scale(10)), ear.subtract(dir.scale(1000)));
            }
            default -> throw new IllegalStateException(current);
        };
    }

    private static final double DOGLEG_START = 2000;
    /** The missile of cases approach, pass and far was launched from afar so many blocks from the target. */
    private static final double MISSILE_FROM = 1840;

    /** Rocket flight time (setting, seconds), at which it launches {@link #MISSILE_FROM} blocks from the target. */
    private static int missileSeconds() {
        return (int) Math.ceil(MISSILE_FROM / (WeaponSpec.MISSILE.airframe().cruiseSpeed() * 20));
    }

    private static Route doglegRoute(Vec3 start, Vec3 target, Vec3 dir) {
        return Route.plan(start, target, dir, 2400, 500, 1);
    }

    private static Vec3 perp(Vec3 dir) {
        return new Vec3(-dir.z, 0, dir.x);
    }

    /** launch of case shells - on the server stream, from the server console (approach course - command rotation). */
    private void fire(MinecraftServer server) {
        Geometry g = geometry();
        String name = current;
        server.execute(() -> {
            ServerLevel level = server.overworld();
            Vec3 t = Target.Ground.at(level, g.target().x, g.target().z).pos();
            String at = String.format(Locale.ROOT, "at %.1f %.1f %.1f", t.x, t.y, t.z);
            String rotated = String.format(Locale.ROOT, "execute rotated %.0f 0 run incoming ", g.yaw());
            var c = IncomingConfig.SERVER;
            switch (name) {
                case "approach", "pass" -> {
                    c.missileFlightTime.set(missileSeconds());
                    run(server, rotated + "missile " + at);
                }
                case "dogleg" -> {
                    // a route with a detour occurs only for launch with a launcher next to the shooter; here it is specified directly
                    StrikeProjectile p = ModEntities.CRUISE_MISSILE.get().create(level);
                    if (p == null) return;
                    Vec3 dir = Local.horizontal(g.yaw());
                    Target.Ground target = Target.Ground.at(level, t.x, t.z);
                    Vec3 start = target.pos().subtract(dir.scale(DOGLEG_START)).add(0, 12, 0);
                    p.launch(start, target, target.pos(), null);
                    Route route = doglegRoute(start, target.pos(), dir);
                    p.setRoute(route);
                    // with a launcher, the missile goes straight to the first point of the route, and not to the target
                    float yaw = ua.zentix.incoming.guidance.FlightController.anglesTo(start, route.points().getFirst().add(0, start.y, 0))[0];
                    try {
                        ((ua.zentix.incoming.guidance.FlightController) FLIGHT.get(p)).set(yaw, 0);
                    } catch (ReflectiveOperationException ex) {
                        throw new IllegalStateException("SCENARIO flyby: rocket course cannot be set", ex);
                    }
                    p.moveTo(start.x, start.y, start.z, yaw, 0);
                    VirtualFlights.launch(level, p);
                    Incoming.LOG.info("SCENARIO flyby dogleg route {} → {}", xyz(start), p.getUUID());
                }
                case "grad" -> {
                    // at night: during the day, the howl of a projectile at the current speed (~80 m/s) drowns in the background already behind ~270 blocks, and the launcher
                    //  in 300 blocks (Hearing according to the sound spec)
                    run(server, "time set 18000");
                    run(server, rotated + "salvo rocket " + GRAD + " 150 " + at);
                }
                case "far" -> {
                    run(server, "time set 6000");
                    c.missileFlightTime.set(missileSeconds());
                    c.droneFlightTime.set(30);
                    run(server, rotated + "drone " + at);
                    run(server, rotated + "missile " + at);
                }
                default -> throw new IllegalStateException(name);
            }
            Incoming.LOG.info("SCENARIO mark {} fire target={} game={} ms={}", name, xyz(t), level.getGameTime(), System.currentTimeMillis());
        });
    }

    private static void run(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        Incoming.LOG.info("SCENARIO /{}", command);
    }

    // ---------------------------------------------------------------- server: launch and hits

    /** Projectiles on the server (in the world and outside of it): marks for launch, entry into the world and exit, impact. */
    private void scan(ServerLevel level) {
        Map<UUID, Seen> now = new LinkedHashMap<>();
        for (Entity e : level.getAllEntities()) {
            if (e instanceof StrikeProjectile p && !p.isRemoved()) now.put(p.getUUID(), new Seen(p.weapon().getSerializedName(), false, p.position()));
        }
        for (StrikeProjectile p : VirtualFlights.get(level).flights()) now.put(p.getUUID(), new Seen(p.weapon().getSerializedName(), true, p.position()));
        String c = serverCase;
        for (var en : now.entrySet()) {
            Seen was = seen.get(en.getKey()), is = en.getValue();
            if (was == null) {
                if (!"-".equals(c)) caseOf.put(en.getKey(), c);
                serverMark(level, c, "launch", en.getKey(), is);
            } else if (was.virtual() && !is.virtual()) {
                enteredAt.put(en.getKey(), serverCaseTick);
                serverMark(level, c, "enter-world", en.getKey(), is);
            } else if (!was.virtual() && is.virtual()) {
                serverMark(level, c, "leave-world", en.getKey(), is);
            }
            // the projectile stands still (the projectile does not stand still in flight): the sound in the packets then fades out, this can be seen in gain
            long game = level.getGameTime();
            if (was == null || was.pos().distanceToSqr(is.pos()) > 1e-4) {
                if (stalled.remove(en.getKey())) serverMark(level, c, "move", en.getKey(), is);
                movedAt.put(en.getKey(), game);
            } else if (game - movedAt.getOrDefault(en.getKey(), game) >= STALL && stalled.add(en.getKey())) {
                serverMark(level, c, "stall", en.getKey(), is);
            }
            alive.put(en.getKey(), true);
        }
        for (var en : seen.entrySet()) {
            if (now.containsKey(en.getKey())) continue;
            alive.remove(en.getKey());
            movedAt.remove(en.getKey());
            stalled.remove(en.getKey());
            goneAt.put(en.getKey(), serverCaseTick);
            serverMark(level, c, "gone", en.getKey(), en.getValue());
        }
        seen.clear();
        seen.putAll(now);
    }

    private void serverMark(ServerLevel level, String c, String what, UUID id, Seen s) {
        Incoming.LOG.info("SCENARIO mark {} {} {}#{} virtual={} pos={} d={} game={} ms={}", c, what, s.weapon(), shortId(id), s.virtual(), xyz(s.pos()),
                String.format(Locale.ROOT, "%.0f", s.pos().distanceTo(ear)), level.getGameTime(), System.currentTimeMillis());
    }

    // ---------------------------------------------------------------- client: what do you hear?

    /** All sounding layers of motors (without threshold): loops, their start and stop, volume line once per 5 tick. */
    private void probe(Minecraft mc) {
        serverCaseTick = caseTick;
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        Map<SoundInstance, Loop> now = new IdentityHashMap<>();
        StringBuilder line = new StringBuilder();
        for (SoundInstance s : Set.copyOf(mc.getSoundManager().soundEngine.instanceToChannel.keySet())) {
            if (!ENGINE.isInstance(s)) continue;
            Loop loop = loops.get(s);
            boolean fromServer;
            try {
                FlightTrack track = (FlightTrack) TRACK.get(s);
                if (loop == null) loop = new Loop(track.id, track.weapon.toString().toLowerCase(Locale.ROOT),
                        LAYER.get(s).toString().toLowerCase(Locale.ROOT), caseTick);
                fromServer = track.fromServer();
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("SCENARIO flyby: mod sound fields have changed", ex);
            }
            // volume against the standard (Loudness.gain): the threshold HEARD compares with it, the file volume has nothing to do with it
            double vol = s.getSound() != null ? ((ua.zentix.incoming.client.sound.EngineSound) s).loudness() : 0, pitch = s.getPitch();
            double d = Math.sqrt((s.getX() - cam.x) * (s.getX() - cam.x) + (s.getY() - cam.y) * (s.getY() - cam.y) + (s.getZ() - cam.z) * (s.getZ() - cam.z));
            if (!loops.containsKey(s)) {
                Incoming.LOG.info("SCENARIO loop start {} {}#{} {} src={} d={} vol={} t={} ms={}", current, loop.weapon, shortId(loop.id), loop.layer,
                        fromServer ? "s" : "e", f0(d), f3(vol), caseTick, System.currentTimeMillis());
                events.add("start " + loop.id + " " + loop.layer + " " + caseTick);
            }
            loop.peak = Math.max(loop.peak, vol);
            now.put(s, loop);
            samples.add(new Sample(caseTick, loop.id, loop.weapon, loop.layer, fromServer, vol, pitch, d));
            line.append(String.format(Locale.ROOT, " %s#%s(%s d=%.0f) %s %.3f×%.2f;", loop.weapon, shortId(loop.id), fromServer ? "s" : "e", d, loop.layer, vol, pitch));
        }
        for (var en : loops.entrySet()) {
            if (now.containsKey(en.getKey())) continue;
            Loop l = en.getValue();
            Incoming.LOG.info("SCENARIO loop stop {} {}#{} {} lived={} peak={} t={} ms={}", current, l.weapon, shortId(l.id), l.layer, caseTick - l.started,
                    f3(l.peak), caseTick, System.currentTimeMillis());
            events.add("stop " + l.id + " " + l.layer + " " + caseTick);
        }
        loops = now;
        maxLive = Math.max(maxLive, now.size());
        if (caseTick % 5 == 0) {
            Incoming.LOG.info("SCENARIO gain {} t={} ms={} live={} |{}", current, caseTick, System.currentTimeMillis(), now.size(), line);
        }
    }

    /** One-time mod sounds (launch, separation, explosions): when the engine was told to play them. */
    private void onPlay(PlaySoundEvent e) {
        SoundInstance s = e.getSound();
        if (s == null || stage != Stage.RUN && stage != Stage.RINGOUT || s.isLooping() || !Incoming.MOD_ID.equals(s.getLocation().getNamespace())) return;
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        String path = s.getLocation().getPath();
        if (path.startsWith("blast.")) blasts.add(caseTick);
        Incoming.LOG.info("SCENARIO play {} {} d={} t={} ms={}", current, path, f0(new Vec3(s.getX(), s.getY(), s.getZ()).distanceTo(cam)),
                caseTick, System.currentTimeMillis());
    }

    // ---------------------------------------------------------------- outcome of the case

    private void finish() {
        mark("end", String.format(Locale.ROOT, "max_live=%d loops_started=%d", maxLive, events.stream().filter(s -> s.startsWith("start")).count()));
        check("max_live", maxLive <= 48 ? "PASS" : "FAIL", "max_live=" + maxLive + " (limit 48)");
        int restarts = restarts();
        check("restarts", restarts == 0 ? "PASS" : "WARN", "loop of the same layer of the same projectile is started again ≤40 tick after stopping: " + restarts);
        switch (current) {
            case "approach" -> checkApproach();
            case "dogleg" -> checkDogleg();
            case "pass" -> checkPass();
            case "grad" -> checkGrad();
            case "far" -> checkFar();
            default -> { }
        }
    }

    private void checkApproach() {
        List<Sample> w = layer("missile_whistle");
        Sample first = w.stream().filter(s -> s.vol >= HEARD).findFirst().orElse(null);
        if (first == null) {
            check("whistle_heard", "FAIL", "there was no whine of the fan on approach");
            return;
        }
        Integer impact = impactOf("missile");
        int warning = impact == null ? -1 : impact - first.t;
        // sound overtakes the rocket by d·(1/v − 1/c): from 1500 blocks — ~290 tick
        int expected = (int) (first.d * (1 / WeaponSpec.MISSILE.airframe().cruiseSpeed() - 1 / ua.zentix.incoming.client.sound.Acoustics.SPEED));
        check("whistle_from", first.d >= 1000 ? "PASS" : "FAIL", "howl can be heard from " + f0(first.d) + " blocks (audible point), volume " + f3(first.vol));
        check("whistle_warning", warning >= expected * 0.8 ? "PASS" : "FAIL", "howl for " + warning + " tick until hit (waiting ~" + expected + ")");
        // the entire motor (near the howl of the fan - already the closest recording in front) closer is louder
        double far = enginePower(800, 1300), near = enginePower(60, 400);
        check("engine_rises", near > far ? "PASS" : "FAIL", "motor away (800–1300) " + f3(far) + ", near (60–400) " + f3(near));
    }

    /** The highest volume of the rocket motor (all layers by power) is in tick, when the audible point is between a and b blocks. */
    private double enginePower(double a, double b) {
        Map<Integer, double[]> byTick = new java.util.TreeMap<>();
        for (Sample s : samples) {
            if (!s.layer.startsWith("missile_")) continue;
            double[] p = byTick.computeIfAbsent(s.t, k -> new double[] {0, s.d});
            p[0] += s.vol * s.vol;
        }
        return Math.sqrt(byTick.values().stream().filter(p -> p[1] >= a && p[1] <= b).mapToDouble(p -> p[0]).max().orElse(0));
    }

    private void checkDogleg() {
        // the rocket on its way around goes past the viewer on a course away from the target: the engine is audible, and only by distance and angle -
        //  the blow of the fan forward along the course does not depend on where it is flying
        double engine = samples.stream().filter(s -> s.layer.startsWith("missile_")).mapToDouble(Sample::vol).max().orElse(0);
        check("engine_heard", engine >= HEARD ? "PASS" : "FAIL", "motor on bypass: " + f3(engine));
    }

    private void checkPass() {
        List<Sample> w = layer("missile_whistle");
        Sample peak = w.stream().max((a, b) -> Double.compare(a.vol, b.vol)).orElse(null);
        if (peak == null || peak.vol < HEARD) {
            check("whistle_heard", "FAIL", "howl on approach " + (peak == null ? 0 : f3(peak.vol)));
            return;
        }
        int fade = w.stream().filter(s -> s.t > peak.t && s.vol < 0.1 * peak.vol).mapToInt(Sample::t).findFirst().orElse(Integer.MAX_VALUE) - peak.t;
        check("whistle_fades", fade >= 6 ? "PASS" : "FAIL", "after the peak " + f3(peak.vol) + " up to 10% for " + (fade == Integer.MAX_VALUE - peak.t ? "∞" : fade) + " tick");
        // motor: the sum of the powers of the layers does not fall on approach, and does not increase afterwards (a margin of 1 dB for volume smoothing);
        //  layers share the same power by distance and angle (EngineSound.share), so its course is a function of distance
        //  and closer: checking up to 30 blocks (even closer the audible point changes faster than the volume smoothing)
        final double nearZone = 30;
        Map<Integer, double[]> byTick = new java.util.TreeMap<>();
        for (Sample s : samples) {
            if (!s.layer.startsWith("missile_")) continue;
            double[] a = byTick.computeIfAbsent(s.t, k -> new double[] {0, Double.MAX_VALUE});
            a[0] += s.vol * s.vol;
            a[1] = Math.min(a[1], s.d);
        }
        int bad = 0;
        double prevP = -1, prevD = -1;
        String worst = "";
        for (double[] a : byTick.values()) {
            if (prevP > 1e-6 && a[0] > 1e-6 && Math.min(prevD, a[1]) >= nearZone) {
                double db = 10 * Math.log10(a[0] / prevP);
                // the distance is standing (the path has not yet begun, the sound of the first point is growing) - there is nothing to compare with
                if (Math.abs(a[1] - prevD) < 0.5) {
                    prevP = a[0];
                    continue;
                }
                boolean closer = a[1] < prevD;
                if (closer && db < -1 || !closer && db > 1) {
                    bad++;
                    worst = String.format(Locale.ROOT, "%.1f dB at %.0f→%.0f blocks", db, prevD, a[1]);
                }
            }
            prevP = a[0];
            prevD = a[1];
        }
        check("engine_even", bad == 0 ? "PASS" : "FAIL", "motor power surges versus distance: " + bad + (bad > 0 ? ", last " + worst : ""));
        // the motor of a long-range rocket (path through server packets) sounds until the explosion reaches your ear: without a pause of silence in front of it
        int blast = blasts.stream().mapToInt(Integer::intValue).min().orElse(-1);
        int stop = events.stream().filter(ev -> ev.startsWith("stop ")).mapToInt(ev -> Integer.parseInt(ev.substring(ev.lastIndexOf(' ') + 1))).max().orElse(-1);
        check("engine_until_blast", blast >= 0 && stop >= blast ? "PASS" : "FAIL", "motor stopped at tick " + stop + ", explosion - on tick " + blast);
    }

    private void checkGrad() {
        // the howl of a projectile due to inertia - the noise of the flow, ∝ U⁶ (Noise.Body): at the current speed (40–80 m/s, ten times lower than the current
        // ) it can be heard only in a hundred or two blocks, and only shells can hear it, passing nearby; at a real
        //  speed it is louder by tens of decibels - then the Mach cone cracks.
        List<UUID> rockets = caseOf.entrySet().stream().filter(en -> en.getValue().equals(current)).map(Map.Entry::getKey).toList();
        long heard = rockets.stream().filter(id -> samples.stream().anyMatch(s -> s.id.equals(id) && s.layer.equals("rocket_air") && s.vol >= HEARD)).count();
        check("rockets_howl", heard > 0 ? "PASS" : "FAIL", "howl after burnout " + heard + " from " + rockets.size() + " (ordered " + GRAD + ")");
        // beyond the indicated range (according to the sound spec, at night, a projectile up to 200 blocks above the ear, speed - up to one and a half
        //  marching) the howl is not heard
        ua.zentix.incoming.sound.Noise noise = WeaponSpec.ROCKET.airframe().noise();
        double range = ua.zentix.incoming.strike.Hearing.range(noise, ua.zentix.incoming.entity.FlightPhase.CRUISE, 100,
                WeaponSpec.ROCKET.airframe().cruiseSpeed() * 1.5, false, 200, ua.zentix.incoming.sound.Propagation.Weather.NIGHT);
        double farthest = samples.stream().filter(s -> s.layer.equals("rocket_air") && s.vol >= HEARD).mapToDouble(Sample::d).max().orElse(0);
        check("howl_within_range", farthest <= range ? "PASS" : "FAIL", "howl can be heard from " + f0(farthest) + " blocks, audibility - up to " + f0(range));
    }

    private void checkFar() {
        List<UUID> ids = caseOf.entrySet().stream().filter(en -> en.getValue().equals(current)).map(Map.Entry::getKey).toList();
        for (UUID id : ids) {
            List<Sample> mine = samples.stream().filter(s -> s.id.equals(id)).toList();
            String weapon = mine.isEmpty() ? "?" : mine.getFirst().weapon;
            int handover = mine.stream().filter(s -> !s.server).mapToInt(Sample::t).min().orElse(-1);
            boolean fromServer = mine.stream().anyMatch(s -> s.server);
            if (!fromServer || handover < 0) {
                check("handover_" + weapon, "WARN", weapon + "#" + shortId(id) + ": transition from packages to entity is not visible (s=" + fromServer + ", e=" + (handover >= 0) + ")");
                continue;
            }
            // the projectile goes towards the viewer: during the transition the power of the layers should not subside (new layers nearby - no restart)
            long restartsNear = restarts(id, handover - 20, handover + 20);
            double before = power(mine, handover - 3, handover - 1), dip = Double.MAX_VALUE;
            for (int t = handover; t <= handover + 5; t++) dip = Math.min(dip, power(mine, t, t));
            double db = before > 0 && dip > 0 ? 10 * Math.log10(dip / before) : 0;
            boolean ok = restartsNear == 0 && db >= -1.5;
            check("handover_" + weapon, ok ? "PASS" : "FAIL", String.format(Locale.ROOT,
                    "%s#%s: entity with t=%d (server: in the world with t=%s), restarts layers nearby %d, power failure for 5 tick %.1f dB",
                    weapon, shortId(id), handover, enteredAt.get(id), restartsNear, db));
        }
    }

    /** Average power of all projectile layers per tick [a, b]. */
    private static double power(List<Sample> mine, int a, int b) {
        return mine.stream().filter(s -> s.t >= a && s.t <= b).mapToDouble(s -> s.vol * s.vol).sum() / (b - a + 1);
    }

    private int restarts() {
        return restarts(null, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /** The projectile layer is started again no later than 40 tick after stopping (at {@code id}, if set, at tick [a, b]). */
    private int restarts(UUID id, int a, int b) {
        Map<String, Integer> stopped = new HashMap<>();
        int n = 0;
        for (String ev : events) {
            String[] p = ev.split(" ");
            if (id != null && !p[1].equals(id.toString())) continue;
            String key = p[1] + " " + p[2];
            int t = Integer.parseInt(p[3]);
            if (p[0].equals("stop")) stopped.put(key, t);
            else if (stopped.containsKey(key) && t - stopped.get(key) <= 40 && t >= a && t <= b) n++;
        }
        return n;
    }

    private Integer impactOf(String weapon) {
        return caseOf.entrySet().stream().filter(en -> en.getValue().equals(current)).map(Map.Entry::getKey)
                .filter(id -> samples.stream().anyMatch(s -> s.id.equals(id) && s.weapon.equals(weapon))).map(goneAt::get)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private List<Sample> layer(String layer) {
        return samples.stream().filter(s -> s.layer.equals(layer)).toList();
    }

    private static double maxVol(List<Sample> list, double dMin, double dMax) {
        return list.stream().filter(s -> s.d >= dMin && s.d <= dMax).mapToDouble(Sample::vol).max().orElse(0);
    }

    private void check(String what, String verdict, String detail) {
        Incoming.LOG.info("SCENARIO check {} {} {} {}", current, what, verdict, detail);
    }

    private void mark(String what, String detail) {
        Incoming.LOG.info("SCENARIO mark {} {} t={} ms={} {}", current, what, caseTick, System.currentTimeMillis(), detail);
    }

    // ---------------------------------------------------------------- little things

    private static String name() {
        return Minecraft.getInstance().player.getGameProfile().getName();
    }

    private static void server(String command) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        server.execute(() -> run(server, command));
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static String xyz(Vec3 v) {
        return String.format(Locale.ROOT, "%.0f %.0f %.0f", v.x, v.y, v.z);
    }

    private static String f0(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }

    private static String f3(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    // layer and loop path are closed in the sound mod (package client.sound) - via reflection
    private static final Class<?> ENGINE;
    private static final Field TRACK, LAYER, FLIGHT;

    static {
        try {
            ENGINE = Class.forName("ua.zentix.incoming.client.sound.EngineSound");
            TRACK = open(ENGINE.getDeclaredField("track"));
            LAYER = open(ENGINE.getDeclaredField("layer"));
            FLIGHT = open(StrikeProjectile.class.getDeclaredField("flight"));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Field open(Field f) {
        f.setAccessible(true);
        return f;
    }
}
