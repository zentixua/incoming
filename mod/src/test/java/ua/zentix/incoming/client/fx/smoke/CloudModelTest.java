package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.util.Stability;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Облако обычного взрыва по законам: подъём MTT, Чёрч, шар топлива CCPS, вещество, цвет огня, жизнь. */
class CloudModelTest {
    private static CloudModel model(WeaponType w, CloudModel.Air air) {
        return new CloudModel(BlastSource.of(w, false), air);
    }

    /** Источник: заряд и топливо без огненной стадии (малое тепло и шар) — подъём почти как от точечного термика. */
    private static CloudModel point(double tntKg, CloudModel.Air air) {
        return new CloudModel(new BlastSource(WeaponType.MISSILE, tntKg, 0, true, false), air);
    }

    /**
     * Термик MTT в устойчивом воздухе: наибольшая высота центра — (8F/(α³N²))^¼ (F = 3B/(4π)), за π/N секунд, — в двух
     * стратификациях (стандартная атмосфера и ночная инверсия). Конечный начальный шар — тот же термик от «виртуального
     * истока» на R₀/α ниже (точечный источник дорос бы до R₀ на высоте R₀/α); время вершины от размера шара не зависит.
     */
    @Test
    void riseFollowsMttInTwoStratifications() {
        for (double n : new double[]{CloudModel.N_STANDARD, Stability.brunt(Stability.F)}) {
            CloudModel.Air air = new CloudModel.Air(n, 0, 0, 0.14, 3, 0.5);
            for (double kg : new double[]{6.4, 50, 450}) {
                CloudModel m = point(kg, air);
                double f = 3 * m.buoyancy / (4 * Math.PI);
                // точечный термик той же плавучести, начавший у виртуального истока: радиус шара / α ниже его центра
                double zMax = Math.pow(8 * f / (Math.pow(CloudModel.ALPHA, 3) * n * n), 0.25) - m.radius(0) / CloudModel.ALPHA + m.height(0);
                assertEquals(1, m.topZ / zMax, 0.03, "N " + n + ", " + kg + " кг: вершина " + m.topZ + " при MTT " + zMax);
                assertEquals(Math.PI / n, m.topSeconds, 0.08 * Math.PI / n, "N " + n + ", " + kg + " кг: время вершины");
                // радиус термика у вершины — α·высоты (MTT: dR/dz = α), с поправкой на начальный шар
                assertEquals(CloudModel.ALPHA, (m.topR - m.radius(0)) / (m.topZ - m.height(0)), 0.03);
            }
        }
    }

    /** Ночью (инверсия) облако ниже, чем днём: z ∝ N^(−½). */
    @Test
    void stableNightKeepsTheCloudLower() {
        CloudModel day = model(WeaponType.DRONE, CloudModel.Air.of(Stability.B, 0.9, 0.45, 0.5)), night = model(WeaponType.DRONE, CloudModel.Air.of(Stability.F, 0.9, 0.45, 0.5));
        assertTrue(night.topZ < day.topZ, "ночью " + night.topZ + ", днём " + day.topZ);
        assertEquals(Math.sqrt(Stability.brunt(Stability.B) / Stability.brunt(Stability.F)), night.topZ / day.topZ, 0.1);
    }

    /** Облако взрывчатки в стандартной атмосфере — верх по Чёрчу (92,6·W^¼ м), закон W^¼ — сам из MTT. */
    @Test
    void highExplosiveCloudFollowsChurch() {
        for (double kg : new double[]{3, 6.4, 50, 150, 450}) {
            CloudModel m = point(kg, CloudModel.Air.STANDARD);
            double church = 92.6 * Math.pow(kg, 0.25);
            assertEquals(1, (m.topZ + m.topR) / church, 0.1, kg + " кг: верх " + (m.topZ + m.topR) + " при Чёрче " + church);
        }
        // тепло взрывчатки в облаке — треть энергии ТНТ: остальное уносят волна и свет
        assertEquals(0.31, CloudModel.HE_HEAT / 4.184e6, 0.03);
    }

