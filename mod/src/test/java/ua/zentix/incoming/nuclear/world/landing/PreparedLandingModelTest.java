package ua.zentix.incoming.nuclear.world.landing;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static ua.zentix.incoming.nuclear.world.landing.PreparedLandingModel.*;

class PreparedLandingModelTest {
    private static final int W = 48, H = 32;
    private static int index(int x, int y, int z) { return (y * W + z) * W + x; }
    private static byte[] floor(int height) {
        byte[] cells = new byte[W * W * H];
        for (int x = 0; x < W; x++) for (int z = 0; z < W; z++) cells[index(x, height, z)] = SOLID;
        return cells;
    }
    private static Source source(long id, int x, int y, int z, double wind) {
        return new Source(id, 1, x, y, z, (int) id, 1, 2300, 0, wind, 0, 50, 3, Kind.ORDINARY);
    }
    private static Result solve(byte[] cells, List<Source> sources) {
        for (Source s : sources) cells[index(s.x(), s.y(), s.z())] = SOLID;
        return PreparedLandingModel.solve(new Snapshot(0, 0, 0, W, H, W, cells), sources, Limits.experiment());
    }

    @Test void fallsOnRoofButInsideCavityFallsToItsOwnFloor() {
        byte[] cells = floor(0);
        for (int x = 0; x < W; x++) for (int z = 0; z < W; z++) cells[index(x, 8, z)] = SOLID;
        var result = solve(cells, List.of(source(1, 12, 20, 12, 0), source(2, 32, 6, 32, 0)));
        assertEquals(2, result.count(Status.LANDED));
        assertEquals(9, result.outcomes().get(0).y());
        assertEquals(1, result.outcomes().get(1).y());
        double expected = Math.sqrt(2 * 11 / GRAVITY);
        assertEquals(expected, result.outcomes().get(0).time(), DT);
    }

    @Test void futureDeletionDoesNotEraseAStillPresentRoof() {
        var late = scheduledRoof(10);
        var early = scheduledRoof(0);
        assertEquals(9, late.outcomes().getFirst().trace().firstContact().y());
        assertTrue(late.outcomes().getFirst().trace().firstContactTime() < 10);
        assertEquals(1, late.outcomes().getFirst().y(), "Removing a later support must wake its owned material");
        assertTrue(late.outcomes().getFirst().time() > 10);
        assertEquals(1, early.outcomes().getFirst().y());
        assertEquals(Status.LANDED, late.outcomes().getFirst().status());
        assertEquals(9, late.count(Status.UNSUPPORTED_SOURCE));
    }

    private static Result scheduledRoof(double departure) {
        byte[] cells = floor(0);
        var sources = new ArrayList<Source>();
        sources.add(source(1, 24, 14, 24, 0));
        long id = 10;
        for (int x = 23; x <= 25; x++) for (int z = 23; z <= 25; z++) {
            sources.add(new Source(id++, 1, x, 8, z, 4, 1, 2300, departure, 0, 0, 0, 0, Kind.UNSUPPORTED_SOURCE));
        }
        return solve(cells, sources);
    }

    @Test void neighborLandingsShareOccupancyAndIgnoreInputIterationOrder() {
        Source left = source(1, 15, 10, 24, 1), right = source(2, 17, 10, 24, -1);
        var a = solve(floor(0), List.of(left)).outcomes().getFirst();
        var b = solve(floor(0), List.of(right)).outcomes().getFirst();
        assertEquals(16, a.x());
        assertEquals(a.x(), b.x(), "Fixture must target the same destination across the chunk seam");
        var both = solve(floor(0), List.of(left, right));
        var reverse = solve(floor(0), List.of(right, left));
        assertEquals(both.outcomes(), reverse.outcomes());
        assertEquals(2, both.count(Status.LANDED));
        var first = both.outcomes().get(0); var second = both.outcomes().get(1);
        assertFalse(first.x() == second.x() && first.y() == second.y() && first.z() == second.z(), "Duplicate landing cell");
    }

    @Test void translatingWindowDoesNotCreateAChunkBoundaryWall() {
        byte[] cells = floor(0);
        Source original = source(7, 15, 10, 24, 1);
        cells[index(15, 10, 24)] = SOLID;
        var a = PreparedLandingModel.solve(new Snapshot(0, 0, 0, W, H, W, cells), List.of(original), Limits.experiment());
        var shifted = new Source(7, 1, 16, 10, 24, 7, 1, 2300, 0, 1, 0, 50, 3, Kind.ORDINARY);
        var b = PreparedLandingModel.solve(new Snapshot(1, 0, 0, W, H, W, cells), List.of(shifted), Limits.experiment());
        assertEquals(a.outcomes().getFirst().x() + 1, b.outcomes().getFirst().x());
        assertEquals(a.outcomes().getFirst().time(), b.outcomes().getFirst().time());
        assertTrue(a.outcomes().getFirst().x() >= 16);
    }

