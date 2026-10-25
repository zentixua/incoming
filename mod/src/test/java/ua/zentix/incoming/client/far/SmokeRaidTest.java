package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.client.fx.FireSmoke;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.smoke.BlastTarget;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.Ejecta;
import ua.zentix.incoming.client.fx.smoke.FirePlume;
import ua.zentix.incoming.client.fx.smoke.GroundDust;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Stability;
import ua.zentix.incoming.warhead.GroundMaterial;

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

    /**
     * Удар шахеда по цели target — те же клубы, что у события {@code FarBlasts} ({@code Event.emit}): облако модели (шапка,
     * ножка трубкой, пыль у земли), пыль раздробленной цели своим видом — у рыхлого кадрами выброса, у связного — дробления
     * (её масса — доля мелочи вещества: у грунта — пыль, что ушла в воздух, у камня — 10 % разрушенного), завеса выброса у
     * грунта ({@code FarBlasts.curtain}).
     */
    static final class Strike implements SmokeGrid.Source {
        final CloudModel model;
        final Ejecta curtain;
        final double curtainK, x, z, born;
        final long seed;
        final float dustR, dustG, dustB, matterR, matterG, matterB, clodR, clodG, clodB;

        Strike(CloudModel.Air air, GroundMaterial material, double x, double z, double born, long seed) {
            BlastSource source = BlastSource.of(WeaponType.DRONE, false);
            BlastTarget target = BlastTarget.of(material, source.chargeKg(), 0);
            this.model = new CloudModel(source, air, target, 0, GroundDust.Ground.uniform(0, 1));
            this.x = x;
            this.z = z;
            this.born = born;
            this.seed = seed;
            float[] dust = FarBlasts.dustColour(material);
            dustR = SmokeGrid.dustAlbedo(dust[0]);
            dustG = SmokeGrid.dustAlbedo(dust[1]);
            dustB = SmokeGrid.dustAlbedo(dust[2]);
            BlastTarget.Matter m = target.matter();
            matterR = SmokeGrid.dustAlbedo(m.r);
            matterG = SmokeGrid.dustAlbedo(m.g);
            matterB = SmokeGrid.dustAlbedo(m.b);
            clodR = SmokeGrid.dustAlbedo(material.r);
            clodG = SmokeGrid.dustAlbedo(material.g);
            clodB = SmokeGrid.dustAlbedo(material.b);
            curtain = FarBlasts.curtain(model, source);
            curtainK = curtain == null ? 0 : m.extinction() / SmokeGrid.dustK(clodR, clodG, clodB);
        }

        @Override
        public boolean emit(double seconds, SmokeGrid.Sink out) {
            double t = seconds - born;
            if (t < 0 || t >= model.life) return false;
            double growth = puffGrowth(seconds);
            boolean loose = model.target.matter().loose;
            SmokeGrid.Look crush = loose ? SmokeGrid.Look.EJECTA : SmokeGrid.Look.CRUSH;
            double crushFrame = FxAtlas.lifeFrame(crush, model.crushed.life > 0 ? t / model.crushed.life : 0);
            double share = model.capMatterShare(t);
            float cr = FarBlasts.mix(matterR, dustR, share), cg = FarBlasts.mix(matterG, dustG, share), cb = FarBlasts.mix(matterB, dustB, share);
            model.blobs(t, new CloudModel.Blobs() {
                @Override
                public void blob(double bx, double by, double bz, double sh, double sv, double soot, double dustKg, double water, double kelvin, double rise) {
                    out.look(SmokeGrid.Look.SMOKE, 0);
                    out.blob(x + bx, by, z + bz, sh, sv, soot, dustKg, water, cr, cg, cb, kelvin, rise);
                }

                @Override
                public void ground(double bx, double by, double bz, double sh, double sv, double dustKg, double kelvin) {
                    out.look(SmokeGrid.Look.SMOKE, 0);
                    out.blob(x + bx, by, z + bz, sh, sv, 0, dustKg, 0, dustR, dustG, dustB, kelvin, 0);
                }

                @Override
                public void crushed(double bx, double by, double bz, double sh, double sv, double dustKg, double kelvin) {
                    out.look(crush, crushFrame);
                    out.blob(x + bx, by, z + bz, sh, sv, 0, dustKg, 0, matterR, matterG, matterB, kelvin, 0);
                }

                @Override
                public void tube(double ax, double ay, double az, double sa, double massA, double bx, double by, double bz, double sb, double massB,
                                 double kelvin, double rise) {
                    out.tube(x + ax, ay, z + az, sa, sa, 0, massA, x + bx, by, z + bz, sb, sb, 0, massB, dustR, dustG, dustB, kelvin, rise,
                            sa / Math.max(growth, 1));
                }
            });
            out.look(SmokeGrid.Look.SMOKE, 0);
            if (curtain != null && t < curtain.life) FarBlasts.curtainClubs(curtain, t, seed, x, 0, z, curtainK, clodR, clodG, clodB, out);
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

    /**
     * Налёт стенда по грунту: 100 ударов в круге 120 м, последний — за 35 с до кадра; каждый третий — в камень (дроблёная
     * пыль связного), остальные — в землю (завеса выброса); у кого топливо ещё горит — шлейф.
     */
    static List<SmokeGrid.Source> raid(double now) {
        CloudModel.Air air = new CloudModel.Air(Stability.STANDARD_N, Fire.WIND, 0, 0.15, (int) Stability.D, 0.5);
        List<SmokeGrid.Source> out = new ArrayList<>();
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < 100; i++) {
            double r = 120 * Math.sqrt((i + 0.5) / 100), a = i * golden;
            double x = r * Math.cos(a), z = r * Math.sin(a), born = now - 35 - 1.5 * (99 - i);
            out.add(new Strike(air, i % 3 == 2 ? GroundMaterial.STONE : GroundMaterial.DIRT, x, z, born, 1000L + i));
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

    /** Наибольшее за кадры {@link #frame} число мест трубок и клубов облаков в плане и заливка сверх плана (с первого кадра). */
    static int maxPlaces, maxBlobs;
    static double worstOverPlan;

    /** Кадр налёта с бюджетом budget: несколько кадров подряд (бюджет клубов трубок и уровень слияния — по прошлым). */
    static SmokeFillTest.Screen frame(List<SmokeGrid.Source> raid, double now, FarView view, double budget) {
        SmokeFillTest.Screen s = new SmokeFillTest.Screen(view);
        double was = SmokePuffs.budget;
        SmokePuffs.budget = budget;
        try {
            SmokePuffs.resetBudget();
            FarFlightView.reset();
            maxPlaces = maxBlobs = 0;
            worstOverPlan = Double.NEGATIVE_INFINITY;
            for (int frame = 0; frame < 40; frame++) {
                s.clear();
                FarFlightView.budget();
                for (SmokeGrid.Source b : raid) b.emit(now, SmokePuffs.sink(view, s, b, now));
                SmokePuffs.flush();
                maxPlaces = Math.max(maxPlaces, SmokePuffs.lastPlaces());
                maxBlobs = Math.max(maxBlobs, SmokePuffs.lastBlobs());
                worstOverPlan = Math.max(worstOverPlan, s.fill() - SmokePuffs.lastFill());
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
            // отрезок (струя завесы) — гауссами через σ/2 вдоль него, как трубка
            double len = 2 * Math.sqrt(ax * ax + ay * ay + az * az), ext = CloudModel.SOOT_K * soot + SmokeGrid.dustK(dustR, dustG, dustB) * dust;
            int n = (int) Math.max(1, Math.ceil(len / (0.5 * Math.min(sh, sv))));
            for (int j = 0; j < n; j++) {
                double f = n == 1 ? 0 : (j + 0.5) / n * 2 - 1;
                gaussians.add(new double[]{x + ax * f - eye.x, y + ay * f - eye.y, z + az * f - eye.z, sh, sv, ext / n,
                        SmokePuffs.smokeShare(glow, kelvin, view.skyLuminance())});
            }
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
     * Налёт из 100 ударов по грунту (с завесой выброса и дроблёной пылью камня) с трёх камер стенда. Без бюджета пол кадра
     * выше бюджета; с бюджетом квадраты заливают не больше плана, план — не больше бюджета (в каждом кадре с первого), пол —
     * не выше бюджета, гауссов вуали — не больше {@link SmokePuffs#VEIL_MAX}, мест трубок и клубов облаков в плане — не
     * больше {@link SmokePuffs#PLACES} и {@link SmokePuffs#BLOBS} в каждом кадре с первого. Пропускание по 18 частям кадра против самого вещества модели ({@link #truth}) — не дальше, чем у
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
            double over = worstOverPlan;
            int places = maxPlaces, blobs = maxBlobs;
            double[] real = truth(raid, now, v);
            double worstFree = SmokeFillTest.worstBlock(free, real, prof), worst = SmokeFillTest.worstBlock(budget, real, prof);
            String got = String.format(Locale.ROOT,
                    "%s: без бюджета %d квадратов, %.1f экранов (пол %.1f); с бюджетом %d, %.1f (пол %.1f, до слияния %.1f, уровень %d, вуаль %d гауссов, кучек %d); худшая часть против вещества: полный %.3f, в бюджете %.3f%n  вещество %s%n  полный   %s%n  бюджет   %s",
                    NAMES[c], free.quads.size(), free.fill(), freeFloor, budget.quads.size(), budget.fill(), floor, raw, level, veils, clusters, worstFree,
                    worst, row(real), row(SmokeFillTest.blocks(free, prof)), row(SmokeFillTest.blocks(budget, prof)));
            System.out.println("RAID " + got);
            all.append(got).append('\n');
            String bounds = String.format(Locale.ROOT, "  заливка сверх плана %.4f, план %.2f; мест трубок до %d, клубов облаков до %d", over,
                    SmokePuffs.lastFill(), places, blobs);
            System.out.println("RAID " + bounds);
            all.append(bounds).append('\n');
            ok &= raw > SmokePuffs.FILL_BUDGET;
            ok &= over <= 1e-9 && SmokePuffs.lastFill() <= SmokePuffs.FILL_BUDGET + 1e-9 && floor <= SmokePuffs.FILL_BUDGET + 1e-9;
            ok &= veils <= SmokePuffs.VEIL_MAX && places <= SmokePuffs.PLACES && blobs <= SmokePuffs.BLOBS;
            ok &= worst <= worstFree + 0.08;
        }
        SmokePuffs.resetBudget();
        assertTrue(ok, all.toString());
    }

    /**
     * Работа плана кадра не растёт с налётом: 400 ударов по грунту (облака без пожаров) — клубов облаков в плане не больше
     * {@link SmokePuffs#BLOBS} (сверх — сливаются до плана и отбираются по вкладу) в каждом кадре с первого, заливка — в
     * бюджете.
     */
    @Test
    void planWorkStaysBoundedAtFourHundredStrikes() {
        double now = 1000;
        CloudModel.Air air = new CloudModel.Air(Stability.STANDARD_N, Fire.WIND, 0, 0.15, (int) Stability.D, 0.5);
        List<SmokeGrid.Source> raid = new ArrayList<>();
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < 400; i++) {
            double r = 240 * Math.sqrt((i + 0.5) / 400), a = i * golden;
            raid.add(new Strike(air, i % 3 == 2 ? GroundMaterial.STONE : GroundMaterial.DIRT, r * Math.cos(a), r * Math.sin(a), now - 35 - 0.5 * (399 - i),
                    7000L + i));
        }
        SmokeFillTest.Screen s = frame(raid, now, view(0), SmokePuffs.FILL_BUDGET);
        String got = String.format(Locale.ROOT, "клубов облаков в плане %d, кучек %d, заливка %.1f (пол %.1f, до слияния %.1f)", SmokePuffs.lastBlobs(),
                SmokePuffs.lastItems(), s.fill(), SmokePuffs.lastFloor(), SmokePuffs.lastRaw());
        System.out.println("RAID 400: " + got);
        SmokePuffs.resetBudget();
        assertTrue(maxBlobs <= SmokePuffs.BLOBS && maxPlaces <= SmokePuffs.PLACES && worstOverPlan <= 1e-9 && s.fill() <= SmokePuffs.FILL_BUDGET + 1e-9,
                got + ", клубов облаков в плане до " + maxBlobs + ", мест до " + maxPlaces);
    }

    /** Источник одного гаусса (σh, σv) с ослаблением ext в (x, y, z), сажей. */
    static SmokeGrid.Source blob(double x, double y, double z, double sh, double sv, double ext, long seed) {
        return new SmokeGrid.Source() {
            @Override
            public boolean emit(double seconds, SmokeGrid.Sink out) {
                out.blob(x, y, z, sh, sv, ext / SmokeGrid.SOOT_K, 0, 0, 0.6f, 0.55f, 0.45f, 300, 0);
                return true;
            }

            @Override
            public long seed() {
                return seed;
            }
        };
    }

    /**
     * Ревью #460: план считал дробную кучку, а рисуются ⌈кучки⌉ квадратов с разбросом размера и места — 12 перекрывающихся
     * гауссов (σ 6,4 м, толща середины 3) в 30 м: план 8, квадраты 11,3 экрана. Теперь квадраты заливают не больше плана,
     * план — не больше бюджета, при бюджетах 4, 8 и настоящем.
     */
    @Test
    void drawnFillNeverExceedsThePlan() {
        double now = 0, sigma = 6.4, ext = 3 * 2 * Math.PI * sigma * sigma;
        List<SmokeGrid.Source> group = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            double a = 2 * Math.PI * i / 12;
            group.add(blob(30 + 1.5 * sigma * Math.cos(a), 10 + 0.5 * sigma * Math.sin(a), 1.5 * sigma * Math.sin(a), sigma, sigma, ext, 100 + i));
        }
        FarView v = SmokeFillTest.view(new Vec3(0, 10, 0), new Vector3f(1, 0, 0));
        for (double budget : new double[]{4, 8, SmokePuffs.FILL_BUDGET}) {
            SmokeFillTest.Screen s = frame(group, now, v, budget);
            String got = String.format(Locale.ROOT, "бюджет %.0f: квадраты %.3f, план %.3f, доля кучки %.2f", budget, s.fill(), SmokePuffs.lastFill(),
                    SmokePuffs.lastShare());
            System.out.println("PLAN " + got);
            assertTrue(worstOverPlan <= 1e-9 && SmokePuffs.lastFill() <= budget + 1e-9, got);
        }
        SmokePuffs.resetBudget();
    }

    /**
     * Ревью #460: предел клубов облаков сливал ячейками без конца — 2001 гаусс σ 1 м сеткой через 40 м выходил гауссами σ 20 м,
     * дым в пустых промежутках. Теперь слияние — не дальше двух краёв, остаток — отбором по вкладу: клубов в плане не больше
     * {@link SmokePuffs#BLOBS}, и ни один квадрат не крупнее края своего гаусса (с ростом до пикселя и разбросом).
     */
    @Test
    void blobLimitDoesNotSmearOverClearSky() {
        double sigma = 1, ext = 2 * 2 * Math.PI * sigma * sigma;
        List<SmokeGrid.Source> one = new ArrayList<>();
        one.add(new SmokeGrid.Source() {
            @Override
            public boolean emit(double seconds, SmokeGrid.Sink out) {
                for (int i = 0; i < 2001; i++) out.blob(40 * (i % 45 - 22), 0, 40 * (i / 45 - 22), sigma, sigma, ext / SmokeGrid.SOOT_K, 0, 0, 0.6f, 0.55f,
                        0.45f, 300, 0);
                return true;
            }
        });
        FarView v = SmokeFillTest.view(new Vec3(0, 300, -200), new Vector3f(0, -300, 200));
        SmokeFillTest.Screen s = frame(one, 0, v, SmokePuffs.FILL_BUDGET);
        double depthMin = 250, biggest = 0;
        for (double[] q : s.quads) biggest = Math.max(biggest, Math.max(q[2], q[3]));
        // край густого гаусса — до 4σ, у одиночного клуба — без разброса; квадрат на экране — не больше (край)/(глубина) с полом в пиксель
        double most = Math.max(4 * sigma / depthMin, 2 * SmokeFillTest.PIXEL);
        String got = String.format(Locale.ROOT, "клубов облаков в плане до %d, квадратов %d, наибольший полуразмер %.4f (край — до %.4f)", maxBlobs,
                s.quads.size(), biggest, most);
        System.out.println("THIN " + got);
        SmokePuffs.resetBudget();
        assertTrue(maxBlobs <= SmokePuffs.BLOBS && !s.quads.isEmpty() && biggest <= most, got);
    }

    /**
     * Ревью #460: у границы бюджета слияние то шло, то нет — пол 8,28↔7,80 при движении камеры, уровень 1↔0, кучек 1↔60.
     * Уровень прошлого кадра держится, пока пол не ляжет под 0,8 бюджета, и только потом сходит.
     */
    @Test
    void mergeLevelHoldsAtTheBudgetBorder() {
        // ряд бледных клубов разных источников поперёк взгляда: друг друга не прячут, а лежат друг на друге — сливаются
        double now = 0, sigma = 3, ext = 0.5 * 2 * Math.PI * sigma * sigma;
        List<SmokeGrid.Source> row = new ArrayList<>();
        for (int i = 0; i < 40; i++) row.add(blob(60, 10, 1.5 * (i - 20), sigma, sigma, ext, 300 + i));
        FarView v = SmokeFillTest.view(new Vec3(0, 10, 0), new Vector3f(1, 0, 0));
        double was = SmokePuffs.budget;
        try {
            SmokePuffs.budget = Double.POSITIVE_INFINITY;
            SmokePuffs.resetBudget();
            SmokeFillTest.Screen s = new SmokeFillTest.Screen(v);
            step(row, now, v, s);
            double raw = SmokePuffs.lastRaw();
            // выше бюджета — слияние; чуть ниже (пол — 0,95 бюджета) — уровень держится; под 0,8 — сходит
            SmokePuffs.budget = raw / 1.3;
            step(row, now, v, s);
            int over = SmokePuffs.lastLevel();
            SmokePuffs.budget = raw / 0.95;
            step(row, now, v, s);
            int near = SmokePuffs.lastLevel();
            SmokePuffs.budget = raw / 0.7;
            step(row, now, v, s);
            int under = SmokePuffs.lastLevel();
            String got = "пол " + raw + ": уровень над бюджетом " + over + ", у бюджета " + near + ", под 0,8 — " + under;
            System.out.println("HYST " + got);
            assertTrue(over > 0 && near > 0 && under == 0, got);
        } finally {
            SmokePuffs.budget = was;
            SmokePuffs.resetBudget();
        }
    }

    private static void step(List<SmokeGrid.Source> raid, double now, FarView v, SmokeFillTest.Screen s) {
        s.clear();
        for (SmokeGrid.Source b : raid) b.emit(now, SmokePuffs.sink(v, s, b, now));
        SmokePuffs.flush();
    }

    /**
     * Ревью #460, круг 2: отрезки сверх мест кадра шли лентой мимо бюджета — 300 стоячих трубок (100 м, σ 1 м) в 30 м
     * заливали 29,5 экрана при бюджете 16, 1000 — 75. Лента — тоже заливка: в плане и бюджете, не влезла — не рисуется.
     * В каждом кадре с первого квадраты и ленты заливают не больше плана, план — не больше бюджета.
     */
    @Test
    void ribbonsStayInTheBudget() {
        double sigma = 1, soot = 1.0 * Math.sqrt(2 * Math.PI) * sigma / CloudModel.SOOT_K;
        for (int n : new int[]{300, 1000}) {
            List<SmokeGrid.Source> tubes = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                double x = 30 + 3 * (i / 30), z = -45 + 3 * (i % 30);
                long seed = 9000L + i;
                tubes.add(new SmokeGrid.Source() {
                    @Override
                    public boolean emit(double seconds, SmokeGrid.Sink out) {
                        for (int k = 0; k < 10; k++) out.tube(x, 10 * k, z, sigma, sigma, soot, 0, x, 10 * (k + 1), z, sigma, sigma, soot, 0, 0.6f, 0.55f,
                                0.45f, 300, 0, sigma);
                        return true;
                    }

                    @Override
                    public long seed() {
                        return seed;
                    }
                });
            }
            SmokeFillTest.Screen s = frame(tubes, 0, SmokeFillTest.view(new Vec3(0, 20, 0), new Vector3f(1, 0, 0)), SmokePuffs.FILL_BUDGET);
            String got = String.format(Locale.ROOT, "%d трубок: заливка %.2f (план %.2f), сверх плана до %.4f, мест до %d, полос лент %d", n, s.fill(),
                    SmokePuffs.lastFill(), worstOverPlan, maxPlaces, s.polygons.size());
            System.out.println("RIBBONS " + got);
            assertTrue(worstOverPlan <= 1e-9 && s.fill() <= SmokePuffs.FILL_BUDGET + 1e-9 && maxPlaces <= SmokePuffs.PLACES, got);
        }
        SmokePuffs.resetBudget();
    }

    /**
     * Лента — вид трубки уже {@link FarFlightView#PUFFS_FULL} px: полоса с прямыми краями и одним цветом поперёк. В налёте
     * стенда шлейфы 70 пожаров просили ~8000 мест при 3000, порог ширины (прежний множитель {@code puffScale}) рос к 40-му
     * кадру в 28–41 раз, и шлейфы шириной до 90–180 px шли лентами: полосы до 212/491/701 px снаружи/с края/внутри — светлые
     * прямые полосы в кадрах стенда 25.10 (с края — косые). Ни при каком бюджете мест полоса не шире ленты узкой трубки:
     * широкая часть трубки без мест — в вуали.
     */
    @Test
    void ribbonsAreOnlyNarrowTubes() {
        double now = 1000;
        List<SmokeGrid.Source> raid = raid(now);
        double limit = FarSprites.RIBBON * FarFlightView.PUFFS_FULL * 1.1;
        StringBuilder all = new StringBuilder();
        boolean ok = true;
        for (int c = 0; c < 3; c++) {
            FarView v = view(c);
            SmokeFillTest.Screen s = frame(raid, now, v, SmokePuffs.FILL_BUDGET);
            double widest = 0;
            int strips = 0;
            for (double[] p : s.polygons) {
                if (SmokeFillTest.isFlat(p) || p[12] < 0.002) continue;
                strips++;
                widest = Math.max(widest, Math.max(pixels(v, p, 0), pixels(v, p, 1)));
            }
            String got = String.format(Locale.ROOT, "%s: полос %d, самая широкая %.1f px (предел %.1f)", NAMES[c], strips, widest, limit);
            System.out.println("RIBBON WIDTH " + got);
            all.append(got).append('\n');
            ok &= widest <= limit;
        }
        SmokePuffs.resetBudget();
        assertTrue(ok, all.toString());
    }

    /** Трубка одного куска от a до b (σ у концов) с толщей поперёк оси tau — источником (сажа на метр — по толще). */
    private static SmokeGrid.Source piece(Vec3 a, double sa, Vec3 b, double sb, double tau, long seed) {
        double soot = tau * Math.sqrt(2 * Math.PI) / CloudModel.SOOT_K;
        return new SmokeGrid.Source() {
            @Override
            public boolean emit(double seconds, SmokeGrid.Sink out) {
                out.tube(a.x, a.y, a.z, sa, sa, soot * sa, 0, b.x, b.y, b.z, sb, sb, soot * sb, 0, 0.6f, 0.55f, 0.45f, 300, 0, Math.min(sa, sb));
                return true;
            }

            @Override
            public long seed() {
                return seed;
            }
        };
    }

    /** Кадр трубок с порогом ширины, выросшим в scale раз (мест кадра мало — как в налёте стенда). */
    private static SmokeFillTest.Screen scaled(List<SmokeGrid.Source> sources, FarView v, double scale) throws ReflectiveOperationException {
        java.lang.reflect.Field field = FarFlightView.class.getDeclaredField("puffScale");
        field.setAccessible(true);
        SmokeFillTest.Screen s = new SmokeFillTest.Screen(v);
        SmokePuffs.resetBudget();
        FarFlightView.reset();
        try {
            for (int f = 0; f < 3; f++) {
                field.setDouble(null, scale);
                step(sources, 0, v, s);
            }
        } finally {
            FarFlightView.reset();
            SmokePuffs.resetBudget();
        }
        return s;
    }

    /** Ширина трубки толщей tau и σ на экране в d блоках от глаза, пикселей кадра проверок. */
    private static double tubePixels(double tau, double sigma, double d) {
        return ua.zentix.incoming.client.fx.plume.TrailModel.width(tau, sigma) / (d * SmokeFillTest.PIXEL);
    }

    /**
     * Ревью #466: отрезок, который клубы взяли целиком (ближний конец широк — {@code whole}), прежде ещё раз уходил в вуаль
     * долей «шире ленты без доли клубов» у дальнего конца — вещество дважды (с края налёта — 1070 раз за 40 кадров). Взятое
     * клубами в вуаль не идёт.
     */
    @Test
    void wholeSegmentGoesOnlyToClubs() throws ReflectiveOperationException {
        double scale = 6, tau = 1, sa = 2, sb = 6;
        Vec3 eye = new Vec3(0, 10, 0), a = new Vec3(40, 10, 3), b = new Vec3(600, 10, 3);
        FarView v = SmokeFillTest.view(eye, new Vector3f(1, 0, 0));
        double pa = tubePixels(tau, sa, 40), pb = tubePixels(tau, sb, 600);
        // ближний конец — клубы целиком, у дальнего настоящая доля «шире ленты» больше доли клубов с порогом
        assertTrue(FarFlightView.puffShare(pa / scale, 1, 1) >= 1 && FarFlightView.puffShare(pb, 1, 1) > FarFlightView.puffShare(pb / scale, 1, 1),
                String.format(Locale.ROOT, "сцена: ближний %.1f px, дальний %.1f px", pa, pb));
        SmokeFillTest.Screen s = scaled(List.of(piece(a, sa, b, sb, tau, 77)), v, scale);
        String got = String.format(Locale.ROOT, "квадратов %d, гауссов вуали %d, полос %d", s.quads.size(), SmokePuffs.lastVeils(), s.polygons.size());
        System.out.println("WHOLE " + got);
        assertTrue(!s.quads.isEmpty() && SmokePuffs.lastVeils() == 0 && s.veil == null && s.polygons.isEmpty(), got);
    }

    /**
     * Ревью #466: у отрезка с узким и широким концом (и у узких концов с широкой серединой у глаза) лента шла всей своей
     * геометрией, и через широкую часть ложилась полоса в 163 px — альфа там лишь сходила на нет. Лента — только узкие куски
     * от концов, до места, где трубка на экране шире {@link FarFlightView#PUFFS_FULL}.
     */
    @Test
    void mixedRibbonEndsAreCut() throws ReflectiveOperationException {
        double limit = FarSprites.RIBBON * FarFlightView.PUFFS_FULL * 1.1, tau = 1;
        Vec3 eye = new Vec3(0, 10, 0);
        FarView v = SmokeFillTest.view(eye, new Vector3f(1, 0, 0));
        Object[][] cases = {
                // узкий дальний конец — широкий ближний
                {new Vec3(200, 10, 250), 0.2, new Vec3(60, 10, -10), 1.5, 1},
                // узкие концы под 45°, широкая середина ближе к глазу
                {new Vec3(100, 10, -100), 0.28, new Vec3(100, 10, 100), 0.28, 2}};
        StringBuilder all = new StringBuilder();
        boolean ok = true;
        for (Object[] c : cases) {
            Vec3 a = (Vec3) c[0], b = (Vec3) c[2];
            SmokeFillTest.Screen s = scaled(List.of(piece(a, (double) c[1], b, (double) c[3], tau, 91)), v, 50);
            double widest = 0;
            int strips = 0;
            for (double[] p : s.polygons) {
                if (SmokeFillTest.isFlat(p) || p[12] < 0.002) continue;
                strips++;
                widest = Math.max(widest, Math.max(pixels(v, p, 0), pixels(v, p, 1)));
            }
            String got = String.format(Locale.ROOT, "%s–%s: полос %d (ждали %d), самая широкая %.1f px (предел %.1f)", a, b, strips, (int) c[4], widest,
                    limit);
            System.out.println("MIXED " + got);
            all.append(got).append('\n');
            ok &= strips == (int) c[4] && widest <= limit;
        }
        assertTrue(ok, all.toString());
    }

    /**
     * Ревью #466: вуаль берёт толщу только по лучам своих вершин, и трубка уже клетки, ушедшая в вуаль целиком (мест нет, для
     * ленты широка), пропадала между ними: сдвиг на 31 px менял непрозрачность её середины с 0,61 до нуля. Вещество трубки в
     * вуали — гауссом не уже полушага сетки ({@link SmokePuffs#VEIL_FILTER}), закрывающим ту же долю экрана: непрозрачность
     * вуали по экрану в сумме — как у самой трубки (±25 %) и при сдвиге на клетку держится (±10 %), середина не пропадает (не
     * меньше половины своего наибольшего).
     */
    @Test
    void veilKeepsNarrowMatterAcrossItsCells() throws ReflectiveOperationException {
        Vec3 eye = new Vec3(0, 10, 0);
        FarView v = SmokeFillTest.view(eye, new Vector3f(1, 0, 0));
        double d = 100, sigma = 1.1, tau = 0.5, length = 60, cell = 2 * SmokePuffs.Veil.extent(v) / SmokePuffs.VEIL_CELLS * d, step = 0.002;
        Vector3f l = v.left(), up = v.up();
        // сама трубка: ∫(1 − e^(−τ(x))) dx поперёк на её длину, в тангенсах экрана (глубина d)
        double across = 0;
        for (double x = -6 * sigma; x <= 6 * sigma; x += sigma / 50) across += -Math.expm1(-tau * Math.exp(-0.5 * x * x / (sigma * sigma))) * sigma / 50;
        double truth = across * length / (d * d), lo = Double.POSITIVE_INFINITY, hi = 0, mlo = Double.POSITIVE_INFINITY, mhi = 0;
        StringBuilder all = new StringBuilder(String.format(Locale.ROOT, "трубка %.1f px, клетка %.1f м, вещество %.5f:", tubePixels(tau, sigma, d), cell,
                truth));
        for (int k = 0; k <= 8; k++) {
            double z = cell * k / 8;
            SmokeFillTest.Screen s = scaled(List.of(piece(new Vec3(d, 0, z), sigma, new Vec3(d, length, z), sigma, tau, 13)), v, 50);
            double sum = 0, m = 0;
            if (s.veil != null) {
                for (double u = -SmokeFillTest.W; u <= SmokeFillTest.W; u += step)
                    for (double w = -SmokeFillTest.H; w <= SmokeFillTest.H; w += step) sum += (1 - s.veil.through(u, w)) * step * step;
                m = 1 - s.veil.through((d * l.x + 20 * l.y + z * l.z) / d, (d * up.x + 20 * up.y + z * up.z) / d);
            }
            lo = Math.min(lo, sum);
            hi = Math.max(hi, sum);
            mlo = Math.min(mlo, m);
            mhi = Math.max(mhi, m);
            all.append(String.format(Locale.ROOT, " %.5f (середина %.4f)", sum, m));
        }
        System.out.println("VEIL CELLS " + all);
        assertTrue(lo >= 0.9 * hi && Math.abs(hi / truth - 1) <= 0.25 && mlo >= 0.5 * mhi && mhi > 0, all.toString());
    }

    /**
     * Угловая ширина полосы ленты p (вершины от глаза: 0, 1 — у a, 2, 3 — у b) у её конца end (0 — a, 1 — b), пикселей кадра
     * проверок: поперечник конца поперёк луча к нему на его дальность — та же мера, что у порога ленты
     * ({@link FarFlightView#puffShare}: ширина / расстояние). Пиксели плоскости экрана у края кадра крупнее угла (у реза в
     * 243 м на глубине 163 м — в 1,5 раза), и узкая лента там шире в пикселях — это перспектива кадра, а не лента.
     */
    private static double pixels(FarView v, double[] p, int end) {
        int i = end == 0 ? 0 : 3, j = end == 0 ? 1 : 2;
        double mx = 0.5 * (p[3 * i] + p[3 * j]), my = 0.5 * (p[3 * i + 1] + p[3 * j + 1]), mz = 0.5 * (p[3 * i + 2] + p[3 * j + 2]);
        double d = Math.sqrt(mx * mx + my * my + mz * mz), rx = mx / d, ry = my / d, rz = mz / d;
        double ox = p[3 * j] - p[3 * i], oy = p[3 * j + 1] - p[3 * i + 1], oz = p[3 * j + 2] - p[3 * i + 2], along = ox * rx + oy * ry + oz * rz;
        return Math.sqrt(Math.max(0, ox * ox + oy * oy + oz * oz - along * along)) / (d * v.pixel());
    }

    /**
     * Ревью #460, круг 2: площадь лежащей пыли план брал прямоугольником на глубине середины, а у квадрата на горизонтали под
     * углом ближний край на экране в разы шире — 300 плоских гауссов (σh 20 м, σv 0,2 м, толща 0,01) в 40 м, глаз на 5 м:
     * план 15,4, квадраты 36,7 экрана. Теперь по четырём вершинам: нарисованное — не больше плана, план — не больше бюджета.
     */
    @Test
    void lyingDustIsMeasuredByItsCorners() {
        double sh = 20, sv = 0.2, ext = 0.01 * 2 * Math.PI * sh * sh;
        List<SmokeGrid.Source> layer = new ArrayList<>();
        for (int i = 0; i < 300; i++) layer.add(blob(0, 0, 40, sh, sv, ext, 400 + i));
        SmokeFillTest.Screen s = frame(layer, 0, SmokeFillTest.view(new Vec3(0, 5, 0), new Vector3f(0, 0, 1)), SmokePuffs.FILL_BUDGET);
        String got = String.format(Locale.ROOT, "заливка %.2f (план %.2f), сверх плана до %.4f, лежащих %d", s.fill(), SmokePuffs.lastFill(), worstOverPlan,
                s.polygons.size());
        System.out.println("FLAT " + got);
        SmokePuffs.resetBudget();
        assertTrue(worstOverPlan <= 1e-9 && s.fill() <= SmokePuffs.FILL_BUDGET + 1e-9 && !s.polygons.isEmpty(), got);
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