    /**
     * Шар топлива по CCPS: D = 5,8·M^⅓ м, горит 0,45·M^⅓ с; и начальный объём облака — тот, в котором тепло держит
     * температуру шара, — того же размера (тот же шар с другой стороны: из тепла и чёрного тела, а не из CCPS).
     */
    @Test
    void fuelFireballFollowsCcps() {
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE}) {
            CloudModel m = model(w, CloudModel.Air.STANDARD);
            double mass = BlastSource.of(w, false).fuelKg();
            assertEquals(5.8 * Math.cbrt(mass), m.fuelBall, 1e-9);
            assertEquals(0.45 * Math.cbrt(mass), m.fuelBallSeconds, 1e-9);
            assertEquals(1, m.radius(0) / (0.5 * m.fuelBall), 0.15, w + ": начальный радиус " + m.radius(0) + " при шаре CCPS " + 0.5 * m.fuelBall);
            assertEquals(CloudModel.FUEL_BALL_K, m.ballKelvin(0.5 * (m.heBallSeconds + m.fuelBallSeconds)), 1e-9, "после вспышки — шар топлива");
        }
        // шахед: 30 кг бензина — шар 18 м на 1,4 с (ref/passport.md: 12–21 м, 1,0–1,7 с)
        CloudModel drone = model(WeaponType.DRONE, CloudModel.Air.STANDARD);
        assertEquals(18, drone.fuelBall, 0.5);
        assertEquals(1.4, drone.fuelBallSeconds, 0.05);
        // у «Града» и «Ланцета» топлива нет — нет и шара топлива
        assertEquals(0, model(WeaponType.ROCKET, CloudModel.Air.STANDARD).fuelBall);
        assertEquals(0, model(WeaponType.LOITER, CloudModel.Air.STANDARD).fuelBall);
    }

    /**
     * Цвет огня — чёрное тело: шар топлива (~1480 K по 275 кВт/м²) — оранжевый (красный > зелёный > синий, синего почти
     * нет), вспышка продуктов детонации (3000 K) — тёплая белая и в сотни раз ярче: тот же {@link BlackBody}, что у ядерки.
     */
    @Test
    void fireColourIsBlackBody() {
        assertEquals(1484, CloudModel.FUEL_BALL_K, 1);
        double[] fuel = new double[4], flash = new double[4];
        BlackBody.light(CloudModel.FUEL_BALL_K, fuel);
        BlackBody.light(CloudModel.HE_BALL_HOT, flash);
        assertTrue(fuel[0] > fuel[1] && fuel[1] > 10 * fuel[2], "шар топлива оранжевый");
        assertTrue(flash[2] > 0.1 * flash[0], "вспышка — тёплая белая");
        assertTrue(flash[3] > 100 * fuel[3], "вспышка ярче шара топлива в сотни раз");
        // температура облака падает: огненная стадия, потом термик остывает подсосом воздуха
        CloudModel m = model(WeaponType.DRONE, CloudModel.Air.STANDARD);
        double prev = m.kelvin(0);
        for (double t = m.fireSeconds; t < 120; t += 1) {
            double k = m.kelvin(t);
            assertTrue(k <= prev + 1e-9, "t " + t);
            prev = k;
        }
        assertTrue(m.kelvin(60) < 300, "через минуту облако почти воздуха");
    }

    /**
     * Вещество: сажа — по составу (тротил чёрный, гексоген — без свободного углерода; у ракеты сажа только от топлива),
     * пыль — из воронки, растёт с зарядом.
     */
    @Test
    void matterFromCompositionAndCrater() {
        assertEquals(0.185, BlastSource.Explosive.TNT.freeCarbon(), 0.002);
        assertEquals(0, BlastSource.Explosive.RDX.freeCarbon(), 1e-12);
        assertEquals(-0.74, BlastSource.Explosive.TNT.oxygenBalance(), 0.005);
        assertEquals(-0.216, BlastSource.Explosive.RDX.oxygenBalance(), 0.002);
        CloudModel rocket = model(WeaponType.ROCKET, CloudModel.Air.STANDARD), missile = model(WeaponType.MISSILE, CloudModel.Air.STANDARD);
        assertEquals(6.4 * 0.185 * CloudModel.SOOT_SURVIVES, rocket.soot, 1e-3);
        assertEquals(WeaponSpec.MISSILE.fuelAtImpact() * BlastSource.Fuel.JP10.sootYield, missile.soot, 1e-9, "гексоген сажи не даёт");
        assertTrue(missile.dust > 10 * rocket.dust, "пыль — объём воронки ∝ W");
    }

    /** Жизнь — пока толща поперёк середины больше порога видимости; облако тоньше со временем. */
    @Test
    void lifeEndsWhenTheCloudIsThin() {
        for (WeaponType w : new WeaponType[]{WeaponType.ROCKET, WeaponType.LOITER, WeaponType.DRONE, WeaponType.MISSILE}) {
            CloudModel m = model(w, CloudModel.Air.of(Stability.B, 0.9, 0.45, 0.5));
            if (m.life < CloudModel.LIFE_MAX) assertEquals(CloudModel.SEEN, m.tau(m.life), 1e-3, w.toString());
            double prev = m.tau(1);
            for (double t = 2; t < m.life; t *= 1.5) {
                assertTrue(m.tau(t) <= prev + 1e-9, w + " t " + t);
                prev = m.tau(t);
            }
            assertTrue(m.life > 60, w + ": облако видно минуты");
        }
    }

    /** Клубы сохраняют вещество: сажа в облаке — вся, пыль в облаке, ножке и у земли — не больше всей. */
    @Test
    void blobsCarryTheMatter() {
        CloudModel m = model(WeaponType.DRONE, CloudModel.Air.STANDARD);
        for (double t : new double[]{0.2, 3, 30, 300}) {
            List<double[]> blobs = new ArrayList<>();
            m.blobs(t, (x, y, z, sh, sv, soot, dust, water, k, rise) -> blobs.add(new double[]{y, sh, sv, soot, dust, k}));
            double soot = 0, dust = 0;
            for (double[] b : blobs) {
                soot += b[3];
                dust += b[4];
                assertTrue(b[1] > 0 && b[2] > 0, "σ > 0");
                assertTrue(b[5] >= CloudModel.T_AIR_K - 1e-9, "не холоднее воздуха");
            }
            assertEquals(m.soot * m.fade(t), soot, 1e-9, "t " + t);
            assertTrue(dust <= m.dust + 1e-9, "t " + t);
            assertTrue(blobs.get(0)[0] > 0, "облако над землёй");
        }
        // в огненной стадии — два горячих шара: продукты детонации (горячее) и топливо
        List<Double> kelvin = new ArrayList<>();
        m.blobs(0.5, (x, y, z, sh, sv, soot, dust, water, k, rise) -> kelvin.add(k));
        assertEquals(m.heBallKelvin(0.5), kelvin.get(0), 1);
        assertEquals(CloudModel.FUEL_BALL_K, kelvin.get(1), 1);
        // свет шара — поток обоих тел: пока светят продукты детонации — ярче шара топлива в одиночку
        double[] both = new double[4], late = new double[4];
        m.ballLight(0.1, both);
        m.ballLight(1.0, late);
        assertTrue(both[3] > late[3], "вспышка ярче");
        assertEquals(BlackBody.luminance(CloudModel.FUEL_BALL_K), late[3], 1e-9, "после вспышки — шар топлива");
    }

    /** Ветер сносит облако по ходу и сильнее на высоте; облако конденсации — только во влажном воздухе. */
    @Test
    void windAndHumidity() {
        CloudModel calm = model(WeaponType.DRONE, CloudModel.Air.of(Stability.B, 0, 0, 0.5)), windy = model(WeaponType.DRONE, CloudModel.Air.of(Stability.B, 2, 0, 0.5));
        assertEquals(0, calm.driftX(120), 1e-9);
        assertTrue(windy.driftX(120) > 2 * 120, "на высоте ветер сильнее, чем на 10 м");
        assertEquals(0, model(WeaponType.MISSILE, CloudModel.Air.of(Stability.B, 1, 0, 0.1)).condensation(), "сухо — облака конденсации нет");
        assertTrue(model(WeaponType.MISSILE, CloudModel.Air.of(Stability.B, 1, 0, 0.8)).condensation() > 10, "влажно — есть");
    }
}
