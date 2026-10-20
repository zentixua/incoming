package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.strike.WeaponType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Взрывы вдали: облако по модели и прежняя картинка бомбы, столб без разрывов, клубы за гребнем. */
class FarBlastsTest {
    private static final List<FarBlasts.Look> LOOKS = List.of(FarBlasts.BUNKER_BREACH, FarBlasts.BUNKER_DEEP);
    /** Облака наземных взрывов — по модели, в стандартной атмосфере. */
    private static final List<CloudModel> MODELS = List.of(model(WeaponType.ROCKET), model(WeaponType.LOITER), model(WeaponType.DRONE),
            model(WeaponType.MISSILE));

    private static CloudModel model(WeaponType w) {
        return new CloudModel(BlastSource.of(w, false), CloudModel.Air.STANDARD);
    }

    /** Верх стабилизированного облака по Чёрчу (1969): 92,6·W^¼ м, W — кг ТНТ. */
    private static double church(double kg) {
        return 92.6 * Math.pow(kg, 0.25);
    }

    /** Бомба: прорвавшаяся — ниже ракеты, под землёй — столб пыли без шара; столб живёт минуты. */
    @Test
    void bunkerKeepsItsColumn() {
        assertTrue(cloud(FarBlasts.BUNKER_BREACH) < model(WeaponType.MISSILE).topZ + model(WeaponType.MISSILE).topR,
                "прорвавшаяся бомба — ниже наземного взрыва ракеты");
        assertEquals(0, FarBlasts.BUNKER_DEEP.fireball(), "бомба под землёй — без вспышки и шара");
        assertEquals(1, FarBlasts.BUNKER_DEEP.dust(), 1e-6, "бомба под землёй — столб пыли");
        for (FarBlasts.Look k : LOOKS) {
            assertTrue(k.puffs() >= 10 && k.puffs() <= 14, "клубов на столб: " + k);
            assertTrue(k.life() >= 1200, "столб живёт минуты: " + k);
            assertTrue(k.life() > k.riseTicks(), "столб поднимается раньше, чем тает: " + k);
        }
    }

    /**
     * Облако взрывчатки без топлива («Град», «Ланцет») поднимается, как по Чёрчу, — верх в 15 % от 92,6·W^¼; с топливом
     * (шахед, ракета) — выше: его тепло в десятки раз больше тепла заряда.
     */
    @Test
    void cloudsRiseByTheirHeat() {
        for (CloudModel m : MODELS) {
            double top = m.topZ + m.topR, church = church(m.source.chargeKg());
            if (m.source.fuelKg() > 0) assertTrue(top > 1.5 * church, m.source + ": верх " + top + " при Чёрче " + church);
            else assertEquals(1, top / church, 0.15, m.source + ": верх " + top + " при Чёрче " + church);
        }
    }

    /** Цвет облака вдали — толстый слой его вещества: сажа темнее пыли, капли — белые. */
    @Test
    void cloudColourIsItsMatter() {
        float soot = FarBlasts.thick(1, 0, 0, 0.9f), dust = FarBlasts.thick(0, 1, 0, SmokeGrid.dustAlbedo(0.6f)), drops = FarBlasts.thick(0, 0, 1, 0.9f);
        assertTrue(soot < 0.45f, "сажа " + soot);
        assertTrue(dust > soot + 0.2f, "пыль " + dust + ", сажа " + soot);
        assertTrue(drops > 0.9f, "капли " + drops);
        // пыль грунта — цвета грунта: толстый слой пыли цвета x на экране и есть x (обращение отражения)
        assertEquals(0.6f, FarBlasts.thick(0, 1, 0, SmokeGrid.dustAlbedo(0.6f)), 0.01f);
    }

