package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Пул кусков {@link Fragments}: правило вытеснения — прежний перебор всех на каждый новый (новый ближе к глазу вытесняет
 * самого дальнего, и того, кого добавили в этом же тике), только через кучу.
 */
class FarthestPoolTest {
    /** Кусок модели: квадрат расстояния до глаза (меняется между тиками), жив ли. */
    private static final class Piece {
        final int id;
        double d;
        boolean alive = true;

        Piece(int id, double d) {
            this.id = id;
            this.d = d;
        }
    }

    private static FarthestPool<Piece> pool() {
        return new FarthestPool<>(p -> p.alive, p -> p.alive = false);
    }

    /** Прежний перебор (Fragments до кучи): самый дальний из всего списка, строго дальше нового, уходит из списка. */
    private static boolean reference(List<Piece> live, double d, int cap) {
        if (live.size() < cap) return true;
        int far = -1;
        double farD = d;
        for (int i = 0; i < live.size(); i++) {
            if (live.get(i).d > farD) {
                farD = live.get(i).d;
                far = i;
            }
        }
        if (far < 0) return false;
        live.get(far).alive = false;
        live.set(far, live.getLast());
        live.removeLast();
        return true;
    }

    /** Ревью #425: пул полон (1199 кусков на 80 м, один на 120 м); новые на 100, 90, 85 м по очереди вытесняют дальнего. */
    @Test
    void newPiecesOfTheSameTickAreEvictedFarthestFirst() {
        FarthestPool<Piece> pool = pool();
        List<Piece> all = new ArrayList<>();
        int id = 0;
        for (int i = 0; i < 1199; i++) all.add(new Piece(id++, 80 * 80));
        all.add(new Piece(id++, 120 * 120));
        for (Piece p : all) {
            assertTrue(pool.admit(p.d, 1200, 1, q -> q.d));
            pool.add(p, p.d, 1);
        }
        long tick = 2;
        Piece far = all.getLast();
        for (double m : new double[]{100, 90, 85}) {
            Piece p = new Piece(id++, m * m);
            assertTrue(pool.admit(p.d, 1200, tick, q -> q.d), m + " м: место есть — дальний уходит");
            assertFalse(far.alive, m + " м: ушёл самый дальний (" + Math.sqrt(far.d) + " м)");
            pool.add(p, p.d, tick);
            far = p;
            assertEquals(1200, pool.size());
        }
        // ближе всех нет — дальние на 80 м не дальше нового на 80 м: места нет
        assertTrue(pool.admit(70 * 70, 1200, tick, q -> q.d), "70 м вытесняет 85-метровый");
        assertFalse(far.alive);
        pool.add(new Piece(id++, 70 * 70), 70 * 70, tick);
        assertFalse(pool.admit(80 * 80, 1200, tick, q -> q.d), "80 м: дальше никого нет");
    }

    /** Случайные тики: рождения на разных расстояниях, смерти, сдвиг кусков между тиками — решения те же, что у перебора. */
    @Test
    void sameDecisionsAsTheScanOverRandomTicks() {
        Random rnd = new Random(425);
        for (int run = 0; run < 20; run++) {
            int cap = 20 + rnd.nextInt(80);
            FarthestPool<Piece> pool = pool();
            List<Piece> ref = new ArrayList<>(), mine = new ArrayList<>();
            int id = 0;
            for (long tick = 1; tick < 60; tick++) {
                int born = rnd.nextInt(3 * cap);
                for (int k = 0; k < born; k++) {
                    double d = 1e4 * rnd.nextDouble();
                    boolean a = reference(ref, d, cap), b = pool.admit(d, cap, tick, q -> q.d);
                    assertEquals(a, b, "тик " + tick + ": решение о новом на " + d);
                    if (a) {
                        Piece r = new Piece(id, d), m = new Piece(id, d);
                        ref.add(r);
                        mine.add(m);
                        pool.add(m, d, tick);
                    }
                    id++;
                }
                assertEquals(alive(ref), alive(mine), "тик " + tick + ": живые те же");
                // между тиками куски летят и уходят сами (движок), список чистится
                for (int i = 0; i < ref.size(); i++) {
                    double step = 50 * rnd.nextGaussian();
                    boolean dies = rnd.nextDouble() < 0.05;
                    for (Piece p : new Piece[]{ref.get(i), byId(mine, ref.get(i).id)}) {
                        p.d = Math.max(0, p.d + step);
                        if (dies) p.alive = false;
                    }
                }
                ref.removeIf(p -> !p.alive);
                pool.purge(p -> p.alive);
                mine.removeIf(p -> !p.alive);
                assertEquals(ref.size(), pool.size());
            }
        }
    }

    private static List<Integer> alive(List<Piece> pieces) {
        return pieces.stream().filter(p -> p.alive).map(p -> p.id).sorted().toList();
    }

    private static Piece byId(List<Piece> pieces, int id) {
        for (Piece p : pieces) if (p.id == id) return p;
        throw new AssertionError("нет куска " + id);
    }
}
