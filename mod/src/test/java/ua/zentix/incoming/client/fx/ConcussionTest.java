package ua.zentix.incoming.client.fx;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.ConcussionDose;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Контузия на экране ({@link Concussion}): включается по дозе, удары складываются, вид и сила — из настроек. */
class ConcussionTest {
    @AfterEach
    void clear() {
        Concussion.reset();
    }

    /** Ниже порога (доза 0) экрана не трогает: ни удара, ни уровня. */
    @Test
    void noDoseNoEffect() {
        assertEquals(0, Concussion.hit(0, 0));
        assertEquals(0, Concussion.hit(-0.3, 0));
        for (int t = 0; t < 80; t++) assertEquals(0, Concussion.level(t), "тик " + t);
    }

    /**
     * Удары залпа складываются долями: вместе сильнее любого одного и не больше полного; кончились (длина отклика
     * модели) — уровня нет.
     */
    @Test
    void salvoAddsUpToOne() {
        Concussion.hit(0.6, 0);
        double one = Concussion.level(2);
        Concussion.hit(0.6, 0);
        Concussion.hit(0.9, 0);
        double three = Concussion.level(2);
        assertTrue(one > 0 && three > one && three <= 1, one + " → " + three);
        int end = (int) Math.ceil(20 * ConcussionDose.durationSeconds(0.9)) + 1;
        for (int t = 0; t < end; t++) Concussion.tick();
        assertEquals(0, Concussion.level(end), 1e-12);
    }

    /** Фронт прошёл тело полтика назад — отклик начался полтика назад, а не в начале тика, где его заметили. */
    @Test
    void startsAtTheFrontNotAtTheTick() {
        Concussion.hit(1, 0.5);
        double early = Concussion.level(0);
        Concussion.reset();
        Concussion.hit(1, 0);
        assertTrue(early > Concussion.level(0), "полтика назад — уже сильнее: " + early);
    }

    /** Картинка: у нуля — ничего; размытие по квадрату уровня уходит раньше серости и краёв; растёт с высотой кадра. */
    @Test
    void lookFollowsTheLevel() {
        double[] zero = Concussion.look(0, 1080), half = Concussion.look(0.5, 1080), full = Concussion.look(1, 1080);
        for (double v : zero) assertEquals(0, v);
        assertEquals(Concussion.BLUR_PX, full[0], 1e-9);
        assertEquals(0.25 * full[0], half[0], 1e-9, "размытие — по квадрату");
        assertEquals(0.5 * full[1], half[1], 1e-9);
        assertEquals(0.5 * full[2], half[2], 1e-9);
        assertEquals(2 * full[0], Concussion.look(1, 2160)[0], 1e-9, "в кадре вдвое выше — вдвое больше пикселей");
    }

    /** Сила — настройка × ванильные «Искажения экрана»; любая из них в 0 — экрана не трогает. */
    @Test
    void settingsScaleIt() {
        assertEquals(1, Concussion.strength(1, 1));
        assertEquals(0, Concussion.strength(0, 1));
        assertEquals(0, Concussion.strength(1, 0));
        assertEquals(0.25, Concussion.strength(0.5, 0.5), 1e-12);
    }

    /**
     * Программа прохода объявляет каждую свою {@code uniform} (необъявленную ваниль не задаёт — шейдер читал бы ноль), в
     * JSON нет лишних; нет «++;» и вложенного «?:» — их ломает разбор GLSL Veil (client-fx.md).
     */
    @Test
    void programDeclaresItsUniforms() throws Exception {
        Path dir = Path.of(ConcussionTest.class.getResource("/assets/incoming/shaders/program").toURI());
        JsonObject program = JsonParser.parseString(Files.readString(dir.resolve("concussion.json"))).getAsJsonObject();
        Set<String> declared = new HashSet<>();
        program.getAsJsonArray("uniforms").forEach(u -> declared.add(u.getAsJsonObject().get("name").getAsString()));
        program.getAsJsonArray("samplers").forEach(u -> declared.add(u.getAsJsonObject().get("name").getAsString()));
        String fsh = Files.readString(dir.resolve("concussion.fsh"));
        Set<String> used = new HashSet<>();
        Matcher m = Pattern.compile("uniform\\s+\\w+\\s+(\\w+)\\s*;").matcher(fsh);
        while (m.find()) used.add(m.group(1));
        // вершинная программа — ванильная «blur»: ProjMat, InSize, OutSize, BlurDir
        used.addAll(Set.of("ProjMat", "InSize", "OutSize", "BlurDir"));
        assertEquals(used, declared);
        assertFalse(fsh.matches("(?s).*\\+\\+\\s*;.*"), "«++;»");
        // проход пишет кадр целиком: смешивания нет
        JsonObject blend = program.getAsJsonObject("blend");
        assertEquals("one", blend.get("srcrgb").getAsString());
        assertEquals("zero", blend.get("dstrgb").getAsString());
    }
}
