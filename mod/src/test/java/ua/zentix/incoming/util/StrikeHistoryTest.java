package ua.zentix.incoming.util;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        // before the first sample (history begun late): as a vanilla day
        StrikeHistory late = new StrikeHistory();
        late.sample(30_000, 50_000, 0, 1500, false);
        assertArrayEquals(new double[]{40_000, 0}, late.at(20_000, 31_000, 51_000, 0), 1e-9);
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
        // a counted jump later still leaves its two samples: moments after it read it
        long late = 20L * 72_000;
        h.sample(late, late, 5000, late / 20.0 + 5000 * 3.6, false);
        assertEquals(StrikeHistory.UNTIL_S / 60 + 3, h.size(), 1);
        assertEquals(5000, h.at(late + 100, late + 200, late + 200, 5000)[1], 0);
        assertEquals(0, h.at(late - 100, late + 200, late + 200, 5000)[1], 0);
    }

    /** The moment the motion age reached a value, across the show's end, the blend and a counted night. */
    @Test
    void theMomentAClockReachedAValue() {
        StrikeHistory h = new StrikeHistory();
        StrikeClock c = StrikeClock.start(0);
        double night = 11_000;
        long jumpAt = 36_000, now = 72_000;
        for (long age = 0; age <= now; age++) {
            double counted = age >= jumpAt ? night : 0;
            if (!c.ended() && age >= 24_000) c = c.end(age, age, counted);
            if (c.rampDue(age)) c = c.ramp(age, age);
            h.sample(age, age, counted, c.motion(age, counted), age == 24_000 || age == 25_200);
        }
        for (long age : new long[]{0, 777, 24_000, 24_300, 25_200, 30_000, jumpAt - 1, 50_000, now}) {
            double counted = age >= jumpAt ? night : 0;
            double m = c.motion(age, counted);
            assertEquals(age, h.ageAtMotion(c, m, now, night), 1e-6, "motion " + m);
        }
        // a value inside the counted night belongs to the jump's moment
        double before = c.motion(jumpAt - 1, 0) + 0.05, after = c.motion(jumpAt, night);
        assertEquals(jumpAt, h.ageAtMotion(c, (before + after) / 2, now, night), 1e-9);
        assertEquals(c.strike(jumpAt, jumpAt, night) - c.motion(jumpAt, night),
                c.strike(jumpAt, jumpAt, 0) - c.motion(jumpAt, 0), 1e-6, "S − M across a counted jump");
        assertTrue(Double.isNaN(h.ageAtMotion(c, c.motion(now, night) + 1, now, night)), "not reached yet");
    }

    /**
     * Unseen jumps (someone awake) are sampled on both sides and read back; a history saved before them reads the unseen
     * jumps it is settled with, crediting none before.
     */
    @Test
    void unseenJumpsAreKept() {
        StrikeHistory h = new StrikeHistory();
        h.sample(0, 0, 0, 100, 0, true);
        h.sample(1200, 1200, 0, 100, 60, false);
        h.sample(1300, 1300, 0, 8100, 65, false);
        assertEquals(4, h.size());
        assertEquals(100, h.unseenAt(1250, 2000, 8100), 0);
        assertEquals(8100, h.unseenAt(1300, 2000, 8100), 0);
        assertEquals(8000 * WorldClock.SECONDS_PER_TICK, h.groundLead(StrikeClock.start(0), 1500, 2000, 2000, 0, 8100), 1e-9);
        CompoundTag old = h.save();
        old.remove("unseen");
        StrikeHistory back = StrikeHistory.load(old);
        assertEquals(500, back.unseenAt(1250, 2000, 500), 0, "unknown reads now");
        back.settleUnseen(500);
        assertEquals(0, back.groundLead(StrikeClock.start(0), 1500, 2000, 2000, 0, 900), 1e-9, "no credit before the history knew");
    }

    /**
     * Once everything has landed a history is compacted: a steady vanilla day's 12 hours of minute samples keep only their
     * bends (burst, show's end, blend), reading the same clocks; later jumps are kept up to {@link StrikeHistory#MAX_COMPACT}
     * samples, so a ground field's history stays under 4 KB saved however many nights pass.
     */
    @Test
    void aCompactedHistoryStaysSmall() throws java.io.IOException {
        StrikeHistory h = new StrikeHistory(), full = new StrikeHistory();
        StrikeClock c = StrikeClock.start(0);
        long until = 12L * 72_000;
        for (long age = 0; age <= until; age += 20) {
            if (!c.ended() && age >= 24_000) c = c.end(age, age, 0);
            if (c.rampDue(age)) c = c.ramp(age, age);
            boolean force = age == 24_000 || age == 25_200;
            h.sample(age, age, 0, 0, c.motion(age, 0), force);
            full.sample(age, age, 0, 0, c.motion(age, 0), force);
        }
        h.compact();
        assertTrue(h.compacted() && h.size() <= 4, "a steady day keeps its bends: " + h.size());
        for (long age = 0; age <= until; age += 777) {
            assertEquals(full.at(age, until, until, 0)[0], h.at(age, until, until, 0)[0], StrikeHistory.COMPACT_TOLERANCE);
            assertEquals(full.groundLead(c, age, until, until, 0, 0), h.groundLead(c, age, until, until, 0, 0), 1e-6);
        }
        // a field's later years: thousands of nights, counted and unseen
        long age = until;
        for (int night = 1; night <= 5000; night++) {
            age += 24_000;
            // even nights everyone slept (counted), odd ones someone stayed awake (unseen)
            h.sample(age, age, night / 2 * 1000.0, (night + 1) / 2 * 1000.0, 1e9, false);
        }
        assertTrue(h.size() <= StrikeHistory.MAX_COMPACT, "samples " + h.size());
        var bytes = new java.io.ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.write(h.save(), new java.io.DataOutputStream(bytes));
        assertTrue(bytes.size() < 4096, "saved " + bytes.size() + " bytes");
        assertTrue(StrikeHistory.load(h.save()).compacted());
    }

    /**
     * A compacted history keeps each later jump once, and when it is full a jump joins the one before it: read earlier, never
     * later (a mob account's start then never reads the ground clock a night short). Review probe of PR #785 round 1 and a
     * full history over 400 nights; also a compaction that spreads samples across many jumps of the air's hours.
     */
    @Test
    void aMergedJumpIsReadEarlierNeverLater() {
        StrikeHistory h = new StrikeHistory();
        h.sample(0, 0, 0, 0, 0, true);
        h.sample(500, 500, 0, 0, 1e9, true);
        h.compact();
        for (int night = 1; night <= 60; night++) h.sample(1000L * night, 1000L * night, 0, night * 1000.0, 1e9, false);
        assertEquals(62, h.size(), "one sample a jump");
        assertEquals(1000, h.unseenAt(1001, 60_001, 60_000), 0, "the first night is read later than it happened");
        for (int night = 61; night <= 400; night++) h.sample(1000L * night, 1000L * night, 0, night * 1000.0, 1e9, false);
        assertTrue(h.size() <= StrikeHistory.MAX_COMPACT);
        for (int night = 1; night <= 400; night++) {
            assertTrue(h.unseenAt(1000L * night, 400_001, 400_000) >= night * 1000.0, "night " + night + " read later");
        }
        assertEquals(400_000, h.unseenAt(400_000, 400_001, 400_000), 0);
        // the air's hours with a jump every 300 real ticks: the spread keeps each jump at or before its moment
        StrikeHistory busy = new StrikeHistory();
        for (long age = 0; age <= 60_000; age += 100) busy.sample(age, age, 0, age / 300 * 1000.0, age / 20.0, false);
        busy.compact();
        assertTrue(busy.size() <= StrikeHistory.MAX_COMPACT / 2, "kept " + busy.size());
        for (long age = 0; age <= 60_000; age += 100) {
            assertTrue(busy.unseenAt(age, 60_001, 200_000) >= age / 300 * 1000.0, "jump at " + age + " read later");
        }
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
