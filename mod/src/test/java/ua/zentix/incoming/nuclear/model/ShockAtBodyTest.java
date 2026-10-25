package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShockAtBodyTest {
    @Test
    void briefExamplesRetainTheirUnitsAndFractionalArrival() {
        double[][] cases = {{50, 30, 17.8, 0.060, 0.013, 40}, {150, 100, 5.9, 0.25, 0.025, 14},
                {3, 10, 23, 0.019, 0.0047, 51}, {6.4, 1000, 0.19, 2.9, 0, 0}};
        for (double[] c : cases) {
            ShockAtBody s = ShockAtBody.chemical(c[0], true, c[1]);
            assertEquals(c[2] * 1000, s.pressurePa(), c[2] * 100);
            assertEquals(c[3], s.arrivalSeconds(), 0.1 * c[3]);
            if (c[4] > 0) {
                assertEquals(c[4], s.positiveSeconds(), 0.1 * c[4]);
                assertEquals(c[5], s.windMetresPerSecond(), 0.1 * c[5]);
            }
            assertFalse(s.arrived(Math.nextDown(s.arrivalSeconds())));
            assertTrue(s.arrived(s.arrivalSeconds()));
            assertEquals(ChemicalFront.cached(c[0], true).arrivalSeconds(c[1]), s.arrivalSeconds());
            assertEquals(BlastModel.chemicalImpulse(c[1], c[0], true), s.impulsePaSeconds());
            assertEquals(1000 * BlastModel.dynamicPressureKpa(s.pressurePa() / 1000), s.dynamicPressurePa());
        }
    }

    @Test
    void reflectionUsesServerInjuryLawWithoutMultiplyingWindOrImpulse() {
        ShockAtBody incident = ShockAtBody.chemical(50, true, 30), wall = incident.withBackingWall(true);
        assertEquals(1000 * BlastInjury.reflectedKpa(incident.pressurePa() / 1000), wall.reflectedPressurePa());
        assertTrue(wall.reflectedPressurePa() > wall.pressurePa());
        assertEquals(incident, wall.withBackingWall(false));
        assertEquals(incident.windMetresPerSecond(), wall.windMetresPerSecond());
        assertEquals(incident.dynamicPressurePa(), wall.dynamicPressurePa());
        assertEquals(incident.impulsePaSeconds(), wall.impulsePaSeconds());
    }

    @Test
    void loadAmplitudesFallWithRangeAndZeroChargeHasNoFront() {
        for (boolean surface : new boolean[]{false, true}) {
            for (double kg : new double[]{3, 6.4, 50, 150, 2400}) {
                ShockAtBody previous = ShockAtBody.chemical(kg, surface, 5);
                for (double r = 6; r <= 2500; r *= 1.2) {
                    ShockAtBody s = ShockAtBody.chemical(kg, surface, r);
                    assertTrue(s.pressurePa() < previous.pressurePa());
                    assertTrue(s.impulsePaSeconds() < previous.impulsePaSeconds());
                    assertTrue(s.dynamicPressurePa() < previous.dynamicPressurePa());
                    assertTrue(s.windMetresPerSecond() < previous.windMetresPerSecond());
                    assertTrue(s.arrivalSeconds() > previous.arrivalSeconds());
                    previous = s;
                }
            }
        }
        assertFalse(ShockAtBody.chemical(0, true, 30).arrived(1000));
    }
}
