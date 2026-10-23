package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Счёт огня чанка: окно выборок обхода и осмотр целиком у свежего удара. */
class FireWatchTest {
    /** Огонь в секции: k мест наугад. */
    private static BitSet fire(int k, long seed) {
        BitSet b = new BitSet(4096);
        SplittableRandom r = new SplittableRandom(seed);
        while (b.cardinality() < k) b.set(r.nextInt(4096));
        return b;
    }

    /** Окно одного сдвига выборки: огонь только на его местах (как {@code scan} в обходе). */
    private static int[] onePhase(BitSet fire, int phase) {
        int[] w = new int[4 * FireWatch.SAMPLE + 1];
        FireWatch.phases(j -> j % FireWatch.SAMPLE == phase && fire.get(j), 0, w);
        w[4 * FireWatch.SAMPLE] = 1 << phase;
        return w;
    }

    /** Осмотр целиком — точный счёт и середина огня, при любом числе мест. */
    @Test
    void wholeChunkCountsEveryFire() {
        for (int k : new int[]{1, 5, 13, 30, 300}) {
            BitSet b = fire(k, k);
            int[] w = new int[4 * FireWatch.SAMPLE + 1];
            assertEquals(k, FireWatch.phases(b::get, 32, w));
            w[4 * FireWatch.SAMPLE] = (1 << FireWatch.SAMPLE) - 1;
            assertEquals(k, FireWatch.blocks(w), 1e-9);
            long y = 0;
            for (int p = 0; p < FireWatch.SAMPLE; p++) y += w[4 * p + 2];
            double mean = b.stream().map(j -> 32 + (j >> 8)).average().orElseThrow();
            assertEquals(mean, y / (double) k, 1e-9);
        }
    }

    /**
     * Почему у удара — целиком: огонь, что горит в 5 блоках (шар топлива поджёг траву, угол дома), первая выборка обхода
     * (одна восьмая мест) видит нулём в части случаев, а в остальных — с ошибкой в разы; пожар, выросший с 5 до 30 блоков,
     * окно из прежних сдвигов держит на прошлом счёте.
     */
    @Test
    void oneSampleMissesASmallFireAndLagsAGrowingOne() {
        int missed = 0, trials = 400;
        double worst = 0;
        for (int s = 0; s < trials; s++) {
            BitSet b = fire(5, 1000 + s);
            double n = FireWatch.blocks(onePhase(b, s % FireWatch.SAMPLE));
            if (n == 0) missed++;
            worst = Math.max(worst, Math.abs(n - 5) / 5);
        }
        assertTrue(missed > trials / 4, "первая выборка не видит малый огонь: " + missed + " из " + trials);
        assertTrue(worst >= 1, "ошибка счёта выборкой: " + worst);
        // окно: семь сдвигов смотрели пожар в 5 блоков, восьмой — уже в 30
        BitSet small = fire(5, 7), big = fire(30, 7);
        big.or(small);
        int[] w = new int[4 * FireWatch.SAMPLE + 1];
        for (int p = 0; p < FireWatch.SAMPLE; p++) {
            int[] one = onePhase(p < FireWatch.SAMPLE - 1 ? small : big, p);
            for (int i = 0; i < 4 * FireWatch.SAMPLE; i++) w[i] += one[i];
            w[4 * FireWatch.SAMPLE] |= one[4 * FireWatch.SAMPLE];
        }
        assertTrue(FireWatch.blocks(w) < 0.5 * big.cardinality(), "окно держит прошлый счёт: " + FireWatch.blocks(w) + " из " + big.cardinality());
    }
}