    @Test
    void ballOutshinesDaySkyForAboutHalfItsLife() {
        // днём (яркость против неба > 1 после воздуха 1,5 км) — меньше половины жизни; ночью — вся
        double t = Sight.transmittance(1500, Sight.CLEAR);
        double day = 0;
        for (double u = 0; u < 1; u += 0.001) if (FarBlasts.ball(u) * t > 1) day += 0.001;
        assertTrue(day > 0.3 && day < 0.55, "днём виден " + day + " жизни");
        assertTrue(Sight.adapted(FarBlasts.ball(0.85), 0.17) * Sight.transmittance(4000, Sight.CLEAR) > 1, "ночью — почти до конца");
    }

    @Test
    void smokeWaitsForTheBallToFade() {
        // пока шар светит целиком, дым столба его не закрывает (иначе десяток клубов за 8 тиков делает из шара бурый ком)
        for (FarBlasts.Look k : LOOKS) {
            int from = FarBlasts.smokeFrom(k);
            for (double age = 0; age < k.ballTicks(); age += 0.25) {
                if (FarBlasts.fade(age / k.ballTicks()) >= 1) assertEquals(0, FarBlasts.emerge(k, age), 1e-12, k + ": на " + age + " тике шар ещё целый");
            }
            assertTrue(from >= FarBlasts.BALL_FADE * k.ballTicks() && (k.ballTicks() == 0 || from < k.ballTicks()), k + ": с " + from);
            assertEquals(1, FarBlasts.emerge(k, Math.max(k.ballTicks(), from + FarBlasts.FADE_IN)), 1e-12, k + ": к концу шара столб весь");
        }
        assertEquals(0, FarBlasts.smokeFrom(FarBlasts.BUNKER_DEEP), "без шара — столб сразу");
    }

    @Test
    void flashHoldsThenFades() {
        FarBlasts.Look k = FarBlasts.BUNKER_BREACH;
        assertEquals(k.flash(), FarBlasts.flash(k, 0), 1e-12);
        assertTrue(FarBlasts.flash(k, 1) > 0.85 * k.flash(), "держится — глаз складывает свет 50–100 мс");
        assertEquals(0, FarBlasts.flash(k, k.flashTicks()), 1e-12);
    }

    @Test
    void columnHasNoGapsEvenAtWorstJitter() {
        for (FarBlasts.Look k : LOOKS) {
            int n = k.puffs();
            for (int i = 0; i + 1 < n; i++) {
                // соседи разнесены разбросом до предела и оба мельче всех
                double f1 = FarBlasts.fraction(i, n, -0.5), f2 = FarBlasts.fraction(i + 1, n, 0.5);
                double r1 = FarBlasts.radius(k, f1) * FarBlasts.SIZE_MIN, r2 = FarBlasts.radius(k, f2) * FarBlasts.SIZE_MIN;
                double gap = FarBlasts.height(k, f2, r2, 1) - FarBlasts.height(k, f1, r1, 1);
                assertTrue(gap < r1 + r2, k + ": клубы " + i + " и " + (i + 1) + " в " + gap + " бл при радиусах " + r1 + ", " + r2);
            }
        }
    }

    @Test
    void columnRisesFastThenSettles() {
        assertEquals(0, FarBlasts.rise(0, 600), 1e-12);
        assertEquals(0.95, FarBlasts.rise(600, 600), 0.01);
        assertTrue(FarBlasts.rise(100, 600) > 100 / 600.0, "вначале быстрее, чем равномерно");
    }

    @Test
    void puffsSinkBehindTheRidgeSmoothly() {
        assertEquals(1, FarBlasts.visible(5, 6, 0), "место открыто — видно всё, и часть у земли тоже");
        assertEquals(0, FarBlasts.visible(20, 6, 40), 1e-12, "весь клуб ниже гребня");
        assertEquals(1, FarBlasts.visible(60, 6, 40), 1e-12, "весь клуб выше гребня");
        assertEquals(0.5, FarBlasts.visible(40, 6, 40), 1e-12, "гребень посередине клуба");
        // клуб над землёй, гребень у самой земли — видно всё: по мере роста гребня клуб уходит без скачка
        assertEquals(1, FarBlasts.visible(10, 6, 1e-9), 1e-12);
        double prev = 1;
        for (double line = 0; line < 30; line += 0.5) {
            double v = FarBlasts.visible(15, 6, line);
            assertTrue(v <= prev + 1e-12 && prev - v < 0.2, "плавно на " + line + ": " + prev + " → " + v);
            prev = v;
        }
    }

