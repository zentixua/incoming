package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FragmentsTest {
    /**
     * Falling speed from mass and area: leaf - 1–2,5 m/s (falling leaves), window 25 kg/m² and board in 2,5 cm - 10–25 m/s,
     *  stone fragment in 30 cm - more than 50 m/s; The larger the fragment, the faster.
     */
    @Test
    void terminalSpeedFromMassAndArea() {
        double leaf = Fragments.terminal(Fragments.Kind.LEAF, 50, 0.1);
        assertTrue(leaf > 1 && leaf < 2.5, "leaf " + leaf);
        double pane = Fragments.terminal(Fragments.Kind.PANE, 25, 0.3), board = Fragments.terminal(Fragments.Kind.BOARD, 600, 0.8);
        assertTrue(pane > 10 && pane < 25, "glass " + pane);
        assertTrue(board > 10 && board < 25, "board " + board);
        double stone = Fragments.terminal(Fragments.Kind.CHUNK, 2300, 0.3);
        assertTrue(stone > 50, "stone " + stone);
        assertTrue(Fragments.terminal(Fragments.Kind.CHUNK, 2300, 0.6) > stone);
    }

    /**
     * Wind at 30 m/s: the leaf moves almost with it in half a second, the stone almost does not move; in quiet air the body falls with
     *  at its falling speed, and the step does not diverge even at the leaf (k ·|v − u| ≫ 1).
     */
    @Test
    void windCarriesLightPiecesAndLeavesHeavyOnes() {
        double u = 30 / 20.0;
        double[] leaf = fly(Fragments.Kind.LEAF, 50, 0.1, u, 10), stone = fly(Fragments.Kind.CHUNK, 2300, 0.3, u, 10);
        assertTrue(leaf[0] > 0.9 * u, "leaf " + leaf[0] * 20 + " m/s");
        assertTrue(stone[0] < 0.1 * u, "stone " + stone[0] * 20 + " m/s");
        for (Fragments.Kind k : Fragments.Kind.values()) {
            double vt = Fragments.terminal(k, k == Fragments.Kind.PANE ? 25 : 600, 0.3);
            double[] v = fly(k, k == Fragments.Kind.PANE ? 25 : 600, 0.3, 0, 2000);
            assertEquals(-vt, v[1] * 20, 0.03 * vt, k + ": falls at its own speed");
        }
    }

    /** A body from rest {@code ticks} tick in a smooth wind u (blocks per tick) along x. */
    private static double[] fly(Fragments.Kind kind, double density, double size, double u, int ticks) {
        double vt = Fragments.terminal(kind, density, size) / 20, drag = Fragments.G / (vt * vt);
        double[] v = new double[3];
        for (int i = 0; i < ticks; i++) Fragments.step(v, u, 0, 0, drag);
        return v;
    }
}
