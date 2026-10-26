package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.sound.Acoustics;
import ua.zentix.incoming.nuclear.model.ChemicalFront;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rumble in the distance: the same front as the near sound, and its own duration, and not its place in the list of pictures. */
class FarBlastSoundTest {
    /**
     * Near sound, far sound and echo are waiting for one front ({@link FarBlasts#arrivalTicks}): near it is faster than sound, therefore, on the
     *  edge of the near model, the rumble would arrive 18–130 ms later than the same front on the other side of the border (C06).
     */
    @Test
    void oneFrontOnBothSidesOfTheNearEdge() {
        for (double kg : new double[]{3, 50, 2423}) {
            ChemicalFront front = ChemicalFront.cached(kg, true);
            double edge = FarBlasts.arrivalTicks(front, FarBlasts.NEAR);
            assertTrue(edge < FarBlasts.NEAR / Acoustics.SPEED, kg + " kg: the front at the edge overtakes the sound");
            assertEquals(20 * front.arrivalSeconds(FarBlasts.NEAR + 1), FarBlasts.arrivalTicks(front, FarBlasts.NEAR + 1), 1e-12);
        }
        assertEquals(1000 / Acoustics.SPEED, FarBlasts.arrivalTicks(null, 1000), 1e-12, "without its own wave - at the speed of sound");
    }

    /** Beyond the limit of events, the oldest goes into {@code gone}, where its rumble awaits its time. */
    @Test
    void overflowHandsTheOldestOverInsteadOfDroppingIt() {
        ArrayDeque<String> events = new ArrayDeque<>();
        List<String> gone = new ArrayList<>();
        for (String s : new String[]{"a", "b", "c", "d", "e"}) FarBlasts.admit(events, s, 3, gone::add);
        assertEquals(List.of("c", "d", "e"), List.copyOf(events));
        assertEquals(List.of("a", "b"), gone);
    }
}
