package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontProfileTest {
    private static final ArrivalTable MODEL = ArrivalTable.of(15, 0, 20000);

    /** Slow far zone speed ({@code nuclear.wave_speed} 0.42, old default) and even older, 3 block per tick. */
    private static final double SLOW = 0.42 * FrontProfile.SOUND_SPEED, OLD = 3;

    /** Profile 15 kt in scale {@code scale} with far-field speed {@code far}, as built by {@code Detonation}. */
    private record Case(double scale, double from, double sound, double far, FrontProfile p) {
        static Case of(double scale, boolean surface, double far) {
            double from = FrontProfile.slowFrom(FireballModel.maxRadius(15, surface) * scale);
            double sound = HobBlast.rangeFor(0.3, 0, 15) * scale;
            return new Case(scale, from, sound, far, FrontProfile.of(MODEL, scale, from, sound, sound * 1.5, far));
        }

        double model(double blocks) {
            return MODEL.arrivalSeconds(blocks / scale) * 20 * scale;
        }
    }

    private static final Case SMALL = Case.of(0.3, false, SLOW), LIFE = Case.of(1, false, SLOW), LIFE_SURFACE = Case.of(1, true, SLOW),
            LIFE_OLD = Case.of(1, false, OLD), SMALL_OLD = Case.of(0.3, false, OLD), LIFE_REAL = Case.of(1, false, FrontProfile.SOUND_SPEED);
    private static final Case[] ALL = {SMALL, LIFE, LIFE_SURFACE, LIFE_OLD, SMALL_OLD, LIFE_REAL};

    /** Two radii of the ball, but no further than 200 blocks: on the 1 scale, the front to 600 blocks is already moving at the speed of the far zone. */
    @Test
    void slowFromIsTwoFireballsCapped() {
        assertEquals(2 * FireballModel.maxRadius(15, false) * 0.3, SMALL.from, 1e-9);
        assertEquals(FrontProfile.SLOW_FROM_MAX, LIFE.from, 1e-9);
        assertEquals(FrontProfile.SLOW_FROM_MAX, LIFE_SURFACE.from, 1e-9);
    }

    /** The epicenter is like the model: the flash, the ball and the first impact do not change. */
    @Test
    void nearGroundZeroFollowsModel() {
        for (Case c : ALL) {
            for (double b = 1; b <= c.from; b += 7) assertEquals(c.model(b), c.p.arrivalTicks(b), Math.max(0.05, c.model(b) * 0.01), "on " + b);
        }
    }

    /** Further, it is not faster than the model, monotonously, without speed jumps, and from {@link FrontProfile#SLOW_TO} to the edge of the wall - the speed of the far zone. */
    @Test
    void slowsDownSmoothlyToFarSpeed() {
        for (Case c : new Case[]{SMALL, LIFE, LIFE_SURFACE, LIFE_OLD, SMALL_OLD}) {
            double prev = 0, prevSpeed = Double.NaN;
            for (double b = 1; b < c.sound - 2; b += 1) {
                double t = c.p.arrivalTicks(b);
                assertTrue(t > prev, "time doesn't grow by " + b);
                assertTrue(t >= c.model(b) - 1e-6, "faster than the model by " + b);
                double speed = 1 / (t - prev);
                if (b > c.from + 2 && !Double.isNaN(prevSpeed)) assertTrue(speed <= prevSpeed * 1.05 + 0.05, "speed jumps to " + b);
                if (b >= FrontProfile.SLOW_TO + 5) assertEquals(c.far, speed, 0.02 * c.far, "on " + b + " (scale " + c.scale + ")");
                prev = t;
                prevSpeed = speed;
            }
        }
    }

    /**
     * Scale 1, 15 kt, half sound: on 3 km impact through ~15 s after the flash (in life 7, with previous 3 blocks per tick
     * — 50), latest 200 blocks the wall goes longer than a second - you can see how it rolls.
     */
    @Test
    void wallIsVisibleApproaching() {
        double to3km = LIFE.p.arrivalTicks(3000) / 20;
        assertTrue(to3km >= 14 && to3km <= 20, "up to 3 km " + to3km + " s");
        for (Case c : new Case[]{SMALL, LIFE, LIFE_SURFACE}) {
            assertTrue(c.p.arrivalTicks(1200) - c.p.arrivalTicks(1200 - 200) >= 20, "scale " + c.scale);
        }
        assertTrue(LIFE_OLD.p.arrivalTicks(3000) / 20 > 2 * to3km, "previous speed is not slower");
    }

    /** The speed of the 1.0 is just like in life: it arrives everywhere like the model (its front in the distance is no slower than sound). */
    @Test
    void fullSpeedIsTheModel() {
        Case c = LIFE_REAL;
        for (double b = 1; b < c.sound; b += 13) assertEquals(c.model(b), c.p.arrivalTicks(b), Math.max(0.05, c.model(b) * 0.01), "on " + b);
    }

    /**
     * Behind the wall of dust there is sound: at the speed of sound from its edge (for 15 kt - ~3.5 km), and a distant blow at 5 km is heard after
     *  tens of seconds, and not minutes, as if there was a wall there too.
     */
    @Test
    void soundBeyondWallTravelsAtSoundSpeed() {
        Case c = LIFE;
        double edge = c.p.arrivalTicks(c.sound);
        for (double b = c.sound + 50; b <= 8000; b += 250) {
            assertEquals(edge + (b - c.sound) / FrontProfile.SOUND_SPEED, c.p.arrivalTicks(b), 2, "on " + b);
        }
        assertTrue(c.p.arrivalTicks(5000) / 20 < 90, "5 km — " + c.p.arrivalTicks(5000) / 20 + " s");
    }

    @Test
    void radiusIsInverseOfArrival() {
        for (Case c : ALL) {
            for (double b = 0; b <= 9000; b += 37) assertEquals(b, c.p.radiusAt(c.p.arrivalTicks(b)), 1e-3 + b * 1e-6);
            assertEquals(0, c.p.radiusAt(0), 0);
        }
    }
}
