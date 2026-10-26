package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.FuelFireball;
import ua.zentix.incoming.strike.WeaponType;

import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Optical budget including cooling: regression for ordinary impact light on hills kilometres away (FLASH). */
class FireballRadiationTest {
    private static double integrate(double from, double to, DoubleUnaryOperator power) {
        int steps = 4000;
        double dt = (to - from) / steps, sum = 0;
        for (int i = 0; i < steps; i++) sum += power.applyAsDouble(from + (i + 0.5) * dt) * dt;
        return sum;
    }

    private static double area(double r) {
        return 4 * Math.PI * r * r;
    }

    /** Independent of the model's series: integrate Planck's law over wavelength, Simpson at 0.5 nm. */
    private static double visibleNumeric(double t) {
        double h = 6.62607015e-34, c = 299792458, k = 1.380649e-23, sum = 0;
        for (int i = 0; i <= 600; i++) {
            double wavelength = (400 + 0.5 * i) * 1e-9;
            double power = 2 * Math.PI * h * c * c / (Math.pow(wavelength, 5) * Math.expm1(h * c / (wavelength * k * t)));
            sum += power * (i == 0 || i == 600 ? 1 : i % 2 == 0 ? 2 : 4) * 0.5e-9 / 3;
        }
        return sum;
    }

    @Test
    void visibleBandIsNotBolometricOrPhotopicPower() {
        for (double t : new double[]{798, 1000, CloudModel.FUEL_BALL_K, 1500, 2000, 2500, 3000}) {
            double exact = visibleNumeric(t);
            assertEquals(exact, FireballRadiation.visibleExitance(t), exact * 1e-8, "Planck 400–700 nm, " + t + " K");
        }
        assertEquals(0.08091, FireballRadiation.visibleExitance(3000) / FireballRadiation.totalExitance(3000), 1e-5);
        assertEquals(0.000494, FireballRadiation.visibleExitance(CloudModel.FUEL_BALL_K)
                / FireballRadiation.totalExitance(CloudModel.FUEL_BALL_K), 1e-6);
    }

    @Test
    void emittedHighExplosiveLightFitsOneBudgetForEveryCharge() {
        assertTrue(FireballRadiation.VISIBLE_SHARE > 0 && FireballRadiation.VISIBLE_SHARE <= 0.01);
        double[] light = new double[4];
        for (double kg : new double[]{6.4, 10, 50, 150, 450, 500}) {
            CloudModel m = new CloudModel(BlastSource.air(kg), CloudModel.Air.STANDARD);
            double budget = FireballRadiation.VISIBLE_SHARE * kg * 4.184e6;
            DoubleUnaryOperator oldPower = t -> area(m.ballRadius(t)) * visibleNumeric(m.ballKelvin(t));
            DoubleUnaryOperator emitted = t -> {
                m.emittedLight(t, light);
                // Actual CloudModel output, not only the coefficient: the old ballLight without ε fails this check.
                return oldPower.applyAsDouble(t) * light[3] / BlackBody.luminance(m.ballKelvin(t));
            };
            double energy = integrate(0, m.heBallSeconds, emitted) + integrate(m.heBallSeconds, m.fireSeconds, emitted);
            double old = integrate(0, m.heBallSeconds, oldPower) + integrate(m.heBallSeconds, m.fireSeconds, oldPower);
            assertTrue(old > 40 * budget, kg + " kg: the old blackbody exceeded the adopted budget");
            assertEquals(budget, energy, budget * 5e-4, kg + " kg: visible-band budget including the whole cooling tail");
            assertTrue(energy < 0.01 * kg * 4.184e6, kg + " kg: below 1% of the charge energy");
            for (double t = 0; t < m.heBallSeconds; t += m.heBallSeconds / 1000) {
                double e = m.radiation.he(t, m.heBallRadius(t), m.heBallKelvin(t));
                assertTrue(e >= 0 && e <= 1, kg + " kg: a bounded effective emissivity at " + t);
            }
        }
    }

