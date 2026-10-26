package ua.zentix.incoming.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReliefTest {
    @Test
    void cachesNegativeZeroAndPositiveHeightsWithoutConfusingUnreadColumns() {
        int[] views = {0}, reads = {0};
        Relief survey = new Relief.Survey((cx, cz) -> {
            views[0]++;
            return (x, z) -> {
                reads[0]++;
                return switch (x & 15) {
                    case 0 -> -64;
                    case 1 -> 0;
                    default -> 320;
                };
            };
        });
        for (int pass = 0; pass < 3; pass++) {
            assertEquals(-64, survey.at(-16, -1));
            assertEquals(0, survey.at(-15, -1));
            assertEquals(320, survey.at(-14, -1));
        }
        assertEquals(1, views[0]);
        assertEquals(3, reads[0]);
    }
}
