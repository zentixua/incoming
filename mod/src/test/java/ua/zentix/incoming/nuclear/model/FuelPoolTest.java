package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Топливо в воронке — один закон у огня на блоках сервера и у дыма и звука клиента. */
class FuelPoolTest {
    /** Что в баке — по маршевому двигателю паспорта: шахед — бензин, ракета — JP-10, без топлива — ничего. */
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
     * Шахед горит ~2,5 мин на 5 клетках, ракета — ~16 мин на 9 (площадь круга воронки, блок — метр); энергия — теплота
     * сгорания всего топлива воронки.
     */
    @Test
    void craterFuelBurnsMinutesOnItsCells() {
        FuelPool drone = WeaponType.DRONE.spec().pool(), missile = WeaponType.MISSILE.spec().pool();
        assertEquals(5, drone.cells());
        assertEquals(9, missile.cells());
        assertTrue(drone.seconds() > 120 && drone.seconds() < 180, "шахед: " + drone.seconds() + " с");
        assertTrue(missile.seconds() > 800 && missile.seconds() < 1050, "ракета: " + missile.seconds() + " с");
        for (FuelPool p : new FuelPool[]{drone, missile}) {
            assertEquals(p.kg() * p.fuel().heatJPerKg, p.watts() * p.seconds(), 1e-6 * p.kg() * p.fuel().heatJPerKg);
            assertEquals(FuelFireball.POOL_SHARE, p.kg() / (p == drone ? WeaponType.DRONE : WeaponType.MISSILE).spec().fuelAtImpact(), 1e-12);
        }
    }

    /**
     * Клетки гаснут по закону: в каждый миг горит столько клеток, сколько велит доля горящей площади (с точностью до
     * клетки, не меньше), первой гаснет край, клетка 0 горит весь срок; до 0,8 срока горят все.
     */
    @Test
    void cellsGoOutAsTheBurningAreaShrinks() {
        for (FuelPool p : new FuelPool[]{WeaponType.DRONE.spec().pool(), WeaponType.MISSILE.spec().pool()}) {
            int n = p.cells();
            assertEquals(p.seconds(), p.cellSeconds(0, n), 1e-9);
            for (int k = 1; k < n; k++) {
                assertTrue(p.cellSeconds(k, n) <= p.cellSeconds(k - 1, n), "клетка " + k);
                assertTrue(p.cellSeconds(k, n) >= 0.8 * p.seconds() - 1e-9, "клетка " + k + " гаснет до спада");
            }
            for (double t = 0; t < p.seconds(); t += p.seconds() / 997) {
                int burning = 0;
                for (int k = 0; k < n; k++) if (p.cellSeconds(k, n) > t) burning++;
                double want = p.share(t) * n;
                assertTrue(burning >= want - 1e-9 && burning < want + 1, String.format("на %.1f с горит %d, закон %.2f", t, burning, want));
            }
        }
    }

    /** Легло на меньшую площадь — слой толще: горит дольше во столько же раз и слабее, энергия та же. */
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
