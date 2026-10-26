package ua.zentix.stand;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Run plan ({@code stand-plan.json}): world, projectile definition, probes and ordered steps. stand.py
 * writes it from scenes.json; scene geometry (camera, target, launch point) is already numeric, harness
 * only executes it.
 *
 * @param seed flat-world seed
 * @param dayTime world time of day (6000 noon)
 * @param settleS seconds after world entry before first step (spawn chunk meshes)
 * @param limitS maximum run seconds, then exit with {@code limit}
 * @param projectiles projectile entity types ({@code incoming:drone}), whose target removals count hits
 * @param warheads warhead damage types ({@code incoming:strike}); only these projectile explosions count
 * @param probes "class#method" static no-argument methods returning strings, logged each second if present
 * @param once same, once after world entry (Iris shader pack etc.)
 */
record Plan(long seed, long dayTime, double settleS, double limitS, Set<String> projectiles, Set<String> warheads,
            List<String> probes, List<String> once, List<Step> steps) {

    /** Plan step. */
    sealed interface Step {}

    /** Move spectator camera eye to {@code eye}, looking at {@code look}; then {@code settleS} for chunk meshes. */
    record Tp(double[] eye, double[] look, double settleS) implements Step {}

    /** Server command (operator, console): {@code cmd} result event. */
    record Cmd(String command) implements Step {}

    /** Wait this many seconds. */
    record Wait(double seconds) implements Step {}

    /** PNG shot in {@code shots/<name>.png}; exclude its frame from measurement. */
    record Shot(String name) implements Step {}

    /** Measurement window {@code name}: frames for this many seconds from step start. */
    record Window(String name, double seconds) implements Step {}

    /**
     * Salvo: target (x, z), radius where projectile removal counts its hit; commands offset by each
     * {@code delays} seconds from step start (step does not wait; {@link AwaitSalvo} does); {@code expect} hits.
     */
    record Salvo(String id, double x, double z, double radius, int expect, List<String> commands, double[] delays) implements Step {}

    /** Wait for expected hits, or no world projectiles/hits for {@code quietS} seconds, or timeout. */
    record AwaitSalvo(String id, double timeoutS, double quietS) implements Step {}

    /**
     * Window from nth hit of {@code salvo} (1 first), lasting {@code seconds}; shots {@code shots} seconds
     * after impact (names {@code <prefix>_<s>s}). Wait at most {@code timeoutS} for impact.
     */
    record ImpactWindow(String name, String salvo, int n, double seconds, double[] shots, String prefix, double timeoutS) implements Step {}

    /**
     * Wait until {@code seconds} after last hit of {@code salvo} (later hit moves deadline). Finish more
     * than {@link Director#LATE_S} late: emit {@code late}, run invalid (results.py also checks against all run hits).
     */
    record Since(String salvo, double seconds) implements Step {}

    /** Event mark. */
    record Mark(String name) implements Step {}

    static Plan read(Path file) throws IOException {
        JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        List<Step> steps = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("steps")) steps.add(step(e.getAsJsonObject()));
        return new Plan(o.get("seed").getAsLong(), o.has("day_time") ? o.get("day_time").getAsLong() : 6000,
                o.has("settle_s") ? o.get("settle_s").getAsDouble() : 5, o.has("limit_s") ? o.get("limit_s").getAsDouble() : 1800,
                Set.copyOf(strings(o.getAsJsonArray("projectiles"))), Set.copyOf(strings(o.getAsJsonArray("warheads"))),
                strings(o.getAsJsonArray("probes")),
                strings(o.getAsJsonArray("once")), List.copyOf(steps));
    }

    static Step step(JsonObject s) {
        String type = s.get("do").getAsString();
        return switch (type) {
            case "tp" -> new Tp(doubles(s.getAsJsonArray("eye")), doubles(s.getAsJsonArray("look")), num(s, "settle_s", 1.5));
            case "cmd" -> new Cmd(s.get("cmd").getAsString());
            case "wait" -> new Wait(s.get("s").getAsDouble());
            case "shot" -> new Shot(s.get("name").getAsString());
            case "window" -> new Window(s.get("name").getAsString(), s.get("s").getAsDouble());
            case "salvo" -> {
                List<String> cmds = strings(s.getAsJsonArray("cmds"));
                double[] delays = s.has("delays") ? doubles(s.getAsJsonArray("delays")) : new double[cmds.size()];
                if (delays.length != cmds.size()) throw new IllegalArgumentException("salvo " + s.get("id") + ": delays and cmds differ in length");
                yield new Salvo(s.get("id").getAsString(), s.get("x").getAsDouble(), s.get("z").getAsDouble(), s.get("radius").getAsDouble(),
                        s.get("expect").getAsInt(), cmds, delays);
            }
            case "await" -> new AwaitSalvo(s.get("salvo").getAsString(), num(s, "timeout_s", 240), num(s, "quiet_s", 30));
            case "impact_window" -> new ImpactWindow(s.get("name").getAsString(), s.get("salvo").getAsString(), s.has("n") ? s.get("n").getAsInt() : 1,
                    s.get("s").getAsDouble(), s.has("shots") ? doubles(s.getAsJsonArray("shots")) : new double[0],
                    s.has("prefix") ? s.get("prefix").getAsString() : s.get("name").getAsString(), num(s, "timeout_s", 240));
            case "since" -> new Since(s.get("salvo").getAsString(), s.get("s").getAsDouble());
            case "mark" -> new Mark(s.get("name").getAsString());
            default -> throw new IllegalArgumentException("unknown step: " + type);
        };
    }

    private static double num(JsonObject o, String key, double fallback) {
        return o.has(key) ? o.get(key).getAsDouble() : fallback;
    }

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) for (JsonElement e : a) out.add(e.getAsString());
        return List.copyOf(out);
    }

    private static double[] doubles(JsonArray a) {
        double[] out = new double[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsDouble();
        return out;
    }
}
