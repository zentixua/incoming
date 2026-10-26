package ua.zentix.incoming.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wind of the world: one for the server and client (only depending on time), the nuke scatter, smoothly, weaker near the ground. */
class WindTest {
    @Test
    void sameForEveryoneAtTheSameTime() {
        for (long t : new long[]{0, 12_345, 24_000, 1_000_000, -5}) assertEquals(Wind.at(t), Wind.at(t));
    }

    @Test
    void eachDayDrawsFromTheNukesRange() {
        for (long day = 0; day < 200; day++) {
            Wind w = Wind.at(day * 24_000);
            assertTrue(w.speed() >= 5 - 1e-9 && w.speed() <= 15 + 1e-9, "day " + day + ": " + w.speed());
        }
    }

    @Test
    void turnsSmoothlyBetweenDays() {
        for (long t = 0; t < 5 * 24_000; t += 20) {
            Wind a = Wind.at(t), b = Wind.at(t + 20);
            double dx = a.speed() * a.x() - b.speed() * b.x(), dz = a.speed() * a.z() - b.speed() * b.z();
            assertTrue(Math.sqrt(dx * dx + dz * dz) < 0.05, "in a second the wind does not jump: " + t);
        }
    }

    @Test
    void weakerNearTheGroundAndMoreSoAtNight() {
        Wind w = new Wind(0, 10);
        assertEquals(10, w.at(Wind.REFERENCE, 0.14), 1e-12);
        assertEquals(10, w.at(5_000, 0.14), 1e-12, "above sample - as on it");
        assertEquals(10 * Math.pow(0.01, 0.14), w.at(10, 0.14), 1e-12);
        assertTrue(w.at(10, 0.35) < w.at(10, 0.1));
    }

    @Test
    void standardAtmosphere() {
        assertEquals(101_325, StandardAtmosphere.pressure(0), 1e-6);
        assertEquals(22_632, StandardAtmosphere.pressure(11_000), 2);
        assertEquals(216.65, StandardAtmosphere.temperature(11_000), 1e-9);
        assertEquals(1.225, StandardAtmosphere.density(0), 0.001);
        assertEquals(5_474.9, StandardAtmosphere.pressure(20_000), 1);
    }
}
