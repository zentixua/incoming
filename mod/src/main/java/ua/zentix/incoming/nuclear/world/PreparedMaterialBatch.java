package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Destination-owned, finite material operations. This new execution path is not connected to
 * strikes yet. A batch has no tick loop, world reference, ticket, trajectory or support graph.
 * Its detached snapshot can be encoded by the existing region writer while the next revision
 * continues to change. Cross-chunk save ordering belongs to the caller, not this codec.
 * The preparation UUID survives detonation unchanged; chronological event IDs do not own pages.
 */
final class PreparedMaterialBatch {
    // At most 4096 final coarse cells plus 4096 source-local dispersed receipts in one section.
    static final int MAX_MOVES = 8192;
    enum Phase { PLANNED, REMOVED, PLACED, SOURCE_CHANGED, DESTINATION_CHANGED, DISPERSED }
    private static final Phase[] PHASES = Phase.values();

    /** Tick offsets from actual detonation origin; consumers supply event age, never world game time. */
    record Move(BlockPos source, BlockPos destination, BlockState before, BlockState placed, BlockState destinationBefore,
                long departureTick, long landingTick, double volume, double mass, boolean dispersed, BlockState sourceAfter) {
        Move(BlockPos source, BlockPos destination, BlockState before, BlockState placed, BlockState destinationBefore,
             long departureTick, long landingTick, double volume, double mass, boolean dispersed) {
            this(source, destination, before, placed, destinationBefore, departureTick, landingTick, volume, mass,
                    dispersed, before.hasProperty(BlockStateProperties.WATERLOGGED) && before.getValue(BlockStateProperties.WATERLOGGED)
                            ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState());
        }

        Move(BlockPos source, BlockPos destination, BlockState before, BlockState placed,
             long departureTick, long landingTick, double volume, double mass) {
            this(source, destination, before, placed, Blocks.AIR.defaultBlockState(), departureTick, landingTick, volume, mass, false);
        }

        Move(BlockPos source, BlockPos destination, BlockState before, BlockState placed, BlockState destinationBefore,
             long departureTick, long landingTick, double volume, double mass) {
            this(source, destination, before, placed, destinationBefore, departureTick, landingTick, volume, mass, false);
        }

        Move {
            source = source.immutable();
            destination = destination.immutable();
            if (before == null || placed == null || destinationBefore == null || sourceAfter == null
                    || before == sourceAfter || before.isAir() || placed.isAir()
                    || !BlockPos.of(source.asLong()).equals(source) || !BlockPos.of(destination.asLong()).equals(destination)
                    || departureTick < 0 || landingTick < departureTick || dispersed && before.hasBlockEntity()
                    || !(volume > 0) || !(mass > 0) || !Double.isFinite(volume + mass))
                throw new IllegalArgumentException("Invalid prepared material move");
        }
    }

    private final UUID plan;
    private final ChunkPos owner;
    private final int sectionY;
    private final List<Move> moves;
    private final byte[] phases;
    // Tags are privately copied on entry, never mutated, and copied on outward access.
    // A snapshot shares these immutable values, not this mutable array.
    private final CompoundTag[] cargo;
    private long revision;
    private long capacity = Long.MAX_VALUE;
    private long cargoBytes;

    PreparedMaterialBatch(UUID plan, ChunkPos owner, List<Move> moves) {
        if (plan == null || owner == null || moves.isEmpty() || moves.size() > MAX_MOVES)
            throw new IllegalArgumentException("Invalid prepared material batch");
        this.plan = plan;
        this.owner = owner;
        this.moves = new ArrayList<>(moves);
        sectionY = moves.getFirst().destination.getY() >> 4;
        phases = new byte[moves.size()];
        cargo = new CompoundTag[moves.size()];
        var identities = new HashSet<Long>();
        var destinations = new HashSet<Long>();
        for (Move move : moves) {
            if (!new ChunkPos(move.destination).equals(owner) || move.destination.getY() >> 4 != sectionY
                    || move.dispersed && !move.source.equals(move.destination)
                    || !identities.add(move.source.asLong()) || !move.dispersed && !destinations.add(move.destination.asLong()))
                throw new IllegalArgumentException("Material batch has a foreign section or duplicate source/destination");
        }
    }

    UUID plan() { return plan; }
    ChunkPos owner() { return owner; }
    int sectionY() { return sectionY; }
    int size() { return moves.size(); }
    Move move(int index) { return moves.get(index); }
    Phase phase(int index) { return PHASES[phases[index]]; }
    long revision() { return revision; }
    CompoundTag cargo(int index) { return cargo[index] == null ? null : cargo[index].copy(); }

    /** The wave's current fire/water decision is bound before removal, never after ownership moved. */
    void bindSourceAfter(int index, BlockState after) {
        require(index, Phase.PLANNED);
        Move move = moves.get(index);
        if (move.sourceAfter == after) return;
        moves.set(index, new Move(move.source, move.destination, move.before, move.placed, move.destinationBefore,
                move.departureTick, move.landingTick, move.volume, move.mass, move.dispersed, after));
        revision++;
    }

