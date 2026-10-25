package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.ShockAtBody;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Фронт у тела ({@link BlastHit}): контузия и сор у камеры — часть картинки ударов. */
class BlastHitTest {
    @AfterEach
    void clear() {
        Concussion.reset();
    }

    /**
     * Своих эффектов взрыва нет ({@code blast_effects} = false) — нет ни контузии, ни сора, и у разрыва ПВО, который идёт
     * сюда же при любой настройке; с ними фронт, что оглушает (шахед в 30 м), даёт и то и другое.
     */
    @Test
    void blastEffectsOffMeansNoConcussionAndNoLitter() {
        ShockAtBody shock = ShockAtBody.chemical(50, true, 30);
        boolean[] litter = {false};
        assertEquals(0, BlastHit.feel(false, shock, 30, null, 0, () -> litter[0] = true));
        assertFalse(litter[0], "сор при выключенных эффектах");
        assertEquals(0, Concussion.level(5), "контузия при выключенных эффектах");
        double dose = BlastHit.feel(true, shock, 30, null, 0, () -> litter[0] = true);
        assertTrue(dose > 0 && litter[0], "доза " + dose);
        assertTrue(Concussion.level(5) > 0);
    }

    /** Фронт ниже порога модели (ракета в 100 м) экрана не трогает. */
    @Test
    void weakFrontLeavesTheScreen() {
        assertEquals(0, BlastHit.feel(true, ShockAtBody.chemical(150, true, 100), 3, null, 0, () -> {}));
        assertEquals(0, Concussion.level(5));
    }
}
