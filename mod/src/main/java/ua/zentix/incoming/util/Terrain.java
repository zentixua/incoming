package ua.zentix.incoming.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntBinaryOperator;

/**
 * Terrain height without waiting: server {@code Level.getHeight} on a loaded chunk not yet ready
 * for delivery waits in {@code managedBlock}, executing unrelated loading tasks meanwhile (up to hundreds of ms per call).
 * Only ready chunks here ({@code getChunkNow}); absent chunk returns world bottom, like vanilla.
 */
public final class Terrain {
    private Terrain() {}

    /** Vanilla Overworld sea level (water through 62, first air above 63): level-free laws and a client before the server's word. */
    public static final int VANILLA_SEA = 63;
    /** Client level the server's sea level belongs to ({@link #seaFromServer}); weak, so a closed world is not retained. */
    private static java.lang.ref.WeakReference<Level> seaOf = new java.lang.ref.WeakReference<>(null);
    private static volatile int seaFromServer = VANILLA_SEA;

    /**
     * The world's sea level, first air above still sea water: the server reads its generator (flat worlds −63, a
     * pack's noise settings their {@code sea_level}), a client what the server sent for its current level
     * ({@code S2C.SeaLevel}, {@link #VANILLA_SEA} until it arrives). Not {@code Level.getSeaLevel}: always 63 in 1.21.1.
     * Every height the mod measures above sea level in a world starts here ({@code SeaLevelTest}).
     */
    public static int sea(Level level) {
        if (level instanceof ServerLevel server) return server.getChunkSource().getGenerator().getSeaLevel();
        return seaOf.get() == level ? seaFromServer : VANILLA_SEA;
    }

    /** Client: the server's sea level for {@code level} (packet handler, client thread). */
    public static void seaFromServer(Level level, int y) {
        seaFromServer = y;
        seaOf = new java.lang.ref.WeakReference<>(level);
    }

    /**
     * Ticks between repeats of {@code S2C.SeaLevel} to every player: a Flashback recording started mid-session holds the
     * value within this time, whatever its snapshot keeps (5 bytes per player each 30 s).
     */
    public static final int SEA_RESEND = 600;

