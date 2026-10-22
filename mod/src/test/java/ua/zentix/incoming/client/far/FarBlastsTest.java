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

/** Взрывы вдали: облако по модели (и у бомбы под землёй), клубы за гребнем. */
class FarBlastsTest {
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


    /**
     * Облако взрывчатки без топлива («Град», «Ланцет») поднимается, как по Чёрчу, — верх в 15 % от 92,6·W^¼; с топливом
     * (шахед, ракета) — выше: его тепло в десятки раз больше тепла заряда.
     */
    /**
     * Бомба по той же модели облака: прорыв наружу на 3 м — облако доли заряда (выше «Града», ниже всего заряда на земле),
     * пыль — грунт воронки выброса; глубже — облако газов из хода (доля сечения хода) — маленькое; пыль входа и провала
     * без жара не всплывает, живёт и тает.
     */
    @Test
    void bunkerCloudsComeFromTheModel() {
        ua.zentix.incoming.warhead.BuriedBlast shallow = new ua.zentix.incoming.warhead.BuriedBlast(2423, 3);
        BlastSource breach = new BlastSource(WeaponType.BUNKER, shallow.airCharge(BlastSource.hole()), 0, true, false);
        CloudModel m = CloudModel.buried(breach, CloudModel.Air.STANDARD, shallow);
        CloudModel whole = new CloudModel(new BlastSource(WeaponType.BUNKER, 2423, 0, true, false), CloudModel.Air.STANDARD);
        assertTrue(m.topZ < whole.topZ && m.topZ > model(WeaponType.ROCKET).topZ, "прорыв: верх " + m.topZ + ", всего заряда " + whole.topZ);
        assertTrue(m.dust > whole.dust, "прорыв: пыль воронки выброса больше воронки заряда на земле");
        ua.zentix.incoming.warhead.BuriedBlast deep = new ua.zentix.incoming.warhead.BuriedBlast(2423, 20);
        CloudModel vent = new CloudModel(new BlastSource(WeaponType.BUNKER, deep.airCharge(BlastSource.hole()), 0, true, true), CloudModel.Air.STANDARD);
        assertTrue(vent.topZ < m.topZ, "газы из хода ниже прорыва");
        BlastSource none = new BlastSource(WeaponType.BUNKER, 0, 0, true, true);
        // ветер 3 м/с у земли: в штиль облако у земли не расплывается (Бриггс — по пути ветра)
        CloudModel dust = CloudModel.dust(none, CloudModel.Air.of(1, 0, 3, 0, 0.5), 160, 3.4);
        assertTrue(Double.isFinite(dust.topZ) && dust.topZ < 3.4, "пыль без жара не всплывает: " + dust.topZ);
        assertTrue(dust.life > 10 && dust.life < CloudModel.LIFE_MAX, "пыль провала живёт и тает: " + dust.life);
    }

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
        float soot = SmokePuffs.thick(1, 0, 0, 0.9f), dust = SmokePuffs.thick(0, 1, 0, SmokeGrid.dustAlbedo(0.6f)), drops = SmokePuffs.thick(0, 0, 1, 0.9f);
        assertTrue(soot < 0.45f, "сажа " + soot);
        assertTrue(dust > soot + 0.2f, "пыль " + dust + ", сажа " + soot);
        assertTrue(drops > 0.9f, "капли " + drops);
        // пыль грунта — цвета грунта: толстый слой пыли цвета x на экране и есть x (обращение отражения)
        assertEquals(0.6f, SmokePuffs.thick(0, 1, 0, SmokeGrid.dustAlbedo(0.6f)), 0.01f);
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

}
