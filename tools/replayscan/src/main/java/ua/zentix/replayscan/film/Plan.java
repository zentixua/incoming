package ua.zentix.replayscan.film;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Filming plan (README.md, "Filming"): replay, output, frame size and shots. {@code tools/clips/plan.py}
 * writes it from scan data; filming makes no random choices: what, when and where are fixed here,
 * camera selected on site by replay-world scoring.
 *
 * @param replay replay (zip copy)
 * @param out output directory: {@code <shot>/<camera>/video.mkv}, frame checks, report
 * @param ready minimum built-world frame share (0 disables check: GPU-less cloud builds sections slower than frames)
 * @param clouds cloud base (shader pack custom layer); NaN for dimension vanilla clouds
 * @param distance render distance, chunks
 * @param loud loud sound IDs (explosions), count out-of-frame instances
 * @param engine engine sound IDs, whether subject engine is audible
 */
record Plan(Path replay, Path out, int width, int height, double fps, int bitrate, int tries, double ready, double clouds, int distance,
            Pattern loud, Pattern engine, List<Shot> shots) {
    /**
     * Default projectile flight sounds (Incoming 2.x/3.x IDs): engines, dive, missile whistle, bomb fall,
     * Grad approach; excludes explosions.
     */
    static final String ENGINE = "(?i)engine|dive|whistle|bomb\\.fall|rocket\\.incoming";

    /**
     * Shot: replay tick interval, subject, ordered cameras (specified first, then score-best), check thresholds.
     *
     * @param warm live replay ticks before start (prior impact smoke/fire in frame, as in game)
     * @param hold ticks at camera after world builds (Distant Horizons builds distant terrain after seek)
     * @param far subject beyond render distance: skip edge fog/entity-section checks, ray uses ready chunks
     * @param smoke strikes {@code [t, x, y, z, ball diameter?, kind?]}, smoke ({@link Plume}) for camera/pilot scoring;
     *              frame-visible subset is {@link Shot#live}
     * @param time export day ticks, as {@code /time set} (18000 midnight), negative keeps recorded time
     */
    record Shot(String name, int a, int b, String dim, int warm, int hold, boolean far, List<Camera> cameras, Subject subject,
                List<double[]> smoke, Need need, long time) {
        /**
         * Smoke potentially in frame: export seeks to {@code a − warm}, then plays live; Incoming treats seek
         * explosions as past, drawing no particles/column ({@code Replay.event}). Earlier smoke exists only in
         * game; pilot/scoring would otherwise avoid absent smoke. Include tick {@code a − warm}, though sought:
         * extra planned smoke is safer than missing it. clips9 frames (14.10): all-impact model obscured projectile
         * for up to 170 of 240 approach frames where actual smoke was clear; impacts from {@code a − warm} obscured none.
         */
        List<double[]> live() {
            return smoke.stream().filter(r -> r[0] >= a - warm).toList();
        }
    }

    /**
     * Camera: {@code pov} through player ({@code player}: {@code recorder}, name or UUID); {@code fixed} at
     * {@code eye}, aim at {@code aim} or track subject ({@code follow}), {@code rise} heights above ground
     * below eye (0 uses eye). {@code tripod} around {@code around}: distances {@code dist}, elevations
     * {@code elev}, azimuth step {@code azStep}, lens puts {@code size}-wide event at {@code share} of frame
     * width. {@code path} interpolates {@code keys} (eye/look linearly; {@code eye}/{@code aim} from first key);
     * {@code rise} also tries the same path this many blocks higher.
     */
    record Camera(String kind, @Nullable String player, boolean gui, double fov, @Nullable Vec3 eye, @Nullable Vec3 aim, boolean follow,
                  @Nullable Vec3 around, @Nullable Vec3 heading, double[] dist, double[] elev, double azStep, double share, double size,
                  double[] rise, List<PathKey> keys) {}

    /** {@code path} camera key: replay tick, eye, look point, roll (degrees, positive left like Flashback). */
    record PathKey(double t, Vec3 eye, Vec3 aim, double roll) {}

    /**
     * Frame thresholds: subject captured share before first event ({@code subject}), same for final second
     * ({@code near}), events visible in first 12 ticks ({@code keys}), minimum frame-width share ({@code share}),
     * frames with audible engine ({@code engine}), off-screen loud sounds at most {@code loud} times on-screen
     * (negative disables), frames smoke-filled at camera ({@link Smoke#smothered}) at most {@code smother}
     * (negative disables).
     */
    record Need(double subject, double near, double keys, double share, double engine, double loud, double smother) {}

    static Plan read(Path file) throws IOException {
        JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        List<Shot> shots = new ArrayList<>();
        for (JsonElement el : o.getAsJsonArray("shots")) shots.add(shot(el.getAsJsonObject()));
        return new Plan(Path.of(o.get("replay").getAsString()).toAbsolutePath(), Path.of(o.get("out").getAsString()).toAbsolutePath(),
                num(o, "width", 1080), num(o, "height", 1920), dbl(o, "fps", 60), num(o, "bitrate", 40_000_000), num(o, "tries", 3),
                dbl(o, "ready", 0.98), o.has("clouds") && !o.get("clouds").isJsonNull() ? o.get("clouds").getAsDouble() : Double.NaN,
                num(o, "distance", 32), Pattern.compile(str(o, "loud", "(?i)blast|explo|boom|detonat|nuke")),
                Pattern.compile(str(o, "engine", ENGINE)), shots);
    }

    private static Shot shot(JsonObject s) {
        List<Camera> cams = new ArrayList<>();
        for (JsonElement el : s.getAsJsonArray("cameras")) cams.add(camera(el.getAsJsonObject()));
        List<double[]> smoke = new ArrayList<>();
        if (s.has("smoke")) {
            for (JsonElement el : s.getAsJsonArray("smoke")) smoke.add(arr(el.getAsJsonArray()));
        }
        JsonObject n = s.has("need") ? s.getAsJsonObject("need") : new JsonObject();
        Need need = new Need(dbl(n, "subject", 0.6), dbl(n, "near", 0.6), dbl(n, "keys", 1), dbl(n, "share", 0.01), dbl(n, "engine", 0),
                dbl(n, "loud", 1), dbl(n, "smother", -1));
        return new Shot(s.get("name").getAsString(), s.get("a").getAsInt(), s.get("b").getAsInt(), str(s, "dim", "minecraft:overworld"),
                num(s, "warm", 200), num(s, "hold", 20), s.has("far") && s.get("far").getAsBoolean(), cams, subject(s.getAsJsonObject("subject")),
                smoke, need, s.has("time") ? s.get("time").getAsLong() : -1);
    }

    private static Camera camera(JsonObject c) {
        String kind = c.get("kind").getAsString();
        boolean subjectAim = c.has("aim") && c.get("aim").isJsonPrimitive();
        List<PathKey> keys = new ArrayList<>();
        if (c.has("keys")) {
            for (JsonElement el : c.getAsJsonArray("keys")) {
                JsonObject k = el.getAsJsonObject();
                Vec3 eye = vec(k, "eye"), aim = vec(k, "aim");
                if (eye == null || aim == null) throw new IllegalArgumentException("path key without eye or aim: " + k);
                keys.add(new PathKey(k.get("t").getAsDouble(), eye, aim, dbl(k, "roll", 0)));
            }
            keys.sort(Comparator.comparingDouble(PathKey::t));
        }
        Vec3 eye = keys.isEmpty() ? vec(c, "eye") : keys.get(0).eye(), aim = keys.isEmpty() ? subjectAim ? null : vec(c, "aim") : keys.get(0).aim();
        return new Camera(kind, c.has("player") ? c.get("player").getAsString() : null, c.has("gui") && c.get("gui").getAsBoolean(),
                dbl(c, "fov", 70), eye, aim, subjectAim || c.has("follow") && c.get("follow").getAsBoolean(),
                vec(c, "around"), c.has("heading") ? new Vec3(arr(c.getAsJsonArray("heading"))[0], 0, arr(c.getAsJsonArray("heading"))[1]) : null,
                c.has("dist") ? arr(c.getAsJsonArray("dist")) : new double[]{60, 90, 130},
                c.has("elev") ? arr(c.getAsJsonArray("elev")) : new double[]{6, 16, 28}, dbl(c, "az_step", 30), dbl(c, "share", 0.25),
                dbl(c, "size", 0), c.has("rise") && c.get("rise").isJsonArray() && !c.getAsJsonArray("rise").isEmpty() ? arr(c.getAsJsonArray("rise")) : new double[]{0},
                keys);
    }

    private static Subject subject(JsonObject s) {
        List<double[]> path = new ArrayList<>();
        if (s.has("path")) {
            for (JsonElement el : s.getAsJsonArray("path")) path.add(arr(el.getAsJsonArray()));
        }
        List<Subject.Key> keys = new ArrayList<>();
        if (s.has("keys")) {
            for (JsonElement el : s.getAsJsonArray("keys")) {
                JsonObject k = el.getAsJsonObject();
                double[] p = arr(k.getAsJsonArray("pos"));
                keys.add(new Subject.Key(k.get("t").getAsInt(), new Vec3(p[0], p[1], p[2]), dbl(k, "size", 16)));
            }
        }
        JsonObject area = s.has("area") ? s.getAsJsonObject("area") : null;
        return new Subject(s.has("uuid") && !s.get("uuid").isJsonNull() ? s.get("uuid").getAsString() : null, dbl(s, "size", 4), path, keys,
                area == null ? null : vec(area, "pos"), area == null ? 0 : dbl(area, "size", 0));
    }

    @Nullable
    private static Vec3 vec(JsonObject o, String k) {
        if (!o.has(k) || !o.get(k).isJsonArray()) return null;
        double[] a = arr(o.getAsJsonArray(k));
        return new Vec3(a[0], a[1], a[2]);
    }

    private static double[] arr(JsonArray a) {
        double[] out = new double[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsDouble();
        return out;
    }

    private static int num(JsonObject o, String k, int def) {
        return o.has(k) ? o.get(k).getAsInt() : def;
    }

    private static double dbl(JsonObject o, String k, double def) {
        return o.has(k) ? o.get(k).getAsDouble() : def;
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) ? o.get(k).getAsString() : def;
    }
}
