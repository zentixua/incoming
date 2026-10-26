package ua.zentix.incoming.client.fx.smoke;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.LocalWind;
import ua.zentix.incoming.util.Stability;
import ua.zentix.incoming.warhead.BreachModel;
import ua.zentix.incoming.warhead.GroundMaterial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * haze at the eye after arrival: optical depth of the eye ray (1,6 m above the street) downwind from the impact - the entire cloud, dust wave and
 *  skirt of destroyed dust. The standard is camera recordings after Shahed strikes on the streets of Kharkov: on 5–10 s the street in a gray-white
 *  haze, cars and houses in 30–60 m are visible (τ beam in 40 m - approx. 1–2: the haze is visible, you can see through it), by the minute it’s clear.
 * ⚠ The standard itself is an estimate of τ from camera frames, not frozen.
 */
class HazeAtTheEyeTest {
    private static final CloudModel.Air CALM = new CloudModel.Air(CloudModel.N_STANDARD, 3, 0, 0.1, (int) Stability.C, 0.5);

    /** Shahed into the street: on 6 and 10 s in 40 m - haze (visible, but not a wall), by a minute - clear. */
    @Test
    void streetHitLeavesASeeThroughHaze() {
        CloudModel m = scene(WeaponType.DRONE, false, CALM);
        for (double t : new double[]{6, 10}) {
            double tau = tau(m, 0, t, 40);
            assertTrue(tau > 0.5 && tau < 3, t + " s: τ in 40 m " + tau);
        }
        assertTrue(tau(m, 0, 60, 40) < 0.2, "minute: τ in 40 m " + tau(m, 0, 60, 40));
    }

    /**
     * Shahed into a concrete roof a meter above the room: from the side of the impact only the dust of the crater
     *  ({@link BlastTarget#SLAB_FRONT}) comes out - on 10 s the street in 40 m thicker than that of an impact on the street, but not the wall (without this - τ ≈ 10).
     */
    @Test
    void roofBreachIsThickerButNotAWall() {
        CloudModel roof = scene(WeaponType.DRONE, true, CALM), street = scene(WeaponType.DRONE, false, CALM);
        double r = tau(roof, 8, 10, 40);
        assertTrue(r > tau(street, 0, 10, 40) && r < 6, "τ in 40 m to 10 s " + r);
    }

    /**
     * Shahed into the pavement near the lawn (complaint: at the blow next to the grass in 40 m on 6 there was a brown wall - the turf gave off a centimeter of
     *  soil, like bare earth): there is grass around the crater. On 6 and 10 s in 40 there is the same haze as on the street, by the minute it’s clear.
     */
    @Test
    void streetHitByALawnIsNotABrownWall() {
        CloudModel m = scene(WeaponType.DRONE, false, CALM, GroundDust.Ground.filled(1), GroundDust.Ground.filled(0));
        for (double t : new double[]{6, 10}) {
            double tau = tau(m, 0, t, 40);
            assertTrue(tau < 3, t + " s: τ in 40 m " + tau + " " + java.util.Arrays.toString(parts(m, 0, t, 40)));
        }
        assertTrue(tau(m, 0, 60, 40) < 0.2, "minute: τ in 40 m " + tau(m, 0, 60, 40));
    }

    /**
     * Shahed at the intersection of the block (houses 10×10×12 m, streets along 6 m; the wind near the ground is {@link LocalWind}): in the street the wind is
     *  many times weaker than the field, and haze lasts longer - by 30 s 40 m along the street is still visible (the field is clear by this time), by
     * 5 minutes it goes away. ⚠ Standard - assessment: in a canyon street the air changes through the top in minutes (Oke 1988), cameras
     *  Kharkov - haze on 11–13 p.
     */
    @Test
    void streetCanyonHoldsTheHazeLonger() {
        GroundDust.Relief town = (x, z) -> Math.floorMod((int) Math.floor(x + 13), 16) < 10 && Math.floorMod((int) Math.floor(z + 13), 16) < 10 ? 12 : 0;
        LocalWind site = LocalWind.of((x, z) -> 64 + town.at(x - 13, z - 13), 13, 13, 1, 0);
        CloudModel.Air air = new CloudModel.Air(CALM.n(), CALM.windX(), CALM.windZ(), CALM.shear(), CALM.spread(), CALM.humidity(), site);
        CloudModel street = scene(WeaponType.DRONE, town, air), field = scene(WeaponType.DRONE, town, CALM);
        double held = tau(street, 0, 30, 40);
        assertTrue(held > 0.4 && held > 3 * tau(field, 0, 30, 40), "30 s: τ in 40 m " + held + ", in the field " + tau(field, 0, 30, 40));
        assertTrue(tau(street, 0, 300, 40) < 0.2, "5 minutes: τ in 40 m " + tau(street, 0, 300, 40));
    }

