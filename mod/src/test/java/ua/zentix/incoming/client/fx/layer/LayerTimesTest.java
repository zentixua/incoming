package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Проба времени слоя ({@link LayerTimes}): без неё меток нет; части квадратов рисуются каждая целиком и по разу. */
class LayerTimesTest {
    /** Проба не идёт — таймер прохода не зовёт OpenGL вовсе (в тесте его нет: вызов упал бы) и кадр не меряет. */
    @Test
    void timerCostsNothingWithoutTheProbe() {
        assertEquals(false, LayerTimes.on());
        GpuTimer timer = new GpuTimer();
        int pair = timer.begin();
        assertEquals(-1, pair);
        timer.end(pair);
        timer.second();
        assertEquals(0, timer.lastFrames);
    }

    /**
     * Отрезки частей в порядке сортировки: каждый квадрат кадра — ровно в одном отрезке своей части, отрезки одной части
     * не соприкасаются (соседние слиты в один вызов). Сумма частей по отдельности — весь кадр, без пропусков и повторов.
     */
    @Test
    void partRunsCoverEveryQuadOnce() {
        Random random = new Random(7);
        int parts = FxQuads.Part.values().length;
        for (int n : new int[]{0, 1, 2, 17, 4000}) {
            byte[] part = new byte[n];
            int[] order = new int[n];
            for (int i = 0; i < n; i++) {
                // длинные серии одной части и частые смены — как у клубов в дыму и лент между ними
                part[i] = (byte) (random.nextInt(3) == 0 ? random.nextInt(parts) : i > 0 ? part[i - 1] : 0);
                order[i] = i;
            }
            for (int i = n - 1; i > 0; i--) {
                int j = random.nextInt(i + 1), t = order[i];
                order[i] = order[j];
                order[j] = t;
            }
            int[][] runs = FxQuads.runs(part, order, n);
            int[] seen = new int[n];
            for (int p = 0; p < parts; p++) {
                int[] r = runs[p];
                for (int k = 0; k < r.length; k += 2) {
                    for (int i = r[k]; i < r[k] + r[k + 1]; i++) {
                        assertEquals(p, part[order[i]], "квадрат в отрезке своей части");
                        seen[i]++;
                    }
                    if (k > 0) assertNotEquals(r[k - 2] + r[k - 1], r[k], "соседние отрезки части слиты");
                }
            }
            for (int i = 0; i < n; i++) assertEquals(1, seen[i], "квадрат " + i + " нарисован по разу");
        }
    }
}
