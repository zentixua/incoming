package ua.zentix.incoming.warhead;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BunkerCoverTest {
    /** Заряд GBU-57, кг ТНТ. */
    private static final double MOP = 2423;

    /**
     * Бомба пробила крышу в 1 блок (верх 75, карта высот 76) и рвётся в комнате под ней: свой ход рядом с зарядом
     * (ниже крыши до дна комнаты) и край стены выше верх не сдвигают — заряд неглубоко, взрыв прорывается наружу.
     */
    @Test
    void shaftUnderThinRoofBreaches() {
        int[] ring = {76, 76, 66, 76, 76, 66, 76, 80};
        int top = BunkerCover.surface(ring);
        assertEquals(76, top);
        assertEquals(6, BunkerCover.depth(top, 70));
        assertTrue(new BuriedBlast(MOP, BunkerCover.depth(top, 70)).breaches());
    }

    /** Ход в открытом грунте на 24 блока: верх — грунт вокруг, не дно хода; взрыв под землёй, прорыва нет. */
    @Test
    void shaftInOpenSoilStaysUnderground() {
        int[] ring = {64, 64, 65, 64, 63, 64, 64, 64};
        int top = BunkerCover.surface(ring);
        assertEquals(64, top);
        assertEquals(24, BunkerCover.depth(top, 40));
        assertFalse(new BuriedBlast(MOP, BunkerCover.depth(top, 40)).breaches());
    }

    /** Граница прорыва — приведённая глубина 1 м/кг^⅓: у 2,4 т — 13,4 м; на метр мельче — прорыв, на метр глубже — нет. */
    @Test
    void breachBoundaryIsScaledDepthOne() {
        double edge = Math.cbrt(MOP);
        assertTrue(new BuriedBlast(MOP, edge - 1).breaches());
        assertFalse(new BuriedBlast(MOP, edge + 1).breaches());
    }

    /** Кольцо не меняется (сортируется копия). */
    @Test
    void ringIsNotModified() {
        int[] ring = {80, 60, 70, 64, 64, 64, 64, 64};
        BunkerCover.surface(ring);
        assertEquals(80, ring[0]);
        assertEquals(60, ring[1]);
    }
}
