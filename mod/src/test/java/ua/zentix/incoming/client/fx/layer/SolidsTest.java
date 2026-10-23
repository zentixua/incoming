package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Отпечаток секции окна заслонов: любая правка блоков меняет его (иначе тень остаётся от прежних блоков). */
class SolidsTest {
    /**
     * Ревью #399: при умножении две правки в верхних битах слов хранилища гасили друг друга, при {@code HashCommon.mix}
     * — правка старшего бита слова и узор {63, 47, 31, 15} в следующем. Теперь отпечаток другой для каждого такого
     * сочетания и для случайных одиночных и парных правок.
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
            // два слова — по старшему биту
            long[] a = raw.clone();
            a[j] ^= 1L << 63;
            a[j + 1] ^= 1L << 63;
            assertNotEquals(base, Solids.fingerprint(4, states, a), "две правки старших битов, слово " + j);
            // старший бит слова и узор {63, 47, 31, 15} в следующем
            long[] b = raw.clone();
            b[j] ^= 1L << 63;
            b[j + 1] ^= 1L << 63 | 1L << 47 | 1L << 31 | 1L << 15;
            assertNotEquals(base, Solids.fingerprint(4, states, b), "узор mix, слово " + j);
            // случайная правка одного номера
            long[] c = raw.clone();
            c[r.nextInt(raw.length)] ^= (long) (1 + r.nextInt(15)) << 4 * r.nextInt(16);
            assertNotEquals(base, Solids.fingerprint(4, states, c), "одна правка");
        }
    }
}
