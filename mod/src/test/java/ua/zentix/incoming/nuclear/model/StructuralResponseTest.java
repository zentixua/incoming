package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StructuralResponseTest {
    @Test void impulseAndLongPulseLimits() {
        assertEquals(0.000005, StructuralResponse.amplification(0.00001), 1e-12);
        assertEquals(2, StructuralResponse.amplification(1e8), 1e-7);
        assertEquals(0, StructuralResponse.amplification(0));
    }

    @Test void shortPulseRetainsMomentumAfterPressureEnds() {
        double duration = 0.01, mass = 2400, resistance = 100000, limit = 0.05;
        double peak = StructuralResponse.displacement(100000, duration, mass, resistance, limit);
        double endUnderConstantPeak = 0.5 * 100000 / mass * duration * duration;
        assertTrue(peak > endUnderConstantPeak);
        assertTrue(peak < limit);
        assertTrue(StructuralResponse.displacement(100000, 1, mass, resistance, limit) > limit);
    }

    @Test void massAndConnectionGeometryMatter() {
        double light = StructuralResponse.displacement(100000, 0.01, 600, 100000, 0.05);
        double heavy = StructuralResponse.displacement(100000, 0.01, 2400, 100000, 0.05);
        assertTrue(light > heavy);
        assertTrue(StructuralResponse.displacement(100000, 0.01, 2400, 200000, 0.05) < heavy);
    }

    @Test void dragConservesImpulseAndCannotOutrunFlow() {
        assertEquals(1, StructuralResponse.driftSpeed(4800, 1, 2400, 100));
        assertEquals(4, StructuralResponse.driftSpeed(4800, 1, 600, 100));
        assertEquals(3, StructuralResponse.driftSpeed(4800, 1, 600, 3));
    }
}