    /**
     * Tells a player the sea level of the level they are in: login, dimension change, respawn and every
     * {@link #SEA_RESEND} ticks. Not from a Flashback replay server: its viewers get the recorded value, and the replay
     * server's own level is not the recorded world.
     */
    public static void sendSea(net.minecraft.server.level.ServerPlayer player) {
        if (ua.zentix.incoming.compat.FlashbackReplay.isReplay(player.server)) return;
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, new ua.zentix.incoming.net.S2C.SeaLevel(sea(player.serverLevel())));
    }

    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % SEA_RESEND != 0) return;
        for (net.minecraft.server.level.ServerPlayer p : e.getServer().getPlayerList().getPlayers()) sendSea(p);
    }

    public static void onLogin(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p) sendSea(p);
    }

    public static void onChangeDimension(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p) sendSea(p);
    }

    public static void onRespawn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p) sendSea(p);
    }

    /**
     * Chunk loaded and ready: server uses {@code getChunkNow}, not {@code hasChunk}/{@code isLoaded}
     * (also true for still-loading chunks, whose reads wait for loading).
     */
    public static boolean ready(Level level, int chunkX, int chunkZ) {
        if (level instanceof ServerLevel server) return server.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
        return level.hasChunk(chunkX, chunkZ);
    }

    public static boolean ready(Level level, BlockPos pos) {
        return ready(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * Chunk in memory: fully loaded, downgraded (visibility edge) or awaiting unload, until its
     * {@code ChunkEvent.Unload}; null if absent from memory or never fully loaded ({@code ChunkEvent.Load}
     * will supply it). Vanilla immediately removes ticketless holders from the visible chunk map, but unloads the
     * chunk later when savable ({@code ChunkMap.processUnloads} → {@code pendingUnloads} →
     * {@code scheduleUnload}); a ticket returning meanwhile restores the holder from {@code pendingUnloads} with the same
     * chunk and no new {@code ChunkEvent.Load}. Thus “absent from visible map” does not mean “unloaded”.
     * Reading such a chunk's own data (heightmap) is safe — causes no loading. AT: {@code ChunkMap.pendingUnloads},
     * read-only.
     */
    @Nullable
    public static LevelChunk inMemory(ServerLevel level, long pos) {
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        ChunkHolder holder = chunkMap.getVisibleChunkIfPresent(pos);
        if (holder == null) holder = chunkMap.pendingUnloads.get(pos);
        return holder != null && holder.getChunkIfPresentUnchecked(ChunkStatus.FULL) instanceof LevelChunk chunk ? chunk : null;
    }

    /** Chunk and all eight neighbors ready: block-update chain (up to 16 blocks) stays within ready chunks. */
    public static boolean neighbourhoodReady(Level level, int chunkX, int chunkZ) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) if (!ready(level, chunkX + dx, chunkZ + dz)) return false;
        }
        return true;
    }

    /** All chunks touched by a square of side {@code 2r} around the point are ready. */
    public static boolean readyAround(Level level, Vec3 centre, double r) {
        for (int cx = Mth.floor(centre.x - r) >> 4; cx <= Mth.floor(centre.x + r) >> 4; cx++) {
            for (int cz = Mth.floor(centre.z - r) >> 4; cz <= Mth.floor(centre.z + r) >> 4; cz++) {
                if (!ready(level, cx, cz)) return false;
            }
        }
        return true;
    }

    /**
     * Ready portion of segment {@code from → to}: point just before first unready chunk (or
     * {@code to} if entire path ready). {@code Level.clip} reads every block along its ray; server reads
     * of unloaded chunks load and generate synchronously in the tick — long rays (aim up to 1024 blocks, projectile nose)
     * would stall for seconds. Client unloaded chunks are empty; no clipping needed.
     */
    public static Vec3 readyUntil(Level level, Vec3 from, Vec3 to) {
        if (!(level instanceof ServerLevel)) return to;
        double t = readyFraction(from.x, from.z, to.x, to.z, (x, z) -> ready(level, x, z));
        return t >= 1 ? to : from.lerp(to, t);
    }

    /** Entire segment crosses ready chunks (see {@link #readyUntil}). */
    public static boolean readyAlong(Level level, Vec3 from, Vec3 to) {
        return !(level instanceof ServerLevel) || readyFraction(from.x, from.z, to.x, to.z, (x, z) -> ready(level, x, z)) >= 1;
    }

    /** Chunk (x, z) is ready. */
    @FunctionalInterface
    public interface ChunkReady {
        boolean test(int chunkX, int chunkZ);
    }

    /**
     * Segment fraction (0..1) before its first unready chunk column. Grid traversal
     * ({@link GridWalk}) misses no intersected column, even when cutting a corner.
     * End is {@link #EDGE} blocks before boundary, keeping last checked block inside a ready chunk.
     */
    public static double readyFraction(double fromX, double fromZ, double toX, double toZ, ChunkReady ready) {
        double t = GridWalk.walk(fromX, fromZ, toX, toZ, 16, ready::test);
        return t >= 1 ? 1 : Math.max(0, t - EDGE / Math.max(1.0e-9, Math.hypot(toX - fromX, toZ - fromZ)));
    }

    private static final double EDGE = 0.01;

    /**
     * Fraction of segment {@code from → to} (0..1) where it first goes below surface from
     * {@code MOTION_BLOCKING} heightmaps in {@code allowed}; 1 — entirely above. A block column intersected by segment
     * ({@link GridWalk}) is blocked if its surface is above the segment's lowest point in that column. This checks
     * terrain top, not blocks: cannot see overhangs or caves. Unknown terrain (world bottom) is open.
     */
    public static double underRelief(Level level, Vec3 from, Vec3 to, Allowed allowed) {
        double dy = to.y - from.y;
        double[] hit = {1};
        GridWalk.walk(from.x, from.z, to.x, to.z, 1, (GridWalk.SpanVisitor) (x, z, t0, t1) -> {
            double low = from.y + dy * (dy < 0 ? t1 : t0);
            if (estimate(level, Heightmap.Types.MOTION_BLOCKING, x, z, allowed).y() > low) {
                hit[0] = t0;
                return false;
            }
            return true;
        });
        return hit[0];
    }

    /**
     * First air above heightmap (like {@link Level#getHeight}) — {@link Source#CHUNK} without source selection: for code
     * that knows chunk readiness (explosion area, nuclear queue), or accepts world bottom as correct. Use {@link #estimate}
     * for “where is the ground” when the chunk may be unready.
     */
    public static int height(Level level, Heightmap.Types type, int x, int z) {
        if (level instanceof ServerLevel server) {
            LevelChunk chunk = server.getChunkSource().getChunkNow(x >> 4, z >> 4);
            return chunk == null ? level.getMinBuildHeight() : chunk.getHeight(type, x & 15, z & 15) + 1;
        }
        return level.getHeight(type, x, z);
    }

    /**
     * Top block of a column that dust lies on and blasts stand over, in a chunk already in hand: the
     * {@code MOTION_BLOCKING} top, but under a canopy the first solid below the leaves (permeable foliage does not
     * stand in for the forest floor). The client's blast clouds and the server's record of them read the same column.
     */
    public static int dustTop(LevelChunk chunk, int x, int z) {
        int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, top, z);
        return belowCanopy(top, chunk.getMinBuildHeight(), canopy(chunk.getBlockState(p)), y -> {
            var s = chunk.getBlockState(p.setY(y));
            return !Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(s);
        });
    }

    /**
     * A canopy block over the ground where dust lies: leaves ({@code minecraft:leaves}, or a leaves block a mod left
     * untagged). One rule for the client chunks and every LOD source's columns.
     */
    public static boolean canopy(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.LEAVES) || state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock;
    }

    /** Pure column operation: a full solid is a surface regardless of what it was built for. */
    public static int belowCanopy(int top, int bottom, boolean leaves, java.util.function.IntPredicate permeable) {
        if (!leaves) return top;
        int y = top;
        while (y > bottom && permeable.test(y)) y--;
        return y;
    }

    /** Surface-height source, precise to coarse; {@link #estimate} takes the first allowed one. */
    public enum Source {
        /** Ready-chunk heightmap. */
        CHUNK,
        /**
         * Ready-chunk heightmap in a ceiling dimension (Nether): top of bedrock ceiling, not ground. In-world
         * obstacle avoidance can use it; outside-world flight and ground points cannot.
         */
        CEILING,
        /**
         * Saved heightmap of an unready chunk: in-memory copy (awaiting unload or downgraded at visibility
         * edge), or chunk file read on chunk I/O thread without chunk loading
         * ({@link TerrainSurvey}). As accurate as chunk heightmap when recorded; answer arrives only after survey
         * reads it (tick does not wait). Only in dimensions without a ceiling.
         */
        DISK,
        /** Top from client map where player picked a location (Distant Horizons or client chunks): order hint. */
        CLIENT_MAP,
        /**
         * World-generator terrain ({@code ChunkGenerator.getBaseHeight}: noise without chunk loading, trees or structures;
         * ≈3 ms per point — orders only, not ticks), at least generator sea level (water itself is surface over water).
         * For worlds built by another generator, far from surface: downloaded city map (63 beneath roof at 107),
         * 1.17 world upgraded to 1.21 (generator gives world bottom);
         * sea level keeps estimate at least near land. Terrain survey ({@link TerrainSurvey}, source set with background
         * sources) uses it for chunks absent or partially generated on disk: computed in background on nodes every
         * {@link TerrainSurvey#GENERATOR_STEP} blocks.
         */
        GENERATOR,
        /**
         * Generator sea level: cheap, tick-safe; land is rarely below it. Not {@code Level.getSeaLevel}:
         * always 63 in 1.21.1, while flat-world sea level is −63.
         */
        SEA,
        /** No allowed source knows: world bottom, like vanilla for an unloaded chunk. */
        UNKNOWN
    }

    /** Surface height (first air above) and its source. */
    public record Surface(int y, Source source) {
        /** Some source supplied height (not {@link Source#UNKNOWN}). */
        public boolean known() {
            return source != Source.UNKNOWN;
        }
    }

    /**
     * Sources allowed for caller and optional client-map hint. Cost is encoded in the source set:
     * {@link #CHUNK}, {@link #FLIGHT} and {@link #RELIEF} are tick-safe; {@link #ORDER} reads generator — orders only.
     * Sets with background sources ({@link #RELIEF}) take {@link Source#DISK} and {@link Source#GENERATOR} only from
     * a completed survey ({@link TerrainSurvey}), requesting it if unanswered; never wait for disk or generator themselves.
     */
    public static final class Allowed {
        /** In-world, tick-safe: ready chunk (including ceiling if present), otherwise world bottom. */
        public static final Allowed CHUNK = new Allowed(Set.of(Source.CHUNK, Source.CEILING), Optional.empty(), false);
        /** Outside-world flight, tick-safe: ready chunk, otherwise sea level; ceiling is not ground. */
        public static final Allowed FLIGHT = new Allowed(Set.of(Source.CHUNK, Source.SEA), Optional.empty(), false);
        /** Order (once per launch): ready chunk, otherwise generator terrain. */
        public static final Allowed ORDER = new Allowed(Set.of(Source.CHUNK, Source.CEILING, Source.GENERATOR), Optional.empty(), false);
        /**
         * Tick-safe flight terrain, inside/outside world: ready chunk (including ceiling if present), otherwise survey — disk
         * or memory heightmap; for chunks absent from disk, generator terrain computed in background; until
         * survey answers, world bottom, like vanilla for an unloaded chunk.
         */
        public static final Allowed RELIEF = new Allowed(Set.of(Source.CHUNK, Source.CEILING, Source.DISK, Source.GENERATOR), Optional.empty(), true);

        /** Outside world, Nether ceiling is unknown terrain, like FLIGHT; bedrock top is not ground. */
        public static final Allowed VIRTUAL_RELIEF = new Allowed(Set.of(Source.CHUNK, Source.DISK, Source.GENERATOR), Optional.empty(), true);

        private final Set<Source> sources;
        private final Optional<Integer> map;
        /** {@link Source#DISK} and {@link Source#GENERATOR} only from completed survey ({@link TerrainSurvey}). */
        private final boolean survey;

        /** Externally, only the sets above and {@link #withMap}. */
        Allowed(Set<Source> sources, Optional<Integer> map, boolean survey) {
            if (sources.contains(Source.CLIENT_MAP) != map.isPresent()) throw new IllegalArgumentException("CLIENT_MAP only with a map hint");
            if (sources.contains(Source.DISK) && !survey) throw new IllegalArgumentException("DISK only from the background survey");
            this.sources = Set.copyOf(sources);
            this.map = map;
            this.survey = survey;
        }

        /**
         * Same sources plus {@link Source#CLIENT_MAP}: {@code top} is first air above ground from client map;
         * empty hints or tops whose block is outside world height are ignored.
         */
        public Allowed withMap(Optional<Integer> top) {
            if (top.isEmpty()) return this;
            Set<Source> s = EnumSet.of(Source.CLIENT_MAP);
            s.addAll(sources);
            return new Allowed(s, top, survey);
        }

        boolean permits(Source source) {
            return sources.contains(source);
        }

        /** Background sources: {@link Source#DISK} and {@link Source#GENERATOR} from survey, not during the call. */
        boolean survey() {
            return survey;
        }

        /** Client-map hint (present only if {@link Source#CLIENT_MAP} allowed). */
        Optional<Integer> map() {
            return map;
        }
    }

    /**
     * Surface height in column (x, z) — mod's sole answer to “where is the ground”: first source in {@code allowed},
     * ordered by {@link Source}, that knows. No chunk loading: readiness via {@link #ready}. Client has no generator,
     * only chunk and map; also no {@code …_NO_LEAVES} heightmaps (client receives only
     * {@code MOTION_BLOCKING} and {@code WORLD_SURFACE}).
     * Not target height: target may be airborne (flying player, craft), surface beneath it is not.
     */
    public static Surface estimate(Level level, Heightmap.Types type, int x, int z, Allowed allowed) {
        return choose(new LevelColumn(level, type, x >> 4, z >> 4), allowed).at(x, z);
    }

    /**
     * MOTION_BLOCKING heights for one synchronous sensor measurement. Both individual estimates and chunk views
     * use {@link #choose}. Views and heights must not outlive the measurement; background answers still become
     * visible only when TerrainSurvey.publish runs at the end of the world tick.
     */
    public static Relief relief(Level level, Allowed allowed) {
        if (allowed != Allowed.RELIEF && allowed != Allowed.VIRTUAL_RELIEF)
            throw new IllegalArgumentException("Relief survey requires a relief source set");
        return new Relief.Survey((cx, cz) ->
                choose(new LevelColumn(level, Heightmap.Types.MOTION_BLOCKING, cx, cz), allowed).height());
    }

    /** A selected source and its heights, resolved lazily at world column coordinates. */
    record View(IntBinaryOperator height, Source source) {
        Surface at(int x, int z) {
            return new Surface(height.applyAsInt(x, z), source);
        }

        static View constant(int height, Source source) {
            return new View((x, z) -> height, source);
        }
    }

    /** Available sources in one chunk; the generator is expensive and must only run when selected. */
    interface Column {
        boolean ready();

        /** First air above the ready chunk's heightmap, at world coordinates. */
        int chunkHeight(int x, int z);

        boolean ceiling();

        boolean hasGenerator();

        int sea();

        int generatorBase(int x, int z);

        boolean outside(int y);

        int bottom();

        /** Survey view, or null if pending, unavailable on the client, or excluded by a ceiling. */
        @Nullable
        default View surveyed() {
            return null;
        }
    }

    /** The single source-selection order for individual estimates and whole-chunk relief views. */
    static View choose(Column c, Allowed allowed) {
        if (c.ready()) {
            Source chunk = c.ceiling() ? Source.CEILING : Source.CHUNK;
            if (allowed.permits(chunk)) return new View(c::chunkHeight, chunk);
        }
        if (allowed.survey()) {
            View s = c.surveyed();
            if (s != null && allowed.permits(s.source())) return s;
        }
        if (allowed.permits(Source.CLIENT_MAP)) {
            int top = allowed.map().orElseThrow();
            if (!c.outside(top - 1)) return View.constant(top, Source.CLIENT_MAP);
        }
        if (c.hasGenerator()) {
            if (allowed.permits(Source.GENERATOR) && !allowed.survey())
                return new View((x, z) -> Math.max(c.sea(), c.generatorBase(x, z)), Source.GENERATOR);
            if (allowed.permits(Source.SEA)) return View.constant(c.sea(), Source.SEA);
        }
        return View.constant(c.bottom(), Source.UNKNOWN);
    }

    private static final class LevelColumn implements Column {
        private final Level level;
        private final Heightmap.Types type;
        private final int cx, cz;
        @Nullable
        private LevelChunk readyChunk;

        LevelColumn(Level level, Heightmap.Types type, int cx, int cz) {
            this.level = level;
            this.type = type;
            this.cx = cx;
            this.cz = cz;
        }

        @Override
        public boolean ready() {
            if (level instanceof ServerLevel server) {
                readyChunk = server.getChunkSource().getChunkNow(cx, cz);
                return readyChunk != null;
            }
            return level.hasChunk(cx, cz);
        }

        @Override
        public int chunkHeight(int x, int z) {
            if (level instanceof ServerLevel)
                return readyChunk == null ? level.getMinBuildHeight() : readyChunk.getHeight(type, x & 15, z & 15) + 1;
            return level.getHeight(type, x, z);
        }

        @Override
        public boolean ceiling() {
            return level.dimensionType().hasCeiling();
        }

        @Override
        public boolean hasGenerator() {
            return level instanceof ServerLevel;
        }

        @Override
        public int sea() {
            return generator().getSeaLevel();
        }

        @Override
        public int generatorBase(int x, int z) {
            ServerLevel server = (ServerLevel) level;
            return generator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, server, server.getChunkSource().randomState());
        }

        @Override
        @Nullable
        public View surveyed() {
            if (!(level instanceof ServerLevel server) || ceiling()) return null;
            return TerrainSurvey.get(server).view(server, type, cx, cz);
        }

        @Override
        public boolean outside(int y) {
            return level.isOutsideBuildHeight(y);
        }

        @Override
        public int bottom() {
            return level.getMinBuildHeight();
        }

        private ChunkGenerator generator() {
            return ((ServerLevel) level).getChunkSource().getGenerator();
        }
    }
}
