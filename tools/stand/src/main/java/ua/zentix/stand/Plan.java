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
 * План прогона ({@code stand-plan.json}): мир, что считать снарядом, пробы и шаги по порядку. План пишет stand.py из
 * scenes.json — вся геометрия сцен (где камера, куда залп, откуда пуск) уже в числах, обвязка их только исполняет.
 *
 * @param seed       сид плоского мира
 * @param dayTime    время суток мира (6000 — полдень)
 * @param settleS    после входа в мир — столько секунд до первого шага (сетки чанков у точки появления)
 * @param limitS     весь прогон — не дольше (потом выход с событием {@code limit})
 * @param projectiles типы сущностей-снарядов ({@code incoming:drone}): по их уходу из мира у цели считаются попадания
 * @param warheads   типы урона подрыва боевой части ({@code incoming:strike}): только такой взрыв снаряда — попадание
 * @param probes     «класс#метод» статических методов без аргументов, чья строка пишется раз в секунду, если есть
 * @param once       то же, один раз после входа в мир (шейдерпак Iris и т. п.)
 */
record Plan(long seed, long dayTime, double settleS, double limitS, Set<String> projectiles, Set<String> warheads,
            List<String> probes, List<String> once, List<Step> steps) {

    /** Шаг плана. */
    sealed interface Step {}

    /** Камера-наблюдатель в точку {@code eye} (глаз), взгляд на {@code look}; потом {@code settleS} на сетки чанков. */
    record Tp(double[] eye, double[] look, double settleS) implements Step {}

    /** Команда серверу (оператор, консоль): итог — событие {@code cmd}. */
    record Cmd(String command) implements Step {}

    /** Ждать столько секунд. */
    record Wait(double seconds) implements Step {}

    /** Кадр PNG в {@code shots/<name>.png} (кадр с ним в замер не идёт). */
    record Shot(String name) implements Step {}

    /** Окно замера: кадры от начала шага столько секунд — окно {@code name}. */
    record Window(String name, double seconds) implements Step {}

    /**
     * Залп: цель (x, z) и радиус, в котором уход снаряда из мира — попадание этого залпа; команды — со сдвигом
     * {@code delays} секунд от начала шага каждая (шаг не ждёт: ждёт {@link AwaitSalvo}); {@code expect} — сколько
     * попаданий у залпа.
     */
    record Salvo(String id, double x, double z, double radius, int expect, List<String> commands, double[] delays) implements Step {}

    /** Ждать конца залпа: попаданий — сколько ждали; или нет снарядов в мире и попаданий {@code quietS} секунд; или срок. */
    record AwaitSalvo(String id, double timeoutS, double quietS) implements Step {}

    /**
     * Окно от n-го попадания залпа {@code salvo} (1 — первое) на {@code seconds}; кадры — через {@code shots} секунд от
     * попадания (имена {@code <prefix>_<с>s}). Ждёт попадания не дольше {@code timeoutS}.
     */
    record ImpactWindow(String name, String salvo, int n, double seconds, double[] shots, String prefix, double timeoutS) implements Step {}

    /** Отметка в событиях. */
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
