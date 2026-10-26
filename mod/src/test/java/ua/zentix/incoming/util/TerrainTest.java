package ua.zentix.incoming.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainTest {
    /** Column without Minecraft: available data and generator call counts. */
    private static final class FakeColumn implements Terrain.Column {
        final boolean ready, ceiling, generator;
        final int chunk, sea, base, bottom, top;
        int generatorCalls, heightCalls, lastX, lastZ;
        /** Survey result ({@link Terrain.Column#surveyed}); null while pending. */
        Terrain.Surface surveyed;
        int surveyCalls;

        FakeColumn(boolean ready, int chunk, boolean ceiling, boolean generator, int sea, int base) {
            this(ready, chunk, ceiling, generator, sea, base, -64, 320);
        }

        FakeColumn(boolean ready, int chunk, boolean ceiling, boolean generator, int sea, int base, int bottom, int top) {
            this.ready = ready;
            this.chunk = chunk;
            this.ceiling = ceiling;
            this.generator = generator;
            this.sea = sea;
            this.base = base;
            this.bottom = bottom;
            this.top = top;
        }

        @Override public boolean ready() { return ready; }
        @Override public int chunkHeight(int x, int z) { heightCalls++; lastX = x; lastZ = z; return chunk; }
        @Override public boolean ceiling() { return ceiling; }
        @Override public boolean hasGenerator() { return generator; }
        @Override public int sea() { return sea; }
        @Override public int generatorBase(int x, int z) { generatorCalls++; return base; }
        @Override public boolean outside(int y) { return y < bottom || y >= top; }
        @Override public int bottom() { return bottom; }
        @Override public Terrain.View surveyed() {
            surveyCalls++;
            return surveyed == null ? null : Terrain.View.constant(surveyed.y(), surveyed.source());
        }
    }

    private static Terrain.Surface choose(Terrain.Column column, Terrain.Allowed allowed) {
        return Terrain.choose(column, allowed).at(0, 0);
    }

    private static Terrain.Surface surface(int y, Terrain.Source source) {
        return new Terrain.Surface(y, source);
    }

    @Test
    void selectionDefersHeightReadsAndKeepsWorldCoordinates() {
        FakeColumn c = new FakeColumn(true, 107, false, true, 63, 70);
        Terrain.View view = Terrain.choose(c, Terrain.Allowed.RELIEF);
        assertEquals(0, c.heightCalls, "selecting a chunk must not read an arbitrary anchor column");
        assertEquals(surface(107, Terrain.Source.CHUNK), view.at(-17, 31));
        assertEquals(1, c.heightCalls);
        assertEquals(-17, c.lastX);
        assertEquals(31, c.lastZ);
        assertEquals(0, c.surveyCalls);
        assertEquals(0, c.generatorCalls);

        FakeColumn unready = new FakeColumn(false, 0, false, true, 63, 70);
        Terrain.View generated = Terrain.choose(unready, Terrain.Allowed.ORDER);
        assertEquals(0, unready.generatorCalls, "selection alone must not generate terrain");
        assertEquals(surface(70, Terrain.Source.GENERATOR), generated.at(0, 0));
        assertEquals(1, unready.generatorCalls);
    }

    @Test
    void readyChunkWinsOverEverySource() {
        FakeColumn c = new FakeColumn(true, 107, false, true, 63, 70);
        assertEquals(surface(107, Terrain.Source.CHUNK), choose(c, Terrain.Allowed.ORDER.withMap(Optional.of(90))));
        assertEquals(surface(107, Terrain.Source.CHUNK), choose(c, Terrain.Allowed.CHUNK));
        assertEquals(surface(107, Terrain.Source.CHUNK), choose(c, Terrain.Allowed.FLIGHT));
        assertEquals(0, c.generatorCalls, "ready chunks must not query the generator");
    }

    @Test
    void clientMapBeforeGenerator() {
        // Imported city: the generator sees the street below the roofs (63 below a roof at 107).
        FakeColumn c = new FakeColumn(false, 0, false, true, 63, 63);
        assertEquals(surface(107, Terrain.Source.CLIENT_MAP), choose(c, Terrain.Allowed.ORDER.withMap(Optional.of(107))));
        assertEquals(0, c.generatorCalls);
        // Ignore a surface whose block lies outside the world build heights.
        assertEquals(surface(63, Terrain.Source.GENERATOR), choose(c, Terrain.Allowed.ORDER.withMap(Optional.of(-64))));
        assertEquals(surface(63, Terrain.Source.GENERATOR), choose(c, Terrain.Allowed.ORDER.withMap(Optional.of(321))));
        assertEquals(surface(320, Terrain.Source.CLIENT_MAP), choose(c, Terrain.Allowed.ORDER.withMap(Optional.of(320))));
    }

    @Test
    void generatorNotBelowSea() {
        // A world upgraded from 1.17 to 1.21: the generator returns the world bottom.
        assertEquals(surface(63, Terrain.Source.GENERATOR), choose(new FakeColumn(false, 0, false, true, 63, -64), Terrain.Allowed.ORDER));
        assertEquals(surface(107, Terrain.Source.GENERATOR), choose(new FakeColumn(false, 0, false, true, 63, 107), Terrain.Allowed.ORDER));
        // Flat world: sea at -63, ground at -60.
        assertEquals(surface(-60, Terrain.Source.GENERATOR), choose(new FakeColumn(false, 0, false, true, -63, -60), Terrain.Allowed.ORDER));
    }

    @Test
    void tickSourcesNeverAskGenerator() {
        FakeColumn c = new FakeColumn(false, 0, false, true, -63, -60);
        assertEquals(surface(-63, Terrain.Source.SEA), choose(c, Terrain.Allowed.FLIGHT));
        assertEquals(surface(-64, Terrain.Source.UNKNOWN), choose(c, Terrain.Allowed.CHUNK));
        assertEquals(0, c.generatorCalls, "tick queries must not run the generator");
    }

    @Test
    void ceilingIsNotGroundForFlight() {
        // Nether: a ready chunk heightmap contains the top of the ceiling.
        FakeColumn nether = new FakeColumn(true, 128, true, true, 32, 40, 0, 256);
        assertEquals(surface(128, Terrain.Source.CEILING), choose(nether, Terrain.Allowed.CHUNK));
        assertEquals(surface(32, Terrain.Source.SEA), choose(nether, Terrain.Allowed.FLIGHT));
        assertEquals(surface(128, Terrain.Source.CEILING), choose(nether, Terrain.Allowed.ORDER));
        assertEquals(surface(128, Terrain.Source.CEILING), choose(nether, Terrain.Allowed.RELIEF));
        assertEquals(surface(0, Terrain.Source.UNKNOWN), choose(nether, Terrain.Allowed.VIRTUAL_RELIEF));
        assertEquals(0, nether.generatorCalls);
    }

    @Test
    void clientHasNoGenerator() {
        FakeColumn client = new FakeColumn(false, 0, false, false, 63, 70);
        assertEquals(surface(-64, Terrain.Source.UNKNOWN), choose(client, Terrain.Allowed.ORDER));
        assertEquals(surface(-64, Terrain.Source.UNKNOWN), choose(client, Terrain.Allowed.FLIGHT));
        assertEquals(surface(90, Terrain.Source.CLIENT_MAP), choose(client, Terrain.Allowed.CHUNK.withMap(Optional.of(90))));
    }

    @Test
    void clientMapNeedsHint() {
        assertThrows(IllegalArgumentException.class, () -> new Terrain.Allowed(Set.of(Terrain.Source.CLIENT_MAP), Optional.empty(), false));
        assertEquals(Terrain.Allowed.ORDER, Terrain.Allowed.ORDER.withMap(Optional.empty()));
    }

    /** Flight relief uses ready chunks, then background surveys; it never runs the generator in the caller. */
    @Test
    void reliefTakesSurveyNeverAsksGenerator() {
        FakeColumn c = new FakeColumn(false, 0, false, true, 63, 120);
        // Survey pending: return the world bottom, as for an unready chunk.
        assertEquals(surface(-64, Terrain.Source.UNKNOWN), choose(c, Terrain.Allowed.RELIEF));
        c.surveyed = surface(97, Terrain.Source.DISK);
        assertEquals(surface(97, Terrain.Source.DISK), choose(c, Terrain.Allowed.RELIEF));
        c.surveyed = surface(88, Terrain.Source.GENERATOR);
        assertEquals(surface(88, Terrain.Source.GENERATOR), choose(c, Terrain.Allowed.RELIEF));
        assertEquals(0, c.generatorCalls, "tick queries must not run the generator");
        // A ready chunk is more accurate; do not query the survey.
        FakeColumn ready = new FakeColumn(true, 70, false, true, 63, 120);
        ready.surveyed = surface(97, Terrain.Source.DISK);
        assertEquals(surface(70, Terrain.Source.CHUNK), choose(ready, Terrain.Allowed.RELIEF));
        assertEquals(0, ready.surveyCalls);
    }

    /** Source sets without background sources do not query the survey. */
    @Test
    void otherSetsIgnoreSurvey() {
        FakeColumn c = new FakeColumn(false, 0, false, true, 63, 120);
        c.surveyed = surface(97, Terrain.Source.DISK);
        assertEquals(surface(-64, Terrain.Source.UNKNOWN), choose(c, Terrain.Allowed.CHUNK));
        assertEquals(surface(63, Terrain.Source.SEA), choose(c, Terrain.Allowed.FLIGHT));
        assertEquals(surface(120, Terrain.Source.GENERATOR), choose(c, Terrain.Allowed.ORDER));
        assertEquals(0, c.surveyCalls);
        assertThrows(IllegalArgumentException.class, () -> new Terrain.Allowed(Set.of(Terrain.Source.DISK), Optional.empty(), false));
    }

    @Test
    void allReadyReachesTheEnd() {
        assertEquals(1, Terrain.readyFraction(1, 1, 900, -300, (x, z) -> true));
    }

    @Test
    void stopsJustBeforeFirstUnreadyChunk() {
        // Along +x: chunks 0..3 are ready; chunk 4 (x = 64..79) is not.
        double t = Terrain.readyFraction(8, 8, 108, 8, (x, z) -> x < 4);
        double x = 8 + t * 100;
        assertTrue(x < 64 && x > 63.9, "clipped at the boundary: " + x);
    }

    @Test
    void unreadyStartGivesZero() {
        assertEquals(0, Terrain.readyFraction(8, 8, 100, 100, (x, z) -> false));
    }

    @Test
    void visitsEveryColumnTheSegmentCrosses() {
        Set<Long> seen = new HashSet<>();
        Terrain.readyFraction(-5.5, 3.25, 70.5, 47.75, (x, z) -> {
            seen.add(((long) x << 32) | (z & 0xFFFFFFFFL));
            return true;
        });
        // Fine-step reference: every column crossed by the segment must be queried.
        for (int i = 0; i <= 10000; i++) {
            double k = i / 10000.0;
            int cx = (int) Math.floor((-5.5 + k * 76) / 16), cz = (int) Math.floor((3.25 + k * 44.5) / 16);
            assertTrue(seen.contains(((long) cx << 32) | (cz & 0xFFFFFFFFL)), "missed column " + cx + "," + cz);
        }
    }

    @Test
    void negativeDirectionStopsBeforeBoundary() {
        // Along -z from chunk z = 0: chunk z = -1 is unready; the boundary is z = 0.
        double t = Terrain.readyFraction(8, 12, 8, -40, (x, z) -> z >= 0);
        double z = 12 - t * 52;
        assertTrue(z >= 0 && z < 0.1, "clipped at the boundary: " + z);
    }
}