    @Test
    void cloudsGlowAtNightNotByDay() {
        // свет неба FarView.ambient: полдень, сумерки и полночь (getSkyDarken 0,2); облака в 130 блоках над местом; шар —
        // вспышка продуктов детонации модели
        double day = 1, night = 0.17, h = 130;
        double[] light = new double[4];
        for (CloudModel m : MODELS) {
            // днём зарево на облаках — только пока светят продукты детонации (~3000 K); шар топлива (~1500 K) их не светит
            for (double t = 0; t < m.fireSeconds; t += 0.05) {
                m.ballLight(t, light);
                boolean lit = FarBlasts.SKY_PEAK * FarBlasts.cloudGlow(light[3], m.ballRadius(t), h, day) >= Sight.THRESHOLD;
                assertTrue(!lit || t < m.heBallSeconds, m.source + ": днём зарево на " + t + " с, вспышка до " + m.heBallSeconds);
            }
            m.ballLight(0, light);
            assertTrue(FarBlasts.cloudGlow(light[3], m.ballRadius(0), h, night) > 0.5, m.source + ": ночью низ облаков над взрывом вспыхивает");
            assertTrue(FarBlasts.cloudGlow(light[3], m.ballRadius(0), h, night) <= 1, "не ярче белого");
        }
        double b = FarBlasts.ball(0);
        assertTrue(FarBlasts.SKY_PEAK * FarBlasts.cloudGlow(b, FarBlasts.BUNKER_BREACH.fireball(), h, day) < Sight.THRESHOLD, "бомба: днём зарева не видно");
    }

    @Test
    void hiddenBallGlowsNoBrighterThanItsVeil() {
        double day = 1, night = 0.17;
        double[] light = new double[4];
        for (CloudModel m : MODELS) {
            m.ballLight(0, light);
            double peak = light[3];
            assertTrue(FarBlasts.behind(peak, day, 1) < 0.5, m.source + ": днём зарево из-за края едва видно");
            assertEquals(Sight.VEIL_PEAK, FarBlasts.behind(peak, night, 1), 1e-9, m.source + ": ночью — в силу вуали, не ярче её");
        }
        assertTrue(Sight.VEIL_PEAK < Sight.HALO, "закрытый шар светит слабее блика открытого");
        assertTrue(FarBlasts.behind(8, night, 0.01) < FarBlasts.behind(8, night, 1), "дымка гасит зарево");
    }

    @Test
    void raysCoverTheBallDiscFacingTheEye() {
        // глаз сверху и сбоку, как с высотки: точки кольца — на радиусе от середины, поперёк луча, и одна — сбоку по горизонтали
        Vec3 eye = new Vec3(-30, 126, 60);
        double[] p = new double[3];
        for (int i = 0; i <= FarBlasts.RING_RAYS; i++) {
            FarBlasts.disc(eye, 0, 0, 0, 7, i, p);
            double off = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
            assertEquals(i == 0 ? 0 : 7, off, 1e-9, "точка " + i);
            assertEquals(0, p[0] * -eye.x + p[1] * -eye.y + p[2] * -eye.z, 1e-6, "точка " + i + " поперёк луча");
        }
        FarBlasts.disc(eye, 0, 0, 0, 7, 1, p);
        assertEquals(0, p[1], 1e-9, "первая — сбоку, на высоте середины");
        FarBlasts.disc(new Vec3(0, 100, 0), 0, 0, 0, 7, 2, p);
        assertTrue(Double.isFinite(p[0] + p[1] + p[2]), "глаз прямо над шаром");
    }

    /** Верх облака: верх верхнего клуба поднявшегося столба. */
    private static double cloud(FarBlasts.Look k) {
        double r = FarBlasts.radius(k, 1);
        return FarBlasts.height(k, 1, r, 1) + r;
    }
}