    @Test void unknownOutsideAndUnsupportedAreDistinctNonLandingOutcomes() {
        byte[] cells = floor(0);
        cells[index(10, 3, 10)] = UNKNOWN;
        cells[index(30, 3, 30)] = UNSUPPORTED;
        var result = solve(cells, List.of(source(1, 10, 8, 10, 0), source(2, 30, 8, 30, 0), source(3, 46, 20, 24, 20)));
        assertEquals(1, result.count(Status.UNKNOWN_GEOMETRY));
        assertEquals(1, result.count(Status.UNSUPPORTED_COLLISION));
        assertEquals(1, result.count(Status.OUT_OF_WINDOW));
        assertEquals(0, result.count(Status.LANDED));
    }

    @Test void conservesEveryExactStateVolumeAndMassAndNeverLandsEarly() {
        var inputs = new ArrayList<Source>();
        for (int i = 0; i < 12; i++) inputs.add(new Source(100 + i, i % 2, 8 + i * 2, 10 + i, 20,
                300 + i, .75 + i / 20.0, 1500 + i, i / 10.0, 0, 0, 0, 0, Kind.ORDINARY));
        var result = solve(floor(0), inputs);
        assertEquals(inputs.size(), result.outcomes().size());
        for (Source input : inputs) {
            var output = result.outcomes().stream().filter(o -> o.source().id() == input.id()).findFirst().orElseThrow();
            assertEquals(input, output.source());
            assertTrue(output.time() >= input.departure());
            assertTrue(output.dueTick() / 20.0 >= output.time());
        }
        assertEquals(inputs.stream().mapToDouble(Source::volume).sum(), result.outcomes().stream().mapToDouble(o -> o.source().volume()).sum(), 1e-12);
        assertEquals(inputs.stream().mapToDouble(Source::mass).sum(), result.outcomes().stream().mapToDouble(o -> o.source().mass()).sum(), 1e-9);
    }

    @Test void snapshotIsDetachedAndWorkCapDoesNotBecomeGround() {
        byte[] cells = floor(0);
        Source input = source(1, 24, 20, 24, 0);
        cells[index(24, 20, 24)] = SOLID;
        var snapshot = new Snapshot(0, 0, 0, W, H, W, cells);
        java.util.Arrays.fill(cells, UNKNOWN);
        var limited = PreparedLandingModel.solve(snapshot, List.of(input), new Limits(1, 1, 120));
        assertEquals(1, limited.count(Status.WORK_LIMIT));
        assertEquals(1, limited.segments());
        var complete = PreparedLandingModel.solve(snapshot, List.of(input), Limits.experiment());
        assertEquals(1, complete.count(Status.LANDED));
        assertEquals(complete, PreparedLandingModel.solve(snapshot, List.of(input), Limits.experiment()));
    }

    @Test void unresolvedStructureRemainsAnObstacle() {
        var unresolved = new Source(10, 1, 24, 8, 24, 4, 1, 2300, 0, 0, 0, 0, 0, Kind.UNRESOLVED_STRUCTURE);
        var result = solve(floor(0), List.of(unresolved));
        assertEquals(Status.UNRESOLVED_STRUCTURE, result.outcomes().getFirst().status());
        assertEquals(0, result.segments());
    }

    @Test void neighborWakeWithoutMovementDoesNotDelayTheActualLanding() {
        Source material = source(1, 24, 1, 24, 0);
        Source laterNeighbor = new Source(2, 1, 25, 1, 24, 5, 1, 2300, 1000, 0, 0, 0, 0, Kind.FINE);
        var result = solve(floor(0), List.of(material, laterNeighbor));
        var landing = result.outcomes().getFirst();
        assertEquals(Status.LANDED, landing.status());
        assertEquals(1, landing.y());
        assertTrue(landing.time() < 1, "A no-op support wake became a false late arrival");
        assertEquals(0, landing.trace().wakes(), "A sideways change should not energize a supported resting block");
    }

    @Test void laterSideHoleCannotMoveMaterialWithItsVerticalSupportIntact() {
        byte[] cells = floor(0);
        for (int x = 0; x < W; x++) for (int z = 0; z < W; z++) cells[index(x, 4, z)] = SOLID;
        Source material = source(1, 24, 5, 24, 0);
        Source adjacentFloor = new Source(2, 1, 25, 4, 24, 5, 1, 2300, 1000, 0, 0, 0, 0, Kind.FINE);
        var result = solve(cells, List.of(material, adjacentFloor));
        var resting = result.outcomes().getFirst();
        assertEquals(Status.LANDED, resting.status());
        assertEquals(new Cell(24, 5, 24), new Cell(resting.x(), resting.y(), resting.z()),
                "Opening a side hole caused motion without an impulse or loss of vertical support");
        assertTrue(resting.time() < 1);
        assertEquals(0, resting.trace().contactTransfers());
    }

