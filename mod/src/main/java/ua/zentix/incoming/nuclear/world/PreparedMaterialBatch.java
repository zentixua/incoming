package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Destination-owned, finite material operations. This new execution path is not connected to
 * strikes yet. A batch has no tick loop, world reference, ticket, trajectory or support graph.
 * Its detached snapshot can be encoded by the existing region writer while the next revision
 * continues to change. Cross-chunk save ordering belongs to the caller, not this codec.
 */
final class PreparedMaterialBatch {
    static final int MAX_MOVES = 65_536;
    enum Phase { PLANNED, REMOVED, PLACED, SOURCE_CHANGED, DESTINATION_CHANGED }

    /** Absolute game ticks; before and placed differ for rotated logs and drained waterlogging. */
    record Move(BlockPos source, BlockPos destination, BlockState before, BlockState placed, BlockState destinationBefore,
                long departureTick, long landingTick, double volume, double mass) {
        Move(BlockPos source, BlockPos destination, BlockState before, BlockState placed,
             long departureTick, long landingTick, double volume, double mass) {
            this(source, destination, before, placed, Blocks.AIR.defaultBlockState(), departureTick, landingTick, volume, mass);
        }

        Move {
            source = source.immutable();
            destination = destination.immutable();
            if (before == null || placed == null || destinationBefore == null || before.isAir() || placed.isAir()
                    || departureTick < 0 || landingTick < departureTick
                    || !(volume > 0) || !(mass > 0) || !Double.isFinite(volume + mass))
                throw new IllegalArgumentException("Invalid prepared material move");
        }
    }

    private final int event;
    private final ChunkPos owner;
    private final List<Move> moves;
    private final byte[] phases;
    // Tags are privately copied on entry, never mutated, and copied on outward access.
    // A snapshot shares these immutable values, not this mutable array.
    private final CompoundTag[] cargo;
    private long revision;

    PreparedMaterialBatch(int event, ChunkPos owner, List<Move> moves) {
        if (event < 0 || moves.isEmpty() || moves.size() > MAX_MOVES)
            throw new IllegalArgumentException("Invalid prepared material batch");
        this.event = event;
        this.owner = owner;
        this.moves = List.copyOf(moves);
        phases = new byte[moves.size()];
        cargo = new CompoundTag[moves.size()];
        var identities = new HashSet<Long>();
        for (Move move : moves) {
            if (!new ChunkPos(move.destination).equals(owner) || !identities.add(move.source.asLong()))
                throw new IllegalArgumentException("Material batch has a foreign destination or duplicate source");
        }
    }

    int event() { return event; }
    ChunkPos owner() { return owner; }
    int size() { return moves.size(); }
    Move move(int index) { return moves.get(index); }
    Phase phase(int index) { return Phase.values()[phases[index]]; }
    long revision() { return revision; }
    CompoundTag cargo(int index) { return cargo[index] == null ? null : cargo[index].copy(); }

    /** Stage live contents immediately before removal. Inventory edits during preparation survive. */
    void capture(int index, CompoundTag contents) {
        require(index, Phase.PLANNED);
        cargo[index] = contents == null ? null : contents.copy();
        revision++;
    }

    /** Call only for a confirmed source mutation, including a partially failed bulk write. */
    void removed(int index) {
        require(index, Phase.PLANNED);
        phases[index] = (byte) Phase.REMOVED.ordinal();
        revision++;
    }

    /** A local source edit invalidates this transfer only; the material still belongs to the world. */
    void sourceChanged(int index) {
        require(index, Phase.PLANNED);
        phases[index] = (byte) Phase.SOURCE_CHANGED.ordinal();
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
        cargo[index] = null;
        revision++;
    }

    boolean ownsMaterial(int index) {
        Phase phase = phase(index);
        return phase == Phase.REMOVED || phase == Phase.DESTINATION_CHANGED;
    }

    boolean finished() {
        for (int i = 0; i < size(); i++)
            if (phase(i) != Phase.PLACED && phase(i) != Phase.SOURCE_CHANGED) return false;
        return true;
    }

    private void require(int index, Phase expected) {
        if (phase(index) != expected) throw new IllegalStateException("Unexpected material phase " + phase(index));
    }

    Snapshot snapshot() { return new Snapshot(event, owner, moves, phases.clone(), cargo.clone(), revision); }

    /** No reference to the mutable batch survives in the encoder. */
    static final class Snapshot {
        private final int event;
        private final ChunkPos owner;
        private final List<Move> moves;
        private final byte[] phases;
        private final CompoundTag[] cargo;
        private final long revision;

