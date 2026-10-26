package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.util.Stability;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.FuelFireball;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Conventional-explosion cloud laws: MTT rise, Church, CCPS fuel fireball, matter, fire color, lifetime. */
class CloudModelTest {
    /**
     * Fire-stage soot enters the cloud without a jump (review #426: a dark dome in one 0.05 s step, missile at
     * 2.60→2.65 s, bomb at 2.95→3.00 s): soot does not jump from fireball to cloud at burnout, day or night;
     * by day, soot coverage around the fireball changes through cooling in 0.05 s by no more than a normal burning step.
     * All mass is retained. ⚠ At night, soot emerges over ~0.1 s as the fireball cools below ~1200 K; likewise by day
     * during explosive-fireball burning (Grad at 0.3–0.4 s, bomb at 2.3–2.4 s): {@link CloudModel#glowShare} multiplies soot
     * mass in {@code SmokePuffs}, and the fireball's hundreds of optical depths are opaque even at a one-percent share.
     * That concerns smoke rendering, not this transition.
     */
    @Test
    void sootCoverageChangesContinuouslyFromBallToCloud() {
        for (WeaponType weapon : List.of(WeaponType.DRONE, WeaponType.MISSILE, WeaponType.BUNKER, WeaponType.ROCKET)) {
            CloudModel m = weapon == WeaponType.BUNKER
                    ? new CloudModel(new BlastSource(weapon, weapon.spec().warheadTnt(), 0, true, false), CloudModel.Air.STANDARD)
                    : model(weapon, CloudModel.Air.STANDARD);
            for (double sky : new double[]{1, 8e-4}) {
                double[] before = sootImage(m, m.burnSeconds - 1e-6, sky), after = sootImage(m, m.burnSeconds + 1e-6, sky);
                // the fireball-size step remains: the cooling fireball starts with the volume holding heat at its temperature (for
                // Shahed, 4 % larger than the CCPS fireball), no larger than a normal 0.05 s step
                assertTrue(imageDifference(before, after) < END_STEP, weapon + ", sky " + sky + ": no step at burnout");
            }
            double[] previous = sootImage(m, m.burnSeconds, 1);
            for (double t = m.burnSeconds + 0.05; t < m.fireSeconds + 0.5; t += 0.05) {
                double[] next = sootImage(m, t, 1);
                double change = imageDifference(previous, next);
                assertTrue(change < STEP_CHANGE, weapon + ": daytime soot coverage at " + t + " s changes by " + change);
                previous = next;
                double[] mass = {0};
                m.blobs(t, (x, y, z, sh, sv, soot, dust, water, kelvin, rise) -> mass[0] += soot);
                assertEquals(m.soot * m.fade(t), mass[0], 1e-9, "all soot retained");
            }
        }
    }

    /**
     * Maximum fraction of the frame around the fireball whose soot coverage changes over 0.05 s: continuous burning
     * and cooling give up to ~0.016; the former instant fireball-to-cloud swap gave 0.05–0.6 (edge growth and center rise in a tick).
     */
    private static final double STEP_CHANGE = 0.03;
    /** Coverage step exactly at burnout: a normal 0.05 s step is up to ~0.016; instant fireball-to-cloud swap, 0.05–0.6. */
    private static final double END_STEP = 0.015;

    /**
     * Cloud soot and dust coverage (1 − e^(−τ)) on a fixed plane spanning 6×6 burnout fireball radii around its center:
     * the share visible as smoke matches {@code SmokePuffs} (the fire body is not smoke, {@link CloudModel#glowShare});
     * ground-level wave dust has its own transition and is excluded.
     */
    static double[] sootImage(CloudModel model, double t, double sky) {
        int size = 64;
        double radius = model.ballRadius(model.burnSeconds), middle = model.ballHeight(model.burnSeconds);
        double[] tau = new double[size * size];
        model.blobs(t, (x, y, z, sh, sv, soot, dust, water, kelvin, rise) -> {
            if (soot <= 0) return;
            double centre = (CloudModel.SOOT_K * soot + ua.zentix.incoming.nuclear.model.DustModel.K_MIX * dust)
                    * (1 - CloudModel.glowShare(kelvin, sky)) / (2 * Math.PI * sh * sv);
            for (int j = 0; j < size; j++) {
                for (int i = 0; i < size; i++) {
                    double dx = ((i + 0.5) / size - 0.5) * 6 * radius - x, dy = ((j + 0.5) / size - 0.5) * 6 * radius + middle - y;
                    tau[j * size + i] += centre * Math.exp(-0.5 * (dx * dx / (sh * sh) + dy * dy / (sv * sv)));
                }
            }
        });
        for (int i = 0; i < tau.length; i++) tau[i] = -Math.expm1(-tau[i]);
        return tau;
    }

    static double imageDifference(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum += Math.abs(a[i] - b[i]);
        return sum / a.length;
    }

