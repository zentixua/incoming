package ua.zentix.incoming.warhead;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Breakthrough according to Yang (SAND97-2426, adj. A) - numbers in the package reference book (ref/passport.md, “Breakthrough MOP according to Yang”). */
class PenetrationTest {
    private static final Penetration.Body MOP = Penetration.body(WeaponSpec.BUNKER.payload().article());
    /** Impact conditions of the present discharge from 12 km (spec). */
    private static final double REAL = WeaponSpec.BUNKER.payload().article().impact().speed();

    /** Body MOP according to spec: m/A 24,5–27,1 t/m², nose - revived ~6 calibers, N ≈ 0,99. */
    @Test
    void bodyFromPassport() {
        assertTrue(MOP.massPerArea() > 24_500 && MOP.massPerArea() < 27_100, "m/A " + MOP.massPerArea());
        assertEquals(0.99, MOP.nose(), 0.01);
    }

    /** On 511 m/s: stone (S 0,84) and concrete - 8,4–9,3 m; deep slate (0,67) - 6,7–7,4; gravel (4–6) – 40–67. */
    @Test
    void realImpactDepthsMatchTheReference() {
        double rock = Penetration.depth(MOP, 0.84, REAL);
        assertTrue(rock > 8.4 && rock < 9.3, "stone " + rock);
        double deep = Penetration.depth(MOP, 0.67, REAL);
        assertTrue(deep > 6.7 && deep < 7.4, "slate " + deep);
        double gravel = Penetration.depth(MOP, 5, REAL);
        assertTrue(gravel > 40 && gravel < 67, "gravel " + gravel);
    }

    /** The formula is direct: D = 0,000018·S·N·(m/A)^0,7·(V − 30,5) - ±5 % to the calculation according to the reference book. */
    @Test
    void highVelocityBranchIsYoung() {
        double expect = 0.000018 * 0.84 * MOP.nose() * Math.pow(MOP.massPerArea(), 0.7) * (300 - 30.5);
        assertEquals(expect, Penetration.depth(MOP, 0.84, 300), expect * 0.05);
    }

    /** Below 61 m/s is the second branch: D = 0,0008·S·N·(m/A)^0,7·ln(1 + 2,15·10⁻⁴·V²). */
    @Test
    void lowVelocityBranchIsLogarithmic() {
        double k = 12 * MOP.nose() * Math.pow(MOP.massPerArea(), 0.7);
        assertEquals(0.0008 * k * Math.log(1 + 2.15e-4 * 40 * 40), Penetration.depth(MOP, 12, 40), 1e-9);
    }

    /** No matter how you cut a homogeneous medium into pieces, the path is the same as in one layer. */
    @Test
    void uniformMediumIsOneLayerHoweverSliced() {
        double d = Penetration.depth(MOP, 0.84, 250);
        Penetration.Run run = new Penetration.Run(MOP, 250);
        double went = 0;
        for (int i = 0; i < 100 && !run.stopped(); i++) went += run.through(Penetration.Kind.ROCK, 0.84, 0.37);
        assertTrue(run.stopped());
        assertEquals(d, went, 1e-9);
    }

    /** The layer is thinner than the path: output speed V·√(1 − T/D); the next layer follows it. */
    @Test
    void layerExitSpeed() {
        double d = Penetration.depth(MOP, 12, 250);
        Penetration.Run run = new Penetration.Run(MOP, 250);
        run.through(Penetration.Kind.SOIL, 12, 10);
        assertEquals(250 * Math.sqrt(1 - 10 / d), run.speed(), 1e-9);
        double v = run.speed();
        double rock = Penetration.depth(MOP, 0.84, v);
        assertEquals(rock, run.through(Penetration.Kind.ROCK, 0.84, 100), 1e-9);
    }

    /** Soil behind concrete - with S/2 for six calibers of path (and through the void between the slabs): the stroke in it is shorter than without the slab. */
    @Test
    void soilUnderConcreteIsHarderForSixCalibres() {
        Penetration.Run slab = new Penetration.Run(MOP, 250);
        slab.through(Penetration.Kind.CONCRETE, 0.84, 1);
        slab.gap();
        double v = slab.speed();
        double soft = Penetration.depth(MOP, 6, v);
        Penetration.Run free = new Penetration.Run(MOP, v);
        double plain = 0;
        while (!free.stopped()) plain += free.through(Penetration.Kind.SOIL, 12, 1);
        double under = 0;
        while (!slab.stopped()) under += slab.through(Penetration.Kind.SOIL, 12, 1);
        assertTrue(under < plain, "behind the stove " + under + ", without her " + plain);
        // first 4,8 m - environment S/2: the speed there fades away as in a homogeneous S = 6
        Penetration.Run check = new Penetration.Run(MOP, 250);
        check.through(Penetration.Kind.CONCRETE, 0.84, 1);
        double vIn = check.speed();
        check.through(Penetration.Kind.SOIL, 12, 4.8);
        assertEquals(vIn * Math.sqrt(1 - 4.8 / Penetration.depth(MOP, 6, vIn)), check.speed(), 1e-9);
        assertTrue(soft > 4.8);
    }

    /** Impenetrable - stands up immediately; in water the speed fades out as e^(−ρ·CdA·x/(2m)). */
    @Test
    void stopAndFluid() {
        Penetration.Run stop = new Penetration.Run(MOP, 250);
        assertEquals(0, stop.through(Penetration.Kind.STOP, 0, 1));
        assertTrue(stop.stopped());
        Penetration.Run water = new Penetration.Run(MOP, 250);
        water.through(Penetration.Kind.FLUID, 1000, 10);
        assertEquals(250 * Math.exp(-1000 * MOP.cda() * 10 / (2 * MOP.mass())), water.speed(), 1e-9);
        assertFalse(water.stopped());
    }
}
