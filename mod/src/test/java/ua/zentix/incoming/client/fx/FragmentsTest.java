package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FragmentsTest {
    /**
     * Скорость падения из массы и площади: лист — 1–2,5 м/с (падающие листья), окно 25 кг/м² и доска в 2,5 см — 10–25 м/с,
     * каменный обломок в 30 см — больше 50 м/с; крупнее обломок — быстрее.
     */
    @Test
    void terminalSpeedFromMassAndArea() {
        double leaf = Fragments.terminal(Fragments.Kind.LEAF, 50, 0.1);
        assertTrue(leaf > 1 && leaf < 2.5, "лист " + leaf);
        double pane = Fragments.terminal(Fragments.Kind.PANE, 25, 0.3), board = Fragments.terminal(Fragments.Kind.BOARD, 600, 0.8);
        assertTrue(pane > 10 && pane < 25, "стекло " + pane);
        assertTrue(board > 10 && board < 25, "доска " + board);
        double stone = Fragments.terminal(Fragments.Kind.CHUNK, 2300, 0.3);
        assertTrue(stone > 50, "камень " + stone);
        assertTrue(Fragments.terminal(Fragments.Kind.CHUNK, 2300, 0.6) > stone);
    }

    /**
     * Ветер в 30 м/с: лист за полсекунды идёт почти с ним, камень почти не сдвигается; в тихом воздухе тело падает со
     * своей скоростью падения, а шаг не расходится и у листа (k·|v − u| ≫ 1).
     */
    @Test
    void windCarriesLightPiecesAndLeavesHeavyOnes() {
        double u = 30 / 20.0;
        double[] leaf = fly(Fragments.Kind.LEAF, 50, 0.1, u, 10), stone = fly(Fragments.Kind.CHUNK, 2300, 0.3, u, 10);
        assertTrue(leaf[0] > 0.9 * u, "лист " + leaf[0] * 20 + " м/с");
        assertTrue(stone[0] < 0.1 * u, "камень " + stone[0] * 20 + " м/с");
        for (Fragments.Kind k : Fragments.Kind.values()) {
            double vt = Fragments.terminal(k, k == Fragments.Kind.PANE ? 25 : 600, 0.3);
            double[] v = fly(k, k == Fragments.Kind.PANE ? 25 : 600, 0.3, 0, 2000);
            assertEquals(-vt, v[1] * 20, 0.03 * vt, k + ": падает со своей скоростью");
        }
    }

    /** Тело из покоя {@code ticks} тиков в ровном ветре u (блоков за тик) вдоль x. */
    private static double[] fly(Fragments.Kind kind, double density, double size, double u, int ticks) {
        double vt = Fragments.terminal(kind, density, size) / 20, drag = Fragments.G / (vt * vt);
        double[] v = new double[3];
        for (int i = 0; i < ticks; i++) Fragments.step(v, u, 0, 0, drag);
        return v;
    }
}
