package ua.zentix.incoming.grid;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.world.WorkClock;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void distantBucketTailNearTwoOverlappingPlayersIsIssuedFirst() throws ReflectiveOperationException {
        BlackoutWorld world = new BlackoutWorld();
        LongArrayList players = field(world, "players");
        players.add(ChunkPos.asLong(50, 30));
        players.add(ChunkPos.asLong(51, 30));
        BlackoutWorld.Sweep sweep = built(instant());
        LongOpenHashSet expected = contents(sweep), near = new LongOpenHashSet(), issued = new LongOpenHashSet();
        for (long c : expected) {
            for (long p : players) {
                if (Math.max(Math.abs(ChunkPos.getX(c) - ChunkPos.getX(p)),
                        Math.abs(ChunkPos.getZ(c) - ChunkPos.getZ(p))) <= BlackoutWorld.NEAR_CHUNKS) near.add(c);
            }
        }
        assertTrue(near.size() > BlackoutWorld.CHUNKS_PER_UNIT);
        WorkClock clock = WorkClock.counting(1_000_000);
        while (!sweep.done()) {
            clock.start(1_000_000);
            world.issueDue(sweep, 100, clock, c -> {
                assertTrue(issued.add(c), "overlapping players or far replay duplicated a chunk");
                if (!near.contains(c)) assertTrue(issued.containsAll(near), "far chunk overtook near tail");
            });
        }
        assertEquals(expected, issued);
    }

    @Test
    void closestChunksOfEveryPlayerLeadEachDistanceRing() throws ReflectiveOperationException {
        BlackoutWorld world = new BlackoutWorld();
        LongArrayList players = field(world, "players");
        players.add(ChunkPos.asLong(50, 30));
        players.add(ChunkPos.asLong(-45, -30));
        BlackoutWorld.Sweep sweep = built(instant());
        WorkClock clock = WorkClock.counting(1_000_000);
        LongArrayList issued = new LongArrayList();
        int[] lastDistance = {-1};
        while (!sweep.done()) {
            clock.start(1_000_000);
            world.issueDue(sweep, 100, clock, c -> {
                int distance = Integer.MAX_VALUE;
                for (long p : players) distance = Math.min(distance, Math.max(
                        Math.abs(ChunkPos.getX(c) - ChunkPos.getX(p)), Math.abs(ChunkPos.getZ(c) - ChunkPos.getZ(p))));
                if (distance <= BlackoutWorld.NEAR_CHUNKS) {
                    assertTrue(distance >= lastDistance[0], "more distant near chunk overtook a closer player chunk");
                    lastDistance[0] = distance;
                }
                issued.add(c);
            });
            assertEquals(players.getLong(0), issued.getLong(0), "first player centre must lead the first unit");
            assertEquals(players.getLong(1), issued.getLong(1), "second player centre must not wait for the first square");
        }
    }

    @Test
    void nearQueueGetsTheNextTickInsteadOfWaitingForWholeFarBucket() throws ReflectiveOperationException {
        BlackoutWorld world = new BlackoutWorld();
        LongArrayList players = field(world, "players");
        players.add(ChunkPos.asLong(50, 30));
        BlackoutWorld.Sweep sweep = built(instant());
        WorkClock clock = WorkClock.counting(1_000_000);
        clock.start(4_000_000);
        world.issueDue(sweep, 100, clock, world::enqueue);
        LongArrayFIFOQueue near = field(world, "near");
        assertFalse(near.isEmpty());
        assertFalse(sweep.done());
        assertEquals(1, clock.units(), "yield to newly admitted near lamps");
        clock.start(1_000_000);
        world.issueDue(sweep, 100, clock, c -> { throw new AssertionError("near lamps must convert first"); });
        assertTrue(clock.canStart(), "the next tick's one-unit guarantee remains for conversion");
        assertEquals(1, clock.units());
    }

    @Test
    void productionNearFifoStartsWithBothPlayersInLargeAndSmallBuckets() throws ReflectiveOperationException {
        for (double radius : new double[]{1200, 80}) {
            BlackoutWorld.Sweep sweep = built(new Outage(1, 0, 0, radius, 100,
                    Double.POSITIVE_INFINITY, Outage.NEVER, 0, -1));
            LongArrayList bucket = sweep.byDue.get(100);
            assertTrue(bucket.size() > 2);
            BlackoutWorld world = new BlackoutWorld();
            LongArrayList players = field(world, "players");
            players.add(radius > 100 ? ChunkPos.asLong(50, 30) : bucket.getLong(bucket.size() - 1));
            players.add(radius > 100 ? ChunkPos.asLong(-45, -30) : bucket.getLong(0));
            assertEquals(radius > 100, bucket.size() > BlackoutWorld.CHUNKS_PER_UNIT);
            WorkClock clock = WorkClock.counting(1_000_000);
            clock.start(4_000_000);
            world.issueDue(sweep, 100, clock, world::enqueue);
            LongArrayFIFOQueue near = field(world, "near");
            assertTrue(near.size() > 2);
            LongOpenHashSet first = new LongOpenHashSet();
            first.add(near.dequeueLong());
            first.add(near.dequeueLong());
            assertEquals(new LongOpenHashSet(players), first, "either player's centre waited behind a farther chunk");
            int previous = 0;
            while (!near.isEmpty()) {
                long c = near.dequeueLong();
                int distance = Integer.MAX_VALUE;
                for (long p : players) distance = Math.min(distance, Math.max(
                        Math.abs(ChunkPos.getX(c) - ChunkPos.getX(p)), Math.abs(ChunkPos.getZ(c) - ChunkPos.getZ(p))));
                assertTrue(distance >= previous, "production near FIFO changed nearest-player order");
                previous = distance;
            }
        }
    }

    @Test
    void partialBucketProgressSurvivesSavedDataRoundTrip() throws ReflectiveOperationException {
        PowerGrid grid = new PowerGrid();
        Outage outage = grid.addOutage(0, 0, 1200, 100, Double.MAX_VALUE, Outage.NEVER, 0, -1);
        BlackoutWorld.Sweep sweep = built(outage);
        LongOpenHashSet expected = contents(sweep);
        WorkClock clock = WorkClock.counting(1_000_000);
        clock.start(1_000_000);
        BlackoutWorld world = new BlackoutWorld();
        world.issueDue(sweep, 100, clock, c -> {});
        grid.swept(outage.id(), false, sweep.issuedThrough());
        var load = PowerGrid.class.getDeclaredMethod("load", CompoundTag.class, HolderLookup.Provider.class);
        load.setAccessible(true);
        PowerGrid reloaded = (PowerGrid) load.invoke(null, grid.save(new CompoundTag(), null), null);
        assertEquals(99, reloaded.swept(outage.id(), false));
        assertTrue(reloaded.swept(outage.id(), false) < reloaded.outages().getFirst().lastDark());
        BlackoutWorld.Sweep restarted = built(reloaded.outages().getFirst());
        LongOpenHashSet replay = new LongOpenHashSet();
        while (!restarted.done()) {
            clock.start(1_000_000);
            world.issueDue(restarted, 100, clock, c -> assertTrue(replay.add(c)));
        }
        assertEquals(expected, replay);
        reloaded.swept(outage.id(), false, restarted.issuedThrough());
        PowerGrid finished = (PowerGrid) load.invoke(null, reloaded.save(new CompoundTag(), null), null);
        assertEquals(Long.MAX_VALUE, finished.swept(outage.id(), false));
    }

    @Test
    void actualDistrictDeadlinesRemainOrderedAndPartialProgressReplaysOnRestart() {
        Outage outage = new Outage(2, -700, 400, 1200, 100, 8, Outage.NEVER, 0, -1);
        BlackoutWorld.Sweep sweep = built(outage);
        LongOpenHashSet expected = contents(sweep), issued = new LongOpenHashSet();
        BlackoutWorld world = new BlackoutWorld();
        WorkClock clock = WorkClock.counting(1_000_000);
        long[] previousDue = {Long.MIN_VALUE};
        for (long now = sweep.first - 1; !sweep.done(); now++) {
            clock.start(1_000_000);
            long time = now;
            world.issueDue(sweep, now, clock, c -> {
                long due = outage.darkAt(Districts.of(ChunkPos.getX(c), ChunkPos.getZ(c)));
                assertTrue(due <= time, "chunk issued before physical deadline");
                assertTrue(due >= previousDue[0], "later deadline overtook an unfinished one");
                previousDue[0] = due;
                assertTrue(issued.add(c));
            });
            assertTrue(sweep.issuedThrough() < sweep.cursor || sweep.done());
            assertTrue(now < outage.lastDark() + 10_000);
        }
        assertEquals(expected, issued);

        // A restart during one huge bucket must not save it as completed just because it is already due.
        BlackoutWorld.Sweep partial = built(instant());
        clock.start(1_000_000);
        world.issueDue(partial, 100, clock, c -> {});
        assertEquals(99, partial.issuedThrough());
        assertFalse(partial.done());
        BlackoutWorld.Sweep restarted = built(partial.outage);
        LongOpenHashSet replay = new LongOpenHashSet();
        while (!restarted.done()) {
            clock.start(1_000_000);
            world.issueDue(restarted, 100, clock, c -> assertTrue(replay.add(c)));
        }
        assertEquals(contents(built(instant())), replay);
        assertEquals(Long.MAX_VALUE, restarted.issuedThrough());
    }

    @Test
    void restorationAndTwoSweepsUseTheSameClock() {
        Outage outage = instant().restoring(200, 0);
        BlackoutWorld.Sweep first = new BlackoutWorld.Sweep(outage, true), second = built(instant());
        while (!first.built()) first.buildRow();
        LongOpenHashSet expected = contents(first), issued = new LongOpenHashSet();
        BlackoutWorld world = new BlackoutWorld();
        WorkClock clock = WorkClock.counting(1_000_000);
        clock.start(1_000_000);
        world.issueDue(first, 199, clock, c -> { throw new AssertionError("restored before due"); });
        assertEquals(0, clock.units());
        while (!first.done()) {
            clock.start(1_000_000);
            world.issueDue(first, 200, clock, c -> assertTrue(issued.add(c)));
            world.issueDue(second, 200, clock, c -> { throw new AssertionError("second sweep bypassed shared cap"); });
        }
        assertEquals(expected, issued);
        assertFalse(second.done());
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(BlackoutWorld world, String name) throws ReflectiveOperationException {
        Field field = BlackoutWorld.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(world);
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
