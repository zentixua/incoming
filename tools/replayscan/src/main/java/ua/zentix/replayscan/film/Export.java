package ua.zentix.replayscan.film;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Flashback for shooting: replay (open, rewind, step), video output by key frames or through the eyes of the player. Flashback —
 * mod Fabric (via Sinytra Connector), API for mods it does not have: public members are reflected, names are Flashback 0.39
 *  (branch 1.21); they are also called by the window Flashback (rewind and output queue - from the client stream).
 */
final class Export {
    private static final String P = "com.moulberry.flashback.";
    /** The camera keys are with a margin before the start: the output puts the camera a little earlier. */
    private static final int LEAD = 40;

    private Export() {}

    private static Class<?> c(String name) throws ClassNotFoundException {
        return Class.forName(P + name);
    }

    private static IllegalStateException fail(String what, Throwable e) {
        return new IllegalStateException("Flashback: " + what, e);
    }

    static void open(Path zip) {
        try {
            c("Flashback").getMethod("openReplayWorld", Path.class).invoke(null, zip);
        } catch (ReflectiveOperationException e) {
            throw fail("openReplayWorld", e);
        }
    }

    static boolean inReplay() {
        try {
            return (boolean) c("Flashback").getMethod("isInReplay").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw fail("isInReplay", e);
        }
    }

    @Nullable
    static net.minecraft.server.MinecraftServer server() {
        try {
            return (net.minecraft.server.MinecraftServer) c("Flashback").getMethod("getReplayServer").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw fail("getReplayServer", e);
        }
    }

    static int tick() {
        try {
            Object s = server();
            return s == null ? -1 : (int) s.getClass().getMethod("getReplayTick").invoke(s);
        } catch (ReflectiveOperationException e) {
            throw fail("getReplayTick", e);
        }
    }

    static int total() {
        try {
            Object s = server();
            return s == null ? -1 : (int) s.getClass().getMethod("getTotalReplayTicks").invoke(s);
        } catch (ReflectiveOperationException e) {
            throw fail("getTotalReplayTicks", e);
        }
    }

    /** id of the entity of the recording player on the replay server ({@code −1} - it does not exist). */
    static int localPlayerId() {
        try {
            Object s = server();
            return s == null ? -1 : (int) s.getClass().getMethod("getLocalPlayerId").invoke(s);
        } catch (ReflectiveOperationException e) {
            throw fail("getLocalPlayerId", e);
        }
    }

    /**
     * The replay starts at tick {@code tick} (on pause). On the next tick - step: the replay server reads one tick, as when playing
     * , the events of this tick are live on the client; then rewind.
     */
    static void seek(int tick) {
        try {
            Object s = server();
            s.getClass().getField("replayPaused").setBoolean(s, true);
            s.getClass().getMethod("goToReplayTick", int.class).invoke(s, tick);
        } catch (ReflectiveOperationException e) {
            throw fail("goToReplayTick", e);
        }
    }

    /**
     * The members of Flashback, who are called by the shooting, are in place: otherwise the shooting would fall in the middle of the replay.
     */
    static void check() throws ReflectiveOperationException {
        Class<?> flashback = peek("Flashback");
        flashback.getMethod("openReplayWorld", Path.class);
        flashback.getMethod("isInReplay");
        flashback.getMethod("getReplayServer");
        flashback.getField("EXPORT_JOB");
        Class<?> server = peek("playback.ReplayServer");
        server.getField("replayPaused");
        for (String m : List.of("getReplayTick", "getTotalReplayTicks", "getLocalPlayerId")) server.getMethod(m);
        server.getMethod("goToReplayTick", int.class);
        Class<?> queue = peek("exporting.ExportJobQueue");
        queue.getField("queuedJobs");
        queue.getField("drainingQueue");
        peek("state.EditorState").getMethod("load", Path.class);
    }

    /** Class Flashback without initialization: check occurs when creating the mod, before the client. */
    private static Class<?> peek(String name) throws ClassNotFoundException {
        return Class.forName(P + name, false, Export.class.getClassLoader());
    }

    static boolean exporting() {
        try {
            return c("Flashback").getField("EXPORT_JOB").get(null) != null;
        } catch (ReflectiveOperationException e) {
            throw fail("EXPORT_JOB", e);
        }
    }

