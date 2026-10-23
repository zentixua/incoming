package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.sound.Spectra;
import ua.zentix.incoming.sound.Spectrum;
import ua.zentix.incoming.strike.WeaponType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Мерка громкости: та же, что в сборке звуков; громкость растёт с уровнем у уха до потолка — громкости ванильного TNT, у
 * взрыва с 1 psi — и ни один звук не упирается в громкость своего файла раньше (3.5.0: файлы моторов на мерке, и удар
 * поджига ракеты ближе ~45 м к пусковой, рёв ускорителя ближе ~20 м звучали одинаково). Окно громкости — {@link WindowTest}.
 */
class LoudnessTest {
    private static final double[] BIG = {200};

    @Test
    void numbersAreTheBuildScriptOnes() throws IOException {
        String script = Files.readString(Path.of(System.getProperty("user.dir")).getParent().resolve("tools/build_sounds.py"));
        assertEquals(constant(script, "ENGINE_LUFS"), Loudness.ENGINE_LUFS, 1e-9);
        assertEquals(constant(script, "MOTOR_LUFS"), Loudness.MOTOR_LUFS, 1e-9);
        Matcher m = Pattern.compile("(?m)^ROCKET_LAUNCH_LUFS = MOTOR_LUFS - ([\\d.]+)").matcher(script);
        assertTrue(m.find(), "ROCKET_LAUNCH_LUFS в build_sounds.py");
        assertEquals(Loudness.MOTOR_LUFS - Double.parseDouble(m.group(1)), Loudness.ROCKET_LAUNCH_LUFS, 1e-9);
        m = Pattern.compile("(?m)^LAUNCH_LUFS = MOTOR_LUFS - ([\\d.]+)").matcher(script);
        assertTrue(m.find(), "LAUNCH_LUFS в build_sounds.py");
        assertEquals(Loudness.MOTOR_LUFS - Double.parseDouble(m.group(1)), Loudness.LAUNCH_LUFS, 1e-9);
        m = Pattern.compile("(?m)^DRILL_LUFS = MOTOR_LUFS - ([\\d.]+)").matcher(script);
        assertTrue(m.find(), "DRILL_LUFS в build_sounds.py");
        assertEquals(Loudness.MOTOR_LUFS - Double.parseDouble(m.group(1)), Loudness.DRILL_LUFS, 1e-9);
        // потолок — громкость ванильного TNT (random/explode1–4: −9,6…−11,0 LUFS); файлы взрывов его не громче
        assertEquals(-10, Loudness.CEILING_LUFS, 0.5);
        assertTrue(BlastMix.NEAR_LUFS <= Loudness.CEILING_LUFS && BlastMix.MID_LUFS <= Loudness.CEILING_LUFS);
    }

    private static double constant(String script, String name) {
        Matcher m = Pattern.compile("(?m)^" + name + " = (-?[\\d.]+)").matcher(script);
        assertTrue(m.find(), name + " в build_sounds.py");
        return Double.parseDouble(m.group(1));
    }

    /**
     * Мотор громче на 2 дБ у уха — на 1 дБ громче в игре, и так до самого верха окна; на верхе — файл целиком, на потолке.
     * Окно у {@link Loudness#CEILING_LEVEL} — самое высокое (взрыв с 1 psi рядом).
     */
    @Test
    void motorsGrowUpToTheCeiling() {
        double prev = 0, top = Loudness.CEILING_LEVEL;
        for (double level = Propagation.NIGHT; level <= top; level += 2) {
            double v = Loudness.motor(Loudness.windowed(level, top));
            assertTrue(v > prev, level + " дБА: " + v + " не громче " + prev);
            if (prev > 0) assertEquals(1, 20 * Math.log10(v / prev), 1e-4, level + " дБА");
            prev = v;
        }
        assertEquals(1, Loudness.motor(Loudness.windowed(top, top)), 1e-6);
        assertEquals(1, Loudness.motor(Loudness.windowed(top + 30, top)), 1e-6);
        assertEquals(Loudness.CEILING_LUFS, Loudness.MOTOR_LUFS + 20 * Math.log10(Loudness.motor(Loudness.windowed(top, top))), 1e-6);
    }

