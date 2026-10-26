package ua.zentix.incoming.nuclear.flash;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/**
 * Flash traces on one chunk's faces (chunk attachment, saved with it): charred faces ({@link FlashChar}) and
 * silhouettes, faces where a body blocked some light ({@link FlashShadows}): shadowed portions stayed light.
 * One layer per detonation: client shows layer when its clock reaches flash (Flashback replay sought backward has
 * no scorch).
 * <p>
 * Compact data: face mark is one {@code int} ({@link FlashChar#mark}); silhouette adds 16×16 2-bit cells (shadow
 * 0, ⅓, ⅔, full), 8 {@code long}. At most {@link #MAX_CHARS} charred faces and {@link #MAX_SHADOWS} silhouette
 * faces per chunk across all layers; weakest evicted beyond limit. City chunk has hundreds, blast-facing wall up to a couple
 * thousand faces: worst case 16 KB and 6 KB. At most {@link #MAX_LAYERS} layers: each strike replaces chunk ruins
 * in its radius; without limit, repeated strikes would accumulate layers and client could not accept packet.
 */
public record FlashMarks(List<Layer> layers) {
    /** Maximum charred faces per chunk. */
    public static final int MAX_CHARS = 4096;
    /** Maximum silhouette faces per chunk. */
    public static final int MAX_SHADOWS = 96;
    /** Maximum layers (detonations) per chunk: oldest evicted beyond limit. Packet decoder accepts no more. */
    public static final int MAX_LAYERS = 16;
    /** Silhouette cells per face side (like block-texture pixels) and {@code long} per mask (2 bits per cell). */
    public static final int CELLS = 16, MASK_LONGS = CELLS * CELLS * 2 / 64;

    public static final FlashMarks EMPTY = new FlashMarks(List.of());