        private Snapshot(int event, ChunkPos owner, List<Move> moves, byte[] phases, CompoundTag[] cargo, long revision) {
            this.event = event; this.owner = owner; this.moves = moves;
            this.phases = phases; this.cargo = cargo; this.revision = revision;
        }

        CompoundTag encode() {
            var tag = new CompoundTag();
            tag.putInt("version", 2);
            tag.putInt("event", event);
            tag.putLong("chunk", owner.toLong());
            tag.putLong("revision", revision);
            var palette = new RuinPalette();
            long[] rows = new long[moves.size() * 6];
            int[] states = new int[moves.size() * 3];
            var contents = new ListTag();
            for (int i = 0; i < moves.size(); i++) {
                Move move = moves.get(i);
                int p = i * 6;
                rows[p] = move.source.asLong(); rows[p + 1] = move.destination.asLong();
                rows[p + 2] = move.departureTick; rows[p + 3] = move.landingTick;
                rows[p + 4] = Double.doubleToLongBits(move.volume);
                rows[p + 5] = Double.doubleToLongBits(move.mass);
                states[i * 3] = palette.index(move.before);
                states[i * 3 + 1] = palette.index(move.placed);
                states[i * 3 + 2] = palette.index(move.destinationBefore);
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
            tag.put("palette", palette.save());
            tag.put("cargo", contents);
            return tag;
        }
    }

    static PreparedMaterialBatch decode(CompoundTag tag) {
        requireType(tag, "version", Tag.TAG_INT);
        requireType(tag, "event", Tag.TAG_INT);
        requireType(tag, "chunk", Tag.TAG_LONG);
        requireType(tag, "revision", Tag.TAG_LONG);
        requireType(tag, "rows", Tag.TAG_LONG_ARRAY);
        requireType(tag, "states", Tag.TAG_INT_ARRAY);
        requireType(tag, "phases", Tag.TAG_BYTE_ARRAY);
        requireType(tag, "palette", Tag.TAG_LIST);
        requireType(tag, "cargo", Tag.TAG_LIST);
        long[] rows = tag.getLongArray("rows");
        int count = rows.length / 6;
        int[] states = tag.getIntArray("states");
        byte[] phases = tag.getByteArray("phases");
        var paletteTag = (ListTag) tag.get("palette");
        var cargoTag = (ListTag) tag.get("cargo");
        if (tag.getInt("version") != 2 || rows.length % 6 != 0 || count < 1 || count > MAX_MOVES
                || states.length != count * 3 || phases.length != count || tag.getLong("revision") < 0
                || paletteTag == null || paletteTag.getElementType() != Tag.TAG_COMPOUND
                || paletteTag.isEmpty() || paletteTag.size() > count * 3
                || cargoTag == null || !cargoTag.isEmpty() && cargoTag.getElementType() != Tag.TAG_COMPOUND
                || cargoTag.size() > count)
            throw new IllegalArgumentException("Invalid prepared material page dimensions");
        var palette = RuinPalette.load(paletteTag);
        var moves = new ArrayList<Move>(count);
        for (int i = 0; i < count; i++) {
            int p = i * 6;
            if (phases[i] < 0 || phases[i] >= Phase.values().length)
                throw new IllegalArgumentException("Invalid prepared material phase");
            moves.add(new Move(BlockPos.of(rows[p]), BlockPos.of(rows[p + 1]), palette.state(states[i * 3]),
                    palette.state(states[i * 3 + 1]), palette.state(states[i * 3 + 2]), rows[p + 2], rows[p + 3],
                    Double.longBitsToDouble(rows[p + 4]), Double.longBitsToDouble(rows[p + 5])));
        }
        var batch = new PreparedMaterialBatch(tag.getInt("event"), new ChunkPos(tag.getLong("chunk")), moves);
        System.arraycopy(phases, 0, batch.phases, 0, count);
        batch.revision = tag.getLong("revision");
        for (Tag value : cargoTag) {
            var entry = (CompoundTag) value;
            requireType(entry, "index", Tag.TAG_INT);
            requireType(entry, "contents", Tag.TAG_COMPOUND);
            int index = entry.getInt("index");
            if (index < 0 || index >= count || batch.cargo[index] != null
                    || batch.phase(index) == Phase.PLACED || batch.phase(index) == Phase.SOURCE_CHANGED)
                throw new IllegalArgumentException("Invalid prepared material cargo owner");
            batch.cargo[index] = entry.getCompound("contents").copy();
        }
        return batch;
    }

    private static void requireType(CompoundTag tag, String name, int type) {
        if (!tag.contains(name, type)) throw new IllegalArgumentException("Invalid prepared material field " + name);
    }
}
