package ua.zentix.incoming.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** N-волна по Карлсону сверена с примерами самого отчёта (NASA TP-1122, прил. B). */
class SonicBoomTest {
    @Test
    void reproducesCarlsonSampleProblems() {
        // пример 1: разведчик, М 1,99 на 15,24 км, l 32,7 м, A_max 15 м² в 24 м от носа — 74 Па, 0,17 с
        double ks = SonicBoom.shape(15.0, 32.7, 24);
        assertEquals(0.087, ks, 0.001);
        SonicBoom.Wave a = SonicBoom.signature(1.99, 14_480, 32.7, ks, 11_600, 92_300, 295, 1.10, 0.85);
        assertEquals(74, a.pressure(), 1);
        assertEquals(0.17, a.seconds(), 0.005);
        // пример 2: бомбардировщик, М 2 сбоку от трассы — 36,1 Па, 0,19 с
        SonicBoom.Wave b = SonicBoom.signature(2.0, 35_100, 30, 0.088, 6880, 92_300, 296, 1.42, 0.82);
        assertEquals(36.1, b.pressure(), 0.2);
        assertEquals(0.19, b.seconds(), 0.005);
    }

    @Test
    void weakensAndLengthensWithDistance() {
        Noise.Body grad = new Noise.Body(0.122, 2.87, 150, Spectra.ROCKET_INCOMING);
        SonicBoom.Wave near = SonicBoom.at(1.4, 100, grad), far = SonicBoom.at(1.4, 1600, grad);
        // давление ∝ h^−¾, длительность ∝ h^¼
        assertEquals(Math.pow(16, -0.75), far.pressure() / near.pressure(), 1e-9);
        assertEquals(Math.pow(16, 0.25), far.seconds() / near.seconds(), 1e-9);
        // снаряд «Града» в 100 м: сотни паскалей за миллисекунды — сухой треск
        assertTrue(near.pressure() > 100 && near.pressure() < 1000, "Па " + near.pressure());
        assertTrue(near.seconds() > 0.001 && near.seconds() < 0.01, "с " + near.seconds());
    }
}
