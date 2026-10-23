package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.util.Stability;
import org.junit.jupiter.api.Test;
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
        CloudModel day = model(WeaponType.DRONE, air(Stability.B, 0.9, 0.45, 0.5)), night = model(WeaponType.DRONE, air(Stability.F, 0.9, 0.45, 0.5));
        assertTrue(night.topZ < day.topZ, "ночью " + night.topZ + ", днём " + day.topZ);
        assertEquals(Math.sqrt(CloudModel.N_STANDARD / Stability.brunt(Stability.F)), night.topZ / day.topZ, 0.1);
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
            double mass = ua.zentix.incoming.nuclear.model.FuelFireball.ballKg(BlastSource.of(w, false).fuelKg());
            assertEquals(5.8 * Math.cbrt(mass), m.fuelBall, 1e-9);
            assertEquals(0.45 * Math.cbrt(mass), m.fuelBallSeconds, 1e-9);
            assertEquals(1, m.radius(0) / (0.5 * m.fuelBall), 0.15, w + ": начальный радиус " + m.radius(0) + " при шаре CCPS " + 0.5 * m.fuelBall);
            assertEquals(CloudModel.FUEL_BALL_K, m.ballKelvin(0.5 * (m.heBallSeconds + m.fuelBallSeconds)), 1e-9, "после вспышки — шар топлива");
        }
        // шахед: 30 кг бензина, шаром — 22,5 кг (четверть — в воронке): шар 16,4 м на 1,27 с (ref/passport.md: 12–21 м, 1,0–1,7 с)
        CloudModel drone = model(WeaponType.DRONE, CloudModel.Air.STANDARD);
        assertEquals(16.4, drone.fuelBall, 0.2);
        assertEquals(1.27, drone.fuelBallSeconds, 0.02);
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
        // сажа облака — топливо шара; сажа воронки уходит шлейфом её пожара
        assertEquals(ua.zentix.incoming.nuclear.model.FuelFireball.ballKg(WeaponSpec.MISSILE.fuelAtImpact()) * BlastSource.Fuel.JP10.sootYield,
                missile.soot, 1e-9, "гексоген сажи не даёт");
        assertTrue(missile.dust > 10 * rocket.dust, "пыль — объём воронки ∝ W");
    }

    /** Жизнь — пока толща поперёк середины больше порога видимости; облако тоньше со временем. */
    @Test
    void lifeEndsWhenTheCloudIsThin() {
        for (WeaponType w : new WeaponType[]{WeaponType.ROCKET, WeaponType.LOITER, WeaponType.DRONE, WeaponType.MISSILE}) {
            CloudModel m = model(w, air(Stability.B, 0.9, 0.45, 0.5));
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
            // пыль — ослабление в кг смеси: воронка, слой волны и сорванное под шаром, не больше поднятого
            assertTrue(dust <= m.dust + m.ground.total + m.ground.underBall(t) + 1e-9, "t " + t);
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

    /**
     * Ножка — сплошная трубка пыли от слоя у земли до низа облака (куски стыкуются конец в начало), и несёт ровно ту пыль, что
     * подсос забрал из слоя волны и ещё не донёс до облака (прежде — клубы через 2σ, кадры F5: бусы).
     */
    @Test
    void stemIsOneTubeWithItsDust() {
        CloudModel m = new CloudModel(BlastSource.of(WeaponType.MISSILE, false), air(Stability.D, 3, 0, 0.5));
        for (double t : new double[]{10, 20, 40}) {
            List<double[]> pieces = new ArrayList<>();
            m.blobs(t, new CloudModel.Blobs() {
                @Override
                public void blob(double x, double y, double z, double sh, double sv, double soot, double dust, double water, double kelvin, double rise) {}

                @Override
                public void tube(double ax, double ay, double az, double sa, double dustA, double bx, double by, double bz, double sb, double dustB,
                                 double kelvin, double rise) {
                    pieces.add(new double[]{ax, ay, az, bx, by, bz, dustA, dustB});
                }
            });
            assertEquals(CloudModel.STEM_PIECES, pieces.size(), "ножка через " + t + " с");
            double foot = pieces.get(0)[1] + m.hob;
            assertTrue(foot > 0 && foot <= Math.max(0.35 * m.sigmaH(t), 1) + 1e-9, "низ ножки — на её σ над землёй: " + foot);
            double dust = 0;
            for (int i = 0; i < pieces.size(); i++) {
                double[] p = pieces.get(i);
                if (i > 0) for (int a = 0; a < 3; a++) assertEquals(pieces.get(i - 1)[3 + a], p[a], 1e-9, "кусок " + i + " — с конца прошлого");
                double len = Math.sqrt(Math.pow(p[3] - p[0], 2) + Math.pow(p[4] - p[1], 2) + Math.pow(p[5] - p[2], 2));
                dust += 0.5 * (p[6] + p[7]) * len;
            }
            double stem = (m.ground.drawn(t) - m.ground.drawn(m.stemEntered(t))) * m.fade(t);
            assertEquals(stem, dust, 1e-9 * Math.max(1, stem), "пыль ножки через " + t + " с");
        }
    }

    /** Ветер сносит облако по ходу и сильнее на высоте; облако конденсации — только во влажном воздухе. */
    @Test
    void windAndHumidity() {
        CloudModel calm = model(WeaponType.DRONE, air(Stability.B, 0, 0, 0.5)), windy = model(WeaponType.DRONE, air(Stability.B, 2, 0, 0.5));
        assertEquals(0, calm.driftX(120), 1e-9);
        assertTrue(windy.driftX(120) > 2 * 120, "на высоте ветер сильнее, чем на 10 м");
        assertEquals(0, model(WeaponType.MISSILE, air(Stability.B, 1, 0, 0.1)).condensation(), "сухо — облака конденсации нет");
        assertTrue(model(WeaponType.MISSILE, air(Stability.B, 1, 0, 0.8)).condensation() > 10, "влажно — есть");
    }

    /** Воздух места взрыва классом устойчивости и ветром на 10 м. */
    private static CloudModel.Air air(double stability, double windX, double windZ, double humidity) {
        return new CloudModel.Air(Stability.brunt(stability), windX, windZ, Stability.alpha(stability), (int) Math.round(stability), humidity);
    }

    /**
     * Огненный шар не гаснет ступенькой (3.5.0: свет шара топлива — полный до конца горения и в следующий миг 0): за тик
     * радиус меняется не больше чем на пятую наибольшего (шар топлива растёт к D за пятую горения), температура — не больше чем на 300 K (вспышка продуктов детонации остывает за доли секунды), а свет, пока он больше
     * тысячной наибольшего, не падает за тик больше чем вдесятеро (остывание по Планку быстрое: 1700 → 1500 K — в восемь раз, но без обрыва в ноль); у точки
     * Дрейпера свет — тысячные доли. Так у всех снарядов, днём и ночью.
     */
    @Test
    void fireballFadesWithoutSteps() {
        double[] light = new double[4];
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE, WeaponType.ROCKET, WeaponType.LOITER}) {
            for (double day : new double[]{1, 0}) {
                CloudModel m = model(w, air(day > 0.5 ? Stability.B : Stability.F, 0.9, 0.45, 0.5));
                assertTrue(m.fireSeconds > m.burnSeconds, w + ": остывает после горения");
                double rMax = 0, yMax = 0;
                for (double t = 0.05; t < m.fireSeconds + 0.5; t += 0.05) {
                    m.ballLight(t, light);
                    rMax = Math.max(rMax, m.ballRadius(t));
                    yMax = Math.max(yMax, light[3]);
                }
                double prevR = m.ballRadius(0.05), prevK = m.ballKelvin(0.05), prevY;
                m.ballLight(0.05, light);
                prevY = light[3];
                for (double t = 0.1; t < m.fireSeconds + 0.5; t += 0.05) {
                    m.ballLight(t, light);
                    double r = m.ballRadius(t), y = light[3], k = m.ballKelvin(t);
                    String at = w + " день " + day + " на " + String.format(java.util.Locale.ROOT, "%.2f", t) + " с: ";
                    // радиус — пока шар заметно светит (у точки Дрейпера его нет на экране: тело огня там уже дым)
                    if (prevY > 1e-3 * yMax) assertTrue(Math.abs(r - prevR) <= 0.2 * rMax, at + "радиус " + prevR + " → " + r);
                    if (k > 0 && prevK > 0) assertTrue(Math.abs(k - prevK) <= 300, at + "температура " + prevK + " → " + k);
                    if (prevY > 1e-3 * yMax) assertTrue(y >= 0.1 * prevY, at + "свет " + prevY + " → " + y);
                    prevR = r;
                    prevY = y;
                    prevK = k;
                }
                m.ballLight(m.fireSeconds - 0.05, light);
                assertTrue(light[3] < 1e-3 * yMax, w + ": у точки Дрейпера свет — тысячные, " + light[3] / yMax);
            }
        }
        // тело огня тает в дым и дым проступает одной долей по температуре
        assertEquals(1, CloudModel.glowShare(CloudModel.FUEL_BALL_K), 1e-12);
        assertEquals(0, CloudModel.glowShare(CloudModel.DRAPER_K), 1e-12);
    }

    /**
     * Огонь виден, пока его свечение не меньше света неба на его дыме ({@link CloudModel#glowShare(double, double)}): шар бомбы
     * (прорыв ~1,3 т) днём к 3 с — уже дым (шлагбаум RV2, бомба в 150 м: шар был сплошь оранжевым), ночью — ещё огонь;
     * в горении — огонь и днём; доля плавная и не больше доли по одной температуре.
     */
    @Test
    void fireballFadesIntoSmokeByWhatTheEyeSees() {
        CloudModel m = new CloudModel(new BlastSource(WeaponType.BUNKER, 1340, 0, true, false), CloudModel.Air.STANDARD);
        // свет неба: днём 1, ночью — ambient^ADAPT (FarView.skyLuminance)
        double day = 1, night = Math.pow(0.17, ua.zentix.incoming.client.far.Sight.ADAPT);
        assertTrue(CloudModel.glowShare(m.ballKelvin(1), day) > 0.95, "в горении — огонь и днём");
        assertTrue(CloudModel.glowShare(m.ballKelvin(3), day) < 0.15, "днём к 3 с — дым: " + CloudModel.glowShare(m.ballKelvin(3), day));
        assertTrue(CloudModel.glowShare(m.ballKelvin(3), night) > 0.9, "ночью к 3 с — огонь");
        // ночью газ у точки Дрейпера — не чернее, чем по одной температуре наполовину (ком сажи поверх шара ночью, F6)
        assertTrue(CloudModel.glowShare(1000, night) > 0.4 * CloudModel.glowShare(1000), "ночью тёплый газ ещё светится");
        // вещество внутри светящегося шара тает в дым той же долей, что шар: днём в первую секунду ножка пыли внутри шара
        // ложилась тёмным комом поверх него (кадры F6, бомба в 150 м)
        for (double t : new double[]{0.1, 0.5, 1, 2}) {
            double ball = m.ballRadius(t), bh = m.ballHeight(t), cx = m.driftX(t), cz = m.driftZ(t), share = CloudModel.glowShare(m.ballKelvin(t), day);
            m.blobs(t, (x, y, z, sh, sv, soot, dust, water, kelvin, rise) -> {
                double dx = x - cx, dy = y - bh, dz = z - cz;
                if (dx * dx + dy * dy + dz * dz < 0.6 * ball * ball && soot + dust > 0)
                    assertTrue(CloudModel.glowShare(kelvin, day) >= share - 1e-9, "в шаре на " + t + " с — огонь, не дым: " + kelvin);
            });
        }
        // огонь, что ярче света дыма за порогом контраста, — весь огонь: ни доли его вещества дымом (тонны пыли шара)
        assertEquals(1, CloudModel.glowShare(m.ballKelvin(0.5), day), 0, "шар в горении днём — ровно огонь");
        assertEquals(0, CloudModel.glowShare(900, day), 0, "тёплый газ днём — ровно дым");
        double prev = 1;
        for (double k = 3000; k >= 700; k -= 5) {
            double g = CloudModel.glowShare(k, day);
            assertTrue(g <= CloudModel.glowShare(k) + 1e-12);
            assertTrue(g <= prev + 1e-12 && prev - g < 0.06, "без скачков: " + k);
            if (k > CloudModel.DRAPER_K) assertEquals(BlackBody.luminance(k), CloudModel.glowLuminance(k), 0.002 * BlackBody.luminance(k));
            prev = g;
        }
    }

    /**
     * Топливо в воронке (Tieszen, Attaway 1996: при крутом ударе в воронке 25–50 %; горит тонким слоем — пятая доля
     * выгорания глубокой лужи, Mealy и др. 2011): горит по выгоранию с площади воронки, пламя по Хескестаду; у «Града»
     * топлива нет — и огня нет. Шахед горит минуты (жалоба F: в 3.5.0 лужа гасла за 12 с, и через 30 с над местом удара
     * не было дыма), ракета — больше десяти минут.
     */
    @Test
    void craterFuelBurnsForMinutes() {
        CloudModel drone = model(WeaponType.DRONE, CloudModel.Air.STANDARD), rocket = model(WeaponType.ROCKET, CloudModel.Air.STANDARD),
                missile = model(WeaponType.MISSILE, CloudModel.Air.STANDARD);
        assertEquals(0, rocket.poolSeconds);
        assertEquals(0, rocket.poolShare(1));
        // на воде воронки нет — и огня в ней нет
        assertEquals(0, new CloudModel(drone.source, CloudModel.Air.STANDARD, true).poolSeconds);
        double area = Math.PI * drone.poolRadius * drone.poolRadius;
        assertEquals(CloudModel.POOL_SHARE * drone.source.fuelKg() / (CloudModel.THIN_LAYER * BlastSource.Fuel.PETROL.poolBurn * area),
                drone.poolSeconds, 1e-9);
        assertEquals(drone.poolKg * BlastSource.Fuel.PETROL.heatJPerKg, drone.poolWatts * drone.poolSeconds, 1e-6 * drone.poolKg * 43.4e6);
        assertTrue(drone.poolSeconds > 90 && drone.poolSeconds < 300, "шахед: топливо горит " + drone.poolSeconds + " с");
        assertTrue(missile.poolSeconds > 600 && missile.poolSeconds < 1800, "ракета: топливо горит " + missile.poolSeconds + " с");
        assertTrue(drone.poolFlame() > 1 && drone.poolFlame() < 15, "пламя " + drone.poolFlame() + " м");
        assertEquals(1, drone.poolShare(60), 1e-12, "через минуту шахед ещё горит в полную силу");
        assertEquals(0, drone.poolShare(drone.poolSeconds), 1e-12);
        // дым над ним виден: толща шлейфа в 20 м по ветру 5 м/с днём (класс C) — выше порога видимости
        FirePlume plume = new FirePlume(drone.poolWatts, drone.poolRadius, BlastSource.Fuel.PETROL.sootYield / BlastSource.Fuel.PETROL.heatJPerKg, 5, 2);
        assertTrue(plume.tau(20) > 10 * CloudModel.SEEN, "толща шлейфа в 20 м " + plume.tau(20));
    }

    /**
     * Пыль волны лежит на земле под взрывом (жалоба 23.10: плоские «блины» пыли над городом на высоте ударов в крыши):
     * у взрыва на высоте h над землёй клубы слоя пыли — у земли (середина клуба не выше 2σ над ней), а не на высоте
     * взрыва (в облаке модели слой стоит на {@code hob} ниже точки взрыва); у удара в землю слой есть.
     */
    @Test
    void groundDustLiesOnTheGround() {
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE, WeaponType.ROCKET}) {
            for (double hob : new double[]{0, 6, 20, 45}) {
                CloudModel m = new CloudModel(BlastSource.of(w, false), CloudModel.Air.STANDARD, false, hob, GroundDust.Ground.BARE);
                double[] dust = new double[1];
                for (double t : new double[]{0.3, 2, 10, 60}) {
                    final double age = t;
                    m.ground.blobs(t, 1, (x, y, z, sh, sv, d) -> {
                        dust[0] += d;
                        assertTrue(y <= 2 * sv + 0.5, w + " на " + hob + " м, " + age + " с: клуб пыли в " + y + " м над землёй, σv " + sv);
                    });
                }
                if (hob == 0) assertTrue(dust[0] > 0, w + ": у удара в землю пыль волны есть");
            }
        }
    }

    /**
     * Ветер растёт с высотой — верх облака уходит вперёд низа (жалоба 23.10: облако сносилось одним твёрдым телом): у
     * ракеты в ветре 5 м/с на 10 м (класс D) слой над серединой облака по ветру дальше слоя под ней, и разница растёт;
     * в штиль слои стоят друг над другом.
     */
    @Test
    void topDriftsFasterThanBottom() {
        CloudModel.Air wind = new CloudModel.Air(ua.zentix.incoming.util.Stability.STANDARD_N, 5, 0, 0.15, 3, 0.5);
        CloudModel m = new CloudModel(BlastSource.of(WeaponType.MISSILE, false), wind);
        double last = 0;
        for (double t : new double[]{20, 60, 120, 240}) {
            assertTrue(t > m.fireSeconds, "после огненной стадии");
            java.util.List<double[]> layers = new java.util.ArrayList<>();
            m.blobs(t, (x, y, z, sh, sv, soot, dust, water, kelvin, rise) -> {
                if (soot > 0) layers.add(new double[]{x, y});
            });
            assertEquals(CloudModel.LAYERS, layers.size(), "слои облака в " + t + " с");
            layers.sort(java.util.Comparator.comparingDouble(a -> a[1]));
            double lead = layers.get(layers.size() - 1)[0] - layers.get(0)[0];
            assertTrue(lead > last, t + " с: верх впереди низа на " + lead + " м (было " + last + ")");
            last = lead;
        }
        assertTrue(last > 20, "за 4 минуты верх ушёл вперёд на " + last + " м");
        CloudModel calm = new CloudModel(BlastSource.of(WeaponType.MISSILE, false), CloudModel.Air.STANDARD);
        calm.blobs(120, (x, y, z, sh, sv, soot, dust, water, kelvin, rise) -> {
            if (soot > 0) assertEquals(calm.driftX(120), x, 1e-9, "в штиль слои друг над другом");
        });
    }

    /**
     * Слои облака — тот же гаусс: масса целиком, середина массы и второй момент по высоте — как у одного гаусса σv, профиль
     * без провалов.
     */
    @Test
    void layersKeepTheCloudsMoments() {
        double w = 0, q = 0, q2 = 0;
        for (int j = 0; j < CloudModel.LAYERS; j++) {
            w += CloudModel.LAYER_W[j];
            q += CloudModel.LAYER_W[j] * CloudModel.LAYER_Q[j];
            q2 += CloudModel.LAYER_W[j] * CloudModel.LAYER_Q[j] * CloudModel.LAYER_Q[j];
        }
        assertEquals(1, w, 1e-12);
        assertEquals(0, q, 1e-12);
        assertEquals(1, q2 + CloudModel.SLICE * CloudModel.SLICE, 1e-6);
        // профиль по высоте без провалов: плотность падает от середины к краю (облако сплошное, а не бусы)
        double prev = Double.MAX_VALUE;
        for (double z = 0; z < 3; z += 0.05) {
            double p = 0;
            for (int j = 0; j < CloudModel.LAYERS; j++) {
                double u = (z - CloudModel.LAYER_Q[j]) / CloudModel.SLICE;
                p += CloudModel.LAYER_W[j] * Math.exp(-0.5 * u * u);
            }
            assertTrue(p < prev, "провал на " + z + " σv");
            prev = p;
        }
    }

    /**
     * Воздух перемешивает облако с первой секунды (жалоба 23.10: облако минутами держало форму): пока термик ещё
     * поднимается, σ уже больше радиуса термика (R/√5), и толща поперёк середины падает всё время, а не только после
     * вершины.
     */
    @Test
    void airMixesTheCloudFromTheStart() {
        CloudModel.Air wind = new CloudModel.Air(ua.zentix.incoming.util.Stability.STANDARD_N, 5, 0, 0.15, 3, 0.5);
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE}) {
            CloudModel m = new CloudModel(BlastSource.of(w, false), wind);
            assertTrue(m.topSeconds > 100, w + ": вершина через " + m.topSeconds + " с");
            for (double t : new double[]{10, 30, 60}) {
                double thermal = m.radius(t) / Math.sqrt(5);
                assertTrue(m.sigmaH(t) > 1.03 * thermal, w + " " + t + " с: σ " + m.sigmaH(t) + " при термике " + thermal);
            }
            double prev = Double.MAX_VALUE;
            for (double t = Math.ceil(m.fireSeconds); t < m.topSeconds; t += 5) {
                double tau = m.tau(t);
                assertTrue(tau < prev, w + " " + t + " с: толща " + tau + " после " + prev);
                prev = tau;
            }
        }
    }
}
