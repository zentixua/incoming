package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** One immutable source-chunk descriptor. Original-input durability is an external commit gate, never a saved assertion. */
final class PreparedSourcePlan {
    private static final int VERSION = 1, MAX_SECTIONS = 256;

    /** Source cell packs a zero-based section index and its 12-bit local cell; destination belongs to this preparation. */
    record Reference(int sourceCell, long destinationSection, int rowIndex) {
        Reference {
            if (sourceCell < 0 || sourceCell >= MAX_SECTIONS * 4096 || rowIndex < 0 || rowIndex >= PreparedMaterialBatch.MAX_MOVES
                    || SectionPos.y(destinationSection) < -128 || SectionPos.y(destinationSection) > 127)
                throw new IllegalArgumentException("Invalid prepared source reference");
        }
    }

    private final UUID preparation;
    private final long generation;
    private final ChunkPos source;
    private final int minSection;
    private final RuinPlan delta;
    private final long[] due;
    private final int[] sources, rows;
    private final long[] destinations;

    /** The producer must finish the handed-off RuinPlan before publication, as for its existing detached snapshot API. */
    PreparedSourcePlan(UUID preparation, long generation, ChunkPos source, int minSection, RuinPlan delta,
                       long[] dueTickOffsets, List<Reference> references) {
        checkIdentity(preparation, generation, source, minSection, dueTickOffsets);
        if (references.size() > dueTickOffsets.length * 4096) throw new IllegalArgumentException("Too many source references");
        var ordered = new ArrayList<>(references);
        ordered.sort(Comparator.comparingInt(Reference::sourceCell));
        int[] cells = new int[ordered.size()], indices = new int[ordered.size()];
        long[] sections = new long[ordered.size()];
        for (int i = 0; i < ordered.size(); i++) {
            Reference ref = ordered.get(i);
            cells[i] = ref.sourceCell; sections[i] = ref.destinationSection; indices[i] = ref.rowIndex;
        }
        validateReferences(cells, sections, indices, dueTickOffsets.length);
        delta.validateSource(source, minSection, dueTickOffsets.length, cells);
        this.preparation = preparation; this.generation = generation; this.source = source; this.minSection = minSection;
        this.delta = delta; due = dueTickOffsets.clone(); sources = cells; rows = indices; destinations = sections;
    }

    private PreparedSourcePlan(PreparedSourcePlan from, RuinPlan delta) {
        preparation = from.preparation; generation = from.generation; source = from.source; minSection = from.minSection;
        this.delta = delta; due = from.due; sources = from.sources; rows = from.rows; destinations = from.destinations;
    }

    /** The decoder transfers its private NBT arrays; do not serialize or copy the decoded delta again. */
    private PreparedSourcePlan(UUID preparation, long generation, ChunkPos source, int minSection, RuinPlan delta,
                               long[] due, int[] sources, long[] destinations, int[] rows) {
        this.preparation = preparation; this.generation = generation; this.source = source; this.minSection = minSection;
        this.delta = delta; this.due = due; this.sources = sources;
        this.destinations = destinations; this.rows = rows;
    }

    UUID preparation() { return preparation; }
    long generation() { return generation; }
    ChunkPos source() { return source; }
    int minSection() { return minSection; }
    int sections() { return due.length; }
    RuinPlan delta() { return delta; }
    long dueTick(int sectionY) { return due[index(sectionY)]; }
    PreparedSourcePlan detached() { return new PreparedSourcePlan(this, delta.detached()); }

    /** At most one section's 4096 immutable references; no global resident reverse index. */
    List<Reference> references(int sectionY) {
        int start = index(sectionY) << 12, end = start + 4096;
        int first = Arrays.binarySearch(sources, start);
        if (first < 0) first = -first - 1;
        var result = new ArrayList<Reference>();
        for (int i = first; i < sources.length && sources[i] < end; i++)
            result.add(new Reference(sources[i], destinations[i], rows[i]));
        return List.copyOf(result);
    }

    BlockPos sourcePos(Reference reference) {
        int cell = reference.sourceCell;
        if (cell >= due.length * 4096) throw new IllegalArgumentException("Source reference outside chunk height");
        return new BlockPos(source.getMinBlockX() + (cell & 15), (minSection << 4) + (cell >> 8),
                source.getMinBlockZ() + ((cell >> 4) & 15));
    }

    /** Check the actual leased destination owner before any source mutation; a descriptor cannot vouch for a cold row. */
    int verify(Reference reference, PreparedMaterialBatch batch) {
        int i = Arrays.binarySearch(sources, reference.sourceCell);
        if (i < 0 || destinations[i] != reference.destinationSection || rows[i] != reference.rowIndex
                || !preparation.equals(batch.plan()) || PreparedMaterialStore.Key.of(batch).section() != reference.destinationSection
                || reference.rowIndex >= batch.size() || !sourcePos(reference).equals(batch.move(reference.rowIndex).source()))
            throw new IllegalArgumentException("Prepared source reference does not match its material owner");
        return reference.rowIndex;
    }

    long retainedBytes() { return 256L + delta.bytes() + 8L * due.length + 16L * sources.length; }