    /**
     * Gap at 100 m above the intersection (review R468): the thermal measures the height from the break point, and its wind is the wind of its height,
     *  as over a field, not the calm of the street; the impact on the pavement has the same thermal at the beginning - in a lull, it is carried away less.
     */
    @Test
    void airBurstOverTheStreetIsNotInTheStreetCalm() {
        GroundDust.Relief town = (x, z) -> Math.floorMod((int) Math.floor(x + 13), 16) < 10 && Math.floorMod((int) Math.floor(z + 13), 16) < 10 ? 12 : 0;
        LocalWind site = LocalWind.of((x, z) -> 64 + town.at(x - 13, z - 13), 13, 13, 1, 0);
        CloudModel.Air air = new CloudModel.Air(CALM.n(), CALM.windX(), CALM.windZ(), CALM.shear(), CALM.spread(), CALM.humidity(), site);
        BlastSource drone = BlastSource.of(WeaponType.DRONE, false);
        GroundDust.Ground flat = new GroundDust.Ground(GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), GroundDust.Ground.filled(1),
                GroundDust.Ground.filled(0), (x, z) -> 0);
        CloudModel high = new CloudModel(drone, air, false, 100, flat), highField = new CloudModel(drone, CALM, false, 100, flat);
        CloudModel low = new CloudModel(drone, air, false, 0, flat), lowField = new CloudModel(drone, CALM, false, 0, flat);
        for (double t : new double[]{1, 3}) {
            assertEquals(highField.thermal.driftX(t), high.thermal.driftX(t), 0.05 * highField.thermal.driftX(t), t + " s: in 100 m");
            assertTrue(low.thermal.driftX(t) < 0.8 * lowField.thermal.driftX(t), t + " from: near the ground " + low.thermal.driftX(t) + " against " + lowField.thermal.driftX(t));
        }
    }

    private static double tau(CloudModel m, double h, double t, double d) {
        double[] c = parts(m, h, t, d);
        return c[0] + c[1] + c[2];
    }

    static CloudModel scene(WeaponType weapon, boolean roof, CloudModel.Air air) {
        return scene(weapon, roof, air, GroundDust.Ground.filled(0), GroundDust.Ground.filled(1));
    }

    /** Shahed into the pavement among the relief {@code relief} (m from the point of impact). */
    static CloudModel scene(WeaponType weapon, GroundDust.Relief relief, CloudModel.Air air) {
        BlastSource source = BlastSource.of(weapon, false);
        BlastTarget target = BlastTarget.of(GroundMaterial.STONE, false, 0,
                BreachModel.Material.ROCK.radius(source.chargeKg(), BreachModel.UNTAMPED), Vec3.ZERO, new Vec3(0, -1, 0));
        double[][] heights = GroundDust.Ground.filled(0);
        for (int s = 0; s < GroundDust.SECTORS; s++) for (int b = 0; b < GroundDust.BANDS.length; b++) {
            double angle = 2 * Math.PI * (s + 0.5) / GroundDust.SECTORS, r = GroundDust.BANDS[b];
            heights[s][b] = relief.at(r * Math.cos(angle), r * Math.sin(angle));
        }
        var ground = new GroundDust.Ground(GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), GroundDust.Ground.filled(1), heights, relief);
        return new CloudModel(source, air, target, 0, ground);
    }

    /** Scene with surfaces around: the share of turf {@code covered} and hard {@code hard} by sectors and stripes. */
    static CloudModel scene(WeaponType weapon, boolean roof, CloudModel.Air air, double[][] covered, double[][] hard) {
        BlastSource source = BlastSource.of(weapon, false);
        double w = source.chargeKg(), h = roof ? 8 : 0;
        BlastTarget target = BlastTarget.of(GroundMaterial.STONE, false, roof ? 1 : 0,
                BreachModel.Material.ROCK.radius(w, BreachModel.UNTAMPED), Vec3.ZERO, new Vec3(0, -1, 0));
        GroundDust.Relief relief = (x, z) -> roof && Math.abs(x) <= 4 && Math.abs(z) <= 4 ? 0 : -h;
        double[][] heights = GroundDust.Ground.filled(0);
        for (int s = 0; s < GroundDust.SECTORS; s++) for (int b = 0; b < GroundDust.BANDS.length; b++) {
            double angle = 2 * Math.PI * (s + 0.5) / GroundDust.SECTORS, r = GroundDust.BANDS[b];
            heights[s][b] = relief.at(r * Math.cos(angle), r * Math.sin(angle));
        }
        var ground = new GroundDust.Ground(GroundDust.Ground.filled(0), covered, hard, heights, relief);
        return new CloudModel(source, air, target, 0, ground);
    }

    static double unitRay(double x, double y, double z, double sh, double sv, double distance) {
        double cdf = 0.5 * (SmokeGrid.erf((distance - x) / (Math.sqrt(2) * sh)) - SmokeGrid.erf(-x / (Math.sqrt(2) * sh)));
        return Math.max(0, cdf) * Math.exp(-0.5 * (Math.pow((1.6 - y) / sv, 2) + z * z / (sh * sh))) / (2 * Math.PI * sh * sv);
    }

    static double[] parts(CloudModel model, double h, double time, double distance) {
        double[] sum = new double[3];
        model.blobs(time, new CloudModel.Blobs() {
            public void blob(double x, double y, double z, double sh, double sv, double soot, double dust, double water, double k, double rise) {
                sum[0] += unitRay(x, y + h, z, sh, sv, distance) * (CloudModel.SOOT_K * soot + DustModel.K_MIX * dust);
            }
            public void ground(double x, double y, double z, double sh, double sv, double dust, double k) {
                sum[1] += unitRay(x, y + h, z, sh, sv, distance) * DustModel.K_MIX * dust;
            }
            public void crushed(double x, double y, double z, double sh, double sv, double dust, double k) {
                sum[2] += unitRay(x, y + h, z, sh, sv, distance) * DustModel.K_MIX * dust;
            }
            public void tube(double ax, double ay, double az, double sa, double ma, double bx, double by, double bz, double sb, double mb, double k, double rise) {
                double length = Math.sqrt(Math.pow(bx - ax, 2) + Math.pow(by - ay, 2) + Math.pow(bz - az, 2));
                for (int i = 0; i < 64; i++) {
                    double f = (i + 0.5) / 64, s = sa + (sb - sa) * f;
                    blob(ax + (bx - ax) * f, ay + (by - ay) * f, az + (bz - az) * f, s, s, 0, (ma + (mb - ma) * f) * length / 64, 0, k, rise);
                }
            }
        });
        return sum;
    }
}
