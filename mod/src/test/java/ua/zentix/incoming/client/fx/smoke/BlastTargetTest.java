package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.nuclear.model.CraterModel;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.util.Stability;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.BreachModel;
import ua.zentix.incoming.warhead.GroundMaterial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explosion family by target and target substance ({@link BlastTarget}): by material and support, not by the name "roof". */
class BlastTargetTest {
    private static final double SHAHED = 50, ROCKET = 6.4, MISSILE = 150;

    /** Water - water; higher than the charge digs the crater, there is air; a slab above a void is a slab; on a support - by connectivity. */
    @Test
    void familyFollowsMaterialAndSupport() {
        assertEquals(BlastTarget.Family.WATER, BlastTarget.family(GroundMaterial.WATER, false, 0, true));
        assertEquals(BlastTarget.Family.AIR, BlastTarget.family(GroundMaterial.DIRT, true, 0, false));
        assertEquals(BlastTarget.Family.SLAB, BlastTarget.family(GroundMaterial.WOOD, false, 1, true));
        assertEquals(BlastTarget.Family.SLAB, BlastTarget.family(GroundMaterial.STONE, false, 1, true), "stone bridge deck - slab");
        assertEquals(BlastTarget.Family.HARD, BlastTarget.family(GroundMaterial.STONE, false, 0, true), "the same stone on a support - solid");
        assertEquals(BlastTarget.Family.SOIL, BlastTarget.family(GroundMaterial.DIRT, true, 0, true));
        assertEquals(BlastTarget.Family.HARD, BlastTarget.family(GroundMaterial.WOOD, false, 0, true), "boards on the ground are not soil");
        // anti-aircraft missile explosion at 30 m and Shahed on the ground
        assertEquals(BlastTarget.Family.AIR, BlastTarget.of(GroundMaterial.STONE, 12, 30).family());
        assertEquals(BlastTarget.Family.SOIL, BlastTarget.of(GroundMaterial.DIRT, SHAHED, 0).family());
        // without the world - according to the substance of the material: loose - soil, cohesive - solid
        for (GroundMaterial m : GroundMaterial.values()) {
            BlastTarget t = BlastTarget.of(m, SHAHED, 0);
            BlastTarget.Family want = m.isWater() ? BlastTarget.Family.WATER : t.matter().loose ? BlastTarget.Family.SOIL : BlastTarget.Family.HARD;
            assertEquals(want, t.family(), m.name());
        }
    }

    /** Slab breach - √(R² − t²) at the breach radius R (BreachGameTests); A slab that is not pierced is not a slab. */
    @Test
    void slabHoleFollowsTheBreachLaw() {
        double r = BreachModel.Material.POOR.radius(SHAHED, BreachModel.UNTAMPED);
        BlastTarget t = BlastTarget.of(GroundMaterial.WOOD, false, 1, r);
        assertEquals(BlastTarget.Family.SLAB, t.family());
        assertEquals(Math.sqrt(r * r - 1), t.hole(), 1e-9);
        assertEquals(Math.PI * t.hole() * t.hole() * 1 * BlastTarget.Matter.WOOD.density, t.mass(SHAHED), 1e-6);
        // with “Grad” a meter of boards is not broken (R < 1): the support is solid, not a slab
        assertTrue(BreachModel.Material.POOR.radius(ROCKET, BreachModel.UNTAMPED) < 1);
        assertEquals(BlastTarget.Family.HARD, BlastTarget.of(GroundMaterial.WOOD, false, 0, 0).family());
    }

    /**
     * The mass of the destroyed - according to the material and soil of the crater: concrete - a bowl of rock × 2500 kg/m³, and not dry soil × 1600 (previously
     *  for any purpose); near the ground - the same number; near the water and in the air - zero.
     */
    @Test
    void crushedMassFollowsTheMaterial() {
        double soil = CraterModel.chargeVolume(SHAHED, CraterModel.Soil.DRY) * CloudModel.SOIL_DENSITY;
        assertEquals(soil, BlastTarget.SOIL.mass(SHAHED), 1e-9);
        assertEquals(soil, BlastTarget.of(GroundMaterial.DIRT, SHAHED, 0).mass(SHAHED), 1e-9);
        double rock = CraterModel.chargeVolume(SHAHED, CraterModel.Soil.ROCK) * BlastTarget.Matter.STONE.density;
        assertEquals(rock, BlastTarget.of(GroundMaterial.STONE, SHAHED, 0).mass(SHAHED), 1e-9);
        assertTrue(rock < 0.6 * soil, "in concrete the crater is smaller: " + rock + " against " + soil);
        assertEquals(0, BlastTarget.of(GroundMaterial.WATER, SHAHED, 0).mass(SHAHED));
        assertEquals(0, BlastTarget.of(GroundMaterial.DIRT, SHAHED, 50).mass(SHAHED));
    }