    /** Conservative admission estimate, not a measured heap claim. Registry states are shared. */
    long retainedBytes() { return 288L + 224L * size() + cargoBytes; }
    /** Also charges shared immutable data: safe initial bound for a queued save generation. */
    long snapshotBytes() { return retainedBytes(); }
    void setCapacity(long bytes) {
        if (bytes < retainedBytes()) throw new IllegalArgumentException("Material page exceeds its reserved capacity");
        capacity = bytes;
    }

    private static long cargoBytes(CompoundTag tag) { return tag == null ? 0 : 64L + 3L * tag.sizeInBytes(); }

    boolean tryCapture(int index, CompoundTag contents) {
        require(index, Phase.PLANNED);
        if (moves.get(index).dispersed && contents != null) throw new IllegalArgumentException("Dispersed material cannot own cargo");
        long next = cargoBytes - cargoBytes(cargo[index]) + cargoBytes(contents);
        if (288L + 224L * size() + next > capacity) return false;
        CompoundTag copy = contents == null ? null : contents.copy();
        cargo[index] = copy;
        cargoBytes = next;
        revision++;
        return true;
    }

    /** Stage live contents immediately before removal. Inventory edits during preparation survive. */
    void capture(int index, CompoundTag contents) {
        if (!tryCapture(index, contents)) throw new IllegalStateException("Material cargo exceeds its admitted page capacity");
    }

    /** Call only for a confirmed source mutation, including a partially failed bulk write. */
    void removed(int index) {
        require(index, Phase.PLANNED);
        phases[index] = (byte) (moves.get(index).dispersed ? Phase.DISPERSED : Phase.REMOVED).ordinal();
        revision++;
    }

    /** A local source edit invalidates this transfer only; the material still belongs to the world. */
    void sourceChanged(int index) {
        require(index, Phase.PLANNED);
        phases[index] = (byte) Phase.SOURCE_CHANGED.ordinal();
        cargoBytes -= cargoBytes(cargo[index]);
        cargo[index] = null;
        revision++;
    }

    /** Retain exact material/cargo for a local repair; never overwrite a player's destination edit. */
    void destinationChanged(int index) {
        require(index, Phase.REMOVED);
        phases[index] = (byte) Phase.DESTINATION_CHANGED.ordinal();
        revision++;
    }

    /** The caller has installed both the block and its contents before acknowledging placement. */
    void placed(int index) {
        require(index, Phase.REMOVED);
        phases[index] = (byte) Phase.PLACED.ordinal();
        cargoBytes -= cargoBytes(cargo[index]);
        cargo[index] = null;
        revision++;
    }

    boolean ownsMaterial(int index) {
        Phase phase = phase(index);
        return phase == Phase.REMOVED || phase == Phase.DESTINATION_CHANGED;
    }

    boolean finished() {
        for (int i = 0; i < size(); i++)
            if (phase(i) != Phase.PLACED && phase(i) != Phase.SOURCE_CHANGED && phase(i) != Phase.DISPERSED) return false;
        return true;
    }

    private void require(int index, Phase expected) {
        if (phase(index) != expected) throw new IllegalStateException("Unexpected material phase " + phase(index));
    }

    Snapshot snapshot() { return new Snapshot(plan, owner, List.copyOf(moves), phases.clone(), cargo.clone(), revision); }

    /** No reference to the mutable batch survives in the encoder. */
    static final class Snapshot {
        private final UUID plan;
        private final ChunkPos owner;
        private final List<Move> moves;
        private final byte[] phases;
        private final CompoundTag[] cargo;
        private final long revision;

        private Snapshot(UUID plan, ChunkPos owner, List<Move> moves, byte[] phases, CompoundTag[] cargo, long revision) {
            this.plan = plan; this.owner = owner; this.moves = moves;
            this.phases = phases; this.cargo = cargo; this.revision = revision;
        }

        CompoundTag encode() {
            var tag = new CompoundTag();
            tag.putInt("version", 5);
            tag.putUUID("plan", plan);
            tag.putLong("chunk", owner.toLong());
            tag.putLong("revision", revision);
            var palette = new RuinPalette();
            long[] rows = new long[moves.size() * 6];
            int[] states = new int[moves.size() * 4];
            var contents = new ListTag();
            byte[] dispersed = new byte[moves.size()];
            for (int i = 0; i < moves.size(); i++) {
                Move move = moves.get(i);
                int p = i * 6;
                rows[p] = move.source.asLong(); rows[p + 1] = move.destination.asLong();
                rows[p + 2] = move.departureTick; rows[p + 3] = move.landingTick;
                rows[p + 4] = Double.doubleToLongBits(move.volume);
                rows[p + 5] = Double.doubleToLongBits(move.mass);
                states[i * 4] = palette.index(move.before);
                states[i * 4 + 1] = palette.index(move.placed);
                states[i * 4 + 2] = palette.index(move.destinationBefore);
                states[i * 4 + 3] = palette.index(move.sourceAfter);
                dispersed[i] = (byte) (move.dispersed ? 1 : 0);
                if (cargo[i] != null) {
                    var entry = new CompoundTag();
                    entry.putInt("index", i);
                    entry.put("contents", cargo[i].copy());
                    contents.add(entry);
                }
            }
            tag.putLongArray("rows", rows);
            tag.putIntArray("states", states);
            tag.putByteArray("phases", phases.clone());
            tag.putByteArray("dispersed", dispersed);
            tag.put("palette", palette.save());
            tag.put("cargo", contents);
            return tag;
        }
    }

