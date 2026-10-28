package ua.zentix.replayscan.film;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Plan-based filming ({@code -Dreplayscan.film=<plan>}, README.md): open replay, build camera candidates
 * (player POV, fixed, circular tripods) per shot, score against replay world ({@link Cameras#score}),
 * export best candidates in order through Flashback with per-frame checks ({@link FrameLog}). First pass
 * completes the shot; if none passes, retain best exported candidate with reasons. Scoring needs world at
 * cameras: viewer first stands at fixed-camera eye (distant subject outside replay chunks, camera inside),
 * otherwise at main site. Before export, traverse camera path to build sections it will see, stand at
 * start until world is built, then play {@code warm} ticks live up to start, so earlier impact smoke/fire
 * appear as in game; with Distant Horizons also wait for DH textures to differ from frame size ({@link DhDepth}).
 * <p>Replay world contains chunks already read during this client run: seeking does not forget them
 * (no "forget chunk" packet recorded); a chunk snapshot only restores recorder chunks at its start.
 * Prime from beginning in steps no longer than {@link #PRIME_STEP} (Flashback plays packets without
 * snapshot), shots chronologically. Free camera then has everything shown before the shot, like watching
 * from start; direct jump would leave only chunks around recorder at that minute.
 * <p>Output {@code <out>/<shot>/<camera>/}: {@code video.mkv}, {@code state.json} editor state,
 * {@code qa.jsonl}, {@code sounds.jsonl}, {@code verdict.json}; {@code <out>/report.jsonl} per exported
 * candidate, {@code <out>/best.json} shot → best with ticks. Already passed identical-tick shots in
 * {@code best.json} are skipped on restart after crash; failed/changed shots recapture. Log {@code Film: …};
 * client exits at end, immediately if plan unreadable.
 */
public final class Film {
    static final Logger LOG = LogUtils.getLogger();
    /**
     * tick: opening replay, after opening, after rewinding, the world is in place, stand at a waypoint, find a player, start of the
     *  output task, the replay does not go until the start of the plan.
     */
    private static final int OPEN_WAIT = 4800, SETTLE = 60, SEEK_SETTLE = 30, WORLD_WAIT = 600, POSE_MIN = 6, POSE_MAX = 300, PLAYER_WAIT = 400,
            EXPORT_WAIT = 1200, STALL = 600;
    /**
     * Replay step forward to plan, tick: Flashback jumps to the piece snapshot only when the target is further than the end of the piece and not
     *  closer to 1200 tick ({@code ReplayServer.handleActions}); closer - loses all actions between.
     */
    private static final int PRIME_STEP = 1200;

    private enum State { TITLE, OPENING, SETTLING, PRIMING, SEEKING, WALKING, WARMING, EXPORTING, DONE }

    private final Minecraft mc = Minecraft.getInstance();
    private final Plan plan;
    private final double aspect;
    private final ArrayDeque<Plan.Shot> queue = new ArrayDeque<>();
    private final JsonObject best;
    private final Smoke smoke = new Smoke();
    private State state = State.TITLE;
    private int ticks, stateAt;

    // plan in progress
    private Plan.Shot shot;
    private final ArrayDeque<Scored> candidates = new ArrayDeque<>();
    private Scored current;
    @Nullable
    private Scored bestTry;
    @Nullable
    private FrameLog.Verdict bestVerdict;
    @Nullable
    private FrameLog log;
    private Path dir;
    private final ArrayDeque<Cameras.Pose> walk = new ArrayDeque<>();
    private Cameras.Pose standing;
    @Nullable
    private UUID povId;
    /** Search for a plan player on the replay server ({@link #lookup}); null - does not work. */
    @Nullable
    private CompletableFuture<UUID> povLookup;
    private int poseAt, stepped, reachedAt, seenAt, progressAt;
    /** Until what tick did the replay go from the beginning without a snapshot (all recording chunks before it are in the replay world); goal and step. */
    private int primed, primeGoal, primeTo, primeStart;
    /** The step was already sent again after the replay was stopped. */
    private boolean primeRetried;
    /** Chunks at the client’s plan location (for the pilot) and from what tick there are so many of them. */
    private int nearChunks = -1, nearSince;
    /** The client's world before the replay is opened: the opening is the client's task, and the tick can still go in the previous world. */
    private WeakReference<ClientLevel> levelBefore = new WeakReference<>(null);

    private record Scored(Cameras.Candidate c, Cameras.Score score) {}

    private Film(Plan plan, IEventBus modBus) {
        this.plan = plan;
        aspect = (double) plan.width() / plan.height();
        best = readBest(plan.out().resolve("best.json"));
        // by time: replay to each plan goes forward from the beginning ({@link #prime})
        for (Plan.Shot s : plan.shots().stream().sorted(Comparator.comparingInt(Plan.Shot::a)).toList()) {
            if (filmed(s)) continue;
            queue.add(s);
        }
        LOG.info("Film: {} -> {}, {} shots to film: {}", plan.replay(), plan.out(), queue.size(), queue.stream().map(Plan.Shot::name).toList());
        LOG.info("Film: smoke of the effects layer {}", smoke.available() ? "checked" : "not checked (Incoming effects layer not found)");
        if (FrameLog.DH) {
            DhDepth.subscribe();
            LOG.info("Film: depth of Distant Horizons LODs checked in every frame");
        }
        // packets to the replay viewer after entering - when the client has peace (up to onTick: leave and OPENING - in one tick)
        ViewerHold.register(modBus);
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::quietCamera);
        NeoForge.EVENT_BUS.addListener(this::onStage);
    }

    /** Entity effect particles - a list sent by the server ({@code LivingEntity.DATA_EFFECT_PARTICLES}); null - no field. */
    @Nullable
    private static final EntityDataAccessor<List<ParticleOptions>> EFFECT_PARTICLES = effectParticles();

    @SuppressWarnings("unchecked")
    @Nullable
    private static EntityDataAccessor<List<ParticleOptions>> effectParticles() {
        try {
            Field f = LivingEntity.class.getDeclaredField("DATA_EFFECT_PARTICLES");
            f.setAccessible(true);
            return (EntityDataAccessor<List<ParticleOptions>>) f.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("Film: effect particles stay at the players'-eyes camera ({})", e.toString());
            return null;
        }
    }

    /**
     * Through the eyes of the player, particles of his effects (spirals of potions: tick effects sprinkles them along his frame, and at the height of the eye)
     *  are born at the camera itself and cover half the frame: at the beginning of the client's tick, the camera entity's list of effect particles
     *  is empty, and tick effects do not generate them. The rest of the entities are as they were.
     */
    private void quietCamera(ClientTickEvent.Pre e) {
        if (EFFECT_PARTICLES != null && mc.level != null && mc.getCameraEntity() instanceof LivingEntity camera && camera != mc.player
                && !camera.getEntityData().get(EFFECT_PARTICLES).isEmpty()) {
            camera.getEntityData().set(EFFECT_PARTICLES, List.of());
        }
    }

    /** Shooting according to plan {@code file}: subscriptions to client tick and frame; then everything goes on its own. */
    public static void start(Path file, IEventBus modBus) {
        try {
            Export.check();
        } catch (ReflectiveOperationException | LinkageError e) {
            LOG.error("Film: Flashback 0.39 is required ({})", e.toString());
            exitWhenLoaded();
            return;
        }
        try {
            Plan plan = Plan.read(file);
            Files.createDirectories(plan.out());
            new Film(plan, modBus);
        } catch (IOException | RuntimeException | LinkageError e) {
            LOG.error("Film: cannot start with plan {}", file, e);
            exitWhenLoaded();
        }
    }

    /** A client without a window will not exit on its own: exit as soon as the game has loaded. */
    private static void exitWhenLoaded() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post t) -> {
            if (Minecraft.getInstance().getOverlay() == null) Minecraft.getInstance().stop();
        });
    }

    /** The plan has already been removed: in {@code best.json} it passed the test with the same tick and the first event. */
    private boolean filmed(Plan.Shot s) {
        if (!best.has(s.name())) return false;
        JsonObject o = best.getAsJsonObject(s.name());
        return o.has("pass") && o.get("pass").getAsBoolean() && o.has("a") && o.get("a").getAsInt() == s.a()
                && o.has("b") && o.get("b").getAsInt() == s.b() && o.has("first") && o.get("first").getAsInt() == s.subject().firstKey();
    }

    private static JsonObject readBest(Path file) {
        try {
            return Files.exists(file) ? JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void go(State s) {
        state = s;
        stateAt = ticks;
    }

    private int waited() {
        return ticks - stateAt;
    }

    private void onStage(RenderLevelStageEvent e) {
        if (log != null) log.onStage(e);
    }

    private void onTick(ClientTickEvent.Post e) {
        if (state == State.DONE) return;
        ticks++;
        try {
            tick();
        } catch (RuntimeException ex) {
            LOG.error("Film: {} failed", shot == null ? "-" : shot.name() + (current == null ? "" : " " + current.c.name()), ex);
            if (log != null) {
                log.close();
                log = null;
                Export.flawless(false);
            }
            if (state == State.TITLE || state == State.OPENING) {
                finish();
            } else if (current != null) {
                report(current, null, "error: " + ex);
                nextCandidate();
            } else {
                nextShot();
            }
        }
    }

    private void tick() {
        switch (state) {
            case TITLE -> {
                // loading finished: title or screen over it; replay will replace them
                if (mc.getOverlay() != null || mc.screen == null || mc.level != null) return;
                if (windowIsFrame()) {
                    finish();
                    return;
                }
                mc.options.onboardAccessibility = false;
                mc.options.pauseOnLostFocus = false;
                mc.options.renderDistance().set(plan.distance());
                mc.options.save();
                levelBefore = new WeakReference<>(mc.level);
                Export.open(plan.replay());
                go(State.OPENING);
            }
            case OPENING -> {
                // the world of replay (not the same), and his viewer passed the entrance - packages after entering him
                if (mc.level != null && mc.level != levelBefore.get() && mc.player != null && Export.inReplay()
                        && ViewerHold.releasedFor(mc.getConnection())) {
                    // Flashback editor must have GUI inactive for an export job to start
                    mc.options.hideGui = true;
                    go(State.SETTLING);
                } else if (waited() > OPEN_WAIT) {
                    LOG.error("Film: replay {} did not open (viewer packets: {})", plan.replay(), ViewerHold.state());
                    finish();
                }
            }
            case SETTLING -> {
                if (waited() >= SETTLE) nextShot();
            }
            case PRIMING -> {
                if (Export.tick() != primeTo) {
                    if (waited() > STALL && !primeRetried) {
                        primeRetried = true;
                        Export.seek(primeTo);
                        go(State.PRIMING);
                    } else if (waited() > STALL) {
                        // Do not advance primed past the played section: next shot primes again from primed
                        LOG.warn("Film: {}: replay stopped at tick {} on the way to {}; the replay world has chunks only up to tick {}",
                                shot.name(), Export.tick(), primeTo, primed);
                        Export.seek(primeGoal);
                        go(State.SEEKING);
                    }
                    return;
                }
                primeRetried = false;
                primed = Math.max(primed, primeTo);
                if (primeTo >= primeGoal) {
                    LOG.info("Film: {}: replay played from the start to tick {} in {} ticks", shot.name(), primeGoal, ticks - primeStart);
                    go(State.SEEKING);
                    return;
                }
                primeTo = Math.min(primeGoal, primeTo + PRIME_STEP);
                Export.seek(primeTo);
                go(State.PRIMING);
            }
            case SEEKING -> {
                if (waited() < SEEK_SETTLE) return;
                if (!mc.level.dimension().location().toString().equals(shot.dim())) {
                    fail("camera is in " + mc.level.dimension().location() + ", shot in " + shot.dim());
                    return;
                }
                Vec3 k = shot.subject().anchor(shot.a(), shot.b());
                Cameras.World w = new Cameras.World(Cameras.cloudHeight(plan));
                // World at fixed-camera eye (flying camera path start), plus site for nearby shots: distant subject
                // outside replay chunks cannot appear and is not needed at the eye
                Plan.Camera fixed = shot.cameras().stream().filter(c -> (c.kind().equals("fixed") || c.kind().equals("path")) && c.eye() != null)
                        .findFirst().orElse(null);
                boolean ready = (fixed == null || w.loaded(fixed.eye())) && (k == null || shot.far() && fixed != null || w.loaded(k));
                // Pilot plans around impact terrain: replay-server chunks arrive asynchronously;
                // wait for stable chunk count over PILOT_SETTLE ticks
                if (ready && k != null && shot.cameras().stream().anyMatch(c -> c.kind().equals("pilot"))) {
                    int n = loadedAround(w, k, PILOT_CHUNKS);
                    if (n != nearChunks) {
                        nearChunks = n;
                        nearSince = ticks;
                    }
                    if (ticks - nearSince < PILOT_SETTLE) ready = false;
                }
                if (!ready && waited() < WORLD_WAIT) {
                    if (fixed != null) standAt(Cameras.Pose.look(fixed.eye(), fixed.aim() != null ? fixed.aim() : k != null ? k : fixed.eye().add(0, 0, 1), 70));
                    else standAt(Cameras.Pose.look(k.add(0, 30, -30), k, 70));
                    return;
                }
                solve(w);
            }
            case WALKING -> {
                if (current.c.player() != null) {
                    Entity target = watch();
                    if (target == null) {
                        if (waited() > PLAYER_WAIT) {
                            report(current, null, "player " + current.c.player() + " not found");
                            nextCandidate();
                        }
                        return;
                    }
                    if (ticks - poseAt < Math.max(POSE_MIN, shot.hold()) || !built() && ticks - poseAt < POSE_MAX) return;
                    warm();
                    return;
                }
                standAt(standing);
                boolean last = walk.isEmpty();
                int at = ticks - poseAt;
                if (at < (last ? Math.max(POSE_MIN, shot.hold()) : POSE_MIN) || !built() && at < POSE_MAX) return;
                if (!built()) LOG.warn("Film: {}: world not built in {} ticks at {}", shot.name(), POSE_MAX, standing.pos());
                if (!last) {
                    standing = walk.poll();
                    poseAt = ticks;
                    return;
                }
                warm();
            }
            case WARMING -> {
                // Play live tick by tick up to shot start like export itself: mod smoke/columns/fire events are live;
                // export at the same position does not seek or clear particles
                if (current.c.player() != null) {
                    if (watch() == null) {
                        // Recorded player left client world (death, another dimension, beyond render distance)
                        if (ticks - seenAt > PLAYER_WAIT) {
                            report(current, null, "player " + current.c.player() + " lost before the start");
                            nextCandidate();
                        }
                        return;
                    }
                } else {
                    standAt(standing);
                }
                int at = Export.tick();
                if (at < shot.a()) {
                    if (at != stepped) {
                        stepped = at;
                        progressAt = ticks;
                        Export.seek(at + 1);
                    } else if (ticks - progressAt > STALL) {
                        report(current, null, "replay stopped at tick " + at + " before the start");
                        nextCandidate();
                        return;
                    }
                    reachedAt = ticks;
                    return;
                }
                boolean past = at > shot.a(), waiting = ticks - reachedAt < POSE_MAX;
                if (waiting && (!past && !built() || dhAtFrame())) return;
                if (past) LOG.warn("Film: {}: replay went past the start ({} > {}), export will seek", shot.name(), at, shot.a());
                if (dhAtFrame()) LOG.warn("Film: {}: Distant Horizons textures are still {}x{}, the frame size, after {} ticks", shot.name(), plan.width(), plan.height(), POSE_MAX);
                export();
            }
            case EXPORTING -> {
                if (current.c.player() != null) watch();
                if (Export.exporting()) {
                    // Player GUI in frame: export window starts only without it; restore afterward
                    if (current.c.gui()) mc.options.hideGui = false;
                    return;
                }
                if (!Export.jobs().isEmpty()) {
                    if (waited() > EXPORT_WAIT) {
                        Export.jobs().clear();
                        throw new IllegalStateException("export did not start in " + EXPORT_WAIT + " ticks");
                    }
                    return;
                }
                done();
            }
            default -> {
            }
        }
    }

    /** Pilot chunk radius around impact and required stable-count ticks. */
    private static final int PILOT_CHUNKS = 16, PILOT_SETTLE = 40;

    private static int loadedAround(Cameras.World w, Vec3 k, int radius) {
        int n = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (w.loaded(k.add(dx * 16, 0, dz * 16))) n++;
            }
        }
        return n;
    }

    /** Live replay up to shot start; world/DH texture waits at start are measured from here. */
    private void warm() {
        reachedAt = ticks;
        go(State.WARMING);
    }

    /**
     * Distant Horizons textures already match shot frame size: export will not recreate them, and Iris
     * will leave LOD in world depth ({@link DhDepth}). They retain prior export size until DH renders LOD in window.
     */
    private boolean dhAtFrame() {
        return FrameLog.DH && DhDepth.textureIs(plan.width(), plan.height());
    }

    // ================================================================ shots and candidates

    private void nextShot() {
        shot = queue.poll();
        current = null;
        nearChunks = -1;
        bestTry = null;
        bestVerdict = null;
        if (shot == null) {
            finish();
            return;
        }
        int total = Export.total();
        if (shot.b() >= total) LOG.warn("Film: {} ends at {}, the replay has {} ticks", shot.name(), shot.b(), total);
        int key = shot.subject().firstKey();
        LOG.info("Film: shot {} ticks {}..{}, first event {}", shot.name(), shot.a(), shot.b(), key);
        prime(Math.max(1, key >= 0 ? Math.max(shot.a(), key - 5) : shot.a()));
    }

    /**
     * Reach {@code goal}, playing all not-yet-played replay from start: jump to {@link #primed} (snapshot
     * loses no already-read chunks), then advance in {@link #PRIME_STEP} steps. Earlier shots seek backward
     * by snapshot; later-delivered chunks retain late state, so shots run chronologically.
     */
    private void prime(int goal) {
        primeGoal = goal;
        primeStart = ticks;
        primeRetried = false;
        if (goal <= primed) {
            LOG.info("Film: {}: replay played from the start to tick {} earlier", shot.name(), primed);
            Export.seek(goal);
            go(State.SEEKING);
            return;
        }
        // Replay differs from primed position (back to shot, forward jump after stall): return first; snapshot
        // jump to primed loses nothing, and forward steps must begin there
        primeTo = Export.tick() == primed ? Math.min(goal, primed + PRIME_STEP) : primed;
        Export.seek(primeTo);
        go(State.PRIMING);
    }

    /**
     * All plan cameras and scores: predicted passes first, plan POV/fixed cameras before tripods within
     * them, score order; then failed candidates in the same grouping.
     */
    private void solve(Cameras.World w) {
        List<Scored> designed = new ArrayList<>(), rest = new ArrayList<>();
        for (Plan.Camera cam : shot.cameras()) {
            for (Cameras.Candidate c : Cameras.of(cam, shot, aspect, w)) {
                Scored s = new Scored(c, Cameras.score(c, shot.subject(), shot, aspect, w));
                (c.designed() ? designed : rest).add(s);
            }
        }
        // Plan paths and elevated copies retain plan order; elevated is fallback only when original path fails
        Comparator<Scored> order = Comparator.<Scored, Boolean>comparing(s -> !s.score.pass()).thenComparingDouble(s -> s.c.roam() ? 0 : -s.score.total());
        designed.sort(order);
        rest.sort(order);
        StringBuilder sb = new StringBuilder();
        for (Scored s : Stream.concat(designed.stream(), rest.stream().limit(8)).toList()) {
            sb.append(String.format(Locale.ROOT, "\n  %-32s %.3f %s (approach %.2f, near %.2f, events %.2f, after %.2f, size %.3f, smoke %.2f) %s",
                    s.c.name(), s.score.total(), s.score.pass() ? "pass" : "fail", s.score.approach(), s.score.near(), s.score.keys(), s.score.after(),
                    s.score.size(), s.score.smoke(), s.score.why()));
        }
        LOG.info("Film: {}: {} designed and {} scored cameras, best:{}", shot.name(), designed.size(), rest.size(), sb);
        candidates.clear();
        // A fixed plan camera predicted to fail does not displace a passing tripod
        Stream.concat(designed.stream(), rest.stream())
                .sorted(Comparator.<Scored, Boolean>comparing(s -> !s.score.pass()).thenComparing(s -> !s.c.designed())
                        .thenComparingDouble(s -> s.c.roam() ? 0 : -s.score.total()))
                .filter(s -> s.score.total() > 0).limit(plan.tries()).forEach(candidates::add);
        if (candidates.isEmpty()) {
            fail("no camera scored above zero");
            return;
        }
        nextCandidate();
    }

    /** Shot not exported at all (no camera, wrong dimension): report reason. */
    private void fail(String why) {
        LOG.warn("Film: {}: {}", shot.name(), why);
        JsonObject o = entry(false);
        o.addProperty("why", why);
        best.add(shot.name(), o);
        writeBest();
        nextShot();
    }

    /** Next candidate: seek before start with live warm-up, traverse camera path/build sections, export. */
    private void nextCandidate() {
        // After another player or interrupted export: camera returns to viewer, GUI hidden
        if (mc.player != null && mc.getCameraEntity() != mc.player) mc.setCameraEntity(mc.player);
        spectate(null);
        mc.options.hideGui = true;
        current = candidates.poll();
        if (current == null) {
            // No passes: report best exported candidate with reasons
            JsonObject o = entry(false);
            if (bestTry != null) {
                o.addProperty("dir", dirOf(bestTry).toString());
                o.addProperty("candidate", bestTry.c.name());
                o.addProperty("why", bestVerdict.why());
            } else {
                o.addProperty("why", "no candidate was exported");
            }
            best.add(shot.name(), o);
            writeBest();
            nextShot();
            return;
        }
        dir = dirOf(current);
        Export.seek(Math.max(1, shot.a() - shot.warm()));
        stepped = -1;
        walk.clear();
        povId = null;
        povLookup = null;
        seenAt = ticks;
        poseAt = ticks + SEEK_SETTLE;
        if (current.c.path() != null) {
            // Camera path sites requiring built world; fixed camera has one site
            Cameras.Path path = current.c.path();
            Cameras.Pose prev = path.at(shot.a());
            for (int i = 1; i <= 6; i++) {
                Cameras.Pose p = path.at(shot.a() + (shot.b() - shot.a()) * i / 6.0);
                if (p.pos().distanceTo(prev.pos()) > 2 || Math.abs(Mth.wrapDegrees(p.yaw() - prev.yaw())) > 10 || Math.abs(p.pitch() - prev.pitch()) > 10) {
                    walk.add(p);
                    prev = p;
                }
            }
            if (!walk.isEmpty()) walk.add(path.at(shot.a()));
            standing = path.at(shot.a());
        }
        go(State.WALKING);
        LOG.info("Film: {} candidate {} score {}", shot.name(), current.c.name(), String.format(Locale.ROOT, "%.3f", current.score.total()));
    }

    private Path dirOf(Scored s) {
        return plan.out().resolve(shot.name()).resolve(s.c.name());
    }

    // ================================================================ camera

    /** World built at camera (all sections in render distance); {@code ready} 0 disables wait/check. */
    private boolean built() {
        return plan.ready() <= 0 || mc.levelRenderer.hasRenderedAllSections();
    }

    /** Replay viewer eye height: Flashback keyframe position is feet. */
    private double eyeHeight() {
        return mc.player == null ? 1.62 : mc.player.getEyeHeight();
    }

    private void standAt(Cameras.Pose p) {
        mc.player.moveTo(p.pos().x, p.pos().y - eyeHeight(), p.pos().z, p.yaw(), p.pitch());
        mc.player.setDeltaMovement(Vec3.ZERO);
    }

    /**
     * Player-POV shot: server replay viewer spectates player ({@link #spectate}), client camera is that
     * player. Resolve on server: after seek, viewer remains at prior shot; beyond tracking range, client
     * cannot receive the player until server moves viewer there. Entity appears later after seek and may
     * be replaced, resolve every tick. null means player absent for now.
     */
    @Nullable
    private Entity watch() {
        if (povId == null) {
            if (povLookup == null) povLookup = lookup(current.c.player());
            if (!povLookup.isDone()) return null;
            povId = povLookup.getNow(null);
            povLookup = null;
            if (povId == null) return null;
        }
        spectate(povId);
        Player target = null;
        for (Player p : mc.level.players()) {
            if (povId.equals(p.getUUID())) target = p;
        }
        if (target == null) return null;
        seenAt = ticks;
        if (mc.getCameraEntity() != target) mc.setCameraEntity(target);
        mc.player.moveTo(target.getX(), target.getY(), target.getZ(), target.getYRot(), target.getXRot());
        mc.player.setDeltaMovement(Vec3.ZERO);
        return target;
    }

    /**
     * Replay-server viewer spectates entity {@code id} (null means self), like Flashback Spectate button
     * ({@code spectate}, {@code ServerPlayer.setCamera}). Otherwise server keeps viewer at last client-sent
     * position; client sends none while camera is another entity ({@code LocalPlayer.isControlledCamera}),
     * but Flashback distributes replay sounds by server viewer position: player shots/steps never reached
     * the frame. Server moves viewer to camera ({@code ServerPlayer.setCamera}, also sending a player beyond
     * tracking range), follows it each tick ({@code ServerPlayer.tick}); after seek Flashback reattaches the
     * same UUID and sends that entity inventory.
     */
    private void spectate(@Nullable UUID id) {
        MinecraftServer server = Export.server();
        if (server == null || mc.player == null) return;
        UUID viewer = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer me = server.getPlayerList().getPlayer(viewer);
            if (me == null) return;
            // Entity appears on server after seek asynchronously; retry next tick
            Entity target = id == null ? me : me.serverLevel().getEntity(id);
            if (target == null || me.getCamera() == target) return;
            double away = me.position().distanceTo(target.position());
            me.setCamera(id == null ? null : target);
            if (id != null) LOG.info("Film: the replay viewer spectates {} on the replay server ({} blocks away)", target.getScoreboardName(),
                    String.format(Locale.ROOT, "%.0f", away));
        });
    }

    /**
     * Plan player UUID on replay server, which has every replay-world player: {@code recorder} is recorded
     * player (replay entity ID), otherwise UUID/name. Future null means player not present yet.
     */
    private CompletableFuture<UUID> lookup(String who) {
        String name = who.startsWith("player:") ? who.substring(7) : who;
        try {
            return CompletableFuture.completedFuture(UUID.fromString(name));
        } catch (IllegalArgumentException notUuid) {
            // Recorder or player name
        }
        MinecraftServer server = Export.server();
        if (server == null) return CompletableFuture.completedFuture(null);
        int local = who.equals("recorder") ? Export.localPlayerId() : -1;
        return CompletableFuture.supplyAsync(() -> {
            for (ServerLevel level : server.getAllLevels()) {
                if (local >= 0) {
                    if (level.getEntity(local) instanceof Player p) return p.getUUID();
                    continue;
                }
                for (Entity e : level.getAllEntities()) {
                    if (e instanceof Player p && p.getGameProfile().getName().equalsIgnoreCase(name)) return p.getUUID();
                }
            }
            return null;
        }, server);
    }

    // ================================================================ export

    /**
     * Game window already equals shot frame size with Distant Horizons present: Flashback resizes game
     * frame to export size; at identical size Iris leaves LOD in world depth, covering nearby world
     * ({@link DhDepth}). Do not capture.
     */
    private boolean windowIsFrame() {
        var main = mc.getMainRenderTarget();
        if (!FrameLog.DH || main.width != plan.width() || main.height != plan.height()) return false;
        LOG.error("Film: the window is {}x{}, the frame size, and Distant Horizons is loaded: its LODs would be drawn over the near world; "
                + "open the client at another size", main.width, main.height);
        return true;
    }

    /** Export job: editor state, video, per-frame checks. */
    private void export() {
        if (windowIsFrame()) {
            finish();
            return;
        }
        try {
            deleteTree(dir);
            Files.createDirectories(dir);
            Path stateFile = dir.resolve("state.json");
            Files.writeString(stateFile, Export.editorState(current.c.path(), shot.a(), shot.b(), eyeHeight(), current.c.gui(), shot.time()), StandardCharsets.UTF_8);
            log = new FrameLog(shot.subject(), shot, plan, current.c.player() != null ? povId : null, current.c.path(), smoke, dir);
            Export.flawless(true);
            if (current.c.player() != null) mc.options.fov().set((int) Math.round(current.c.fov()));
            Vec3 at = mc.player.position();
            Export.queue(shot.name(), stateFile, at, mc.player.getYRot(), mc.player.getXRot(), plan.width(), plan.height(), shot.a(), shot.b(), plan.fps(),
                    plan.bitrate(), current.c.gui(), dir.resolve("video.mkv"));
            go(State.EXPORTING);
            LOG.info("Film: export {} {}: ticks {}..{}, all sections {}", shot.name(), current.c.name(), shot.a(), shot.b(),
                    mc.levelRenderer.hasRenderedAllSections());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Export finished: check result; pass completes shot, fail tries next candidate. */
    private void done() {
        Export.flawless(false);
        mc.options.hideGui = true;
        if (current.c.player() != null) {
            mc.setCameraEntity(mc.player);
            spectate(null);
        }
        // Export played continuously from live warm-up start (not beyond primed) through shot end; earlier chunks remain in replay world
        if (shot.a() - shot.warm() <= primed) primed = Math.max(primed, Math.min(shot.b(), Export.tick()));
        FrameLog l = log;
        log = null;
        l.close();
        FrameLog.Verdict v = l.verdict(current.c.player() != null);
        Path video = dir.resolve("video.mkv");
        try {
            if (!Files.exists(video) || Files.size(video) == 0) v = new FrameLog.Verdict(false, "no video", 0, v.numbers());
            JsonObject o = v.numbers().deepCopy();
            o.addProperty("pass", v.pass());
            o.addProperty("why", v.why());
            Files.writeString(dir.resolve("verdict.json"), new GsonBuilder().setPrettyPrinting().create().toJson(o), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        LOG.info("Film: verdict {} {}: {} {} {}", shot.name(), current.c.name(), v.pass() ? "PASS" : "FAIL", v.numbers(), v.why());
        report(current, v, v.why());
        if (bestTry == null || v.rank() > bestVerdict.rank()) {
            bestTry = current;
            bestVerdict = v;
        }
        if (v.pass()) {
            JsonObject o = entry(true);
            o.addProperty("dir", dir.toString());
            o.addProperty("candidate", current.c.name());
            best.add(shot.name(), o);
            writeBest();
            nextShot();
            return;
        }
        nextCandidate();
    }

    private void report(Scored c, @Nullable FrameLog.Verdict v, String why) {
        JsonObject o = new JsonObject();
        o.addProperty("shot", shot.name());
        o.addProperty("a", shot.a());
        o.addProperty("b", shot.b());
        o.addProperty("candidate", c.c.name());
        o.addProperty("dir", dirOf(c).toString());
        o.addProperty("score", c.score.total());
        o.addProperty("pass", v != null && v.pass());
        if (v != null) o.add("numbers", v.numbers());
        o.addProperty("why", why);
        try {
            Files.writeString(plan.out().resolve("report.jsonl"), o + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Record shot result, pass and ticks (restart identifies identical shot by ticks). */
    private JsonObject entry(boolean pass) {
        JsonObject o = new JsonObject();
        o.addProperty("pass", pass);
        o.addProperty("a", shot.a());
        o.addProperty("b", shot.b());
        o.addProperty("first", shot.subject().firstKey());
        return o;
    }

    /** Write {@code best.json} through a temporary file: crash mid-write cannot leave it truncated. */
    private void writeBest() {
        try {
            Path file = plan.out().resolve("best.json"), tmp = plan.out().resolve("best.json.tmp");
            Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(best), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    private void finish() {
        go(State.DONE);
        writeBest();
        LOG.info("Film: done, {}", best);
        mc.stop();
    }
}
