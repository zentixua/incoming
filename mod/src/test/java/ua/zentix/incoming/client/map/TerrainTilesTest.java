package ua.zentix.incoming.client.map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainTilesTest {
    /** The tile is no smaller than a screen pixel: 1 pixel per block and larger - detailed, then - twice as large for each octave. */
    @Test
    void levelFollowsScale() {
        assertEquals(0, TerrainTiles.levelFor(8));
        assertEquals(0, TerrainTiles.levelFor(1));
        assertEquals(0, TerrainTiles.levelFor(0.6));
        assertEquals(1, TerrainTiles.levelFor(0.5));
        assertEquals(3, TerrainTiles.levelFor(1.0 / 10));
        assertEquals(TerrainTiles.MAX_LEVEL, TerrainTiles.levelFor(1.0 / 1000), "there are no larger tiles than the largest ones");
    }

    /** The shadow is like a vanilla map: the slope upward to the south is lighter, downward is darker, smooth - normal; the water darkens with depth. */
    @Test
    void slopesAndWaterShade() {
        assertEquals(MapColor.Brightness.NORMAL, TerrainTiles.shade(64, 0, 64, 1, 0));
        assertEquals(MapColor.Brightness.NORMAL, TerrainTiles.shade(64, 0, 64, 1, 1));
        assertEquals(MapColor.Brightness.HIGH, TerrainTiles.shade(64, 0, 62, 1, 0), "higher than its northern neighbor");
        assertEquals(MapColor.Brightness.LOW, TerrainTiles.shade(64, 0, 66, 1, 0), "below its northern neighbor");
        assertEquals(MapColor.Brightness.NORMAL, TerrainTiles.shade(64, 0, 63, 16, 0), "on a large cage the drop block is level");
        assertEquals(MapColor.Brightness.HIGH, TerrainTiles.shade(64, 1, 64, 1, 0), "finely");
        assertEquals(MapColor.Brightness.LOW, TerrainTiles.shade(64, 12, 64, 1, 0), "deep");
    }

    /**
     * The first row of tiles without the north tile in memory: its height is estimated from the south slope, so the
     *  slope is shaded the same as the other rows, without reading another DH section for each tile.
     */
    @Test
    void firstRowShadedBySlopeInside() {
        TerrainTiles.Columns c = filled((x, z) -> 60 + 2 * z); // slope up to the south by 2 block per cell
        int[] pixels = TerrainTiles.paint(c, 0, null);
        int high = MapColor.GRASS.calculateRGBColor(MapColor.Brightness.HIGH);
        assertEquals(high, pixels[0], "the first row is like the second");
        assertEquals(high, pixels[TerrainTiles.SIZE]);
        assertEquals(0, TerrainTiles.paint(new TerrainTiles.Columns(), 0, null)[0], "without data - transparent");
    }

    /** North tile in memory: the first row is shaded by its last row, not by the slope estimate. */
    @Test
    void firstRowShadedByNorthTile() {
        int n = TerrainTiles.SIZE;
        TerrainTiles.Columns flat = filled((x, z) -> 64);
        // to the north - cliff: the last row of northern tiles is 10 blocks above
        TerrainTiles.Columns north = filled((x, z) -> z == n - 1 ? 74 : 64);
        assertEquals(MapColor.GRASS.calculateRGBColor(MapColor.Brightness.LOW), TerrainTiles.paint(flat, 0, north)[0], "below the neighbor's northern row");
        assertEquals(MapColor.GRASS.calculateRGBColor(MapColor.Brightness.NORMAL), TerrainTiles.paint(flat, 0, null)[0], "without a neighbor - exactly");
        // my neighbor has a hole in the last row - graded by slope
        TerrainTiles.Columns holed = filled((x, z) -> z == n - 1 ? Integer.MIN_VALUE : 64);
        assertEquals(MapColor.GRASS.calculateRGBColor(MapColor.Brightness.NORMAL), TerrainTiles.paint(flat, 0, holed)[0]);
    }

    /** The “relief has changed” mark on the chunk is on the tiles above it at all levels, and minus the coordinates. */
    @Test
    void changedChunkMarksTilesAtNegativeCoordinates() {
        TerrainTiles.Layer layer = layer();
        TerrainTiles.Key[] over = {new TerrainTiles.Key(0, -1, -2), new TerrainTiles.Key(1, -1, -1), new TerrainTiles.Key(4, -1, -1)};
        TerrainTiles.Key[] beside = {new TerrainTiles.Key(0, 0, -2), new TerrainTiles.Key(0, -1, -1), new TerrainTiles.Key(0, -2, -2)};
        for (TerrainTiles.Key k : over) layer.tiles.put(k, new TerrainTiles.Tile(k));
        for (TerrainTiles.Key k : beside) layer.tiles.put(k, new TerrainTiles.Tile(k));
        // chunk (−1, −5): blocks x −16...−1, z −80...−65 - tile (−1, −2) level 0
        TerrainTiles.changed(layer, -1, -5);
        for (TerrainTiles.Key k : over) assertTrue(layer.tiles.get(k).stale, "not marked " + k);
        for (TerrainTiles.Key k : beside) assertFalse(layer.tiles.get(k).stale, "neighboring one is marked " + k);
    }

    /**
     * A large tile of four small ones: a pixel - a child’s cell in the middle of its cell (for a tile of level 1 this is the same
     *  column that a tile from the source would read; for a larger one - shifted a quarter of the cell to the southeast, not visible to the eye
     * ), children - northwest, northeast, southwest, southeast; Children's holes remain holes.
     */
    @Test
    void composeSamplesChildrenAtCellMiddles() {
        int n = TerrainTiles.SIZE;
        TerrainTiles.Columns[] kids = new TerrainTiles.Columns[4];
        for (int k = 0; k < 4; k++) {
            kids[k] = new TerrainTiles.Columns();
            for (int i = 0; i < n * n; i++) {
                if (k == 3) continue; // southeast is empty
                // height encodes the child (8000·k) and the cell number (row·64 + column)
                kids[k].set(i, new TerrainSource.Column(k * 8000 + i % 8000, MapColor.STONE, 0));
            }
        }
        TerrainTiles.Columns c = TerrainTiles.compose(kids);
        // pixel (0, 0) - cell (1, 1) of the northwestern child
        assertEquals(n + 1, c.height[0]);
        // pixel (40, 0) - cell (81 − 64 = 17, 1) northeast
        assertEquals(8000 + n + 17, c.height[40]);
        // pixel (0, 40) - cell (1, 17) southwestern
        assertEquals(16000 + (17 * n + 1) % 8000, c.height[40 * n]);
        assertFalse(c.has(40 * n + 40), "an empty child has a hole");
        assertEquals(3 * n * n / 4, c.filled);
    }

    /** First, what does not exist or what has become obsolete due to an event; empty ones are asked more often than incomplete ones; complete - as the source says. */
    @Test
    void urgencyOrder() {
        long s = 1_000_000_000L, now = 1000 * s;
        TerrainTiles.Columns full = new TerrainTiles.Columns();
        for (int i = 0; i < TerrainTiles.SIZE * TerrainTiles.SIZE; i++) full.set(i, new TerrainSource.Column(64, MapColor.GRASS, 0));
        TerrainTiles.Columns partial = new TerrainTiles.Columns();
        partial.set(0, new TerrainSource.Column(64, MapColor.GRASS, 0));
        long never = Long.MAX_VALUE;
        assertEquals(-1, TerrainTiles.urgency(true, 0, true, null, never, now), "under construction");
        assertEquals(0, TerrainTiles.urgency(false, 0, false, null, never, now), "not yet built");
        assertEquals(0, TerrainTiles.urgency(false, now - s, true, full, never, now), "the relief has changed");
        assertEquals(1, TerrainTiles.urgency(false, now - 6 * s, false, new TerrainTiles.Columns(), never, now), "empty via 5 s");
        assertEquals(-1, TerrainTiles.urgency(false, now - 6 * s, false, partial, never, now), "incomplete - not through 5 s");
        assertEquals(1, TerrainTiles.urgency(false, now - 31 * s, false, partial, never, now), "incomplete via 30 s");
        assertEquals(-1, TerrainTiles.urgency(false, now - 999 * s, false, full, never, now), "full - only by event");
        assertEquals(1, TerrainTiles.urgency(false, now - 31 * s, false, full, 30 * s, now), "complete - according to source clock");
    }

    /**
     * The rebuilt small tile fits into its quarter of the large one, without touching the other three, and the count of columns with data
     *  is recalculated (the hole in the place of the data is a minus, the data in the place of the hole is a plus).
     */
    @Test
    void quarterReplacesOnlyItsPart() {
        int n = TerrainTiles.SIZE;
        TerrainTiles.Columns[] kids = {filled((x, z) -> 1), filled((x, z) -> 2), filled((x, z) -> 3), filled((x, z) -> 4)};
        TerrainTiles.Columns parent = TerrainTiles.compose(kids);
        assertTrue(parent.complete());
        // southeast child rebuilt: half without data, half above
        parent.putQuarter(3, filled((x, z) -> x < n / 2 ? Integer.MIN_VALUE : 40));
        assertEquals(n * n - n * n / 8, parent.filled, "a quarter has half holes");
        assertEquals(1, parent.height[0]);
        assertEquals(2, parent.height[n - 1]);
        assertEquals(3, parent.height[(n - 1) * n]);
        assertFalse(parent.has(n / 2 * n + n / 2), "southeast, western half - hole");
        assertEquals(40, parent.height[n * n - 1]);
        parent.putQuarter(3, kids[3]);
        assertTrue(parent.complete(), "the holes are closed again");
        assertEquals(4, parent.height[n * n - 1]);
    }

    /**
     * Large tiles are assembled from children only when all four are fresh; someone is under construction or outdated - waits for it
     *  (rather than reading the source a second time); someone is missing or its construction failed - reads the source.
     */
    @Test
    void composeOnlyFromFreshChildren() {
        long s = 1_000_000_000L, now = 1000 * s, never = Long.MAX_VALUE;
        assertEquals(TerrainTiles.Kids.ABSENT, TerrainTiles.kids(null, never, now), "detailed has no children");
        assertEquals(TerrainTiles.Kids.READY, TerrainTiles.kids(kids(now - s), never, now));

        TerrainTiles.Tile[] missing = kids(now - s);
        missing[2] = null;
        assertEquals(TerrainTiles.Kids.ABSENT, TerrainTiles.kids(missing, never, now), "no child");
        TerrainTiles.Tile[] failed = kids(now - s);
        failed[1].columns = null;
        assertEquals(TerrainTiles.Kids.ABSENT, TerrainTiles.kids(failed, never, now), "construction failed");

        TerrainTiles.Tile[] stale = kids(now - s);
        stale[3].stale = true;
        assertEquals(TerrainTiles.Kids.REFRESHING, TerrainTiles.kids(stale, never, now), "obsolete by event");
        TerrainTiles.Tile[] building = kids(now - s);
        building[0].buildingSince = now - s;
        assertEquals(TerrainTiles.Kids.REFRESHING, TerrainTiles.kids(building, never, now), "under construction");
        TerrainTiles.Tile[] firstBuild = kids(now - s);
        firstBuild[0].columns = null;
        firstBuild[0].buildingSince = now - s;
        assertEquals(TerrainTiles.Kids.REFRESHING, TerrainTiles.kids(firstBuild, never, now), "being built for the first time - wait, don’t read it a second time");

        // incomplete child: fresh - good (the source would give the same holes), it's time to ask again - first he
        TerrainTiles.Tile[] partial = kids(now - s);
        partial[2].columns = new TerrainTiles.Columns();
        partial[2].columns.set(0, new TerrainSource.Column(64, MapColor.GRASS, 0));
        assertEquals(TerrainTiles.Kids.READY, TerrainTiles.kids(partial, never, now));
        partial[2].builtAt = now - 31 * s;
        assertEquals(TerrainTiles.Kids.REFRESHING, TerrainTiles.kids(partial, never, now), "incomplete via 30 s");
        TerrainTiles.Tile[] empty = kids(now - 6 * s);
        empty[1].columns = new TerrainTiles.Columns();
        assertEquals(TerrainTiles.Kids.REFRESHING, TerrainTiles.kids(empty, never, now), "empty via 5 s");
        // complete - according to source hours
        assertEquals(TerrainTiles.Kids.REFRESHING, TerrainTiles.kids(kids(now - 31 * s), 30 * s, now));
    }

    /** In advance - detailed tiles in a square around the player, the closest ones first (his tile is the first). */
    @Test
    void prefetchAroundPlayerNearestFirst() {
        List<TerrainTiles.Key> keys = TerrainTiles.around(-100, 700);
        int r = TerrainTiles.PREFETCH_RADIUS;
        assertEquals((2 * r + 1) * (2 * r + 1), keys.size());
        assertEquals(new TerrainTiles.Key(0, -2, 10), keys.get(0));
        assertTrue(keys.stream().allMatch(k -> k.level() == 0 && Math.abs(k.tx() + 2) <= r && Math.abs(k.tz() - 10) <= r));
    }

    private interface HeightAt {
        int at(int x, int z);
    }

    /** Tiles with data in all cells, height in place; {@link Integer#MIN_VALUE} is a hole. */
    private static TerrainTiles.Columns filled(HeightAt h) {
        TerrainTiles.Columns c = new TerrainTiles.Columns();
        int n = TerrainTiles.SIZE;
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int y = h.at(x, z);
                if (y != Integer.MIN_VALUE) c.set(z * n + x, new TerrainSource.Column(y, MapColor.GRASS, 0));
            }
        }
        return c;
    }

    /** Four complete finished baby tiles (1, 0, 0) built in {@code builtAt}. */
    private static TerrainTiles.Tile[] kids(long builtAt) {
        TerrainTiles.Tile[] kids = new TerrainTiles.Tile[4];
        for (int q = 0; q < 4; q++) {
            kids[q] = new TerrainTiles.Tile(new TerrainTiles.Key(0, q & 1, q >> 1));
            kids[q].columns = filled((x, z) -> 64);
            kids[q].builtAt = builtAt;
        }
        return kids;
    }

    private static TerrainTiles.Layer layer() {
        return layer(true, Long.MAX_VALUE);
    }

    private static TerrainTiles.Layer layer(boolean offThread, long refreshNanos) {
        return new TerrainTiles.Layer("test", new TerrainSource() {
            @Override
            public boolean offThread() {
                return offThread;
            }

            @Override
            public long refreshNanos() {
                return refreshNanos;
            }

            @Nullable
            @Override
            public Reader open(ClientLevel level) {
                return null;
            }
        });
    }

    /**
     * The world has changed in the middle of the background building: it stops before the next column (the source is no longer asked
     * , the flow is not interrupted - DH writes an interruption of its reading to the log with an error), the tile returns to the layer
     *  without columns, the reader is closed.
     */
    @Test
    void cancelledBackgroundTileStopsBetweenColumns() {
        TerrainTiles.Layer layer = layer();
        int[] reads = {0};
        boolean[] closed = {false};
        TerrainSource.Reader reader = new TerrainSource.Reader() {
            @Nullable
            @Override
            public TerrainSource.Column column(int x, int z) {
                reads[0]++;
                return null;
            }

            @Override
            public void close() {
                closed[0] = true;
            }
        };
        TerrainTiles.Key key = new TerrainTiles.Key(0, 1, 1);
        TerrainTiles.buildOffThread(layer, key, 3, reader, () -> reads[0] >= 10);
        assertEquals(10, reads[0], "after cancellation the source is not asked");
        TerrainTiles.Built b = layer.done.poll();
        assertEquals(key, b.key());
        assertEquals(3, b.generation());
        assertNull(b.columns(), "canceled tile - no columns");
        assertTrue(closed[0], "reader is closed");
    }

    /** The background tile returns to the layer for any outcome, and for Error: otherwise the layer would forever wait for its task. */
    @Test
    void backgroundTileReturnsEvenOnError() {
        TerrainTiles.Layer layer = layer();
        boolean[] closed = {false};
        TerrainSource.Reader reader = new TerrainSource.Reader() {
            @Nullable
            @Override
            public TerrainSource.Column column(int x, int z) {
                throw new StackOverflowError();
            }

            @Override
            public void close() {
                closed[0] = true;
            }
        };
        TerrainTiles.Key key = new TerrainTiles.Key(2, 3, -1);
        assertThrows(StackOverflowError.class, () -> TerrainTiles.buildOffThread(layer, key, 7, reader, () -> false), "Error cannot be swallowed");
        TerrainTiles.Built b = layer.done.poll();
        assertEquals(new TerrainTiles.Key(2, 3, -1), b.key());
        assertEquals(7, b.generation());
        assertNull(b.columns(), "without speakers - they will ask again");
        assertTrue(closed[0], "reader is closed");
    }

    /** The layer limit accommodates the two widest views of the map at any scale (and smooth, like a camera map) and the margin around the player. */
    @Test
    void capacityHoldsTwoWidestViews() {
        int w = 853, h = 414;
        int widest = 0;
        for (double e = -5; e <= 3; e += 0.01) {
            double k = Math.pow(2, e);
            for (double c = 0; c < 4096; c += 97.3) {
                MapProjection map = new MapProjection(w / 2.0, h / 2.0, c, -0.7 * c, k);
                widest = Math.max(widest, TerrainTiles.visible(map, TerrainTiles.levelFor(k), 0, 0, w, h).size());
            }
        }
        int around = (2 * TerrainTiles.PREFETCH_RADIUS + 1) * (2 * TerrainTiles.PREFETCH_RADIUS + 1);
        assertTrue(TerrainTiles.capacity(w, h) >= 2 * widest + around, "limit " + TerrainTiles.capacity(w, h) + ", the widest view " + widest);
        assertEquals(768, TerrainTiles.capacity(200, 120), "small card - no less than the previous limit");
    }

    /** Readings immediately according to the number of source streams - only while the map is on the screen; in advance - two, no matter how many threads there are. */
    @Test
    void readsInParallelOnlyWhileMapShown() {
        assertEquals(8, TerrainTiles.jobLimit(false, 8));
        assertEquals(TerrainTiles.PREFETCH_JOBS, TerrainTiles.jobLimit(true, 8));
        assertEquals(TerrainTiles.PREFETCH_JOBS, TerrainTiles.jobLimit(false, 1), "the source has one stream - no less than in advance");
    }

    /**
     * Relief in advance - only for those who need the map: disabled in the settings - no one; the map in this world was opened -
     *  without a remote control; otherwise - with the remote control in the inventory. The inventory is viewed only if the first two conditions are not resolved.
     */
    @Test
    void prefetchOnlyForMapUsers() {
        int[] asked = {0};
        java.util.function.BooleanSupplier has = () -> ++asked[0] > 0;
        java.util.function.BooleanSupplier hasNot = () -> ++asked[0] < 0;
        assertFalse(TerrainTiles.prefetchWanted(false, true, has), "disabled in settings");
        assertEquals(0, asked[0], "disabled - inventory is not displayed");
        assertTrue(TerrainTiles.prefetchWanted(true, true, hasNot), "the card was opened");
        assertEquals(0, asked[0], "opened - the inventory does not look");
        assertTrue(TerrainTiles.prefetchWanted(true, false, has), "remote control in inventory");
        assertFalse(TerrainTiles.prefetchWanted(true, false, hasNot), "without remote control and card");
        assertEquals(2, asked[0]);
    }

    /** Without a remote control and an open map, a layer of chunks in advance costs nothing: no source, no hours; the started tile is thrown. */
    @Test
    void gameThreadPrefetchCostsNothingWhenNotWanted() {
        TerrainTiles.Layer layer = layer(false, 30_000_000_000L);
        TerrainTiles.Key key = new TerrainTiles.Key(0, 0, 0);
        layer.tiles.put(key, new TerrainTiles.Tile(key));
        layer.prefetchOrder = List.of(key);
        layer.prefetch = Set.of(key);
        layer.partial = new TerrainTiles.Partial(key, 5);
        layer.tiles.get(key).buildingSince = 5;
        TerrainTiles.prefetchInGameThread(layer, false, () -> {
            throw new AssertionError("source open");
        }, () -> {
            throw new AssertionError("hours asked");
        }, 0, b -> {
            throw new AssertionError("tile built");
        });
        assertNull(layer.partial, "started tile is thrown");
        assertEquals(0, layer.tiles.get(key).buildingSince, "and is not listed as under construction");
    }

    /**
     * A layer of chunks in advance - under the deadline by hours, and not by the number of tiles: the columns are read until the clock reaches the deadline
     *  (here each column is a unit of hours), the started tile is finished reading in the next tick from the same place (while it is
     *  started, it is marked “under construction” - the card in the frame does not build it a second time), the finished one goes into the layer, and in the same
     *  tic the next of the neighbors begins. The one started, which is no longer needed in advance (the player has left), is thrown.
     */
    @Test
    void gameThreadPrefetchStopsAtDeadlineAndResumes() {
        int n = TerrainTiles.SIZE * TerrainTiles.SIZE;
        TerrainTiles.Layer layer = layer(false, 30_000_000_000L);
        TerrainTiles.Key first = new TerrainTiles.Key(0, -1, 2), second = new TerrainTiles.Key(0, 0, 2), done = new TerrainTiles.Key(0, 1, 2);
        for (TerrainTiles.Key k : List.of(first, second, done)) layer.tiles.put(k, new TerrainTiles.Tile(k));
        // the finished full tile is not re-read in advance
        layer.tiles.get(done).columns = filled((x, z) -> 64);
        layer.tiles.get(done).builtAt = 1;
        layer.prefetchOrder = List.of(done, first, second);
        layer.prefetch = Set.copyOf(layer.prefetchOrder);
        long[] clock = {100};
        List<int[]> reads = new ArrayList<>();
        TerrainSource.Reader reader = new TerrainSource.Reader() {
            @Override
            public TerrainSource.Column column(int x, int z) {
                clock[0]++;
                reads.add(new int[] {x, z});
                return new TerrainSource.Column(70, MapColor.STONE, 0);
            }

            @Override
            public void close() {}
        };
        List<TerrainTiles.Built> built = new ArrayList<>();
        java.util.function.Consumer<TerrainTiles.Built> sink = b -> {
            built.add(b);
            TerrainTiles.Tile t = layer.tiles.get(b.key());
            t.columns = b.columns();
            t.builtAt = clock[0];
        };

        TerrainTiles.prefetchInGameThread(layer, true, () -> reader, () -> clock[0], clock[0] + 1000, sink);
        assertEquals(1000, reads.size(), "exactly before the deadline");
        assertEquals(first, layer.partial.key, "the closest missing one - first");
        assertEquals(1000, layer.partial.next);
        assertTrue(layer.tiles.get(first).buildingSince != 0, "started - “under construction”");
        assertTrue(built.isEmpty());
        assertEquals(-64, reads.get(0)[0], "column (−64, 128) - tile corner (−1, 2)");
        assertEquals(128, reads.get(0)[1]);

        // next tick: the tile has been read from the same place and moved to the layer, the next one has been started
        TerrainTiles.prefetchInGameThread(layer, true, () -> reader, () -> clock[0], clock[0] + n, sink);
        assertEquals(1000 + n, reads.size());
        assertEquals(1, built.size());
        assertEquals(first, built.get(0).key());
        assertTrue(built.get(0).columns().complete());
        assertEquals(1000, reads.get(1000)[0] + 64 + (reads.get(1000)[1] - 128) * TerrainTiles.SIZE, "continued from column 1000");
        assertEquals(second, layer.partial.key, "in the same tick - next");
        assertEquals(1000, layer.partial.next);
        assertEquals(0, layer.tiles.get(first).buildingSince, "the finished one is no longer “under construction”");

        // the player has left: the second one is no longer needed in advance - it is abandoned unfinished, and the finished ones are no longer readable
        layer.prefetchOrder = List.of(done, first);
        layer.prefetch = Set.copyOf(layer.prefetchOrder);
        int total = reads.size();
        TerrainTiles.prefetchInGameThread(layer, true, () -> reader, () -> clock[0], clock[0] + n, sink);
        assertEquals(total, reads.size(), "doesn’t finish reading, and there’s nothing more to read");
        assertNull(layer.partial);
        assertEquals(0, layer.tiles.get(second).buildingSince, "abandoned is not listed as under construction");
        assertEquals(1, built.size());
    }

    /**
     * The chunk arrived to the client in the middle of building the tile in advance: the columns before it were already read empty, so the completed
     *  tile goes into the layer marked “outdated” and is reread in its entirety in the next tick.
     */
    @Test
    void chunkArrivingMidBuildRebuildsTile() {
        int n = TerrainTiles.SIZE * TerrainTiles.SIZE;
        TerrainTiles.Layer layer = layer(false, 30_000_000_000L);
        TerrainTiles.Key key = new TerrainTiles.Key(0, 0, 0);
        layer.tiles.put(key, new TerrainTiles.Tile(key));
        layer.prefetchOrder = List.of(key);
        layer.prefetch = Set.of(key);
        long[] clock = {100};
        boolean[] chunkHere = {false};
        int[] reads = {0};
        TerrainSource.Reader reader = new TerrainSource.Reader() {
            @Nullable
            @Override
            public TerrainSource.Column column(int x, int z) {
                clock[0]++;
                reads[0]++;
                // chunk (0, 0) - blocks 0...15: the client does not have it before arrival
                return x < 16 && z < 16 && !chunkHere[0] ? null : new TerrainSource.Column(64, MapColor.GRASS, 0);
            }

            @Override
            public void close() {}
        };
        java.util.function.Consumer<TerrainTiles.Built> sink = b -> {
            TerrainTiles.Tile t = layer.tiles.get(b.key());
            t.columns = b.columns();
            t.builtAt = clock[0];
        };
        TerrainTiles.prefetchInGameThread(layer, true, () -> reader, () -> clock[0], clock[0] + 10, sink);
        assertTrue(layer.tiles.get(key).buildingSince != 0, "started");
        chunkHere[0] = true;
        TerrainTiles.arrived(layer, 0, 0);
        // exactly to the end of the tile: this tick does not start the next construction
        TerrainTiles.prefetchInGameThread(layer, true, () -> reader, () -> clock[0], clock[0] + n - 10, sink);
        assertFalse(layer.tiles.get(key).columns.complete(), "finished reading with a hole, read before the chunk arrived");
        assertTrue(layer.tiles.get(key).stale, "and marked obsolete");
        int before = reads[0];
        TerrainTiles.prefetchInGameThread(layer, true, () -> reader, () -> clock[0], clock[0] + 2L * n, sink);
        assertEquals(before + n, reads[0], "re-read in its entirety");
        assertTrue(layer.tiles.get(key).columns.complete(), "no hole");
        assertFalse(layer.tiles.get(key).stale);
    }

    /**
     * The chunk came to the client: only tiles above it with holes (at all levels) and those under construction are re-read; complete, still
     *  not built and the neighboring one are not touched.
     */
    @Test
    void arrivedChunkRefreshesOnlyTilesWithHoles() {
        TerrainTiles.Layer layer = layer(false, 30_000_000_000L);
        TerrainTiles.Key holed = new TerrainTiles.Key(0, -1, -2), full = new TerrainTiles.Key(1, -1, -1), fresh = new TerrainTiles.Key(4, -1, -1),
                beside = new TerrainTiles.Key(0, 0, -2);
        for (TerrainTiles.Key k : List.of(holed, full, fresh, beside)) layer.tiles.put(k, new TerrainTiles.Tile(k));
        layer.tiles.get(holed).columns = filled((x, z) -> x < 8 ? 64 : Integer.MIN_VALUE);
        layer.tiles.get(holed).builtAt = 1;
        layer.tiles.get(full).columns = filled((x, z) -> 64);
        layer.tiles.get(full).builtAt = 1;
        layer.tiles.get(beside).columns = new TerrainTiles.Columns();
        layer.tiles.get(beside).builtAt = 1;
        // chunk (−1, −5): tile (−1, −2) of level 0
        TerrainTiles.arrived(layer, -1, -5);
        assertTrue(layer.tiles.get(holed).stale, "with holes - reread");
        assertFalse(layer.tiles.get(full).stale, "complete - she already had this chunk");
        assertFalse(layer.tiles.get(fresh).stale, "not yet built - and so in the queue");
        assertFalse(layer.tiles.get(beside).stale, "neighboring");
        // is being built for the first time (it’s not ready yet): the columns are already read empty before the chunk arrives
        layer.tiles.get(fresh).buildingSince = 5;
        TerrainTiles.arrived(layer, -1, -5);
        assertTrue(layer.tiles.get(fresh).stale, "under construction - re-read");
    }

    /**
     * Far relief is the first one that works by preference: LSS (higher in the list) before DH, but only while the server distributes
     *  relief; otherwise DH; there are no distant ones - only chunks.
     */
    @Test
    void farLayerPrefersWorkingLss() {
        boolean[] lssOn = {false};
        TerrainTiles.Layer dh = farLayer("dh", () -> true), lss = farLayer("lss", () -> lssOn[0]), chunks = layer();
        assertEquals(dh, TerrainTiles.pickFar(List.of(dh, lss, chunks)), "server without LSS - DH");
        lssOn[0] = true;
        assertEquals(lss, TerrainTiles.pickFar(List.of(dh, lss, chunks)), "the server distributes LOD - LSS");
        assertEquals(lss, TerrainTiles.pickFar(List.of(lss, chunks)));
        lssOn[0] = false;
        assertNull(TerrainTiles.pickFar(List.of(lss, chunks)), "LSS without server and without DH - only chunks");
        assertNull(TerrainTiles.pickFar(List.of(chunks)));
    }

    /**
     * The order of distant sources is LSS, when the server distributes the relief, then the base Voxy, then DH, then only chunks - in
     *  three build cases (Voxy only, DH only, neither), each with LSS and without; LSS is worth it, but the server does not distribute it
     *  (single player) - as without it.
     */
    @Test
    void farSourceOrderForEachPack() {
        boolean[] serving = {false};
        TerrainSource chunks = source(() -> true), lss = source(() -> serving[0]);
        TerrainSource voxy = source(() -> true), dh = source(() -> true);
        // only Voxy (Open Skies)
        assertEquals("voxy", far(TerrainTiles.stack(null, voxy, null, chunks)));
        assertEquals("voxy", far(TerrainTiles.stack(null, voxy, lss, chunks)), "single player: LSS does not distribute - base Voxy");
        serving[0] = true;
        assertEquals("lss", far(TerrainTiles.stack(null, voxy, lss, chunks)), "the server distributes LOD - LSS");
        serving[0] = false;
        // only DH
        assertEquals("dh", far(TerrainTiles.stack(dh, null, null, chunks)));
        assertEquals("dh", far(TerrainTiles.stack(dh, null, lss, chunks)));
        serving[0] = true;
        assertEquals("lss", far(TerrainTiles.stack(dh, null, lss, chunks)));
        serving[0] = false;
        // neither one nor the other
        assertNull(far(TerrainTiles.stack(null, null, null, chunks)), "only chunks");
        assertNull(far(TerrainTiles.stack(null, null, lss, chunks)));
        serving[0] = true;
        assertEquals("lss", far(TerrainTiles.stack(null, null, lss, chunks)));
        serving[0] = false;
        // both: base Voxy before DH, but only while Voxy holds the world
        boolean[] voxyWorld = {true};
        TerrainSource voxyMaybe = source(() -> voxyWorld[0]);
        assertEquals("voxy", far(TerrainTiles.stack(dh, voxyMaybe, lss, chunks)));
        voxyWorld[0] = false;
        assertEquals("dh", far(TerrainTiles.stack(dh, voxyMaybe, lss, chunks)), "voxy has no world - DH");
        List<TerrainTiles.Layer> all = TerrainTiles.stack(dh, voxy, lss, chunks);
        assertEquals("chunks", all.get(all.size() - 1).name, "client chunks - on top, always");
        assertFalse(all.get(all.size() - 1).far);
    }

    @Nullable
    private static String far(List<TerrainTiles.Layer> layers) {
        TerrainTiles.Layer l = TerrainTiles.pickFar(layers);
        return l == null ? null : l.name;
    }

    private static TerrainSource source(java.util.function.BooleanSupplier active) {
        return farLayer("", active).source;
    }

    private static TerrainTiles.Layer farLayer(String name, java.util.function.BooleanSupplier active) {
        return new TerrainTiles.Layer(name, new TerrainSource() {
            @Override
            public boolean offThread() {
                return true;
            }

            @Override
            public long refreshNanos() {
                return Long.MAX_VALUE;
            }

            @Override
            public boolean active() {
                return active.getAsBoolean();
            }

            @Nullable
            @Override
            public Reader open(ClientLevel level) {
                return null;
            }
        }, true);
    }
}
