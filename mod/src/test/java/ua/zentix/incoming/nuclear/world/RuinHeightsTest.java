package ua.zentix.incoming.nuclear.world;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class RuinHeightsTest {
    @Test void everyColumnIsExactAcrossPackingBoundaries() {
        Random random = new Random(71329);
        for (int bits = 0; bits <= 32; bits++) {
            int[] values = new int[256];
            for (int repeat = 0; repeat < 8; repeat++) {
                for (int i = 0; i < values.length; i++) values[i] = bits == 32 ? random.nextInt()
                        : -2032 + (int) (random.nextLong() & ((1L << bits) - 1));
                RuinHeights tile = new RuinHeights(values);
                for (int i = 0; i < values.length; i++) assertEquals(values[i], tile.get(i), "column " + i);
            }
        }
    }

    @Test void flatAndVanillaHeightTilesUseBoundedPackedStorage() {
        int[] values = new int[256];
        Arrays.fill(values, -64);
        assertEquals(64, new RuinHeights(values).bytes());
        values[255] = 320;
        assertEquals(352, new RuinHeights(values).bytes());
    }
}
