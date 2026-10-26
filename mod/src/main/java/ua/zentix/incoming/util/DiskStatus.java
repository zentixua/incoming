package ua.zentix.incoming.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Disk chunk status — one rule for the whole mod. Final blocks ({@link State#blocksFinal}) allow a disk ruin plan
 * ({@code nuclear.world.DiskShots}, preparation phase 2 in {@code nuclear.world.NuclearPrep}) and a ruined disk copy for
 * distant LODs ({@code nuclear.world.FarLods}: otherwise {@code nuclear.world.FarZone} loads it into the world).
 * Whole ({@link State#WHOLE}) allows the post-wave zone to load it without generation
 * ({@code nuclear.world.NuclearPrep}: chunk whose ruin window is whole). Difference: 1.17 worlds upgraded to 1.21 have
 * blocks in place, but vanilla generates the below-zero world bottom on load. Their disk plan is valid (checks old
 * states at plan positions, excluding world bottom and bedrock); zone does not load them before the wave, player or
 * LODs do afterward; plan retained until zone end. Almost all chunks in old maps are such (Zearth — 77974 of 86392 disk
 * chunks, Greenfield — 295718 of 295936): classified “not whole”, they would get no disk plan and distant LODs would
 * load them into the world with generation.
 */
public final class DiskStatus {
    /** Full-generation name in {@code Status}. */
    private static final ResourceLocation FULL = ResourceLocation.withDefaultNamespace("full");
    /**
     * Statuses after which chunk blocks are final: decorations from radius-1 neighbors (also writing into this chunk) have
     * been placed — vanilla lighting waits for them ({@code ChunkPyramid}). For 1.17 chunks, full generation in
     * {@code below_zero_retrogen} is recorded as {@code heightmaps}, renamed to {@code spawn}.
     */
    private static final Set<ResourceLocation> FINISHED = Set.of(ResourceLocation.withDefaultNamespace("light"),
            ResourceLocation.withDefaultNamespace("spawn"), ResourceLocation.withDefaultNamespace("heightmaps"), FULL);

    /** Disk chunk status. */
    public enum State {
        /** Absent on disk, incomplete (rings at explored-world edge, structure starts), or read error. */
        PARTIAL,
        /**
         * Final blocks, but cannot enter the world without generation: below-zero retrogen ({@code below_zero_retrogen}) or
         * pre-1.18 format ({@code Status} inside {@code Level}: upgrade result depends on dimension).
         */
        FINAL,
        /** Full generation without retrogen: vanilla loads without generating anything. */
        WHOLE;

        /** Suitable for a disk ruin plan and LOD copy. */
        public boolean blocksFinal() {
            return this != PARTIAL;
        }
    }

    private DiskStatus() {}

    /**
     * Chunk status from disk data before version upgrade. Unnamespaced status names (old saves) are {@code minecraft}, as
     * vanilla reads them ({@code ChunkStatus.byName}). No status registry needed: checked on background threads too.
     *
     * @param fields chunk data or only {@link #fields}; {@code null} — chunk absent
     */
    public static State of(@Nullable CompoundTag fields) {
        if (fields == null) return State.PARTIAL;
        if (fields.contains("below_zero_retrogen", Tag.TAG_COMPOUND))
            return finished(fields.getCompound("below_zero_retrogen").getString("target_status")) ? State.FINAL : State.PARTIAL;
        if (fields.contains("Status", Tag.TAG_STRING))
            return FULL.equals(ResourceLocation.tryParse(fields.getString("Status"))) ? State.WHOLE : State.PARTIAL;
        if (fields.contains("Level", Tag.TAG_COMPOUND))
            return finished(fields.getCompound("Level").getString("Status")) ? State.FINAL : State.PARTIAL;
        return State.PARTIAL;
    }

    private static boolean finished(String status) {
        // name with invalid characters does not parse (null): not a vanilla status
        ResourceLocation name = ResourceLocation.tryParse(status);
        return name != null && FINISHED.contains(name);
    }

    /** Fields used by {@link #of}, without decoding the whole chunk ({@code chunkScanner}). */
    private static CollectFields fields() {
        return fields(new FieldSelector[0]);
    }

    /** {@link #of} fields plus {@code extra} in one header read ({@link TerrainSurvey}: heightmap too). */
    static CollectFields fields(FieldSelector... extra) {
        java.util.List<FieldSelector> all = new java.util.ArrayList<>(java.util.List.of(new FieldSelector(StringTag.TYPE, "Status"),
                new FieldSelector(CompoundTag.TYPE, "below_zero_retrogen"), new FieldSelector("Level", StringTag.TYPE, "Status")));
        all.addAll(java.util.List.of(extra));
        return new CollectFields(all.toArray(FieldSelector[]::new));
    }

    /**
     * Disk chunk status from its header on the chunk I/O thread ({@code chunkScanner}: without loading or decoding the
     * chunk). Completion arrives on the I/O thread; read error — {@link State#PARTIAL}.
     */
    public static CompletableFuture<State> scan(ServerLevel level, ChunkPos pos) {
        CollectFields fields = fields();
        return level.getChunkSource().chunkMap.chunkScanner().scanChunk(pos, fields)
                .handle((v, e) -> e == null ? of(fields.getResult() instanceof CompoundTag tag ? tag : null) : State.PARTIAL);
    }
}