    @Test
    void crushedMaterialFeedsColdDustOnPavingWithoutIncreasingItsSurfaceLoading() {
        var hard = new GroundDust.Ground(GroundDust.Ground.filled(0), GroundDust.Ground.filled(0),
                GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), (x, z) -> 0);
        for (WeaponType w : List.of(WeaponType.DRONE, WeaponType.MISSILE, WeaponType.ROCKET)) {
            var source = BlastSource.of(w, false);
            var m = new CloudModel(source, CloudModel.Air.STANDARD, false, 0, hard);
            double produced = ua.zentix.incoming.nuclear.model.CraterModel.chargeVolume(source.chargeKg(),
                    ua.zentix.incoming.nuclear.model.CraterModel.Soil.DRY) * CloudModel.SOIL_DENSITY * CloudModel.DUST_LOFTED;
            assertEquals(produced, m.dust + m.crushed.total, 1e-9 * produced, "hot + cold conserves crushed material");
            assertTrue(m.crushed.total > 0 && m.dust > 0);
            assertEquals(0, m.crushed.underBall(60), "cold dust is not counted a second time in the hot cloud");
            double[] optical = {0}, aloft = {0};
            m.crushed.blobs(10, 1, (x, y, z, sh, sv, d) -> {
                assertTrue(y <= 2 * sv + 1, "cold source remains by the supporting surface");
                optical[0] = Math.max(optical[0], ua.zentix.incoming.nuclear.model.DustModel.K_MIX * d / (2 * Math.PI * sh * sv));
                aloft[0] += d;
            });
            assertTrue(optical[0] > 0.1, w + ": cold crushed dust is visible at 10 s, " + optical[0]);
            assertTrue(aloft[0] <= m.crushed.total + 1e-9);
            assertEquals(6e-4, ua.zentix.incoming.nuclear.model.DustModel.Surface.HARD.supply(), 0);
            var airburst = new CloudModel(source, CloudModel.Air.STANDARD, false, 100, hard);
            var water = new CloudModel(source, CloudModel.Air.STANDARD, true, 0, hard);
            assertEquals(0, airburst.crushed.total);
            assertEquals(0, water.crushed.total);
        }
    }

    private static CloudModel model(WeaponType w, CloudModel.Air air) {
        return new CloudModel(BlastSource.of(w, false), air);
    }

    /** Source: charge and fuel without a fire stage (little heat, small fireball), rising nearly like a point thermal. */
    private static CloudModel point(double tntKg, CloudModel.Air air) {
        return new CloudModel(new BlastSource(WeaponType.MISSILE, tntKg, 0, true, false), air);
    }

    /**
     * MTT thermal in stable air: maximum center height (8F/(α³N²))^¼ (F = 3B/(4π)), reached after π/N seconds, in two
     * stratifications (standard atmosphere and nighttime inversion). A finite initial sphere is the same thermal from
     * a "virtual origin" R₀/α below it (a point source would reach R₀ at height R₀/α); apex time is independent of sphere size.
     */
    @Test
    void riseFollowsMttInTwoStratifications() {
        for (double n : new double[]{CloudModel.N_STANDARD, Stability.brunt(Stability.F)}) {
            CloudModel.Air air = new CloudModel.Air(n, 0, 0, 0.14, 3, 0.5);
            for (double kg : new double[]{6.4, 50, 450}) {
                CloudModel m = point(kg, air);
                double f = 3 * m.buoyancy / (4 * Math.PI);
                // point thermal of the same buoyancy starting at the virtual origin: sphere radius / α below its center
                double zMax = Math.pow(8 * f / (Math.pow(CloudModel.ALPHA, 3) * n * n), 0.25) - m.radius(0) / CloudModel.ALPHA + m.height(0);
                assertEquals(1, m.topZ / zMax, 0.03, "N " + n + ", " + kg + " kg: apex " + m.topZ + " versus MTT " + zMax);
                assertEquals(Math.PI / n, m.topSeconds, 0.08 * Math.PI / n, "N " + n + ", " + kg + " kg: apex time");
                // thermal radius at the apex is α·height (MTT: dR/dz = α), corrected for the initial sphere
                assertEquals(CloudModel.ALPHA, (m.topR - m.radius(0)) / (m.topZ - m.height(0)), 0.03);
            }
        }
    }

    /** At night (inversion), the cloud is lower than by day: z ∝ N^(−½). */
    @Test
    void stableNightKeepsTheCloudLower() {
        CloudModel day = model(WeaponType.DRONE, air(Stability.B, 0.9, 0.45, 0.5)), night = model(WeaponType.DRONE, air(Stability.F, 0.9, 0.45, 0.5));
        assertTrue(night.topZ < day.topZ, "night " + night.topZ + ", day " + day.topZ);
        assertEquals(Math.sqrt(CloudModel.N_STANDARD / Stability.brunt(Stability.F)), night.topZ / day.topZ, 0.1);
    }

    /** Explosive cloud in a standard atmosphere: Church top (92.6·W^¼ m); the W^¼ law itself follows from MTT. */
    @Test
    void highExplosiveCloudFollowsChurch() {
        for (double kg : new double[]{3, 6.4, 50, 150, 450}) {
            CloudModel m = point(kg, CloudModel.Air.STANDARD);
            double church = 92.6 * Math.pow(kg, 0.25);
            assertEquals(1, (m.topZ + m.topR) / church, 0.1, kg + " kg: top " + (m.topZ + m.topR) + " versus Church " + church);
        }
        // explosive heat in the cloud is one third of TNT energy; the wave and light carry away the rest
        assertEquals(0.31, CloudModel.HE_HEAT / 4.184e6, 0.03);
    }

    /**
     * CCPS fuel fireball: D = 5.8·M^⅓ m, burning for 0.45·M^⅓ s; the initial cloud volume, in which the heat sustains
     * fireball temperature, has the same size (the same fireball derived from heat and black-body radiation rather than CCPS).
     */
    @Test
    void fuelFireballFollowsCcps() {
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE}) {
            CloudModel m = model(w, CloudModel.Air.STANDARD);
            double mass = ua.zentix.incoming.nuclear.model.FuelFireball.ballKg(BlastSource.of(w, false).fuelKg());
            assertEquals(5.8 * Math.cbrt(mass), m.fuelBall, 1e-9);
            assertEquals(0.45 * Math.cbrt(mass), m.fuelBallSeconds, 1e-9);
            assertEquals(1, m.radius(0) / (0.5 * m.fuelBall), 0.15, w + ": initial radius " + m.radius(0) + " versus CCPS fireball " + 0.5 * m.fuelBall);
            assertEquals(CloudModel.FUEL_BALL_K, m.ballKelvin(0.5 * (m.heBallSeconds + m.fuelBallSeconds)), 1e-9, "after the flash, fuel fireball");
        }
        // Shahed: 30 kg gasoline, 22.5 kg in the fireball (a quarter in the crater): 16.4 m fireball for 1.27 s (ref/passport.md: 12–21 m, 1.0–1.7 s)
        CloudModel drone = model(WeaponType.DRONE, CloudModel.Air.STANDARD);
        assertEquals(16.4, drone.fuelBall, 0.2);
        assertEquals(1.27, drone.fuelBallSeconds, 0.02);
        // Grad and Lancet have no fuel, hence no fuel fireball
        assertEquals(0, model(WeaponType.ROCKET, CloudModel.Air.STANDARD).fuelBall);
        assertEquals(0, model(WeaponType.LOITER, CloudModel.Air.STANDARD).fuelBall);
    }

    /**
     * Fire color follows a black body: the fuel fireball (~1480 K from 275 kW/m²) is orange (red > green > blue,
     * almost no blue); the detonation-products flash (3000 K) is warm white and hundreds of times brighter:
     * the same {@link BlackBody} as the nuke.
     */
    @Test
    void fireColourIsBlackBody() {
        assertEquals(1484, CloudModel.FUEL_BALL_K, 1);
        double[] fuel = new double[4], flash = new double[4];
        BlackBody.light(CloudModel.FUEL_BALL_K, fuel);
        BlackBody.light(CloudModel.HE_BALL_HOT, flash);
        assertTrue(fuel[0] > fuel[1] && fuel[1] > 10 * fuel[2], "fuel fireball is orange");
        assertTrue(flash[2] > 0.1 * flash[0], "flash is warm white");
        assertTrue(flash[3] > 100 * fuel[3], "flash is hundreds of times brighter than the fuel fireball");
        // cloud temperature falls: fire stage, then the thermal cools by entraining air
        CloudModel m = model(WeaponType.DRONE, CloudModel.Air.STANDARD);
        double prev = m.kelvin(0);
        for (double t = m.fireSeconds; t < 120; t += 1) {
            double k = m.kelvin(t);
            assertTrue(k <= prev + 1e-9, "t " + t);
            prev = k;
        }
        assertTrue(m.kelvin(60) < 300, "after a minute, cloud temperature is close to air temperature");
    }

    /**
     * Matter: soot follows composition (TNT is black, RDX has no free carbon; missile soot comes only from fuel);
     * dust comes from the crater and grows with charge.
     */
    @Test
    void matterFromCompositionAndCrater() {
        assertEquals(0.185, BlastSource.Explosive.TNT.freeCarbon(), 0.002);
        assertEquals(0, BlastSource.Explosive.RDX.freeCarbon(), 1e-12);
        assertEquals(-0.74, BlastSource.Explosive.TNT.oxygenBalance(), 0.005);
        assertEquals(-0.216, BlastSource.Explosive.RDX.oxygenBalance(), 0.002);
        CloudModel rocket = model(WeaponType.ROCKET, CloudModel.Air.STANDARD), missile = model(WeaponType.MISSILE, CloudModel.Air.STANDARD);
        assertEquals(6.4 * 0.185 * CloudModel.SOOT_SURVIVES, rocket.soot, 1e-3);
        // cloud soot comes from fireball fuel; crater soot leaves in its fire plume
        assertEquals(ua.zentix.incoming.nuclear.model.FuelFireball.ballKg(WeaponSpec.MISSILE.fuelAtImpact()) * ua.zentix.incoming.nuclear.model.FuelPool.Fuel.JP10.sootYield,
                missile.soot, 1e-9, "RDX produces no soot");
        assertTrue(missile.dust > 10 * rocket.dust, "dust: crater volume ∝ W");
    }

    /** Lifetime lasts while optical depth across the center exceeds the visibility threshold; the cloud thins over time. */
    @Test
    void lifeEndsWhenTheCloudIsThin() {
        for (WeaponType w : new WeaponType[]{WeaponType.ROCKET, WeaponType.LOITER, WeaponType.DRONE, WeaponType.MISSILE}) {
            CloudModel m = model(w, air(Stability.B, 0.9, 0.45, 0.5));
            if (m.life < CloudModel.LIFE_MAX) assertEquals(CloudModel.SEEN, m.tau(m.life), 1e-3, w.toString());
            double prev = m.tau(1);
            for (double t = 2; t < m.life; t *= 1.5) {
                assertTrue(m.tau(t) <= prev + 1e-9, w + " t " + t);
                prev = m.tau(t);
            }
            assertTrue(m.life > 60, w + ": cloud visible for minutes");
        }
    }

    /** Billows conserve matter: cloud soot equals all soot produced so far; dust in cloud, stem and ground layer never exceeds the total. */
    @Test
    void blobsCarryTheMatter() {
        CloudModel m = model(WeaponType.DRONE, CloudModel.Air.STANDARD);
        for (double t : new double[]{0.2, 3, 30, 300}) {
            List<double[]> blobs = new ArrayList<>();
            m.blobs(t, (x, y, z, sh, sv, soot, dust, water, k, rise) -> blobs.add(new double[]{y, sh, sv, soot, dust, k}));
            double soot = 0, dust = 0;
            for (double[] b : blobs) {
                soot += b[3];
                dust += b[4];
                assertTrue(b[1] > 0 && b[2] > 0, "σ > 0");
                assertTrue(b[5] >= CloudModel.T_AIR_K - 1e-9, "no colder than air");
            }
            assertEquals(m.sootBurnt(t) * m.fade(t), soot, 1e-9, "t " + t);
            // dust is extinction in kg of mixture: crater, wave layer and material stripped beneath the fireball, never more than lofted
            assertTrue(dust <= m.dust + m.crushed.total + m.ground.total + m.ground.underBall(t) + 1e-9, "t " + t);
            assertTrue(blobs.get(0)[0] > 0, "cloud above ground");
        }
        // two hot spheres during the fire stage: detonation products (hotter) and fuel
        List<Double> kelvin = new ArrayList<>();
        m.blobs(0.5, (x, y, z, sh, sv, soot, dust, water, k, rise) -> kelvin.add(k));
        assertEquals(m.heBallKelvin(0.5), kelvin.get(0), 1);
        assertEquals(CloudModel.FUEL_BALL_K, kelvin.get(1), 1);
        // fireball light is the flux from both bodies: while detonation products emit, brighter than the fuel fireball alone
        double[] both = new double[4], late = new double[4];
        m.ballLight(0.1, both);
        m.ballLight(1.0, late);
        assertTrue(both[3] > late[3], "flash is brighter");
        assertEquals(BlackBody.luminance(CloudModel.FUEL_BALL_K) * m.radiation.fuel(), late[3], 1e-9, "after the flash, fuel fireball within its own budget");
    }

    /**
     * The stem is a continuous dust tube from the ground layer to the cloud bottom (segments meet end to start), carrying
     * exactly the dust drawn from the wave layer but not yet delivered to the cloud (previously billows every 2σ; F5 frames: beads).
     */
    @Test
    void stemIsOneTubeWithItsDust() {
        CloudModel m = new CloudModel(BlastSource.of(WeaponType.MISSILE, false), air(Stability.D, 3, 0, 0.5));
        for (double t : new double[]{10, 20, 40}) {
            List<double[]> pieces = new ArrayList<>();
            m.blobs(t, new CloudModel.Blobs() {
                @Override
                public void blob(double x, double y, double z, double sh, double sv, double soot, double dust, double water, double kelvin, double rise) {}

                @Override
                public void tube(double ax, double ay, double az, double sa, double dustA, double bx, double by, double bz, double sb, double dustB,
                                 double kelvin, double rise) {
                    pieces.add(new double[]{ax, ay, az, bx, by, bz, dustA, dustB});
                }
            });
            assertEquals(CloudModel.STEM_PIECES, pieces.size(), "stem after " + t + " s");
            double foot = pieces.get(0)[1] + m.hob;
            assertTrue(foot > 0 && foot <= Math.max(0.35 * m.sigmaH(t), 1) + 1e-9, "stem bottom at its σ above ground: " + foot);
            double dust = 0;
            for (int i = 0; i < pieces.size(); i++) {
                double[] p = pieces.get(i);
                if (i > 0) for (int a = 0; a < 3; a++) assertEquals(pieces.get(i - 1)[3 + a], p[a], 1e-9, "segment " + i + " starts at the previous end");
                double len = Math.sqrt(Math.pow(p[3] - p[0], 2) + Math.pow(p[4] - p[1], 2) + Math.pow(p[5] - p[2], 2));
                dust += 0.5 * (p[6] + p[7]) * len;
            }
            double stem = (m.ground.drawn(t) - m.ground.drawn(m.stemEntered(t))) * m.fade(t);
            assertEquals(stem, dust, 1e-9 * Math.max(1, stem), "stem dust after " + t + " s");
        }
    }

    /** Wind carries the cloud along, more strongly at height; condensation cloud occurs only in humid air. */
    @Test
    void windAndHumidity() {
        CloudModel calm = model(WeaponType.DRONE, air(Stability.B, 0, 0, 0.5)), windy = model(WeaponType.DRONE, air(Stability.B, 2, 0, 0.5));
        assertEquals(0, calm.driftX(120), 1e-9);
        assertTrue(windy.driftX(120) > 2 * 120, "wind is stronger aloft than at 10 m");
        assertEquals(0, model(WeaponType.MISSILE, air(Stability.B, 1, 0, 0.1)).condensation(), "dry: no condensation cloud");
        assertTrue(model(WeaponType.MISSILE, air(Stability.B, 1, 0, 0.8)).condensation() > 10, "humid: cloud present");
    }

    /** Air at the explosion site, by stability class and wind at 10 m. */
    @Test
    void wilsonWaterDoesNotExistInsideTheHotFireball() {
        CloudModel cloud = model(WeaponType.DRONE, air(Stability.B, 1, 0, 1));
        int[] seen = {0};
        for (double t = 0.01; t < cloud.condensationSeconds(); t += 0.01) {
            double ball = cloud.ballRadius(t), cx = cloud.driftX(t), cy = cloud.ballHeight(t), cz = cloud.driftZ(t);
            cloud.condensation(t, (x, y, z, sh, sv, soot, dust, water, kelvin, rise) -> {
                if (water <= 0) return;
                seen[0]++;
                double dx = x-cx, dy = y-cy, dz = z-cz;
                assertTrue(Math.sqrt(dx*dx + dy*dy + dz*dz) > ball, "hot products cannot contain a cold Wilson cloud");
            });
        }
        assertTrue(seen[0] > 0, "the saturated shell still forms outside the fireball");
    }

    private static CloudModel.Air air(double stability, double windX, double windZ, double humidity) {
        return new CloudModel.Air(Stability.brunt(stability), windX, windZ, Stability.alpha(stability), (int) Math.round(stability), humidity);
    }

    /**
     * Fireball light does not switch off in a step (3.5.0: full fuel-fireball light until burnout, then 0): each tick,
     * radius changes by at most a fifth of its maximum (fuel fireball grows to D over a fifth of its burn), temperature
     * by at most 300 K (detonation-products flash cools in fractions of a second), and light, while above a thousandth
     * of the fuel maximum, falls no more than tenfold (Planck cooling is fast: 1700 → 1500 K gives an eightfold drop,
     * without cutting to zero). At the Draper point light is thousandths. Holds for all projectiles, day and night.
     * The short explosive pulse ends in less than a tick; FireballRadiationTest checks its continuity and budget.
     */
    @Test
    void fireballFadesWithoutSteps() {
        double[] light = new double[4];
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE, WeaponType.ROCKET, WeaponType.LOITER}) {
            for (double day : new double[]{1, 0}) {
                CloudModel m = model(w, air(day > 0.5 ? Stability.B : Stability.F, 0.9, 0.45, 0.5));
                assertTrue(m.fireSeconds > m.burnSeconds, w + ": cools after burnout");
                double rMax = 0, yMax = 0;
                for (double t = 0.05; t < m.fireSeconds + 0.5; t += 0.05) {
                    m.ballLight(t, light);
                    rMax = Math.max(rMax, m.ballRadius(t));
                    yMax = Math.max(yMax, light[3]);
                }
                double prevR = m.ballRadius(0.05), prevK = m.ballKelvin(0.05), prevY;
                m.ballLight(0.05, light);
                prevY = light[3];
                for (double t = 0.1; t < m.fireSeconds + 0.5; t += 0.05) {
                    m.ballLight(t, light);
                    double r = m.ballRadius(t), y = light[3], k = m.ballKelvin(t);
                    String at = w + " day " + day + " at " + String.format(java.util.Locale.ROOT, "%.2f", t) + " s: ";
                    // radius is checked while the fireball emits visibly (at the Draper point it is absent from screen: the fire body is already smoke)
                    if (prevY > 1e-3 * yMax) assertTrue(Math.abs(r - prevR) <= 0.2 * rMax, at + "radius " + prevR + " → " + r);
                    if (k > 0 && prevK > 0) assertTrue(Math.abs(k - prevK) <= 300, at + "temperature " + prevK + " → " + k);
                    if (m.source.fuelKg() > 0 && prevY > 1e-3 * yMax)
                        assertTrue(y >= 0.1 * prevY, at + "light " + prevY + " → " + y);
                    prevR = r;
                    prevY = y;
                    prevK = k;
                }
                m.ballLight(m.fireSeconds - 0.05, light);
                assertTrue(light[3] < 1e-3 * yMax, w + ": at the Draper point, light is thousandths, " + light[3] / yMax);
            }
        }
        // the fire body fades into smoke and smoke emerges with the same temperature share
        assertEquals(1, CloudModel.glowShare(CloudModel.FUEL_BALL_K), 1e-12);
        assertEquals(0, CloudModel.glowShare(CloudModel.DRAPER_K), 1e-12);
    }

    /**
     * Fireball grows from the impact point and rises without jumps (BLASTSEQ D01: at 0.1–0.2 s, center jumped 6 m for
     * Shahed and 14 m for missile; fuel fireball appeared at D/2 above ground): after detonation-products expansion,
     * center moves by at most 0.15 maximum radius per 0.05 s, including when the larger sphere changes, at burnout,
     * and for airbursts. While the fuel fireball grows, it rests on ground (bottom no higher than the impact point);
     * at burnout its center is 0.75·D (CCPS).
     */
    @Test
    void fireballGrowsFromTheGroundWithoutJumps() {
        List<CloudModel> models = new ArrayList<>();
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE, WeaponType.ROCKET, WeaponType.LOITER}) {
            models.add(model(w, air(Stability.D, 5, 0, 0.5)));
        }
        models.add(new CloudModel(BlastSource.air(20), air(Stability.D, 5, 0, 0.5), false, 15));
        for (CloudModel m : models) {
            String name = m.source.weapon() + " at " + m.hob + " m";
            double rMax = 0;
            for (double t = 0; t < m.fireSeconds; t += 0.01) rMax = Math.max(rMax, m.ballRadius(t));
            // first 0.05 s: detonation-products expansion (almost the full radius in a few percent of lifetime), growth rather than a jump
            double prev = m.ballHeight(0.05);
            for (double t = 0.1; t < m.fireSeconds; t += 0.05) {
                double h = m.ballHeight(t);
                assertTrue(Math.abs(h - prev) <= 0.15 * rMax, name + ": fireball center " + prev + " → " + h + " m at " + t + " s");
                prev = h;
            }
            if (m.fuelBall > 0) {
                for (double t = 0.01; t < 0.2 * m.fuelBallSeconds; t += 0.01) {
                    assertTrue(m.fuelBallHeight(t) - m.fuelBallRadius(t) <= 1e-9, name + ": growing fuel fireball left ground at " + t + " s");
                }
                assertEquals(0.75 * m.fuelBall, m.ballHeight(m.fuelBallSeconds - 1e-9), 1e-6, name + ": center at burnout is 0.75·D");
            }
        }
    }

    /**
     * The fireball image, light and heat share its smoke position (BLASTSEQ D01: fireball stood above the crater while
     * smoke drifted away, 21 m for missile by the end of emission): during burning, the larger fireball billow
     * is centered at {@link CloudModel#ballX}, fireball height, {@link CloudModel#ballZ}; wind carries the fireball away from impact.
     */
    @Test
    void fireballAndItsSmokeShareOnePlace() {
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE, WeaponType.ROCKET}) {
            CloudModel m = model(w, air(Stability.D, 5, 2, 0.5));
            for (double t = 0.05; t < m.burnSeconds; t += 0.05) {
                double[] big = {-1, 0, 0, 0};
                // during burning, billows are only fireballs; ground dust and stem are separate
                m.blobs(t, new CloudModel.Blobs() {
                    @Override
                    public void blob(double x, double y, double z, double sh, double sv, double soot, double dust, double water, double kelvin, double rise) {
                        if (sh > big[0]) {
                            big[0] = sh;
                            big[1] = x;
                            big[2] = y;
                            big[3] = z;
                        }
                    }

                    @Override
                    public void ground(double x, double y, double z, double sh, double sv, double dust, double kelvin) {}

                    @Override
                    public void crushed(double x, double y, double z, double sh, double sv, double dust, double kelvin) {}

                    @Override
                    public void tube(double ax, double ay, double az, double sa, double dustA, double bx, double by, double bz, double sb, double dustB,
                                     double kelvin, double rise) {}
                });
                assertEquals(m.ballRadius(t) / Math.sqrt(5), big[0], 1e-9, w + ": fireball billow at " + t + " s has its size");
                assertEquals(m.ballX(t), big[1], 1e-9, w + ": fireball and smoke x at " + t + " s");
                assertEquals(m.ballHeight(t), big[2], 1e-9, w + ": fireball and smoke height at " + t + " s");
                assertEquals(m.ballZ(t), big[3], 1e-9, w + ": fireball and smoke z at " + t + " s");
            }
            assertTrue(Math.hypot(m.ballX(m.burnSeconds), m.ballZ(m.burnSeconds)) > 1, w + ": fireball does not leave impact in 5 m/s wind");
        }
    }

    /**
     * Soot forms when its source burns (BLASTSEQ D02): explosive soot all appears at detonation; fuel soot follows
     * the share burnt in the fireball (Shahed at 0.1 burn time has a tenth), reaching the total by burnout;
     * billows contain exactly the soot produced.
     */
    @Test
    void fuelSootIsBornAsTheFuelBurns() {
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE}) {
            CloudModel m = model(w, air(Stability.D, 3, 0, 0.5));
            double he = m.heSoot(), fuel = m.soot - he;
            assertTrue(fuel > 0, w + ": fuel produces soot");
            assertEquals(he, m.sootBurnt(0), 1e-12, w + ": at detonation, only explosive soot");
            assertEquals(he + 0.1 * fuel, m.sootBurnt(0.1 * m.fuelBallSeconds), 1e-9, w + ": at 0.1 burn time, a tenth of fuel soot");
            assertEquals(m.soot, m.sootBurnt(m.fuelBallSeconds), 1e-12, w + ": at burnout, all soot");
            for (double t = 0.02; t < m.burnSeconds; t += 0.1) {
                double[] mass = {0};
                m.blobs(t, (x, y, z, sh, sv, soot, dust, water, kelvin, rise) -> mass[0] += soot);
                assertEquals(m.sootBurnt(t) * m.fade(t), mass[0], 1e-9, w + ": billows at " + t + " s contain the soot produced");
            }
        }
    }

    /**
     * The stem grows from below (BLASTSEQ D09: previously reached the cloud immediately when inflow began): its top
     * follows the earliest entrained dust, no higher than the cloud bottom; initially below the cloud bottom, reaching it later.
     */
    @Test
    void stemRisesFromTheGround() {
        CloudModel m = new CloudModel(BlastSource.of(WeaponType.MISSILE, false), air(Stability.D, 3, 0, 0.5));
        double from = m.ground.drawnFrom;
        assertTrue(Double.isFinite(from), "stem inflow begins");
        boolean below = false, reached = false;
        double prevTop = 0;
        for (double t = from + 0.5; t < 60; t += 0.5) {
            double[] top = {Double.NaN};
            m.blobs(t, new CloudModel.Blobs() {
                @Override
                public void blob(double x, double y, double z, double sh, double sv, double soot, double dust, double water, double kelvin, double rise) {}

                @Override
                public void tube(double ax, double ay, double az, double sa, double dustA, double bx, double by, double bz, double sb, double dustB,
                                 double kelvin, double rise) {
                    top[0] = by + m.hob;
                }
            });
            if (Double.isNaN(top[0])) continue;
            double bottom = m.height(t) - 1.5 * m.sigmaV(t) + m.hob;
            assertTrue(top[0] <= bottom + 1e-9, "stem above cloud bottom at " + t + " s");
            assertTrue(top[0] <= Math.max(0.35 * m.sigmaH(t), 1) + m.stemTop(t) + 1e-9, "stem above the dust arrival height at " + t + " s");
            assertTrue(top[0] >= prevTop - 1e-6 || reached, "stem descended at " + t + " s");
            prevTop = top[0];
            if (top[0] < bottom - 1) below = true;
            if (top[0] > bottom - 1e-6) reached = true;
        }
        assertTrue(below, "stem did not grow from below: reached the cloud immediately");
        assertTrue(reached, "stem never reached the cloud");
    }

    /**
     * Fire stays visible while its emission is at least sky light on its smoke ({@link CloudModel#glowShare(double, double)}):
     * bomb fireball (venting ~1.3 t) is smoke by 3 s during the day (look gate RV2, bomb at 150 m: entirely orange fireball),
     * still fire at night; while burning, fire is visible by day too. The share is smooth and no greater than the temperature-only share.
     */
    @Test
    void fireballFadesIntoSmokeByWhatTheEyeSees() {
        CloudModel m = new CloudModel(new BlastSource(WeaponType.BUNKER, 1340, 0, true, false), CloudModel.Air.STANDARD);
        // sky light: 1 by day, ambient^ADAPT at night (FarView.skyLuminance)
        double day = 1, night = Math.pow(0.17, ua.zentix.incoming.client.far.Sight.ADAPT);
        assertTrue(CloudModel.glowShare(m.ballKelvin(1), day) > 0.95, "while burning, fire is visible by day too");
        assertTrue(CloudModel.glowShare(m.ballKelvin(3), day) < 0.15, "smoke by 3 s during the day: " + CloudModel.glowShare(m.ballKelvin(3), day));
        assertTrue(CloudModel.glowShare(m.ballKelvin(3), night) > 0.9, "fire at 3 s at night");
        // at night, gas at the Draper point is no darker than the temperature-only half share (soot clump over the nighttime fireball, F6)
        assertTrue(CloudModel.glowShare(1000, night) > 0.4 * CloudModel.glowShare(1000), "warm gas still glows at night");
        // matter inside the glowing fireball fades into smoke by the same share as the fireball: during the first daytime second, its dust stem
        // formed a dark clump over the fireball (F6 frames, bomb at 150 m)
        for (double t : new double[]{0.1, 0.5, 1, 2}) {
            double ball = m.ballRadius(t), bh = m.ballHeight(t), cx = m.driftX(t), cz = m.driftZ(t), share = CloudModel.glowShare(m.ballKelvin(t), day);
            m.blobs(t, (x, y, z, sh, sv, soot, dust, water, kelvin, rise) -> {
                double dx = x - cx, dy = y - bh, dz = z - cz;
                if (dx * dx + dy * dy + dz * dz < 0.6 * ball * ball && soot + dust > 0)
                    assertTrue(CloudModel.glowShare(kelvin, day) >= share - 1e-9, "inside the fireball at " + t + " s, fire rather than smoke: " + kelvin);
            });
        }
        // fire brighter than smoke light by the contrast threshold is entirely fire: none of its matter renders as smoke (tons of fireball dust)
        assertEquals(1, CloudModel.glowShare(m.ballKelvin(0.5), day), 0, "burning fireball by day is exactly fire");
        assertEquals(0, CloudModel.glowShare(900, day), 0, "warm gas by day is exactly smoke");
        double prev = 1;
        for (double k = 3000; k >= 700; k -= 5) {
            double g = CloudModel.glowShare(k, day);
            assertTrue(g <= CloudModel.glowShare(k) + 1e-12);
            assertTrue(g <= prev + 1e-12 && prev - g < 0.06, "no jumps: " + k);
            if (k > CloudModel.DRAPER_K) assertEquals(BlackBody.luminance(k), CloudModel.glowLuminance(k), 0.002 * BlackBody.luminance(k));
            prev = g;
        }
    }

    /**
     * Crater fuel (Tieszen, Attaway 1996: 25–50 % remains in the crater at steep impact; burns as a thin layer, one fifth
     * of deep-pool burn rate, Mealy et al. 2011): follows area burn rate over crater area, Heskestad flame law; Grad has
     * no fuel and no fire. Shahed burns for minutes (complaint F: the 3.5.0 pool went out after 12 s, leaving no smoke
     * above impact by 30 s), missile for more than ten minutes.
     */
    @Test
    void craterFuelBurnsForMinutes() {
        CloudModel drone = model(WeaponType.DRONE, CloudModel.Air.STANDARD), rocket = model(WeaponType.ROCKET, CloudModel.Air.STANDARD),
                missile = model(WeaponType.MISSILE, CloudModel.Air.STANDARD);
        assertEquals(0, rocket.poolSeconds);
        assertEquals(0, rocket.poolShare(1));
        // no crater on water, hence no crater fire
        assertEquals(0, new CloudModel(drone.source, CloudModel.Air.STANDARD, true).poolSeconds);
        double area = Math.PI * drone.poolRadius * drone.poolRadius;
        assertEquals(CloudModel.POOL_SHARE * drone.source.fuelKg() / (ua.zentix.incoming.nuclear.model.FuelPool.THIN_LAYER * ua.zentix.incoming.nuclear.model.FuelPool.Fuel.PETROL.poolBurn * area),
                drone.poolSeconds, 1e-9);
        assertEquals(drone.poolKg * ua.zentix.incoming.nuclear.model.FuelPool.Fuel.PETROL.heatJPerKg, drone.poolWatts * drone.poolSeconds, 1e-6 * drone.poolKg * 43.4e6);
        assertTrue(drone.poolSeconds > 90 && drone.poolSeconds < 300, "Shahed: fuel burns for " + drone.poolSeconds + " s");
        assertTrue(missile.poolSeconds > 600 && missile.poolSeconds < 1800, "missile: fuel burns for " + missile.poolSeconds + " s");
        assertTrue(drone.pool.flame() > 1 && drone.pool.flame() < 15, "flame " + drone.pool.flame() + " m");
        assertEquals(1, drone.poolShare(60), 1e-12, "after a minute, Shahed still burns at full strength");
        assertEquals(0, drone.poolShare(drone.poolSeconds), 1e-12);
        // smoke above it is visible: plume optical depth 20 m downwind in 5 m/s wind by day (class C) exceeds the visibility threshold
        FirePlume plume = new FirePlume(drone.poolWatts, drone.poolRadius, ua.zentix.incoming.nuclear.model.FuelPool.Fuel.PETROL.sootYield / ua.zentix.incoming.nuclear.model.FuelPool.Fuel.PETROL.heatJPerKg, 5, 2);
        assertTrue(plume.tau(20) > 10 * CloudModel.SEEN, "plume optical depth at 20 m " + plume.tau(20));
    }

    /**
     * Wave dust rests on ground beneath the explosion (23.10 complaint: flat dust "pancakes" above the city at roof-hit
     * heights): for a burst at height h above ground, dust-layer billows stay near ground (center no higher than 2σ
     * above it; heights relative to the burst), rather than at burst height; a ground impact has a dust layer.
     */
    @Test
    void groundDustLiesOnTheGround() {
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE, WeaponType.ROCKET}) {
            for (double hob : new double[]{0, 6, 20, 45}) {
                CloudModel m = new CloudModel(BlastSource.of(w, false), CloudModel.Air.STANDARD, false, hob, GroundDust.Ground.BARE);
                double[] dust = new double[1];
                for (double t : new double[]{0.3, 2, 10, 60}) {
                    final double age = t;
                    m.ground.blobs(t, 1, (x, y, z, sh, sv, d) -> {
                        dust[0] += d;
                        assertTrue(y + hob <= 2 * sv + 0.5, w + " at " + hob + " m, " + age + " s: dust billow " + (y + hob) + " m above ground, σv " + sv);
                    });
                }
                if (hob == 0) assertTrue(dust[0] > 0, w + ": a ground impact has wave dust");
            }
        }
    }

    /**
     * Wind grows with height: cloud top leads the bottom (23.10 complaint: cloud drifted as a rigid body). For missile
     * in 5 m/s wind at 10 m (class D), the layer above the cloud center is farther downwind than the layer below it,
     * and the difference grows; in calm air, layers stay directly above each other.
     */
    @Test
    void topDriftsFasterThanBottom() {
        CloudModel.Air wind = new CloudModel.Air(ua.zentix.incoming.util.Stability.STANDARD_N, 5, 0, 0.15, 3, 0.5);
        CloudModel m = new CloudModel(BlastSource.of(WeaponType.MISSILE, false), wind);
        double last = 0;
        for (double t : new double[]{20, 60, 120, 240}) {
            assertTrue(t > m.fireSeconds, "after the fire stage");
            java.util.List<double[]> layers = new java.util.ArrayList<>();
            m.blobs(t, (x, y, z, sh, sv, soot, dust, water, kelvin, rise) -> {
                if (soot > 0) layers.add(new double[]{x, y});
            });
            assertEquals(CloudModel.LAYERS, layers.size(), "cloud layers at " + t + " s");
            layers.sort(java.util.Comparator.comparingDouble(a -> a[1]));
            double lead = layers.get(layers.size() - 1)[0] - layers.get(0)[0];
            assertTrue(lead > last, t + " s: top leads bottom by " + lead + " m (previously " + last + ")");
            last = lead;
        }
        assertTrue(last > 20, "after 4 minutes, top leads by " + last + " m");
        CloudModel calm = new CloudModel(BlastSource.of(WeaponType.MISSILE, false), CloudModel.Air.STANDARD);
        calm.blobs(120, (x, y, z, sh, sv, soot, dust, water, kelvin, rise) -> {
            if (soot > 0) assertEquals(calm.driftX(120), x, 1e-9, "in calm air, layers stay directly above each other");
        });
    }

    /**
     * Cloud layers preserve the same Gaussian: total mass, center of mass and vertical second moment match a single
     * Gaussian of σv; the profile has no gaps.
     */
    @Test
    void layersKeepTheCloudsMoments() {
        double w = 0, q = 0, q2 = 0;
        for (int j = 0; j < CloudModel.LAYERS; j++) {
            w += CloudModel.LAYER_W[j];
            q += CloudModel.LAYER_W[j] * CloudModel.LAYER_Q[j];
            q2 += CloudModel.LAYER_W[j] * CloudModel.LAYER_Q[j] * CloudModel.LAYER_Q[j];
        }
        assertEquals(1, w, 1e-12);
        assertEquals(0, q, 1e-12);
        assertEquals(1, q2 + CloudModel.SLICE * CloudModel.SLICE, 1e-6);
        // vertical profile without gaps: density falls from center to edge (a continuous cloud rather than beads)
        double prev = Double.MAX_VALUE;
        for (double z = 0; z < 3; z += 0.05) {
            double p = 0;
            for (int j = 0; j < CloudModel.LAYERS; j++) {
                double u = (z - CloudModel.LAYER_Q[j]) / CloudModel.SLICE;
                p += CloudModel.LAYER_W[j] * Math.exp(-0.5 * u * u);
            }
            assertTrue(p < prev, "gap at " + z + " σv");
            prev = p;
        }
    }

    /**
     * Air mixes the cloud from the first second (23.10 complaint: cloud held its shape for minutes): while the thermal
     * still rises, σ already exceeds thermal radius (R/√5), and optical depth across the center decreases throughout,
     * not only after the apex.
     */
    @Test
    void airMixesTheCloudFromTheStart() {
        CloudModel.Air wind = new CloudModel.Air(ua.zentix.incoming.util.Stability.STANDARD_N, 5, 0, 0.15, 3, 0.5);
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE}) {
            CloudModel m = new CloudModel(BlastSource.of(w, false), wind);
            assertTrue(m.topSeconds > 100, w + ": apex after " + m.topSeconds + " s");
            for (double t : new double[]{10, 30, 60}) {
                double thermal = m.radius(t) / Math.sqrt(5);
                assertTrue(m.sigmaH(t) > 1.03 * thermal, w + " " + t + " s: σ " + m.sigmaH(t) + " versus thermal " + thermal);
            }
            double prev = Double.MAX_VALUE;
            for (double t = Math.ceil(m.fireSeconds); t < m.topSeconds; t += 5) {
                double tau = m.tau(t);
                assertTrue(tau < prev, w + " " + t + " s: optical depth " + tau + " after " + prev);
                prev = tau;
            }
        }
    }

    /**
     * The fireball igniting server blocks ({@link FuelFireball.Sweep}, {@code Craters.ignite}) is the same one players see
     * (BLASTSEQ D18): radius and center height at every burning step exactly match the visual fuel fireball; wind drift
     * is within a meter or 15 % of visual drift (the visual follows cloud thermal motion; server uses wind at fireball-center height).
     */
    @Test
    void serverFireballIsTheBallThePlayerSees() {
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE}) {
            for (double wind : new double[]{0, 2, 5, 10}) {
                CloudModel.Air air = air(Stability.D, wind * 0.8, wind * 0.6, 0.5);
                CloudModel m = model(w, air);
                FuelFireball.Sweep ball = FuelFireball.Sweep.of(FuelFireball.ballKg(m.source.fuelKg()), m.hob, air.windX(), air.windZ(), air.shear());
                for (int i = 0; i < ball.r().length; i++) {
                    double t = (i + 0.5) * ball.dt();
                    String at = w + ", wind " + wind + " m/s, " + t + " s";
                    assertEquals(m.fuelBallRadius(t), ball.r()[i], 1e-9, at + ": radius");
                    assertEquals(m.fuelBallHeight(t), ball.y()[i], 1e-9, at + ": center height");
                    double far = Math.hypot(m.ballX(t), m.ballZ(t));
                    assertEquals(0, Math.hypot(ball.x()[i] - m.ballX(t), ball.z()[i] - m.ballZ(t)), Math.max(1, 0.15 * far), at + ": drift");
                }
            }
        }
    }
}
