package ua.zentix.incoming.strike;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.sound.Noise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Тяга стартового ускорителя во времени: без скачков, спад — до отделения, один закон у света и звука. */
class ThrustCurveTest {
    /** Ускорители с пусковой — шахед и «Томагавк». */
    private static final WeaponType[] BOOSTED = {WeaponType.DRONE, WeaponType.MISSILE};

    @Test
    void tailOffIsRealAndEndsAtSeparation() {
        ThrustCurve missile = WeaponType.MISSILE.spec().airframe().thrust();
        assertNotNull(missile);
        // 5 % настоящих 12 с работы Mk 106 — 0,6 с, 12 тиков; отделение — в конце фазы разгона
        assertEquals(12, missile.tail(), 1e-9);
        assertEquals(1, missile.share(FlightPhase.BOOST, missile.burn() - missile.tail()), 1e-12);
        assertEquals(0, missile.share(FlightPhase.BOOST, missile.burn()), 1e-12, "к отделению ускоритель догорел");
        assertEquals(0, missile.share(FlightPhase.CLIMB, 0));
        // спад к концу — с нулевым наклоном: в последнюю десятую тика тяги меньше полпроцента
        assertTrue(missile.share(FlightPhase.BOOST, missile.burn() - 0.1) < 0.005);
    }

    @Test
    void thrustHasNoSteps() {
        for (WeaponType w : BOOSTED) {
            ThrustCurve c = w.spec().airframe().thrust();
            assertNotNull(c, w.name());
            double prev = c.share(FlightPhase.IGNITION, 0), dt = 1e-3;
            // поджиг до выхода на режим, потом весь разгон до отделения и дальше
            for (double a = dt; a <= c.rise(); a += dt) {
                double s = c.share(FlightPhase.IGNITION, a);
                assertTrue(Math.abs(s - prev) < 0.01, w + " поджиг " + a);
                prev = s;
            }
            assertEquals(1, prev, 1e-3);
            prev = 1;
            for (double a = 0; a <= c.burn() + 1; a += dt) {
                double s = c.share(FlightPhase.BOOST, a);
                assertTrue(Math.abs(s - prev) < 0.01, w + " разгон " + a + ": " + prev + " → " + s);
                assertTrue(s <= prev + 1e-12, w + " тяга на разгоне не растёт");
                prev = s;
            }
            assertEquals(0, prev);
        }
    }

    @Test
    void soundFollowsTheSameCurve() {
        for (WeaponType w : BOOSTED) {
            WeaponSpec.Airframe a = w.spec().airframe();
            Noise.Rocket motor = a.noise().motor();
            assertNotNull(motor);
            ThrustCurve c = a.thrust();
            assertEquals(c, motor.curve(), w + ": звук и пламя — по одной кривой");
            double full = motor.power(FlightPhase.BOOST, 0);
            for (double age = c.burn() - c.tail(); age < c.burn(); age += 0.25) {
                // мощность струи ∝ тяге при той же скорости истечения
                assertEquals(10 * Math.log10(c.share(FlightPhase.BOOST, age)), motor.power(FlightPhase.BOOST, age) - full, 1e-9);
            }
            assertEquals(Noise.SILENT, motor.power(FlightPhase.BOOST, c.burn()));
        }
    }
}
