package ua.zentix.incoming.nuclear.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.util.DiskStatus;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Chunks outside memory read directly from region files without world loading (ruin preparation phase 2,
 * {@link NuclearPrep}): reads on chunk I/O thread ({@code ChunkStorage.read}, same as world loading:
 * unsaved changes supplied from its write queue), data upgrades and parsing on background ruin
 * threads ({@link RuinWorkers#executor}), like vanilla (upgrades/parses chunks in background). Server
 * thread only runs {@link ChunkShot#fromDisk}: palette states into property table.
 * <p>
 * Only final-block chunks accepted ({@link DiskStatus.State#blocksFinal}, including 1.17 chunks with below-zero
 * retrogen); others skipped with reason ({@link Read#skip}), their ruins built after wave. Heightmaps
 * {@link RuinPlan#HEIGHTMAP_TYPES} absent or wrong-sized on disk (editor maps: incomplete
 * maps in Greenfield for 295855 of 295936 chunks, Newisle 23595 of 23644 whole chunks) primed from blocks as
 * vanilla does on load ({@link Read#primed}).
 */
final class DiskShots {
    private static final Codec<PalettedContainer<BlockState>> BLOCK_STATE_CODEC = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY,
            BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
    /** Window cost of chunk without disk heightmaps ({@link Read#cost}): at most 4 primings per {@link RuinContext#requestWindow}. */
    static final int PRIME_COST = 6;

    private DiskShots() {}

    /**
     * Read chunk: sections (null air), first free height by {@link RuinPlan#HEIGHTMAP_TYPES}
     * ({@code kind * 256 + column}); {@code unprimed} maps absent on disk (bit {@code 1 << kind}, values
     * not yet computed, {@link #primed}); {@code skip} reason invalid (rest empty then); {@code flashLayer}
     * flash-layer mark in chunk data ({@code FLASH_LAYER}: detonation ID whose layer fire stands without ruins; 0 absent):
     * snapshot for that detonation reads fire as air, like in-memory chunk snapshot ({@link ChunkShot#fromDisk}).
     */
    record Read(ChunkPos pos, int minY, PalettedContainer<BlockState>[] states, int[] heights, int unprimed, @Nullable String skip, int flashLayer) {
        static Read skipped(ChunkPos pos, String why) {
            return new Read(pos, 0, empty(), new int[0], 0, why, 0);
        }

        /**
         * Heightmaps absent on disk computed from blocks, as vanilla primes them on load
         * ({@code Heightmap.primeHeightmaps}: downward to first map-opaque block, world bottom if none).
         * Server thread: state properties from ruin table ({@code props}). Cost averages 0.6 ms (up to 1.4 ms) per chunk
         * with all sections nonempty to world top but columns mostly air (measured: glass pillar to 319
         * above flat world), usually tens of microseconds; {@link #cost} limits how many server thread takes at once.
         */
        Read primed(Blast.PropsView props) {
            if (unprimed == 0) return this;
            int[] out = heights.clone();
            int types = RuinPlan.HEIGHTMAP_TYPES.length;
            // consecutive column blocks nearly always same state (air, water): map flags without table lookup
            BlockState last = null;
            int opaque = 0;
            for (int c = 0; c < 256; c++) {
                int left = unprimed;
                for (int t = 0; t < types; t++) if ((left & 1 << t) != 0) out[t * 256 + c] = minY;
                for (int i = states.length - 1; i >= 0 && left != 0; i--) {
                    if (states[i] == null) continue;
                    for (int y = 15; y >= 0 && left != 0; y--) {
                        BlockState st = states[i].get(c & 15, y, c >> 4);
                        if (st != last) {
                            last = st;
                            Blast.Props p = props.get(st);
                            opaque = 0;
                            for (int t = 0; t < types; t++) if (p.opaque(t)) opaque |= 1 << t;
                        }
                        int hit = left & opaque;
                        if (hit == 0) continue;
                        for (int t = 0; t < types; t++) if ((hit & 1 << t) != 0) out[t * 256 + c] = minY + (i << 4) + y + 1;
                        left &= ~hit;
                    }
                }
            }
            return new Read(pos, minY, states, out, 0, null, flashLayer);
        }

        /**
         * Server-thread cost of this chunk in {@link RuinContext#requestWindow}, in chunks with disk heightmaps:
         * worst-case heightmap priming ({@link #primed}) tens of times more expensive than snapshot: 25 at once means tens of milliseconds
         * in one tick, 4 at once up to 4.3 ms.
         */
        int cost() {
            return unprimed == 0 ? 1 : PRIME_COST;
        }

        /** First air above {@code MOTION_BLOCKING}, for light-pulse shadow ({@link RuinContext#putHeights}). */
        int[] motion() {
            int t = ChunkShot.type(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING);
            return java.util.Arrays.copyOfRange(heights, t * 256, t * 256 + 256);
        }

        @SuppressWarnings("unchecked")
        private static PalettedContainer<BlockState>[] empty() {
            return (PalettedContainer<BlockState>[]) new PalettedContainer<?>[0];
        }
    }

    /** World data needed for chunk parsing (captured on server thread; parsing itself reads no world). */
    record Format(ChunkMap storage, ResourceKey<Level> dimension, Optional<ResourceKey<MapCodec<? extends ChunkGenerator>>> generator,
                  int minSection, int sections, int minY, int height, Registry<Biome> biomes, String flashLayerKey) {
        static Format of(ServerLevel level) {
            return new Format(level.getChunkSource().chunkMap, level.dimension(), level.getChunkSource().getGenerator().getTypeNameForDataFixer(),
                    level.getMinSection(), level.getSectionsCount(), level.getMinBuildHeight(), level.getHeight(),
                    level.registryAccess().registryOrThrow(Registries.BIOME),
                    String.valueOf(NeoForgeRegistries.ATTACHMENT_TYPES.getKey(ModAttachments.FLASH_LAYER.get())));
        }
    }

    /**
     * Read and parse chunk (server thread submits, result on background ruin thread): I/O-thread read,
     * {@link RuinWorkers} parsing at {@code priority}. No chunk on disk: {@code skip}.
     */
    static CompletableFuture<Read> read(Format f, ChunkPos pos, RuinWorkers.Priority priority) {
        long diag = NukeDiag.readStart();
        return f.storage.read(pos).whenComplete((t, e) -> NukeDiag.readDone(diag)).thenApplyAsync(tag -> tag.map(t -> parse(f, pos, t)).orElseGet(() -> Read.skipped(pos, "not on disk")),
                RuinWorkers.executor(priority)).exceptionally(e -> Read.skipped(pos, "read error: " + e));
    }

    /** Parse chunk data (any thread). */
    static Read parse(ServerLevel level, ChunkPos pos, CompoundTag tag) {
        return parse(Format.of(level), pos, tag);
    }

    static Read parse(Format f, ChunkPos pos, CompoundTag raw) {
        Checked checked = check(f, raw);
        if (checked.skip != null) return Read.skipped(pos, checked.skip);
        int[] heights = new int[RuinPlan.HEIGHTMAP_TYPES.length * 256];
        int unprimed = heights(f, checked.tag, heights);
        PalettedContainer<BlockState>[] states = blankStates(f);
        ListTag list = checked.tag.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag section = list.getCompound(i);
            int index = section.getByte("Y") - f.minSection;
            if (index < 0 || index >= f.sections || !section.contains("block_states", Tag.TAG_COMPOUND)) continue;
            Optional<PalettedContainer<BlockState>> parsed = BLOCK_STATE_CODEC.parse(NbtOps.INSTANCE, section.getCompound("block_states")).result();
            if (parsed.isEmpty()) return Read.skipped(pos, "section " + index + " not parsed");
            PalettedContainer<BlockState> c = parsed.get();
            if (c.maybeHas(st -> !st.isAir())) states[index] = c;
        }
        return new Read(pos, f.minY, states, heights, unprimed, null, flashLayer(f, checked.tag));
    }

    /** Flash-layer mark in chunk data (attachment, as NeoForge writes {@code neoforge:attachments}); 0 absent. */
    private static int flashLayer(Format f, CompoundTag tag) {
        return tag.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY).getInt(f.flashLayerKey);
    }

    @SuppressWarnings("unchecked")
    private static PalettedContainer<BlockState>[] blankStates(Format f) {
        return (PalettedContainer<BlockState>[]) new PalettedContainer<?>[f.sections];
    }

    /**
     * Heightmaps: all plan types in native map format (bits per value by world height), into {@code out}; absent
     * or wrong-sized map primed from blocks ({@link Read#primed}), like vanilla on load.
     *
     * @return absent maps ({@link Read#unprimed})
     */
    private static int heights(Format f, CompoundTag tag, int[] out) {
        CompoundTag maps = tag.getCompound("Heightmaps");
        int bits = Mth.ceillog2(f.height + 1), unprimed = 0;
        for (int t = 0; t < RuinPlan.HEIGHTMAP_TYPES.length; t++) {
            String key = RuinPlan.HEIGHTMAP_TYPES[t].getSerializationKey();
            SimpleBitStorage data = null;
            if (maps.contains(key, Tag.TAG_LONG_ARRAY)) {
                try {
                    data = new SimpleBitStorage(bits, 256, maps.getLongArray(key));
                } catch (SimpleBitStorage.InitializationException e) {
                    // wrong size: vanilla also discards map and computes it from blocks
                }
            }
            if (data == null) {
                unprimed |= 1 << t;
                continue;
            }
            for (int c = 0; c < 256; c++) out[t * 256 + c] = data.get(c) + f.minY;
        }
        return unprimed;
    }

    /** Chunk data upgraded to current version, or why chunk invalid ({@code skip}). */
    private record Checked(CompoundTag tag, @Nullable String skip) {}

    private static Checked check(Format f, CompoundTag tag) {
        // as on disk before version upgrade: same rule as header (DiskStatus.scan)
        if (!DiskStatus.of(tag).blocksFinal()) return new Checked(tag, "not fully generated on disk");
        int version = ChunkStorage.getVersion(tag);
        if (version != SharedConstants.getCurrentVersion().getDataVersion().getVersion()) {
            try {
                tag = f.storage.upgradeChunkTag(f.dimension, () -> null, tag, f.generator);
            } catch (RuntimeException e) {
                return new Checked(tag, "data upgrade failed from version " + version);
            }
        }
        return new Checked(tag, null);
    }

    /**
     * Whole chunk: block/biome sections parsed like vanilla ({@code ChunkSerializer.read}) without world:
     * no POI, light or heightmaps. For ruined chunk copies in Distant Horizons LODs ({@link FarLods}). I/O-thread
     * read, {@link RuinWorkers} parsing; {@code skip} explains invalid chunk.
     */
    record Sections(ChunkPos pos, LevelChunkSection[] sections, @Nullable String skip) {}

    static CompletableFuture<Sections> readSections(Format f, ChunkPos pos) {
        return f.storage.read(pos).thenApplyAsync(tag -> tag.map(t -> parseSections(f, pos, t)).orElseGet(() -> new Sections(pos, new LevelChunkSection[0], "not on disk")),
                RuinWorkers.executor(RuinWorkers.Priority.FAR)).exceptionally(e -> new Sections(pos, new LevelChunkSection[0], "read error: " + e));
    }

    static Sections parseSections(Format f, ChunkPos pos, CompoundTag raw) {
        Checked checked = check(f, raw);
        if (checked.skip != null) return new Sections(pos, new LevelChunkSection[0], checked.skip);
        return sections(f, pos, checked.tag);
    }

    /** Chunk sections from data already upgraded to current version ({@link #check}). */
    private static Sections sections(Format f, ChunkPos pos, CompoundTag tag) {
        Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec = PalettedContainer.codecRO(f.biomes.asHolderIdMap(), f.biomes.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES, f.biomes.getHolderOrThrow(Biomes.PLAINS));
        LevelChunkSection[] sections = new LevelChunkSection[f.sections];
        ListTag list = tag.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag section = list.getCompound(i);
            int index = section.getByte("Y") - f.minSection;
            if (index < 0 || index >= f.sections) continue;
            PalettedContainer<BlockState> states;
            if (section.contains("block_states", Tag.TAG_COMPOUND)) {
                Optional<PalettedContainer<BlockState>> parsed = BLOCK_STATE_CODEC.parse(NbtOps.INSTANCE, section.getCompound("block_states")).result();
                if (parsed.isEmpty()) return new Sections(pos, new LevelChunkSection[0], "section " + index + " not parsed");
                states = parsed.get();
            } else {
                states = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
            }
            PalettedContainerRO<Holder<Biome>> biomes;
            if (section.contains("biomes", Tag.TAG_COMPOUND)) {
                Optional<PalettedContainerRO<Holder<Biome>>> parsed = biomeCodec.parse(NbtOps.INSTANCE, section.getCompound("biomes")).result();
                if (parsed.isEmpty()) return new Sections(pos, new LevelChunkSection[0], "biomes of section " + index + " not parsed");
                biomes = parsed.get();
            } else {
                biomes = new PalettedContainer<>(f.biomes.asHolderIdMap(), f.biomes.getHolderOrThrow(Biomes.PLAINS), PalettedContainer.Strategy.SECTION_BIOMES);
            }
            sections[index] = new LevelChunkSection(states, biomes);
        }
        // sections absent from data absent in world: air with default biome, like vanilla
        for (int i = 0; i < sections.length; i++) if (sections[i] == null) sections[i] = new LevelChunkSection(f.biomes);
        return new Sections(pos, sections, null);
    }

    /**
     * Chunk for distant LOD detonation trace without ruins ({@link FarLods}): copy sections and snapshot share palettes, one
     * read and parse. Snapshot reads copy palettes: trace plan ({@link RuinPlanner#scorch}) built before its positions
     * are written into copy. {@code read.skip} explains invalid chunk (sections then absent too).
     */
    record Scorch(Sections sections, Read read) {}

    static CompletableFuture<Scorch> readScorch(Format f, ChunkPos pos) {
        return f.storage.read(pos).thenApplyAsync(tag -> tag.map(t -> parseScorch(f, pos, t)).orElseGet(() -> skippedScorch(pos, "not on disk")),
                RuinWorkers.executor(RuinWorkers.Priority.FAR)).exceptionally(e -> skippedScorch(pos, "read error: " + e));
    }

    static Scorch parseScorch(Format f, ChunkPos pos, CompoundTag raw) {
        Checked checked = check(f, raw);
        if (checked.skip != null) return skippedScorch(pos, checked.skip);
        Sections sections = sections(f, pos, checked.tag);
        if (sections.skip() != null) return skippedScorch(pos, sections.skip());
        int[] heights = new int[RuinPlan.HEIGHTMAP_TYPES.length * 256];
        int unprimed = heights(f, checked.tag, heights);
        PalettedContainer<BlockState>[] states = blankStates(f);
        for (int i = 0; i < states.length; i++) {
            PalettedContainer<BlockState> c = sections.sections()[i].getStates();
            if (c.maybeHas(st -> !st.isAir())) states[i] = c;
        }
        return new Scorch(sections, new Read(pos, f.minY, states, heights, unprimed, null, flashLayer(f, checked.tag)));
    }

    private static Scorch skippedScorch(ChunkPos pos, String why) {
        return new Scorch(new Sections(pos, new LevelChunkSection[0], why), Read.skipped(pos, why));
    }
}
