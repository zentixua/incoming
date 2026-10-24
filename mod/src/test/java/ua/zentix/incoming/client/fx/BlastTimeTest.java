package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.ChemicalFront;
import static org.junit.jupiter.api.Assertions.*;

class BlastTimeTest {
    @Test
    void aPostTickBirthDoesNotStartOneTickIntoTheFront() {
        BlastTime.reset();
        BlastTime.advance();
        long birth = BlastTime.ticks();
        assertEquals(0, BlastTime.seconds(birth, birth, 0));
        assertEquals(0.025, BlastTime.seconds(birth, birth, 0.5), 1e-12);
        assertEquals(birth, BlastTime.ticks(), "post-tick listeners do not advance the clock");
        BlastTime.advance();
        assertEquals(0.05, BlastTime.seconds(BlastTime.ticks(), birth, 0), 1e-12);
        ChemicalFront f = ChemicalFront.cached(50, true);
        assertEquals(0, f.radiusAt(BlastTime.seconds(birth, birth, 0)));
        BlastTime.reset();
        assertTrue(f.radiusAt(BlastTime.seconds(birth, birth, 0.5)) < 20);
    }
}
