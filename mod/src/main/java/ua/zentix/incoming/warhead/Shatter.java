package ua.zentix.incoming.warhead;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.compat.DhUpdates;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.world.BlockResponse;
import ua.zentix.incoming.registry.ModTags;
import ua.zentix.incoming.strike.ImpactCost;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.util.Palettes;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.work.UnitQueue;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Blast shatters glass (missile also foliage): impact-queue work, unit up to {@link #PORTION} blocks in a 16³
 * section containing them; sections outward from blast center like wave. {@link Zone} defines what/where: glass
 * by warhead pressure ({@link Wave}), leaves in box ({@link Box}).
 * <p>
 * Wave travels through air: glass only if outside air reaches it, above column tops and up to {@link #SKY_DEPTH}
 * blocks inside through openings, like nuclear ({@code Blast.SKY_DEPTH}); glass behind glass too, front
 * penetration breaks full thickness. Underground bases, windowless basements, underwater glass intact.
 * <p>
 * Sound/fragments at shattered windows, not impact: each productive unit plays block-break sound from center of
 * its shattered blocks and emits their particles ({@link #burst}).
 * <p>
 * Read ready chunks only; palette rejects empty sections ({@link Palettes#contains}), making even missile sphere
 * of hundreds of sections cheap. Unready neighbor can be reached by update chains beyond any block margin
 * (disconnected pane shape; door checks redstone and conducting block neighbors; rails/wire farther), causing
 * synchronous load. Thus remove without neighbor updates there ({@link Warheads#EDGE_FLAGS}: client update
 * without neighbor shapes, neighboring pane keeps connection), and never within {@link Warheads#EDGE} blocks of
 * unready chunk: Sable reads neighbors of every changed block. Changed chunks update Distant Horizons LOD ({@link
 * DhUpdates#mark}).
 */
final class Shatter implements UnitQueue.Job {
    /** Palette-rejected or absent section is not a work unit. */
    private static final int SKIPPED = -1;
    /**
     * Maximum glass blocks per unit: glass tower section may have hundreds of windows; removal with all neighbor
     * updates costs ~10 μs, many times longer in host pack; plus up to {@link #SKY_DEPTH} outside-air search
     * steps.
     */
    static final int PORTION = 32;
    /** Maximum palette-rejected sections per unit: missile sphere has thousands, almost all glass-free. */
    private static final int SKIPS = 1024;
    /** Glass reach inward from outside air, blocks (like nuclear burst). */
    static final int SKY_DEPTH = 4;
    /** Wave blockers: same as {@link Heightmap.Types#MOTION_BLOCKING_NO_LEAVES} column top (not leaves). */
    private static final Predicate<BlockState> SOLID = Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque();
    private static final Direction[] DIRECTIONS = Direction.values();

    /** What/where wave breaks. */
    sealed interface Zone permits Box, Wave {
        /** Blocks wave can break. */
        TagKey<Block> tag();

        /** Corner of bounds where wave can break something. */
        BlockPos min();

        BlockPos max();

        /** Whether any block in section {@code (sx, sy, sz)} is reachable: no blocks beyond section read. */
        boolean reaches(int sx, int sy, int sz);

        /** Whether wave breaks tagged {@code state} at {@code (x, y, z)} within bounds. */
        boolean breaks(BlockState state, int x, int y, int z);

        /** Whether wave travels through air: without outside air ({@link #SKY_DEPTH}), block stays intact. */
        boolean airborne();
    }

    /** All tagged blocks in {@code min..max} box. */
    record Box(TagKey<Block> tag, BlockPos min, BlockPos max) implements Zone {
        /** Box around {@code centre}: {@code radius} sideways, {@code below} down, {@code above} up. */
        static Box around(Vec3 centre, int radius, int below, int above, TagKey<Block> tag) {
            BlockPos c = BlockPos.containing(centre);
            return new Box(tag, c.offset(-radius, -below, -radius), c.offset(radius, above, radius));
        }

        @Override
        public boolean reaches(int sx, int sy, int sz) {
            return true;
        }

        @Override
        public boolean breaks(BlockState state, int x, int y, int z) {
            return true;
        }

        @Override
        public boolean airborne() {
            return false;
        }
    }

    /**
     * Glass ({@link ModTags#SHATTERS}) from surface {@code tntKg} kg TNT blast: pressure by Kinney–Graham
     * distance ({@link BlastModel#surfaceOverpressureKpa}), break threshold/jitter as nuclear ({@link
     * BlockResponse#breaksAt}: glass 0.8 psi ±15%, DESIGN-nuke §3.3, different neighbors), so sphere edge windows
     * break intermittently rather than along a ruler. Sphere ends below minimum break pressure ({@link
     * BlockResponse#FRAGILE_PSI} × {@link BlockResponse#JITTER_MIN}). Wave wraps around building shadows (lower
     * pressure behind but windows still break); no shadow modeled here.
     *
     * @param radius sphere radius, blocks
     */
    record Wave(Vec3 centre, double tntKg, double radius) implements Zone {
        static Wave of(Vec3 centre, double tntKg) {
            double lowest = BlastModel.kpa(BlockResponse.FRAGILE_PSI * BlockResponse.JITTER_MIN);
            return new Wave(centre, tntKg, BlastModel.rangeForSurfaceOverpressure(lowest, tntKg));
        }

        @Override
        public TagKey<Block> tag() {
            return ModTags.SHATTERS;
        }

        @Override
        public BlockPos min() {
            return BlockPos.containing(centre.subtract(radius, radius, radius));
        }

        @Override
        public BlockPos max() {
            return BlockPos.containing(centre.add(radius, radius, radius));
        }

        @Override
        public boolean reaches(int sx, int sy, int sz) {
            // Section point nearest center.
            double dx = Mth.clamp(centre.x, sx << 4, (sx << 4) + 16) - centre.x;
            double dy = Mth.clamp(centre.y, sy << 4, (sy << 4) + 16) - centre.y;
            double dz = Mth.clamp(centre.z, sz << 4, (sz << 4) + 16) - centre.z;
            return dx * dx + dy * dy + dz * dz <= radius * radius;
        }

        @Override
        public boolean breaks(BlockState state, int x, int y, int z) {
            double d = Math.sqrt(centre.distanceToSqr(x + 0.5, y + 0.5, z + 0.5));
            if (d > radius) return false;
            double psi = BlastModel.psi(BlastModel.surfaceOverpressureKpa(d, tntKg));
            return BlockResponse.of(state).breaksAt(psi, Mth.murmurHash3Mixer(Long.hashCode(BlockPos.asLong(x, y, z))));
        }

        @Override
        public boolean airborne() {
            return true;
        }
    }

    @Nullable
    private final BlastArea area;
    private final List<StagedExplosion> after;
    private final Vec3 centre;
    private final Zone zone;
    private final BlockPos min, max;
    /**
     * World sections within reachable bounds ({@link SectionPos#asLong}), near-to-far; null until collected
     * (first unit, not impact tick: missile has thousands).
     */
    @Nullable
    private long[] sections;
    /** Changed chunks for Distant Horizons LOD. */
    private final LongOpenHashSet touched = new LongOpenHashSet();
    /** Outside-air search ({@link #open}): visited positions/traversal layers. */
    private final LongOpenHashSet visited = new LongOpenHashSet();
    private LongArrayList layer = new LongArrayList(), nextLayer = new LongArrayList();
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    /** Next section in {@link #sections}. */
    private int index;
    /** Current section and continuation index (y·256 + z·16 + x); −1 means take next. */
    private long current;
    private int cursor = -1;
    /** Last portion's broken blocks: first block (sound/particles), position bounds. */
    @Nullable
    private BlockState pane;
    private int x0, y0, z0, x1, y1, z1;

    /**
     * @param area strike area held until work ends; null without area/queue ({@link #now})
     * @param after wait for these explosions (main strike explosion)
     */
    Shatter(@Nullable BlastArea area, List<StagedExplosion> after, Vec3 centre, Zone zone) {
        this.area = area;
        this.after = after;
        this.centre = centre;
        this.zone = zone;
        min = zone.min();
        max = zone.max();
    }

    /** Break immediately without queue/area, same portions/sound/particles (tests). @return broken count */
    static int now(ServerLevel level, Vec3 centre, Zone zone) {
        Shatter job = new Shatter(null, List.of(), centre, zone);
        int broken = 0;
        while (job.hasNext(level)) {
            int n = job.nextPortion(level, PORTION);
            if (n > 0 && job.pane != null) job.burst(level, n);
            broken += Math.max(0, n);
        }
        return broken;
    }

    /** Queue strike impact work after {@code after}, holding {@code area}. */
    static void queue(ServerLevel level, BlastArea area, List<StagedExplosion> after, Vec3 centre, Zone zone) {
        StrikeWorld.get(level).impacts().add(level, new Shatter(area.retain(), after, centre, zone));
    }

    /** Reachable world sections nearest center first: sort key squared center distance. */
    private long[] sections(ServerLevel level) {
        if (sections != null) return sections;
        int sx0 = SectionPos.blockToSectionCoord(min.getX()), sx1 = SectionPos.blockToSectionCoord(max.getX());
        int sy0 = Math.max(level.getMinSection(), SectionPos.blockToSectionCoord(min.getY()));
        int sy1 = Math.min(level.getMaxSection() - 1, SectionPos.blockToSectionCoord(max.getY()));
        int sz0 = SectionPos.blockToSectionCoord(min.getZ()), sz1 = SectionPos.blockToSectionCoord(max.getZ());
        int ny = Math.max(0, sy1 - sy0 + 1), nz = sz1 - sz0 + 1;
        long[] keys = new long[(sx1 - sx0 + 1) * ny * nz];
        int n = 0;
        for (int sx = sx0; sx <= sx1; sx++) {
            for (int sy = sy0; sy <= sy1; sy++) {
                for (int sz = sz0; sz <= sz1; sz++) {
                    if (!zone.reaches(sx, sy, sz)) continue;
                    double d = centre.distanceToSqr(SectionPos.sectionToBlockCoord(sx, 8), SectionPos.sectionToBlockCoord(sy, 8),
                            SectionPos.sectionToBlockCoord(sz, 8));
                    // Squared distance (up to hundreds of thousands) in high bits, section index within bounds in low bits.
                    keys[n++] = (long) d << 32 | ((sx - sx0) * ny + (sy - sy0)) * nz + (sz - sz0);
                }
            }
        }
        Arrays.sort(keys, 0, n);
        sections = new long[n];
        for (int i = 0; i < n; i++) {
            int k = (int) keys[i];
            sections[i] = SectionPos.asLong(sx0 + k / (ny * nz), sy0 + k / nz % ny, sz0 + k % nz);
        }
        return sections;
    }

    boolean hasNext(ServerLevel level) {
        return cursor >= 0 || index < sections(level).length;
    }

    /**
     * Next portion: up to {@code limit} pressure-breakable blocks in current section from previous end, or next
     * in order. @return broken count, or {@link #SKIPPED} for palette rejection without block reads
     */
    int nextPortion(ServerLevel level, int limit) {
        TagKey<Block> tag = zone.tag();
        pane = null;
        if (cursor < 0) {
            current = sections(level)[index++];
            LevelChunkSection section = section(level);
            if (section == null || section.hasOnlyAir() || !section.maybeHas(st -> st.is(tag))) return SKIPPED;
            // Palette retains previously removed entries; global always “maybe”: count for exactness.
            if (!Palettes.contains(section.getStates(), st -> st.is(tag))) return 0;
            cursor = 0;
        }
        // Chunk may have left memory between portions.
        LevelChunkSection section = section(level);
        if (section == null) {
            cursor = -1;
            return 0;
        }
        int sx = SectionPos.x(current), sz = SectionPos.z(current);
        int bx = SectionPos.sectionToBlockCoord(sx), by = SectionPos.sectionToBlockCoord(SectionPos.y(current)), bz = SectionPos.sectionToBlockCoord(sz);
        // Ready neighbors: normal changes anywhere; otherwise no neighbor updates and not at edge.
        boolean edgesReady = Terrain.neighbourhoodReady(level, sx, sz);
        // seen counts pressure-breakable blocks, both removed and unreachable by air.
        int broken = 0, seen = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = cursor; i < 4096; i++) {
            int x = bx + (i & 15), y = by + (i >> 8), z = bz + (i >> 4 & 15);
            if (x < min.getX() || x > max.getX() || y < min.getY() || y > max.getY() || z < min.getZ() || z > max.getZ()) continue;
            // From acquired section: setBlock changes same object, subsequent reads remain valid.
            BlockState st = section.getBlockState(i & 15, i >> 8, i >> 4 & 15);
            if (!st.is(tag) || !zone.breaks(st, x, y, z)) continue;
            m.set(x, y, z);
            if ((!zone.airborne() || open(level, x, y, z, tag))
                    && (edgesReady || Terrain.readyAround(level, Vec3.atCenterOf(m), Warheads.EDGE))) {
                level.setBlock(m, Blocks.AIR.defaultBlockState(), edgesReady ? Block.UPDATE_ALL : Warheads.EDGE_FLAGS);
                touched.add(ChunkPos.asLong(sx, sz));
                broken++;
                if (pane == null) {
                    pane = st;
                    x0 = x1 = x;
                    y0 = y1 = y;
                    z0 = z1 = z;
                } else {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    z0 = Math.min(z0, z);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                    z1 = Math.max(z1, z);
                }
            }
            if (++seen == limit) {
                cursor = i + 1;
                return broken;
            }
        }
        cursor = -1;
        return broken;
    }

    /**
     * Whether outside air reaches block {@code (x, y, z)}: within {@link #SKY_DEPTH} + 1 face steps through
     * nonblocking cells or glass in {@code tag}, to above own column top. Ready chunks only; unready acts as
     * wall.
     */
    private boolean open(ServerLevel level, int x, int y, int z, TagKey<Block> tag) {
        visited.clear();
        layer.clear();
        long start = BlockPos.asLong(x, y, z);
        visited.add(start);
        layer.add(start);
        for (int step = 1; step <= SKY_DEPTH + 1 && !layer.isEmpty(); step++) {
            nextLayer.clear();
            for (int k = 0; k < layer.size(); k++) {
                long p = layer.getLong(k);
                for (Direction dir : DIRECTIONS) {
                    long n = BlockPos.offset(p, dir);
                    if (!visited.add(n)) continue;
                    int nx = BlockPos.getX(n), ny = BlockPos.getY(n), nz = BlockPos.getZ(n);
                    if (ny >= level.getMaxBuildHeight()) return true;
                    if (ny < level.getMinBuildHeight()) continue;
                    LevelChunk chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(nx), SectionPos.blockToSectionCoord(nz));
                    if (chunk == null) continue;
                    BlockState st = chunk.getBlockState(probe.set(nx, ny, nz));
                    boolean air = !SOLID.test(st);
                    if (air && ny > chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz)) return true;
                    if (step <= SKY_DEPTH && (air || st.is(tag))) nextLayer.add(n);
                }
            }
            LongArrayList t = layer;
            layer = nextLayer;
            nextLayer = t;
        }
        return false;
    }

    /**
     * Sound/fragments for last portion's {@code n} blocks: first block break sound (glass ring, foliage rustle),
     * at broken-bounds center, volume √n; n identical sources heard equally loud √n farther (intensities sum,
     * 1/r² falloff; Minecraft volume v heard to 16·v blocks). Block particles across bounds: {@link
     * #PANE_PARTICLES} per block, capped at {@link #BURST_PARTICLES} (vanilla full-block break count); particles
     * signify fragments, not their count. Visible nearby (server particles within 32 blocks), like normal block
     * breaking.
     */
    private void burst(ServerLevel level, int n) {
        BlockState st = pane;
        double x = 0.5 * (x0 + x1) + 0.5, y = 0.5 * (y0 + y1) + 0.5, z = 0.5 * (z0 + z1) + 0.5;
        var sound = st.getSoundType(level, BlockPos.containing(x, y, z), null);
        level.playSound(null, x, y, z, sound.getBreakSound(), SoundSource.BLOCKS, (float) Math.sqrt(n), sound.getPitch() * (0.9f + 0.2f * level.random.nextFloat()));
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, st), x, y, z, Math.min(BURST_PARTICLES, PANE_PARTICLES * n),
                0.25 * (x1 - x0 + 1), 0.25 * (y1 - y0 + 1), 0.25 * (z1 - z0 + 1), 0.15);
    }

    /** Particles per broken block: vanilla pane break count (4×4 shape cells, ¼ block each). */
    static final int PANE_PARTICLES = 16;
    /** Particles per portion cap: vanilla full-block break count (4×4×4). */
    static final int BURST_PARTICLES = 64;

    /** {@link #current} section from ready chunk, or null. */
    @Nullable
    private LevelChunkSection section(ServerLevel level) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(SectionPos.x(current), SectionPos.z(current));
        if (chunk == null) return null;
        int idx = chunk.getSectionIndexFromSectionY(SectionPos.y(current));
        return idx < 0 || idx >= chunk.getSectionsCount() ? null : chunk.getSection(idx);
    }

    @Override
    public boolean ready(ServerLevel level) {
        return area == null || area.ready(level);
    }

    @Override
    public boolean blocked() {
        return Warheads.pending(after);
    }

    @Override
    public int unitKind() {
        return ImpactCost.Kind.GLASS.ordinal();
    }

    @Override
    public boolean step(ServerLevel level) {
        long t0 = System.nanoTime();
        int broken = SKIPPED;
        // Palette-rejected sections in same unit: work is a portion of a section whose blocks needed inspection.
        for (int skipped = 0; hasNext(level) && broken == SKIPPED && skipped < SKIPS; skipped++) broken = nextPortion(level, PORTION);
        broken = Math.max(0, broken);
        if (broken > 0 && pane != null) burst(level, broken);
        long took = System.nanoTime() - t0;
        if (area != null) area.record(level, ImpactCost.Kind.GLASS, took, broken);
        else StrikeWorld.get(level).impactCost().add(ImpactCost.Kind.GLASS, took, broken);
        return hasNext(level);
    }

    @Override
    public void end(ServerLevel level) {
        long at = level.getGameTime() + DhUpdates.SETTLE;
        touched.forEach(c -> DhUpdates.mark(level, new ChunkPos(c), at));
        if (area != null) area.release(level);
    }

    @Override
    public String describe() {
        return "Glass at " + Mth.floor(centre.x) + " " + Mth.floor(centre.y) + " " + Mth.floor(centre.z);
    }
}
