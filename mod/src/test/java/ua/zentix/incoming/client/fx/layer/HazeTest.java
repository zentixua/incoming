package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.client.fx.plume.Flame;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.FirePlume;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Air;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Дрожание воздуха над огнём, огненным шаром и в струе сопла ({@link HeatPass}): на экране видно там, где его видит глаз
 * (жалоба FX 23.10: «дрожания нет нигде» — было 0,01–0,1 пикселя), и не сильнее того, что видит глаз.
 */
class HazeTest {
    /** Пиксель при поле зрения 70° по вертикали и 1080 строках, рад. */
    static final double PIXEL = Math.toRadians(70) / 1080;
    private static final CloudModel DRONE = new CloudModel(BlastSource.of(WeaponType.DRONE, false), CloudModel.Air.STANDARD);

    static HeatPass.Frame frame(double pixel) {
        HeatPass.Frame f = new HeatPass.Frame();
        f.begin();
        f.pixel = pixel;
        return f;
    }

    /** Сдвиг самого заметного столба кадра, пиксели (СКО по оси). */
    static double shift(HeatPass.Frame f) {
        double most = 0;
        for (double s : f.hotShift) most = Math.max(most, s);
        return most;
    }

    static Flame booster() {
        Flame f = ClientWeaponSpec.of(WeaponType.MISSILE).airframe(false).far().stage(FlightPhase.BOOST).flame(Air.SEA);
        assertNotNull(f);
        return f;
    }

    /**
     * При обычном поле зрения огонь Minecraft, шар шахеда и струя ускорителя на старте в 20–100 блоках сбоку дрожат на
     * заметную долю пикселя и больше (СКО ≥ 0,3; размах — вдвое-втрое), но не на «космические» пиксели.
     */
    @Test
    void fireBallAndJetShimmerOnScreen() {
        FirePlume fire = new FirePlume(6, FirePlume.CALM, 3);
        Flame boost = booster();
        for (double d : new double[]{20, 50, 100}) {
            HeatPass.Frame f = frame(PIXEL);
            f.plume(d, -1.6, 0, fire, 1);
            HeatPass.Frame b = frame(PIXEL);
            double t = 1, cr = DRONE.radius(t);
            b.hot(d, Math.max(0, DRONE.height(t) - cr) - 1.6, 0, 2 * cr, cr, DRONE.kelvin(t), DRONE.rise(t));
            HeatPass.Frame j = frame(PIXEL);
            j.exhaust(d, 1.5, 0, 0, -0.5, -0.866, boost, Double.POSITIVE_INFINITY);
            for (HeatPass.Frame x : new HeatPass.Frame[]{f, b, j}) {
                assertTrue(shift(x) >= 0.3 && shift(x) <= 4, "в " + d + " блоках: " + shift(x) + " px");
            }
        }
    }

    /**
     * Подача — только в долях того, что различает глаз: при обычном пикселе сдвиг на экране — угол / острота зрения (усиление
     * пиксель/острота, не больше), в бинокль (пиксель мельче остроты) — угол / пиксель, как есть.
     */
    @Test
    void presentationIsTheEyesResolutionOnly() {
        double dn = HeatPass.dn(600, HeatPass.TURBULENCE), len = 4, eddy = 0.4, d = 30, speed = 3;
        double tele = HeatPass.ACUITY / 8;
        double angle = HeatPass.hazeAngle(dn, len, eddy, HeatPass.aperture(d, tele)) * HeatPass.hazeSeen(eddy, speed);
        assertEquals(angle / tele, HeatPass.hazeShift(dn, len, eddy, d, tele, speed), 1e-9);
        double eye = HeatPass.hazeAngle(dn, len, eddy, HeatPass.aperture(d, HeatPass.ACUITY)) * HeatPass.hazeSeen(eddy, speed);
        double onScreen = HeatPass.hazeShift(dn, len, eddy, d, PIXEL, speed);
        assertEquals(eye / HeatPass.ACUITY, onScreen, 1e-9);
        assertTrue(onScreen * PIXEL / eye <= PIXEL / HeatPass.ACUITY + 1e-9);
    }

    /**
     * Закон: сильнее всего дрожит газ при T = 2T₀ (δn ∝ (T₀/T)(1 − T₀/T)); длиннее путь — сильнее (√L); дальше газ (пятно,
     * что различает глаз, крупнее) — не сильнее; быстрые вихри глаз сливает.
     */
    @Test
    void lawIsMonotonic() {
        double t0 = CloudModel.T_AIR_K, peak = HeatPass.dn(2 * t0, 1);
        for (double k = 300; k < 3000; k += 50) assertTrue(HeatPass.dn(k, 1) <= peak + 1e-15, k + " K");
        double dn = 2e-5, prev = 0;
        for (double len = 0.5; len < 50; len *= 1.5) {
            double a = HeatPass.hazeAngle(dn, len, 0.4, 0.004);
            assertTrue(a > prev);
            prev = a;
        }
        prev = Double.POSITIVE_INFINITY;
        for (double d = 1; d < 5000; d *= 1.3) {
            double s = HeatPass.hazeShift(dn, 4, 0.4, d, PIXEL, 3);
            assertTrue(s <= prev + 1e-12, d + " блоков");
            prev = s;
        }
        assertEquals(1, HeatPass.hazeSeen(0.4, 3), 1e-12);
        assertTrue(HeatPass.hazeSeen(0.02, 2000) < 0.1);
    }

    /** Без горячего газа столба нет: газ холоднее воздуха + COOL, факел без скорости газа. */
    @Test
    void nothingWithoutHotGas() {
        HeatPass.Frame f = frame(PIXEL);
        f.hot(20, 0, 0, 10, 3, CloudModel.T_AIR_K + HeatPass.COOL - 1, 2);
        Flame b = booster();
        f.exhaust(20, 1.5, 0, 0, -1, 0, new Flame(b.core(), b.flame(), b.length(), b.width(), b.diamonds(), b.coreLuminance(),
                b.flameLuminance(), b.tailLuminance(), b.coreColor(), b.flameColor(), b.tailColor(), b.temperature(), b.flameTemperature(),
                b.emissivity(), b.sheath(), b.middle(), b.tailMiddle(), 0), Double.POSITIVE_INFINITY);
        assertEquals(0, shift(f));
        // струя, что ушла в землю у среза, — не дрожит
        f.exhaust(20, 1.5, 0, 0, -1, 0, b, 0);
        assertEquals(0, shift(f));
    }
}
