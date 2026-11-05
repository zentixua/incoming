package ua.zentix.incoming.nuclear.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;

/** Partial source writes saved with their chunk, independent of destination material ownership. */
public final class PreparedSourceProgress {
    private static final int MAX_SECTIONS = 256, WORDS = 64;
    private static final int PLANNED = 0, BEGUN = 1, FINISHED = 2;

    record Identity(int event, UUID preparation, long generation) {
        private static final Codec<Identity> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.intRange(1, Integer.MAX_VALUE).fieldOf("event").forGetter(Identity::event),
                UUIDUtil.CODEC.fieldOf("preparation").forGetter(Identity::preparation),
                Codec.LONG.validate(value -> value >= 0 ? DataResult.success(value)
                        : DataResult.error(() -> "Negative source plan generation")).fieldOf("generation").forGetter(Identity::generation)
        ).apply(i, Identity::new));

        Identity {
            Objects.requireNonNull(preparation);
            if (event <= 0 || generation < 0) throw new IllegalArgumentException("Invalid prepared source identity");
        }
    }

    private static final Codec<long[]> MASK = Codec.LONG_STREAM.comapFlatMap(stream -> {
        long[] words = stream.limit(WORDS + 1L).toArray();
        return words.length > WORDS ? DataResult.error(() -> "Source section mask exceeds 4096 cells") : DataResult.success(words);
    }, LongStream::of);

    private record Section(int y, long[] planned, long[] completed) {
        private static final Codec<Section> CODEC = RecordCodecBuilder.<Section>create(i -> i.group(
                Codec.INT.fieldOf("y").forGetter(Section::y),
                MASK.fieldOf("planned").forGetter(Section::planned),
                MASK.fieldOf("completed").forGetter(Section::completed)
        ).apply(i, Section::new)).validate(s -> {
            BitSet outside = BitSet.valueOf(s.completed);
            outside.andNot(BitSet.valueOf(s.planned));
            return BitSet.valueOf(s.planned).isEmpty() || !outside.isEmpty()
                    ? DataResult.error(() -> "Invalid source planned/completed mask") : DataResult.success(s);
        });

        Section {
            planned = planned.clone();
            completed = completed.clone();
        }

        BitSet pending() {
            BitSet bits = BitSet.valueOf(planned);
            bits.andNot(BitSet.valueOf(completed));
            return bits;
        }

        boolean complete() { return pending().isEmpty(); }
    }

    private record Active(Identity identity, List<Section> sections, int stage) {
        private static final Codec<Active> CODEC = RecordCodecBuilder.<Active>create(i -> i.group(
                Identity.CODEC.fieldOf("identity").forGetter(Active::identity),
                Section.CODEC.listOf(0, MAX_SECTIONS).fieldOf("sections").forGetter(Active::sections),
                Codec.intRange(PLANNED, FINISHED).fieldOf("stage").forGetter(Active::stage)
        ).apply(i, Active::new)).validate(a -> {
            for (int i = 1; i < a.sections.size(); i++) if (a.sections.get(i - 1).y >= a.sections.get(i).y)
                return DataResult.error(() -> "Duplicate or unordered source sections");
            boolean any = a.sections.stream().anyMatch(s -> s.completed.length != 0);
            if (a.stage == PLANNED && any || a.stage == FINISHED && !a.complete())
                return DataResult.error(() -> "Source stage contradicts cell receipts");
            return DataResult.success(a);
        });

        Active { sections = List.copyOf(sections); }
        boolean complete() { return sections.stream().allMatch(Section::complete); }
    }

    public static final PreparedSourceProgress EMPTY = new PreparedSourceProgress(null);
    public static final Codec<PreparedSourceProgress> CODEC = Active.CODEC.optionalFieldOf("active").codec()
            .xmap(a -> a.map(PreparedSourceProgress::new).orElse(EMPTY), p -> Optional.ofNullable(p.active));

    private final Active active;

    private PreparedSourceProgress(Active active) { this.active = active; }

    public boolean isEmpty() { return active == null; }
    boolean begun() { return active != null && active.stage >= BEGUN; }
    boolean finished() { return active != null && active.stage == FINISHED; }
    boolean cellsComplete() { return active != null && active.complete(); }
    Identity identity() { return active == null ? null : active.identity; }

    static PreparedSourceProgress get(LevelChunk chunk) {
        return chunk.hasData(ModAttachments.PREPARED_SOURCE_PROGRESS) ? chunk.getData(ModAttachments.PREPARED_SOURCE_PROGRESS) : EMPTY;
    }

    BitSet pending(int sectionY) {
        Section section = section(sectionY);
        return section == null ? new BitSet() : section.pending();
    }

    /** Install the complete source footprint before mutation; never replace an unfinished identity. */
    static PreparedSourceProgress install(LevelChunk chunk, Identity id, Map<Integer, BitSet> planned) {
        checkThread(chunk);
        if (planned.size() > MAX_SECTIONS) throw new IllegalArgumentException("Source plan exceeds section bound");
        var sections = new ArrayList<Section>();
        planned.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            checkSection(chunk, entry.getKey());
            checkMask(entry.getValue());
            if (!entry.getValue().isEmpty()) sections.add(new Section(entry.getKey(), entry.getValue().toLongArray(), new long[0]));
        });
        PreparedSourceProgress old = get(chunk);
        if (!old.isEmpty()) {
            old.require(id);
            if (old.active.sections.size() != sections.size()) throw new IllegalStateException("Source footprint changed without repair");
            for (int i = 0; i < sections.size(); i++) {
                Section a = old.active.sections.get(i), b = sections.get(i);
                if (a.y != b.y || !BitSet.valueOf(a.planned).equals(BitSet.valueOf(b.planned)))
                    throw new IllegalStateException("Source footprint changed without repair");
            }
            return old;
        }
        if (chunk.getData(ModAttachments.CHUNK_SCAR) >= id.event) throw new IllegalStateException("Source event already scarred");
        return save(chunk, new Active(id, sections, PLANNED));
    }

    /** Call only after original inputs are durable and beginWave's ancillary work succeeded. */
    static PreparedSourceProgress begun(LevelChunk chunk, Identity id) {
        checkThread(chunk);
        PreparedSourceProgress old = get(chunk).require(id);
        return old.begun() ? old : save(chunk, new Active(id, old.active.sections, BEGUN));
    }

    /** The mask contains confirmed source results only, never attempted, changed-only, denied or stale cells. */
    static PreparedSourceProgress recordApplied(LevelChunk chunk, Identity id, int sectionY, BitSet applied) {
        checkThread(chunk);
        checkSection(chunk, sectionY);
        checkMask(applied);
        PreparedSourceProgress old = get(chunk).require(id);
        if (old.active.stage != BEGUN) throw new IllegalStateException("Source writes require a begun, unfinished plan");
        Section section = old.section(sectionY);
        if (section == null) throw new IllegalArgumentException("Source section is not planned");
        BitSet extra = (BitSet) applied.clone();
        extra.andNot(BitSet.valueOf(section.planned));
        if (!extra.isEmpty()) throw new IllegalArgumentException("Receipt contains an unplanned source cell");
        BitSet completed = BitSet.valueOf(section.completed);
        int before = completed.cardinality();
        completed.or(applied);
        return before == completed.cardinality() ? old : replace(chunk, old,
                new Section(sectionY, section.planned, completed.toLongArray()));
    }

    /** Checkpoint the writer's exact applied receipts, including those available in an exceptional finally block. */
    static PreparedSourceProgress record(LevelChunk chunk, Identity id, int sectionY,
                                         List<PreparedSectionWriter.CellChange> changes, PreparedSectionWriter.Receipt receipt) {
        if (changes.size() > 4096) throw new IllegalArgumentException("Source attempt exceeds section bound");
        BitSet applied = new BitSet(4096);
        for (int i = 0; i < changes.size(); i++) {
            BlockPos at = changes.get(i).pos();
            if (at.getX() >> 4 != chunk.getPos().x || at.getZ() >> 4 != chunk.getPos().z || at.getY() >> 4 != sectionY)
                throw new IllegalArgumentException("Receipt belongs to another source section");
            if (receipt.applied(i) && !receipt.stale(i)) applied.set(local(at));
        }
        return recordApplied(chunk, id, sectionY, applied);
    }

    /**
     * Caller has published the revised section plan and cancelled its old, unremoved material rows.
     * Only remaining work changes; completed cells and other sections keep their receipts.
     */
    static PreparedSourceProgress replacePending(LevelChunk chunk, Identity id, int sectionY, BitSet remaining) {
        checkThread(chunk);
        checkSection(chunk, sectionY);
        checkMask(remaining);
        PreparedSourceProgress old = get(chunk).require(id);
        if (old.finished()) throw new IllegalStateException("Finished source cannot be repaired");
        Section section = old.section(sectionY);
        BitSet completed = section == null ? new BitSet() : BitSet.valueOf(section.completed);
        if (completed.intersects(remaining)) throw new IllegalArgumentException("Repair replays a completed source cell");
        BitSet planned = (BitSet) completed.clone();
        planned.or(remaining);
        return replace(chunk, old, new Section(sectionY, planned.toLongArray(), completed.toLongArray()));
    }

    /** Call after finishWave succeeded. Stale or unattempted cells are still pending until explicit repair. */
    static PreparedSourceProgress finish(LevelChunk chunk, Identity id) {
        checkThread(chunk);
        PreparedSourceProgress old = get(chunk).require(id);
        if (!old.begun() || !old.cellsComplete()) throw new IllegalStateException("Source plan still has pending work");
        return old.finished() ? old : save(chunk, new Active(id, old.active.sections, FINISHED));
    }

    /** The completed scar watermark must replace this receipt before its section masks can be retired. */
    static void retire(LevelChunk chunk, Identity id) {
        checkThread(chunk);
        PreparedSourceProgress old = get(chunk).require(id);
        if (!old.finished() || chunk.getData(ModAttachments.CHUNK_SCAR) < id.event)
            throw new IllegalStateException("Source progress has no completed scar receipt");
        chunk.removeData(ModAttachments.PREPARED_SOURCE_PROGRESS);
        chunk.setUnsaved(true);
    }

    static int local(BlockPos at) { return (at.getY() & 15) << 8 | (at.getZ() & 15) << 4 | at.getX() & 15; }

    private Section section(int y) {
        if (active != null) for (Section section : active.sections) if (section.y == y) return section;
        return null;
    }

    private PreparedSourceProgress require(Identity id) {
        if (active == null || !active.identity.equals(id)) throw new IllegalStateException("Prepared source identity mismatch");
        return this;
    }

    private static PreparedSourceProgress replace(LevelChunk chunk, PreparedSourceProgress old, Section with) {
        var sections = new ArrayList<>(old.active.sections);
        sections.removeIf(s -> s.y == with.y);
        if (with.planned.length != 0) sections.add(with);
        sections.sort(java.util.Comparator.comparingInt(Section::y));
        if (sections.size() > MAX_SECTIONS) throw new IllegalArgumentException("Source plan exceeds section bound");
        return save(chunk, new Active(old.active.identity, sections, old.active.stage));
    }

    private static PreparedSourceProgress save(LevelChunk chunk, Active active) {
        PreparedSourceProgress result = new PreparedSourceProgress(active);
        chunk.setData(ModAttachments.PREPARED_SOURCE_PROGRESS, result);
        chunk.setUnsaved(true);
        return result;
    }

    private static void checkMask(BitSet bits) {
        if (bits.length() > 4096) throw new IllegalArgumentException("Source section mask exceeds 4096 cells");
    }

    private static void checkSection(LevelChunk chunk, int y) {
        if (y < chunk.getMinSection() || y >= chunk.getMaxSection()) throw new IllegalArgumentException("Source section outside chunk height");
    }

    private static void checkThread(LevelChunk chunk) {
        if (!(chunk.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread())
            throw new IllegalStateException("Source progress mutations require the server thread");
    }
}
