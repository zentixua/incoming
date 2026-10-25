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

    /** Источник, чей устоявшийся дым — в объёме ({@link SmokeGrid.Source#volume}): один клуб в точке, не всплывает. */
    private static SmokeGrid.Source blob(double x, double y, double z, double sh, double sv) {
        return volume((t, out) -> {
            out.blob(x, y, z, sh, sv, 1, 1, 0, 0.8f, 0.7f, 0.6f, 400, 0);
            return true;
        });
    }

    /** Тот же источник, но масштаба сетки: только такие она берёт. */
    private static SmokeGrid.Source volume(SmokeGrid.Source s) {
        return new SmokeGrid.Source() {
            @Override
            public boolean emit(double seconds, SmokeGrid.Sink out) {
                return s.emit(seconds, out);
            }

            @Override
            public boolean volume() {
                return true;
            }
        };
    }

    /** Каскад клуба таблицы с центром x от глаза и σ по горизонтали sh (порядок таблицы — от ближних источников). */
    private static int cascadeAt(SmokeGrid.Frame f, double x, double sh) {
        for (int i = 0; i < f.count; i++) {
            float h = f.blobs[i * 4 + 3];
            if (Math.abs(f.blobs[i * 4] - x) < 1e-3 && h >= sh && h <= sh + SmokeGrid.CELL[SmokeGrid.CASCADES - 1]) return f.cascade[i];
        }
        return -1;
    }

    /** Малый клуб у глаза — в мелкий каскад; большой или дальний — в крупный; за последним — нет. */
    @Test
    void blobGoesToTheFinestCascadeThatHoldsIt() {
        SmokeGrid.Frame f = frame(1000.3, 70.7, -500.2, blob(1010, 75, -490, 5, 5), blob(1000, 70, -500, 70, 70), blob(1150, 120, -500, 20, 20),
                blob(1400, 120, -500, 70, 70), blob(5000, 70, 0, 70, 70));
        assertEquals(4, f.count, "за последним каскадом — нет");
        assertEquals(0, cascadeAt(f, 1010 - 1000.3, 5), "малый у глаза — в каскад 0");
        assertEquals(2, cascadeAt(f, 1000 - 1000.3, 70), "σ 70 шире четырёх клеток каскада 1 — в каскад 2");
        assertEquals(1, cascadeAt(f, 1150 - 1000.3, 20), "в 150 блоках — в каскад 1 (±256 м)");
        assertEquals(2, cascadeAt(f, 1400 - 1000.3, 70), "в 400 блоках — в каскад 2 (±1024 м)");
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
        SmokeGrid.Frame f = frame(0, 0, 0, blob(5, 3, -7, 5, 4));
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
        double cell = SmokeGrid.CELL[0], sv = Math.sqrt(4 * 4 + 1);
        assertEquals(Math.floor((3 + 3 * sv - f.corner[1]) / cell) - Math.floor((3 - 3 * sv - f.corner[1]) / cell) + 1, layers);
    }

    /**
     * Ослабление в клетках (как считает шейдер заполнения по таблице клубов) в сумме по объёму — κ'·m: клуб не теряет и не
     * прибавляет вещества ни в одном каскаде (смазан на полклетки, масса та же).
     */
    @Test
    void cellsHoldTheBlobMass() {
        for (double[] at : new double[][]{{5, 3, -7, 5, 4}, {150, 40, 100, 20, 16}, {600, 200, -300, 80, 70}}) {
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
            // в сетке — доля клуба ({@link SmokeGrid#share}: у грани каскада — часть)
            double[] corner = new double[3 * SmokeGrid.CASCADES];
            SmokeGrid.corners(0, 0, 0, corner);
            double k = SmokeGrid.dustK(0.8f, 0.7f, 0.6f) * SmokeGrid.share(corner, at[0], at[1], at[2], 0, 0, 0, at[3], at[4], 0, false);
            assertEquals(k, sum * cell * cell * cell, 0.02 * k, "клуб в каскаде " + c);
        }
    }

    /**
     * Клуб по отрезку (след, столб над пожаром): в клетках — та же масса, что у клуба (шейдер заполнения считает
     * {@link SmokeGrid#line}), прямоугольники слоёв покрывают весь отрезок, а нулевой отрезок — точка.
     */
    @Test
    void segmentHoldsItsMassAlongItsLength() {
        for (double[] at : new double[][]{{5, 3, -7, 20, 8, -6, 4.5, 4.5}, {100, 40, 60, 60, 30, 0, 16, 16}, {-20, 10, 20, 0, 25, 0, 4.5, 4.5}}) {
            double ax = at[3], ay = at[4], az = at[5];
            SmokeGrid.Frame f = frame(0, 0, 0, volume((t, out) -> {
                out.segment(at[0], at[1], at[2], ax, ay, az, at[6], at[7], 0, 1, 0, 0.8f, 0.7f, 0.6f, 288, 0);
                return true;
            }));
            assertEquals(1, f.count);
            int c = f.cascade[0];
            float cell = f.corner[4 * c + 3];
            float[] b = f.blobs;
            int m = SmokeGrid.MAX_BLOBS;
            double h = b[3], v = b[m * 4], dust = b[m * 4 + 2], sum = 0;
            assertEquals(ax, b[(2 * m) * 4 + 3], 1e-4);
            assertEquals(ay, b[(3 * m) * 4 + 2], 1e-4);
            assertEquals(az, b[(3 * m) * 4 + 3], 1e-4);
            double sa = Math.sqrt(ax * ax / (h * h) + ay * ay / (v * v) + az * az / (h * h));
            // клетки, которые слои покрывают
            boolean[] covered = new boolean[SmokeGrid.N * SmokeGrid.N * SmokeGrid.N];
            for (int l = c * SmokeGrid.N; l < (c + 1) * SmokeGrid.N; l++) {
                for (int k = f.layerStart[l]; k < f.layerStart[l + 1]; k += 6) {
                    float x0 = f.vertices[3 * k], z0 = f.vertices[3 * k + 1], x1 = f.vertices[3 * (k + 2)], z1 = f.vertices[3 * (k + 2) + 1];
                    if (x0 >= 0) continue;
                    int cx0 = Math.round((x0 + 1) * SmokeGrid.N), cx1 = Math.round((x1 + 1) * SmokeGrid.N);
                    int cz0 = Math.round((z0 + 1) / 2 * SmokeGrid.N), cz1 = Math.round((z1 + 1) / 2 * SmokeGrid.N);
                    for (int x = cx0; x < cx1; x++) for (int z = cz0; z < cz1; z++) covered[(x * SmokeGrid.N + (l - c * SmokeGrid.N)) * SmokeGrid.N + z] = true;
                }
            }
            double outside = 0;
            for (int x = 0; x < SmokeGrid.N; x++) {
                for (int y = 0; y < SmokeGrid.N; y++) {
                    for (int z = 0; z < SmokeGrid.N; z++) {
                        double px = f.corner[4 * c] + (x + 0.5) * cell - b[0], py = f.corner[4 * c + 1] + (y + 0.5) * cell - b[1],
                                pz = f.corner[4 * c + 2] + (z + 0.5) * cell - b[2];
                        double qx = px / h, qy = py / v, qz = pz / h, q2 = qx * qx + qy * qy + qz * qz;
                        double along = sa < SmokeGrid.LINE_POINT ? 0 : (qx * ax / h + qy * ay / v + qz * az / h) / sa;
                        double e = dust * Math.exp(-0.5 * Math.max(0, q2 - along * along)) * SmokeGrid.line(sa, sa < SmokeGrid.LINE_POINT ? Math.sqrt(q2) : along);
                        sum += e;
                        if (!covered[(x * SmokeGrid.N + y) * SmokeGrid.N + z]) outside += e;
                    }
                }
            }
            double[] corner = new double[3 * SmokeGrid.CASCADES];
            SmokeGrid.corners(0, 0, 0, corner);
            double k = SmokeGrid.dustK(0.8f, 0.7f, 0.6f) * SmokeGrid.share(corner, at[0], at[1], at[2], ax, ay, az, at[6], at[7], 0, false);
            assertEquals(k, sum * cell * cell * cell, 0.02 * k, "отрезок в каскаде " + c);
            assertTrue(outside * cell * cell * cell < 0.01 * k, "вне прямоугольников слоёв — " + outside * cell * cell * cell / k);
        }
        // отрезок к нулю — точка
        for (double q : new double[]{0, 0.7, 2}) {
            assertEquals(Math.exp(-0.5 * q * q), SmokeGrid.line(SmokeGrid.LINE_POINT * 1.001, q), 2e-3);
            assertEquals(Math.exp(-0.5 * q * q), SmokeGrid.line(0, q), 1e-12);
        }
        // erf — с точностью Абрамовица и Стиган
        assertEquals(0.8427007929, SmokeGrid.erf(1), 2e-7);
        assertEquals(-0.5204998778, SmokeGrid.erf(-0.5), 2e-7);
    }

    /**
     * Доля сечения, что верно показывает каскад: σ не меньше двух клеток — вся, не больше клетки — нет; клетка — по месту
     * клуба (у глаза мелкий каскад, дальше крупнее, за сеткой — ни одного).
     */
    @Test
    void resolvedShareFollowsTheCascadeCell() {
        assertEquals(1, SmokeGrid.resolved(4, 2), 1e-12);
        assertEquals(0, SmokeGrid.resolved(2, 2), 1e-12);
        assertEquals(0.5, SmokeGrid.resolved(3, 2), 1e-12);
        assertEquals(0, SmokeGrid.resolved(5, 0), 1e-12);
        double[] c = new double[3 * SmokeGrid.CASCADES];
        SmokeGrid.corners(0.5, 0.5, 0.5, c);
        assertEquals(2, SmokeGrid.cellFor(c, 10.5, 0.5, 0.5, 0, 0, 0, 1, 1), 0);
        assertEquals(8, SmokeGrid.cellFor(c, 100.5, 0.5, 0.5, 0, 0, 0, 1, 1), 0);
        assertEquals(8, SmokeGrid.cellFor(c, 10.5, 0.5, 0.5, 60, 0, 0, 1, 1), 0, "длинный отрезок у глаза — в каскад, где он весь");
        assertEquals(0, SmokeGrid.cellFor(c, 5000.5, 0.5, 0.5, 0, 0, 0, 1, 1), 0);
    }

    /** Иссякший источник уходит; объём рисует облако у глаза и не рисует на краю сетки. */
    @Test
    void sourcesEndAndShareFadesAtTheEdge() {
        List<SmokeGrid.Source> list = new ArrayList<>(List.of(volume((t, out) -> false), blob(0, 0, 0, 5, 5)));
        SmokeGrid.build(list, new SmokeGrid.Frame(), 0, 0, 0, 0);
        assertEquals(1, list.size());
        double half = 0.5 * SmokeGrid.N * SmokeGrid.CELL[SmokeGrid.CASCADES - 1];
        assertEquals(1, SmokeGrid.volumeShare(100, 50, -200));
        assertEquals(0, SmokeGrid.volumeShare(0.8 * half, 0, 0), 1e-12);
        assertEquals(0, SmokeGrid.volumeShare(0, 0, -0.9 * half), 1e-12);
    }

    /** Источник из n клубов в точке; помнит итог раскладки. */
    private static final class Cloud implements SmokeGrid.Source {
        final double x;
        final int n;
        Boolean placed;

        Cloud(double x, int n) {
            this.x = x;
            this.n = n;
        }

        @Override
        public boolean emit(double seconds, SmokeGrid.Sink out) {
            // σ — не у́же двух клеток каскада на этой дальности (у глаза 2 м, до 256 м — 8, дальше — 32)
            double sigma = x < 50 ? 5 : x < 200 ? 20 : 70;
            for (int i = 0; i < n; i++) out.blob(x, 0, 0, sigma, sigma, 1, 0, 0, 0.8f, 0.8f, 0.8f, 300, 0);
            return true;
        }

        @Override
        public void placed(boolean inGrid) {
            placed = inGrid;
        }

        @Override
        public boolean volume() {
            return true;
        }
    }

    /**
     * Таблица клубов полна — уходят дальние облака целиком (сами рисуют себя), ближние — все свои клубы; облако, которое
     * не влезает, не мешает влезть меньшему за ним.
     */
    @Test
    void overflowDropsWholeFarClouds() {
        int m = SmokeGrid.MAX_BLOBS / 8;
        Cloud far = new Cloud(300, 3 * m), near = new Cloud(10, 4 * m), mid = new Cloud(100, 5 * m), small = new Cloud(400, m / 2);
        SmokeGrid.Frame f = frame(0, 0, 0, far, near, mid, small);
        assertEquals(Boolean.TRUE, near.placed);
        assertEquals(Boolean.TRUE, far.placed, "4m + 3m ≤ 8m");
        assertEquals(Boolean.FALSE, mid.placed, "ближе дальнего, но 4m + 5m > 8m — его нет целиком");
        assertEquals(Boolean.TRUE, small.placed, "m/2 клубов ещё помещаются");
        assertEquals(7 * m + m / 2, f.count);
    }

    /**
     * Клуб облака, ушедший за все каскады (снос ветром), — квадратами (доля объёма у грани последнего каскада сошла на
     * нет), клуб того же облака у глаза — в сетке.
     */
    @Test
    void blobPastTheLastCascadeIsDrawnByPuffs() {
        SmokeGrid.Source drifted = new SmokeGrid.Source() {
            Boolean placed;

            @Override
            public boolean emit(double seconds, SmokeGrid.Sink out) {
                out.blob(100, 50, 0, 20, 20, 1, 0, 0, 0.8f, 0.8f, 0.8f, 300, 0);
                out.blob(3000, 50, 0, 20, 20, 1, 0, 0, 0.8f, 0.8f, 0.8f, 300, 0);
                return true;
            }

            @Override
            public void placed(boolean inGrid) {
                placed = inGrid;
                assertEquals(true, inGrid);
            }

            @Override
            public boolean volume() {
                return true;
            }
        };
        Cloud near = new Cloud(10, 3);
        SmokeGrid.Frame f = frame(0, 0, 0, drifted, near);
        assertEquals(4, f.count, "клубы ближнего облака и ближний клуб снесённого");
        assertEquals(Boolean.TRUE, near.placed);
    }

    /**
     * Коробка каскада — где его клубы (три σ), внутри каскада; каскад без клубов пуст (шейдер его не читает и проходит
     * без шагов по клетке).
     */
    @Test
    void cascadeBoxesHoldTheirBlobs() {
        SmokeGrid.Frame f = frame(0.5, 0.5, 0.5, blob(10, 5, -8, 5, 4), blob(400, 50, 0, 70, 65));
        assertTrue(SmokeGrid.occupied(f, 0));
        assertTrue(!SmokeGrid.occupied(f, 1), "среднего каскада клубы не выбрали");
        assertTrue(SmokeGrid.occupied(f, 2));
        double h = Math.sqrt(5 * 5 + 1), v = Math.sqrt(4 * 4 + 1);
        assertEquals(10 - 3 * h - 0.5, f.boxLo[0], 1e-4);
        assertEquals(5 + 3 * v - 0.5, f.boxHi[1], 1e-4);
        // в последнем каскаде коробка — в его границах
        for (int a = 0; a < 3; a++) {
            assertTrue(f.boxLo[6 + a] >= f.corner[8 + a] - 1e-3 && f.boxHi[6 + a] <= f.corner[8 + a] + SmokeGrid.N * SmokeGrid.CELL[2] + 1e-3);
        }
    }

    /** Ослабление пыли после дельта-Эддингтона: белая (ω = 1) — K_MIX·(1 − f), чёрная — K_MIX. */
    @Test
    void dustExtinctionIsDeltaScaled() {
        double k = ua.zentix.incoming.nuclear.model.DustModel.K_MIX;
        assertEquals(k * (1 - SmokeGrid.DUST_F), SmokeGrid.dustK(1, 1, 1), 1e-9 * k);
        assertEquals(k, SmokeGrid.dustK(0, 0, 0), 1e-9 * k);
        // обращение ω' → ω: ω = 0,9 даёт ω' = 0,64·0,9/(1 − 0,324)
        float wp = (float) (0.64 * 0.9 / (1 - 0.36 * 0.9));
        assertEquals(k * (1 - 0.9 * 0.36), SmokeGrid.dustK(wp, wp, wp), 1e-5 * k);
    }

    /**
     * Источник, чей дым в объём не идёт ({@link SmokeGrid.Source#volume} нет), сетке клубов не отдаёт; у остальных в сетку
     * идёт доля каждого клуба по {@link SmokeGrid#share}: клуб не шире клетки (3.5.0: клетка 2–32 м размазывала метры в
     * кубы) — ни капли, в полторы клетки — половина, от двух клеток — весь.
     */
    @Test
    void gridTakesOnlyWhatItsCellResolves() {
        boolean[] asked = {false};
        SmokeGrid.Source other = new SmokeGrid.Source() {
            @Override
            public boolean emit(double seconds, SmokeGrid.Sink out) {
                asked[0] = true;
                out.blob(5, 0, 5, 50, 50, 1, 1, 0, 0.8f, 0.8f, 0.8f, 300, 0);
                return true;
            }
        };
        SmokeGrid.Frame f = frame(0, 0, 0, other, blob(0, 0, 0, 1.9, 1.9));
        assertEquals(false, asked[0], "источник не объёма сетке клубов не отдаёт");
        assertEquals(0, f.count, "клуб у́же клетки — не в сетке");
        for (double sigma : new double[]{3, 4, 5}) {
            f = frame(0, 0, 0, blob(0, 0, 0, sigma, sigma));
            assertEquals(1, f.count);
            double h = Math.sqrt(sigma * sigma + 1), peak = 1 / (Math.pow(2 * Math.PI, 1.5) * h * h * h);
            double k = SmokeGrid.dustK(0.8f, 0.7f, 0.6f), share = SmokeGrid.resolved(sigma, 2);
            assertEquals(k * peak * share, f.blobs[SmokeGrid.MAX_BLOBS * 4 + 2], 1e-6 * k * peak, "σ " + sigma);
        }
        assertEquals(0.5, SmokeGrid.resolved(3, 2), 1e-12);
    }

    /**
     * Доля клуба в объёме меняется без скачков: клуб уходит от глаза через грани каскадов (у грани — переход к доле
     * каскада крупнее, у последнего — к нулю) и растёт через предел каскада ({@link SmokeGrid#SPAN}); облако, что ещё
     * клубится (оборот σ/w быстрее {@link SmokeGrid#MATURE_S}), — квадратами, устоявшееся — объёмом.
     */
    @Test
    void shareHasNoJumps() {
        double[] corner = new double[3 * SmokeGrid.CASCADES];
        SmokeGrid.corners(0.3, 40.7, -0.2, corner);
        for (double sigma : new double[]{3, 5, 10, 20, 40, 80}) {
            double last = SmokeGrid.share(corner, 0, 40, 0, 0, 0, 0, sigma, sigma, 0, false);
            for (double x = 0.25; x < 1200; x += 0.25) {
                double w = SmokeGrid.share(corner, x, 40, 0, 0, 0, 0, sigma, sigma, 0, false);
                assertTrue(Math.abs(w - last) < 0.03, "σ " + sigma + " в " + x + " м: " + last + " → " + w);
                last = w;
            }
            assertEquals(0, last, 1e-9, "за сеткой — квадраты");
        }
        for (double x : new double[]{0, 30, 100, 200, 400}) {
            double last = SmokeGrid.share(corner, x, 40, 0, 0, 0, 0, 0.5, 0.5, 0, true);
            for (double sigma = 0.5; sigma < 300; sigma *= 1.005) {
                double w = SmokeGrid.share(corner, x, 40, 0, 0, 0, 0, sigma, sigma, 0, true);
                assertTrue(Math.abs(w - last) < 0.02, "в " + x + " м, σ " + sigma + ": " + last + " → " + w);
                last = w;
            }
        }
        // шахед в 20 с: σ 14,7 м, подъём 2,8 м/с — оборот 5,2 с, почти весь квадратами; в 60 с (25,5 м, 1,6 м/с) — объёмом
        assertTrue(SmokeGrid.share(corner, 50, 90, 0, 0, 0, 0, 14.7, 12.4, 2.83, false) < 0.05);
        assertEquals(1, SmokeGrid.share(corner, 20, 100, 0, 0, 0, 0, 25.5, 21.5, 1.58, false), 1e-9);
        assertEquals(1, SmokeGrid.share(corner, 20, 100, 0, 0, 0, 0, 25.5, 21.5, 1.58, true), 1e-9);
        assertEquals(0, SmokeGrid.share(corner, 5, 40, 0, 0, 0, 0, 5, 5, 3, false), 1e-9, "молодое облако — квадратами");
    }

    /**
     * Объём и квадраты делят клуб без остатка: квадратам ({@link SmokeGrid#rest}) — масса без доли объёма, сетке — доля; без
     * объёма в кадре (проход пелены не рисовал) — всё квадратам.
     */
    @Test
    void puffsAndVolumeKeepTheWholeBlob() {
        double[][] blobs = {{2, 41, 1, 3, 3, 0}, {20, 60, -10, 14, 12, 2.8}, {150, 120, 30, 30, 25, 1}, {900, 200, 0, 30, 30, 0}};
        SmokeGrid.Source cloud = volume((t, out) -> {
            for (double[] b : blobs) out.blob(b[0], b[1], b[2], b[3], b[4], 2, 0, 0, 0.8f, 0.8f, 0.8f, 300, b[5]);
            return true;
        });
        double[] corner = new double[3 * SmokeGrid.CASCADES];
        SmokeGrid.corners(0, 40, 0, corner);
        for (boolean on : new boolean[]{true, false}) {
            SmokeGrid.enable(on);
            try {
                SmokeGrid.Frame f = frame(0, 40, 0, cloud);
                List<Double> rest = new ArrayList<>();
                cloud.emit(0, SmokeGrid.rest(cloud, new SmokeGrid.Sink() {
                    @Override
                    public void segment(double x, double y, double z, double ax, double ay, double az, double sh, double sv, double soot,
                                        double dust, double water, float r, float g, float b, double kelvin, double rise) {
                        rest.add(soot);
                    }
                }));
                int inGrid = 0;
                for (int i = 0; i < blobs.length; i++) {
                    double[] b = blobs[i];
                    double w = on ? SmokeGrid.share(corner, b[0], b[1], b[2], 0, 0, 0, b[3], b[4], b[5], false) : 0;
                    assertEquals(2 * (1 - w), rest.get(i), 1e-9, "квадратам, клуб " + i);
                    if (w > 1e-3) inGrid++;
                }
                // сетка строится и без прохода (её просто не рисуют) — доли те же
                assertTrue(inGrid == 0 || f.count > 0);
            } finally {
                SmokeGrid.enable(false);
            }
        }
    }

    /**
     * Переход клуба из квадратов в объём не меняет вид: пропускание по лучу через клуб (через середину и в одной и двух σ
     * от неё) у смеси — доля w сеткой (её клетки, трилинейно, как читает шейдер) и 1 − w гауссом, как у квадратов
     * ({@code SmokePuffs.sightDepth}: толща ext/(2πσ²)), — то же, что у одного гаусса, в пределах 0,04 при любой толще
     * (больше всего — в полторы клетки: клетка и трилинейная выборка расширяют клуб на 20 %).
     */
    @Test
    void handoffKeepsTransmittance() {
        double[] corner = new double[3 * SmokeGrid.CASCADES];
        SmokeGrid.corners(0, 0, 0, corner);
        for (int c = 0; c < SmokeGrid.CASCADES; c++) {
            double cell = SmokeGrid.CELL[c];
            for (double cells : new double[]{1.2, 1.5, 2, 3, 5}) {
                double sigma = cells * cell, w = SmokeGrid.resolved(sigma, cell);
                double h2 = sigma * sigma + 0.25 * cell * cell;
                for (double tau0 : new double[]{0.05, 0.3, 1, 3, 10}) {
                    for (double b : new double[]{0, sigma, 2 * sigma}) {
                        // толща по лучу вдоль x на высоте b над серединой: гаусс и его клетки (середины — в целых клетках + ½)
                        double puff = tau0 * Math.exp(-0.5 * b * b / (sigma * sigma));
                        double grid = 0, step = cell / 8, peak = tau0 * sigma * sigma / (h2 * Math.sqrt(2 * Math.PI * h2));
                        for (double x = -6 * Math.sqrt(h2); x <= 6 * Math.sqrt(h2); x += step) grid += step * trilinear(x, b, 0.3 * cell, cell, peak, h2);
                        double mixed = w * grid + (1 - w) * puff;
                        assertEquals(Math.exp(-puff), Math.exp(-mixed), 0.04,
                                "каскад " + c + ", σ " + cells + " клетки, τ " + tau0 + ", в " + b + " м: " + puff + " против " + grid);
                    }
                }
            }
        }
    }

    /** Трилинейная выборка клеток (середины в (i + ½)·cell) гаусса с пиком peak и σ² = s2 с центром в (0, 0, z0). */
    private static double trilinear(double x, double y, double z0, double cell, double peak, double s2) {
        double fx = x / cell - 0.5, fy = y / cell - 0.5, fz = -z0 / cell - 0.5;
        int ix = (int) Math.floor(fx), iy = (int) Math.floor(fy), iz = (int) Math.floor(fz);
        double sum = 0;
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                for (int d = 0; d < 2; d++) {
                    double wx = a == 0 ? 1 - (fx - ix) : fx - ix, wy = b == 0 ? 1 - (fy - iy) : fy - iy, wz = d == 0 ? 1 - (fz - iz) : fz - iz;
                    double px = (ix + a + 0.5) * cell, py = (iy + b + 0.5) * cell, pz = (iz + d + 0.5) * cell + z0;
                    sum += wx * wy * wz * peak * Math.exp(-0.5 * (px * px + py * py + pz * pz) / s2);
                }
            }
        }
        return sum;
    }

    /** Источник масштаба сетки, что помнит итог раскладки в box[0]. */
    private abstract static class Placed implements SmokeGrid.Source {
        private final Boolean[] box;

        Placed(Boolean[] box) {
            this.box = box;
        }

        @Override
        public void placed(boolean inGrid) {
            box[0] = inGrid;
        }

        @Override
        public boolean volume() {
            return true;
        }
    }

    /**
     * Устоявшийся дым в объём отдают только облака взрывов ({@code FarBlasts}) и дым пожаров ({@code FireSmoke}): облака
     * старта, следы и облака ядерки — квадратами, как были. Новый источник объёма — сознательно сюда.
     */
    @Test
    void onlyBlastCloudsAndFireSmokeGoToTheVolume() throws java.io.IOException {
        java.nio.file.Path root = java.nio.file.Path.of("src/main/java/ua/zentix/incoming");
        List<String> volume = new ArrayList<>();
        try (var files = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String text = java.nio.file.Files.readString(p);
                if (text.contains("boolean volume()") && !p.endsWith("SmokeGrid.java")) volume.add(root.relativize(p).toString());
            }
        }
        java.util.Collections.sort(volume);
        assertEquals(List.of("client/far/FarBlasts.java", "client/fx/FireSmoke.java"), volume, "источники объёма");
    }
}
