package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Сетка дыма: клуб — в один каскад, слои и прямоугольники там, где он есть, масса в клетках — его масса. */
class SmokeGridTest {
    private static SmokeGrid.Frame frame(double eyeX, double eyeY, double eyeZ, SmokeGrid.Source... sources) {
        SmokeGrid.Frame f = new SmokeGrid.Frame();
        SmokeGrid.build(new ArrayList<>(List.of(sources)), f, eyeX, eyeY, eyeZ, 0);
        return f;
    }

    private static SmokeGrid.Source blob(double x, double y, double z, double sh, double sv) {
        return (t, out) -> {
            out.blob(x, y, z, sh, sv, 1, 1, 0, 0.8f, 0.7f, 0.6f, 400, 2);
            return true;
        };
    }

    /** Малый клуб у глаза — в мелкий каскад; большой или дальний — в крупный; за последним — нет. */
    @Test
    void blobGoesToTheFinestCascadeThatHoldsIt() {
        SmokeGrid.Frame f = frame(1000.3, 70.7, -500.2, blob(1010, 75, -490, 3, 3), blob(1000, 70, -500, 40, 40), blob(1150, 120, -500, 3, 3),
                blob(1400, 120, -500, 3, 3), blob(5000, 70, 0, 3, 3));
        assertEquals(4, f.count, "за последним каскадом — нет");
        assertEquals(0, f.cascade[0], "малый у глаза — в каскад 0");
        assertEquals(2, f.cascade[1], "σ 40 шире четырёх клеток каскада 1 — в каскад 2");
        assertEquals(1, f.cascade[2], "в 150 блоках — в каскад 1 (±256 м)");
        assertEquals(2, f.cascade[3], "в 400 блоках — в каскад 2 (±1024 м)");
        // углы каскадов кратны клетке и обнимают глаз
        for (int c = 0; c < SmokeGrid.CASCADES; c++) {
            float cell = f.corner[4 * c + 3];
            assertEquals(SmokeGrid.CELL[c], cell);
            assertEquals(0, Math.floorMod(Math.round(1000.3 + f.corner[4 * c]), (int) cell));
            assertTrue(f.corner[4 * c] < 0 && f.corner[4 * c] + SmokeGrid.N * cell > 0);
        }
    }

    /**
     * Прямоугольники клуба в слоях: столько слоёв, сколько клеток он занимает по высоте (три σ), на обеих страницах; NDC в
     * пределах слоя (страница 0 — левая половина).
     */
    @Test
    void layersCoverTheBlob() {
        SmokeGrid.Frame f = frame(0, 0, 0, blob(5, 3, -7, 3, 2));
        int layers = 0;
        for (int l = 0; l < SmokeGrid.CASCADES * SmokeGrid.N; l++) {
            int n = f.layerStart[l + 1] - f.layerStart[l];
            if (n == 0) continue;
            layers++;
            assertEquals(2 * 6, n, "две страницы по два треугольника");
            for (int v = f.layerStart[l]; v < f.layerStart[l + 1]; v++) {
                assertTrue(Math.abs(f.vertices[3 * v]) <= 1 && Math.abs(f.vertices[3 * v + 1]) <= 1);
                assertEquals(0, f.vertices[3 * v + 2]);
            }
        }
        double cell = SmokeGrid.CELL[0], sv = Math.sqrt(2 * 2 + 1);
        assertEquals(Math.floor((3 + 3 * sv - f.corner[1]) / cell) - Math.floor((3 - 3 * sv - f.corner[1]) / cell) + 1, layers);
    }

    /**
     * Ослабление в клетках (как считает шейдер заполнения по таблице клубов) в сумме по объёму — κ'·m: клуб не теряет и не
     * прибавляет вещества ни в одном каскаде (смазан на полклетки, масса та же).
     */
    @Test
    void cellsHoldTheBlobMass() {
        for (double[] at : new double[][]{{5, 3, -7, 3, 2}, {150, 40, 100, 12, 8}, {600, 200, -300, 60, 40}}) {
            SmokeGrid.Frame f = frame(0, 0, 0, blob(at[0], at[1], at[2], at[3], at[4]));
            int c = f.cascade[0];
            float cell = f.corner[4 * c + 3];
            float[] b = f.blobs;
            int m = SmokeGrid.MAX_BLOBS;
            double h = b[3], v = b[m * 4], dust = b[m * 4 + 2], sum = 0;
            for (int x = 0; x < SmokeGrid.N; x++) {
                for (int y = 0; y < SmokeGrid.N; y++) {
                    for (int z = 0; z < SmokeGrid.N; z++) {
                        double px = f.corner[4 * c] + (x + 0.5) * cell - b[0], py = f.corner[4 * c + 1] + (y + 0.5) * cell - b[1],
                                pz = f.corner[4 * c + 2] + (z + 0.5) * cell - b[2];
                        sum += dust * Math.exp(-0.5 * ((px * px + pz * pz) / (h * h) + py * py / (v * v)));
                    }
                }
            }
            assertEquals(ua.zentix.incoming.nuclear.model.DustModel.K_MIX, sum * cell * cell * cell, 0.02 * ua.zentix.incoming.nuclear.model.DustModel.K_MIX,
                    "клуб в каскаде " + c);
        }
    }

    /** Иссякший источник уходит; объём рисует облако у глаза и не рисует на краю сетки. */
    @Test
    void sourcesEndAndShareFadesAtTheEdge() {
        List<SmokeGrid.Source> list = new ArrayList<>(List.of((t, out) -> false, blob(0, 0, 0, 1, 1)));
        SmokeGrid.build(list, new SmokeGrid.Frame(), 0, 0, 0, 0);
        assertEquals(1, list.size());
        double half = 0.5 * SmokeGrid.N * SmokeGrid.CELL[SmokeGrid.CASCADES - 1];
        assertEquals(1, SmokeGrid.volumeShare(100, 50, -200));
        assertEquals(0, SmokeGrid.volumeShare(0.8 * half, 0, 0), 1e-12);
        assertEquals(0, SmokeGrid.volumeShare(0, 0, -0.9 * half), 1e-12);
    }
}
