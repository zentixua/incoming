package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Импульс шара топлива у места ({@link FuelFireball.Sweep#exposure}, BLASTSEQ D18): шар растёт от земли, всплывает к 0,75·D
 * и уходит с ветром, поэтому импульс — у места, где шар был, и тем меньше, чем меньше его горения место было в шаре.
 * Прежде сервер жёг полусферу D/2 у точки удара полным импульсом: крону над поднявшимся шаром — никогда, траву под ним —
 * как в шаре всё горение.
 */
class FuelFireballTest {
    private static final double ALPHA = 0.15;

    private static FuelFireball.Sweep ball(WeaponType w, double windX) {
        return FuelFireball.Sweep.of(FuelFireball.ballKg(w.spec().fuelAtImpact()), 0, windX, 0, ALPHA);
    }

    private static double full(WeaponType w) {
        return FuelFireball.exposure(FuelFireball.ballKg(w.spec().fuelAtImpact()));
    }

    /**
     * Трава у точки удара: шар ушёл вверх и светит на неё всё слабее — импульс меньше полного. У шахеда загорается
     * пятая-третья часть сухой травы и большая часть листвы, у ракеты — почти вся трава.
     */
    @Test
    void groundUnderTheRisenBallGetsLessThanFull() {
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE}) {
            double q = ball(w, 0).exposure(0, 0.5, 0);
            assertTrue(q < 0.92 * full(w) && q > 0.75 * full(w), w + ": у точки удара " + q + " из " + full(w));
        }
        double drone = ball(WeaponType.DRONE, 0).exposure(0, 0.5, 0);
        double grass = PulseIgnition.share(drone, PulseIgnition.Fuel.GRASS);
        assertTrue(grass > 0.15 && grass < 0.35, "шахед, трава у удара " + grass);
        assertTrue(PulseIgnition.share(drone, PulseIgnition.Fuel.LEAVES) > 0.6, "шахед, листва у удара");
        assertTrue(PulseIgnition.share(ball(WeaponType.MISSILE, 0).exposure(0, 0.5, 0), PulseIgnition.Fuel.GRASS) > 0.9, "ракета, трава у удара");
    }

    /** Крона над поднявшимся шаром (на высоте D, вне прежней полусферы D/2) загорается: у ракеты — почти вся листва. */
    @Test
    void canopyAboveTheRisenBallBurns() {
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE}) {
            double d = FuelFireball.diameter(FuelFireball.ballKg(w.spec().fuelAtImpact()));
            double q = ball(w, 0).exposure(0, d, 0);
            assertTrue(PulseIgnition.share(q, PulseIgnition.Fuel.LEAVES) > (w == WeaponType.MISSILE ? 0.95 : 0.4), w + ": листва на высоте D, " + q);
        }
    }

    /** Ветер уносит шар: по ветру импульс у земли больше, чем против него на том же расстоянии. */
    @Test
    void windCarriesTheBallDownwind() {
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE}) {
            FuelFireball.Sweep b = ball(w, 5);
            double r = 0.5 * FuelFireball.diameter(FuelFireball.ballKg(w.spec().fuelAtImpact()));
            double down = b.exposure(r, 0.5, 0), up = b.exposure(-r, 0.5, 0);
            assertTrue(down > 1.3 * up, w + ": по ветру " + down + ", против " + up);
            assertTrue(b.x()[b.x().length - 1] > 0.3 * r, w + ": шар ушёл по ветру на " + b.x()[b.x().length - 1] + " м");
        }
    }

    /**
     * Импульс не прыгает на краю шара (в шаре — полный, вне — (r/d)²) и дальше трёх радиусов от конца пути шара ниже
     * {@link PulseIgnition#FLOOR}: обход поджига сервера там кончается.
     */
    @Test
    void exposureIsContinuousAndEndsAtReach() {
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE}) {
            for (double wind : new double[]{0, 5}) {
                FuelFireball.Sweep b = ball(w, wind);
                double prev = b.exposure(0, 0.5, 0);
                for (double x = 0.05; x < 60; x += 0.05) {
                    double q = b.exposure(x, 0.5, 0);
                    assertEquals(prev, q, 0.05 * full(w), w + ": скачок импульса у земли в " + x + " м");
                    prev = q;
                }
                int last = b.r().length - 1;
                double reach = Math.hypot(b.x()[last], b.z()[last]) + 1.5 * FuelFireball.diameter(FuelFireball.ballKg(w.spec().fuelAtImpact()));
                for (double a = 0; a < 2 * Math.PI; a += Math.PI / 16) {
                    for (double y = 0.5; y < 3 * reach; y += 1) {
                        double q = b.exposure((reach + 0.5) * Math.cos(a), y, (reach + 0.5) * Math.sin(a));
                        assertTrue(q < PulseIgnition.FLOOR, w + ", ветер " + wind + ": за " + reach + " м импульс " + q);
                    }
                }
            }
        }
    }

    /** Без топлива шара нет: импульса нигде. */
    @Test
    void noFuelNoBall() {
        FuelFireball.Sweep b = FuelFireball.Sweep.of(0, 0, 5, 0, ALPHA);
        assertEquals(0, b.exposure(0, 0.5, 0));
    }
}
