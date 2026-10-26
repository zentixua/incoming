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
 * Price of smoke raid from 100 impacts (scene bench 25.10, scene “100”: filling 41/104/175 screens outside/from the edge/inside with
 *  budget 4, GPU 13,5/39,4/55,5 ms vs 2,9/5,8/6,4 for 3.4.1): 100 Shahed by Vogel's sunflower in the circle 120 m,
 *  through 1,5 s, windows - through 35 s after the last one; fuel in the crater burns 150 s - 70 trail fires; wind 4 m/s,
 * class D. Clouds - model ({@link CloudModel}: cap, stem tube, dust near the ground), trail - {@link FireSmoke#lay}; frame
 *  lays out the same layer receiver as in the game ({@link SmokePuffs}), three cameras of the stand. Half of the frame (one billows per
 *  gauss and tube place) - 18/70/153 screen, up to 10 times higher than the budget (before the budget did not apply here at all).
 */
class SmokeRaidTest {
    /**
     * Fuel fire in a Shahed crater (as in {@code PlumeContinuityTest.POOL}): burns for 150 s after impact.
     */
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
     * The Shahed strike on the target target - the same billows as the event {@code FarBlasts} ({@code Event.emit}): the model cloud (cap,
     *  leg like a tube, dust near the ground), the dust of a fragmented target in its appearance - in loose ejection frames, in a coherent one - crushing
     *  (its mass is a fraction small substances: near the soil - dust that went into the air, near the stone - 10% destroyed), a curtain of emission from
     *  soil ({@code FarBlasts.curtain}).
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
     * Stand attack on the ground: 100 impacts in a circle 120 m, the last one - for 35 s to frame; every third - into stone (crushed
     *  dust of the coherent), the rest - into the ground (veil of ejection); those whose fuel is still burning - trail.
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

    /** Stand cameras (tools/stand/scenes.json): outside - 200 m, 30 m above the ground; from the edge - 120 m, 10 m; inside - in the middle, 4 m. */
    static final Vec3[] EYES = {new Vec3(-200, 30, 0), new Vec3(0, 10, -120), new Vec3(0, 4, 0)};
    static final Vec3[] AIMS = {new Vec3(0, 15, 0), new Vec3(0, 10, 0), new Vec3(100, 20, 0)};
    static final String[] NAMES = {"outside", "from the edge", "inside"};

    static FarView view(int camera) {
        Vec3 e = EYES[camera], a = AIMS[camera];
        return SmokeFillTest.view(e, new Vector3f((float) (a.x - e.x), (float) (a.y - e.y), (float) (a.z - e.z)));
    }

    /** For frames {@link #frame} the greatest number of places of tubes and billows of clouds in the plan and filling above the plan (from the first frame). */
    static int maxPlaces, maxBlobs;
    static double worstOverPlan;

    /** Raid frame with budget budget: several frames in a row (budget billows tubes and fusion level - according to the past). */
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
     * Passing the plaque substance itself along the beam (u, v) of the screen: e^(−Στ) through each gauss of clouds ({@link SmokePuffs#sightDepth})
     *  and through the tubes of legs and trail - by gauss through σ/2 along the axis (their sum - the same tube); the fire body has a share of smoke, like
     *  for the layer receiver. Without squares: both frames are checked against it - full and in budget.
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

    /** The substance of the sources by gauss from the eye: middle, σh, σv, weakening of the entire mass, fraction of smoke (at the body of fire). */
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
            // segment (curtain jet) - Gaussians through σ/2 along it, like a tube
            double len = 2 * Math.sqrt(ax * ax + ay * ay + az * az), ext = CloudModel.SOOT_K * soot + SmokeGrid.dustK(dustR, dustG, dustB) * dust;
            int n = (int) Math.max(1, Math.ceil(len / (0.5 * Math.min(sh, sv))));
            for (int j = 0; j < n; j++) {
                double f = n == 1 ? 0 : (j + 0.5) / n * 2 - 1;
                gaussians.add(new double[]{x + ax * f - eye.x, y + ay * f - eye.y, z + az * f - eye.z, sh, sv, ext / n,
                        SmokePuffs.smokeShare(glow, kelvin, view.skyLuminance())});
            }
        }

        /** Tube - Gaussians through σ/2 along the axis: their sum is the same tube (section √(σh·σv), like that of the layer receiver). */
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
     * A raid of 100 impacts on the ground (with a curtain of ejection and crushed stone dust) from three cameras of the stand. Without a budget, half the frame
     *  from the edge and from the inside above the budget (from the outside with a step of places according to the budget - 14,9 screen: it fits like that); with a budget, the squares fill no more than the plan, the plan - no more than the budget (in each frame from the first), the floor -
     * , no more than the budget, the Gaussian veil - no more than {@link SmokePuffs#VEIL_MAX}, the places of tubes and billows of clouds in the plan - no
     *  more than {@link SmokePuffs#PLACES} and {@link SmokePuffs#BLOBS} in each frame from the first. Passing through the 18 parts of the frame against the very substance of the model ({@link #truth}) is no further than that of the
     *  full frame, more than the noise details of the billows (0,08, {@code SmokeFillTest.clusterDetailNoise}).
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
                    "%s: without budget %d squares, %.1f screens (floor %.1f); with budget %d, %.1f (floor %.1f, before merger %.1f, level %d, veil %d of Gaussians, heaps %d); worst part against substance: full %.3f, in budget %.3f%n substance %s%n full %s%n budget %s",
                    NAMES[c], free.quads.size(), free.fill(), freeFloor, budget.quads.size(), budget.fill(), floor, raw, level, veils, clusters, worstFree,
                    worst, row(real), row(SmokeFillTest.blocks(free, prof)), row(SmokeFillTest.blocks(budget, prof)));
            System.out.println("RAID " + got);
            all.append(got).append('\n');
            String bounds = String.format(Locale.ROOT, "  filling in excess of plan %.4f, plan %.2f; tube places up to %d, billows clouds up to %d", over,
                    SmokePuffs.lastFill(), places, blobs);
            System.out.println("RAID " + bounds);
            all.append(bounds).append('\n');
            ok &= c == 0 || raw > SmokePuffs.FILL_BUDGET;
            ok &= over <= 1e-9 && SmokePuffs.lastFill() <= SmokePuffs.FILL_BUDGET + 1e-9 && floor <= SmokePuffs.FILL_BUDGET + 1e-9;
            ok &= veils <= SmokePuffs.VEIL_MAX && places <= SmokePuffs.PLACES && blobs <= SmokePuffs.BLOBS;
            ok &= worst <= worstFree + 0.08;
        }
        SmokePuffs.resetBudget();
        assertTrue(ok, all.toString());
    }

    /**
     * The work of the frame plan does not grow with the raid: 400 impacts on the ground (clouds without fires) - billows of clouds in the plan are no more than
     * {@link SmokePuffs#BLOBS} (over - merge to the plan and are selected by contribution) in each frame from the first, filling - in
     *  budget.
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
        String got = String.format(Locale.ROOT, "billows of clouds in plan %d, heaps %d, fill %.1f (floor %.1f, before merging %.1f)", SmokePuffs.lastBlobs(),
                SmokePuffs.lastItems(), s.fill(), SmokePuffs.lastFloor(), SmokePuffs.lastRaw());
        System.out.println("RAID 400: " + got);
        SmokePuffs.resetBudget();
        assertTrue(maxBlobs <= SmokePuffs.BLOBS && maxPlaces <= SmokePuffs.PLACES && worstOverPlan <= 1e-9 && s.fill() <= SmokePuffs.FILL_BUDGET + 1e-9,
                got + ", billows of clouds in terms of up " + maxBlobs + ", places to " + maxPlaces);
    }

    /** Single gauss source (σh, σv) with attenuation ext in (x, y, z), soot. */
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
     * Review #460: the plan considered a fractional heap, and ⌈heaps⌉ of squares with a spread of size and space are drawn - 12 overlapping
     * gaussians (σ 6,4 m, thickness of the middle 3) in 30 m: plan 8, screen squares 11,3. Now the squares are filled no more than the plan,
     * plan - no more than the budget, with budgets 4, 8 and the present.
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
            String got = String.format(Locale.ROOT, "budget %.0f: squares %.3f, plan %.3f, share of heap %.2f", budget, s.fill(), SmokePuffs.lastFill(),
                    SmokePuffs.lastShare());
            System.out.println("PLAN " + got);
            assertTrue(worstOverPlan <= 1e-9 && SmokePuffs.lastFill() <= budget + 1e-9, got);
        }
        SmokePuffs.resetBudget();
    }

    /**
     * Review #460: the limit of billows clouds merged in cells endlessly - 2001 gauss σ 1 m with a grid through 40 m came out as gauss σ 20 m,
     *  smoke in empty gaps. Now the merger is no further than two edges, the remainder is selected by contribution: billows in terms of no more than
     * {@link SmokePuffs#BLOBS}, and not a single square is larger than the edge of its Gaussian (with growth to a pixel and spread).
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
        // the edge of a thick Gaussian - up to 4σ, for a single billows - without scatter; square on the screen - no more than (edge)/(depth) with a pixel floor
        double most = Math.max(4 * sigma / depthMin, 2 * SmokeFillTest.PIXEL);
        String got = String.format(Locale.ROOT, "billows of clouds in plan up to %d, squares %d, largest half-size %.4f (edge - up to %.4f)", maxBlobs,
                s.quads.size(), biggest, most);
        System.out.println("THIN " + got);
        SmokePuffs.resetBudget();
        assertTrue(maxBlobs <= SmokePuffs.BLOBS && !s.quads.isEmpty() && biggest <= most, got);
    }

    /**
     * Review #460: near the merge budget boundary, merging switched on and off: floor 8.28↔7.80 as the camera moved,
     * level 1↔0, clusters 1↔60. Previous frame level stays until the floor falls below 0.8 of budget, then decreases.
     */
    @Test
    void mergeLevelHoldsAtTheBudgetBorder() {
        // a row of pale billows of different sources across the view: they do not hide each other, but lie on top of each other - merge
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
            // above budget: merge; just below (floor at 0.95 of budget), hold level; below 0.8, decrease
            SmokePuffs.budget = raw / 1.3;
            step(row, now, v, s);
            int over = SmokePuffs.lastLevel();
            SmokePuffs.budget = raw / 0.95;
            step(row, now, v, s);
            int near = SmokePuffs.lastLevel();
            SmokePuffs.budget = raw / 0.7;
            step(row, now, v, s);
            int under = SmokePuffs.lastLevel();
            String got = "floor " + raw + ": level above budget " + over + ", on budget " + near + ", under 0,8 - " + under;
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
     * Review #460, circle 2: sections in excess of the frame locations were tape past the budget - 300 standing tubes (100 m, σ 1 m) in 30 m
     *  were poured 29,5 screen for the budget 16, 1000 - 75. The tape is also a fill: in the plan and budget, if it doesn’t fit, it’s not drawn.
     *  In each frame from the first, the squares and tapes are filled in no more than the plan, the plan - no more than the budget.
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
            String got = String.format(Locale.ROOT, "%d tubes: filling %.2f (plan %.2f), over plan up to %.4f, places up to %d, strips of tape %d", n, s.fill(),
                    SmokePuffs.lastFill(), worstOverPlan, maxPlaces, s.polygons.size());
            System.out.println("RIBBONS " + got);
            assertTrue(worstOverPlan <= 1e-9 && s.fill() <= SmokePuffs.FILL_BUDGET + 1e-9 && maxPlaces <= SmokePuffs.PLACES, got);
        }
        SmokePuffs.resetBudget();
    }

    /**
     * Ribbon - a type of tube narrower {@link FarFlightView#PUFFS_FULL} px: a strip with straight edges and one color across. In the raid
     *  of the trail stand 70 fires were asked for ~8000 places at 3000, the width threshold (the previous multiplier {@code puffScale}) grew to the 40-th
     *  frame in 28–41 times, and trail width up to 90–180 px went in ribbons: stripes up to 212/491/701 px outside/from the edge/inside — light
     *  straight stripes in the frames of the 25.10 stand (oblique at the edges). Under no seat budget is the strip wider than the tape of a narrow tube:
     *  the budget increases the billows step, not the width threshold.
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
            String got = String.format(Locale.ROOT, "%s: bands %d, widest %.1f px (limit %.1f)", NAMES[c], strips, widest, limit);
            System.out.println("RIBBON WIDTH " + got);
            all.append(got).append('\n');
            ok &= widest <= limit;
        }
        SmokePuffs.resetBudget();
        assertTrue(ok, all.toString());
    }

    /** A tube of one piece from a to b (σ at the ends) with thickness across the axis tau - the source (soot per meter - thicker). */
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

    /** A frame of tubes with a step of places increased by stride times (there are few places in the frame - like in a stand raid). */
    private static SmokeFillTest.Screen strided(List<SmokeGrid.Source> sources, FarView v, double stride) throws ReflectiveOperationException {
        java.lang.reflect.Field field = FarFlightView.class.getDeclaredField("stride");
        field.setAccessible(true);
        SmokeFillTest.Screen s = new SmokeFillTest.Screen(v);
        SmokePuffs.resetBudget();
        FarFlightView.reset();
        try {
            for (int f = 0; f < 3; f++) {
                field.setDouble(null, stride);
                step(sources, 0, v, s);
            }
        } finally {
            FarFlightView.reset();
            SmokePuffs.resetBudget();
        }
        return s;
    }

    /** The width of the tube is thicker than tau and σ on the screen in d blocks from the eye, frame pixels of checks. */
    private static double tubePixels(double tau, double sigma, double d) {
        return ua.zentix.incoming.client.fx.plume.TrailModel.width(tau, sigma) / (d * SmokeFillTest.PIXEL);
    }

    /**
     * Review #466: for a segment with narrow and wide ends (and narrow ends with a wide middle near the eye), the
     * ribbon used its full geometry and laid a 163 px strip through the wide part; alpha merely faded there. Ribbon
     * uses only narrow portions from the ends up to where the screen-space tube exceeds {@link FarFlightView#PUFFS_FULL},
     * with any point spacing: no strip through the wide middle.
     */
    @Test
    void mixedRibbonEndsAreCut() throws ReflectiveOperationException {
        double limit = FarSprites.RIBBON * FarFlightView.PUFFS_FULL * 1.1, tau = 1;
        Vec3 eye = new Vec3(0, 10, 0);
        FarView v = SmokeFillTest.view(eye, new Vector3f(1, 0, 0));
        Object[][] cases = {
                // narrow far end - wide near
                {new Vec3(200, 10, 250), 0.2, new Vec3(60, 10, -10), 1.5, false},
                // narrow ends under 45°, wide middle closer to the eye
                {new Vec3(100, 10, -100), 0.28, new Vec3(100, 10, 100), 0.28, true}};
        StringBuilder all = new StringBuilder();
        boolean ok = true;
        for (Object[] c : cases) {
            Vec3 a = (Vec3) c[0], b = (Vec3) c[2];
            SmokeFillTest.Screen s = strided(List.of(piece(a, (double) c[1], b, (double) c[3], tau, 91)), v, 50);
            double widest = 0;
            int strips = 0;
            for (double[] p : s.polygons) {
                if (SmokeFillTest.isFlat(p) || p[12] < 0.002) continue;
                strips++;
                widest = Math.max(widest, Math.max(pixels(v, p, 0), pixels(v, p, 1)));
            }
            double middle = ribbonAt(s, a, b, 0.5);
            String got = String.format(Locale.ROOT, "%s–%s: stripes %d, widest %.1f px (limit %.1f), in the middle %.4f", a, b, strips, widest, limit,
                    middle);
            System.out.println("MIXED " + got);
            all.append(got).append('\n');
            ok &= strips > 0 && widest <= limit && (middle == 0 || !(boolean) c[4]);
        }
        assertTrue(ok, all.toString());
    }

    /**
     * Review #466, circle 2: at narrow ends with a wide middle, one strip at the ends held the middle to the threshold of the tape and dropped
     *  it beyond the threshold: with the width of the middle 4,79999952 → 4,80000048 px it opacity - 0,392 → 0,010, in the quarter segment -
     * 0,389 → 0,197; movement flickered at the threshold. The point inside the segment closest to the eye is the top of the tape with its share (in width
     *  there): the share of the tape in the middle is 1 - the share of billows there ({@link FarFlightView#puffShare}, ±0,001), and throughout the entire passage of the middle
     *  from 2 to 10 px step 0,005 px shares of the tape in the middle and in the quarters and the share of substance in billows change no more than 0,01
     *  per step (the tape melts in width no steeper than 0,63 per pixel), at the threshold ±1e-6 px - less than 0,001.
     */
    @Test
    void ribbonShareIsContinuous() throws ReflectiveOperationException {
        FarView v = SmokeFillTest.view(new Vec3(0, 10, 0), new Vector3f(1, 0, 0));
        double tau = 1, d = 100;
        // narrow ends at 45°, the middle is the point of the segment closest to the eye
        Vec3 a = new Vec3(d, 10, -d), b = new Vec3(d, 10, d);
        double length = a.distanceTo(b), end = Math.sqrt(2) * d;
        double[] half = new double[2];
        java.util.List<Double> widths = new ArrayList<>();
        for (double w = 2; w <= 10 + 1e-9; w += 0.005) widths.add(w);
        widths.add(FarFlightView.PUFFS_FULL - 1e-6);
        widths.add(FarFlightView.PUFFS_FULL + 1e-6);
        double[] prev = null, below = null;
        double worst = 0, atLimit = 0, sum = 0;
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < widths.size(); i++) {
            double w = widths.get(i), sigma = w / tubePixels(tau, 1, d);
            SmokeFillTest.Screen s = strided(List.of(piece(a, sigma, b, sigma, tau, 5)), v, 1);
            // shares: the tape in the middle and in the quarter - its opacity against the tube (at the tops of the tape the opacity of the tube is along
            //  straight between the ends, like {@code FarFlightView.cut}), billows - the substance of the places against the substance of the segment
            double width = ua.zentix.incoming.client.fx.plume.TrailModel.width(tau, sigma);
            FarFlightView.ribbon(width, end, v.pixel(), half);
            double alpha = -Math.expm1(-tau) * half[1] * v.air(end, 0);
            double[] seen = {ribbonAt(s, a, b, 0.5) / alpha, ribbonAt(s, a, b, 0.25) / alpha,
                    SmokePuffs.lastPlaceMatter() / (tau * Math.sqrt(2 * Math.PI) * sigma * length)};
            if (i < widths.size() - 2) {
                if (prev != null)
                    for (int k = 0; k < 3; k++) worst = Math.max(worst, Math.abs(seen[k] - prev[k]));
                prev = seen;
                sum = Math.max(sum, Math.abs(seen[0] + FarFlightView.puffShare(width, d, v.pixel()) - 1));
                if (Math.abs(w - 2) < 1e-9 || Math.abs(w - 4.8) < 0.003 || Math.abs(w - 10) < 1e-9)
                    all.append(String.format(Locale.ROOT, " %.2f px: %.3f / %.3f / %.3f;", w, seen[0], seen[1], seen[2]));
            } else if (below == null) below = seen;
            else for (int k = 0; k < 3; k++) atLimit = Math.max(atLimit, Math.abs(seen[k] - below[k]));
        }
        String got = String.format(Locale.ROOT, "tape in the middle / in the quarter / billows:%s largest step %.4f, at the threshold ±1e-6 px - %.6f, tape + billows in the middle - 1 ± %.5f",
                all, worst, atLimit, sum);
        System.out.println("CONTINUOUS " + got);
        assertTrue(worst <= 0.01 && atLimit <= 0.001 && sum <= 0.001, got);
    }

    /**
     * Opacity of frame strips s at point u of segment a–b (world; eye - in (0, 10, 0)): stripes through the axis of which it passes
     *  (at the common vertex two stripes - one, the one that starts from it).
     */
    private static double ribbonAt(SmokeFillTest.Screen s, Vec3 a, Vec3 b, double u) {
        double px = a.x + (b.x - a.x) * u, py = a.y + (b.y - a.y) * u - 10, pz = a.z + (b.z - a.z) * u, ribbon = 0;
        for (double[] p : s.polygons) {
            if (SmokeFillTest.isFlat(p) || p.length < 15) continue;
            double ax = 0.5 * (p[0] + p[3]), ay = 0.5 * (p[1] + p[4]), az = 0.5 * (p[2] + p[5]);
            double bx = 0.5 * (p[6] + p[9]), by = 0.5 * (p[7] + p[10]), bz = 0.5 * (p[8] + p[11]);
            double tx = bx - ax, ty = by - ay, tz = bz - az, len2 = tx * tx + ty * ty + tz * tz;
            double t = ((px - ax) * tx + (py - ay) * ty + (pz - az) * tz) / len2;
            if (t < -1e-6 || t > 1 - 1e-6) continue;
            double ex = ax + tx * t - px, ey = ay + ty * t - py, ez = az + tz * t - pz;
            if (ex * ex + ey * ey + ez * ez > 1e-6 * len2) continue;
            ribbon = 1 - (1 - ribbon) * (1 - (p[13] + (p[14] - p[13]) * Math.clamp(t, 0, 1)));
        }
        return ribbon;
    }

    /**
     * The budget of the places increases the pitch of the billows tube, and there are fewer places per segment, but the substance of the segment is the same: each stands for its own,
     * , longer piece of the axle. Tube σ 1 m, τ 1, 40 m in 30 m from the eye (entire billows): in steps ×1–128 substance of places - then same
     * (±0,1%) and is equal to the substance of the segment, the places are less and less, down to one.
     */
    @Test
    void strideKeepsTheMatter() throws ReflectiveOperationException {
        FarView v = SmokeFillTest.view(new Vec3(0, 10, 0), new Vector3f(1, 0, 0));
        double tau = 1, sigma = 1, length = 40, truth = tau * Math.sqrt(2 * Math.PI) * sigma * length;
        SmokeGrid.Source tube = piece(new Vec3(30, 10, -length / 2), sigma, new Vec3(30, 10, length / 2), sigma, tau, 3);
        StringBuilder all = new StringBuilder(String.format(Locale.ROOT, "%.3f cut material:", truth));
        boolean ok = true;
        int was = Integer.MAX_VALUE;
        for (double stride : new double[]{1, 1.5, 2, 3, 5, 8, 128}) {
            strided(List.of(tube), v, stride);
            double matter = SmokePuffs.lastPlaceMatter();
            int places = SmokePuffs.lastPlaces();
            all.append(String.format(Locale.ROOT, " ×%.1f - %d seats, %.3f;", stride, places, matter));
            ok &= Math.abs(matter / truth - 1) <= 0.001 && places <= was && places >= 1;
            was = places;
        }
        ok &= was == 1;
        System.out.println("STRIDE " + all);
        assertTrue(ok, all.toString());
    }

    /**
     * Places of tubes - no more than {@link SmokePuffs#PLACES} in each frame of the stand raid, and in the first (the step is still according to the law), and from
     *  of the second or third frame there are enough places for all segments in the frame: the step increases immediately as many times as the number of places requested
     * more (×1,1 per frame - twelve frames of part of the segments without bills). The shares of billows and tapes are the same - there are no tapes wider than
     * , and there is no narrow tube ({@link #ribbonsAreOnlyNarrowTubes}).
     */
    @Test
    void placesHoldFromTheFirstFrame() {
        double now = 1000;
        List<SmokeGrid.Source> raid = raid(now);
        StringBuilder all = new StringBuilder();
        boolean ok = true;
        for (int c = 0; c < 3; c++) {
            FarView v = view(c);
            SmokeFillTest.Screen s = new SmokeFillTest.Screen(v);
            SmokePuffs.resetBudget();
            FarFlightView.reset();
            StringBuilder row = new StringBuilder(NAMES[c] + ": places / denied -");
            int settled = -1;
            for (int frame = 0; frame < 12; frame++) {
                s.clear();
                FarFlightView.budget();
                for (SmokeGrid.Source b : raid) b.emit(now, SmokePuffs.sink(v, s, b, now));
                SmokePuffs.flush();
                int places = SmokePuffs.lastPlaces(), refused = SmokePuffs.lastRefused();
                row.append(String.format(Locale.ROOT, " %d/%d", places, refused));
                ok &= places <= SmokePuffs.PLACES;
                if (refused == 0 && settled < 0) settled = frame;
                if (frame >= 3) ok &= refused == 0;
            }
            row.append("; no failures from the frame ").append(settled);
            System.out.println("FIRST " + row);
            all.append(row).append('\n');
        }
        SmokePuffs.resetBudget();
        FarFlightView.reset();
        assertTrue(ok, all.toString());
    }

    /**
     * Angular width of the tape strip p (vertices from the eye: 0, 1 - for a, 2, 3 - for b) at its end end (0 - a, 1 - b), frame pixels
     *  checks: the diameter of the end across the beam to it at its range is the same measure as at the threshold of the tape
     *  ({@link FarFlightView#puffShare}: width / distance). The pixels of the screen plane at the edge of the frame are larger than the corner (at the cut at
     * 243 m at a depth of 163 m - 1,5 times), and the narrow tape there is wider in pixels - this is the perspective of the frame, not the tape.
     */
    private static double pixels(FarView v, double[] p, int end) {
        int i = end == 0 ? 0 : 3, j = end == 0 ? 1 : 2;
        double mx = 0.5 * (p[3 * i] + p[3 * j]), my = 0.5 * (p[3 * i + 1] + p[3 * j + 1]), mz = 0.5 * (p[3 * i + 2] + p[3 * j + 2]);
        double d = Math.sqrt(mx * mx + my * my + mz * mz), rx = mx / d, ry = my / d, rz = mz / d;
        double ox = p[3 * j] - p[3 * i], oy = p[3 * j + 1] - p[3 * i + 1], oz = p[3 * j + 2] - p[3 * i + 2], along = ox * rx + oy * ry + oz * rz;
        return Math.sqrt(Math.max(0, ox * ox + oy * oy + oz * oz - along * along)) / (d * v.pixel());
    }

    /**
     * Review #460, circle 2: the plan took the area of the lying dust with a rectangle at the depth of the middle, and for a square on the horizontal at a
     *  angle, the near edge on the screen is many times wider - 300 flat Gaussians (σh 20 m, σv 0,2 m, thickness 0,01) in 40 m, eye on 5 m:
     * plan 15,4, squares 36,7 screen. Now for the four peaks: what is drawn is no more than the plan, the plan is no more than the budget.
     */
    @Test
    void lyingDustIsMeasuredByItsCorners() {
        double sh = 20, sv = 0.2, ext = 0.01 * 2 * Math.PI * sh * sh;
        List<SmokeGrid.Source> layer = new ArrayList<>();
        for (int i = 0; i < 300; i++) layer.add(blob(0, 0, 40, sh, sv, ext, 400 + i));
        SmokeFillTest.Screen s = frame(layer, 0, SmokeFillTest.view(new Vec3(0, 5, 0), new Vector3f(0, 0, 1)), SmokePuffs.FILL_BUDGET);
        String got = String.format(Locale.ROOT, "filling %.2f (plan %.2f), above the plan up to %.4f, lying %d", s.fill(), SmokePuffs.lastFill(), worstOverPlan,
                s.polygons.size());
        System.out.println("FLAT " + got);
        SmokePuffs.resetBudget();
        assertTrue(worstOverPlan <= 1e-9 && s.fill() <= SmokePuffs.FILL_BUDGET + 1e-9 && !s.polygons.isEmpty(), got);
    }

    /** Frame parts in rows from top to bottom. */
    static String row(double[] parts) {
        StringBuilder b = new StringBuilder();
        for (int j = 2; j >= 0; j--) {
            b.append(j == 2 ? "[" : " / ");
            for (int i = 0; i < 6; i++) b.append(String.format(Locale.ROOT, i == 0 ? "%.2f" : " %.2f", parts[i * 3 + j]));
        }
        return b.append(']').toString();
    }
}