    /** Output location: tick replay with share ({@code startTick} tasks + completed); does not display - NaN. */
    static double exportTick() {
        try {
            Object job = c("Flashback").getField("EXPORT_JOB").get(null);
            if (job == null) return Double.NaN;
            Object settings = job.getClass().getMethod("getSettings").invoke(job);
            int start = (int) settings.getClass().getMethod("startTick").invoke(settings);
            return start + (double) job.getClass().getMethod("getCurrentTickDouble").invoke(job);
        } catch (ReflectiveOperationException e) {
            throw fail("EXPORT_JOB.getCurrentTickDouble", e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<Object> jobs() {
        try {
            return (List<Object>) c("exporting.ExportJobQueue").getField("queuedJobs").get(null);
        } catch (ReflectiveOperationException e) {
            throw fail("ExportJobQueue.queuedJobs", e);
        }
    }

    /**
     * Setting the output to the queue and starting the queue - as the buttons "Add to queue" and "Start" of the output window Flashback: camera and angle
     *  frame - from the editor state {@code state} (JSON, as it stores Flashback), video - MKV, H264 by the first encoder
     * Flashback (its window selects it), stereo AAC, identical random numbers of particles in each output; {@code gui} —
     *  with the player interface (the output window starts the task only without it, you can return it later).
     */
    static void queue(String name, Path state, Vec3 camera, float yaw, float pitch, int width, int height, int start, int end, double fps,
                      int bitrate, boolean gui, Path video) {
        try {
            Object editor = c("state.EditorState").getMethod("load", Path.class).invoke(null, state);
            if (editor == null) throw new IllegalStateException("editor state not read: " + state);
            Object h264 = constant("combo_options.VideoCodec", "H264");
            String[] encoders = (String[]) h264.getClass().getMethod("getEncoders").invoke(h264);
            Constructor<?> ctor = Arrays.stream(c("exporting.ExportSettings").getConstructors()).filter(k -> k.getParameterCount() == 23).findFirst()
                    .orElseThrow(() -> new NoSuchMethodException("ExportSettings(23)"));
            Object job = ctor.newInstance(name, editor, camera, yaw, pitch, width, height, start, end, fps, true,
                    constant("combo_options.VideoContainer", "MKV"), h264, encoders[0], bitrate, false, false, !gui, true, true,
                    constant("combo_options.AudioCodec", "AAC"), video, null);
            jobs().add(job);
            c("exporting.ExportJobQueue").getField("drainingQueue").setBoolean(null, true);
        } catch (ReflectiveOperationException e) {
            throw fail("ExportSettings", e);
        }
    }

    private static Object constant(String type, String name) throws ClassNotFoundException {
        for (Object k : c(type).getEnumConstants()) {
            if (((Enum<?>) k).name().equals(name)) return k;
        }
        throw new IllegalArgumentException(type + "." + name);
    }

    /**
     * Editor status Flashback (as stored by Flashback): one scene; for the camera on key frames - camera tracks and
     *  frame corner with a key for each tick of the window (course without a jump through ±180°; the place of the key is the viewer's feet, {@code eye} below the
     *  eye); through the eyes of the player - without paths. The replay interface (chat, scoreboard, names) is hidden, the item panel is only
     *  with {@code gui}, replay viewers are not visible; {@code time} not less than 0 - time of day (window Visuals Flashback: during
     *  output Flashback takes it from the task state).
     */
    static String editorState(@Nullable Cameras.Path path, int a, int b, double eye, boolean gui, long time) {
        JsonArray tracks = new JsonArray();
        if (path != null) {
            JsonObject cam = new JsonObject(), fov = new JsonObject();
            float yaw = Float.NaN;
            for (int t = a - LEAD - 2; t <= b + 2; t++) {
                Cameras.Pose p = path.at(t);
                yaw = Float.isNaN(yaw) ? p.yaw() : yaw + Mth.wrapDegrees(p.yaw() - yaw);
                JsonObject k = new JsonObject();
                JsonArray pos = new JsonArray();
                pos.add(p.pos().x);
                pos.add(p.pos().y - eye);
                pos.add(p.pos().z);
                k.add("position", pos);
                k.addProperty("yaw", yaw);
                k.addProperty("pitch", p.pitch());
                k.addProperty("roll", path.roll(t));
                k.addProperty("type", "camera");
                k.addProperty("interpolation_type", "SMOOTH");
                cam.add(Integer.toString(t), k);
                JsonObject f = new JsonObject();
                f.addProperty("fov", p.fov());
                f.addProperty("type", "fov");
                f.addProperty("interpolation_type", "SMOOTH");
                fov.add(Integer.toString(t), f);
            }
            tracks.add(track("CAMERA", cam));
            tracks.add(track("FOV", fov));
        }
        JsonObject visuals = new JsonObject();
        for (String k : List.of("showChat", "showBossBar", "showTitleText", "showScoreboard", "showActionBar")) visuals.addProperty(k, false);
        visuals.addProperty("showHotbar", gui);
        visuals.addProperty("renderNametags", false);
        if (time >= 0) visuals.addProperty("overrideTimeOfDay", time);
        JsonObject scene = new JsonObject();
        scene.addProperty("name", "film");
        scene.add("keyframeTracks", tracks);
        scene.addProperty("exportStartTicks", a);
        scene.addProperty("exportEndTicks", b);
        JsonObject history = new JsonObject();
        history.add("entries", new JsonArray());
        history.addProperty("position", 0);
        scene.add("history", history);
        JsonArray scenes = new JsonArray();
        scenes.add(scene);
        JsonObject state = new JsonObject();
        state.add("replayVisuals", visuals);
        state.addProperty("hideAllSpectators", true);
        state.add("scenes", scenes);
        state.addProperty("sceneIndex", 0);
        return new GsonBuilder().create().toJson(state);
    }

    private static JsonObject track(String type, JsonObject keys) {
        JsonObject t = new JsonObject();
        t.addProperty("enabled", true);
        t.add("customName", null);
        t.addProperty("customColour", 0);
        t.addProperty("keyframeType", type);
        t.add("keyframesByTick", keys);
        return t;
    }

    @Nullable
    private static Consumer<Boolean> flawless;
    private static boolean flawlessSearched;

    /**
     * Frame - only when the world is built in it: “flawless frames” Sodium (open {@code FlawlessFrames.getProvider()};
     * Flashback includes it with the entry point Fabric, but on NeoForge Sodium does not read it - we turn it on ourselves). Without Sodium —
     *  nothing: unfinished frames are seen by the check.
     */
    @SuppressWarnings("unchecked")
    static void flawless(boolean on) {
        if (!flawlessSearched) {
            flawlessSearched = true;
            try {
                var provider = (Function<String, Consumer<Boolean>>) Class.forName("net.caffeinemc.mods.sodium.client.util.FlawlessFrames")
                        .getMethod("getProvider").invoke(null);
                flawless = provider.apply("replayscan film");
            } catch (ReflectiveOperationException | RuntimeException e) {
                flawless = null;
            }
        }
        if (flawless != null) flawless.accept(on);
    }
}
