package ua.zentix.incoming.strike;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Полосы залпа ({@link WeaponSpec.Lanes}): у каждого снаряда своя, по зерну залпа, в паспортной ширине. */
class SalvoLanesTest {
    private static final WeaponSpec.Lanes LANES = new WeaponSpec.Lanes(32, 100, 300);

    private static double[] lanes(long seed, int total) {
        double[] out = new double[total];
        for (int i = 0; i < total; i++) out[i] = LANES.offset(seed, i, total);
        return out;
    }

    @Test
    void sameSeedSameLanes() {
        assertArrayEquals(lanes(42, 7), lanes(42, 7));
        assertFalse(Arrays.equals(lanes(42, 7), lanes(43, 7)), "другое зерно — те же полосы");
    }

    @Test
    void singleRoundFliesThePathItself() {
        assertEquals(0, LANES.offset(42, 0, 1));
        assertEquals(0, WeaponSpec.Lanes.NONE.offset(42, 0, 5));
    }

    /** Каждый в своей полосе: не шире паспорта, соседи не ближе половины полосы, волна — почти на всю ширину. */
    @Test
    void everyRoundHasItsOwnLaneWithinTheBand() {
        for (int total = 2; total <= 30; total++) {
            for (long seed = 0; seed < 50; seed++) {
                double[] l = lanes(seed * 7919, total);
                Arrays.sort(l);
                double slot = 2 * LANES.halfWidth() / total;
                assertTrue(l[0] > -LANES.halfWidth() && l[total - 1] < LANES.halfWidth(), "шире паспорта: " + Arrays.toString(l));
                for (int i = 1; i < total; i++) {
                    assertTrue(l[i] - l[i - 1] >= slot / 2 - 1e-9, "соседи ближе половины полосы: " + Arrays.toString(l));
                }
                assertTrue(l[total - 1] - l[0] >= LANES.halfWidth() * 2 * (total - 1.5) / total - 1e-9,
                        "волна уже, чем полосы: " + Arrays.toString(l));
            }
        }
    }

    /** Порядок полос перемешан: первые снаряды залпа идут не с одного края (иначе волна — косая цепочка). */
    @Test
    void lanesAreShuffledBySeed() {
        int firstOnEdge = 0, seeds = 200;
        for (long seed = 0; seed < seeds; seed++) {
            double[] l = lanes(seed, 5);
            if (l[0] < l[1] && l[1] < l[2] && l[2] < l[3] && l[3] < l[4]) firstOnEdge++;
        }
        assertTrue(firstOnEdge < seeds / 10, "по порядку слева направо в " + firstOnEdge + " залпах из " + seeds);
    }

    /**
     * Полосы — у оружия, что летит по маршруту (шахед, ракета, «Ланцет»); у «Града», B-2 и МБР маршрута нет. Выход в
     * полосу — дальше радиуса захвата точки (иначе снаряд брал бы её сразу), схождение — не короче геометрии атаки: заход
     * на цель и пике — уже на пути к своей точке прицеливания.
     */
    @Test
    void passportLanes() {
        for (WeaponType w : WeaponType.values()) {
            WeaponSpec spec = w.spec();
            WeaponSpec.Lanes lanes = spec.salvo().lanes();
            boolean routed = w == WeaponType.DRONE || w == WeaponType.MISSILE || w == WeaponType.LOITER;
            assertEquals(routed, lanes.halfWidth() > 0, w + ": полосы " + lanes);
            if (!routed) continue;
            assertTrue(lanes.exit() >= 2 * spec.capture(0), w + ": выход в полосу " + lanes.exit() + " при захвате " + spec.capture(0));
            assertTrue(lanes.converge() >= spec.airframe().attack(0).terminalRange(), w + ": схождение короче атаки");
            // полоса порядка расстояния между соседними снарядами: волна, а не цепочка и не веер
            double spacing = spec.salvo().gapMin() * spec.airframe().cruiseSpeed(0);
            assertTrue(lanes.halfWidth() <= spacing && 2 * lanes.halfWidth() >= spacing / 2,
                    w + ": полоса ±" + lanes.halfWidth() + " при шаге " + spacing);
        }
    }

    /** Длины полос растут с маршевой, как расстояние между снарядами ({@code Airframe.stretch}). */
    @Test
    void lanesScaleWithCruise() {
        WeaponSpec.Lanes k2 = LANES.scaled(2);
        assertEquals(64, k2.halfWidth());
        assertEquals(200, k2.exit());
        assertEquals(600, k2.converge());
        assertEquals(2 * LANES.offset(9, 2, 5), k2.offset(9, 2, 5), 1e-9);
    }
}
