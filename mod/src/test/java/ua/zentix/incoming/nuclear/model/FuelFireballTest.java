package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The impulse of the fuel ball at the place ({@link FuelFireball.Sweep#exposure}, BLASTSEQ D18): the ball grows from the ground, floats up to 0,75·D
 *  and leaves with the wind, therefore the impulse is at the place where the ball was, and the less, the less of its combustion the place was in ball.
 * Previously, the server burned the hemisphere D/2 at the point of impact with a full impulse: the crown above the rising ball - never, the grass below it -
 * as in the ball all the burning.
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
     * The grass is at the point of impact: the ball has gone up and the light on it is getting weaker and weaker - the impulse is less than full. In Shahed,
     *  a fifth to a third of the dry grass and most of the foliage catches fire; in the rocket, almost all of the grass lights up.
     */
    @Test
    void groundUnderTheRisenBallGetsLessThanFull() {
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE}) {
            double q = ball(w, 0).exposure(0, 0.5, 0);
            assertTrue(q < 0.92 * full(w) && q > 0.75 * full(w), w + ": at the point of impact " + q + " from " + full(w));
        }
        double drone = ball(WeaponType.DRONE, 0).exposure(0, 0.5, 0);
        double grass = PulseIgnition.share(drone, PulseIgnition.Fuel.GRASS);
        assertTrue(grass > 0.15 && grass < 0.35, "shahed, grass at the blow " + grass);
        assertTrue(PulseIgnition.share(drone, PulseIgnition.Fuel.LEAVES) > 0.6, "shahed, foliage at the blow");
        assertTrue(PulseIgnition.share(ball(WeaponType.MISSILE, 0).exposure(0, 0.5, 0), PulseIgnition.Fuel.GRASS) > 0.9, "rocket, grass at impact");
    }

    /** The crown above the rising ball (at a height of D, outside the previous hemisphere D/2) lights up: the rocket has almost all the foliage. */
    @Test
    void canopyAboveTheRisenBallBurns() {
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE}) {
            double d = FuelFireball.diameter(FuelFireball.ballKg(w.spec().fuelAtImpact()));
            double q = ball(w, 0).exposure(0, d, 0);
            assertTrue(PulseIgnition.share(q, PulseIgnition.Fuel.LEAVES) > (w == WeaponType.MISSILE ? 0.95 : 0.4), w + ": foliage at height D, " + q);
        }
    }

    /** The wind carries the ball away: the momentum of the ground is greater with the wind than against it at the same distance. */
    @Test
    void windCarriesTheBallDownwind() {
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE}) {
            FuelFireball.Sweep b = ball(w, 5);
            double r = 0.5 * FuelFireball.diameter(FuelFireball.ballKg(w.spec().fuelAtImpact()));
            double down = b.exposure(r, 0.5, 0), up = b.exposure(-r, 0.5, 0);
            assertTrue(down > 1.3 * up, w + ": downwind " + down + ", against " + up);
            assertTrue(b.x()[b.x().length - 1] > 0.3 * r, w + ": the ball went downwind to " + b.x()[b.x().length - 1] + " m");
        }
    }

    /**
     * The impulse does not jump at the edge of the ball (in the ball - full, outside - (r/d)²) and beyond three radii from the end of the ball's path below
     * {@link PulseIgnition#FLOOR}: the server ignition bypass ends there.
     */
    @Test
    void exposureIsContinuousAndEndsAtReach() {
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE}) {
            for (double wind : new double[]{0, 5}) {
                FuelFireball.Sweep b = ball(w, wind);
                double prev = b.exposure(0, 0.5, 0);
                for (double x = 0.05; x < 60; x += 0.05) {
                    double q = b.exposure(x, 0.5, 0);
                    assertEquals(prev, q, 0.05 * full(w), w + ": impulse jump near the ground at " + x + " m");
                    prev = q;
                }
                int last = b.r().length - 1;
                double reach = Math.hypot(b.x()[last], b.z()[last]) + 1.5 * FuelFireball.diameter(FuelFireball.ballKg(w.spec().fuelAtImpact()));
                for (double a = 0; a < 2 * Math.PI; a += Math.PI / 16) {
                    for (double y = 0.5; y < 3 * reach; y += 1) {
                        double q = b.exposure((reach + 0.5) * Math.cos(a), y, (reach + 0.5) * Math.sin(a));
                        assertTrue(q < PulseIgnition.FLOOR, w + ", wind " + wind + ": for " + reach + " m impulse " + q);
                    }
                }
            }
        }
    }

    /** Without fuel, there is no ball: there is no momentum anywhere. */
    @Test
    void noFuelNoBall() {
        FuelFireball.Sweep b = FuelFireball.Sweep.of(0, 0, 5, 0, ALPHA);
        assertEquals(0, b.exposure(0, 0.5, 0));
    }
}
