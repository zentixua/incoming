package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Звук обычного взрыва по заряду и давлению у уха: громкость растёт с зарядом, тон и длина — по ∛W (Сакс), ракурсы —
 * как одна запись без ступенек, стык с дальней моделью на 640 блоках, удар у уха из модели — там, где ближний ракурс уходит.
 */
class BlastMixTest {
    private static final WeaponType[] SURFACE = {WeaponType.LOITER, WeaponType.ROCKET, WeaponType.DRONE, WeaponType.MISSILE};

    private static BlastSource of(WeaponType w) {
        return BlastSource.of(w, false);
    }

    /**
     * Громкость прямого звука, LUFS: ракурсы одной записи (один вариант) складываются по амплитуде, удар в грудь — своя
     * запись — по мощности.
     */
    private static double direct(BlastMix.Layers l) {
        double a = l.near() * Math.pow(10, BlastMix.NEAR_LUFS / 20) + l.mid() * Math.pow(10, BlastMix.MID_LUFS / 20)
                + l.far() * Math.pow(10, BlastMix.FAR_LUFS / 20), sub = l.sub() * Math.pow(10, BlastMix.SUB_LUFS / 20);
        return 10 * Math.log10(a * a + sub * sub);
    }

    @Test
    void louderWithChargeAtEveryDistance() {
        for (double d = 5; d <= Outdoor.NEAR; d *= 1.3) {
            double prev = Double.NEGATIVE_INFINITY;
            for (double kg : new double[]{1, 3, 6.4, 20, 50, 150, 450, 2000}) {
                double l = BlastMix.loudness(BlastMix.pressure(new BlastSource(null, kg, 0, true, false), d));
                assertTrue(l >= prev, kg + " кг на " + d + " бл: " + l + " < " + prev);
                prev = l;
            }
        }
    }

    /** Время записи — × ∛(W/W₀): тон ∛(W₀/W) (Minecraft сжимает и тянет запись тоном), в пределах движка. */
    @Test
    void durationScalesWithCubeRootOfCharge() {
        assertEquals(1, BlastMix.pitch(BlastMix.REFERENCE_KG), 1e-6);
        for (double kg : new double[]{6.25, 10, 50, 150, 400}) {
            assertEquals(Math.cbrt(kg / BlastMix.REFERENCE_KG), 1 / BlastMix.pitch(kg), 1e-5, kg + " кг");
        }
        // ракета (150 кг) — ниже и дольше шахеда, «Град» (6,4 кг) — выше и короче; «Ланцет» (3 кг) — у предела движка
        assertTrue(BlastMix.pitch(150) < BlastMix.pitch(50) && BlastMix.pitch(6.4) > BlastMix.pitch(50));
        assertEquals(2, BlastMix.pitch(3), 1e-6);
        assertEquals(0.5, BlastMix.pitch(2400), 1e-6);
    }

    @Test
    void perspectivesAreOneRecordingAndQuietDownSmoothly() {
        for (WeaponType w : SURFACE) {
            BlastSource s = of(w);
            double prev = Double.POSITIVE_INFINITY;
            for (double d = 2; d <= Outdoor.NEAR; d += 0.5) {
                BlastMix.Layers l = BlastMix.at(s, d);
                double near = BlastMix.far(d), crack = BlastMix.crack(BlastMix.pressure(s, d));
                // доли ракурсов в сумме 1
                assertEquals(1, (1 - near) * crack + (1 - near) * (1 - crack) + near, 1e-9);
                double v = direct(l);
                assertTrue(v <= prev + 0.05, w + ": громче на " + d + " бл");
                // громкость — по давлению: ракурсы с ударом в грудь вместе дают её (ближний и средний сведены одинаково,
                // дальний тише) до самого потолка — файлы её не обрезают
                assertEquals(BlastMix.loudness(BlastMix.pressure(s, d)), v, 1.6, w + " на " + d);
                assertTrue(l.near() <= 1 && l.mid() <= 1 && l.far() <= 1 && l.sub() <= 1 && l.pulse() <= 1);
                prev = v;
            }
        }
    }

    @Test
    void edgeOfTheNearModelIsTheFarModel() {
        for (WeaponType w : SURFACE) {
            BlastMix.Layers l = BlastMix.at(of(w), Outdoor.NEAR);
            assertEquals(0, l.near(), 1e-6);
            assertEquals(0, l.mid(), 1e-6);
            assertEquals(BlastMix.v640(of(w)), l.far(), 1e-6, w + ": дальний ракурс на краю — громкость дальней модели");
        }
    }

    /** Вблизи — ближний ракурс со скачком; где скачок нарастает медленно (слабее ~1 кПа), его играет модель. */
    @Test
    void crackIsTheNearPerspectiveUpCloseAndTheModelFurther() {
        BlastSource shahed = of(WeaponType.DRONE);
        BlastMix.Layers close = BlastMix.at(shahed, 10);
        assertTrue(close.near() > 0.9 && close.mid() < 0.05 && close.pulse() < 0.05, "в 10 м: " + close);
        assertEquals(close.near(), close.sub(), 1e-6, "удар в грудь — низ ближнего ракурса");
        assertTrue(BlastMix.crack(10_000) > 0.99 && BlastMix.crack(300) < 0.1);
        BlastMix.Layers mid = BlastMix.at(shahed, 400);
        assertTrue(mid.pulse() > 0.1 && mid.far() > mid.near() + mid.mid(), "в 400 м: " + mid);
        // меньше заряд — скачок слабее на том же расстоянии: ближний уходит раньше
        assertTrue(BlastMix.at(of(WeaponType.LOITER), 150).near() < BlastMix.at(of(WeaponType.MISSILE), 150).near());
    }
}
