package ua.zentix.incoming.warhead;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponType;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shards according to Gurney and Mott ({@link FragmentModel}): numbers against the law and reference ranges. */
class FragmentModelTest {
    private static FragmentModel of(WeaponType w) {
        FragmentModel m = FragmentModel.of(w.spec());
        assertNotNull(m, w + ": no splinters");
        return m;
    }

    @Test
    void table() {
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.LOITER, WeaponType.MISSILE, WeaponType.ROCKET}) {
            FragmentModel m = of(w);
            System.out.printf(Locale.ROOT, "%s: V0 %.0f m/s, N %.0f, mean %.2f g, wall %.1f mm, d %.0f mm, L %.0f mm, reach %.0f m, hazard(mean) %.0f m, HFD %.0f m, dens30 %.2f/m2%n",
                    w, m.speed(), m.count(), m.meanMass() * 1000, m.wall() * 1000, m.inner() * 1000, m.length() * 1000, m.reach(),
                    FragmentModel.rangeTo(m.speed(), m.meanMass(), FragmentModel.HAZARD_J), m.hazardDistance(), m.peakDensity(30, FragmentModel.HAZARD_J));
        }
    }

    /** Garni: “Grad” (β 0,53) has 1–2 km/s, like high-explosive fragmentation shells. */
    @Test
    void gurneyGradInReferenceRange() {
        FragmentModel m = of(WeaponType.ROCKET);
        assertTrue(m.speed() > 1000 && m.speed() < 2000, "Grad: " + m.speed());
        // law: 2440·√(β/(1 + β/2)), β = 6,4/12
        double beta = 6.4 / 12;
        assertEquals(2440 * Math.sqrt(beta / (1 + beta / 2)), m.speed(), 1);
    }

    /**
     * Shahed: β = 4 (40 kg explosive, 10 kg body - spec rating) - thin body, speed closer to the Garni limit
     * √2·√(2E): faster 2 km/s, but not above the limit.
     */
    @Test
    void gurneyShahedBelowLimit() {
        FragmentModel m = of(WeaponType.DRONE);
        assertTrue(m.speed() > 2000, "shahed: " + m.speed());
        assertTrue(m.speed() < Math.sqrt(2) * FragmentModel.GURNEY_COMP_B, "shahed above the Garni limit: " + m.speed());
    }

    /** Mott: N₀ = M/(2μ), a sample of fragments gives the average mass 2μ and the fraction heavier than m - exp(−√(m/μ)). */
    @Test
    void mottCountAndMasses() {
        FragmentModel m = of(WeaponType.ROCKET);
        assertEquals(12 / (2 * m.mu()), m.count(), 1e-6);
        int n = 200_000, heavy = 0;
        double sum = 0, x = 4 * m.mu();
        for (int i = 0; i < n; i++) {
            double mass = m.fragment(42, i, new Vec3(0, -1, 0)).mass();
            sum += mass;
            if (mass > x) heavy++;
        }
        assertEquals(m.meanMass(), sum / n, 0.02 * m.meanMass());
        assertEquals(m.shareAbove(x), (double) heavy / n, 0.005);
        // “Grad” has thousands of fragments of several grams each
        assertTrue(m.count() > 2000 && m.count() < 20000, "fragments: " + m.count());
        assertTrue(m.meanMass() > 0.5e-3 && m.meanMass() < 10e-3, "average weight: " + m.meanMass());
    }

    /** Braking: the heavy one flies further than the light one; dangerous (79 J) medium fragment “Grad” - tens of meters, not kilometers. */
    @Test
    void dragRanges() {
        FragmentModel m = of(WeaponType.ROCKET);
        double light = FragmentModel.rangeTo(m.speed(), m.meanMass(), FragmentModel.HAZARD_J);
        double heavy = FragmentModel.rangeTo(m.speed(), 10 * m.meanMass(), FragmentModel.HAZARD_J);
        assertTrue(heavy > light, light + " / " + heavy);
        assertTrue(light > 20 && light < 300, "the average fragment is dangerous up to " + light);
        // speed along the way: e^(−x/L), time — L/V·(e^(s/L) − 1)
        double l = FragmentModel.dragLength(m.meanMass());
        assertEquals(m.speed() / Math.E, FragmentModel.speedAt(m.speed(), m.meanMass(), l), 1e-6);
        assertEquals(l / m.speed() * (Math.E - 1), FragmentModel.timeTo(m.speed(), m.meanMass(), l), 1e-9);
        // the farthest is the heaviest, and it is not further than the limit
        assertTrue(m.reach() > heavy && m.reach() <= FragmentModel.MAX_RANGE, "reach " + m.reach());
    }

    /**
     * Belt: with a sheer impact, almost all fragments fall within ±30° from the plane across the axis (scatter 10°, tilt
     * Taylor and warhead speed - several degrees), azimuth - uniform.
     */
    @Test
    void beltAcrossAxis() {
        FragmentModel m = of(WeaponType.ROCKET);
        int n = 50_000, inBelt = 0, east = 0;
        for (int i = 0; i < n; i++) {
            Vec3 d = m.fragment(7, i, new Vec3(0, -1, 0)).dir();
            if (Math.abs(Math.toDegrees(Math.asin(d.y))) < 30) inBelt++;
            if (d.x > 0) east++;
        }
        assertTrue(inBelt > 0.95 * n, "in the belt " + inBelt);
        assertEquals(0.5, (double) east / n, 0.01);
    }

    /** The dangerous density falls faster than 1/R² (braking), the dangerous distance is where it is equal to DDESB. */
    @Test
    void hazardDistanceByDdesb() {
        FragmentModel m = of(WeaponType.DRONE);
        double r = m.hazardDistance();
        assertEquals(FragmentModel.HAZARD_DENSITY, m.peakDensity(r, FragmentModel.HAZARD_J), 1e-3 * FragmentModel.HAZARD_DENSITY);
        assertTrue(m.peakDensity(20, FragmentModel.HAZARD_J) * 400 <= m.peakDensity(10, FragmentModel.HAZARD_J) * 100 + 1e-9);
        assertTrue(r > 50 && r < 1000, "shahed: " + r);
    }

    /** One seed - one shard (server and client). */
    @Test
    void sameSeedSameFragments() {
        FragmentModel m = of(WeaponType.DRONE);
        Vec3 axis = new Vec3(0.5, -0.8, 0.2).normalize();
        for (int i = 0; i < 100; i++) assertEquals(m.fragment(99, i, axis), m.fragment(99, i, axis));
    }

    /** There is no ground explosion or body - no fragments: the bomb explodes underground (S5), ICBM has a nuclear explosion. */
    @Test
    void noFragmentsWithoutCasing() {
        assertNull(FragmentModel.of(WeaponType.BUNKER.spec()));
        assertNull(FragmentModel.of(WeaponType.NUKE.spec()));
    }

    /** Damage: dangerous fragment 79 J - median of severe injury; death is no more than a fraction of what is vital. */
    @Test
    void injuryByEnergy() {
        assertEquals(0.5, FragmentModel.injury(FragmentModel.HAZARD_J), 1e-9);
        assertTrue(FragmentModel.injury(5) < 0.001);
        assertTrue(FragmentModel.death(1e6) <= FragmentModel.VITAL_SHARE + 1e-9);
        assertTrue(FragmentModel.death(30) < 0.01);
    }
}
