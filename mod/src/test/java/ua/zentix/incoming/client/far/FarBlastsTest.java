package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.client.fx.layer.HeatPass;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.client.fx.smoke.WaterBlast;
import ua.zentix.incoming.client.fx.layer.WaterDecal;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.ChemicalFront;
import ua.zentix.incoming.nuclear.model.ShockOptics;
import ua.zentix.incoming.strike.WeaponType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Distant explosions: model-based clouds (including an underground bomb), billows behind a ridge. */
class FarBlastsTest {
    /** Cooling fireballs hidden by day do not occupy particle-light slots; physical world light remains. */
    @Test
    void hiddenCoolingBallsDoNotEnterParticleLights() throws ReflectiveOperationException {
        Class<?> eventType = Class.forName(FarBlasts.class.getName() + "$Event");
        var constructor = eventType.getDeclaredConstructor(net.minecraft.client.multiplayer.ClientLevel.class,
                BlastSource.class, CloudModel.class, double.class, double.class, double.class,
                ua.zentix.incoming.warhead.GroundMaterial.class, long.class, boolean.class, BlastSource.class);
        constructor.setAccessible(true);
        var collect = FarBlasts.class.getDeclaredMethod("light", eventType, FarView.class, FarSprites.class,
                ua.zentix.incoming.client.fx.layer.FxLights.class, double.class, double.class, double.class,
                double.class, double.class, double.class);
        collect.setAccessible(true);
        var balls = ua.zentix.incoming.client.fx.layer.FxLights.class.getDeclaredField("balls");
        balls.setAccessible(true);
        FarView day = new FarView(Vec3.ZERO, null, null, null, 0, 1000, 0.001, 1, 1, 1, null,
                Sight.CLEAR, 200, 130, 0, Sight.Air.at(0, Sight.CLEAR));
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE}) {
            CloudModel m = model(w);
            double t = m.burnSeconds;
            while (t < m.fireSeconds && CloudModel.glowShare(m.ballKelvin(t), 1) > 0) t += 0.005;
            double[] physical = new double[4];
            m.emittedLight(t, physical);
            assertTrue(t < m.fireSeconds && physical[3] > 0, "a hidden body has nonzero flux: " + w);
            Object event = constructor.newInstance(null, m.source, m, 0, 0, 0,
                    ua.zentix.incoming.warhead.GroundMaterial.DIRT, 1L, false, m.source);
            var lights = new ua.zentix.incoming.client.fx.layer.FxLights();
            // The actual FarBlasts.light path, one hundred hidden strikes: neither occupy a slot nor draw the body before returning.
            for (int i = 0; i < 100; i++) collect.invoke(null, event, day, null, lights, t * 20, 0, 0, 200, 200, 0);
            assertEquals(new org.joml.Matrix4f().zero(), balls.get(lights), "hidden fireballs do not enable FxLights: " + w);
        }
    }

    /** The visible front follows the law from the first frame rather than jumping to the column radius; no dark rim at the edge. */
    @Test
    void waterWaveStartsAtItsOwnFrontWithASoftEdge() {
        WaterBlast w = new WaterBlast(50, 0, 4, CloudModel.Air.STANDARD);
        float[] dark = new float[WaterDecal.SAMPLES], foam = new float[WaterDecal.SAMPLES];
        for (double t : new double[]{0.1, 1, 4, 10}) {
            double front = FarBlasts.waterProfile(w, t, dark, foam);
            assertEquals(w.waveRadius(t), front, 1e-9);
            if (t == 0.1) assertTrue(front < w.radius, "the first frame is not stretched to the column radius");
            assertEquals(0, dark[0], 0, "the front edge is transparent");
            for (float d : dark) assertTrue(d >= 0 && d <= 0.35, "no dense dark disc");
        }
    }

    /** Surface-explosion clouds follow the model in a standard atmosphere. */
    private static final List<CloudModel> MODELS = List.of(model(WeaponType.ROCKET), model(WeaponType.LOITER), model(WeaponType.DRONE),
            model(WeaponType.MISSILE));

    private static CloudModel model(WeaponType w) {
        return new CloudModel(BlastSource.of(w, false), CloudModel.Air.STANDARD);
    }

    /** Stabilized cloud top from Church (1969): 92.6·W^¼ m, W in kg TNT. */
    private static double church(double kg) {
        return 92.6 * Math.pow(kg, 0.25);
    }


    /**
     * The explosive-only cloud (Grad, Lancet) rises as in Church: its top is within 15 % of 92.6·W^¼; fuel
     * (Shahed, missile) lifts it higher, with tens of times more heat than the charge.
     */
    /**
     * The bomb uses the same cloud model: venting at 3 m gives a cloud from the escaped charge share (higher than Grad,
     * lower than the full charge at the surface), with ejecta-crater soil as dust; deeper, gases from the bore
     * (bore cross-section share) form a small cloud; entry and collapse dust stays unheated, lives and fades without rising.
     */
    @Test
    void bunkerCloudsComeFromTheModel() {
        ua.zentix.incoming.warhead.BuriedBlast shallow = new ua.zentix.incoming.warhead.BuriedBlast(2423, 3);
        BlastSource breach = new BlastSource(WeaponType.BUNKER, shallow.airCharge(BlastSource.hole()), 0, true, false);
        CloudModel m = CloudModel.buried(breach, CloudModel.Air.STANDARD, shallow, false);
        CloudModel whole = new CloudModel(new BlastSource(WeaponType.BUNKER, 2423, 0, true, false), CloudModel.Air.STANDARD);
        assertTrue(m.topZ < whole.topZ && m.topZ > model(WeaponType.ROCKET).topZ, "venting: top " + m.topZ + ", full charge " + whole.topZ);
        assertTrue(m.dust > whole.dust, "venting: ejecta-crater dust exceeds the surface-charge crater");
        ua.zentix.incoming.warhead.BuriedBlast deep = new ua.zentix.incoming.warhead.BuriedBlast(2423, 20);
        CloudModel vent = new CloudModel(new BlastSource(WeaponType.BUNKER, deep.airCharge(BlastSource.hole()), 0, true, true), CloudModel.Air.STANDARD);
        assertTrue(vent.topZ < m.topZ, "bore gases rise less than venting");
        BlastSource none = new BlastSource(WeaponType.BUNKER, 0, 0, true, true);
        // wind 3 m/s at the surface: in calm air, a ground cloud does not spread (Briggs follows wind travel)
        CloudModel dust = CloudModel.dust(none, new CloudModel.Air(CloudModel.N_STANDARD, 3, 0, 0.14, 3, 0.5), 160, 3.4);
        assertTrue(Double.isFinite(dust.topZ) && dust.topZ < 3.4, "unheated dust does not rise: " + dust.topZ);
        assertTrue(dust.life > 10 && dust.tau(600) < 0.5 * dust.tau(10), "collapse dust lives and thins: " + dust.tau(10) + " → " + dust.tau(600));
    }

    @Test
    void cloudsRiseByTheirHeat() {
        for (CloudModel m : MODELS) {
            double top = m.topZ + m.topR, church = church(m.source.chargeKg());
            if (m.source.fuelKg() > 0) assertTrue(top > 1.5 * church, m.source + ": top " + top + " versus Church " + church);
            else assertEquals(1, top / church, 0.15, m.source + ": top " + top + " versus Church " + church);
        }
    }

    /** Distant cloud color is its material as a thick layer: soot is darker than dust, droplets are white. */
    @Test
    void cloudColourIsItsMatter() {
        float soot = SmokePuffs.thick(1, 0, 0, 0.9f), dust = SmokePuffs.thick(0, 1, 0, SmokeGrid.dustAlbedo(0.6f)), drops = SmokePuffs.thick(0, 0, 1, 0.9f);
        assertTrue(soot < 0.45f, "soot " + soot);
        assertTrue(dust > soot + 0.2f, "dust " + dust + ", soot " + soot);
        assertTrue(drops > 0.9f, "droplets " + drops);
        // soil dust takes the soil color: a thick dust layer with screen color x is x (inverse reflectance)
        assertEquals(0.6f, SmokePuffs.thick(0, 1, 0, SmokeGrid.dustAlbedo(0.6f)), 0.01f);
    }

    @Test
    void puffsSinkBehindTheRidgeSmoothly() {
        assertEquals(1, FarBlasts.visible(5, 6, 0), "open site: everything is visible, including the ground-level part");
        assertEquals(0, FarBlasts.visible(20, 6, 40), 1e-12, "the whole billow is below the ridge");
        assertEquals(1, FarBlasts.visible(60, 6, 40), 1e-12, "the whole billow is above the ridge");
        assertEquals(0.5, FarBlasts.visible(40, 6, 40), 1e-12, "the ridge bisects the billow");
        // billow above ground, ridge at ground level: all visible; as the ridge grows, the billow disappears without a jump
        assertEquals(1, FarBlasts.visible(10, 6, 1e-9), 1e-12);
        double prev = 1;
        for (double line = 0; line < 30; line += 0.5) {
            double v = FarBlasts.visible(15, 6, line);
            assertTrue(v <= prev + 1e-12 && prev - v < 0.2, "smooth at " + line + ": " + prev + " → " + v);
            prev = v;
        }
    }

    @Test
    void cloudsGlowAtNightNotByDay() {
        // frame eye adaptation without fire at the eye (Sight.exposure), with noon and midnight sky light (getSkyDarken 0,2);
        // clouds 130 blocks above the site; the fireball is
        // the model detonation-products flash
        double day = Sight.adapted(1, 1), night = Sight.adapted(1, 0.17), h = 130;
        double[] light = new double[4];
        for (CloudModel m : MODELS) {
            double nightPeak = 0;
            // by day, cloud glow lasts only while detonation products emit (~3000 K); the fuel fireball (~1500 K) does not light them
            for (double t = 0; t < m.fireSeconds; t += 0.05) {
                m.emittedLight(t, light);
                nightPeak = Math.max(nightPeak, FarBlasts.cloudGlow(light[3], m.ballRadius(t), h, night));
                boolean lit = FarBlasts.SKY_PEAK * FarBlasts.cloudGlow(light[3], m.ballRadius(t), h, day) >= Sight.THRESHOLD;
                assertTrue(!lit || t < m.heBallSeconds, m.source + ": daytime glow at " + t + " s, flash until " + m.heBallSeconds);
            }
            assertTrue(FarBlasts.SKY_PEAK * nightPeak > Sight.THRESHOLD,
                    m.source + ": at night, cloud undersides above the explosion visibly brighten; the budget does not demand a half-white sky for every charge");
            assertTrue(nightPeak <= 1, "no brighter than white");
        }
    }

    @Test
    void hiddenBallGlowsNoBrighterThanItsVeil() {
        // frame eye adaptation without fire at the eye (Sight.exposure): noon and midnight
        double day = Sight.adapted(1, 1), night = Sight.adapted(1, 0.17);
        double[] light = new double[4];
        for (CloudModel m : MODELS) {
            double peak = 0;
            for (double t = 0; t < m.heBallSeconds; t += 0.01) {
                m.ballLight(t, light);
                peak = Math.max(peak, light[3]);
            }
            assertTrue(FarBlasts.behind(peak, day, 1) < 0.5, m.source + ": by day, glow beyond the edge is barely visible");
            assertEquals(Sight.VEIL_PEAK, FarBlasts.behind(peak, night, 1), 1e-9, m.source + ": at night, as strong as the veil, no brighter");
        }
        assertTrue(Sight.VEIL_PEAK < Sight.HALO, "a hidden fireball shines less than an open fireball glare");
        assertTrue(FarBlasts.behind(8, night, 0.01) < FarBlasts.behind(8, night, 1), "haze attenuates the glow");
    }

    @Test
    void raysCoverTheBallDiscFacingTheEye() {
        // eye above and to the side, as from a high-rise: ring points are a radius from the center, across the ray; one is horizontally to the side
        Vec3 eye = new Vec3(-30, 126, 60);
        double[] p = new double[3];
        for (int i = 0; i <= FarBlasts.RING_RAYS; i++) {
            FarBlasts.disc(eye, 0, 0, 0, 7, i, p);
            double off = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
            assertEquals(i == 0 ? 0 : 7, off, 1e-9, "point " + i);
            assertEquals(0, p[0] * -eye.x + p[1] * -eye.y + p[2] * -eye.z, 1e-6, "point " + i + " across the ray");
        }
        FarBlasts.disc(eye, 0, 0, 0, 7, 1, p);
        assertEquals(0, p[1], 1e-9, "the first is to the side, at center height");
        FarBlasts.disc(new Vec3(0, 100, 0), 0, 0, 0, 7, 2, p);
        assertTrue(Double.isFinite(p[0] + p[1] + p[2]), "eye directly above the fireball");
    }


    /**
     * Explosion dust takes the color of dry fine dust, not the soil block (F3: a thick layer with wet-soil color formed
     * a nearly black disc): soil uses the nuke dust-wall color (same ground, {@code DustModel.Surface.REFERENCE}),
     * and no material has dust darker than its block.
     */
    @org.junit.jupiter.api.Test
    void dustTakesTheColourOfDrySoilDust() {
        float[] dirt = FarBlasts.dustColour(ua.zentix.incoming.warhead.GroundMaterial.DIRT);
        for (int i = 0; i < 3; i++)
            org.junit.jupiter.api.Assertions.assertEquals(ua.zentix.incoming.client.nuclear.NukePall.DUST[i], dirt[i], 1e-6);
        for (ua.zentix.incoming.warhead.GroundMaterial g : ua.zentix.incoming.warhead.GroundMaterial.values()) {
            if (g.isWater()) continue; // no dust over water: spray
            float[] c = FarBlasts.dustColour(g);
            org.junit.jupiter.api.Assertions.assertTrue(c[0] + c[1] + c[2] >= g.r + g.g + g.b - 1e-6, g + ": dust darker than the block");
        }
    }

    /**
     * Contact follows what lies beneath the point, not its kind: a roof hit (column top at the point) or wall hit
     * (point on the face, column beyond is a wall higher than the point) gives 0; over a craft 100 m above grass, 100; unknown terrain, 0.
     */
    @Test
    void contactByWhatIsUnderThePoint() {
        // house x 0..9 up to height 80, street at 64
        Sightline.Heights town = (x, z) -> x >= 0 && x < 10 ? 80 : 64;
        assertEquals(0, FarBlasts.overSurface(town, new Vec3(5.5, 79.8, 5.5)), 1e-9, "roof: point in the top block");
        assertEquals(0, FarBlasts.overSurface(town, new Vec3(-0.001, 70, 5.5)), 1e-9, "wall: point on the outer face");
        assertEquals(100, FarBlasts.overSurface(town, new Vec3(-50.5, 164, 5.5)), 1e-9, "craft above the street");
        assertEquals(0, FarBlasts.overSurface((x, z) -> Double.NaN, new Vec3(0, 300, 0)), 1e-9, "unknown: surface burst");
    }

    /**
     * Visible conventional-explosion front (23.10 complaint: "no ring at all"): absent while inside the fireball;
     * outside, a sphere at the law radius ({@code ChemicalFront}), with refractive-index jump from its Kinney–Graham
     * pressure. Visible while the pressure jump exceeds the visibility threshold: edge displacement grows with
     * pressure at fixed geometry; a Shahed gives a viewer at 50–300 m at least a pixel of displacement, while a front
     * 2 km from the explosion (0.03 psi), viewed from 1 km away, falls below the pass threshold.
     */
    @Test
    void shockFrontIsSeenWhileItsJumpIs() {
        ChemicalFront f = ChemicalFront.of(50, true, 2500);
        double[] out = new double[3], dn = new double[4];
        double pixel = Math.toRadians(70) / 1080, ball = 6;
        assertFalse(FarBlasts.front(f, f.arrivalSeconds(5), ball, 0, out, dn), "inside the fireball");
        assertTrue(FarBlasts.front(f, f.arrivalSeconds(10), ball, 0, out, dn));
        assertEquals(10, out[0], 1e-6);
        assertEquals(0, out[2], 1e-12, "surface burst: hemisphere above ground");
        double jump = ShockOptics.indexJump(f.kpa(10), HeatPass.N0);
        for (double v : dn) assertEquals(jump, v, 1e-15);
        assertTrue(FarBlasts.front(f, f.arrivalSeconds(30), ball, 20, out, dn));
        assertEquals(-20.0 / 30, out[2], 1e-9, "airburst 20 m above ground");
        // displacement grows with pressure at fixed geometry
        double last = Double.MAX_VALUE;
        for (double r = 10; r < 2000; r *= 1.2) {
            double w = ShockOptics.most(r, ShockOptics.indexJump(f.kpa(r),
                    HeatPass.N0), 100, pixel) / pixel;
            assertTrue(w <= last, r + " m");
            last = w;
        }
        for (double view : new double[]{50, 100, 300}) {
            double r = 0.5 * view;
            assertTrue(FarBlasts.front(f, f.arrivalSeconds(r), ball, 0, out, dn));
            double w = ShockOptics.most(out[0], dn[0], view - r, pixel) / pixel;
            assertTrue(w > 0.3, "viewer at " + view + " m: " + w + " px");
        }
        assertTrue(FarBlasts.front(f, f.arrivalSeconds(2000), ball, 0, out, dn));
        assertTrue(ShockOptics.most(out[0], dn[0], 1000, pixel) / pixel < HeatPass.SHIFT_MIN, "2 km viewed from 1 km");
    }
}
