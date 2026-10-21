package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.util.Stability;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.client.fx.plume.Motor;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Облако старта: когда продукты — облако (длина Мортона), сколько их, и подъём — термик MTT. */
class LaunchCloudTest {
    private static Motor motor(WeaponType w) {
        return ClientWeaponSpec.of(w).airframe(false).far().stage(FlightPhase.BOOST).motor();
    }

    /**
     * Длина Мортона M^¾/F^½ из паспорта двигателя: у «Града» (10,5 кг/с, 2 000 м/с, ~50 МВт в воздухе) — ~40 м, у
     * ускорителя шахеда — ~24 м, у ускорителя ракеты — ~41 м, у первой ступени МБР — ~100 м.
     */
    @Test
    void mortonLengthFromThePassport() {
        Motor grad = motor(WeaponType.ROCKET);
        double m = grad.exhaust() * grad.velocity() / 1.225, f = 9.80665 * grad.heatFlow() / (1.225 * 1005 * 288.15);
        assertEquals(Math.pow(m, 0.75) / Math.sqrt(f), LaunchCloud.mortonLength(grad), 1e-9);
        assertEquals(40, LaunchCloud.mortonLength(grad), 3);
        assertEquals(24, LaunchCloud.mortonLength(motor(WeaponType.DRONE)), 3);
        assertEquals(41, LaunchCloud.mortonLength(motor(WeaponType.MISSILE)), 4);
        assertEquals(104, LaunchCloud.mortonLength(motor(WeaponType.NUKE)), 10);
    }

    /**
     * Струя касается земли полушириной: отвесно вниз — на высоте сопла, вбок — на высоте/0,094, вверх — никогда; у сопла
     * в 2,5 м над землёй под 30° «Град» отдаёт облаку свои продукты, пока не пролетит ~35 м.
     */
    @Test
    void jetHitsTheGroundWithinItsMortonLength() {
        assertEquals(10, LaunchCloud.reach(10, -1), 1e-9);
        assertEquals(10 / LaunchCloud.SPREAD, LaunchCloud.reach(10, 0), 1e-6);
        assertEquals(Double.POSITIVE_INFINITY, LaunchCloud.reach(10, 0.5));
        Motor grad = motor(WeaponType.ROCKET);
        double sin = Math.sin(Math.toRadians(30)), cos = Math.cos(Math.toRadians(30));
        double fed = -1;
        for (double s = 0; s < 200; s += 0.5) {
            if (!LaunchCloud.impinges(grad, 2.5 + s * sin, -sin)) {
                fed = s;
                break;
            }
        }
        double l0 = 2.5 / (sin + LaunchCloud.SPREAD * cos), k = sin + LaunchCloud.SPREAD * cos;
        // ℓ = (2,5 + s·sin)/k ≤ L_M
        assertEquals((LaunchCloud.mortonLength(grad) * k - 2.5) / sin, fed, 0.6);
        assertTrue(fed > 20 && fed < 50, "путь подачи " + fed + " (ℓ у трубы " + l0 + ")");
        // шахед с рельса в 2 м под 15° — половину пути ускорителя
        assertTrue(LaunchCloud.impinges(motor(WeaponType.DRONE), 2, -Math.sin(Math.toRadians(15))));
        assertFalse(LaunchCloud.impinges(motor(WeaponType.DRONE), 20, -Math.sin(Math.toRadians(15))));
    }

    /**
     * Подача: тепло — тепло двигателя в воздухе × время, сажа и капли — та же толща, что у продуктов, пыль — по рыхлости;
     * струя мимо земли не подаёт.
     */
    @Test
    void feedTakesTheProductsOfTheBurn() {
        Motor grad = motor(WeaponType.ROCKET);
        LaunchCloud c = new LaunchCloud(CloudModel.Air.STANDARD, 64, 1, 0.4f, 0.3f, 0.2f, 0);
        double sin = Math.sin(Math.toRadians(30)), cos = Math.cos(Math.toRadians(30));
        assertTrue(c.feed(grad, 0.05, 0, 66.5, 0, -cos, -sin, 0));
        assertTrue(c.feed(grad, 0.05, 1, 67, 0, -cos, -sin, 0));
        assertFalse(c.feed(grad, 0.05, 0, 66.5, 0, 0, 1, 0), "струя вверх");
        assertEquals(grad.heatFlow() * 0.1, c.heat(), 1e-6 * c.heat());
        double ext = grad.smoke().extinction * grad.smokeFlow() * 0.1;
        assertEquals(ext, CloudModel.SOOT_K * c.soot() + CloudModel.DROP_K * c.water(), 1e-9 * ext);
        assertEquals(LaunchCloud.DUST_PER_KG * grad.exhaust() * 0.1, c.dust(), 1e-9);
        LaunchCloud stone = new LaunchCloud(CloudModel.Air.STANDARD, 64, 0, 0.5f, 0.5f, 0.5f, 0);
        stone.feed(grad, 0.05, 0, 66.5, 0, -cos, -sin, 0);
        assertEquals(0, stone.dust(), 0, "камень не пылит");
        assertTrue(c.life() > 10, "облако залпа видно " + c.life() + " с");
    }

    /**
     * Термик облака — MTT: у точечного источника верх центра (8F/(α³N²))^¼ при F = 3B/(4π), за ~π/N; от малого начального
     * радиуса (шар, где тепло держит 600 K: 6 м против 150 м подъёма) — в пределах 15 % (начальный термик без импульса
     * выходит на автомодельный подъём не сразу); ночью (N больше) — ниже как N^−½.
     */
    @Test
    void cloudRisesAsAnMttThermal() {
        double heat = 2e8, b = Thermal.buoyancy(heat), f = 3 * b / (4 * Math.PI), a = CloudModel.ALPHA;
        for (double n : new double[]{CloudModel.N_STANDARD, Stability.brunt(Stability.F)}) {
            CloudModel.Air air = new CloudModel.Air(n, 0, 0, 0.14, 3, 0.5);
            Thermal t = new Thermal(b, CloudModel.G * (1 - CloudModel.T_AIR / 600), 0.5, air);
            double top = Math.pow(8 * f / (a * a * a * n * n), 0.25);
            assertEquals(top, t.topZ, 0.15 * top, "N = " + n);
            assertEquals(a * top, t.topR, 0.25 * a * top);
            assertEquals(Math.PI / n, t.topSeconds, 0.35 * Math.PI / n);
        }
    }
}
