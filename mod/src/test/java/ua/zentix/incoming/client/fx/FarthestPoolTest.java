package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pool of pieces {@link Fragments}: the rule of displacement is the same search of all for each new one (the new one closer to the eye displaces the farthest
 * , and the one who was added in the same tick), only through the heap.
 */
class FarthestPoolTest {
    /** A piece of the model: the square of the distance to the eye (changes between ticks), whether it is alive. */
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

    /** The previous search (Fragments to the heap): the furthest one from the entire list, strictly further than the new one, leaves the list. */
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

    /** Review #425: the pool is full (1199 pieces on 80 m, one on 120 m); new ones on 100, 90, 85 m in turn displace the distant one. */
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
            assertTrue(pool.admit(p.d, 1200, tick, q -> q.d), m + " m: there is a place - the distant one leaves");
            assertFalse(far.alive, m + " m: the farthest one left (" + Math.sqrt(far.d) + " m)");
            pool.add(p, p.d, tick);
            far = p;
            assertEquals(1200, pool.size());
        }
        // there are no closer ones - the farthest ones are at 80 m no further than the new one at 80 m: there is no space
        assertTrue(pool.admit(70 * 70, 1200, tick, q -> q.d), "70 m displaces 85-meter");
        assertFalse(far.alive);
        pool.add(new Piece(id++, 70 * 70), 70 * 70, tick);
        assertFalse(pool.admit(80 * 80, 1200, tick, q -> q.d), "80 m: there is no one further");
    }

    /** Random tick: births at different distances, deaths, shift of pieces between ticks - the solutions are the same as for brute force. */
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
                    assertEquals(a, b, "tick " + tick + ": decision on a new one " + d);
                    if (a) {
                        Piece r = new Piece(id, d), m = new Piece(id, d);
                        ref.add(r);
                        mine.add(m);
                        pool.add(m, d, tick);
                    }
                    id++;
                }
                assertEquals(alive(ref), alive(mine), "tick " + tick + ": the living ones are the same");
                // between ticks the pieces fly and go away on their own (engine), the list is cleared
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
        throw new AssertionError("no piece " + id);
    }
}
