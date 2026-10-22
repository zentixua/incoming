package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.sound.Spectra;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Мерка громкости файлов: та же, что в сборке звуков; звуки источника громче неё — приводятся вниз без потерь. */
class LoudnessTest {
    @Test
    void engineLufsIsTheBuildScriptOne() throws IOException {
        Path script = Path.of(System.getProperty("user.dir")).getParent().resolve("tools/build_sounds.py");
        Matcher m = Pattern.compile("(?m)^ENGINE_LUFS = (-?[\\d.]+)").matcher(Files.readString(script));
        assertTrue(m.find(), "ENGINE_LUFS в build_sounds.py");
        assertEquals(Double.parseDouble(m.group(1)), Loudness.ENGINE_LUFS, 1e-9);
    }

    /** Файл громче мерки — множитель меньше 1; на мерке — 1. */
    @Test
    void sourceFilesAreScaledDown() {
        assertEquals(1, Loudness.fileGain(Loudness.ENGINE_LUFS), 1e-12);
        for (double lufs : new double[]{Spectra.SIREN_LUFS, Spectra.NUKE_ALARM_LUFS, Spectra.GRID_FAIL_LUFS, Spectra.GRID_HUM_LUFS,
                Spectra.BLAST_FIRE_LUFS, Spectra.DEBRIS_FALL_LUFS, Spectra.NUKE_COLLAPSE_LUFS, Spectra.NUKE_LAUNCH_LUFS}) {
            assertTrue(Loudness.fileGain(lufs) <= 1, "файл " + lufs + " LUFS тише мерки — громче единицы не сыграть");
        }
    }
}
