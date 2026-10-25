package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.client.fx.FireSmoke;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.FirePlume;
import ua.zentix.incoming.client.fx.smoke.GroundDust;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Stability;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Цена дыма налёта из 100 ударов (стенд сцен 25.10, сцена «100»: заливка 41/104/175 экранов снаружи/с края/внутри при
 * бюджете 4, видеокарта 13,5/39,4/55,5 мс против 2,9/5,8/6,4 у 3.4.1): 100 шахедов подсолнухом Фогеля в круге 120 м,
 * через 1,5 с, окна — через 35 с после последнего; топливо в воронке горит 150 с — 70 шлейфов пожаров; ветер 4 м/с,
 * класс D. Облака — модель ({@link CloudModel}: шапка, ножка трубкой, пыль у земли), шлейфы — {@link FireSmoke#lay}; кадр
 * раскладывает тот же приёмник слоя, что в игре ({@link SmokePuffs}), три камеры стенда. Пол кадра (по одному клубу на
 * гаусс и место трубки) — 18/70/153 экрана, до 10 раз выше бюджета (прежде бюджет здесь не действовал вовсе).
 */
class SmokeRaidTest {
    /** Пожар топлива в воронке шахеда (как {@code PlumeContinuityTest.POOL}): горит 150 с с удара. */
    record Fire(double x, double z, double born, long seed) implements SmokeGrid.Source {
        static final FireSmoke.Fuel POOL = new FireSmoke.Fuel(2.2e6, Math.PI * 4, 0.04 / 43.4e6);
        static final double WIND = 4, BURNS = 150;

        @Override
        public boolean emit(double seconds, SmokeGrid.Sink out) {
            double age = seconds - born;
            FirePlume plume = new FirePlume(POOL.watts(), Math.sqrt(POOL.area() / Math.PI), POOL.sootPerJoule(), WIND, Stability.D);
            FireSmoke.lay(POOL, x, 0, z, 1, 0, WIND, Stability.D, plume.visible(FireSmoke.REACH), plume.size,
                    p -> age - p / WIND > 0 && age - p / WIND < BURNS ? 1 : 0, p -> 1, out);
            return true;
        }

        @Override
        public long seed() {
            return seed;
        }
    }

    /** Налёт стенда: 100 ударов в круге 120 м, последний — за 35 с до кадра, у кого топливо ещё горит — шлейф. */
    static List<SmokeGrid.Source> raid(double now) {
        CloudModel.Air air = new CloudModel.Air(Stability.STANDARD_N, Fire.WIND, 0, 0.15, (int) Stability.D, 0.5);
        List<SmokeGrid.Source> out = new ArrayList<>();
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < 100; i++) {
            double r = 120 * Math.sqrt((i + 0.5) / 100), a = i * golden;
            double x = r * Math.cos(a), z = r * Math.sin(a), born = now - 35 - 1.5 * (99 - i);
            CloudModel m = new CloudModel(BlastSource.of(WeaponType.DRONE, false), air, false, 0, GroundDust.Ground.uniform(0, 1));
            out.add(new SmokeFillTest.Blast(m, x, 0, z, born, 1000L + i));
            if (now - born < Fire.BURNS + 60) out.add(new Fire(x, z, born, 5000L + i));
        }
        return out;
    }

    /** Камеры стенда (tools/stand/scenes.json): снаружи — 200 м, 30 м над землёй; с края — 120 м, 10 м; внутри — в середине, 4 м. */
    static final Vec3[] EYES = {new Vec3(-200, 30, 0), new Vec3(0, 10, -120), new Vec3(0, 4, 0)};
    static final Vec3[] AIMS = {new Vec3(0, 15, 0), new Vec3(0, 10, 0), new Vec3(100, 20, 0)};
    static final String[] NAMES = {"снаружи", "с края", "внутри"};

    static FarView view(int camera) {
        Vec3 e = EYES[camera], a = AIMS[camera];
        return SmokeFillTest.view(e, new Vector3f((float) (a.x - e.x), (float) (a.y - e.y), (float) (a.z - e.z)));
    }

    /** Кадр налёта с бюджетом budget: несколько кадров подряд (бюджет клубов трубок и уровень слияния — по прошлым). */
    static SmokeFillTest.Screen frame(List<SmokeGrid.Source> raid, double now, FarView view, double budget) {
        SmokeFillTest.Screen s = new SmokeFillTest.Screen(view);
        double was = SmokePuffs.budget;
        SmokePuffs.budget = budget;
        try {
            SmokePuffs.resetBudget();
            FarFlightView.reset();
            for (int frame = 0; frame < 40; frame++) {
                s.quads.clear();
                s.veil = null;
                FarFlightView.budget();
                for (SmokeGrid.Source b : raid) b.emit(now, SmokePuffs.sink(view, s, b, now));
                SmokePuffs.flush();
            }
        } finally {
            SmokePuffs.budget = was;
        }
        return s;
    }

    /**
     * Пропускание самого вещества налёта по лучу (u, v) экрана: e^(−Στ) через каждый гаусс облаков ({@link SmokePuffs#sightDepth})
     * и через трубки ножек и шлейфов — гауссами через σ/2 вдоль оси (их сумма — та же трубка); у тела огня — доля дыма, как
     * у приёмника слоя. Без квадратов: с ним сверяются оба кадра — полный и в бюджете.
     */
    static double[] truth(List<SmokeGrid.Source> raid, double now, FarView view) {
        double[] parts = new double[18];
        Vector3f f = view.forward(), l = view.left(), up = view.up();
        Matter matter = new Matter(view);
        for (SmokeGrid.Source s : raid) {
            matter.glow = s.drawsGlow();
            s.emit(now, matter);
        }
        List<double[]> gs = matter.gaussians;
        for (int i = 0; i < 48; i++) {
            for (int j = 0; j < 27; j++) {
                double u = -SmokeFillTest.W + (i + 0.5) * 2 * SmokeFillTest.W / 48, v = -SmokeFillTest.H + (j + 0.5) * 2 * SmokeFillTest.H / 27;
                double rx = f.x + u * l.x + v * up.x, ry = f.y + u * l.y + v * up.y, rz = f.z + u * l.z + v * up.z, n = Math.sqrt(rx * rx + ry * ry + rz * rz);
                double tau = 0;
                for (double[] g : gs) {
                    if (!(g[5] > 0) || !(g[6] > 0)) continue;
                    double along = SmokePuffs.sightDepth(g[0], g[1], g[2], g[3], g[4], g[5], rx / n, ry / n, rz / n);
                    tau -= Math.log(1 - Math.min(g[6] * (1 - Math.exp(-along)), 1 - 1e-12));
                }
                parts[i / 8 * 3 + j / 9] += Math.exp(-tau) / 72;
            }
        }
        return parts;
    }

    /** Вещество источников гауссами от глаза: середина, σh, σv, ослабление всей массы, доля дыма (у тела огня). */
    static final class Matter implements SmokeGrid.Sink {
        final List<double[]> gaussians = new ArrayList<>();
        final FarView view;
        boolean glow;

        Matter(FarView view) {
            this.view = view;
        }

        @Override
        public void segment(double x, double y, double z, double ax, double ay, double az, double sh, double sv, double soot, double dust,
                            double water, float dustR, float dustG, float dustB, double kelvin, double rise) {
            Vec3 eye = view.camera();
            gaussians.add(new double[]{x - eye.x, y - eye.y, z - eye.z, sh, sv, CloudModel.SOOT_K * soot + SmokeGrid.dustK(dustR, dustG, dustB) * dust,
                    SmokePuffs.smokeShare(glow, kelvin, view.skyLuminance())});
        }

        /** Трубка — гауссами через σ/2 вдоль оси: их сумма — та же трубка (сечение √(σh·σv), как у приёмника слоя). */
        @Override
        public void tube(double ax, double ay, double az, double sha, double sva, double sootA, double dustA, double bx, double by, double bz,
                         double shb, double svb, double sootB, double dustB, float albedoR, float albedoG, float albedoB, double kelvin, double rise,
                         double sigma0) {
            Vec3 eye = view.camera();
            double share = SmokePuffs.smokeShare(glow, kelvin, view.skyLuminance());
            double k = SmokeGrid.dustK(albedoR, albedoG, albedoB), len = Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
            double sa = Math.sqrt(sha * sva), sb = Math.sqrt(shb * svb);
            int n = (int) Math.max(1, Math.ceil(len / (0.5 * Math.min(sa, sb))));
            for (int j = 0; j < n; j++) {
                double u = (j + 0.5) / n, s = sa + (sb - sa) * u;
                double e = CloudModel.SOOT_K * (sootA + (sootB - sootA) * u) + k * (dustA + (dustB - dustA) * u);
                gaussians.add(new double[]{ax + (bx - ax) * u - eye.x, ay + (by - ay) * u - eye.y, az + (bz - az) * u - eye.z, s, s, e * len / n, share});
            }
        }
    }

    /**
     * Налёт из 100 ударов с трёх камер стенда. Без бюджета пол кадра выше бюджета (снаружи — чуть, с края и изнутри —
     * в 4–10 раз); с бюджетом заливка — не больше бюджета
     * (с запасом на оценку площади кучки, 15 %), пол — не выше бюджета, гауссов вуали — не больше {@link SmokePuffs#VEIL_MAX},
     * кучек — не больше {@link SmokePuffs#BLOBS} и {@code FarFlightView.CLUB_BUDGET} вместе. Пропускание по 18 частям кадра против самого вещества модели ({@link #truth}) — не дальше, чем у
     * полного кадра, больше чем на шум подробности клубов (0,08, {@code SmokeFillTest.clusterDetailNoise}).
     */
    @Test
    void raidOfAHundredStaysInTheBudgetAtTheSameDensity() throws IOException {
        double now = 1000;
        List<SmokeGrid.Source> raid = raid(now);
        double[][] prof = PlumeContinuityTest.profiles();
        boolean ok = true;
        StringBuilder all = new StringBuilder();
        for (int c = 0; c < 3; c++) {
            FarView v = view(c);
            SmokeFillTest.Screen free = frame(raid, now, v, Double.POSITIVE_INFINITY);
            double freeFloor = SmokePuffs.lastRaw();
            SmokeFillTest.Screen budget = frame(raid, now, v, SmokePuffs.FILL_BUDGET);
            double raw = SmokePuffs.lastRaw(), floor = SmokePuffs.lastFloor();
            int level = SmokePuffs.lastLevel(), veils = SmokePuffs.lastVeils(), clusters = SmokePuffs.lastItems();
            double[] real = truth(raid, now, v);
            double worstFree = SmokeFillTest.worstBlock(free, real, prof), worst = SmokeFillTest.worstBlock(budget, real, prof);
            String got = String.format(Locale.ROOT,
                    "%s: без бюджета %d квадратов, %.1f экранов (пол %.1f); с бюджетом %d, %.1f (пол %.1f, до слияния %.1f, уровень %d, вуаль %d гауссов, кучек %d); худшая часть против вещества: полный %.3f, в бюджете %.3f%n  вещество %s%n  полный   %s%n  бюджет   %s",
                    NAMES[c], free.quads.size(), free.fill(), freeFloor, budget.quads.size(), budget.fill(), floor, raw, level, veils, clusters, worstFree,
                    worst, row(real), row(SmokeFillTest.blocks(free, prof)), row(SmokeFillTest.blocks(budget, prof)));
            System.out.println("RAID " + got);
            all.append(got).append('\n');
            ok &= raw > SmokePuffs.FILL_BUDGET;
            ok &= budget.fill() <= 1.15 * SmokePuffs.FILL_BUDGET && floor <= SmokePuffs.FILL_BUDGET + 1e-9;
            ok &= veils <= SmokePuffs.VEIL_MAX && clusters <= SmokePuffs.BLOBS + FarFlightView.CLUB_BUDGET;
            ok &= worst <= worstFree + 0.08;
        }
        SmokePuffs.resetBudget();
        assertTrue(ok, all.toString());
    }

    /**
     * Работа плана кадра не растёт с налётом: 400 ударов (облака без пожаров) — клубов облаков в плане не больше
     * {@link SmokePuffs#BLOBS} (сверх — сливаются до плана), заливка — в бюджете.
     */
    @Test
    void planWorkStaysBoundedAtFourHundredStrikes() {
        double now = 1000;
        CloudModel.Air air = new CloudModel.Air(Stability.STANDARD_N, Fire.WIND, 0, 0.15, (int) Stability.D, 0.5);
        List<SmokeGrid.Source> raid = new ArrayList<>();
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < 400; i++) {
            double r = 240 * Math.sqrt((i + 0.5) / 400), a = i * golden;
            CloudModel m = new CloudModel(BlastSource.of(WeaponType.DRONE, false), air, false, 0, GroundDust.Ground.uniform(0, 1));
            raid.add(new SmokeFillTest.Blast(m, r * Math.cos(a), 0, r * Math.sin(a), now - 35 - 0.5 * (399 - i), 7000L + i));
        }
        SmokeFillTest.Screen s = frame(raid, now, view(0), SmokePuffs.FILL_BUDGET);
        String got = String.format(Locale.ROOT, "клубов облаков в плане %d, кучек %d, заливка %.1f (пол %.1f, до слияния %.1f)", SmokePuffs.lastBlobs(),
                SmokePuffs.lastItems(), s.fill(), SmokePuffs.lastFloor(), SmokePuffs.lastRaw());
        System.out.println("RAID 400: " + got);
        SmokePuffs.resetBudget();
        assertTrue(SmokePuffs.lastBlobs() <= SmokePuffs.BLOBS && s.fill() <= 1.15 * SmokePuffs.FILL_BUDGET, got);
    }

    /** Части кадра строками сверху вниз. */
    static String row(double[] parts) {
        StringBuilder b = new StringBuilder();
        for (int j = 2; j >= 0; j--) {
            b.append(j == 2 ? "[" : " / ");
            for (int i = 0; i < 6; i++) b.append(String.format(Locale.ROOT, i == 0 ? "%.2f" : " %.2f", parts[i * 3 + j]));
        }
        return b.append(']').toString();
    }
}
