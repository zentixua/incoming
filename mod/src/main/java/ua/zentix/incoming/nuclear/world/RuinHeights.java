package ua.zentix.incoming.nuclear.world;

/** Exact 256-column shadow tile. Most terrain tiles need a few bits/column; flat tiles need none. */
final class RuinHeights {
    private final int minimum, bits;
    private final long mask;
    private final long[] packed;

    RuinHeights(int[] heights) {
        if (heights.length != 256) throw new IllegalArgumentException("Expected one height tile");
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int height : heights) { min = Math.min(min, height); max = Math.max(max, height); }
        minimum = min;
        bits = 64 - Long.numberOfLeadingZeros((long) max - min);
        mask = bits == 0 ? 0 : (1L << bits) - 1;
        packed = new long[(256 * bits + 63) / 64];
        for (int i = 0; bits != 0 && i < 256; i++) {
            long value = (long) heights[i] - minimum;
            int bit = i * bits, word = bit >>> 6, shift = bit & 63;
            packed[word] |= value << shift;
            if (shift + bits > 64) packed[word + 1] |= value >>> (64 - shift);
        }
    }

    int get(int column) {
        if (bits == 0) return minimum;
        int bit = column * bits, word = bit >>> 6, shift = bit & 63;
        long value = packed[word] >>> shift;
        if (shift + bits > 64) value |= packed[word + 1] << (64 - shift);
        return (int) (minimum + (value & mask));
    }

    long bytes() { return 64L + 8L * packed.length; }
}
