package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.util.Arrays;

/** First removed state/event, shared by all unfinished support searches. A section has at most
 * 4096 entries: adding a city block never doubles a world-sized table of per-block objects.
 * Registry IDs are process-local only; the normal ledger stream writes named state palettes. */
final class RuinOriginals {
    private static final float LOAD = 0.75f;
    private static final int ROW = 64;
    private static final class Page extends Int2LongOpenHashMap {
        private static final long serialVersionUID = 1L;
        int imported = -1;
        Page(int expected) { super(expected, LOAD); }
        long bytes() { return pageBytes(n); }
        long growth(int additions) {
            int next = Math.max(n, capacity(Math.min(4096, size() + additions)));
            // The previous arrays stay live while rehash installs the new arrays.
            return next > n ? pageBytes(next) : 0;
        }
    }
    private static final class Pages extends Long2ObjectOpenHashMap<Page> {
        private static final long serialVersionUID = 1L;
        long bytes() { return 192L + 16L * (n + 1); }
        long growth(int additions) {
            int next = Math.max(n, capacity(size() + additions));
            return next > n ? 192L + 16L * (next + 1) : 0;
        }
    }

    private final Pages pages = new Pages();
    // A RuinPlan's positions are in one chunk, with at most 256 representable vertical sections.
    // Keep this admission scratch owned, instead of allocating a map before obtaining a permit.
    private final int[] additions = new int[256];
    private final long[] sections = new long[256];
    private long pageBytes;
    private int size;

    private static int capacity(int entries) { return HashCommon.arraySize(Math.max(2, entries), LOAD); }
    private static long pageBytes(int capacity) { return 192L + 12L * (capacity + 1); }
    static long importBytes(int entries) {
        if (entries < 1 || entries > 4096) throw new IllegalArgumentException("Invalid original section size");
        // Page arrays plus its share of the outer index, including index rehash overlap.
        return pageBytes(capacity(entries)) + 512;
    }

    long bytes() { return 128L + 64 + 12L * additions.length + pages.bytes() + pageBytes; }
    int size() { return size; }
    boolean isEmpty() { return size == 0; }
    boolean containsKey(long position) { return get(position) != 0; }

    private static long section(long position) {
        return SectionPos.asLong(BlockPos.getX(position) >> 4, BlockPos.getY(position) >> 4, BlockPos.getZ(position) >> 4);
    }
    private static int local(long position) {
        return (BlockPos.getY(position) & 15) << 8 | (BlockPos.getZ(position) & 15) << 4 | BlockPos.getX(position) & 15;
    }
    private static long position(long section, int local) {
        return BlockPos.asLong((SectionPos.x(section) << 4) + (local & 15),
                (SectionPos.y(section) << 4) + (local >>> 8), (SectionPos.z(section) << 4) + ((local >>> 4) & 15));
    }
    private long get(long position) {
        Page page = pages.get(section(position));
        return page == null ? 0 : page.get(local(position));
    }
    BlockState state(long position) {
        long value = get(position);
        return value == 0 ? null : stateOf(value);
    }
    private static BlockState stateOf(long value) { return Block.stateById((int) value - 1); }
    private static int eventOf(long value) { return (int) (value >>> 32); }
    private static long value(BlockState state, int event) {
        return (long) event << 32 | Integer.toUnsignedLong(Block.getId(state) + 1);
    }

    /** Existing first-removal provenance wins; legacy duplicates use the earliest event ID. */
    void put(long position, BlockState state, int event, boolean earliest) {
        long key = section(position);
        Page page = pages.get(key);
        if (page == null) { page = new Page(2); pages.put(key, page); pageBytes += page.bytes(); }
        int at = local(position);
        long old = page.get(at);
        if (old != 0 && (!earliest || eventOf(old) <= event)) return;
        long before = page.bytes();
        page.put(at, value(state, event));
        pageBytes += page.bytes() - before;
        if (old == 0) size++;
    }

