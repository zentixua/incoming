package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.ints.IntCollection;
import it.unimi.dsi.fastutil.longs.LongCollection;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.io.DataOutput;
import java.io.IOException;

/** Streaming NBT: a ledger never constructs a whole save tree or copies its primitive arrays. */
final class LedgerWriter {
    private final DataOutput output;
    LedgerWriter(DataOutput output) { this.output = output; }

    void header(int type, String name) throws IOException { output.writeByte(type); output.writeUTF(name); }
    void compound(String name) throws IOException { header(Tag.TAG_COMPOUND, name); }
    void end() throws IOException { output.writeByte(Tag.TAG_END); }
    void fields(CompoundTag row) throws IOException {
        for (String key : row.getAllKeys()) {
            Tag value = row.get(key);
            header(value.getId(), key);
            value.write(output);
        }
    }
    void row(CompoundTag row) throws IOException { fields(row); end(); }
    void list(String name, int count) throws IOException {
        header(Tag.TAG_LIST, name); output.writeByte(Tag.TAG_COMPOUND); output.writeInt(count);
    }
    void longs(String name, LongCollection values) throws IOException {
        header(Tag.TAG_LONG_ARRAY, name); output.writeInt(values.size());
        var iterator = values.iterator();
        while (iterator.hasNext()) output.writeLong(iterator.nextLong());
    }
    void ints(String name, IntCollection values) throws IOException {
        header(Tag.TAG_INT_ARRAY, name); output.writeInt(values.size());
        var iterator = values.iterator();
        while (iterator.hasNext()) output.writeInt(iterator.nextInt());
    }
}
