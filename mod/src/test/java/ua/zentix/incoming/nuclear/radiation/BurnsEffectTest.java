package ua.zentix.incoming.nuclear.radiation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Уровни ожогов и рана поверх прежних ({@link BurnsEffect}). */
class BurnsEffectTest {
    @Test
    void levelRoundsUpToTenthsAndHoldsNinetyUntilShock() {
        assertEquals(0, BurnsEffect.level(0));
        assertEquals(4, BurnsEffect.level(0.396));
        assertEquals(4, BurnsEffect.level(0.4));
        assertEquals(9, BurnsEffect.level(0.95));
        assertEquals(BurnsEffect.SHOCK, BurnsEffect.level(1));
    }

    @Test
    void woundIsOnlyTheRiseOfTheCap() {
        assertEquals(0.4, BurnsEffect.wound(-1, 4), 1e-12, "первые ожоги");
        assertEquals(0.9, BurnsEffect.wound(-1, BurnsEffect.level(0.95)), 1e-12, "от 0,9 до шока — 0,9");
        assertEquals(0.2, BurnsEffect.wound(4, 6), 1e-12, "тяжелее прежних — прибавка");
        assertEquals(0.05, BurnsEffect.wound(9, BurnsEffect.SHOCK), 1e-12, "шок после 0,9 — до 5 %");
        assertEquals(1 - BurnsEffect.SHOCK_LEFT, BurnsEffect.wound(-1, BurnsEffect.SHOCK), 1e-12, "шок сразу");
        assertEquals(0, BurnsEffect.wound(6, 4), "слабее прежних");
        assertEquals(0, BurnsEffect.wound(4, 4), "те же");
    }
}
