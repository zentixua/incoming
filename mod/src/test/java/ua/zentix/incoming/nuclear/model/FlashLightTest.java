package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.client.nuclear.NukeExposure;

import static org.junit.jupiter.api.Assertions.*;

class FlashLightTest {
    @Test
    void sealedEyesReceiveNothingAndOpeningsFollowVanillaFalloff() {
        assertEquals(0, FlashLight.opening(0));
        assertEquals(1, FlashLight.opening(15));
        assertEquals(1.0 / 3, FlashLight.opening(10), 1e-12);
        assertEquals(1.0 / 9, FlashLight.opening(5), 1e-12);
        double previous = 0;
        for (int sky = 0; sky <= 15; sky++) {
            double t = FlashLight.transmission(false, sky, 0.7, 0.4);
            assertTrue(t >= previous);
            previous = t;
        }
        assertEquals(0, FlashLight.transmission(false, 0, 0.7, 0.4));
        assertEquals(0.3, FlashLight.transmission(false, 15, 0.7, 0.4), 1e-12);
        assertEquals(0.7, FlashLight.transmission(true, 0, 0.7, 0.4));
        assertEquals(0, FlashLight.transmission(false, 15, 0.4, 0.7));
        assertEquals(0, FlashLight.Eye.DARK.strength());
        assertEquals(1, FlashLight.Eye.DARK.peak(1200));
        assertEquals(0, EyeInjury.dazzleSeconds(FlashLight.Eye.DARK.fluence(), 1000, 1));
    }

    @Test
    void logFollowHasTheSameTimeConstantAtEveryFrameRate() {
        double expected = Math.exp(Math.log(0.01) + (Math.log(1200) - Math.log(0.01)) / Math.E);
        for (int frames : new int[]{1, 6, 12, 24, 48}) {
            double e = 1200;
            for (int i = 0; i < frames; i++) e = FlashLight.follow(e, 0.01, NukeExposure.ADAPT_S / frames, NukeExposure.ADAPT_S);
            assertEquals(expected, e, 1e-10);
            assertEquals(e, FlashLight.follow(e, 10, 0, NukeExposure.ADAPT_S));
        }
    }

    @Test
    void cameraCatchesTheActualPulseAtTheEye() {
        for (double[] scene : new double[][]{{1000, 4550, 2400, 0.17, 3}, {15, 1400, 592, 1, 1.5}}) {
            double y = scene[0], ambient = scene[3], e = Sight.exposure(ambient, 0), target = e;
            double dt = 1.0 / 120;
            for (int i = 1; i <= Math.round(scene[4] / dt); i++) {
                double t = i * dt;
                MushroomModel.Shape s = MushroomModel.shape(t, y, scene[2], false, 0, null);
                double distance = Math.hypot(scene[1], s.capY());
                double fire = MushroomModel.light(s, y, false, distance) * ThermalModel.transmittance(distance, 20_000);
                target = Sight.exposure(ambient, fire);
                e = FlashLight.follow(e, target, dt, NukeExposure.ADAPT_S);
            }
            assertTrue(e / target >= 0.5 && e / target <= 2, "exposure at " + scene[4] + " s: " + e / target);
        }
    }

    @Test
    void escapingPowerFollowsThePulseThroughRollup() {
        for (double y : new double[]{1, 15, 1000}) for (boolean surface : new boolean[]{false, true}) {
            double tm = FireballModel.secondMaximumSeconds(y), rPeak = FireballModel.maxRadius(y, surface);
            double peak = Math.pow(FireballModel.PEAK_K, 4) * rPeak * rPeak;
            // Includes the reported 1 Mt 20–33 s collapse, beyond the nominal 10 t_max interval.
            for (int i = 0; i <= 200; i++) {
                double t = tm * Math.pow(40, i / 200.0);
                MushroomModel.Shape s = MushroomModel.shape(t, y, surface ? 0 : 240 * Math.cbrt(y), surface, 0, null);
                double temp = FlashLight.temperature(t, y, surface, s.capR());
                double actual = Math.pow(temp, 4) * s.capR() * s.capR() / peak;
                double pulse = FireballModel.brightness(t, y) / FireballModel.brightness(tm, y);
                assertEquals(pulse, actual, pulse * 1e-10, y + " kt at " + t);
                double[] rgb = new double[4];
                FlashLight.source(s, y, surface, rgb);
                assertEquals(rgb[3], MushroomModel.light(s, y, surface, s.capR()), rgb[3] * 1e-12);
                assertTrue(Double.isFinite(rgb[3]) && rgb[3] >= 0);
            }
        }
    }

    @Test
    void visibleOutputStaysWithinTwofoldOfFigureReadingsAfterSpectralConversion() {
        // G&D 1977 fig. 7.84, independently read normalized power ordinates (est., graph resolution).
        // Keep the existing peak calibration; these compare pulse shape, not absolute radiant yield.
        double[] power = {1, 0.40, 0.21, 0.14, 0.10, 0.074, 0.060, 0.047, 0.040, 0.033};
        for (double y : new double[]{1, 15, 1000}) for (boolean surface : new boolean[]{false, true}) {
            double tm = FireballModel.secondMaximumSeconds(y), rPeak = FireballModel.maxRadius(y, surface);
            for (int tau = 1; tau <= power.length; tau++) {
                MushroomModel.Shape s = MushroomModel.shape(tau * tm, y, surface ? 0 : 240 * Math.cbrt(y), surface, 0);
                double referenceK = FireballModel.PEAK_K * Math.pow(power[tau - 1] * rPeak * rPeak / (s.capR() * s.capR()), 0.25);
                double expected = BlackBody.luminance(referenceK) * s.capR() * s.capR();
                double actual = MushroomModel.light(s, y, surface, s.capR()) * s.capR() * s.capR();
                assertTrue(actual / expected >= 0.5 && actual / expected <= 2,
                        y + " kt, surface=" + surface + ", tau=" + tau + ": " + actual / expected);
            }
        }
    }

    @Test
    void dayAndNightDazzleUseIndependentFigureRangeFixtures() {
        // G&D 1977 fig. 12.88a/b, HOB 10,000 ft; graph readings in miles (±1 mile).
        double[][] rows = {{1, 5.7, 40.3}, {10, 12.7, 60}, {100, 19.8, 67}, {1000, 15.1, 53.7}};
        for (double[] row : rows) for (int dark = 0; dark <= 1; dark++) {
            double d = Math.hypot(row[1 + dark] * 1609.344, 3048);
            double q = ThermalModel.fluenceCalPerCm2(d, row[0], false, ThermalModel.VISIBILITY_CLEAR);
            double admitted = FlashLight.transmission(true, 15, 1, 1);
            assertEquals(10, EyeInjury.dazzleSeconds(q * admitted, row[0], dark), 1e-8);
            assertTrue(EyeInjury.dazzleSeconds(q * FlashLight.opening(5), row[0], dark) < 10);
        }
    }
}
