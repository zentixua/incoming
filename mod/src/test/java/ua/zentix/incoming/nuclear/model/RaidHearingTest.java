package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponType;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A raid's hearing dose ({@code client-sound.md}, the raid window measurement): Shaheds landing 30–50 blocks away, one every
 * 1–2 s as the scene bench's launchers release them. The ear sums blasts within 2 minutes of event time
 * ({@link HearingModel.Ear}); with the window on the world clock a vanilla day made it 1.7 real seconds, so a gap of 2 s
 * split the raid into several doses.
 */
class RaidHearingTest {
    /** Seconds between hits (1–2 s, alternating) and distances, m, of the first ten seconds. */
    private static final double[] GAPS = {0, 1.0, 2.0, 1.5, 2.0, 1.0, 2.0, 1.5};
    private static final double[] METRES = {30, 42, 35, 50, 38, 45, 33, 48};

    /** High-frequency shift right after the last hit, dB, with recovery-clock and event-time hours per real second. */
    private static double shiftAfterRaid(double recoveryPerSecond, double eventPerSecond) {
        HearingModel.Ear ear = new HearingModel.Ear();
        double charge = WeaponType.DRONE.spec().charge(), t = 0;
        for (int i = 0; i < GAPS.length; i++) {
            t += GAPS[i];
            ear.blast(HearingModel.blastExposureAt(charge, METRES[i]), t * recoveryPerSecond, t * eventPerSecond);
        }
        // at the 2-minute anchor of the last dose's line
        double hour = t * recoveryPerSecond + HearingModel.FIRST_HOURS, event = t * eventPerSecond + HearingModel.FIRST_HOURS;
        return ear.shift(hour, event);
    }

    @Test
    void theWholeRaidIsOneDose() {
        double hour = 1 / 3600.0;
        double eventTime = shiftAfterRaid(hour, hour);
        double charge = WeaponType.DRONE.spec().charge(), sum = 0;
        for (double m : METRES) sum += Math.pow(10, HearingModel.blastExposureAt(charge, m) / 10);
        // every hit within 2 real minutes: one dose of the summed energy
        assertEquals(HearingModel.shift(10 * Math.log10(sum)), eventTime, 1e-9);
        // the window on a vanilla day's world clock (72×): split at each 2 s gap, the shift of the loudest piece only
        double worldClock = shiftAfterRaid(72 * hour, 72 * hour);
        System.out.printf(Locale.ROOT, "RAIDHEARING one dose %.1f dB, split by the 1.7 s window %.1f dB%n", eventTime, worldClock);
        assertTrue(eventTime > worldClock + 3, eventTime + " vs " + worldClock);
    }
}