    /** Complete incremental ownership for the known source positions, including rehash scratch. */
    long growthBytes(long[] positions) {
        if (positions.length == 0) return 0;
        Arrays.fill(additions, 0);
        int count = 0;
        for (long position : positions) {
            if (containsKey(position)) continue;
            long key = section(position);
            int at = 0;
            while (at < count && sections[at] != key) at++;
            if (at == count) {
                if (count == sections.length) throw new IllegalArgumentException("Material plan exceeds its section bound");
                sections[count++] = key;
            }
            additions[at]++;
        }
        int newPages = 0;
        long growth = 0;
        for (int i = 0; i < count; i++) {
            Page page = pages.get(sections[i]);
            if (page == null) {
                newPages++;
                // A fresh page may grow through several tables before the batch ends.
                growth += 2 * pageBytes(capacity(Math.min(4096, additions[i])));
            } else growth += page.growth(additions[i]);
        }
        return growth + pages.growth(newPages);
    }

    long growthBytes(long position) {
        if (containsKey(position)) return 0;
        Page page = pages.get(section(position));
        return page == null ? pageBytes(capacity(2)) + pages.growth(1) : page.growth(1);
    }

    void clear() { pages.clear(); pages.trim(); size = 0; pageBytes = 0; }

    void loaded() {
        for (Page page : pages.values()) if (page.imported >= 0) {
            if (page.size() != page.imported) throw new IllegalArgumentException("Incomplete original section");
            page.imported = -1;
        }
    }

    @FunctionalInterface interface Visitor { void accept(long position, BlockState state, int event); }
    void forEach(Visitor visitor) {
        for (var entry : pages.long2ObjectEntrySet()) {
            for (var cell : entry.getValue().int2LongEntrySet())
                visitor.accept(position(entry.getLongKey(), cell.getIntKey()), stateOf(cell.getLongValue()), eventOf(cell.getLongValue()));
        }
    }

    /** Small independent rows keep the existing bounded streaming loader and frozen-save owner.
     * The first row pays and preallocates its whole section; later rows add no backing arrays. */
    void write(LedgerWriter writer) throws IOException {
        int rows = 0;
        for (Page page : pages.values()) rows += (page.size() + ROW - 1) / ROW;
        writer.list("original_sections", rows);
        for (var entry : pages.long2ObjectEntrySet()) {
            Page page = entry.getValue();
            var cells = page.int2LongEntrySet().fastIterator();
            boolean first = true;
            while (cells.hasNext()) {
                var palette = new RuinPalette();
                long[] packed = new long[Math.min(ROW, first ? page.size() : ROW)];
                int count = 0;
                while (count < packed.length && cells.hasNext()) {
                    var cell = cells.next();
                    int state = palette.index(stateOf(cell.getLongValue()));
                    packed[count++] = (long) eventOf(cell.getLongValue()) << 24 | (long) state << 12 | cell.getIntKey();
                }
                CompoundTag row = new CompoundTag();
                row.putLong("section", entry.getLongKey());
                if (first) row.putInt("original_entries", page.size());
                row.put("palette", palette.save());
                row.putLongArray("cells", count == packed.length ? packed : Arrays.copyOf(packed, count));
                writer.row(row);
                first = false;
            }
        }
    }

    void read(CompoundTag row) {
        long key = row.getLong("section");
        if (SectionPos.y(key) < -128 || SectionPos.y(key) > 127)
            throw new IllegalArgumentException("Original section exceeds block-position height");
        if (row.contains("original_entries")) {
            int count = row.getInt("original_entries");
            importBytes(count); // Validate before allocation.
            if (pages.containsKey(key)) throw new IllegalArgumentException("Duplicate original section header");
            Page page = new Page(count);
            page.imported = count;
            pages.put(key, page); pageBytes += page.bytes();
        }
        Page page = pages.get(key);
        if (page == null) throw new IllegalArgumentException("Original section has no header");
        var palette = RuinPalette.load(row.getList("palette", Tag.TAG_COMPOUND));
        long[] cells = row.getLongArray("cells");
        if (cells.length == 0 || cells.length > ROW) throw new IllegalArgumentException("Invalid original section row");
        for (long cell : cells) {
            int at = (int) cell & 4095, id = (int) (cell >>> 12) & 4095, event = (int) (cell >>> 24);
            if (page.containsKey(at)) throw new IllegalArgumentException("Duplicate original section cell");
            if (page.imported < 0 || page.size() >= page.imported)
                throw new IllegalArgumentException("Original section exceeds its declared size");
            long before = page.bytes();
            page.put(at, value(palette.state(id), event));
            pageBytes += page.bytes() - before;
            size++;
        }
    }
}
