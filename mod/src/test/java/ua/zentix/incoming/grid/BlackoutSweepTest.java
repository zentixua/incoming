package ua.zentix.incoming.grid;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.world.WorkClock;

import static org.junit.jupiter.api.Assertions.*;

class BlackoutSweepTest {
    @Test
    void largeInstantBucketYieldsToTheExistingClockAndResumesWithoutLoss() {
        BlackoutWorld world = new BlackoutWorld();
        BlackoutWorld.Sweep sweep = built(instant());
        LongOpenHashSet expected = contents(sweep), issued = new LongOpenHashSet();
        assertTrue(expected.size() > 10_000);
        WorkClock clock = WorkClock.counting(1_000_000);
        int ticks = 0;
        while (!sweep.done()) {
            int before = issued.size(), units = clock.units();
            clock.start(1_000_000);
            world.issueDue(sweep, 100, clock, c -> assertTrue(issued.add(c), "duplicate chunk " + c));
            assertEquals(1, clock.units() - units, "due work must consume the shared clock");
            assertTrue(issued.size() - before <= BlackoutWorld.CHUNKS_PER_UNIT, "unbounded due bucket");
            assertTrue(++ticks < 10_000);
        }
        assertTrue(ticks > 1);
        assertEquals(expected, issued);
    }

    @Test
    void exhaustedSharedClockDoesNotIssueEvenOneDueChunk() {
        BlackoutWorld.Sweep sweep = built(instant());
        WorkClock clock = WorkClock.counting(1_000_000);
        clock.start(1_000_000);
        clock.end(clock.begin()); // another grid/world unit already used this tick's guarantee
        LongArrayList issued = new LongArrayList();
        new BlackoutWorld().issueDue(sweep, 100, clock, issued::add);
        assertTrue(issued.isEmpty(), "due issuance bypassed an exhausted shared clock");
        assertFalse(sweep.done());
    }

    private static Outage instant() {
        return new Outage(1, 0, 0, 1200, 100, Double.POSITIVE_INFINITY, Outage.NEVER, 0, -1);
    }

    private static BlackoutWorld.Sweep built(Outage outage) {
        BlackoutWorld.Sweep sweep = new BlackoutWorld.Sweep(outage, false);
        while (!sweep.built()) sweep.buildRow();
        return sweep;
    }

    private static LongOpenHashSet contents(BlackoutWorld.Sweep sweep) {
        LongOpenHashSet result = new LongOpenHashSet();
        for (LongArrayList chunks : sweep.byDue.values()) result.addAll(chunks);
        return result;
    }
}
