package ua.zentix.incoming.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.fx.particle.FxBudget;
import ua.zentix.incoming.client.fx.particle.FxPool;
import ua.zentix.incoming.client.nuclear.ClientNuclear;
import ua.zentix.incoming.client.nuclear.NukeFlash;
import ua.zentix.incoming.client.replay.Replay;
import ua.zentix.incoming.entity.StrikeProjectile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Flashback replay ({@code replay} scenario: launch as in {@code launch}, with recording mods from zentixua/open-skies
 * and {@code recordingControls.quicksave} in Flashback settings): client leaves the world as with "Save and Quit";
 * Flashback finishes the recording zip in its replay folder on exit; scenario opens that replay, seeks before the
 * first launch, and plays to the end. Camera (own replay player, the viewer) moves beside the projectile;
 * {@code replay_<name>.png} frames show projectile in flight ({@link #FLIGHT} ticks after appearance) and disappearance
 * site ({@link #GONE} ticks later): explosion or leaving what the recording player saw. Flashback is a Fabric mod
 * (via Sinytra Connector), absent from the mod build: its methods are called by reflection. Log lines:
 * {@code SCENARIO replay saved|opened|play|frame|failed}, ending with {@code SCENARIO done}; play/frame lines include
 * the {@code launch} scenario pad floor block ({@link #FLOOR}, smooth stone): replay shows the same blocks as the game.
 * <p>
 * Seeking ({@link #seek}): recording also includes a nuclear detonation (1 kt behind the viewer,
 * {@code ClientScenario.planLaunch}). Immediately after opening, seek forward past all impacts; after playback,
 * seek back to the end, to playback start, to two ticks after detonation (it occurs during tick-by-tick catch-up),
 * then forward again. Each seek logs {@code SCENARIO replay seek}: no live mod events ({@link Replay#counts}:
 * Flashback delivers seek packets together, Flashback NeoForge Fixed places the latest in the snapshot as history),
 * and seeking across impacts brings historical events; no flash or live detonation; a detonation before the seek
 * position has its world-clock age, one after it is invisible; frame {@code replay_seek_<name>.png}. Seek window ends
 * on replay server markers: position reached and replay standing without seeking. During playback, explosions and
 * detonation are live. Result: {@code SCENARIO replay seeks ok|FAIL}.
 */
final class ReplayCheck {
    /** So much tick to wait for the replay file after exiting and the replay world after opening. */
    private static final int SAVE_WAIT = 1200, OPEN_WAIT = 2400;
    /**
     * Playback - from this point to the end of the recording (tick to the end): the recording ends with the exit at tick 1520 of the script
     * {@code launch}, the first launch - at tick 230. tick after opening before transition and after transition before launch playback.
     */
    private static final int PLAY_BACK = 1320, SEEK_AT = 20, PLAY_AT = 60;
    /** Footage: a projectile in flight - so many tick after its appearance, the place of loss - after it. */
    private static final int[] FLIGHT = {60, 160}, GONE = {4, 30};
    private static final int MAX_FRAMES = 14;
    /** The floor of the {@code launch} scenario (fill ... smooth_stone on y 199) is under the middle of the path to the goal. */
    private static final BlockPos FLOOR = new BlockPos(0, 199, 60);
    /**
     * Rewind “for all beats” - by so many ticks until the end of the recording; window after rewind: no less than the client's tick (the flash
     *  would have started in time) and replay server marks, no more than the client's tick before failure; by how much tick the age of detonation
     *  can diverge from the world clock (the client’s clock is repeated - a time packet once per second, and a window).
     */
    private static final int END_BACK = 30, SEEK_WINDOW = 20, SEEK_MARKERS = 3, SEEK_TIMEOUT = 1200, AGE_SLACK = 40;

    private Class<?> flashback;
    private Object server;
    private int ticks, openedAt = -1, frames, total, playFrom, playTicks = -1;
    private Path saved;
    private boolean finished;
    /** Replay projectiles visible in the past tick; numbers and last places of everyone that appeared. */
    private final Set<UUID> live = new HashSet<>();
    private final Map<UUID, Integer> number = new HashMap<>();
    private final Map<UUID, Vec3> last = new HashMap<>();
    /** Frames in the queue: playback tick, name, projectile - the camera is on the side of it while it is there, then at its last place. */
    private final List<Shot> shots = new ArrayList<>();

    private record Shot(int due, String name, UUID of, boolean flight) {}

    /**
     * Rewind under check: where, tick of the script, when it started, marks and count of the events of the mod to its beginning, whether there should be a
     *  explosion ({@code null} - not known), whether the rewind should bring events in the past, what’s next.
     */
    private record Seek(String name, int to, int started, long markers, Map<String, int[]> counts, Boolean nuke, boolean past, Step then) {}

    private interface Step {
        void run() throws ReflectiveOperationException;
    }

    private Seek seek;
    /** The greatest whiteness of the flash behind the rewind window. */
    private float seekFlash;
    /** The location of the recording, where the explosion appeared on the client during playback, and whether it was alive; particles when playing. */
    private int nukeTick = -1, playFx, openNukes;
    private boolean nukeLive;
    /** Counting mod events at the start of playback. */
    private Map<String, int[]> playCounts;
    private final List<String> seekFails = new ArrayList<>();

    ReplayCheck() {
        try {
            flashback = Class.forName("com.moulberry.flashback.Flashback");
        } catch (ClassNotFoundException e) {
            fail("Flashback not loaded");
            return;
        }
        NeoForge.EVENT_BUS.addListener(this::onTick);
        // outside tick: exiting the world in the middle of a tick event would leave the world null to the rest of the handlers
        Minecraft.getInstance().tell(ReplayCheck::leave);
    }

    /** Like {@code PauseScreen.onDisconnect} for a single-player game: at the output, Flashback ends the recording (quicksave goes straight to zip). */
    private static void leave() {
        Minecraft mc = Minecraft.getInstance();
        mc.level.disconnect();
        mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
        mc.setScreen(new TitleScreen());
        Incoming.LOG.info("SCENARIO replay left world");
    }

    private void onTick(ClientTickEvent.Post e) {
        if (finished) return;
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        try {
            if (saved == null) {
                if (mc.level != null) return;
                saved = newestReplay();
                if (saved != null) {
                    Incoming.LOG.info("SCENARIO replay saved {} {} bytes after {} ticks", saved.getFileName(), Files.size(saved), ticks);
                    ticks = 0;
                    Path open = saved;
                    mc.tell(() -> {
                        try {
                            flashback.getMethod("openReplayWorld", Path.class).invoke(null, open);
                        } catch (ReflectiveOperationException ex) {
                            Incoming.LOG.error("SCENARIO replay failed: openReplayWorld", ex);
                            finish();
                        }
                    });
                } else if (ticks > SAVE_WAIT) {
                    fail("no repeat in " + replayFolder() + " through " + SAVE_WAIT + " tick after exit: " + list(replayFolder().getParent()));
                }
                return;
            }
            if (openedAt < 0) {
                if (mc.level != null && mc.player != null && (boolean) flashback.getMethod("isInReplay").invoke(null)) {
                    openedAt = ticks;
                    int all = 0, ours = 0;
                    for (var en : mc.level.entitiesForRendering()) {
                        all++;
                        if (BuiltInRegistries.ENTITY_TYPE.getKey(en.getType()).getNamespace().equals(Incoming.MOD_ID)) ours++;
                    }
                    Incoming.LOG.info("SCENARIO replay opened after {} ticks: {} at {}, entities {} (incoming {})", ticks,
                            mc.level.dimension().location(), mc.player.blockPosition().toShortString(), all, ours);
                } else if (ticks > OPEN_WAIT) {
                    fail("the world of repetition has not opened for " + OPEN_WAIT + " tick, screen " + (mc.screen == null ? "-" : mc.screen.getClass().getName()));
                }
                return;
            }
            int since = ticks - openedAt;
            if (seek != null) {
                watchSeek(mc);
            } else if (since == SEEK_AT) {
                server = flashback.getMethod("getReplayServer").invoke(null);
                if (server == null) {
                    fail("no replay server (Flashback.getReplayServer)");
                    return;
                }
                total = (int) server.getClass().getMethod("getTotalReplayTicks").invoke(server);
                playFrom = Math.max(0, total - PLAY_BACK);
                mc.options.hideGui = true;
                // first glance: for all the blows at once - their packages arrive at once
                seek("open", total - END_BACK, null, true, () -> {
                    server.getClass().getMethod("goToReplayTick", int.class).invoke(server, playFrom);
                    openedAt = ticks - SEEK_AT; // before launch playback - as without rewinding
                    Incoming.LOG.info("SCENARIO replay play from {} of {} ticks", playFrom, total);
                });
            } else if (since == PLAY_AT && playTicks < 0) {
                server.getClass().getField("replayPaused").setBoolean(server, false);
                playTicks = 0;
                playCounts = Replay.counts();
            } else if (playTicks >= 0) {
                if (mc.level == null || mc.player == null) {
                    fail("the replay world closed in the middle of playback");
                    return;
                }
                play(mc);
                playTicks++;
            }
        } catch (ReflectiveOperationException | IOException ex) {
            Incoming.LOG.error("SCENARIO replay failed", ex);
            finish();
        }
    }

    /** tick playback: shells appeared and disappeared - frames in line; camera - on the side of the projectile; frames on time. */
    private void play(Minecraft mc) throws ReflectiveOperationException {
        int tick = (int) server.getClass().getMethod("getReplayTick").invoke(server);
        Map<UUID, Vec3> now = new HashMap<>();
        Map<String, Integer> ours = new TreeMap<>();
        for (Entity en : mc.level.entitiesForRendering()) {
            var key = BuiltInRegistries.ENTITY_TYPE.getKey(en.getType());
            if (key.getNamespace().equals(Incoming.MOD_ID)) ours.merge(key.getPath(), 1, Integer::sum);
            if (en instanceof StrikeProjectile) now.put(en.getUUID(), en.position());
        }
        last.putAll(now);
        for (UUID id : now.keySet()) {
            if (number.putIfAbsent(id, number.size() + 1) == null) {
                if (frames < MAX_FRAMES) for (int d : FLIGHT) shots.add(new Shot(playTicks + d, "flight" + number.get(id) + "_" + d, id, true));
            }
        }
        for (UUID id : live) {
            if (!now.containsKey(id)) {
                if (frames < MAX_FRAMES) for (int d : GONE) shots.add(new Shot(playTicks + d, "gone" + number.get(id) + "_" + d, id, false));
            }
        }
        live.clear();
        live.addAll(now.keySet());
        int fx = fx();
        playFx = Math.max(playFx, fx);
        if (nukeTick < 0 && !ClientNuclear.detonations().isEmpty()) {
            nukeTick = tick;
            nukeLive = ClientNuclear.detonations().get(0).live;
            Incoming.LOG.info("SCENARIO replay nuke at {} live={}", tick, nukeLive);
        }
        String floor = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(FLOOR).getBlock()).toString();
        if (playTicks % 20 == 0) {
            Incoming.LOG.info("SCENARIO replay play tick={} incoming={} fx={} floor={}", tick, ours, fx, floor);
        }
        shots.sort(Comparator.comparingInt(Shot::due));
        if (!shots.isEmpty()) {
            Shot next = shots.get(0);
            Vec3 at = last.get(next.of);
            // shell - in 16 blocks on the side and slightly behind, the place of loss - in 40: fireball and smoke are visible if it is an explosion
            look(mc, at, next.flight && now.containsKey(next.of) ? new Vec3(14, 4, -8) : new Vec3(32, 14, -22));
            if (playTicks >= next.due) {
                shots.remove(0);
                Screenshot.grab(mc.gameDirectory, "replay_" + next.name + ".png", mc.getMainRenderTarget(), c -> {});
                var cam = mc.getCameraEntity();
                BlockPos under = BlockPos.containing(at.x, FLOOR.getY(), at.z);
                Incoming.LOG.info("SCENARIO replay frame {} tick={} at {} camera {} {} incoming={} fx={} fps={} floor={} under={}", next.name, tick,
                        BlockPos.containing(at).toShortString(), cam == null ? "-" : cam.getClass().getSimpleName(),
                        cam == null ? "-" : cam.blockPosition().toShortString(), ours, fx, mc.getFps(), floor,
                        BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(under).getBlock()));
                // there are enough frames - playback continues without them, to rewind
                if (++frames == MAX_FRAMES) shots.clear();
            }
        }
        if (shots.isEmpty() && (tick >= total - 2 || playTicks > total - playFrom + 400)) {
            Map<String, int[]> played = diff(playCounts, Replay.counts());
            Incoming.LOG.info("SCENARIO replay played to tick {} of {}: projectiles {}, frames {}, fx up to {}, events {}", tick, total,
                    number.size(), frames, playFx, describe(played));
            if (count(played, "blast", 0) == 0) seekFails.add("there are no live explosions when playing");
            playTicks = -1;
            int end = total - END_BACK;
            boolean nukeBefore = nukeTick >= 0 && nukeTick <= end;
            // after the explosion there may not be any events in the recording: from “forward” - only not a single living
            Step forward = () -> seek("forward", end, nukeBefore, false, this::seekVerdict);
            // two ticks after the explosion: he is inspected by tick in front of the place
            Step after = nukeTick < 0 ? forward : () -> seek("after", nukeTick + 2, true, true, forward);
            seek("back", end, nukeBefore, true, () -> seek("before", playFrom, nukeTick >= 0 ? false : null, false, after));
        }
    }

    private static int fx() {
        int fx = 0;
        for (FxBudget b : FxBudget.values()) fx += FxPool.INSTANCE.live(b);
        return fx;
    }

    /** Rewind to place {@code to} on pause; result - when it ended ({@link #watchSeek}). */
    private void seek(String name, int to, Boolean nuke, boolean past, Step then) throws ReflectiveOperationException {
        server.getClass().getField("replayPaused").setBoolean(server, true);
        server.getClass().getMethod("goToReplayTick", int.class).invoke(server, to);
        seek = new Seek(name, to, ticks, Replay.markers(), Replay.counts(), nuke, past, then);
        seekFlash = 0;
    }

    /**
     * Window after rewind - until the replay server has reached the place and stands without rewind (tag), but not less than
     * {@link #SEEK_WINDOW} tick: flash - the largest for the window; mod events are out the window - only in the past; explosions: there are no living
     * , the explosion to the place is aged according to the clock of the world, after the place it is not visible.
     */
    private void watchSeek(Minecraft mc) throws ReflectiveOperationException {
        seekFlash = Math.max(seekFlash, NukeFlash.whiteness(0));
        int at = (int) server.getClass().getMethod("getReplayTick").invoke(server);
        boolean settled = at == seek.to && Replay.steady() && Replay.markers() - seek.markers >= SEEK_MARKERS;
        int waited = ticks - seek.started;
        if (waited < SEEK_WINDOW || !settled && waited < SEEK_TIMEOUT) return;
        int fx = fx();
        Map<String, int[]> events = diff(seek.counts, Replay.counts());
        List<ClientNuclear.Active> nukes = ClientNuclear.detonations();
        long live = nukes.stream().filter(a -> a.live).count();
        long clock = mc.level.getGameTime();
        StringBuilder ages = new StringBuilder();
        boolean agesOk = true;
        for (ClientNuclear.Active a : nukes) {
            long expected = clock - a.d.gameTime();
            agesOk &= Math.abs(a.ticks(0) - expected) <= AGE_SLACK;
            ages.append(String.format(java.util.Locale.ROOT, " %.0f/%d", a.ticks(0), expected));
        }
        List<String> why = new ArrayList<>();
        if (!settled) why.add("the rewind didn't end in " + SEEK_TIMEOUT + " tick: place " + at + ", labels " + (Replay.markers() - seek.markers));
        if (total(events, 0) > 0) why.add("live events");
        if (seek.past && total(events, 1) == 0) why.add("no past events");
        if (seekFlash > 0) why.add("flash");
        if (live > 0) why.add("live explosions " + live);
        if (!agesOk) why.add("age");
        if (seek.nuke != null && seek.nuke != !nukes.isEmpty()) why.add(seek.nuke ? "no detonation" : "detonation ahead of time");
        if ("open".equals(seek.name)) openNukes = nukes.size();
        Screenshot.grab(mc.gameDirectory, "replay_seek_" + seek.name + ".png", mc.getMainRenderTarget(), c -> {});
        Incoming.LOG.info("SCENARIO replay seek {} to {}: at {} after {} ticks, events {}, fx={} flash={} nukes={} live={} age/expected{} {}", seek.name,
                seek.to, at, waited, describe(events), fx, String.format(java.util.Locale.ROOT, "%.2f", seekFlash), nukes.size(), live, ages,
                why.isEmpty() ? "ok" : "FAIL " + String.join(", ", why));
        if (!why.isEmpty()) seekFails.add(seek.name + ": " + String.join(", ", why));
        Step then = seek.then;
        seek = null;
        then.run();
    }

    /** Result rewind: even when playing back, the explosions and explosions were alive (screening out the past does not suppress the living). */
    private void seekVerdict() {
        if (nukeTick < 0) seekFails.add("there is no explosion in the recording");
        else if (!nukeLive) seekFails.add("explosion when playing not live");
        else if (openNukes == 0) seekFails.add("open: the explosion is not visible after rewinding behind it");
        Incoming.LOG.info("SCENARIO replay seeks {}", seekFails.isEmpty() ? "ok" : "FAIL: " + String.join("; ", seekFails));
        finish();
    }

    /** Mod event count between two copies of {@link Replay#counts}: kind → {live, past}. */
    private static Map<String, int[]> diff(Map<String, int[]> before, Map<String, int[]> after) {
        Map<String, int[]> d = new TreeMap<>();
        after.forEach((k, v) -> {
            int[] b = before.getOrDefault(k, new int[2]);
            if (v[0] != b[0] || v[1] != b[1]) d.put(k, new int[] {v[0] - b[0], v[1] - b[1]});
        });
        return d;
    }

    private static int count(Map<String, int[]> events, String kind, int i) {
        int[] c = events.get(kind);
        return c == null ? 0 : c[i];
    }

    private static int total(Map<String, int[]> events, int i) {
        return events.values().stream().mapToInt(c -> c[i]).sum();
    }

    /** "live {kind=n…} past {kind=n…}". */
    private static String describe(Map<String, int[]> events) {
        StringBuilder live = new StringBuilder(), past = new StringBuilder();
        events.forEach((k, v) -> {
            if (v[0] != 0) live.append(live.isEmpty() ? "" : ", ").append(k).append('=').append(v[0]);
            if (v[1] != 0) past.append(past.isEmpty() ? "" : ", ").append(k).append('=').append(v[1]);
        });
        return "live {" + live + "} past {" + past + "}";
    }

    /** The replay viewer is {@code offset} from the point, facing it. */
    private static void look(Minecraft mc, Vec3 at, Vec3 offset) {
        Vec3 eye = at.add(offset);
        Vec3 d = at.subtract(eye);
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
        float pitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
        mc.player.moveTo(eye.x, eye.y - mc.player.getEyeHeight(), eye.z, yaw, pitch);
        mc.player.setDeltaMovement(Vec3.ZERO);
        if (mc.getCameraEntity() != mc.player) mc.setCameraEntity(mc.player);
    }

    private Path replayFolder() throws ReflectiveOperationException {
        return (Path) flashback.getMethod("getReplayFolder").invoke(null);
    }

    private Path newestReplay() throws ReflectiveOperationException, IOException {
        Path dir = replayFolder();
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".zip")).max(Comparator.comparing(p -> p.toFile().lastModified())).orElse(null);
        }
    }

    /** What is in the data folder Flashback (two levels): how the recording ended if there is no replay. */
    private static String list(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) return "no folder";
        try (Stream<Path> s = Files.walk(dir, 2)) {
            List<String> names = s.map(p -> dir.relativize(p).toString()).filter(n -> !n.isEmpty()).sorted().toList();
            return names.isEmpty() ? "empty" : String.join(", ", names);
        }
    }

    private void fail(String why) {
        Incoming.LOG.error("SCENARIO replay failed: {}", why);
        finish();
    }

    private void finish() {
        finished = true;
        Incoming.LOG.info("SCENARIO done");
        Minecraft.getInstance().stop();
    }
}
