package ua.zentix.incoming.warhead;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.FuelFireball;
import ua.zentix.incoming.nuclear.model.PulseIgnition;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Обход шара топлива ({@code Craters.Ignition}) на ровной земле кончается сам, а не пределом {@link Craters#FIREBALL_CELLS}
 * (ревью #467): шар ракеты всплывает на 26 м, и без потолка обхода над землёй — небо — клеток с импульсом выше
 * {@link PulseIgnition#FLOOR} больше предела, обход, идущий от большего импульса, кончался в небе на 10 кал/см², и листва у
 * края шара на земле не загоралась. Под потолком (на ровной земле — слой воздуха над ней) клеток меньше предела.
 */
class FireballWalkTest {
    /** Клеток с импульсом не ниже FLOOR над ровной землёй: до {@code layers} слоёв воздуха (0 — до неба, 120 м). */
    private static int cells(WeaponType w, double wind, int layers) {
        FuelFireball.Sweep ball = FuelFireball.Sweep.of(FuelFireball.ballKg(w.spec().fuelAtImpact()), 0, wind, 0, 0.15);
        int n = 0, top = layers > 0 ? layers : 120, r = 80;
        for (int x = -r; x <= r; x++)
            for (int z = -r; z <= r; z++)
                for (int y = 0; y < top; y++) if (ball.exposure(x + 0.5, y + 0.5, z + 0.5) >= PulseIgnition.FLOOR) n++;
        return n;
    }

    @Test
    void walkOnFlatGroundEndsBeforeTheCap() {
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE}) {
            for (double wind : new double[]{0, 5, 10}) {
                int under = cells(w, wind, 1);
                assertTrue(under < Craters.FIREBALL_CELLS / 4, w + ", ветер " + wind + ": под потолком " + under + " клеток");
            }
        }
        // без потолка небо над всплывшим шаром ракеты больше предела — так обход и обрывался
        int sky = cells(WeaponType.MISSILE, 0, 0);
        assertTrue(sky > Craters.FIREBALL_CELLS, "небо над шаром ракеты — " + sky + " клеток");
    }
}
