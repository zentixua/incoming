package ua.zentix.incoming.client.fx.smoke;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.BreachModel;
import ua.zentix.incoming.warhead.GroundMaterial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Finite aerosol rays, before shader scattering/noise. No client, terrain occlusion or GPU. */
class CloudDustTransportTest {
    // Day C, prescribed world wind 12 m/s at 1000 m, unchanged power law.
    private static final CloudModel.Air AIR = new CloudModel.Air(CloudModel.N_STANDARD,
            12 * Math.pow(0.01, 0.1), 0, 0.1, 2, 0.5);
    private static final double[] TIMES = {1, 3, 6, 10, 30, 60};

    @Test
    void eyeRaysDoNotAcquireAColdCrushedSheet() {
        for (WeaponType weapon : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE}) {
            for (boolean roof : new boolean[]{false, true}) {
                for (double fines : new double[]{0.002, 0.005, 0.0125}) {
                    CloudModel model = scene(weapon, roof, fines);
                    for (double t : TIMES) {
                        double shorter = 0;
                        for (double distance : new double[]{20, 40, 60}) {
                            double tau = ray(model, roof ? 8 : 0, t, distance, 64);
                            String where = weapon + ", roof " + roof + ", fines " + fines + ", t " + t + ", D " + distance;
                            assertTrue(Double.isFinite(tau) && tau >= 0, where);
                            assertTrue(tau + 1e-12 >= shorter, "nested segments: " + where);
                            shorter = tau;
                            // Regression bound from the task, not a coefficient in the model. An upper bound only:
                            // it does not certify the observed brightness, a lower haze bound, or a minute of local haze.
                            if (distance == 40 && (t == 6 || t == 10)) assertTrue(tau < 2, where + ": tau " + tau);
                        }
                    }
                    assertTrue(model.fallout.airborne(60) > 0.15 * model.dust,
                            "the 20% fine bin cannot settle out in a minute: departure from the ray is advection");
                    assertTrue(model.life > 60);
                    assertEquals(1, model.fade(60), 1e-12);
                    if (fines == 0.005) {
                        var fine = new DustFallout(model.thermal, model.dust, 64, 4);
                        for (double t : TIMES) for (double d : new double[]{20, 40, 60}) {
                            double[] ordinary = {0}, reference = {0};
                            double height = roof ? 8 : 0;
                            model.fallout.blobs(t, (x, y, z, sh, sv, mass, optical) -> ordinary[0] +=
                                    unitRay(x, y + height, z, sh, sv, d) * optical * DustModel.K_MIX);
                            fine.blobs(t, (x, y, z, sh, sv, mass, optical) -> reference[0] +=
                                    unitRay(x, y + height, z, sh, sv, d) * optical * DustModel.K_MIX);
                            assertEquals(reference[0], ordinary[0], Math.max(1e-5, reference[0] * 0.005),
                                    "fallout ray quadrature t " + t + ", D " + d);
                        }
                    }
                }
            }
        }
    }

    @Test
    void coarseParticlesLeaveTheGasThenReachTheGround() {
        CloudModel model = scene(WeaponType.MISSILE, false, 0.005);
        double initialCoarse = model.dust * DustModel.binShare(4);
        assertTrue(model.fallout.airborneBin(4, 30) > model.dust * DustModel.binShare(4)
                * Math.exp(-DustModel.binSettle(4) * model.thermal.settling(30)), "fallout remains airborne after exiting the gas");
        assertTrue(model.fallout.airborneBin(4, 3600) < initialCoarse * 0.01, "400 micrometres cannot float for an hour");
        for (double t : TIMES) {
            double total = model.fallout.airborne(t);
            assertTrue(total >= 0 && total <= model.dust * (1 + 1e-9), "mass at " + t);
            DustFallout finer = new DustFallout(model.thermal, model.dust, 32, 4);
            assertEquals(finer.airborne(t), total, model.dust * 0.005, "mass quadrature at " + t);
        }
    }

    @Test
    void overloadedCarrierSpreadsOnItsSupport() {
        Thermal dense = new Thermal(0, 0, 5, AIR, 0, 80);
        assertTrue(dense.horizontalRadius(3) > dense.horizontalRadius(0));
        assertTrue(dense.height(3) < dense.height(0));
        assertEquals(Math.pow(dense.r0, 3), Math.pow(dense.horizontalRadius(3), 2) * dense.verticalRadius(3), 1e-8);
        double solids = 3 * 80 / (4 * Math.PI * CloudModel.RHO), u = Math.pow(dense.r0, 3);
        double coefficient = 2 * GroundDust.FR * Math.sqrt(2 * CloudModel.G * solids * u / (u + solids));
        assertEquals(coefficient * 3, Math.pow(dense.horizontalRadius(3), 2) - 25, coefficient * 3 * 0.01);
        Thermal heavy = new Thermal(0, 0, 3.36, AIR, 0, 5075);
        double fastestFront = GroundDust.FR * Math.sqrt(2 * CloudModel.G * heavy.r0);
        assertTrue(heavy.horizontalRadius(1) < heavy.r0 + fastestFront,
                "particle inertia bounds the dense acceleration by g even at 26 times the air mass");
    }

    @Test
    void visibleMatterDoesNotExpireAtOneHour() {
        CloudModel.Air night = new CloudModel.Air(ua.zentix.incoming.util.Stability.brunt(ua.zentix.incoming.util.Stability.F),
                0, 0, 0.35, 5, 0.5);
        var source = BlastSource.of(WeaponType.MISSILE, false);
        var target = BlastTarget.of(GroundMaterial.STONE, source.chargeKg(), 0);
        var model = new CloudModel(source, night, target, 0, GroundDust.Ground.uniform(0, 1));
        assertTrue(model.visibilityTau(3600) > CloudModel.SEEN);
        assertTrue(model.life > 3600);
        assertEquals(1, model.fade(3420));
        assertEquals(1, model.fade(3600));
        assertTrue(model.visibilityTau(model.life) <= CloudModel.SEEN * (1 + 1e-9));
    }

    @Test
    void narrowSurvivingFalloutIsNotLostBetweenMassSamples() {
        var night = new CloudModel.Air(ua.zentix.incoming.util.Stability.brunt(ua.zentix.incoming.util.Stability.F),
                0, 0, 0.35, 5, 0.5);
        Thermal gas = new Thermal(0, 0, 3.36, night, 5075, 0);
        DustFallout fallout = new DustFallout(gas, 5075);
        for (double t : new double[]{60, 135.73}) {
            // Independent quadrature in birth TIME, not in cumulative released mass. The old 16 and 32
            // mass midpoints both missed this tail, so comparing those two resolutions alone was insufficient.
            int steps = 65536;
            double reference = 0, v = DustModel.binSettle(0), m0 = 5075 * DustModel.binShare(0);
            for (int j = 0; j < steps; j++) {
                double lo = t * j / steps, hi = t * (j + 1) / steps, birth = 0.5 * (lo + hi), age = t - birth;
                double released = m0 * Math.exp(-v * gas.settling(lo)) * -Math.expm1(-v * (gas.settling(hi) - gas.settling(lo)));
                double b = gas.verticalRadius(birth), h = gas.height(birth), relaxation = v / CloudModel.G;
                double y = h + gas.velocity(birth) * relaxation * -Math.expm1(-age / relaxation) - v * age;
                double q = Math.min(Math.min(1, h / b), Math.max(0, y / b));
                reference += released * q * q;
            }
            double core = m0 * Math.exp(-v * gas.settling(t));
            double free = fallout.airborneBin(0, t) - core;
            assertTrue(reference > 0.001, "a physical surviving tail exists at " + t);
            assertEquals(reference, free, reference * 0.005, "independent birth-time quadrature at " + t);
            var finer = new DustFallout(gas, 5075, 32, 4);
            assertEquals(finer.tauUpper(t), fallout.tauUpper(t), finer.tauUpper(t) * 0.005);
        }
    }

    @Test
    void particleWeightAndInertiaSlowTheCarrierWithoutRemovingItsHeat() {
        double heat = 50 * CloudModel.HE_HEAT, buoyancy = Thermal.buoyancy(heat);
        double g0 = CloudModel.G * (1 - CloudModel.T_AIR / CloudModel.HE_BALL_END), mass = 80;
        Thermal gas = new Thermal(buoyancy, g0, 0.5, AIR);
        Thermal loaded = new Thermal(buoyancy, g0, 0.5, AIR, mass, 0);
        assertEquals(gas.r0, loaded.r0, 1e-12);
        assertEquals(gas.kelvin(0), loaded.kelvin(0), 1e-12);
        double u = Math.pow(gas.r0, 3), solids = 3 * mass / (4 * Math.PI * CloudModel.RHO);
        double acceleration = (u * g0 - CloudModel.G * solids) / (u + solids);
        assertEquals(acceleration, loaded.rise(Thermal.DT) / Thermal.DT, 0.01 * acceleration);
        assertTrue(loaded.height(6) < gas.height(6));
        double previousJ = 0, previousMass = mass, previousOptics = mass;
        for (double t : new double[]{0, 1, 3, 6, 10, 30, 60, 300, 600, 1800}) {
            assertTrue(loaded.settling(t) >= previousJ);
            assertTrue(loaded.airborneDust(t) <= previousMass + 1e-12);
            assertTrue(loaded.opticalDust(t) <= previousOptics + 1e-12);
            previousJ = loaded.settling(t);
            previousMass = loaded.airborneDust(t);
            previousOptics = loaded.opticalDust(t);
        }
    }

    @Test
    void analyticRayMatchesDirectConcentrationIntegralAndStemQuadratureConverges() {
        double sh = 7, sv = 4, x = 25, y = 6, z = 2, distance = 40;
        double numeric = 0;
        int steps = 4000;
        for (int i = 0; i < steps; i++) {
            double at = distance * (i + 0.5) / steps;
            numeric += Math.exp(-0.5 * (Math.pow((at - x) / sh, 2) + Math.pow((1.6 - y) / sv, 2)
                    + Math.pow(z / sh, 2))) / (Math.pow(2 * Math.PI, 1.5) * sh * sh * sv) * distance / steps;
        }
        assertEquals(numeric, unitRay(x, y, z, sh, sv, distance), numeric * 2e-6);
        CloudModel model = scene(WeaponType.DRONE, false, 0.005);
        assertEquals(ray(model, 0, 3, 40, 128), ray(model, 0, 3, 40, 64), 1e-4);
    }

    private static CloudModel scene(WeaponType weapon, boolean roof, double fines) {
        BlastSource source = BlastSource.of(weapon, false);
        double w = source.chargeKg(), h = roof ? 8 : 0;
        BlastTarget target = BlastTarget.of(GroundMaterial.STONE, false, roof ? 1 : 0,
                BreachModel.Material.ROCK.radius(w, BreachModel.UNTAMPED), Vec3.ZERO, new Vec3(0, -1, 0));
        GroundDust.Relief relief = (x, z) -> roof && Math.abs(x) <= 4 && Math.abs(z) <= 4 ? 0 : -h;
        double[][] heights = GroundDust.Ground.filled(0);
        for (int s = 0; s < GroundDust.SECTORS; s++) {
            for (int b = 0; b < GroundDust.BANDS.length; b++) {
                double angle = 2 * Math.PI * (s + 0.5) / GroundDust.SECTORS, r = GroundDust.BANDS[b];
                heights[s][b] = relief.at(r * Math.cos(angle), r * Math.sin(angle));
            }
        }
        var ground = new GroundDust.Ground(GroundDust.Ground.filled(0), GroundDust.Ground.filled(0),
                GroundDust.Ground.filled(1), heights, relief);
        // The nominal case exercises the production enum/constructor, not an injected replacement for its fines.
        CloudModel result = fines == 0.005 ? new CloudModel(source, AIR, target, 0, ground)
                : new CloudModel(source, AIR, target, target.mass(w) * fines, 0.5, 0, ground);
        assertEquals(target.mass(w) * fines, result.dust, 1e-9);
        return result;
    }

    /** Camera (D, 1.6, 0), landmark (0, 1.6, 0), z is crosswind. Mass-normalized Gaussian integral. */
    private static double unitRay(double x, double y, double z, double sh, double sv, double distance) {
        double cdf = 0.5 * (SmokeGrid.erf((distance - x) / (Math.sqrt(2) * sh)) - SmokeGrid.erf(-x / (Math.sqrt(2) * sh)));
        return Math.max(0, cdf) * Math.exp(-0.5 * (Math.pow((1.6 - y) / sv, 2) + z * z / (sh * sh))) / (2 * Math.PI * sh * sv);
    }

    private static double ray(CloudModel model, double impactHeight, double time, double distance, int samples) {
        double[] sum = {0};
        model.blobs(time, new CloudModel.Blobs() {
            public void blob(double x, double y, double z, double sh, double sv, double soot, double dust, double water, double k, double rise) {
                sum[0] += unitRay(x, y + impactHeight, z, sh, sv, distance) * (CloudModel.SOOT_K * soot + DustModel.K_MIX * dust);
            }

            public void tube(double ax, double ay, double az, double sa, double ma, double bx, double by, double bz, double sb, double mb,
                             double k, double rise) {
                double length = Math.sqrt(Math.pow(bx - ax, 2) + Math.pow(by - ay, 2) + Math.pow(bz - az, 2));
                for (int i = 0; i < samples; i++) {
                    double f = (i + 0.5) / samples, s = sa + (sb - sa) * f;
                    blob(ax + (bx - ax) * f, ay + (by - ay) * f, az + (bz - az) * f, s, s, 0,
                            (ma + (mb - ma) * f) * length / samples, 0, k, rise);
                }
            }
        });
        return sum[0];
    }
}
