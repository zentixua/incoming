package ua.zentix.incoming.warhead;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Underground explosion at reduced depth (TM 5-855-1, estimates in {@link BuriedBlast}). */
class BuriedBlastTest {
    private static final double MOP = 2423, HOLE = Math.PI * 0.8 * 0.8 / 4;

    /** For 2,4 t: cavity ~3,4 m, crushing ~10 m, cracks ~27 m (“tens of meters”). */
    @Test
    void radiiScaleWithCubeRoot() {
        BuriedBlast b = new BuriedBlast(MOP, 20);
        assertEquals(0.25 * Math.cbrt(MOP), b.cavity(), 1e-9);
        assertEquals(3.36, b.cavity(), 0.01);
        assertEquals(3 * b.cavity(), b.crush(), 1e-9);
        assertTrue(b.fracture() > 20 && b.fracture() < 40, "cracks " + b.fracture());
    }

    /** Deep: outward - only gases along the passage, the proportion of the passage cross-section in the cavity wall (~0,35%); failure if the pipe reaches. */
    @Test
    void deepChargeVentsThroughTheHoleOnly() {
        BuriedBlast b = new BuriedBlast(MOP, 15);
        assertFalse(b.breaches());
        double share = HOLE / (4 * Math.PI * b.cavity() * b.cavity());
        assertEquals(share, b.airShare(HOLE), 1e-12);
        assertEquals(0.0035, share, 0.0005);
        assertTrue(b.subsides(), "collapse pipe 5 cavity radii (16,8 m) above the charge by 15 m - failure");
        assertFalse(new BuriedBlast(MOP, 30).subsides());
        assertEquals(0, b.craterRadius(ua.zentix.incoming.nuclear.model.CraterModel.Soil.DRY));
    }

    /** Fine: crater of release, into the air - the smaller the smaller; the entire charge is at the surface itself. */
    @Test
    void shallowChargeBreaches() {
        BuriedBlast b = new BuriedBlast(MOP, 3);
        assertTrue(b.breaches());
        assertFalse(b.subsides());
        var dry = ua.zentix.incoming.nuclear.model.CraterModel.Soil.DRY;
        double r0 = ua.zentix.incoming.nuclear.model.CraterModel.chargeRadius(MOP, dry);
        assertEquals(r0, new BuriedBlast(MOP, 0).craterRadius(dry), 1e-9, "near the surface - ground charge crater");
        double half = new BuriedBlast(MOP, 0.5 * Math.cbrt(MOP)).craterRadius(dry);
        assertTrue(half > 1.4 * r0, "about λ 0,5 - wider than ground: " + half);
        assertTrue(new BuriedBlast(MOP, 0.95 * Math.cbrt(MOP)).craterRadius(dry) < 0.4 * r0, "at the breakout boundary: fades out");
        assertEquals(1 - 3 / Math.cbrt(MOP), b.airShare(HOLE), 1e-9);
        assertEquals(1, new BuriedBlast(MOP, 0).airShare(HOLE), 1e-12);
        assertTrue(new BuriedBlast(MOP, 12).airShare(HOLE) > new BuriedBlast(MOP, 14).airShare(HOLE));
    }

    /** Collapse of the vault: the rock of the cavity volume falls to the height of the pipe - tens of MJ, kg of TNT - a few to tens. */
    @Test
    void collapseEnergy() {
        double v = 4.0 / 3 * Math.PI * Math.pow(new BuriedBlast(MOP, 20).cavity(), 3);
        double tnt = BuriedBlast.collapseTnt(v);
        assertEquals(v * 2400 * 9.80665 * 5 * new BuriedBlast(MOP, 20).cavity() / 4.184e6, tnt, 1e-6);
        assertTrue(tnt > 1 && tnt < 100, "collapse " + tnt);
    }
}
