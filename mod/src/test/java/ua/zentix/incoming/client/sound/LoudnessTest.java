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
 * Мерка громкости: та же, что в сборке звуков; громкость растёт с уровнем у уха до потолка — ближнего взрыва с 1 psi —
 * и ни один звук не упирается в громкость своего файла раньше (3.5.0: файлы моторов на мерке, и удар поджига ракеты
 * ближе ~45 м к пусковой, рёв ускорителя ближе ~20 м звучали одинаково).
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
        // потолок — ближний ракурс взрыва с 1 psi: громче в игре нет ничего
        assertEquals(BlastMix.NEAR_LUFS, Loudness.CEILING_LUFS, 1e-9);
    }

    private static double constant(String script, String name) {
        Matcher m = Pattern.compile("(?m)^" + name + " = (-?[\\d.]+)").matcher(script);
        assertTrue(m.find(), name + " в build_sounds.py");
        return Double.parseDouble(m.group(1));
    }

    /** Мотор громче на 2 дБ у уха — на 1 дБ громче в игре, и так до самого потолка; на потолке — файл целиком. */
    @Test
    void motorsGrowUpToTheCeiling() {
        double prev = 0;
        for (double level = Propagation.NIGHT; level <= Loudness.CEILING_LEVEL; level += 2) {
            double v = Loudness.motor(Loudness.gain(level, BIG[0]));
            assertTrue(v > prev, level + " дБА: " + v + " не громче " + prev);
            if (prev > 0) assertEquals(1, 20 * Math.log10(v / prev), 1e-4, level + " дБА");
            prev = v;
        }
        assertEquals(1, Loudness.motor(Loudness.gain(Loudness.CEILING_LEVEL, BIG[0])), 1e-6);
        assertEquals(1, Loudness.motor(Loudness.gain(Loudness.CEILING_LEVEL + 30, BIG[0])), 1e-6);
        assertEquals(Loudness.ENGINE_LUFS, Loudness.MOTOR_LUFS + 20 * Math.log10(Loudness.motor(Loudness.gain(Loudness.REF, BIG[0]))),
                1e-5, "на REF — мерка");
    }

    /**
     * Удар поджига ускорителя ракеты громче {@link Loudness#REF} на десятки метров вокруг (РДТТ по NASA SP-8072) — тот
     * самый случай, что упирался в файл: в 5 м он звучит громче, чем в 15 м, а в 15 м — громче, чем в 25 м.
     */
    @Test
    void missileLaunchIsLouderUpClose() {
        Noise.Rocket motor = WeaponType.MISSILE.spec().airframe().noise().motor();
        double lw = motor.power(FlightPhase.BOOST, 0);
        double v5 = launchVolume(lw, 5), v15 = launchVolume(lw, 15), v25 = launchVolume(lw, 25);
        assertTrue(Loudness.gain(level(lw, Spectra.LAUNCH_BOOSTER, 25), BIG[0]) > 1, "в 25 м — громче мерки");
        assertTrue(v5 > v15 && v15 > v25, "в 5 м " + v5 + ", в 15 м " + v15 + ", в 25 м " + v25);
    }

    private static double launchVolume(double lw, double d) {
        return Loudness.motor(Loudness.gain(level(lw, Spectra.LAUNCH_BOOSTER, d), BIG[0]));
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
     * Звуки мира по закону источника: файл не тише того, как громко источник звучит в метре (или потолка, если тот
     * ниже), — иначе всё ближе звучало бы одинаково.
     */
    @Test
    void sourceFilesHoldTheirLoudestLevel() {
        for (Sources.Source s : List.of(Sources.SIREN, Sources.GRID_FAIL, Sources.GRID_SPARK, Sources.GRID_HUM,
                Sources.GRID_POWER_DOWN, Sources.GRID_POWER_UP)) {
            double l = level(s.lwA() - s.spectrum().aWeighting(), s.spectrum(), 1);
            double want = Math.min(s.ceilingLufs(), Loudness.ENGINE_LUFS + (l - Loudness.REF) / 2);
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
            assertEquals(Math.min(1, old) * Loudness.fileGain(Loudness.MOTOR_LUFS),
                    Loudness.motor(Loudness.gain(level, BIG[0], Loudness.ENGINE_LUFS)), 1e-6, "МБР: петля ускорителя");
        }
    }
}
