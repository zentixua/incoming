package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Замер (не проверка): насколько кадр дыма налёта зависит от взгляда. Время стоит, движется только камера — всё, что
 * меняется в пропускании по лучам, привязанным к миру, — от способа рисования, а не от дыма. Поворот — лучи те же в
 * мире (у дыма, нарисованного в мире, пропускание по ним не меняется вовсе); шаг вбок — против самого вещества по тем же
 * лучам (параллакс есть и у него).
 */
class SmokeSteadinessProbe {
    static final int STEPS = 60, COLS = 32, ROWS = 18;

    @Test
    void probe() throws IOException {
        double now = 1000;
        List<SmokeGrid.Source> raid = SmokeRaidTest.raid(now);
        double[][] prof = PlumeContinuityTest.profiles();
        for (double budget : new double[]{Double.POSITIVE_INFINITY, SmokePuffs.FILL_BUDGET}) {
            for (int c = 0; c < 3; c++) {
                for (String motion : new String[]{"стоит", "поворот 0.5°", "шаг 0.08 м"}) run(raid, now, prof, budget, c, motion);
            }
        }
        SmokePuffs.resetBudget();
    }

    static FarView at(int camera, String motion, int step) {
        Vec3 e = SmokeRaidTest.EYES[camera], a = SmokeRaidTest.AIMS[camera];
        Vector3f fwd = new Vector3f((float) (a.x - e.x), (float) (a.y - e.y), (float) (a.z - e.z)).normalize();
        if (motion.startsWith("поворот")) {
            double yaw = Math.toRadians(0.5 * (step - STEPS / 2));
            float x = fwd.x, z = fwd.z;
            fwd = new Vector3f((float) (x * Math.cos(yaw) - z * Math.sin(yaw)), fwd.y, (float) (x * Math.sin(yaw) + z * Math.cos(yaw)));
        } else if (motion.startsWith("шаг")) {
            Vector3f left = new Vector3f(0, 1, 0).cross(fwd, new Vector3f()).normalize();
            double s = 0.08 * (step - STEPS / 2);
            e = e.add(left.x * s, 0, left.z * s);
        }
        return SmokeFillTest.view(e, fwd);
    }