    /**
     * Удар поджига ускорителя ракеты громче {@link Loudness#REF} на десятки метров вокруг (РДТТ по NASA SP-8072) — тот
     * самый случай, что упирался в файл: в 15 м он звучит громче, чем в 25 м, а в 25 м — громче, чем в 45 м (файл поджига
     * на 1 дБ ниже потолка: ближе ~15 м — он целиком).
     */
    @Test
    void missileLaunchIsLouderUpClose() {
        Noise.Rocket motor = WeaponType.MISSILE.spec().airframe().noise().motor();
        double lw = motor.power(FlightPhase.BOOST, 0);
        double v5 = launchVolume(lw, 15), v15 = launchVolume(lw, 25), v25 = launchVolume(lw, 45);
        assertTrue(Loudness.windowed(level(lw, Spectra.LAUNCH_BOOSTER, 25), Loudness.CEILING_LEVEL) > 1, "в 25 м — громче мерки");
        assertTrue(v5 > v15 && v15 > v25, "в 15 м " + v5 + ", в 25 м " + v15 + ", в 45 м " + v25);
    }

    private static double launchVolume(double lw, double d) {
        // окно у взрыва с 1 psi: громче поджиг звучит только ближе — файл громкость не обрезает
        return Loudness.volume(Loudness.windowed(level(lw, Spectra.LAUNCH_BOOSTER, d), Loudness.CEILING_LEVEL), Loudness.LAUNCH_LUFS);
    }

    private static double level(double lw, Spectrum spectrum, double d) {
        double[] bands = new double[Propagation.BANDS];
        return Propagation.level(lw, spectrum, Propagation.Path.over(d, 2, Propagation.Weather.NIGHT), bands);
    }

    /**
     * Хлопок отделения ускорителя и катапульта собраны на мерке ({@link Loudness#ENGINE_LUFS}): громче {@link Loudness#REF}
     * их не бывает и в метре — иначе их файл обрезал бы громкость.
     */
    @Test
    void quietLaunchCuesNeverReachTheirFile() {
        double sep = WeaponType.MISSILE.spec().airframe().noise().separation() - Spectra.BOOSTER_SEPARATE.aWeighting();
        double cat = WeaponType.LOITER.spec().airframe().noise().catapult() - Spectra.LOITER_LAUNCH.aWeighting();
        assertTrue(level(sep, Spectra.BOOSTER_SEPARATE, 1) < Loudness.REF, "отделение в 1 м");
        assertTrue(level(cat, Spectra.LOITER_LAUNCH, 1) < Loudness.REF, "катапульта в 1 м");
    }

    /**
     * Звуки мира по закону источника: файл не тише того, как громко источник звучит в метре при окне у взрыва с 1 psi
     * (или потолка, если тот ниже), — иначе всё ближе звучало бы одинаково. В тишине (окно у {@link Loudness.Window#FLOOR})
     * громкий источник в метре упирается в файл раньше: треск дуги подстанции — ближе ~2,5 м.
     */
    @Test
    void sourceFilesHoldTheirLoudestLevel() {
        for (Sources.Source s : List.of(Sources.SIREN, Sources.GRID_FAIL, Sources.GRID_SPARK, Sources.GRID_HUM,
                Sources.GRID_POWER_DOWN, Sources.GRID_POWER_UP)) {
            double l = level(s.lwA() - s.spectrum().aWeighting(), s.spectrum(), 1);
            double want = Math.min(s.ceilingLufs(), Loudness.CEILING_LUFS + (l - Loudness.CEILING_LEVEL) / 2);
            assertTrue(s.fileLufs() >= want - 0.05, s + ": файл " + s.fileLufs() + " LUFS, а в метре нужно " + want);
        }
        assertEquals(1, Loudness.fileGain(Loudness.ENGINE_LUFS), 1e-12);
    }

    /** Ядерный удар не меняется: у его звуков потолок прежний — мерка, как в 3.5.0. */
    @Test
    void nukeKeepsItsCeiling() {
        for (Sources.Source s : List.of(Sources.NUKE_ALARM, Sources.NUKE_COLLAPSE, Sources.RUIN_DEBRIS, Sources.NUKE_LAUNCH)) {
            assertEquals(Loudness.ENGINE_LUFS, s.ceilingLufs(), 1e-12, s.toString());
        }
        for (double level = 100; level < 180; level += 5) {
            double old = Math.min(1, Math.pow(10, (level - Loudness.REF) / 40));
            assertEquals(old, Loudness.gain(level, BIG[0], Loudness.ENGINE_LUFS), 1e-12);
            assertEquals(Math.min(1, old * Loudness.fileGain(Spectra.NUKE_LAUNCH_LUFS)),
                    Loudness.volume(Loudness.gain(level, BIG[0], Loudness.ENGINE_LUFS), Spectra.NUKE_LAUNCH_LUFS), 1e-6);
            assertEquals(Math.min(1, old) * Loudness.fileGain(EngineSound.Layer.BOOSTER.fileLufs()),
                    Loudness.volume(Loudness.gain(level, BIG[0], Loudness.ENGINE_LUFS), EngineSound.Layer.BOOSTER.fileLufs()), 1e-6,
                    "МБР: петля ускорителя");
        }
    }
}