    /**
     * Traces of one detonation.
     *
     * @param detonation detonation ID
     * @param time       flash game time
     * @param chars      charred faces ({@link FlashChar#mark}), ascending key order
     * @param shadows    silhouette faces (marks with own scorch tier)
     * @param masks      silhouette masks: {@link #MASK_LONGS} per face, cell {@code (u, v)} at bits {@code 2(v·16 + u)}
     */
    public record Layer(int detonation, long time, int[] chars, int[] shadows, long[] masks) {
        static final Codec<Layer> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("detonation").forGetter(Layer::detonation),
                Codec.LONG.fieldOf("time").forGetter(Layer::time),
                Codec.INT_STREAM.xmap(IntStream::toArray, IntStream::of).fieldOf("chars").forGetter(Layer::chars),
                Codec.INT_STREAM.xmap(IntStream::toArray, IntStream::of).fieldOf("shadows").forGetter(Layer::shadows),
                Codec.LONG_STREAM.xmap(LongStream::toArray, LongStream::of).fieldOf("masks").forGetter(Layer::masks)
        ).apply(i, Layer::new));

        boolean isEmpty() {
            return chars.length == 0 && shadows.length == 0;
        }

        /** Layer without {@code stale} marks; same object if nothing removed. */
        Layer without(IntPredicate stale) {
            int goneChars = 0, goneShadows = 0;
            for (int c : chars) if (stale.test(c)) goneChars++;
            for (int c : shadows) if (stale.test(c)) goneShadows++;
            if (goneChars == 0 && goneShadows == 0) return this;
            int[] keptChars = new int[chars.length - goneChars], keptShadows = new int[shadows.length - goneShadows];
            long[] keptMasks = new long[keptShadows.length * MASK_LONGS];
            int n = 0;
            for (int c : chars) if (!stale.test(c)) keptChars[n++] = c;
            n = 0;
            for (int k = 0; k < shadows.length; k++) {
                if (stale.test(shadows[k])) continue;
                System.arraycopy(masks, k * MASK_LONGS, keptMasks, n * MASK_LONGS, MASK_LONGS);
                keptShadows[n++] = shadows[k];
            }
            return new Layer(detonation, time, keptChars, keptShadows, keptMasks);
        }

        /** Shadow of silhouette cell {@code k}: 0 lit, 3 fully shadowed. */
        public int shade(int k, int u, int v) {
            int bit = 2 * (v * CELLS + u);
            return (int) (masks[k * MASK_LONGS + (bit >> 6)] >>> (bit & 63)) & 3;
        }
    }

    /** Saved with chunk: layers in detonation order and within limits (older-version saves could exceed them). */
    public static final Codec<FlashMarks> CODEC = Layer.CODEC.listOf().xmap(FlashMarks::of, FlashMarks::layers);

    public static final StreamCodec<ByteBuf, FlashMarks> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public FlashMarks decode(ByteBuf b) {
            int n = ByteBufCodecs.VAR_INT.decode(b);
            if (n < 0 || n > MAX_LAYERS) throw new IllegalArgumentException("flash marks: " + n + " layers");
            List<Layer> layers = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int det = ByteBufCodecs.VAR_INT.decode(b);
                long time = b.readLong();
                int[] chars = ints(b, MAX_CHARS);
                int[] shadows = ints(b, MAX_SHADOWS);
                long[] masks = new long[shadows.length * MASK_LONGS];
                for (int k = 0; k < masks.length; k++) masks[k] = b.readLong();
                layers.add(new Layer(det, time, chars, shadows, masks));
            }
            return new FlashMarks(List.copyOf(layers));
        }

        @Override
        public void encode(ByteBuf b, FlashMarks m) {
            ByteBufCodecs.VAR_INT.encode(b, m.layers.size());
            for (Layer l : m.layers) {
                ByteBufCodecs.VAR_INT.encode(b, l.detonation);
                b.writeLong(l.time);
                ByteBufCodecs.VAR_INT.encode(b, l.chars.length);
                for (int c : l.chars) b.writeInt(c);
                ByteBufCodecs.VAR_INT.encode(b, l.shadows.length);
                for (int c : l.shadows) b.writeInt(c);
                for (long w : l.masks) b.writeLong(w);
            }
        }

        private static int[] ints(ByteBuf b, int max) {
            int n = ByteBufCodecs.VAR_INT.decode(b);
            if (n < 0 || n > max) throw new IllegalArgumentException("flash marks: " + n + " entries");
            int[] out = new int[n];
            for (int k = 0; k < n; k++) out[k] = b.readInt();
            return out;
        }
    };

    /** For {@link FriendlyByteBuf} packets. */
    public static final StreamCodec<FriendlyByteBuf, FlashMarks> FRIENDLY = STREAM_CODEC.cast();

    public boolean isEmpty() {
        return layers.isEmpty();
    }

    /** Face count across all layers (scorch and silhouettes). */
    public int size() {
        int n = 0;
        for (Layer l : layers) n += l.chars.length + l.shadows.length;
        return n;
    }

    /**
     * Charred faces of {@code detonation} replace its previous faces (chunk ruins rebuilt); layer silhouettes
     * remain.
     */
    public FlashMarks withChars(int detonation, long time, int[] chars) {
        Layer old = layer(detonation);
        return with(new Layer(detonation, time, chars, old == null ? new int[0] : old.shadows, old == null ? new long[0] : old.masks));
    }

    /**
     * Silhouettes of {@code detonation} added to previous ones; one entry per face (two bodies before face, including
     * within one {@code shadows} batch: cell shadow is maximum of their shadows).
     */
    public FlashMarks withShadows(int detonation, long time, int[] shadows, long[] masks) {
        Layer old = layer(detonation);
        int[] oldShadows = old == null ? NO_MARKS : old.shadows;
        Int2IntOpenHashMap at = new Int2IntOpenHashMap();
        at.defaultReturnValue(-1);
        IntArrayList keys = new IntArrayList(oldShadows);
        LongArrayList all = new LongArrayList(old == null ? NO_MASKS : old.masks);
        for (int k = 0; k < oldShadows.length; k++) at.put(oldShadows[k] & FlashChar.KEY_MASK, k);
        for (int k = 0; k < shadows.length; k++) {
            int was = at.get(shadows[k] & FlashChar.KEY_MASK);
            if (was < 0) {
                at.put(shadows[k] & FlashChar.KEY_MASK, keys.size());
                keys.add(shadows[k]);
                for (int w = 0; w < MASK_LONGS; w++) all.add(masks[k * MASK_LONGS + w]);
                continue;
            }
            // two bodies before one face: shadow wherever either casts shadow
            for (int w = 0; w < MASK_LONGS; w++) all.set(was * MASK_LONGS + w, maxShade(all.getLong(was * MASK_LONGS + w), masks[k * MASK_LONGS + w]));
        }
        return with(new Layer(detonation, time, old == null ? NO_MARKS : old.chars, keys.toIntArray(), all.toLongArray()));
    }

    private static final int[] NO_MARKS = new int[0];
    private static final long[] NO_MASKS = new long[0];

    /** Per-cell maximum of two 2-bit masks. */
    static long maxShade(long a, long b) {
        long out = 0;
        for (int s = 0; s < 64; s += 2) out |= Math.max(a >>> s & 3, b >>> s & 3) << s;
        return out;
    }

    private Layer layer(int detonation) {
        for (Layer l : layers) if (l.detonation == detonation) return l;
        return null;
    }

    /** Detonation layer replaces its previous layer; limits across all layers ({@link #of}). */
    private FlashMarks with(Layer layer) {
        List<Layer> out = new ArrayList<>(layers.size() + 1);
        for (Layer l : layers) if (l.detonation != layer.detonation) out.add(l);
        out.add(layer);
        return of(out);
    }

    /**
     * Traces from layers: detonation order (IDs increase over time), at most {@link #MAX_LAYERS} newest layers,
     * face counts within limits (evict weakest, older layers first).
     */
    static FlashMarks of(List<Layer> layers) {
        List<Layer> out = new ArrayList<>(layers);
        out.sort(Comparator.comparingInt(Layer::detonation));
        if (out.size() > MAX_LAYERS) out.subList(0, out.size() - MAX_LAYERS).clear();
        return new FlashMarks(List.copyOf(capped(out)));
    }

    private static List<Layer> capped(List<Layer> layers) {
        int chars = 0, shadows = 0;
        for (Layer l : layers) {
            chars += l.chars.length;
            shadows += l.shadows.length;
        }
        if (chars <= MAX_CHARS && shadows <= MAX_SHADOWS) return layers;
        // above limit, evict old layers first: new detonation matters more
        List<Layer> out = new ArrayList<>(layers);
        for (int i = 0; i < out.size() && (chars > MAX_CHARS || shadows > MAX_SHADOWS); i++) {
            Layer l = out.get(i);
            int[] c = strongest(l.chars, Math.max(0, l.chars.length - Math.max(0, chars - MAX_CHARS)));
            chars -= l.chars.length - c.length;
            int keep = Math.max(0, l.shadows.length - Math.max(0, shadows - MAX_SHADOWS));
            int[] s = Arrays.copyOf(l.shadows, keep);
            long[] m = Arrays.copyOf(l.masks, keep * MASK_LONGS);
            shadows -= l.shadows.length - keep;
            out.set(i, new Layer(l.detonation, l.time, c, s, m));
        }
        out.removeIf(Layer::isEmpty);
        return out;
    }

    /** At most {@code max} darkest faces, ascending key order (same plan regardless of order). */
    public static int[] strongest(int[] marks, int max) {
        if (marks.length <= max) return marks;
        Integer[] boxed = new Integer[marks.length];
        for (int k = 0; k < marks.length; k++) boxed[k] = marks[k];
        Arrays.sort(boxed, (a, b) -> FlashChar.level(a) != FlashChar.level(b) ? Integer.compare(FlashChar.level(b), FlashChar.level(a))
                : Integer.compare(a & FlashChar.KEY_MASK, b & FlashChar.KEY_MASK));
        int[] out = new int[max];
        for (int k = 0; k < max; k++) out[k] = boxed[k];
        Arrays.sort(out);
        return out;
    }

    /**
     * Without faces whose blocks changed (ruins, player): no longer their block at position ({@link FlashChar#holds}). Same object
     * if nothing removed.
     */
    public FlashMarks pruned(LevelChunk chunk) {
        int cx = chunk.getPos().x, cz = chunk.getPos().z;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        return without(c -> !FlashChar.holds(chunk.getBlockState(m.set(cx << 4 | FlashChar.lx(c), FlashChar.y(c), cz << 4 | FlashChar.lz(c))), c));
    }

    /**
     * Without faces of chunk block {@code (lx, y, lz)} if it is no longer their block: {@code state} is current
     * state at position. Same object if nothing removed.
     */
    public FlashMarks prunedAt(int lx, int y, int lz, BlockState state) {
        int place = FlashChar.key(lx, y, lz, Direction.DOWN) & FlashChar.PLACE_MASK;
        return without(c -> (c & FlashChar.PLACE_MASK) == place && !FlashChar.holds(state, c));
    }

    /** Without {@code stale} marks; empty layers removed. Same object if nothing removed. */
    private FlashMarks without(IntPredicate stale) {
        List<Layer> out = null;
        for (int i = 0; i < layers.size(); i++) {
            Layer l = layers.get(i), kept = l.without(stale);
            if (kept != l && out == null) out = new ArrayList<>(layers.subList(0, i));
            if (out != null && !kept.isEmpty()) out.add(kept);
        }
        return out == null ? this : new FlashMarks(List.copyOf(out));
    }
}
