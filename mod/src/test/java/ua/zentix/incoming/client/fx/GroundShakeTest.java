package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.GroundShock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Толчок земли у игрока ({@link GroundShake}): сила — ощутимость и скорость грунта, без скачков. */
class GroundShakeTest {
    private static GroundShock.Sample sample(double ppvMmPerS, double felt) {
        return new GroundShock.Sample(0.1, ppvMmPerS / 1000, 15, felt);
    }

    /**
     * Неощутимое не трясёт; сильнее — с каждой декадой скорости грунта, полный — к 100 мм/с; растёт без скачков (ощутимость
     * модели насыщается на 0,3 мм/с и силы не различает).
     */
    @Test
    void joltGrowsWithGroundVelocity() {
        assertEquals(0, GroundShake.strength(sample(0.05, 0)));
        assertEquals(0, GroundShake.strength(sample(50, 0)), "вне полосы восприятия модели — нет");
        assertEquals(1, GroundShake.strength(sample(100, 1)), 1e-9);
        assertEquals(1, GroundShake.strength(sample(500, 1)), 1e-9);
        double before = GroundShake.strength(sample(0.3, 1));
        for (double v = 0.3 * 1.05; v <= 100; v *= 1.05) {
            double s = GroundShake.strength(sample(v, 1));
            assertTrue(s > before && s - before < 0.03, "скачок у " + v + " мм/с: " + before + " → " + s);
            before = s;
        }
        assertTrue(GroundShake.strength(sample(1, 1)) < GroundShake.strength(sample(10, 1)));
    }

    /** Толчок у камеры длится импульс грунта и затухание тела после него: короткий удар скалы не короче кивка. */
    @Test
    void bodyRingsAfterAShortPulse() {
        GroundShock.Sample rock = new GroundShock.Sample(0.1, 0.1, 15, 1);
        assertTrue(rock.durationSeconds() + GroundShake.BODY_RING_S > 1 / CameraShake.GROUND_HZ, "кивок не успел бы");
    }
}
