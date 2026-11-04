package ua.zentix.incoming.warhead;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.registry.ModTags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FragmentTraceTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    private record Encounter(String kind, BlockPos pos, BlockState state, double distance, Vec3 point, Direction entry) {}
    private record Result(double distance, List<Encounter> encounters) {}

    private static final class Fixture {
        final ServerLevel level = mock(ServerLevel.class);
        final ServerChunkCache source = mock(ServerChunkCache.class);
        final Map<BlockPos, BlockState> states = new HashMap<>();
        final Map<BlockPos, BlockState> virtual = new HashMap<>();
        final Map<Long, LevelChunk> chunks = new HashMap<>();
        final Set<Long> absent = new HashSet<>();
        final List<Long> requested = new ArrayList<>();
        final AtomicInteger blockReads = new AtomicInteger();
        boolean debug;
        Fixture() {
            when(level.getMinBuildHeight()).thenReturn(-32);
            when(level.getMaxBuildHeight()).thenReturn(96);
            when(level.isDebug()).thenAnswer(a -> debug);
            when(level.getChunkSource()).thenReturn(source);
            when(source.getChunkNow(anyInt(), anyInt())).thenAnswer(a -> {
                int x = a.getArgument(0), z = a.getArgument(1);
                long key = net.minecraft.world.level.ChunkPos.asLong(x, z);
                requested.add(key);
                return absent.contains(key) ? null : chunks.computeIfAbsent(key, unused -> chunk(x, z));
            });
        }
        private LevelChunk chunk(int x, int z) {
            LevelChunk chunk = mock(LevelChunk.class);
            when(chunk.getBlockState(any(BlockPos.class))).thenAnswer(a -> {
                blockReads.incrementAndGet();
                return (debug ? virtual : states).getOrDefault(a.getArgument(0), Blocks.AIR.defaultBlockState());
            });
            var sections = new LevelChunkSection[8];
            for (int i = 0; i < sections.length; i++) {
                int y = i - 2;
                sections[i] = mock(LevelChunkSection.class);
                when(sections[i].hasOnlyAir()).thenAnswer(a -> states.entrySet().stream().noneMatch(e ->
                        e.getKey().getX() >> 4 == x && e.getKey().getZ() >> 4 == z && e.getKey().getY() >> 4 == y && !e.getValue().isAir()));
            }
            when(chunk.getSectionIndex(anyInt())).thenAnswer(a -> ((int) a.getArgument(0) + 32) >> 4);
            when(chunk.getSection(anyInt())).thenAnswer(a -> sections[(int) a.getArgument(0)]);
            return chunk;
        }
        Result run(Vec3 from, Vec3 dir, double range, boolean optimized) {
            List<Encounter> events = new ArrayList<>();
            FragmentTrace.Visitor visitor = new FragmentTrace.Visitor() {
                @Override public void glass(BlockPos pos, BlockState state, double s) {
                    events.add(new Encounter("glass", pos, state, s, null, null));
                }
                @Override public void stop(BlockPos pos, BlockState state, double s, Vec3 point, Direction entry) {
                    events.add(new Encounter("stop", pos, state, s, point, entry));
                }
            };
            double stopped = optimized ? FragmentTrace.trace(level, from, dir, range, visitor) : reference(level, from, dir, range, visitor);
            return new Result(stopped, events);
        }
        void same(Vec3 from, Vec3 dir, double range) {
            requested.clear();
            Result expected = run(from, dir, range, false);
            List<Long> reads = List.copyOf(requested);
            requested.clear();
            assertEquals(expected, run(from, dir, range, true), "callback order, states, distance, face and point");
            assertEquals(reads, requested, "same ready-chunk boundaries, without loading or skipping unknown terrain");
        }
    }

    @Test void raysKeepTheirExactDdaTieOrderAcrossEmptySectionsAndBoundaries() {
        Fixture f = new Fixture();
        for (int x : new int[]{-17, -16, -1, 0, 15, 16, 31}) {
            f.states.put(new BlockPos(x, 16, 0), Blocks.STONE.defaultBlockState());
            f.states.put(new BlockPos(x, -16, -16), Blocks.WATER.defaultBlockState());
        }
        List<Vec3> starts = List.of(new Vec3(.5, .5, .5), new Vec3(-16, 16, -16), new Vec3(15.5, -15.5, .5), new Vec3(.5, 95.5, .5));
        for (Vec3 start : starts) for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
            if (x == 0 && y == 0 && z == 0) continue;
            f.same(start, new Vec3(x, y, z).normalize(), 48);
        }
    }

    @Test void glassShapelessFluidAndChangedSectionsKeepTheirEncounters() {
        Fixture f = new Fixture();
        BlockState glass = mock(BlockState.class);
        when(glass.is(ModTags.SHATTERS)).thenReturn(true);
        f.states.put(new BlockPos(4, 0, 0), glass);
        f.states.put(new BlockPos(5, 0, 0), Blocks.SHORT_GRASS.defaultBlockState());
        f.states.put(new BlockPos(16, 0, 0), Blocks.WATER.defaultBlockState());
        Vec3 from = new Vec3(.5, .5, .5), dir = new Vec3(1, 0, 0);
        f.same(from, dir, 48);
        var hit = f.run(from, dir, 48, true);
        assertEquals(List.of("glass", "stop"), hit.encounters().stream().map(Encounter::kind).toList());
        assertEquals(new BlockPos(16, 0, 0), hit.encounters().getLast().pos());
        f.states.clear();
        f.chunks.clear(); // a later ray must resolve newly loaded section objects, not a retained terrain cache
        f.same(from, dir, 48);
        assertTrue(f.run(from, dir, 48, true).encounters().isEmpty());
        f.states.put(new BlockPos(31, 0, 0), Blocks.STONE.defaultBlockState());
        f.same(from, dir, 48);
        assertEquals(new BlockPos(31, 0, 0), f.run(from, dir, 48, true).encounters().getFirst().pos());
    }

    @Test void emptyAirSkipsBlockReadsButNeverSkipsAnUnreadyChunk() {
        Fixture f = new Fixture();
        Vec3 from = new Vec3(.5, .5, .5), dir = new Vec3(1, 0, 0);
        f.same(from, dir, 48);
        f.blockReads.set(0);
        assertEquals(48, f.run(from, dir, 48, true).distance());
        assertEquals(0, f.blockReads.get(), "empty section must not call the hot block getter");
        f.absent.add(net.minecraft.world.level.ChunkPos.asLong(1, 0));
        f.same(from, dir, 48);
        assertEquals(-1, f.run(from, dir, 48, true).distance());
        assertEquals(net.minecraft.world.level.ChunkPos.asLong(1, 0), f.requested.getLast());
    }

    @Test void aDebugWorldStillUsesItsVirtualBlocksDespiteEmptyStoredSections() {
        Fixture f = new Fixture();
        f.debug = true;
        f.virtual.put(new BlockPos(4, 70, 0), Blocks.STONE.defaultBlockState());
        Vec3 from = new Vec3(.5, 70.5, .5), dir = new Vec3(1, 0, 0);
        f.same(from, dir, 48);
        assertEquals(new BlockPos(4, 70, 0), f.run(from, dir, 48, true).encounters().getFirst().pos());
    }

    private static int floor(double value) { return (int) Math.floor(value); }

    private static double reference(Level level, Vec3 from, Vec3 dir, double maxS, FragmentTrace.Visitor visitor) {
        int x = floor(from.x), y = floor(from.y), z = floor(from.z);
        int sx = (int) Math.signum(dir.x), sy = (int) Math.signum(dir.y), sz = (int) Math.signum(dir.z);
        double tdx = sx == 0 ? Double.MAX_VALUE : Math.abs(1 / dir.x);
        double tdy = sy == 0 ? Double.MAX_VALUE : Math.abs(1 / dir.y);
        double tdz = sz == 0 ? Double.MAX_VALUE : Math.abs(1 / dir.z);
        double tx = sx == 0 ? Double.MAX_VALUE : ((sx > 0 ? x + 1 - from.x : from.x - x) * tdx);
        double ty = sy == 0 ? Double.MAX_VALUE : ((sy > 0 ? y + 1 - from.y : from.y - y) * tdy);
        double tz = sz == 0 ? Double.MAX_VALUE : ((sz > 0 ? z + 1 - from.z : from.z - z) * tdz);
        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
        int cx = Integer.MIN_VALUE, cz = Integer.MIN_VALUE;
        LevelChunk chunk = null;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        net.minecraft.core.Direction entry = net.minecraft.core.Direction.UP;
        double s = 0;
        while (true) {
            // Step into next cell.
            if (tx <= ty && tx <= tz) {
                s = tx;
                x += sx;
                tx += tdx;
                entry = sx > 0 ? net.minecraft.core.Direction.WEST : net.minecraft.core.Direction.EAST;
            } else if (ty <= tz) {
                s = ty;
                y += sy;
                ty += tdy;
                entry = sy > 0 ? net.minecraft.core.Direction.DOWN : net.minecraft.core.Direction.UP;
            } else {
                s = tz;
                z += sz;
                tz += tdz;
                entry = sz > 0 ? net.minecraft.core.Direction.NORTH : net.minecraft.core.Direction.SOUTH;
            }
            if (s >= maxS || y < minY || y >= maxY) return maxS;
            if (x >> 4 != cx || z >> 4 != cz) {
                cx = x >> 4;
                cz = z >> 4;
                chunk = FragmentTrace.chunkNow(level, cx, cz);
            }
            if (chunk == null) return -1;
            m.set(x, y, z);
            BlockState st = chunk.getBlockState(m);
            if (st.isAir()) continue;
            if (st.is(ModTags.SHATTERS)) {
                visitor.glass(m.immutable(), st, s);
                continue;
            }
            if (st.getFluidState().isEmpty() && st.getCollisionShape(chunk, m).isEmpty()) continue;
            visitor.stop(m.immutable(), st, s, from.add(dir.scale(s)), entry);
            return s;
        }
    }

}
