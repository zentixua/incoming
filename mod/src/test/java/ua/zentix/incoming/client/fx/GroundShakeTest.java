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

    /** Приходы толчка, что записал эффект ожидания: на сколько тиков позже прихода. */
    private static final java.util.List<Double> FIRED = new java.util.ArrayList<>();

    private static GroundShake.Arrival arrival(double arrivalTicks, double lengthTicks, double ageAtBirth) {
        FIRED.clear();
        BlastTime.reset();
        return new GroundShake.Arrival(arrivalTicks, lengthTicks, ageAtBirth, BlastTime.ticks(), FIRED::add);
    }

    /**
     * Волна в скале на 300 м приходит через 2,2 тика после источника: ни толчка, ни гула раньше — даже когда очередь
     * эффектов зовёт ожидание дважды в тик рождения (сразу и в своём тике); в первом тике после прихода — с долей тика.
     */
    @Test
    void nothingBeforeTheWaveArrives() {
        GroundShake.Arrival a = arrival(2.2, 9.4, 0);
        assertTrue(a.tick(null, 0), "тик рождения");
        assertTrue(a.tick(null, 1), "тот же тик, второй вызов очереди");
        BlastTime.advance();
        assertTrue(a.tick(null, 2));
        BlastTime.advance();
        assertTrue(a.tick(null, 3), "2 тика — волна ещё в пути");
        assertTrue(FIRED.isEmpty(), "толчок до прихода: " + FIRED);
        BlastTime.advance();
        assertTrue(!a.tick(null, 4), "пришла — ожидание кончено");
        assertEquals(1, FIRED.size());
        assertEquals(0.8, FIRED.getFirst(), 1e-9, "позже прихода на долю тика");
    }

    /** Пакет шёл, пока у сервера прошло 5 тиков: волна (2,2 тика) уже идёт — толчок с того места, где он уже был. */
    @Test
    void lateDeliveryJoinsTheJoltMidway() {
        GroundShake.Arrival a = arrival(2.2, 9.4, 5);
        assertTrue(!a.tick(null, 0));
        assertEquals(1, FIRED.size());
        assertEquals(2.8, FIRED.getFirst(), 1e-9);
    }

    /** Пакет опоздал на весь толчок (5 с — вошёл позже, сеть встала): прошедший толчок заново не играется. */
    @Test
    void lateDeliveryDoesNotReplayAPastJolt() {
        GroundShake.Arrival a = arrival(2.2, 9.4, 100);
        assertTrue(!a.tick(null, 0), "ожидание кончено");
        assertTrue(FIRED.isEmpty(), "свежий толчок по старому событию: " + FIRED);
    }
}
