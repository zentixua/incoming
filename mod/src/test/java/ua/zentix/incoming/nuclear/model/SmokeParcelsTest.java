package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.NukeClock;
import ua.zentix.incoming.util.StrikeClock;
import ua.zentix.incoming.util.WorldClock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke parcels ({@code docs/design/time.md} §8.3, rule 4): emitted per real second at the fire's strength on its strike clock,
 * moving and widening on the motion age; S at a past motion age from the anchors alone ({@link StrikeClock#strikeAtMotion}).
 */
public class SmokeParcelsTest {
    private static final long SHOW = NukeClock.SHOW_TICKS;
    /** Hiroshima: 15 kt, fires out to 2 km, wind 2.5 m/s toward +x. */
    static final SmokeParcels HIROSHIMA = new SmokeParcels(15, 2000, 0, 2.5);

    /** A strike's anchors under a steady day of rate {@code k}, read {@code age} real ticks after the burst. */
    static StrikeClock anchors(double k, long age) {
        StrikeClock c = StrikeClock.start(0);
        if (age < SHOW) return c;
        c = c.end(SHOW, 0, 0);
        if (age >= SHOW + StrikeClock.RAMP_TICKS) c = c.ramp(SHOW + StrikeClock.RAMP_TICKS, StrikeClock.RAMP_TICKS * k * WorldClock.REAL_RATE);
        return c;
    }

    /** Clock without jumps {@code age} real ticks after the burst under a steady day of rate {@code k}. */
    static double rate(double k, double age) {
        return Math.max(0, age - SHOW) * k * WorldClock.REAL_RATE;
    }

    /** The parcels' clock {@code seconds} real seconds after the burst under a steady day of rate {@code k}, no jumps. */
    public static SmokeParcels.Clock day(double k, double seconds) {
        long age = Math.round(seconds * 20);
        StrikeClock c = anchors(k, age);
        double rate = rate(k, age);
        return new SmokeParcels.Clock() {
            @Override
            public double motion() {
                return c.motion(age, 0);
            }

            @Override
            public double strikeAt(double m) {
                return c.strikeAtMotion(m, age, rate, 0);
            }
        };
    }

    /**
     * Under a steady day the strike clock at a past motion age read from the anchors and the present alone is the one the clock
     * showed then: the show real, the blend, the world's rate after it.
     */
    @Test
    void theStrikeClockThenFromTheAnchorsAlone() {
        for (double k : new double[]{1, 12, 72}) {
            for (long now : new long[]{SHOW / 2, SHOW + 300, SHOW + StrikeClock.RAMP_TICKS + 1, SHOW + 20 * 600, SHOW + 20 * 7200}) {
                StrikeClock c = anchors(k, now);
                double rateNow = rate(k, now);
                for (long then = 0; then <= now; then += 97) {
                    double truth = anchors(k, then).strike(then, rate(k, then), 0);
                    assertEquals(truth, c.strikeAtMotion(then / 20.0, now, rateNow, 0), 1e-6 * Math.max(1, truth),
                            "k " + k + ", now " + now + ", then " + then);
                }
                assertEquals(c.strike(now, rateNow, 0), c.strikeAtMotion(now / 20.0 + 5, now, rateNow, 0), 1e-9, "the present");
            }
        }
    }

    /**
     * A counted jump since the show's end is the newest stretch of M: across it S and M advance together, so the smoke that left
     * during the night carries the fire as the night burned it; before it, the clock as it was.
     */
    @Test
    void aCountedJumpIsTheNewestStretchOfMotion() {
        double k = 72, night = 10 * WorldClock.HOUR, jumpS = night * WorldClock.SECONDS_PER_TICK;
        long now = SHOW + 20 * 300;
        StrikeClock c = anchors(k, now);
        double rate = rate(k, now), m = c.motion(now, night), s = c.strike(now, rate, night);
        assertEquals(now / 20.0 + jumpS, m, 1e-9);
        for (double back = 0; back <= jumpS; back += 600) {
            assertEquals(s - back, c.strikeAtMotion(m - back, now, rate, night), 1e-6, "inside the night, " + back + " s back");
        }
        // before the night: as the clock was (no jump yet), and continuous at the night's start
        double before = m - jumpS;
        assertEquals(c.strike(now, rate, 0), c.strikeAtMotion(before, now, rate, night), 1e-6, "at the night's start");
        assertEquals(anchors(k, now - 400).strike(now - 400, rate(k, now - 400), 0), c.strikeAtMotion(before - 20, now, rate, night), 1e-6);
    }

    /** A parcel at shift s left the fires s / wind ago, at the fire's strength then (FireStormLaw.smoke at its strike clock). */
    @Test
    void aParcelCarriesTheFireAsItWasWhenItLeft() {
        for (double k : new double[]{1, 72}) {
            for (double t : new double[]{600, 1300, 1800, 3 * 3600}) {
                SmokeParcels.Clock c = day(k, t);
                for (double s = 0; s <= HIROSHIMA.length(c); s += 250) {
                    double left = c.motion() - s / HIROSHIMA.wind();
                    double expected = left <= 0 ? 0 : FireStormLaw.smoke(c.strikeAt(left), FireStormLaw.PEAK_S);
                    assertEquals(expected, HIROSHIMA.emitted(s, c), 1e-12, "k " + k + ", " + t + " s, shift " + s);
                }
                assertTrue(HIROSHIMA.length(c) <= HIROSHIMA.wind() * c.motion() + 1e-9, "no parcel ahead of the first one");
            }
        }
    }

    /**
     * No flare: the fires emit per real second at their strength, which never exceeds full; on a vanilla day they burn out on
     * their strike clock within about 10 minutes of the show and emit less in total than at a 24-hour day.
     */
    @Test
    void aShortDayEmitsLessNeverMore() {
        double real = 0, vanilla = 0, lastSmoke = 0;
        for (double t = 0; t < 14 * 3600; t += 1) {
            double r = HIROSHIMA.emitted(0, day(1, t)), v = HIROSHIMA.emitted(0, day(72, t));
            assertTrue(v <= 1 && r <= 1, "stronger than full fire at " + t);
            real += r;
            vanilla += v;
            if (v > 0) lastSmoke = t;
        }
        assertTrue(vanilla < 0.1 * real, "vanilla day " + vanilla + " against " + real);
        double after = lastSmoke - NukeClock.SHOW_S;
        assertTrue(after > 7 * 60 && after < 10.5 * 60, "fires feed the parcels " + after / 60 + " min after the show");
    }

    /**
     * Widening: the Hiroshima band (Uda), 5.5 km half-width 17 km beyond the 2 km fire zone; the column thins by the same factor,
     * and a parcel is seen (and rains) while its column is at least {@link SmokeParcels#GONE}, drawn fading out to half that.
     */
    @Test
    void parcelsWidenAndThinUntilTheyAreGone() {
        assertEquals(5500, HIROSHIMA.fireRadiusM() * HIROSHIMA.widen(19_000), 300);
        assertEquals(0.62, HIROSHIMA.depth(), 0.01, "full-strength haze column: 900 m visibility, 260 m top");
        double reach = HIROSHIMA.reach();
        assertEquals(SmokeParcels.GONE, HIROSHIMA.depth() / HIROSHIMA.widen(reach), 1e-9, "seen to where its column is gone");
        assertEquals(SmokeParcels.GONE / SmokeParcels.FADE, HIROSHIMA.depth() / HIROSHIMA.widen(HIROSHIMA.drawnReach()), 1e-9,
                "drawn, fading, to half of that");
        // at a 24-hour day the parcels at full strength reach the rain band's end (2 h of travel, 18 km at 2.5 m/s) and beyond
        SmokeParcels.Clock c = day(1, 2.5 * 3600);
        assertTrue(HIROSHIMA.carried(2.5 * 7200, c) > 0);
        SmokeParcels.Clock late = day(1, 40 * 3600);
        assertEquals(0, HIROSHIMA.carried(reach + 100, late), 0, "past its reach nothing is drawn");
        assertEquals(0, new SmokeParcels(15, 0, 0, 5).length(c), 0, "no fires, no parcels");
    }

    /**
     * No cut: where a parcel thins past the seen limit ({@link SmokeParcels#GONE}) its drawn column fades to nothing instead of
     * dropping from 0.05 to 0 (that step drew a straight cut across the band, `parcels-maps` in the mission folder): between
     * samples 10 m apart, never 0 beside a column of half the limit or more.
     */
    @Test
    void theBandHasNoCut() {
        for (double k : new double[]{1, 72}) {
            for (double t : new double[]{26 * 60, 32 * 60, 3600, 4 * 3600, 9 * 3600, 20 * 3600}) {
                SmokeParcels.Clock c = day(k, t);
                double last = HIROSHIMA.carried(0, c) * HIROSHIMA.depth();
                for (double s = 10; s <= HIROSHIMA.length(c); s += 10) {
                    double v = HIROSHIMA.carried(s, c) * HIROSHIMA.depth();
                    assertTrue(Math.min(v, last) > 0 || Math.max(v, last) < 0.5 * SmokeParcels.GONE,
                            "k " + k + ", " + t + " s: cut from " + last + " to " + v + " at " + s + " m");
                    last = v;
                }
            }
        }
    }

    /** On a vanilla day the haze leaves the fire zone with the wind once the fires are out: the air is not compressed. */
    @Test
    void theHazeLeavesWithTheWind() {
        SmokeParcels.Clock c = day(72, 50 * 60);
        assertEquals(0, HIROSHIMA.carried(0, c), 0, "the fires are out: nothing over them");
        double drawn = -1;
        for (double s = 0; s <= HIROSHIMA.length(c); s += 50) if (HIROSHIMA.carried(s, c) > 0) {
            drawn = s;
            break;
        }
        // the last smoke left about 10 minutes after the show: it is (50 − 30) min × 2.5 m/s ≈ 3 km downwind now
        assertTrue(drawn > 2000 && drawn < 4500, "nearest drawn parcel " + drawn + " m downwind");
    }
}
