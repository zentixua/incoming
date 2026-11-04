package ua.zentix.incoming.nuclear.world;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * FIFO of fixed-width exact records. Full pages are immutable; a save shares them and copies at
 * most the two partial ends. Removing a row advances an offset, never mutates a saved page.
 * Server-thread ownership, detached snapshots safe for asynchronous compression.
 */
final class RuinRecords<T> implements Iterable<T> {
    static final int ROWS = 1024;
    private static final class Page {
        byte[] bytes;
        int from, used;
        Page(byte[] bytes, int used) { this.bytes = bytes; this.used = used; }
    }
    private final int width;
    private final BiConsumer<ByteBuffer, T> encode;
    private final Function<ByteBuffer, T> decode;
    private final ArrayDeque<Page> pages = new ArrayDeque<>();
    private int size;

    RuinRecords(int width, BiConsumer<ByteBuffer, T> encode, Function<ByteBuffer, T> decode) {
        this.width = width; this.encode = encode; this.decode = decode;
    }

    void addLast(T value) {
        Page page = pages.peekLast();
        if (page == null || page.used == ROWS) {
            page = new Page(new byte[width * ROWS], 0);
            pages.addLast(page);
        } else if (page.bytes.length < width * ROWS) {
            // A restored partial tail belongs to the input snapshot; append only to an owned copy.
            page.bytes = Arrays.copyOf(page.bytes, width * ROWS);
        }
        ByteBuffer buffer = ByteBuffer.wrap(page.bytes, page.used * width, width);
        encode.accept(buffer, value);
        if (buffer.hasRemaining()) throw new IllegalStateException("Incomplete ruin record");
        page.used++; size++;
    }

    T removeFirst() {
        Page page = pages.getFirst();
        T value = decode.apply(ByteBuffer.wrap(page.bytes, page.from * width, width));
        page.from++; size--;
        if (page.from == page.used) pages.removeFirst();
        return value;
    }

    int size() { return size; }
    boolean isEmpty() { return size == 0; }
    void clear() { pages.clear(); size = 0; }

    List<byte[]> snapshot() {
        List<byte[]> result = new ArrayList<>(pages.size());
        for (Page page : pages) result.add(page.from == 0 && page.used == ROWS ? page.bytes
                : Arrays.copyOfRange(page.bytes, page.from * width, page.used * width));
        return result;
    }

    void restore(byte[] bytes) {
        if (bytes.length == 0 || bytes.length % width != 0 || bytes.length > width * ROWS)
            throw new IllegalArgumentException("Invalid ruin record page");
        pages.addLast(new Page(bytes, bytes.length / width));
        size = Math.addExact(size, bytes.length / width);
    }

    @Override public Iterator<T> iterator() {
        var iterator = pages.iterator();
        return new Iterator<>() {
            Page page;
            int next;
            @Override public boolean hasNext() { return page != null && next < page.used || iterator.hasNext(); }
            @Override public T next() {
                if (page == null || next == page.used) { page = iterator.next(); next = page.from; }
                return decode.apply(ByteBuffer.wrap(page.bytes, next++ * width, width));
            }
        };
    }
}
