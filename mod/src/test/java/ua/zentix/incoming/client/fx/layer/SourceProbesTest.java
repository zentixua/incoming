package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Source visibility tests: each frame source has its own number, with 1 (the highlight square writes minus the number in the softness field,
 * 0 there - “no sample”), and there are no more numbers than this 16-bit field contains.
 */
class SourceProbesTest {
    @Test
    void numbersFitTheVertexField() {
        SourceProbes probes = new SourceProbes();
        probes.begin();
        assertEquals(1, probes.add(0, 0, -100, 5), "first source - sample 1");
        assertEquals(2, probes.add(10, 0, -100, 5));
        // memory of sources grows itself
        for (int i = 3; i <= 1000; i++) assertEquals(i, probes.add(i, 0, -100, 1));
        probes.begin();
        assertEquals(1, probes.add(0, 0, -100, 5), "new frame - from the beginning");
        for (int i = 2; i <= SourceProbes.MAX; i++) probes.add(0, 0, -100, 1);
        assertEquals(0, probes.add(0, 0, -100, 1), "above the top field - without testing");
    }
}