    static PreparedMaterialBatch decode(CompoundTag tag) {
        requireType(tag, "version", Tag.TAG_INT);
        requireType(tag, "plan", Tag.TAG_INT_ARRAY);
        requireType(tag, "chunk", Tag.TAG_LONG);
        requireType(tag, "revision", Tag.TAG_LONG);
        requireType(tag, "rows", Tag.TAG_LONG_ARRAY);
        requireType(tag, "states", Tag.TAG_INT_ARRAY);
        requireType(tag, "phases", Tag.TAG_BYTE_ARRAY);
        requireType(tag, "dispersed", Tag.TAG_BYTE_ARRAY);
        requireType(tag, "palette", Tag.TAG_LIST);
        requireType(tag, "cargo", Tag.TAG_LIST);
        long[] rows = tag.getLongArray("rows");
        int count = rows.length / 6;
        int[] states = tag.getIntArray("states");
        byte[] phases = tag.getByteArray("phases");
        byte[] dispersed = tag.getByteArray("dispersed");
        var paletteTag = (ListTag) tag.get("palette");
        var cargoTag = (ListTag) tag.get("cargo");
        if (tag.getInt("version") != 5 || tag.getIntArray("plan").length != 4 || tag.contains("event")
                || rows.length % 6 != 0 || count < 1 || count > MAX_MOVES
                || states.length != count * 4 || phases.length != count || tag.getLong("revision") < 0
                || dispersed.length != count
                || paletteTag == null || paletteTag.getElementType() != Tag.TAG_COMPOUND
                || paletteTag.isEmpty() || paletteTag.size() > count * 4
                || cargoTag == null || !cargoTag.isEmpty() && cargoTag.getElementType() != Tag.TAG_COMPOUND
                || cargoTag.size() > count)
            throw new IllegalArgumentException("Invalid prepared material page dimensions");
        var palette = RuinPalette.load(paletteTag);
        var moves = new ArrayList<Move>(count);
        for (int i = 0; i < count; i++) {
            int p = i * 6;
            if (phases[i] < 0 || phases[i] >= PHASES.length || dispersed[i] < 0 || dispersed[i] > 1
                    || phases[i] == Phase.DISPERSED.ordinal() && dispersed[i] == 0
                    || dispersed[i] == 1 && (phases[i] == Phase.REMOVED.ordinal() || phases[i] == Phase.PLACED.ordinal()
                    || phases[i] == Phase.DESTINATION_CHANGED.ordinal()))
                throw new IllegalArgumentException("Invalid prepared material phase");
            moves.add(new Move(BlockPos.of(rows[p]), BlockPos.of(rows[p + 1]), palette.state(states[i * 4]),
                    palette.state(states[i * 4 + 1]), palette.state(states[i * 4 + 2]), rows[p + 2], rows[p + 3],
                    Double.longBitsToDouble(rows[p + 4]), Double.longBitsToDouble(rows[p + 5]), dispersed[i] == 1,
                    palette.state(states[i * 4 + 3])));
        }
        var batch = new PreparedMaterialBatch(tag.getUUID("plan"), new ChunkPos(tag.getLong("chunk")), moves);
        System.arraycopy(phases, 0, batch.phases, 0, count);
        batch.revision = tag.getLong("revision");
        for (Tag value : cargoTag) {
            var entry = (CompoundTag) value;
            requireType(entry, "index", Tag.TAG_INT);
            requireType(entry, "contents", Tag.TAG_COMPOUND);
            int index = entry.getInt("index");
            if (index < 0 || index >= count || batch.cargo[index] != null
                    || batch.phase(index) == Phase.PLACED || batch.phase(index) == Phase.SOURCE_CHANGED || batch.move(index).dispersed)
                throw new IllegalArgumentException("Invalid prepared material cargo owner");
            batch.cargo[index] = entry.getCompound("contents").copy();
            batch.cargoBytes += cargoBytes(batch.cargo[index]);
        }
        return batch;
    }

    private static void requireType(CompoundTag tag, String name, int type) {
        if (!tag.contains(name, type)) throw new IllegalArgumentException("Invalid prepared material field " + name);
    }
}
