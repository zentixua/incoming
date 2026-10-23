package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.GroundDust;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Stability;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Цена дыма в кадре (жалоба 23.10: в дыму налёта шахедов 8 к/с, кадр 62 мс, пики 279 мс, видеокарта 91 %, без шейдеров).
 * Налёт — 14 шахедов и 2 ракеты в квадрате 240 м вокруг глаза, возраст облаков 5–150 с, ветер 4 м/с; облака раскладывает
 * тот же приёмник слоя, что в игре ({@link SmokePuffs#sink}), квадраты пишет свой приёмник. Что видно сквозь дым —
 * пропускание по лучу взгляда, e^(−Στ), а не число слоёв: с бюджетом заливки ({@link SmokePuffs#FILL_BUDGET}) кадр
 * закрывает не больше бюджета экранов, а пропускание по экрану то же, что без бюджета.
 */
class SmokeFillTest {
    @org.junit.jupiter.api.BeforeAll
    static void optics() throws IOException {
        ClubFrames.install();
    }

    /**
     * Кадр 16:9, 70° по вертикали на 1080 строк: пиксель и полудиагональ — как у кадра игры ({@code FarRenderer.view}:
     * тангенс на пиксель, край — полудиагональ).
     */
    static final double H = Math.tan(Math.toRadians(35)), W = H * 16 / 9, PIXEL = 2 * H / 1080,
            EDGE = Math.atan(0.5 * PIXEL * Math.hypot(1920, 1080));

    /** Облако модели — источник, как событие {@code FarBlasts.Event}. */
    record Blast(CloudModel model, double x, double y, double z, double born, long seed) implements SmokeGrid.Source {
        @Override
        public boolean emit(double seconds, SmokeGrid.Sink out) {
            double t = seconds - born;
            if (t < 0 || t >= model.life) return false;
            double growth = puffGrowth(seconds);
            model.blobs(t, new CloudModel.Blobs() {
                @Override
                public void blob(double bx, double by, double bz, double sh, double sv, double soot, double dust, double water, double kelvin, double rise) {
                    out.blob(x + bx, y + by, z + bz, sh, sv, soot, dust, water, 0.6f, 0.55f, 0.45f, kelvin, rise);
                }

                @Override
                public void tube(double ax, double ay, double az, double sa, double massA, double bx, double by, double bz, double sb, double massB,
                                 double kelvin, double rise) {
                    out.tube(x + ax, y + ay, z + az, sa, sa, 0, massA, x + bx, y + by, z + bz, sb, sb, 0, massB, 0.6f, 0.55f, 0.45f, kelvin, rise,
                            sa / Math.max(growth, 1));
                }
            });
            return true;
        }

        @Override
        public double puffGrowth(double seconds) {
            double t = Math.max(0, seconds - born);
            return model.sigmaH(t) / Math.max(model.sigmaH(0), 1e-6);
        }

        @Override
        public long seed() {
            return seed;
        }

        @Override
        public boolean drawsGlow() {
            return true;
        }
    }

    /** Квадраты кадра на экране (тангенсы): середина, полуразмеры, непрозрачность, кадр клуба; вуали. */
    static final class Screen implements SmokePuffs.Out {
        final FarView view;
        final List<double[]> quads = new ArrayList<>();
        double veil;

        Screen(FarView view) {
            this.view = view;
        }

        @Override
        public void puff6(double dx, double dy, double dz, double halfA, double halfB, FxAtlas.Sprite sprite, float mix, float shade, float r, float g,
                          float b, float a, double tau) {
            if (a < 0.002f || !(tau > 0)) return;
            Vector3f f = view.forward(), l = view.left(), up = view.up();
            double depth = dx * f.x + dy * f.y + dz * f.z;
            if (depth <= -Math.max(halfA, halfB)) return;
            depth = Math.max(depth, 0.05);
            double[] q = PlumeContinuityTest.club((dx * l.x + dy * l.y + dz * l.z) / depth, (dx * up.x + dy * up.y + dz * up.z) / depth, 0,
                    halfA / depth, halfB / depth, sprite, mix, a, tau);
            quads.add(q);
        }

        @Override
        public void puff6Flat(double dx, double dy, double dz, double halfA, double halfB, double soft, FxAtlas.Sprite sprite, float mix, float shade,
                              float r, float g, float b, float a, double tau) {
            // лежащий квадрат: по высоте экрана — проекция диска
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            puff6(dx, dy, dz, halfA, halfB * Math.abs(dy) / d, sprite, mix, shade, r, g, b, a, tau);
        }

        @Override
        public void veil(double dx, double dy, double dz, double half, float r, float g, float b, float a, int light) {
            veil += -Math.log(1 - Math.min(a, 0.999));
        }

        @Override
        public void veil(double dx, double dy, double dz, double half, float r, float g, float b, float a) {
            veil(dx, dy, dz, half, r, g, b, a, 0);
        }

        @Override
        public boolean ribbon(double ax, double ay, double az, double halfA, float alphaA, int lightA, double bx, double by, double bz, double halfB,
                              float alphaB, int lightB, float r, float g, float b, float[] joint) {
            return false;
        }

        /** Сколько экранов закрывают квадраты (с перекрытием). */
        double fill() {
            double sum = 0;
            for (double[] q : quads) {
                double x0 = Math.max(-W, q[0] - q[3]), x1 = Math.min(W, q[0] + q[3]), y0 = Math.max(-H, q[1] - q[4]), y1 = Math.min(H, q[1] + q[4]);
                if (x1 > x0 && y1 > y0) sum += (x1 - x0) * (y1 - y0) / (4 * W * H);
            }
            return sum;
        }

        /** Пропускание в точке экрана (u, v): квадраты — по пикселям кадра клуба, как в шейдере ({@link ClubFrames}), вуали. */
        double transmittance(double u, double v) throws IOException {
            double clear = Math.exp(-veil);
            for (double[] q : quads) {
                double x = (u - q[0]) / q[3], y = (v - q[1]) / q[4];
                if (Math.abs(x) < 1 && Math.abs(y) < 1) clear *= 1 - PlumeContinuityTest.clubAt(q, x, y);
            }
            return clear;
        }
    }

    static FarView view(Vec3 eye, Vector3f fwd) {
        fwd.normalize();
        Vector3f left = new Vector3f(0, 1, 0).cross(fwd, new Vector3f()).normalize(), up = fwd.cross(left, new Vector3f()).normalize();
        // воздух без дымки: сверяется рисовка вещества, а не дымка перед ним (её у «истины» нет)
        return new FarView(eye, fwd, left, up, 0, 1000, PIXEL, EDGE, 1, 1, new Vector3f(1, 1, 1), 32000, 192, 192, 0, new Sight.Air(0, 0, 0));
    }

    /** Налёт: 14 шахедов и 2 ракеты в квадрате 240 м, 40 % — в крыши на 10–40 м, возраст 5–150 с, ветер 4 м/с (класс D). */
    static List<Blast> raid(double now) {
        Random rnd = new Random(7);
        CloudModel.Air air = new CloudModel.Air(Stability.STANDARD_N, 4, 1, 0.15, 3, 0.5);
        List<Blast> out = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            WeaponType w = i < 2 ? WeaponType.MISSILE : WeaponType.DRONE;
            double hob = rnd.nextDouble() < 0.4 ? 10 + 30 * rnd.nextDouble() : 0;
            CloudModel m = new CloudModel(BlastSource.of(w, false), air, false, hob, GroundDust.Ground.BARE);
            double x = (rnd.nextDouble() - 0.5) * 240, z = (rnd.nextDouble() - 0.5) * 240;
            out.add(new Blast(m, x, hob, z, now - 5 - 145 * rnd.nextDouble(), rnd.nextLong()));
        }
        return out;
    }

    /**
     * Пропускание самого вещества налёта в точке экрана (u, v) из глаза кадра {@code view}: e^(−Στ), τ — толща по лучу
     * через каждый гаусс облаков модели ({@link SmokePuffs#sightDepth}; ножка — гауссами трубки), без квадратов. С ним
     * сверяются оба кадра — полный и в бюджете.
     */
    static double truth(List<Blast> raid, double now, FarView view, double u, double v) {
        Vector3f f = view.forward(), l = view.left(), up = view.up();
        double rx = f.x + u * l.x + v * up.x, ry = f.y + u * l.y + v * up.y, rz = f.z + u * l.z + v * up.z, n = Math.sqrt(rx * rx + ry * ry + rz * rz);
        double fx = rx / n, fy = ry / n, fz = rz / n, kd = SmokeGrid.dustK(0.6f, 0.55f, 0.45f);
        double[] tau = new double[1];
        Vec3 eye = view.camera();
        for (Blast b : raid) {
            double t = now - b.born();
            if (t < 0 || t >= b.model().life) continue;
            b.model().blobs(t, (x, y, z, sh, sv, soot, dust, water, kelvin, rise) -> {
                // светящийся газ — тело огня, не дым (как у приёмника слоя)
                double smoke = 1 - CloudModel.glowShare(kelvin, view.skyLuminance());
                double ext = smoke * (CloudModel.SOOT_K * soot + kd * dust);
                if (ext > 0) tau[0] += SmokePuffs.sightDepth(b.x() + x - eye.x, b.y() + y - eye.y, b.z() + z - eye.z, sh, sv, ext, fx, fy, fz);
            });
        }
        return Math.exp(-tau[0]);
    }

    /** Кадр налёта из глаза eye в сторону fwd с бюджетом заливки budget. */
    static Screen frame(List<Blast> raid, double now, Vec3 eye, Vector3f fwd, double budget) {
        FarView v = view(eye, new Vector3f(fwd));
        Screen s = new Screen(v);
        double was = SmokePuffs.budget;
        SmokePuffs.budget = budget;
        try {
            // два кадра: доля кучек трубок — из прошлого кадра
            SmokePuffs.resetBudget();
            for (int frame = 0; frame < 2; frame++) {
                s.quads.clear();
                s.veil = 0;
                for (Blast b : raid) b.emit(now, SmokePuffs.sink(v, s, b, now));
                SmokePuffs.flush();
            }
        } finally {
            SmokePuffs.budget = was;
            SmokePuffs.resetBudget();
        }
        return s;
    }

    /**
     * Глаз на крыше в дыму налёта и рядом с облаками, взгляд вдоль дыма: без бюджета кадр закрывает больше двух бюджетов, с ним —
     * не больше бюджета или его пола (по одному клубу на гаусс; с запасом на оценку площади кучки, 15 %) и меньше вдвое.
     * Видимая толща — модели: в каждой из 18 частей кадра пропускание и полного кадра, и кадра в бюджете — у пропускания
     * самого вещества модели ({@link #truth}: гауссы облаков без квадратов) не дальше {@link #MODEL_PART}, в среднем по
     * кадру — не дальше {@link #MODEL_MEAN}. До 3.6.1 части расходились до 0,4 (полный кадр) и 0,34 (в бюджете), всегда в
     * светлую сторону: квадрат клуба был «видимым краем» гаусса с линейной непрозрачностью (тонкий дым терял до 80 %
     * вещества), кучка — комьями со случайными просветами, квадрат в плоскости экрана у края кадра — меньше шара.
     */
    @Test
    void raidSmokeStaysInTheFillBudgetAtTheSameDensity() throws IOException {
        double now = 1000;
        List<Blast> raid = raid(now);
        Vector3f along = new Vector3f(1, 0, 0);
        for (Vec3 eye : new Vec3[]{new Vec3(0, 40, 0), new Vec3(raid.get(3).x(), 25, raid.get(3).z() + 20), new Vec3(raid.get(5).x() + 5, 60, raid.get(5).z())}) {
            Screen free = frame(raid, now, eye, along, Double.POSITIVE_INFINITY), budget = frame(raid, now, eye, along, SmokePuffs.FILL_BUDGET);
            double meanFree = 0, meanBudget = 0;
            int n = 0;
            for (int i = 0; i < 48; i++) {
                for (int j = 0; j < 27; j++) {
                    double u = -W + (i + 0.5) * 2 * W / 48, v = -H + (j + 0.5) * 2 * H / 27;
                    meanFree += free.transmittance(u, v);
                    meanBudget += budget.transmittance(u, v);
                    n++;
                }
            }
            meanFree /= n;
            meanBudget /= n;
            // подробность клубов мельче части кадра — её бюджет и отдаёт, плотность — по частям; оба кадра — против вещества
            // модели (без квадратов): бюджет не дальше от него, чем полный кадр, больше чем на шум подробности
            double floor = SmokePuffs.lastFloor();
            double[] real = new double[18];
            FarView v0 = view(eye, new Vector3f(along));
            for (int i = 0; i < 48; i++) {
                for (int j = 0; j < 27; j++) {
                    double u = -W + (i + 0.5) * 2 * W / 48, v = -H + (j + 0.5) * 2 * H / 27;
                    real[i / 8 * 3 + j / 9] += truth(raid, now, v0, u, v) / 72;
                }
            }
            double worstFree = worstBlock(free, real), worst = worstBlock(budget, real);
            String got = String.format(Locale.ROOT, "глаз %s: без бюджета %d квадратов, %.1f экранов, пропускание %.3f; с бюджетом %d, %.1f (пол %.1f), %.3f; худшая часть против вещества: полный %.3f, в бюджете %.3f",
                    eye, free.quads.size(), free.fill(), meanFree, budget.quads.size(), budget.fill(), floor, meanBudget, worstFree, worst);
            System.out.println("FILL " + got);
            assertTrue(free.fill() > 2 * SmokePuffs.FILL_BUDGET, got);
            assertTrue(budget.fill() <= 1.15 * Math.max(SmokePuffs.FILL_BUDGET, floor), got);
            assertTrue(budget.fill() < free.fill() / 2, got);
            double meanReal = 0;
            for (double r : real) meanReal += r / real.length;
            assertTrue(Math.abs(meanFree - meanReal) <= MODEL_MEAN && Math.abs(meanBudget - meanReal) <= MODEL_MEAN, got + "; модель в среднем " + meanReal);
            assertTrue(worstFree <= MODEL_PART && worst <= MODEL_PART, got);
        }
    }

    /**
     * Части кадра против вещества модели — не дальше этого: подробность клубов (раскладка кучки, кадр и отражение по зерну)
     * сама двигает пропускание частей до {@link #DETAIL} ({@link #clusterDetailNoise}), остальное — форма кадра клуба
     * против гаусса.
     */
    static final double MODEL_PART = 0.15, MODEL_MEAN = 0.03, DETAIL = 0.12;

    /**
     * Подробность кучек — по зерну облака (поворот раскладки, кадр, отражение и размер клубов): смена зерна меняет
     * пропускание по частям кадра не больше чем на {@link #DETAIL} — это клубы, а не плотность (на весь кадр вещество то же).
     */
    @Test
    void clusterDetailNoise() throws IOException {
        double now = 1000;
        List<Blast> raid = raid(now), other = new ArrayList<>();
        for (Blast b : raid) other.add(new Blast(b.model(), b.x(), b.y(), b.z(), b.born(), b.seed() * 31 + 7));
        Screen a = frame(raid, now, new Vec3(0, 40, 0), new Vector3f(1, 0, 0), Double.POSITIVE_INFINITY);
        Screen b = frame(other, now, new Vec3(0, 40, 0), new Vector3f(1, 0, 0), Double.POSITIVE_INFINITY);
        double worst = worstBlock(a, b);
        assertTrue(worst <= DETAIL, "зерно: худшая часть кадра " + worst);
    }

    /** Наибольшая разница среднего пропускания двух кадров по 18 частям (8 × 9 узлов сетки 48×27). */
    static double worstBlock(Screen a, Screen b) throws IOException {
        return worstBlock(a, blocks(b));
    }

    /** То же против средних по частям {@code ref}. */
    static double worstBlock(Screen a, double[] ref) throws IOException {
        double[] ba = blocks(a);
        double worst = 0;
        for (int k = 0; k < 18; k++) worst = Math.max(worst, Math.abs(ba[k] - ref[k]));
        return worst;
    }

    /** Среднее пропускание кадра по 18 частям. */
    static double[] blocks(Screen a) throws IOException {
        double[] ba = new double[18];
        for (int i = 0; i < 48; i++) {
            for (int j = 0; j < 27; j++) {
                double u = -W + (i + 0.5) * 2 * W / 48, v = -H + (j + 0.5) * 2 * H / 27;
                ba[i / 8 * 3 + j / 9] += a.transmittance(u, v) / 72;
            }
        }
        return ba;
    }

    /** Слияние двух равных гауссов на расстоянии σ держит массу, середину и вторые моменты; дальний остаётся отдельно. */
    @Test
    void mergeKeepsMassAndMoments() {
        int f = SmokePuffs.FIELDS;
        double[] held = new double[3 * f];
        double[][] blobs = {{0, 10, 0, 4, 4, 1, 2, 0}, {4, 10, 0, 4, 4, 1, 2, 0}, {40, 10, 0, 4, 4, 1, 2, 0}};
        for (int i = 0; i < 3; i++) {
            System.arraycopy(blobs[i], 0, held, i * f, 8);
            held[i * f + 8] = 0.6;
            held[i * f + 9] = 0.55;
            held[i * f + 10] = 0.45;
        }
        boolean[] gone = new boolean[3];
        SmokePuffs.merge(held, 0, 3, gone, CloudModel.SOOT_K, SmokeGrid.DROP_K);
        assertTrue(!gone[0] && gone[1] && !gone[2], "слит только близкий");
        assertTrue(Math.abs(held[0] - 2) < 1e-9 && Math.abs(held[5] - 2) < 1e-9 && Math.abs(held[6] - 4) < 1e-9, "середина и массы");
        assertTrue(Math.abs(held[3] - Math.sqrt(16 + 0.5 * 4)) < 1e-9, "σh² = σh² + половина разноса²: " + held[3]);
        assertTrue(Math.abs(held[4] - 4) < 1e-9, "σv та же");
    }
}
