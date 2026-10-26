package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Imprint of a section of the screen window: any editing of blocks changes it (otherwise the shadow remains from the previous blocks). */
class SolidsTest {
    /**
     * Review #399: during multiplication, two edits in the upper bits of the storage words canceled each other out, with {@code HashCommon.mix}
     *  - an edit of the highest bit of the word and the pattern {63, 47, 31, 15} in the following. Now the fingerprint is different for each such
     *  combination and for random single and paired edits.
     */
    @Test
    void anyBlockChangeChangesTheFingerprint() {
        Random r = new Random(11);
        int[] states = {101, 202, 303, 404, 505, 606, 707, 808, 909, 1001};
        for (int trial = 0; trial < 2000; trial++) {
            long[] raw = new long[256];
            for (int i = 0; i < raw.length; i++) raw[i] = r.nextLong();
            long base = Solids.fingerprint(4, states, raw);
            int j = r.nextInt(raw.length - 1);
            // two words - by the most significant bit
            long[] a = raw.clone();
            a[j] ^= 1L << 63;
            a[j + 1] ^= 1L << 63;
            assertNotEquals(base, Solids.fingerprint(4, states, a), "two edits of the most significant bits, a word " + j);
            // the most significant bit of the word and the pattern {63, 47, 31, 15} as follows
            long[] b = raw.clone();
            b[j] ^= 1L << 63;
            b[j + 1] ^= 1L << 63 | 1L << 47 | 1L << 31 | 1L << 15;
            assertNotEquals(base, Solids.fingerprint(4, states, b), "pattern mix, word " + j);
            // random edit of one number
            long[] c = raw.clone();
            c[r.nextInt(raw.length)] ^= (long) (1 + r.nextInt(15)) << 4 * r.nextInt(16);
            assertNotEquals(base, Solids.fingerprint(4, states, c), "one edit");
        }
    }
}
