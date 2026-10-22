package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Записи ядерки играются в своей скорости: тон в пределах ±25 % ({@code SHIFT_MAX} сборки звуков, разделы {@code NATIVE}).
 * Величину ядерки дают законы — скачок с положительной фазой модели, эхо, ветер, разрушения, гул шторма, — а не
 * замедленная запись: прежде удар вблизи шёл записью ближнего взрыва на тоне 0,55 и далёким раскатом на 0,75.
 */
class NukeTonesTest {
    /** Из {@code tools/build_sounds.py}: наибольший сдвиг записи. */
    private static double shiftMax() throws Exception {
        Matcher m = Pattern.compile("(?m)^SHIFT_MAX = ([0-9.]+)").matcher(Files.readString(Path.of("../tools/build_sounds.py")));
        assertTrue(m.find(), "SHIFT_MAX в build_sounds.py");
        return Double.parseDouble(m.group(1));
    }

    @Test
    void nukeRecordingsAtNativeSpeed() throws Exception {
        double max = shiftMax();
        float[][] all = {NukeSounds.Schedule.CRACK_TONES, {NukeSounds.Schedule.BOOM_TONE}, NukeAfter.ROAR_TONES, {NukeAfter.WIND_TONE}};
        for (float[] tones : all) {
            for (float t : tones) assertTrue(t > 0 && Math.max(t, 1 / t) <= max + 1e-6, "тон " + t + " — больше ±" + Math.round((max - 1) * 100) + " %");
        }
    }

    /** В разделах ядерки сборка не принимает причин для большего сдвига (нет «Сакса на глаз»). */
    @Test
    void buildRefusesReasonsInNukeSections() throws Exception {
        String build = Files.readString(Path.of("../tools/build_sounds.py"));
        Matcher m = Pattern.compile("(?m)^NATIVE = \\{([^}]*)}").matcher(build);
        assertTrue(m.find(), "NATIVE в build_sounds.py");
        for (String part : new String[]{"\"nuke\"", "\"after\"", "\"sirens\""}) assertTrue(m.group(1).contains(part), "NATIVE: " + part);
        assertTrue(build.contains("(not why or SECTION in NATIVE)"), "speed() — без причин в NATIVE");
    }
}
