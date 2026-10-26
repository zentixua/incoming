package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fuel in the crater is the same for the fire on the server blocks and for the smoke and sound of the client. */
class FuelPoolTest {
    /** What's in the tank - according to the propulsion engine spec: Shahed - gasoline, rocket - JP-10, without fuel - nothing. */
    @Test
    void fuelComesFromTheSustainer() {
        assertEquals(FuelPool.Fuel.PETROL, WeaponType.DRONE.spec().fuel());
        assertEquals(FuelPool.Fuel.JP10, WeaponType.MISSILE.spec().fuel());
        for (WeaponType w : new WeaponType[]{WeaponType.ROCKET, WeaponType.LOITER, WeaponType.BUNKER}) {
            assertEquals(FuelPool.Fuel.NONE, w.spec().fuel(), w.name());
            assertEquals(0, w.spec().pool().cells(), w.name());
        }
    }

    /**
     * Shahed burns ~2,5 min on 5 cells, rocket - ~16 min on 9 (circle area crater, block - meter); energy - heat
     *  of combustion of all fuel crater.
     */
    @Test
    void craterFuelBurnsMinutesOnItsCells() {
        FuelPool drone = WeaponType.DRONE.spec().pool(), missile = WeaponType.MISSILE.spec().pool();
        assertEquals(5, drone.cells());
        assertEquals(9, missile.cells());
        assertTrue(drone.seconds() > 120 && drone.seconds() < 180, "shahed: " + drone.seconds() + " s");
        assertTrue(missile.seconds() > 800 && missile.seconds() < 1050, "rocket: " + missile.seconds() + " s");
        for (FuelPool p : new FuelPool[]{drone, missile}) {
            assertEquals(p.kg() * p.fuel().heatJPerKg, p.watts() * p.seconds(), 1e-6 * p.kg() * p.fuel().heatJPerKg);
            assertEquals(FuelFireball.POOL_SHARE, p.kg() / (p == drone ? WeaponType.DRONE : WeaponType.MISSILE).spec().fuelAtImpact(), 1e-12);
        }
    }

    /**
     * Cells go out according to the law: at each moment as many cells burn as the proportion of the burning area dictates (with an accuracy of
     *  cells, no less), the edge goes out first, cell 0 burns for the entire period; until the 0,8 deadline, all are lit.
     */
    @Test
    void cellsGoOutAsTheBurningAreaShrinks() {
        for (FuelPool p : new FuelPool[]{WeaponType.DRONE.spec().pool(), WeaponType.MISSILE.spec().pool()}) {
            int n = p.cells();
            assertEquals(p.seconds(), p.cellSeconds(0, n), 1e-9);
            for (int k = 1; k < n; k++) {
                assertTrue(p.cellSeconds(k, n) <= p.cellSeconds(k - 1, n), "cell " + k);
                assertTrue(p.cellSeconds(k, n) >= 0.8 * p.seconds() - 1e-9, "cell " + k + " fades to decline");
            }
            for (double t = 0; t < p.seconds(); t += p.seconds() / 997) {
                int burning = 0;
                for (int k = 0; k < n; k++) if (p.cellSeconds(k, n) > t) burning++;
                double want = p.share(t) * n;
                assertTrue(burning >= want - 1e-9 && burning < want + 1, String.format("at %.1f s, %d cells are burning, law %.2f", t, burning, want));
            }
        }
    }

    /** It falls on a smaller area - the layer is thicker: it burns the same amount longer and weaker, the energy is the same. */
    @Test
    void thickerLayerBurnsLongerAndWeaker() {
        FuelPool p = WeaponType.MISSILE.spec().pool(), half = p.spread(4, 8);
        assertEquals(2 * p.seconds(), half.seconds(), 1e-9);
        assertEquals(p.watts() / 2, half.watts(), 1e-6);
        assertEquals(p.kg(), half.kg(), 0);
        assertEquals(p, p.spread(p.cells(), p.cells()));
        assertEquals(p, p.spread(p.cells() + 3, p.cells()));
    }
}
