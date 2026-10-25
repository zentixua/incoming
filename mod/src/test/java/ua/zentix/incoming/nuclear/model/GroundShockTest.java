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
        // Independent TM (1.2): a0=50*.14*1000*10^-3.75 / 10 = 0.1244795587 g, not ft/s².
        assertEquals(0.124479558702725 * 9.80665, s.peakAccelerationMps2(), 1e-10);
        assertEquals(16.002058959483, s.frequencyHz(), 1e-10);
        assertEquals(0.062491958224375, s.durationSeconds(), 1e-12);
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
                assertTrue(s.peakAccelerationMps2() <= previous.peakAccelerationMps2());
                assertTrue(s.perceptibility() <= previous.perceptibility());
                assertTrue(s.frequencyHz() <= previous.frequencyHz());
                assertEquals(r / GroundShock.waveSpeed(medium), s.arrivalSeconds());
                previous = s;
            }
        }
        double fullDepth = 1.4 * 0.3048 * Math.cbrt(50 / 0.45359237);
        for (double depth : new double[]{0, fullDepth}) {
            double a = GroundShock.coupling(new GroundShock.Source(50, depth - 1e-6, GroundShock.Medium.ROCK));
            double b = GroundShock.coupling(new GroundShock.Source(50, depth + 1e-6, GroundShock.Medium.ROCK));
            assertEquals(a, b, 1e-9);
        }
    }

    @Test
    void timeDomainPulseMatchesTmPeakAccelerationAsWellAsVelocity() {
        var s = GroundShock.sample(new GroundShock.Source(453.59237, 0, GroundShock.Medium.SOIL), 30.48);
        double expectedAcceleration = 0.124479558702725 * 9.80665;
        double dt = s.durationSeconds() * 1e-6, maxAcceleration = 0;
        for (int i = 0; i <= 6000; i++) {
            double t = s.arrivalSeconds() + s.durationSeconds() * i / 6000;
            double a = (GroundShock.velocityMps(s, t + dt) - GroundShock.velocityMps(s, t - dt)) / (2 * dt);
            maxAcceleration = Math.max(maxAcceleration, Math.abs(a));
        }
        assertEquals(expectedAcceleration, maxAcceleration, expectedAcceleration * 1e-7,
                "a frequency label alone must not hide a different acceleration in the windowed pulse");
        for (var medium : GroundShock.Medium.values()) for (double r : new double[]{30, 100, 300, 1000}) {
            var pulse = GroundShock.sample(new GroundShock.Source(2400, 10, medium), r);
            assertTrue(pulse.durationSeconds() > 0 && pulse.durationSeconds() <= pulse.arrivalSeconds(),
                    medium + " at " + r + " m: ground shock became prolonged rocking");
            assertEquals(0, GroundShock.velocityMps(pulse, pulse.arrivalSeconds() + pulse.durationSeconds()), 1e-12);
        }
    }

    @Test
    void perceptibilityNeverGrowsWithRangeAndRecipientsEndAtItsContour() {
        // Spec: every value falls with distance. Frequency is highest at the crater (1.6 c/R): a band that zeroed
        // high frequencies left the strongest motion "imperceptible" in rock and saturated ground (review round 2).
        for (var medium : GroundShock.Medium.values())
            for (double[] charge : new double[][]{{6.4, 0}, {16, 0}, {50, 1}, {150, 0}, {2400, 0}, {2400, 14}}) {
                var source = new GroundShock.Source(charge[0], charge[1], medium);
                String what = medium + " " + charge[0] + " kg at " + charge[1] + " m depth";
                var previous = GroundShock.sample(source, 0);
                assertEquals(1, previous.perceptibility(), what + " at the source");
                double reach = GroundShock.feltRangeM(source);
                for (double r = 0.5; r < 2 * reach; r *= 1.02) {
                    var s = GroundShock.sample(source, r);
                    assertTrue(s.perceptibility() <= previous.perceptibility(), what + ": felt more at " + r + " m");
                    // The perception estimate never exceeds the flat-weighted PPV: the PPV contour stays an outer bound.
                    assertTrue(GroundShock.kbFmax(s.peakVelocityMps(), s.frequencyHz())
                            <= 0.6 * 1000 * s.peakVelocityMps() / Math.sqrt(2) * (1 + 1e-12), what);
                    if (r >= reach) assertEquals(0, s.perceptibility(), what + ": felt beyond the recipient range");
                    previous = s;
                }
                assertEquals(0, GroundShock.sample(source, reach).perceptibility(), what);
                double inside = GroundShock.sample(source, reach * (1 - 1e-6)).perceptibility();
                assertTrue(inside > 0 && inside < 1e-9, what + ": recipient range is not the perception contour, " + inside);
            }
    }

    @Test
    void bunkerBombInRockIsFullyFeltNextToTheCrater() {
        // Review round 2: 2.4 t in rock at 30 m -- PPV ~3.6 m/s at 160 Hz -- was perceptibility 0.
        for (double depth : new double[]{0, 14}) {
            var s = GroundShock.sample(new GroundShock.Source(2400, depth, GroundShock.Medium.ROCK), 30);
            assertTrue(s.frequencyHz() > 80, "the case must stay above the DIN 4150-2 1--80 Hz band");
            assertEquals(1, s.perceptibility(), "2.4 t in rock at 30 m, depth " + depth);
        }
    }

    @Test
    void perceptionIsTheDinKbEstimateWithoutBandEdges() {
        // DIN 4150-2:1999-06 annex C.7.3, blasting, single event: v_max 4 mm/s at 14 Hz, c_F 0.8 (table 3 row 4a)
        // gives KB*_Fmax 2.1 by eqs. (6), (7). The model uses row 4b (no floor resonance): c_F 0.6, same formula.
        assertEquals(2.1 * 0.6 / 0.8, GroundShock.kbFmax(0.004, 14), 0.005);
        double v = 0.001, flat = 0.6 * 1000 * v / Math.sqrt(2);
        // Eq. (1) at the high-pass corner f0 = 5.6 Hz.
        assertEquals(flat / Math.sqrt(2), GroundShock.kbFmax(v, 5.6), 1e-12);
        double previous = 0;
        for (double hz = 0.01; hz < 5000; hz *= 1.01) {
            double kb = GroundShock.kbFmax(v, hz);
            assertTrue(kb > previous && kb < flat, hz + " Hz");
            previous = kb;
        }
        // Outside the DIN 1--80 Hz signal band the same weighting continues (estimate): no zero and no step.
        for (double edge : new double[]{1, 80})
            assertEquals(GroundShock.kbFmax(v, edge * (1 - 1e-9)), GroundShock.kbFmax(v, edge * (1 + 1e-9)), flat * 1e-8);
        assertTrue(GroundShock.kbFmax(v, 160) > 0.99 * flat);
        assertTrue(GroundShock.kbFmax(v, 0.5) > 0);
        assertEquals(0, GroundShock.kbFmax(v, 0));
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
        assertEquals(0, GroundShock.velocityMps(s, s.arrivalSeconds() + s.durationSeconds()), 1e-14);
        assertEquals(0, GroundShock.velocityMps(s, 60));
        double max = 0, integral = 0;
        for (int i = 0; i < 6000; i++) {
            double v = GroundShock.velocityMps(s, s.arrivalSeconds() + i / (6000 * s.frequencyHz()));
            max = Math.max(max, Math.abs(v));
            integral += v;
        }
        assertEquals(s.peakVelocityMps(), max, 1e-10);
        assertEquals(0, integral, 1e-10);
        assertEquals(0, GroundShock.velocityMps(s, s.arrivalSeconds() + 1e-8 / s.frequencyHz()), s.peakVelocityMps() * 1e-12);
        var empty = new GroundShock.Source(0, 0, GroundShock.Medium.SOIL);
        assertEquals(0, GroundShock.feltRangeM(empty));
        assertEquals(0, GroundShock.velocityMps(GroundShock.sample(empty, 0), 0));
        assertEquals(0, GroundShock.sample(empty, 0).durationSeconds());
        assertTrue(Double.isFinite(GroundShock.sample(new GroundShock.Source(50, 1, GroundShock.Medium.SOIL), 0).peakVelocityMps()));
    }
}
