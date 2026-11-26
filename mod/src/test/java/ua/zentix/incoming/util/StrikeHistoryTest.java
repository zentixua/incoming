package ua.zentix.incoming.util;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A strike's world-clock history ({@code docs/design/time.md} §8.3 rule 4) gives its strike clock at any past real moment;
 * the world clock's saved state loads from worlds saved before it.
 */
class StrikeHistoryTest {
    /** A vanilla day (1 clock tick a tick), a counted 11-hour night at real minute 30: S and M at past moments. */
    @Test
    void theStrikeClockAtAnyPastMoment() {
        StrikeHistory h = new StrikeHistory();
        StrikeClock c = StrikeClock.start(0);
        double night = 11_000;
        long jumpAt = 36_000;
        for (long age = 0; age <= 72_000; age++) {
            double counted = age >= jumpAt ? night : 0;
            if (!c.ended() && age >= 24_000) c = c.end(age, age, counted);
            if (c.rampDue(age)) c = c.ramp(age, age);
            h.sample(age, age, counted, c.motion(age, counted), age == 24_000 || age == 25_200);
        }
        assertEquals(61 + 1, h.size(), "a sample a real minute (the show's and the blend's ends fall on minutes), both sides of the jump");
        for (long age : new long[]{0, 1234, 24_000, 24_600, 30_000, jumpAt - 1, jumpAt, 50_000}) {
            double counted = age >= jumpAt ? night : 0;
            double[] r = h.at(age, 72_000, 72_000, night);
            assertEquals(age, r[0], 1e-9, "the clock without jumps at " + age);
            assertEquals(counted, r[1], 0, "the counted jumps at " + age);
            assertEquals(c.strike(age, age, counted), c.strike(age, r[0], r[1]), 1e-9);
        }
        // past the last sample: interpolated to now
        assertArrayEquals(new double[]{80_000, night}, h.at(80_000, 90_000, 90_000, night), 1e-9);
        // saved and loaded whole
        StrikeHistory back = StrikeHistory.load(h.save());
        assertEquals(h.size(), back.size());
        assertArrayEquals(h.at(40_000, 72_000, 72_000, night), back.at(40_000, 72_000, 72_000, night), 0);
    }

    /** No samples after the motion age passes the last fallout arrival (~11 h): the history stays bounded. */
    @Test
    void samplesStopAfterTheLastArrival() {
        StrikeHistory h = new StrikeHistory();
        for (long age = 0; age < 20L * 72_000; age += 1200) h.sample(age, age, 0, age / 20.0, false);
        assertEquals(StrikeHistory.UNTIL_S / 60 + 1, h.size(), 1);
    }

    /** A world saved before the clock without jumps, the pace and the jumps loads with the vanilla pace and no jumps. */
    @Test
    void anOldWorldClockLoads() {
        CompoundTag old = new CompoundTag();
        old.putDouble("ticks", 123_456);
        WorldClock c = WorldClock.load(old, null);
        assertArrayEquals(new double[]{123_456, 123_456, WorldClock.Steps.VANILLA_PACE}, c.readingsForTest(), 0);
        assertArrayEquals(new double[3], c.jumpsForTest("minecraft:overworld"), 0);
        CompoundTag back = c.save(new CompoundTag(), null);
        assertEquals(123_456, back.getDouble("rate_ticks"), 0);
    }
}
