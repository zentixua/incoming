package ua.zentix.incoming.warhead;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Breakdown of an obstacle by an applied charge - FM 5-250 (1992), files 3-6 and B-7, table. 3-4, 3-5, B-3, fig. 3-15. */
class BreachModelTest {
    /** Package M112 - 1,25 pounds C4, C4 - 1,34 TNT (FM 5-250, table 1-2); lb - 0,4536 kg. */
    private static final double M112_TNT_KG = 1.25 * 1.34 * 0.45359237;
    private static final double FOOT = 0.3048;

    /**
     * The metric table gives the same charges as the table. 3-5 FM (packages C4 for reinforced concrete, calculated according to the form 3-6 in
     *  feet): wall 7 feet, charge without driving above the ground (C = 1,8) - 200 packages; 5 feet - 85. The steps of the tables in meters
     *  and feet coincide with 1,5 m (5 feet) - we check there.
     */
    @Test
    void metricTableMatchesFieldTable() {
        BreachModel.Material rc = BreachModel.Material.REINFORCED;
        assertEquals(200, rc.charge(7 * FOOT, BreachModel.UNTAMPED) / M112_TNT_KG, 200 * 0.03);
        assertEquals(85, rc.charge(5 * FOOT, BreachModel.UNTAMPED) / M112_TNT_KG, 85 * 0.03);
        // same wall, charge filled (C = 1): 111 packages; at the center of mass (C = 1, R - half thickness): 21
        assertEquals(111, rc.charge(7 * FOOT, BreachModel.TAMPED) / M112_TNT_KG, 111 * 0.03);
        assertEquals(21, rc.charge(3.5 * FOOT, BreachModel.TAMPED) / M112_TNT_KG, 21 * 0.05);
    }

    /** The breach radius is an inversion of the formula: the charge required by FM for this radius breaks through it (inside the steps). */
    @Test
    void radiusInvertsCharge() {
        for (BreachModel.Material m : BreachModel.Material.values()) {
            for (double r : new double[]{0.2, 0.6, 1.2, 1.7, 2.5, 4}) {
                for (double c : new double[]{BreachModel.TAMPED, BreachModel.UNTAMPED}) {
                    double k = m.charge(r, c);
                    assertEquals(r, m.radius(k, c), 1e-9, m + " R " + r + " C " + c);
                }
            }
        }
    }

    /** More charge - no less penetration; the one who is scored penetrates more than the one who is not scored. */
    @Test
    void radiusGrowsWithCharge() {
        for (BreachModel.Material m : BreachModel.Material.values()) {
            double last = 0;
            for (double k = 0.1; k < 5000; k *= 1.05) {
                double r = m.radius(k, BreachModel.UNTAMPED);
                assertTrue(r >= last, m + " " + k + " kg: " + r + " < " + last);
                assertTrue(m.radius(k, BreachModel.TAMPED) > r, m + " " + k + " kg");
                assertTrue(m.charge(r, BreachModel.UNTAMPED) <= k * (1 + 1e-9), m + " " + k + " kg: for R " + r + " need more");
                last = r;
            }
        }
    }

    /**
     * Mod warheads on rock (stone, ordinary masonry) without driving: a rocket (150 kg) penetrates a meter, not three (R ≈ 2,7 m);
     * shahed (50 kg) - a meter, not two (≈ 1,75 m); “Grad” (6,4 kg) does not penetrate the meter (≈ 0,77 m). The earth is loose: the rocket
     *  pierces ~4,2 m above the void.
     */
    @Test
    void warheadsInRock() {
        BreachModel.Material rock = BreachModel.Material.ROCK;
        double missile = rock.radius(WeaponType.MISSILE.spec().charge(), BreachModel.UNTAMPED);
        assertEquals(2.68, missile, 0.01);
        double drone = rock.radius(WeaponType.DRONE.spec().charge(), BreachModel.UNTAMPED);
        assertEquals(1.75, drone, 0.01);
        double grad = rock.radius(WeaponType.ROCKET.spec().charge(), BreachModel.UNTAMPED);
        assertTrue(grad < 1, "\"Grad\" breaks through a meter of stone: " + grad);
        assertEquals(4.21, BreachModel.Material.EARTH.radius(WeaponType.MISSILE.spec().charge(), BreachModel.UNTAMPED), 0.01);
        assertTrue(BreachModel.Material.REINFORCED.radius(150, BreachModel.UNTAMPED) < missile);
    }

    /**
     * Two independent laws agree: a tamped charge in soil penetrates to a void as far as the depth from which an
     * underground explosion still ejects soil (TM 5-855-1, λ = 1 m/kg^⅓, {@link BuriedBlast}), within 10%.
     */
    @Test
    void tampedEarthBreachMatchesBuriedBlast() {
        for (double k : new double[]{50, 150, 2423}) {
            double r = BreachModel.Material.EARTH.radius(k, BreachModel.TAMPED);
            double breach = BuriedBlast.BREACH * Math.cbrt(k);
            assertEquals(breach, r, 0.1 * breach, k + " kg");
        }
    }

    /** Table steps B-3: "0,3 m or less" at the cliff includes 0,3; in weak masonry “less than 1,5 m” - 1,5 is already the second. */
    @Test
    void bandEdges() {
        assertEquals(14.09, BreachModel.Material.ROCK.factor(0.3), 0);
        assertEquals(7.69, BreachModel.Material.ROCK.factor(0.31), 0);
        assertEquals(6.41, BreachModel.Material.ROCK.factor(1.0), 0);
        assertEquals(5.13, BreachModel.Material.POOR.factor(1.49), 0);
        assertEquals(4.64, BreachModel.Material.POOR.factor(1.5), 0);
        assertEquals(1.12, BreachModel.Material.EARTH.factor(9), 0);
    }

    /** Charge ball: 150 kg cast TNT - 0,28 m. */
    @Test
    void chargeRadius() {
        assertEquals(0.28, BreachModel.chargeRadius(150), 0.005);
    }
}
