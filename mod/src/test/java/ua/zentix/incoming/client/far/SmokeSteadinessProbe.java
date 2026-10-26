package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Дым налёта при остановленном времени: поворот не меняет луч мира, шаг меняет только его начало. Кадр — настоящие
 * плоскости клубов, гладкие радиальные профили их альфы и вуаль; это проверка постоянства рисунка, а не его красоты.
 * У шага естественный параллакс отделяется по веществу модели, как в {@link SmokeRaidTest#truth}.
 */
class SmokeSteadinessProbe {
    static final int STEPS = 60, COLS = 32, ROWS = 18;
    // При 0,60 и наклонённой внутренней камере крайний луч в конце поворота выходил за экран на 0,042 пикселя.
    static final double INSET = 0.59, VISIBLE = 4.0 / 255;

    enum Motion { STILL, YAW, STEP }

    record Metrics(double meanDelta, double meanTruthDelta, double meanResidual, double maxDelta, double p99Delta,
                   double visiblePercent, double meanError, double maxError, double minShare, double maxShare,
                   double maxShareJump, int levelFlips, int minQuads, int maxQuads, double maxFill) {
        String describe(double budget, int camera, Motion motion) {
            return String.format(Locale.ROOT,
                    "%s %s %s: mean |ΔT| %.9f, truth %.9f, residual %.9f, p99 %.9f, max %.9f, >4/255 %.3f%%; "
                            + "mean |T−truth| %.6f, max %.6f; share %.6f–%.6f (jump %.6f), level flips %d, quads %d–%d, fill %.4f",
                    Double.isInfinite(budget) ? "unlimited" : String.format(Locale.ROOT, "budget %.0f", budget),
                    SmokeRaidTest.NAMES[camera], motion, meanDelta, meanTruthDelta, meanResidual, p99Delta, maxDelta, visiblePercent,
                    meanError, maxError, minShare, maxShare, maxShareJump, levelFlips, minQuads, maxQuads, maxFill);
        }
    }

    @Test
    void frozenRaidStaysStableWhileTheViewMoves() throws IOException {
        double now = 1000;
        List<SmokeGrid.Source> raid = SmokeRaidTest.raid(now);
        double[][] profiles = PlumeContinuityTest.profiles();
        Map<String, double[][]> truthCache = new HashMap<>();
        try {
            for (double budget : new double[]{Double.POSITIVE_INFINITY, SmokePuffs.FILL_BUDGET}) {
                for (int camera = 0; camera < 3; camera++) {
                    double[][] rays = rays(camera);
                    for (Motion motion : Motion.values()) {
                        String key = camera + ":" + motion;
                        double[][] truth = truthCache.computeIfAbsent(key, k -> new double[STEPS][]);
                        Metrics m = measure(raid, now, profiles, budget, camera, motion, rays, truth);
                        String got = m.describe(budget, camera, motion);
                        System.out.println("STEADY " + got);
                        if (motion == Motion.STILL) assertEquals(0, m.maxDelta, 1e-12, got);
                        else if (motion == Motion.YAW) {
                            // У поворота то же начало и направление луча мира: естественного изменения вещества нет.
                            assertEquals(0, m.meanTruthDelta, 1e-12, got);
                            assertTrue(m.meanDelta <= (Double.isInfinite(budget) ? 1e-4 : 1e-3) && m.visiblePercent <= 0.1, got);
                        } else {
                            // Радиальные клубы приближают вещество: сверяем изменение ошибки на тех же лучах, не разность
                            // двух средних |ΔT| (изменения противоположного знака тогда скрылись бы).
                            assertTrue(m.meanTruthDelta > 0 && m.meanResidual <= 0.0035 && m.visiblePercent <= 3.5, got);
                        }
                        assertTrue(m.maxFill <= budget + 1e-6, got);
                    }
                }
            }
        } finally {
            SmokePuffs.resetBudget();
            FarFlightView.reset();
        }
    }

    static FarView at(int camera, Motion motion, int step) {
        Vec3 eye = SmokeRaidTest.EYES[camera], aim = SmokeRaidTest.AIMS[camera];
        Vector3f forward = new Vector3f((float) (aim.x - eye.x), (float) (aim.y - eye.y), (float) (aim.z - eye.z)).normalize();
        if (motion == Motion.YAW) {
            double yaw = Math.toRadians(0.5 * (step - STEPS / 2));
            float x = forward.x, z = forward.z;
            forward = new Vector3f((float) (x * Math.cos(yaw) - z * Math.sin(yaw)), forward.y,
                    (float) (x * Math.sin(yaw) + z * Math.cos(yaw)));
        } else if (motion == Motion.STEP) {
            Vector3f left = new Vector3f(0, 1, 0).cross(forward, new Vector3f()).normalize();
            double shift = 0.08 * (step - STEPS / 2);
            eye = eye.add(left.x * shift, 0, left.z * shift);
        }
        return SmokeFillTest.view(eye, forward);
    }

    /** Направления мира закреплены средней камерой; движение меняет проекцию, но не эти числа. */
    static double[][] rays(int camera) {
        FarView mid = at(camera, Motion.STILL, STEPS / 2);
        Vector3f f = mid.forward(), l = mid.left(), up = mid.up();
        double[][] rays = new double[COLS * ROWS][];
        for (int i = 0; i < COLS; i++) for (int j = 0; j < ROWS; j++) {
            double u = INSET * SmokeFillTest.W * ((i + 0.5) / COLS * 2 - 1);
            double v = INSET * SmokeFillTest.H * ((j + 0.5) / ROWS * 2 - 1);
            rays[i * ROWS + j] = new double[]{f.x + u * l.x + v * up.x, f.y + u * l.y + v * up.y, f.z + u * l.z + v * up.z};
        }
        return rays;
    }

    static Metrics measure(List<SmokeGrid.Source> raid, double now, double[][] profiles, double budget, int camera, Motion motion,
                           double[][] rays, double[][] truth) {
        double was = SmokePuffs.budget;
        SmokePuffs.budget = budget;
        try {
            SmokePuffs.resetBudget();
            FarFlightView.reset();
            FarView first = at(camera, motion, 0);
            SmokeFillTest.Screen warmup = new SmokeFillTest.Screen(first);
            // Бюджет мест и уровень слияния зависят от прошлых кадров: сначала неподвижная камера.
            for (int frame = 0; frame < 30; frame++) draw(raid, now, first, warmup);
            if (motion != Motion.STEP && truth[0] == null) {
                truth[0] = truth(raid, now, first, rays);
                for (int frame = 1; frame < STEPS; frame++) truth[frame] = truth[0];
            }
            double[] previous = null, previousTruth = null, deltas = new double[(STEPS - 1) * rays.length];
            double sum = 0, sumTruth = 0, residual = 0, worst = 0, visible = 0, error = 0, maxError = 0;
            double minShare = 1, maxShare = 0, shareJump = 0, previousShare = Double.NaN, maxFill = 0;
            int flips = 0, previousLevel = -1, minQuads = Integer.MAX_VALUE, maxQuads = 0, n = 0;
            for (int frame = 0; frame < STEPS; frame++) {
                FarView view = at(camera, motion, frame);
                SmokeFillTest.Screen screen = new SmokeFillTest.Screen(view);
                draw(raid, now, view, screen);
                double share = SmokePuffs.lastShare();
                minShare = Math.min(minShare, share);
                maxShare = Math.max(maxShare, share);
                if (Double.isFinite(previousShare)) shareJump = Math.max(shareJump, Math.abs(share - previousShare));
                previousShare = share;
                int level = SmokePuffs.lastLevel();
                if (previousLevel >= 0 && previousLevel != level) flips++;
                previousLevel = level;
                minQuads = Math.min(minQuads, screen.quads.size());
                maxQuads = Math.max(maxQuads, screen.quads.size());
                maxFill = Math.max(maxFill, screen.fill());
                double[] current = new double[rays.length];
                if (truth[frame] == null) truth[frame] = truth(raid, now, view, rays);
                for (int ray = 0; ray < rays.length; ray++) {
                    double[] d = rays[ray];
                    assertInViewport(view, d);
                    current[ray] = screen.transmittanceRay(d[0], d[1], d[2], profiles);
                    assertTrue(Double.isFinite(current[ray]) && current[ray] >= 0 && current[ray] <= 1, "Invalid transmittance");
                    double e = Math.abs(current[ray] - truth[frame][ray]);
                    error += e;
                    maxError = Math.max(maxError, e);
                    if (previous == null) continue;
                    double dt = current[ray] - previous[ray], real = truth[frame][ray] - previousTruth[ray], delta = Math.abs(dt);
                    deltas[n++] = delta;
                    sum += delta;
                    sumTruth += Math.abs(real);
                    residual += Math.abs(dt - real);
                    worst = Math.max(worst, delta);
                    if (delta > VISIBLE) visible++;
                }
                previous = current;
                previousTruth = truth[frame];
            }
            Arrays.sort(deltas);
            return new Metrics(sum / n, sumTruth / n, residual / n, worst, deltas[(int) (0.99 * (deltas.length - 1))], 100 * visible / n,
                    error / (STEPS * rays.length), maxError, minShare, maxShare, shareJump, flips, minQuads, maxQuads, maxFill);
        } finally {
            SmokePuffs.budget = was;
        }
    }

    static void assertInViewport(FarView view, double[] ray) {
        Vector3f f = view.forward(), l = view.left(), up = view.up();
        double depth = ray[0] * f.x + ray[1] * f.y + ray[2] * f.z;
        double u = (ray[0] * l.x + ray[1] * l.y + ray[2] * l.z) / depth;
        double v = (ray[0] * up.x + ray[1] * up.y + ray[2] * up.z) / depth;
        assertTrue(depth > 0 && Math.abs(u) <= SmokeFillTest.W && Math.abs(v) <= SmokeFillTest.H, "World-tied ray left the viewport");
    }

    static void draw(List<SmokeGrid.Source> raid, double now, FarView view, SmokeFillTest.Screen screen) {
        screen.clear();
        FarFlightView.budget();
        for (SmokeGrid.Source source : raid) source.emit(now, SmokePuffs.sink(view, screen, source, now));
        SmokePuffs.flush();
    }

    /** Вещество на тех же направлениях мира, как {@link SmokeRaidTest#truth}; начало луча — глаз текущего кадра. */
    static double[] truth(List<SmokeGrid.Source> raid, double now, FarView view, double[][] rays) {
        SmokeRaidTest.Matter matter = new SmokeRaidTest.Matter(view);
        for (SmokeGrid.Source source : raid) {
            matter.glow = source.drawsGlow();
            source.emit(now, matter);
        }
        double[] result = new double[rays.length];
        for (int r = 0; r < rays.length; r++) {
            double[] d = rays[r];
            double norm = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]), tau = 0;
            for (double[] g : matter.gaussians) {
                if (!(g[5] > 0) || !(g[6] > 0)) continue;
                double along = SmokePuffs.sightDepth(g[0], g[1], g[2], g[3], g[4], g[5], d[0] / norm, d[1] / norm, d[2] / norm);
                tau -= Math.log(1 - Math.min(g[6] * (1 - Math.exp(-along)), 1 - 1e-12));
            }
            result[r] = Math.exp(-tau);
        }
        return result;
    }
}
