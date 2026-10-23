package ua.zentix.incoming.warhead;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Пробой преграды приложенным зарядом — FM 5-250 (1992), ф-лы 3-6 и B-7, табл. 3-4, 3-5, B-3, рис. 3-15. */
class BreachModelTest {
    /** Пакет M112 — 1,25 фунта C4, C4 — 1,34 ТНТ (FM 5-250, табл. 1-2); фунт — 0,4536 кг. */
    private static final double M112_TNT_KG = 1.25 * 1.34 * 0.45359237;
    private static final double FOOT = 0.3048;

    /**
     * Метрическая таблица даёт те же заряды, что табл. 3-5 FM (пакеты C4 на железобетон, рассчитанные по ф-ле 3-6 в
     * футах): стена 7 футов, заряд без забивки над землёй (C = 1,8) — 200 пакетов; 5 футов — 85. Ступени таблиц в метрах
     * и футах совпадают от 1,5 м (5 футов) — там и сверяем.
     */
    @Test
    void metricTableMatchesFieldTable() {
        BreachModel.Material rc = BreachModel.Material.REINFORCED;
        assertEquals(200, rc.charge(7 * FOOT, BreachModel.UNTAMPED) / M112_TNT_KG, 200 * 0.03);
        assertEquals(85, rc.charge(5 * FOOT, BreachModel.UNTAMPED) / M112_TNT_KG, 85 * 0.03);
        // та же стена, заряд забит (C = 1): 111 пакетов; в центре массы (C = 1, R — половина толщины): 21
        assertEquals(111, rc.charge(7 * FOOT, BreachModel.TAMPED) / M112_TNT_KG, 111 * 0.03);
        assertEquals(21, rc.charge(3.5 * FOOT, BreachModel.TAMPED) / M112_TNT_KG, 21 * 0.05);
    }

    /** Радиус пробоя — обращение формулы: заряд, нужный по FM на этот радиус, его и пробивает (внутри ступеней). */
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

    /** Больше заряд — пробивает не меньше; забитый пробивает больше незабитого. */
    @Test
    void radiusGrowsWithCharge() {
        for (BreachModel.Material m : BreachModel.Material.values()) {
            double last = 0;
            for (double k = 0.1; k < 5000; k *= 1.05) {
                double r = m.radius(k, BreachModel.UNTAMPED);
                assertTrue(r >= last, m + " " + k + " кг: " + r + " < " + last);
                assertTrue(m.radius(k, BreachModel.TAMPED) > r, m + " " + k + " кг");
                assertTrue(m.charge(r, BreachModel.UNTAMPED) <= k * (1 + 1e-9), m + " " + k + " кг: на R " + r + " нужно больше");
                last = r;
            }
        }
    }

    /**
     * Боевые части мода по скале (камень, обычная кладка) без забивки: ракета (150 кг) пробивает метр, не три (R ≈ 2,7 м);
     * шахед (50 кг) — метр, не два (≈ 1,75 м); «Град» (6,4 кг) метра не пробивает (≈ 0,77 м). Земля — рыхлая: ракета
     * пробивает ~4,2 м её над пустотой.
     */
    @Test
    void warheadsInRock() {
        BreachModel.Material rock = BreachModel.Material.ROCK;
        double missile = rock.radius(WeaponType.MISSILE.spec().charge(), BreachModel.UNTAMPED);
        assertEquals(2.68, missile, 0.01);
        double drone = rock.radius(WeaponType.DRONE.spec().charge(), BreachModel.UNTAMPED);
        assertEquals(1.75, drone, 0.01);
        double grad = rock.radius(WeaponType.ROCKET.spec().charge(), BreachModel.UNTAMPED);
        assertTrue(grad < 1, "«Град» пробивает метр камня: " + grad);
        assertEquals(4.21, BreachModel.Material.EARTH.radius(WeaponType.MISSILE.spec().charge(), BreachModel.UNTAMPED), 0.01);
        assertTrue(BreachModel.Material.REINFORCED.radius(150, BreachModel.UNTAMPED) < missile);
    }

    /**
     * Два независимых закона сходятся: забитый заряд в земле пробивает до пустоты столько же, сколько глубина, с которой
     * подземный взрыв ещё выбрасывает грунт наружу (TM 5-855-1, λ = 1 м/кг^⅓, {@link BuriedBlast}) — в пределах 10 %.
     */
    @Test
    void tampedEarthBreachMatchesBuriedBlast() {
        for (double k : new double[]{50, 150, 2423}) {
            double r = BreachModel.Material.EARTH.radius(k, BreachModel.TAMPED);
            double breach = BuriedBlast.BREACH * Math.cbrt(k);
            assertEquals(breach, r, 0.1 * breach, k + " кг");
        }
    }

    /** Ступени табл. B-3: «0,3 м и меньше» у скалы включает 0,3; у слабой кладки «меньше 1,5 м» — 1,5 уже вторая. */
    @Test
    void bandEdges() {
        assertEquals(14.09, BreachModel.Material.ROCK.factor(0.3), 0);
        assertEquals(7.69, BreachModel.Material.ROCK.factor(0.31), 0);
        assertEquals(6.41, BreachModel.Material.ROCK.factor(1.0), 0);
        assertEquals(5.13, BreachModel.Material.POOR.factor(1.49), 0);
        assertEquals(4.64, BreachModel.Material.POOR.factor(1.5), 0);
        assertEquals(1.12, BreachModel.Material.EARTH.factor(9), 0);
    }

    /** Шар заряда: 150 кг литого ТНТ — 0,28 м. */
    @Test
    void chargeRadius() {
        assertEquals(0.28, BreachModel.chargeRadius(150), 0.005);
    }
}