    @Test void adjacentChunkPartitionsShareOneCausalCohortAndLaterSupportDeletion() {
        int width = 64;
        byte[] cells = new byte[width * H * W];
        for (int x = 0; x < width; x++) for (int z = 0; z < W; z++) {
            cells[z * width + x] = SOLID;
            cells[(8 * W + z) * width + x] = SOLID;
        }
        var left = new ArrayList<Source>(); var right = new ArrayList<Source>();
        left.add(source(1, 31, 18, 24, 1));
        right.add(source(2, 33, 18, 24, -1));
        long id = 10;
        for (int x = 30; x <= 34; x++) for (int z = 23; z <= 25; z++) {
            var removal = new Source(id++, 1, x, 8, z, 5, 1, 2300, 5, 0, 0, 0, 0, Kind.FINE);
            (x < 32 ? left : right).add(removal);
        }
        var merged = new ArrayList<>(left); merged.addAll(right);
        for (Source s : merged) cells[(s.y() * W + s.z()) * width + s.x()] = SOLID;
        var snapshot = new Snapshot(0, 0, 0, width, H, W, cells);
        var forward = PreparedLandingModel.solve(snapshot, merged, Limits.experiment());
        merged.clear(); merged.addAll(right); merged.addAll(left);
        var reverse = PreparedLandingModel.solve(snapshot, merged, Limits.experiment());
        assertEquals(forward, reverse, "Chunk partition order changed shared occupancy");
        assertEquals(2, forward.count(Status.LANDED));
        assertEquals(15, forward.count(Status.DISPERSED));
        var occupied = new java.util.HashSet<Cell>();
        for (var output : forward.outcomes()) if (output.status() == Status.LANDED) {
            assertTrue(occupied.add(new Cell(output.x(), output.y(), output.z())));
            assertTrue(output.trace().firstContact().y() >= 9);
            assertEquals(1, output.y(), "A later neighboring departure left an obsolete endpoint on its former roof");
            assertTrue(output.time() > 5);
        }
    }

    @Test void finiteContactUsesHeightPotentialButFreeFlightStillHasItsAgeGuard() {
        byte[] cells = floor(0);
        var inputs = new ArrayList<Source>();
        for (int y = 1; y < H; y++) {
            Source s = source(y, 24, y, 24, 0);
            inputs.add(s); cells[index(24, y, 24)] = SOLID;
        }
        var result = PreparedLandingModel.solve(new Snapshot(0, 0, 0, W, H, W, cells), inputs, new Limits(32, 100_000, .1));
        assertEquals(inputs.size(), result.count(Status.LANDED));
        assertTrue(result.outcomes().stream().mapToDouble(Outcome::time).max().orElseThrow() > .1);
        var single = source(100, 10, 20, 10, 0);
        cells = floor(0); cells[index(10, 20, 10)] = SOLID;
        result = PreparedLandingModel.solve(new Snapshot(0, 0, 0, W, H, W, cells), List.of(single), new Limits(1, 100_000, .1));
        assertEquals(1, result.count(Status.WORK_LIMIT));
    }

    @Test void fineMaterialDispersesAtItsDepartureWithExactStateVolumeAndMass() {
        var fine = new Source(1, 1, 24, 20, 24, 25, .125, 300, 7, 10, 0, 5, 3, Kind.FINE);
        var result = solve(floor(0), List.of(fine));
        var output = result.outcomes().getFirst();
        assertEquals(Status.DISPERSED, output.status());
        assertEquals(fine, output.source());
        assertEquals(7, output.time());
        assertEquals(0, result.segments());
    }

    @Test void fullHeightDenseStackNeverCreatesUpwardEnergyOrOutsideHeight() {
        var inputs = new ArrayList<Source>();
        for (int x = 20; x < 25; x++) for (int z = 20; z < 25; z++) for (int y = 1; y < H; y++)
            inputs.add(source(inputs.size() + 1, x, y, z, 20));
        var result = solve(floor(0), inputs);
        assertEquals(inputs.size(), result.count(Status.LANDED));
        var occupied = new java.util.HashSet<Cell>();
        for (var output : result.outcomes()) {
            assertTrue(occupied.add(new Cell(output.x(), output.y(), output.z())));
            assertEquals(0, output.trace().climbs());
            assertEquals(0, output.trace().postContactRises());
            assertTrue(output.trace().firstContact().y() <= output.source().y());
            assertTrue(output.y() <= output.trace().firstContact().y());
        }
    }

    @Test void shuffledDenseSourcesHaveIdenticalFiniteResults() {
        var inputs = new ArrayList<Source>();
        for (int x = 18; x < 22; x++) for (int z = 18; z < 22; z++) for (int y = 5; y < 15; y++)
            inputs.add(source(inputs.size() + 1, x, y, z, .5));
        var first = solve(floor(0), inputs);
        Collections.shuffle(inputs, new java.util.Random(42));
        assertEquals(first, solve(floor(0), inputs));
        assertEquals(inputs.size(), first.count(Status.LANDED));
        assertTrue(first.segments() < 100_000, "Dense contact must not return to ghost-overlap integration");
        assertTrue(first.outcomes().stream().mapToLong(o -> o.trace().contactTransfers()).sum() > 0);
        for (var output : first.outcomes()) {
            assertEquals(0, output.trace().postContactRises());
            assertTrue(output.y() <= output.trace().firstContact().y());
            assertTrue(output.time() >= output.trace().firstContactTime());
        }
    }
}
