package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Пыль ножки — та, что подсос забрал из слоя пыли волны: у воздушного 15 кт на 592 м (как Хиросима, SHOB ~790
 * фут/кт^⅓) пыльная ножка не догоняет шапку (NRC 1985, с. 22: у 10 кт на 450 м — «did not merge»), у наземного — ножка
 * до шапки с первых секунд; до начала подсоса у воздушного пыли в ножке нет.
 */
class StemDustTest {
    @Test
    void airBurstDustyStemDoesNotReachTheCap() {
        StemDust d = StemDust.of(15, 592, false, StemDust.CLEAR);
        assertEquals(0, d.lambda(5), 0, "до подсоса (нисходящий поток шара у оси) пыли нет");
        for (double t : new double[]{30, 60, 120, 240, 400}) {
            MushroomModel.Shape s = MushroomModel.shape(t, 15, 592, false, 1e9, d);
            assertTrue(d.lambda(t) > 0, "t = " + t);
            assertTrue(s.stemTop() < 0.8 * s.capBottom(), "t = " + t + ": ножка " + s.stemTop() + ", низ шапки " + s.capBottom());
        }
    }

    @Test
    void groundBurstStemIsTheFireballTrail() {
        StemDust d = StemDust.of(15, 0, true, StemDust.CLEAR);
        for (double t : new double[]{5, 30, 120}) {
            MushroomModel.Shape s = MushroomModel.shape(t, 15, 0, true, 1e9, d);
            assertTrue(d.lambda(t) > 0, "шар на земле уносит пыль сразу, t = " + t);
            assertTrue(s.stemTop() >= s.capBottom(), "t = " + t);
            assertTrue(s.stemTau() > 1, "ножка наземного непрозрачна: " + s.stemTau());
        }
    }

    /** Ниже подрыв — больше пыли под ним и раньше подсос: пыль ножки растёт к земле. */
    @Test
    void lowerBurstFeedsMoreDust() {
        double high = StemDust.of(15, 592, false, StemDust.CLEAR).lambda(60), low = StemDust.of(15, 300, false, StemDust.CLEAR).lambda(60);
        assertTrue(low > high, low + " > " + high);
    }
}
