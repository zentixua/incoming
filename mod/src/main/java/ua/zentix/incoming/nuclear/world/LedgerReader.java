package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagTypes;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;

/** Imports legacy and current NBT one compound row at a time, without a whole-ledger tag tree. */
final class LedgerReader {
    private LedgerReader() {}

    static LedgerData read(Path file, String name, HolderLookup.Provider registries, long rowLimit, java.util.Set<Integer> activeCraters) throws IOException {
        LedgerData result = switch (name) {
            case "incoming_ruin_matter" -> new RuinMatter();
            case "incoming_crater_matter" -> new CraterMatter();
            case "incoming_ruin_settle" -> new RuinProgress();
            default -> throw new IOException("Unknown material ledger " + name);
        };
        try (var counted = new Counted(LedgerScan.open(file)); var input = new DataInputStream(counted);
             var probeCounted = new Counted(LedgerScan.open(file)); var probe = new DataInputStream(probeCounted)) {
            if (input.readUnsignedByte() != Tag.TAG_COMPOUND) throw new IOException("Invalid material root");
            input.readUTF();
            boolean data = false;
            for (int type; (type = input.readUnsignedByte()) != Tag.TAG_END;) {
                String key = input.readUTF();
                if (key.equals("data") && type == Tag.TAG_COMPOUND) {
                    fields(input, counted, probe, probeCounted, result, registries, rowLimit, activeCraters); data = true;
                } else TagTypes.getType(type).skip(input, NbtAccounter.create(rowLimit));
            }
            if (!data) throw new IOException("Material root has no data");
        }
        if (result instanceof RuinMatter matter) RuinMatter.read(new CompoundTag(), registries, matter, true);
        result.setDirty(); // Persist packed rows and retired-checkpoint compaction on the next normal save.
        return result;
    }

    private static void fields(DataInputStream input, Counted counted, DataInputStream probe, Counted probeCounted,
                               LedgerData result, HolderLookup.Provider registries, long rowLimit,
                               java.util.Set<Integer> activeCraters) throws IOException {
        CompoundTag header = new CompoundTag(); // Scalars plus the paired support arrays; charged by seed capacity.
        for (int type; (type = input.readUnsignedByte()) != Tag.TAG_END;) {
            String key = input.readUTF();
            if (type == Tag.TAG_LIST) {
                int element = input.readUnsignedByte(), count = input.readInt();
                if (count < 0 || element != Tag.TAG_COMPOUND && count != 0) throw new IOException("Invalid material row list");
                for (int i = 0; i < count; i++) {
                    Tag row;
                    int retired = -1;
                    if (result instanceof CraterMatter && key.equals("passes")) {
                        // Field order in legacy compounds is unspecified. A second streaming cursor
                        // reads the event ID first, without retaining its possibly enormous arrays.
                        probe.skipNBytes(counted.position - probeCounted.position);
                        int event = LedgerScan.event(probe);
                        if (!activeCraters.contains(event)) retired = event;
                        row = craterRow(input, retired >= 0, rowLimit);
                    } else row = TagTypes.getType(element).load(input, NbtAccounter.create(rowLimit));
                    CompoundTag part = new CompoundTag(); ListTag rows = new ListTag(); rows.add(row); part.put(key, rows);
                    accept(result, part, registries);
                    if (retired >= 0) ((CraterMatter) result).complete(retired);
                }
            } else if (result instanceof RuinProgress) {
                CompoundTag part = new CompoundTag();
                part.put(key, TagTypes.getType(type).load(input, NbtAccounter.create(rowLimit)));
                accept(result, part, registries);
            } else header.put(key, TagTypes.getType(type).load(input, NbtAccounter.create(rowLimit)));
        }
        accept(result, header, registries);
    }

    private static CompoundTag craterRow(DataInputStream input, boolean retired, long limit) throws IOException {
        CompoundTag row = new CompoundTag();
        var counter = NbtAccounter.create(limit);
        for (int type; (type = input.readUnsignedByte()) != Tag.TAG_END;) {
            String key = input.readUTF();
            if (retired && (key.equals("columns") || key.equals("ground") || key.equals("done")))
                LedgerScan.skip(input, type);
            else row.put(key, TagTypes.getType(type).load(input, counter));
        }
        return row;
    }

    private static final class Counted extends java.io.FilterInputStream {
        long position;
        Counted(java.io.InputStream input) { super(input); }
        @Override public int read() throws IOException {
            int value = in.read(); if (value >= 0) position++; return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, length); if (count > 0) position += count; return count;
        }
        @Override public long skip(long count) throws IOException {
            long skipped = in.skip(count); position += skipped; return skipped;
        }
    }

    private static void accept(LedgerData result, CompoundTag row, HolderLookup.Provider registries) {
        if (result instanceof RuinMatter matter) RuinMatter.read(row, registries, matter, false);
        else if (result instanceof CraterMatter crater) CraterMatter.read(row, registries, crater);
        else RuinProgress.read(row, registries, (RuinProgress) result);
    }
}