    static void run(List<SmokeGrid.Source> raid, double now, double[][] prof, double budget, int camera, String motion) {
        double was = SmokePuffs.budget;
        SmokePuffs.budget = budget;
        try {
            SmokePuffs.resetBudget();
            FarFlightView.reset();
            // разогрев: бюджет мест и уровень слияния — по прошлым кадрам
            FarView first = at(camera, motion, 0);
            SmokeFillTest.Screen s = new SmokeFillTest.Screen(first);
            for (int k = 0; k < 30; k++) draw(raid, now, first, s);
            // лучи — из средней камеры, внутренние 60 % кадра (в кадре на всём пути)
            FarView mid = at(camera, motion, STEPS / 2);
            Vector3f f = mid.forward(), l = mid.left(), up = mid.up();
            double[][] rays = new double[COLS * ROWS][];
            for (int i = 0; i < COLS; i++) {
                for (int j = 0; j < ROWS; j++) {
                    double u = 0.6 * SmokeFillTest.W * ((i + 0.5) / COLS * 2 - 1), v = 0.6 * SmokeFillTest.H * ((j + 0.5) / ROWS * 2 - 1);
                    rays[i * ROWS + j] = new double[]{f.x + u * l.x + v * up.x, f.y + u * l.y + v * up.y, f.z + u * l.z + v * up.z};
                }
            }
            double[] prev = null, prevTruth = null;
            double sumD = 0, sumTruth = 0, visible = 0, worst = 0, sMin = 1, sMax = 0, sJump = 0, lastS = -1;
            int levelFlips = 0, lastLevel = -1, n = 0, countMin = Integer.MAX_VALUE, countMax = 0;
            for (int step = 0; step < STEPS; step++) {
                FarView v = at(camera, motion, step);
                SmokeFillTest.Screen screen = new SmokeFillTest.Screen(v);
                draw(raid, now, v, screen);
                double sh = SmokePuffs.lastShare();
                sMin = Math.min(sMin, sh);
                sMax = Math.max(sMax, sh);
                if (lastS >= 0) sJump = Math.max(sJump, Math.abs(sh - lastS));
                lastS = sh;
                if (lastLevel >= 0 && lastLevel != SmokePuffs.lastLevel()) levelFlips++;
                lastLevel = SmokePuffs.lastLevel();
                countMin = Math.min(countMin, screen.quads.size());
                countMax = Math.max(countMax, screen.quads.size());
                double[] t = new double[rays.length];
                Vector3f vf = v.forward(), vl = v.left(), vu = v.up();
                for (int r = 0; r < rays.length; r++) {
                    double[] d = rays[r];
                    double depth = d[0] * vf.x + d[1] * vf.y + d[2] * vf.z;
                    t[r] = screen.transmittance((d[0] * vl.x + d[1] * vl.y + d[2] * vl.z) / depth, (d[0] * vu.x + d[1] * vu.y + d[2] * vu.z) / depth, prof);
                }
                double[] truth = motion.startsWith("шаг") ? truth(raid, now, v, rays) : null;
                if (prev != null) {
                    for (int r = 0; r < rays.length; r++) {
                        double dd = Math.abs(t[r] - prev[r]);
                        sumD += dd;
                        worst = Math.max(worst, dd);
                        if (dd > 4.0 / 255) visible++;
                        if (truth != null) sumTruth += Math.abs(truth[r] - prevTruth[r]);
                        n++;
                    }
                }
                prev = t;
                prevTruth = truth;
            }
            System.out.println(String.format(Locale.ROOT,
                    "STEADY бюджет %s %s %s: |ΔT| на кадр в среднем %.4f%s, худший луч %.3f, лучей с видимым скачком (>4/255) %.1f %%; доля кучки %.3f–%.3f (скачок до %.3f), смен уровня слияния %d, квадратов %d–%d",
                    Double.isInfinite(budget) ? "нет" : String.format(Locale.ROOT, "%.0f", budget), SmokeRaidTest.NAMES[camera], motion, sumD / n,
                    motion.startsWith("шаг") ? String.format(Locale.ROOT, " (у вещества %.4f)", sumTruth / n) : "", worst, 100 * visible / n, sMin, sMax, sJump,
                    levelFlips, countMin, countMax));
        } finally {
            SmokePuffs.budget = was;
        }
    }

    static void draw(List<SmokeGrid.Source> raid, double now, FarView view, SmokeFillTest.Screen s) {
        s.clear();
        FarFlightView.budget();
        for (SmokeGrid.Source b : raid) b.emit(now, SmokePuffs.sink(view, s, b, now));
        SmokePuffs.flush();
    }

    /** Пропускание самого вещества по лучам (как {@link SmokeRaidTest#truth}). */
    static double[] truth(List<SmokeGrid.Source> raid, double now, FarView view, double[][] rays) {
        SmokeRaidTest.Matter matter = new SmokeRaidTest.Matter(view);
        for (SmokeGrid.Source s : raid) {
            matter.glow = s.drawsGlow();
            s.emit(now, matter);
        }
        double[] out = new double[rays.length];
        for (int r = 0; r < rays.length; r++) {
            double[] d = rays[r];
            double nn = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]), tau = 0;
            for (double[] g : matter.gaussians) {
                if (!(g[5] > 0) || !(g[6] > 0)) continue;
                double along = SmokePuffs.sightDepth(g[0], g[1], g[2], g[3], g[4], g[5], d[0] / nn, d[1] / nn, d[2] / nn);
                tau -= Math.log(1 - Math.min(g[6] * (1 - Math.exp(-along)), 1 - 1e-12));
            }
            out[r] = Math.exp(-tau);
        }
        return out;
    }
}