    /** A cloud without information about the target is the same (bare loose soil): the dust of the cloud and cold dust are the same. */
    @Test
    void plainCloudIsTheSoilCloud() {
        BlastSource s = BlastSource.of(WeaponType.DRONE, false);
        CloudModel plain = new CloudModel(s, CloudModel.Air.STANDARD, false, 0, GroundDust.Ground.BARE);
        CloudModel soil = new CloudModel(s, CloudModel.Air.STANDARD, BlastTarget.of(GroundMaterial.DIRT, s.chargeKg(), 0), 0, GroundDust.Ground.BARE);
        assertEquals(plain.dust, soil.dust, 1e-9);
        assertEquals(plain.crushed.total, soil.crushed.total, 1e-9);
        assertEquals(plain.life, soil.life, 1e-9);
        BlastTarget stone = BlastTarget.of(GroundMaterial.STONE, s.chargeKg(), 0);
        CloudModel concrete = new CloudModel(s, CloudModel.Air.STANDARD, stone, 0, GroundDust.Ground.BARE);
        assertEquals(stone.dust(s.chargeKg()) * (1 - CloudModel.CRUSHED_HOT_SHARE), concrete.dust, 1e-9, "concrete crushing dust - according to its size, density and fineness");
        assertEquals(stone.mass(s.chargeKg()), stone.dust(s.chargeKg()) + stone.ballistic(s.chargeKg()), 1e-9, "dust + emission - destroyed");
    }

    /**
     * Coherent trifle - by measuring the contact explosion on concrete (Costa 2025: PM10 0–2,4 g/kg, order 1 g/kg): PM10 mixtures
     *  baskets on kilogram of destroyed - {@link BlastTarget#PM10_CONTACT}; The water has no dust, the loose one has the same share.
     */
    @Test
    void crushedMatterFinesFollowTheContactBlastMeasurement() {
        assertEquals(CloudModel.DUST_LOFTED, BlastTarget.Matter.DIRT.fines, 1e-12);
        for (BlastTarget.Matter m : BlastTarget.Matter.values()) {
            if (m == BlastTarget.Matter.WATER) assertEquals(0, m.fines);
            else if (!m.loose && m != BlastTarget.Matter.WOOD) {
                double pm10 = m.fines * DustModel.PM10_SHARE;
                assertEquals(BlastTarget.PM10_CONTACT, pm10, 1e-12, m.name());
                assertTrue(pm10 > 0 && pm10 <= 0.0024, m.name() + ": PM10 within the limits of experiments, " + pm10);
            }
            assertTrue(m.fines < 0.5, m.name() + ": dust is a smaller part of the destroyed");
        }
    }

    /**
     * Shahed into a meter-long concrete roof, wind 6 m/s, class C: the cold dust of the breach is not a wall. The densest billows through
     *  through the middle to 10 s is less than τ 3 (the houses behind it are visible, as in camera recordings after arrivals: haze, not a curtain), and
     *  at 30 s it is still visible. With the same share of 0,1 the same billows on 10 s - τ ≈ 35, with the share of 0,005 - ≈ 2,7, at the skirt (wider, and from the
     *  side of impact - only dust crater, {@link BlastTarget#SLAB_FRONT}) - ≈ 0,5.
     */
    @Test
    void roofBreachDustOfAShahedIsAHazeNotAWall() {
        var hard = new GroundDust.Ground(GroundDust.Ground.filled(0), GroundDust.Ground.filled(0),
                GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), (x, z) -> 0);
        var air = new CloudModel.Air(CloudModel.N_STANDARD, 6, 0, 0.1, (int) Stability.C, 0.5);
        double breach = BreachModel.Material.ROCK.radius(SHAHED, BreachModel.UNTAMPED);
        var m = new CloudModel(BlastSource.of(WeaponType.DRONE, false), air, BlastTarget.of(GroundMaterial.STONE, false, 1, breach), 0, hard);
        assertTrue(m.crushed.tau(10) < 3, "τ at 10 s " + m.crushed.tau(10));
        assertTrue(m.crushed.tau(30) > CloudModel.SEEN, "τ at 30 s " + m.crushed.tau(30));
    }

    /** Dust color: concrete and stone are lighter than soil, slate is darker, wood is ocher (red is more blue). */
    @Test
    void dustColourFollowsTheMatter() {
        BlastTarget.Matter soil = BlastTarget.Matter.DIRT, stone = BlastTarget.Matter.STONE;
        assertTrue(stone.r + stone.g + stone.b > soil.r + soil.g + soil.b, "concrete is lighter than soil");
        assertTrue(BlastTarget.Matter.DEEPSLATE.g < soil.g, "slate is darker");
        assertTrue(BlastTarget.Matter.WOOD.r > BlastTarget.Matter.WOOD.b + 0.2f, "ocher sawdust");
        assertTrue(BlastTarget.Matter.SAND.extinction() > BlastTarget.Matter.DIRT.extinction(), "sand is finer - the curtain is thicker by kg");
        assertEquals(0, BlastTarget.Matter.STONE.extinction(), "the messenger has no veil");
    }

    /**
     * The rear spalling of a broken slab is an open Gurney “sandwich”: in Shahed, in a meter of boards ~29 m/s, in a meter of stone - slower;
     *  in non-slabs - 0.
     */
    @Test
    void spallSpeedFollowsGurney() {
        double rw = BreachModel.Material.POOR.radius(SHAHED, BreachModel.UNTAMPED);
        BlastTarget wood = BlastTarget.of(GroundMaterial.WOOD, false, 1, rw);
        assertEquals(29, wood.plugSpeed(SHAHED), 3);
        double rs = BreachModel.Material.ROCK.radius(SHAHED, BreachModel.UNTAMPED);
        BlastTarget stone = BlastTarget.of(GroundMaterial.STONE, false, 1, rs);
        assertTrue(stone.plugSpeed(SHAHED) < wood.plugSpeed(SHAHED) / 2, "heavy stone plug slower");
        assertEquals(0, BlastTarget.of(GroundMaterial.DIRT, MISSILE, 0).plugSpeed(MISSILE));
    }
}