    /** Encode-only view: as in RuinPlan.store, primitive payloads are borrowed immutable arrays, never caller-editable NBT. */
    CompoundTag store() {
        var tag = new CompoundTag();
        tag.putInt("version", VERSION); tag.putUUID("preparation", preparation); tag.putLong("generation", generation);
        tag.putLong("chunk", source.toLong()); tag.putInt("min_section", minSection);
        tag.putLongArray("due", due); tag.put("delta", delta.store());
        tag.putIntArray("sources", sources); tag.putLongArray("destinations", destinations);
        tag.putIntArray("rows", rows);
        return tag;
    }

    /** Includes input NBT, decoded owners and bounded reference-validation scratch; not a hostile-NBT parser cap. */
    static long restorePublicationBytes(CompoundTag tag) {
        validateHeader(tag);
        return 512L + tag.sizeInBytes() + RuinPlan.restorePublicationBytes(tag.getCompound("delta"))
                + 1040L * tag.getLongArray("due").length + 112L * tag.getIntArray("sources").length;
    }

    /** Transfers the freshly decoded tag's ownership; its primitive payloads must not be mutated after this call. */
    static PreparedSourcePlan restore(CompoundTag tag, @Nullable RuinContext context, long maximumPublicationBytes) {
        long bytes = restorePublicationBytes(tag);
        if (maximumPublicationBytes < 0 || bytes > maximumPublicationBytes)
            throw new IllegalArgumentException("Prepared source restore exceeds its reserved publication capacity");
        UUID preparation = tag.getUUID("preparation");
        long generation = tag.getLong("generation");
        ChunkPos source = new ChunkPos(tag.getLong("chunk"));
        int minSection = tag.getInt("min_section");
        long[] due = tag.getLongArray("due");
        checkIdentity(preparation, generation, source, minSection, due);
        int[] cells = tag.getIntArray("sources"), rows = tag.getIntArray("rows");
        long[] destinations = tag.getLongArray("destinations");
        validateReferences(cells, destinations, rows, due.length);
        RuinPlan delta = RuinPlan.restore(tag.getCompound("delta"), context);
        delta.validateSource(source, minSection, due.length, cells);
        return new PreparedSourcePlan(preparation, generation, source, minSection, delta, due, cells, destinations, rows);
    }

    private int index(int sectionY) {
        int index = sectionY - minSection;
        if (index < 0 || index >= due.length) throw new IllegalArgumentException("Source section outside declared chunk height");
        return index;
    }

    private static void checkIdentity(UUID preparation, long generation, ChunkPos source, int minSection, long[] due) {
        if (preparation == null || generation < 0 || source == null || source.x < -(1 << 21) || source.x >= 1 << 21
                || source.z < -(1 << 21) || source.z >= 1 << 21 || due.length < 1 || due.length > MAX_SECTIONS
                || minSection < -128 || minSection > 127 || minSection + due.length > 128)
            throw new IllegalArgumentException("Invalid prepared source identity or chunk height");
        for (long tick : due) if (tick < 0) throw new IllegalArgumentException("Negative prepared source due offset");
    }

    private static void validateHeader(CompoundTag tag) {
        type(tag, "version", Tag.TAG_INT); type(tag, "preparation", Tag.TAG_INT_ARRAY); type(tag, "generation", Tag.TAG_LONG);
        type(tag, "chunk", Tag.TAG_LONG); type(tag, "min_section", Tag.TAG_INT); type(tag, "due", Tag.TAG_LONG_ARRAY);
        type(tag, "delta", Tag.TAG_COMPOUND); type(tag, "sources", Tag.TAG_INT_ARRAY);
        type(tag, "destinations", Tag.TAG_LONG_ARRAY); type(tag, "rows", Tag.TAG_INT_ARRAY);
        int height = tag.getLongArray("due").length, count = tag.getIntArray("sources").length;
        if (tag.getInt("version") != VERSION || tag.getIntArray("preparation").length != 4
                || height < 1 || height > MAX_SECTIONS || count > height * 4096
                || tag.getLongArray("destinations").length != count || tag.getIntArray("rows").length != count)
            throw new IllegalArgumentException("Invalid prepared source dimensions");
        RuinPlan.validateStored(tag.getCompound("delta"));
        int sections = tag.getCompound("delta").getInt("sections");
        if (sections != height && sections != 0) throw new IllegalArgumentException("Ruin delta has another chunk height");
    }

    private static void validateReferences(int[] cells, long[] destinations, int[] rows, int height) {
        if (destinations.length != cells.length || rows.length != cells.length || cells.length > height * 4096)
            throw new IllegalArgumentException("Invalid prepared source reference dimensions");
        var ordered = new Reference[cells.length];
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] >= height * 4096 || i > 0 && cells[i - 1] >= cells[i])
                throw new IllegalArgumentException("Duplicate or unordered source cell");
            ordered[i] = new Reference(cells[i], destinations[i], rows[i]);
        }
        Arrays.sort(ordered, Comparator.comparingLong(Reference::destinationSection).thenComparingInt(Reference::rowIndex));
        for (int i = 1; i < ordered.length; i++)
            if (ordered[i - 1].destinationSection == ordered[i].destinationSection && ordered[i - 1].rowIndex == ordered[i].rowIndex)
                throw new IllegalArgumentException("Material row referenced twice by one source chunk");
    }

    private static void type(CompoundTag tag, String name, int type) {
        if (!tag.contains(name, type)) throw new IllegalArgumentException("Invalid prepared source field " + name);
    }
}
