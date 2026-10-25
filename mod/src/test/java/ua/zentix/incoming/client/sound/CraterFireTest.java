package ua.zentix.incoming.client.sound;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.sound.Spectra;
import ua.zentix.incoming.sound.Spectrum;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraterFireTest {
    private static CloudModel model(WeaponType weapon) {
        return new CloudModel(BlastSource.of(weapon, false), CloudModel.Air.STANDARD);
    }

    @Test
    void burningFuelDoesNotFadeOrGoSilentAtTheOldRecordingBoundaries() {
        for (WeaponType weapon : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE}) {
            CloudModel m = model(weapon);
            assertTrue(m.poolSeconds > 30);
            // The old recording faded after 9 s and restarted every 13 s, with weapon-dependent gaps/overlaps.
            for (double t : new double[]{1, 9, 12.9, 13, 13.1, 26, 30}) {
                assertEquals(m.poolWatts, CraterFire.heat(ua.zentix.incoming.client.fx.FuelBurn.at(m.pool, net.minecraft.core.BlockPos.ZERO), t, 0), 1e-6, weapon + " at " + t);
            }
        }
    }

    @Test
    void envelopeArrivesAtSoundSpeedAndEndsWithTheFuel() {
        CloudModel m = model(WeaponType.DRONE);
        double distance = 2 * Noise.SOUND;
        assertEquals(0, CraterFire.heat(ua.zentix.incoming.client.fx.FuelBurn.at(m.pool, net.minecraft.core.BlockPos.ZERO), 1.9, distance));
        assertEquals(m.poolWatts, CraterFire.heat(ua.zentix.incoming.client.fx.FuelBurn.at(m.pool, net.minecraft.core.BlockPos.ZERO), 2.1, distance), 1e-6);
        assertEquals(m.poolWatts / 2, CraterFire.heat(ua.zentix.incoming.client.fx.FuelBurn.at(m.pool, net.minecraft.core.BlockPos.ZERO), 0.9 * m.poolSeconds + 2, distance), 1e-6);
        assertEquals(0, CraterFire.heat(ua.zentix.incoming.client.fx.FuelBurn.at(m.pool, net.minecraft.core.BlockPos.ZERO), m.poolSeconds + 2, distance));
    }

    /** Громкость пожара {@code watts} Вт в {@code d} м открытым путём ночью — как в {@link CraterFire#tick} (без преграды). */
    private static float volume(double watts, double d, Sources.Source source) {
        return Sources.volume(source.power(Sources.flame(watts, source.spectrum())), new Vec3(d, 64, 0), new Vec3(0, 65.62, 0), Propagation.Weather.NIGHT);
    }

    /**
     * Сумма пожаров в четверти звучит так, как её посчитали, до самого потолка игры, — и не упирается раньше в громкость
     * файла (громче файла Minecraft не играет): петли пожара собраны на потолке (ревью #456, круг 5 — собранные по пику
     * −1 дБFS, −21,6 LUFS, они обрезали 300 пожаров по 2 МВт в 30 м на 5,1 дБ). Выше потолка — потолок у суммы всех
     * четвертей, а не у каждой. На оси четверти и посередине между двумя.
     */
    @Test
    void summedFiresPlayAsComputedUpToTheCeiling() throws java.io.IOException {
        double file = SoundAssetsTest.loopMean("blast.fire");
        assertEquals(Loudness.CEILING_LUFS, file, 0.3, "петли пожара — на потолке");
        float v = volume(2e6, 30, Sources.BLAST_FIRE);
        boolean crossed = false;
        for (int n : new int[]{1, 10, 100, 300, 1000, 3000, 10000}) {
            // как посчитано: громкость в игре одного пожара (по закону) и сумма n по мощности
            double wanted = Loudness.ENGINE_LUFS + 20 * Math.log10(v / Loudness.fileGain(Spectra.BLAST_FIRE_LUFS)) + 10 * Math.log10(n);
            for (double azimuth : new double[]{0, Math.PI / 4}) {
                Quarters q = new Quarters();
                for (int i = 0; i < n; i++) q.add(azimuth, v, 1, 1);
                double power = 0;
                for (int k = 0; k < Quarters.N; k++) power += q.volume(k) * q.volume(k);
                // как звучит: файлы с их настоящей средней громкостью на громкостях петель
                double played = file + 10 * Math.log10(power);
                if (wanted <= Loudness.CEILING_LUFS) assertEquals(wanted, played, 0.3, n + " пожаров, азимут " + azimuth + ": сумма ниже потолка — как посчитана");
                else assertEquals(Loudness.CEILING_LUFS, played, 0.3, n + " пожаров, азимут " + azimuth + ": выше потолка — потолок у суммы");
                crossed |= wanted > Loudness.CEILING_LUFS;
            }
            if (n == 300) assertTrue(wanted < Loudness.CEILING_LUFS, "сцена ревью — ниже потолка: " + wanted);
        }
        assertTrue(crossed, "проверка доходит до потолка");
    }

    /**
     * Одиночный пожар звучит так же, как в 6763a1c8 (средняя громкость петли по закону пламени): файлы стали громче
     * (на потолке), а громкость в игре — та же, на любом расстоянии. Звучит — настоящая средняя громкость файлов на
     * громкости, что даёт клиент; у 6763a1c8 — его паспорт (спектр, −21,6 LUFS) и средняя его файлов по замеру
     * (−21,726, −21,704, −21,718, −21,712 — ревью круга 5; по мощности −21,715).
     */
    @Test
    void aSingleFireSoundsAsIn6763a1c8() throws java.io.IOException {
        Spectrum before = Spectrum.of(-24.1, -6.5, -6.2, -7.6, -6.3, 0.0, -3.8, -18.7);
        Sources.Source then = new Sources.Source(Double.NaN, before, -21.6);
        double file = SoundAssetsTest.loopMean("blast.fire"), fileThen = -21.715;
        for (double watts : new double[]{2e5, 2e6, 2e7}) {
            for (double d : new double[]{5, 15, 40, 100, 300}) {
                double now = file + 20 * Math.log10(volume(watts, d, Sources.BLAST_FIRE));
                double was = fileThen + 20 * Math.log10(volume(watts, d, then));
                assertEquals(was, now, 0.2, watts + " Вт в " + d + " м: средняя громкость в игре, LUFS");
            }
        }
    }

    /**
     * Слышно ли пожар — решает звук у уха, а не громкость файла (ревью R11): настоящее решение о запуске
     * ({@link CraterFire#volume}, потом {@link Quarters#plays}) у 0,2, 2 и 20 МВт на 5 м – 12 км ночью одно и то же у файла
     * на потолке и у того же файла, собранного на 8,1 дБ тише; нигде пожар, который звучал в 6763a1c8 (громкость файла
     * −21,6 LUFS не тише {@code AUDIBLE}), теперь не молчит; граница — где пожар тонет в фоне во всех октавах.
     */
    @Test
    void audibilityIsAtTheEarNotInTheFile() {
        Sources.Source quiet = new Sources.Source(Double.NaN, Spectra.BLAST_FIRE, Spectra.BLAST_FIRE_LUFS - 8.1);
        Sources.Source then = new Sources.Source(Double.NaN, Spectrum.of(-24.1, -6.5, -6.2, -7.6, -6.3, 0.0, -3.8, -18.7), -21.6);
        Vec3 ear = new Vec3(0, 65.62, 0);
        for (double watts : new double[]{2e5, 2e6, 2e7}) {
            double last = 0, lastThen = 0;
            for (int d = 5; d <= 12000; d += 5) {
                Vec3 at = new Vec3(d, 64, 0);
                boolean now = plays(CraterFire.volume(Sources.BLAST_FIRE, watts, at, ear, Propagation.Weather.NIGHT));
                assertEquals(now, plays(CraterFire.volume(quiet, watts, at, ear, Propagation.Weather.NIGHT)), watts + " Вт в " + d + " м: файл тише — решение то же");
                boolean wasPlaying = volume(watts, d, then) >= VoiceBudget.AUDIBLE;
                assertTrue(!wasPlaying || now, watts + " Вт в " + d + " м: в 6763a1c8 звучал — звучит и теперь");
                double gain = Sources.gain(Sources.BLAST_FIRE.power(Sources.flame(watts, Spectra.BLAST_FIRE)), at, ear, Propagation.Weather.NIGHT);
                assertEquals(gain > 0, now, watts + " Вт в " + d + " м: звучит, пока не тонет в фоне");
                if (now) last = d;
                if (wasPlaying) lastThen = d;
            }
            System.out.printf(java.util.Locale.ROOT, "fire %.0f W heard to %.0f m (6763a1c8: %.0f m)%n", watts, last, lastThen);
            assertTrue(last > 300 && last >= lastThen, watts + " Вт: слышно дальше 300 м и не ближе, чем в 6763a1c8");
        }
    }

    /** Решение о запуске петли одного пожара громкостью {@code volume}: как в {@link BlastSounds#fireLoops}. */
    private static boolean plays(float volume) {
        Quarters q = new Quarters();
        q.add(0, volume, 1, 1);
        return q.plays(0);
    }

    @Test
    void noFuelOrWaterMeansNoFireSound() {
        CloudModel dry = model(WeaponType.ROCKET);
        CloudModel water = new CloudModel(BlastSource.of(WeaponType.DRONE, false), CloudModel.Air.STANDARD, true);
        for (CloudModel m : new CloudModel[]{dry, water}) {
            assertEquals(0, CraterFire.heat(ua.zentix.incoming.client.fx.FuelBurn.at(m.pool, net.minecraft.core.BlockPos.ZERO), 1, 0));
            assertEquals(0, CraterFire.heat(ua.zentix.incoming.client.fx.FuelBurn.at(m.pool, net.minecraft.core.BlockPos.ZERO), 30, 0));
        }
    }
}
