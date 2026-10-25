package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GroundShockTest {
    @Test
    void publishedImperialExampleIsConvertedBeforeApplyingPowerLaw() {
        // Independent hand calculation: W=1000 lb, R=100 ft => Z=10 ft/lb^(1/3), sandy loam n=2.75.
        // Contact V=160*.14*10^-2.75=0.0398334587849 ft/s, then convert output to m/s.
        var source = new GroundShock.Source(453.59237, 0, GroundShock.Medium.SOIL);
        var s = GroundShock.sample(source, 30.48);
        assertEquals(0.0121412382376491, s.peakVelocityMps(), 1e-12);
        assertEquals(0.1, s.arrivalSeconds(), 1e-12);
        assertEquals(0.14, GroundShock.coupling(source));
        assertEquals(1, GroundShock.coupling(new GroundShock.Source(453.59237, 4.2672, source.medium())), 1e-12);
    }

    @Test
    void depthAndPerceptionGatesAreContinuousAndAmplitudesDecrease() {
        for (var medium : GroundShock.Medium.values()) {
            var source = new GroundShock.Source(50, 1, medium);
            var previous = GroundShock.sample(source, 1);
            for (double r = 1.1; r < 100_000; r *= 1.2) {
                var s = GroundShock.sample(source, r);
                assertTrue(s.peakVelocityMps() <= previous.peakVelocityMps());
                assertTrue(s.perceptibility() <= previous.perceptibility());
                assertTrue(s.frequencyHz() <= previous.frequencyHz());
                assertEquals(r / GroundShock.waveSpeed(medium), s.arrivalSeconds());
                previous = s;
            }
            double reach = GroundShock.feltRangeM(source);
            assertEquals(GroundShock.PERCEPTION_START_MPS, GroundShock.sample(source, reach).peakVelocityMps(), 1e-15);
            assertEquals(0, GroundShock.sample(source, reach * (1 + 1e-6)).perceptibility());
            assertTrue(GroundShock.sample(source, reach * (1 - 1e-6)).perceptibility() < 1e-9);
        }
        double fullDepth = 1.4 * 0.3048 * Math.cbrt(50 / 0.45359237);
        for (double depth : new double[]{0, fullDepth}) {
            double a = GroundShock.coupling(new GroundShock.Source(50, depth - 1e-6, GroundShock.Medium.ROCK));
            double b = GroundShock.coupling(new GroundShock.Source(50, depth + 1e-6, GroundShock.Medium.ROCK));
            assertEquals(a, b, 1e-9);
        }
    }

    @Test
    void earthPrecedesAirOnlyWhenItsSpeedExceedsTheAirFrontsPathAverage() {
        // A decelerating shock has no single U: the comparison is R / integral(dr/U(r)), not its endpoint speed.
        for (double kg : new double[]{6.4, 50, 2400}) for (double r : new double[]{1, 30, 300, 1000}) {
            double airArrival = ShockAtBody.chemical(kg, true, r).arrivalSeconds();
            double averageAirSpeed = r / airArrival;
            for (var medium : GroundShock.Medium.values()) {
                var ground = GroundShock.sample(new GroundShock.Source(kg, 10, medium), r);
                assertEquals(GroundShock.waveSpeed(medium) > averageAirSpeed, ground.arrivalSeconds() < airArrival);
            }
        }
        assertTrue(GroundShock.sample(new GroundShock.Source(2400, 10, GroundShock.Medium.ROCK), 300).arrivalSeconds()
                < ShockAtBody.chemical(2400, true, 300).arrivalSeconds());
        assertTrue(GroundShock.sample(new GroundShock.Source(50, 0, GroundShock.Medium.LOOSE), 30).arrivalSeconds()
                > ShockAtBody.chemical(50, true, 30).arrivalSeconds());
    }

    @Test
    void finitePulseHasNoPrearrivalMotionNoJumpAndThePromisedPeak() {
        var s = GroundShock.sample(new GroundShock.Source(2400, 10, GroundShock.Medium.ROCK), 300);
        assertEquals(0, GroundShock.velocityMps(s, -1));
        assertEquals(0, GroundShock.velocityMps(s, Math.nextDown(s.arrivalSeconds())));
        assertEquals(0, GroundShock.velocityMps(s, s.arrivalSeconds()));
        assertEquals(0, GroundShock.velocityMps(s, s.arrivalSeconds() + 1 / s.frequencyHz()), 1e-14);
        assertEquals(0, GroundShock.velocityMps(s, 60));
        double max = 0, integral = 0;
        for (int i = 0; i < 6000; i++) {
            double v = GroundShock.velocityMps(s, s.arrivalSeconds() + i / (6000 * s.frequencyHz()));
            max = Math.max(max, Math.abs(v));
            integral += v;
        }
        assertEquals(s.peakVelocityMps(), max, 1e-10);
        assertEquals(0, integral, 1e-10);
        assertEquals(0, GroundShock.velocityMps(s, s.arrivalSeconds() + 1e-6), 1e-10);
        var empty = new GroundShock.Source(0, 0, GroundShock.Medium.SOIL);
        assertEquals(0, GroundShock.feltRangeM(empty));
        assertEquals(0, GroundShock.velocityMps(GroundShock.sample(empty, 0), 0));
        assertTrue(Double.isFinite(GroundShock.sample(new GroundShock.Source(50, 1, GroundShock.Medium.SOIL), 0).peakVelocityMps()));
    }
}
