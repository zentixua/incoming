package ua.zentix.incoming.nuclear.world;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/** Bounded-scratch payload scan, including legacy arrays: no NBT tree exists before admission. */
final class LedgerScan {
    private long bytes, cargo, checkpoints;
    private int event;
    private final long limit;
    private LedgerScan(long limit) { this.limit = limit; }

    static DataInputStream open(Path file) throws IOException {
        var input = new BufferedInputStream(Files.newInputStream(file));
        input.mark(2);
        int first = input.read(), second = input.read();
        input.reset();
        try {
            InputStream decoded = first == 0x1f && second == 0x8b ? new GZIPInputStream(input) : input;
            return new DataInputStream(new BufferedInputStream(decoded));
        } catch (IOException error) { input.close(); throw error; }
    }

    static long bytes(Path file, long limit) throws IOException {
        var scan = new LedgerScan(limit);
        try (var input = open(file)) {
            if (input.readUnsignedByte() != 10) throw new IOException("Ledger root is not a compound");
            scan.string(input);
            scan.payload(input, 10, 0);
        } catch (Capacity full) { return limit + 1; }
        return scan.bytes;
    }

    record Measure(long owned, long row) {}

    /** Live representation plus one transient row, rather than eight copies of the old save tree. */
    static Measure measure(Path file, String name, java.util.Set<Integer> activeCraters, long limit) throws IOException {
        long owned = LedgerData.IO_BYTES + 8192, largest = 0, cargoTotal = 0;
        var scan = new LedgerScan(Long.MAX_VALUE / 16);
        try (var input = open(file)) {
            if (input.readUnsignedByte() != 10) throw new IOException("Invalid ledger root");
            input.readUTF();
            for (int root; (root = input.readUnsignedByte()) != 0;) {
                String rootName = input.readUTF();
                if (root != 10 || !rootName.equals("data")) {
                    scan.bytes = 0; scan.payload(input, root, 0); continue;
                }
                for (int type; (type = input.readUnsignedByte()) != 0;) {
                    String key = input.readUTF();
                    if (type == 9) {
                        int element = input.readUnsignedByte(), count = scan.count(input);
                        if (element != 10 && count != 0) throw new IOException("Invalid ledger row list");
                        for (int i = 0; i < count; i++) {
                            scan.bytes = 0; scan.cargo = 0; scan.checkpoints = 0; scan.event = -1;
                            scan.payload(input, element, 0);
                            long rowBytes = key.equals("passes") && !activeCraters.contains(scan.event)
                                    ? scan.bytes - scan.checkpoints : scan.bytes;
                            largest = Math.max(largest, rowBytes);
                            long charge = switch (key) {
                                case "moving" -> 544 + 3 * scan.cargo;
                                case "stranded" -> 256 + 3 * scan.cargo;
                                case "support_sources" -> 4096;
                                case "support_originals", "cold_edges" -> 256;
                                case "materials", "settled" -> 768;
                                // Old crater checkpoints can be large. Twelve times encoded tag
                                // storage covers both maps/sets and their temporary primitive arrays.
                                default -> 12 * rowBytes;
                            };
                            owned += charge;
                            if (key.equals("moving") || key.equals("stranded")) cargoTotal += scan.cargo;
                            if (owned > limit) return new Measure(limit + 1, largest);
                        }
                    } else {
                        scan.bytes = 0; scan.payload(input, type, 0);
                        largest = Math.max(largest, scan.bytes);
                        // Support arrays load together; maps, old backing arrays and the input
                        // arrays fit inside the existing 256-byte-per-seed ownership estimate.
                        owned += key.equals("supports") ? 32 * scan.bytes
                                : key.equals("support_events") ? 0 : 8 * scan.bytes;
                        if (owned > limit) return new Measure(limit + 1, largest);
                    }
                }
            }
        } catch (Capacity full) { return new Measure(limit + 1, limit); }
        // The existing three-cargo charge already covers the live cargo and its transient import.
        // IO_BYTES also pays the ordinary row; large non-cargo rows need explicit extra room.
        owned += Math.max(0, 2 * largest - 2 * cargoTotal - LedgerData.IO_BYTES);
        return new Measure(owned, Math.max(LedgerData.IO_BYTES, 2 * largest));
    }

    static int event(DataInputStream input) throws IOException {
        var scan = new LedgerScan(Long.MAX_VALUE / 16);
        scan.payload(input, 10, 0);
        return scan.event;
    }

    static void skip(DataInputStream input, int type) throws IOException {
        new LedgerScan(Long.MAX_VALUE / 16).payload(input, type, 0);
    }

    private void add(long n) throws Capacity {
        if (n < 0 || n > limit - bytes) throw new Capacity();
        bytes += n;
    }

    private void string(DataInputStream input) throws IOException {
        String text = input.readUTF();
        add(36L + 2L * text.length());
    }

    private int count(DataInputStream input) throws IOException {
        int n = input.readInt();
        if (n < 0) throw new IOException("Negative ledger array/list size");
        return n;
    }

    private void payload(DataInputStream input, int type, int depth) throws IOException {
        if (depth > 512) throw new IOException("Ledger NBT nesting exceeds 512");
        switch (type) {
            case 0 -> { }
            case 1 -> { add(9); input.readByte(); }
            case 2 -> { add(10); input.readShort(); }
            case 3, 5 -> { add(12); input.readInt(); }
            case 4, 6 -> { add(16); input.readLong(); }
            case 7, 11, 12 -> {
                int width = type == 7 ? 1 : type == 11 ? 4 : 8;
                long size = (long) count(input) * width;
                add(24 + size); input.skipNBytes(size);
            }
            case 8 -> string(input);
            case 9 -> {
                int element = input.readUnsignedByte(), size = count(input);
                if (element == 0 && size != 0) throw new IOException("Nonempty end-tag list");
                add(37L + 4L * size);
                for (int i = 0; i < size; i++) payload(input, element, depth + 1);
            }
            case 10 -> {
                add(48);
                for (int child; (child = input.readUnsignedByte()) != 0;) {
                    String key = input.readUTF(); add(64L + 2L * key.length());
                    long before = bytes;
                    if (key.equals("event") && depth == 0 && child == 3) {
                        add(12); event = input.readInt();
                    } else payload(input, child, depth + 1);
                    if (depth == 0) {
                        if (key.equals("contents")) cargo += bytes - before;
                        if (key.equals("columns") || key.equals("ground") || key.equals("done")) checkpoints += bytes - before;
                    }
                }
            }
            default -> throw new IOException("Unknown ledger NBT type " + type);
        }
    }

    private static final class Capacity extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
