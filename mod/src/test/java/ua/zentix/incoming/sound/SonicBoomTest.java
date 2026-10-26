package ua.zentix.incoming.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** N-wave according to Carlson was compared with examples of the report itself (NASA TP-1122, appendix B). */
class SonicBoomTest {
    @Test
    void reproducesCarlsonSampleProblems() {
        // example 1: scout, M 1,99 at 15,24 km, l 32,7 m, A_max 15 m² at 24 m from the bow - 74 Pa, 0,17 s
        double ks = SonicBoom.shape(15.0, 32.7, 24);
        assertEquals(0.087, ks, 0.001);
        SonicBoom.Wave a = SonicBoom.signature(1.99, 14_480, 32.7, ks, 11_600, 92_300, 295, 1.10, 0.85);
        assertEquals(74, a.pressure(), 1);
        assertEquals(0.17, a.seconds(), 0.005);
        // example 2: bomber, M 2 on the side of the route - 36,1 Pa, 0,19 s
        SonicBoom.Wave b = SonicBoom.signature(2.0, 35_100, 30, 0.088, 6880, 92_300, 296, 1.42, 0.82);
        assertEquals(36.1, b.pressure(), 0.2);
        assertEquals(0.19, b.seconds(), 0.005);
    }

    @Test
    void weakensAndLengthensWithDistance() {
        Noise.Body grad = new Noise.Body(0.122, 2.87, 150, Spectra.ROCKET_INCOMING);
        SonicBoom.Wave near = SonicBoom.at(1.4, 100, grad), far = SonicBoom.at(1.4, 1600, grad);
        // pressure ∝ h^−¾, duration ∝ h^¼
        assertEquals(Math.pow(16, -0.75), far.pressure() / near.pressure(), 1e-9);
        assertEquals(Math.pow(16, 0.25), far.seconds() / near.seconds(), 1e-9);
        // "Grad" projectile at 100 m: hundreds of pascals in milliseconds - dry crack
        assertTrue(near.pressure() > 100 && near.pressure() < 1000, "Pa " + near.pressure());
        assertTrue(near.seconds() > 0.001 && near.seconds() < 0.01, "s " + near.seconds());
    }
}
