package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.sound.Acoustics;
import ua.zentix.incoming.nuclear.model.ChemicalFront;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Раскат вдали: тот же фронт, что у ближнего звука, и свой срок, а не место в списке картинки. */
class FarBlastSoundTest {
    /**
     * Ближний звук, дальний и эхо ждут один фронт ({@link FarBlasts#arrivalTicks}): вблизи он быстрее звука, поэтому на
     * краю ближней модели раскат приходил бы на 18–130 мс позже того же фронта по ту сторону границы (C06).
     */
    @Test
    void oneFrontOnBothSidesOfTheNearEdge() {
        for (double kg : new double[]{3, 50, 2423}) {
            ChemicalFront front = ChemicalFront.cached(kg, true);
            double edge = FarBlasts.arrivalTicks(front, FarBlasts.NEAR);
            assertTrue(edge < FarBlasts.NEAR / Acoustics.SPEED, kg + " кг: фронт на краю обгоняет звук");
            assertEquals(20 * front.arrivalSeconds(FarBlasts.NEAR + 1), FarBlasts.arrivalTicks(front, FarBlasts.NEAR + 1), 1e-12);
        }
        assertEquals(1000 / Acoustics.SPEED, FarBlasts.arrivalTicks(null, 1000), 1e-12, "без своей волны — со скоростью звука");
    }

    /** Сверх предела событий уходит самое старое — в {@code gone}, где его раскат ждёт свой срок. */
    @Test
    void overflowHandsTheOldestOverInsteadOfDroppingIt() {
        ArrayDeque<String> events = new ArrayDeque<>();
        List<String> gone = new ArrayList<>();
        for (String s : new String[]{"a", "b", "c", "d", "e"}) FarBlasts.admit(events, s, 3, gone::add);
        assertEquals(List.of("c", "d", "e"), List.copyOf(events));
        assertEquals(List.of("a", "b"), gone);
    }
}
