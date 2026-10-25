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

    /** Источник масштаба сетки ({@link SmokeGrid.Source#volume}): один клуб в точке. */
    private static SmokeGrid.Source blob(double x, double y, double z, double sh, double sv) {
        return volume((t, out) -> {
            out.blob(x, y, z, sh, sv, 1, 1, 0, 0.8f, 0.7f, 0.6f, 400, 2);
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

    /**
     * Малый клуб у глаза — в мелкий каскад; большой или дальний — в крупный; за последним — нет. Клубы — не у́же двух
     * клеток своего каскада ({@link SmokeGrid#RESOLVE}): у́же сетка не берёт.
     */
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
            double k = SmokeGrid.dustK(0.8f, 0.7f, 0.6f);
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
            double k = SmokeGrid.dustK(0.8f, 0.7f, 0.6f);
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
     * Доля сечения, что верно показывает каскад: σ не меньше клетки — вся, не больше полклетки — нет; клетка — по месту
     * клуба (у глаза мелкий каскад, дальше крупнее, за сеткой — ни одного).
     */
    @Test
    void resolvedShareFollowsTheCascadeCell() {
        assertEquals(1, SmokeGrid.resolved(2, 2), 1e-12);
        assertEquals(0, SmokeGrid.resolved(1, 2), 1e-12);
        assertEquals(0.5, SmokeGrid.resolved(1.5, 2), 1e-12);
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
        Cloud far = new Cloud(300, 200), near = new Cloud(10, 300), mid = new Cloud(100, 250), small = new Cloud(400, 10);
        SmokeGrid.Frame f = frame(0, 0, 0, far, near, mid, small);
        assertEquals(Boolean.TRUE, near.placed);
        assertEquals(Boolean.TRUE, far.placed, "300 + 200 ≤ 512");
        assertEquals(Boolean.FALSE, mid.placed, "ближе дальнего, но 300 + 250 > 512 — его нет целиком");
        assertEquals(Boolean.TRUE, small.placed, "10 клубов ещё помещаются");
        assertEquals(510, f.count);
    }

    /** Облако, чей клуб ушёл за все каскады (снос ветром), в сетку не идёт вовсе — рисует себя само, а не обрезком. */
    @Test
    void cloudPastTheLastCascadeLeavesWhole() {
        SmokeGrid.Source drifted = new SmokeGrid.Source() {
            Boolean placed;

            @Override
            public boolean emit(double seconds, SmokeGrid.Sink out) {
                out.blob(100, 50, 0, 20, 10, 1, 0, 0, 0.8f, 0.8f, 0.8f, 300, 0);
                out.blob(3000, 50, 0, 20, 10, 1, 0, 0, 0.8f, 0.8f, 0.8f, 300, 0);
                return true;
            }

            @Override
            public void placed(boolean inGrid) {
                placed = inGrid;
                assertEquals(false, inGrid);
            }

            @Override
            public boolean volume() {
                return true;
            }
        };
        Cloud near = new Cloud(10, 3);
        SmokeGrid.Frame f = frame(0, 0, 0, drifted, near);
        assertEquals(3, f.count, "только клубы ближнего облака");
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
     * Облака обычного оружия в сетку не идут (3.5.0: клетка 8–32 м размазала облако в метры в кубы): источник не
     * масштаба сетки ({@link SmokeGrid.Source#volume} нет) не отдаёт ей клубов вовсе и получает «не в сетке» — его рисуют
     * квадраты слоя; а клуб у́же двух клеток своего каскада сетка не берёт и у источника масштаба сетки — весь источник.
     */
    @Test
    void gridRefusesWhatItsCellDoesNotResolve() {
        boolean[] asked = {false};
        Boolean[] conventional = {null}, thin = {null}, wide = {null};
        SmokeGrid.Source shahed = new SmokeGrid.Source() {
            @Override
            public boolean emit(double seconds, SmokeGrid.Sink out) {
                asked[0] = true;
                out.blob(5, 0, 5, 50, 50, 1, 1, 0, 0.8f, 0.8f, 0.8f, 300, 0);
                return true;
            }

            @Override
            public void placed(boolean inGrid) {
                conventional[0] = inGrid;
            }
        };
        // клуб σ 3 м у глаза — в каскад 0 (клетка 2 м): уже двух клеток — не разрешён; с ним в источнике — и широкий
        SmokeGrid.Source mixed = new Placed(thin) {
            @Override
            public boolean emit(double seconds, SmokeGrid.Sink out) {
                out.blob(0, 0, 0, 3, 3, 1, 0, 0, 0.8f, 0.8f, 0.8f, 300, 0);
                out.blob(0, 0, 0, 200, 200, 1, 0, 0, 0.8f, 0.8f, 0.8f, 300, 0);
                return true;
            }
        };
        // σ 5 м у глаза — каскад 0, две с половиной клетки: берёт
        SmokeGrid.Source resolved = new Placed(wide) {
            @Override
            public boolean emit(double seconds, SmokeGrid.Sink out) {
                out.blob(0, 0, 0, 5, 5, 1, 0, 0, 0.8f, 0.8f, 0.8f, 300, 0);
                return true;
            }
        };
        SmokeGrid.Frame f = frame(0, 0, 0, shahed, mixed, resolved);
        assertEquals(false, asked[0], "обычное облако сетке клубов не отдаёт");
        assertEquals(Boolean.FALSE, conventional[0], "обычное облако — квадратами");
        assertEquals(Boolean.FALSE, thin[0], "клуб у́же двух клеток — весь источник квадратами");
        assertEquals(Boolean.TRUE, wide[0]);
        assertEquals(1, f.count);
        // каскад клуба σ: σ не меньше двух его клеток — при любой дальности, где он ложится
        double[] corner = new double[3 * SmokeGrid.CASCADES];
        SmokeGrid.corners(0, 0, 0, corner);
        for (double sigma : new double[]{1, 3, 5, 15, 20, 40, 63, 70, 200}) {
            for (double x : new double[]{0, 100, 300, 800}) {
                float cell = SmokeGrid.cellFor(corner, x, 0, 0, 0, 0, 0, sigma, sigma);
                Boolean[] got = {null};
                frame(0, 0, 0, new Placed(got) {
                    @Override
                    public boolean emit(double seconds, SmokeGrid.Sink out) {
                        out.blob(x, 0, 0, sigma, sigma, 1, 0, 0, 0.8f, 0.8f, 0.8f, 300, 0);
                        return true;
                    }
                });
                assertEquals(cell > 0 && sigma >= SmokeGrid.RESOLVE * cell, got[0], "σ " + sigma + " в " + x + " м, клетка " + cell);
            }
        }
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
     * Облака обычного оружия масштабом сетки себя не объявляют: облака взрывов ({@code FarBlasts}) и дым пожаров
     * ({@code FireSmoke}) и облака старта ({@code LaunchCloud}, и у МБР) не переопределяют {@link SmokeGrid.Source#volume}.
     * Новый источник масштаба сетки — сознательно сюда.
     */
    @Test
    void conventionalCloudsAreNotVolume() throws java.io.IOException {
        for (ua.zentix.incoming.strike.WeaponType w : ua.zentix.incoming.strike.WeaponType.values()) {
            var spec = ua.zentix.incoming.client.ClientWeaponSpec.of(w).airframe(false);
            if (spec == null) continue;
            ua.zentix.incoming.client.fx.plume.Motor m = spec.far().stage(ua.zentix.incoming.entity.FlightPhase.BOOST).motor();
            if (m == null) continue;
            LaunchCloud c = new LaunchCloud(CloudModel.Air.STANDARD, 64, 0.5, 0.5f, 0.5f, 0.5f, 0);
            for (int i = 0; i < 40; i++) c.feed(m, 0.05, 0, 66, 0, 0, -1, 0);
            if (!c.fed()) continue;
            assertEquals(false, c.volume(), w + ": облако старта σ " + c.thermal().sigmaH(0));
        }
        java.nio.file.Path root = java.nio.file.Path.of("src/main/java/ua/zentix/incoming");
        List<String> volume = new ArrayList<>();
        try (var files = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String text = java.nio.file.Files.readString(p);
                if (text.contains("boolean volume()") && !p.endsWith("SmokeGrid.java")) {
                    volume.add(root.relativize(p).toString());
                }
            }
        }
        assertEquals(List.of(), volume, "источники масштаба сетки");
    }
}