    @Test
    void broadPulseKeepsTheMeasuredIntegralAnchors() {
        assertEquals(1, integrate(0, 0.2, FireballRadiation::pulse), 1e-6);
        assertEquals(0.0015 / 0.0024, integrate(0, 0.1, FireballRadiation::pulse), 1e-6,
                "Khristoforov p.110: 0.15% at reduced time 0.1, 0.24% at 0.2");
        assertEquals(0, FireballRadiation.pulse(0), 0, "continuous onset, no sub-frame initial peak");
        assertEquals(1.0 / 15, FireballRadiation.PULSE_PEAK, 1e-15);
        assertEquals(10, FireballRadiation.pulse(FireballRadiation.PULSE_PEAK), 1e-12,
                "one broad maximum, fixed by duration and budget");
        for (double kg : new double[]{6.4, 10, 50, 150, 450, 500}) {
            CloudModel m = new CloudModel(BlastSource.air(kg), CloudModel.Air.STANDARD);
            assertEquals(0, m.radiation.he(m.heBallSeconds, m.heBall, CloudModel.HE_BALL_END), 0);
            double[] before = new double[4], after = new double[4];
            for (double u : new double[]{0, FireballRadiation.PULSE_PEAK, 0.2}) {
                double edge = u * Math.cbrt(kg), step = 1e-8 * Math.cbrt(kg);
                m.ballLight(edge - step, before);
                m.ballLight(edge + step, after);
                assertEquals(before[3], after[3], 0.01, kg + " kg: no discontinuity at pulse knot " + u);
            }
        }
    }

    @Test
    void broadPulseIsResolvedAcrossFrameRatesAndFrameOffsets() {
        // Sample the actual HE source at arbitrary frame phases. A 5–8 ms prompt spike fails the peak bound at
        // ordinary FPS. The broad pulse keeps both peak and integrated energy stable even at 20 FPS (tick rate).
        for (double kg : new double[]{6.4, 10, 50, 150, 450, 500}) {
            CloudModel m = new CloudModel(BlastSource.air(kg), CloudModel.Air.STANDARD);
            double budget = FireballRadiation.VISIBLE_SHARE * kg * 4.184e6;
            double peak = 10 * budget / m.radiation.heTimeScale();
            double[] light = new double[4];
            for (int fps : new int[]{20, 30, 60, 144, 240}) {
                for (int phase = 0; phase < 16; phase++) {
                    double energy = 0, sampledPeak = 0, dt = 1.0 / fps;
                    for (double t = phase * dt / 16; t < m.heBallSeconds; t += dt) {
                        m.emittedLight(t, light);
                        double power = area(m.heBallRadius(t)) * FireballRadiation.visibleExitance(m.heBallKelvin(t))
                                * light[3] / BlackBody.luminance(m.heBallKelvin(t));
                        energy += power * dt;
                        sampledPeak = Math.max(sampledPeak, power);
                    }
                    String label = kg + " kg at " + fps + " FPS, frame phase " + phase;
                    assertEquals(budget, energy, budget * 0.03, label + ": integrated visible energy");
                    assertTrue(sampledPeak >= 0.85 * peak && sampledPeak <= peak * (1 + 1e-12),
                            label + ": broad peak is always captured within 15%");
                }
            }
        }
    }

    @Test
    void fuelCoolingSharesTheFuelRadiationBudget() {
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE}) {
            CloudModel m = new CloudModel(BlastSource.of(w, false), CloudModel.Air.STANDARD);
            double budget = CloudModel.RADIANT * FuelFireball.ballKg(m.source.fuelKg()) * m.source.fuel().heatJPerKg;
            double burning = integrate(0, m.fuelBallSeconds, t -> area(m.fuelBallRadius(t))
                    * FireballRadiation.SIGMA * Math.pow(CloudModel.FUEL_BALL_K, 4));
            double tail = integrate(m.burnSeconds, m.fireSeconds, t -> area(m.ballRadius(t))
                    * FireballRadiation.SIGMA * Math.pow(m.coolKelvin(t), 4));
            assertTrue(burning < budget, w + ": burning alone was within budget");
            assertTrue(burning + tail > budget, w + ": the old cooling tail exceeded the budget");
            assertEquals(budget, burning * m.radiation.fuel() + tail * m.radiation.cooling(), budget * 5e-4);
            assertEquals(m.radiation.fuel(), m.radiation.cooling(), 0, "one coefficient, no jump back to a blackbody");
            // After HE ends, the actual visible source uses the same greybody coefficient.
            double t = (m.heBallSeconds + m.fuelBallSeconds) / 2;
            double[] out = new double[4];
            m.ballLight(t, out);
            assertEquals(BlackBody.luminance(CloudModel.FUEL_BALL_K) * m.radiation.fuel(), out[3], 1e-12);
            t = m.burnSeconds + 0.1;
            m.ballLight(t, out);
            assertEquals(BlackBody.luminance(m.coolKelvin(t)) * m.radiation.cooling(), out[3], 1e-12);
        }
    }

    @Test
    void noChargeOrFuelHasNoEmission() {
        CloudModel m = new CloudModel(BlastSource.air(0), CloudModel.Air.STANDARD);
        double[] out = new double[4];
        for (double t : new double[]{0, 0.1, 1, 10}) {
            m.emittedLight(t, out);
            for (double c : out) assertEquals(0, c, 0);
        }
    }
}
