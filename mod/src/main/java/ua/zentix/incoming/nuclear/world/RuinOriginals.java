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
    private static final class Table extends Int2LongOpenHashMap {
        private static final long serialVersionUID = 1L;
        Table(int expected) { super(expected, LOAD); }
        long bytes() { return tableBytes(n); }
        long growth(int additions) {
            int next = Math.max(n, capacity(Math.min(4096, size() + additions)));
            // The previous arrays stay live while rehash installs the new arrays.
            return rehashBytes(n, next, 12);
        }
    }
    private static final class Page {
        private int first;
        private long value;
        private Table table;
        int imported = -1;
        Page(int expected) { if (expected > 1) table = new Table(expected); }
        int size() { return table != null ? table.size() : value == 0 ? 0 : 1; }
        long get(int key) { return table != null ? table.get(key) : first == key ? value : 0; }
        boolean containsKey(int key) { return get(key) != 0; }
        long bytes() { return 96L + (table == null ? 0 : table.bytes()); }
        long growth(int additions) {
            if (table != null) return table.growth(additions);
            int count = Math.min(4096, size() + additions);
            if (count <= 1) return 0;
            return (additions > 1 ? 2 : 1) * tableBytes(capacity(count));
        }
        void put(int key, long next) {
            if (table == null && (value == 0 || first == key)) { first = key; value = next; return; }
            if (table == null) {
                table = new Table(2); table.put(first, value); value = 0;
            }
            table.put(key, next);
        }
        it.unimi.dsi.fastutil.ints.IntIterator keys() {
            return table != null ? table.keySet().iterator() : value == 0
                    ? it.unimi.dsi.fastutil.ints.IntIterators.EMPTY_ITERATOR
                    : it.unimi.dsi.fastutil.ints.IntIterators.singleton(first);
        }
    }
    private static final class Pages extends Long2ObjectOpenHashMap<Page> {
        private static final long serialVersionUID = 1L;
        long bytes() { return 192L + 16L * (n + 1); }
        long growth(int additions) {
            int next = Math.max(n, capacity(size() + additions));
            return rehashBytes(n, next, 16);
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
    private static long rehashBytes(int current, int next, int width) {
        if (next <= current) return 0;
        // A batch can cross several capacities. At the last rehash the old array is next/2,
        // not necessarily the smaller array counted when the batch obtained its permit.
        return 192L + width * (next + 1L) + width * (long) Math.max(0, next / 2 - current);
    }
    private static long tableBytes(int capacity) { return 192L + 12L * (capacity + 1); }
    private static long pageBytes(int entries) { return 96L + (entries <= 1 ? 0 : tableBytes(capacity(entries))); }
    static long importBytes(int entries) {
        if (entries < 1 || entries > 4096) throw new IllegalArgumentException("Invalid original section size");
        // Page arrays plus its share of the outer index, including index rehash overlap.
        return pageBytes(entries) + 128;
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
        if (page == null) { page = new Page(1); pages.put(key, page); pageBytes += page.bytes(); }
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
                growth += 2 * pageBytes(Math.min(4096, additions[i]));
            } else growth += page.growth(additions[i]);
        }
        return growth + pages.growth(newPages);
    }

    long growthBytes(long position) {
        if (containsKey(position)) return 0;
        Page page = pages.get(section(position));
        return page == null ? pageBytes(1) + pages.growth(1) : page.growth(1);
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
            Page page = entry.getValue();
            for (var cells = page.keys(); cells.hasNext();) {
                int local = cells.nextInt(); long value = page.get(local);
                visitor.accept(position(entry.getLongKey(), local), stateOf(value), eventOf(value));
            }
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
            var cells = page.keys();
            boolean first = true;
            while (cells.hasNext()) {
                var palette = new RuinPalette();
                long[] packed = new long[Math.min(ROW, first ? page.size() : ROW)];
                int count = 0;
                while (count < packed.length && cells.hasNext()) {
                    int local = cells.nextInt(); long value = page.get(local);
                    int state = palette.index(stateOf(value));
                    packed[count++] = (long) eventOf(value) << 24 | (long) state << 12 | local;
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
        if (!row.contains("section", Tag.TAG_LONG)) throw new IllegalArgumentException("Invalid original section key");
        long key = row.getLong("section");
        if (SectionPos.y(key) < -128 || SectionPos.y(key) > 127)
            throw new IllegalArgumentException("Original section exceeds block-position height");
        if (row.contains("original_entries")) {
            // getInt accepts every numeric NBT type, but the preflight scanner charges IntTag.
            // Match that schema before any page allocation, including rejected partial files.
            if (!row.contains("original_entries", Tag.TAG_INT))
                throw new IllegalArgumentException("Invalid original section header type");
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
