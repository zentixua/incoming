package ua.zentix.incoming.compat;

import io.netty.buffer.Unpooled;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Chunk sections with light, as bytes, for a player's Voxy LODs ({@link VoxyLods} → {@code S2C.VoxyChunk} →
 * {@code client/VoxyIngest}): the same data Voxy takes from a client chunk (section blocks and biomes, block light and
 * skylight), for chunks the client does not have. A section uses the vanilla chunk packet format
 * ({@code LevelChunkSection.write}: block count, block and biome palettes using registry IDs shared by server and client);
 * light uses 2048-byte layers. All chunk sections from bottom to top, including empty ones: an air section where a tower
 * was destroyed erases it from LODs.
 * <p>
 * Format: lowest section index, section count; each section has a flags byte ({@link #BLOCKS}, {@link #BLOCK_LIGHT},
 * {@link #SKY_LIGHT}, {@link #FULL_SKY}) and the data those flags specify, in order.
 */
public final class LodSections {
    /** The section contains more than air: its blocks and biomes follow. */
    static final int BLOCKS = 1;
    /** Block light follows (2048 bytes); absent — dark. */
    static final int BLOCK_LIGHT = 2;
    /** Skylight follows (2048 bytes). */
    static final int SKY_LIGHT = 4;
    /** Skylight 15 everywhere (section under open sky), no layer. */
    static final int FULL_SKY = 8;
    /** Sparse update: leave this section untouched, including its light. */
    static final int UNCHANGED = 16;

    private LodSections() {}

    /** Section as supplied to Voxy: height index, blocks (empty for air), block light and skylight (null — dark). */
    public record Section(int y, LevelChunkSection blocks, @Nullable DataLayer blockLight, @Nullable DataLayer skyLight) {}

    /**
     * Loaded world chunk with the world's light engine lighting, as Voxy takes a client chunk; server thread
     * (reads live sections: the bytes themselves are the snapshot).
     */
    public static byte[] ofLoaded(ServerLevel level, ChunkAccess chunk) {
        LevelLightEngine light = level.getLightEngine();
        boolean hasSky = level.dimensionType().hasSkyLight();
        LevelChunkSection[] sections = chunk.getSections();
        FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
        out.writeVarInt(chunk.getMinSection());
        out.writeVarInt(sections.length);
        for (int i = 0; i < sections.length; i++) {
            SectionPos pos = SectionPos.of(chunk.getPos(), chunk.getMinSection() + i);
            DataLayer sky = hasSky ? light.getLayerListener(LightLayer.SKY).getDataLayerData(pos) : null;
            // the light engine has no skylight layer above the terrain: skylight is 15 there (as vanilla reads it)
            write(out, sections[i], light.getLayerListener(LightLayer.BLOCK).getDataLayerData(pos), sky, hasSky && sky == null);
        }
        return bytes(out);
    }

    /**
     * Chunk copy outside the world (nobody changes it after delivery), with light computed from its blocks ({@link CopyLight});
     * any thread.
     *
     * @param hasSky whether the dimension has skylight
     */
    public static byte[] ofCopy(ChunkAccess copy, boolean hasSky) {
        LevelChunkSection[] sections = copy.getSections();
        CopyLight light = CopyLight.of(sections, hasSky);
        FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
        out.writeVarInt(copy.getMinSection());
        out.writeVarInt(sections.length);
        for (int i = 0; i < sections.length; i++) {
            boolean full = hasSky && light.fullSky(i);
            write(out, sections[i], light.blockLayer(i), full ? null : light.skyLayer(i), full);
        }
        return bytes(out);
    }

    /** Canonical section bytes, including computed light; worker only. */
    public static byte[][] snapshot(LevelChunkSection[] sections, boolean hasSky) {
        CopyLight light = CopyLight.of(sections, hasSky);
        byte[][] result = new byte[sections.length][];
        for (int i = 0; i < sections.length; i++) {
            FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
            boolean full = hasSky && light.fullSky(i);
            write(out, sections[i], light.blockLayer(i), full ? null : light.skyLayer(i), full);
            result[i] = bytes(out);
        }
        return result;
    }

    /** Send every changed section, including all-air erasures and light-only changes; skip identical sections. */
    public static byte[] difference(int minSection, byte[][] before, byte[][] after) {
        if (before.length != after.length) throw new IllegalArgumentException("section range changed");
        FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
        out.writeVarInt(minSection);
        out.writeVarInt(after.length);
        for (int i = 0; i < after.length; i++) {
            if (java.util.Arrays.equals(before[i], after[i])) out.writeByte(UNCHANGED);
            else out.writeBytes(after[i]);
        }
        return bytes(out);
    }

    /** Retain the union of applied section revisions for replay repair. Newer sections replace older ones. */
    public static byte[] merge(byte[] older, byte[] newer, Registry<Biome> biomes) {
        java.util.TreeMap<Integer, Section> sections = new java.util.TreeMap<>();
        for (Section section : read(older, biomes)) sections.put(section.y(), section);
        for (Section section : read(newer, biomes)) sections.put(section.y(), section);
        FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
        int min = sections.isEmpty() ? 0 : sections.firstKey(), max = sections.isEmpty() ? -1 : sections.lastKey();
        out.writeVarInt(min);
        out.writeVarInt(max - min + 1);
        for (int y = min; y <= max; y++) {
            Section section = sections.get(y);
            if (section == null) out.writeByte(UNCHANGED);
            else write(out, section.blocks(), section.blockLight(), section.skyLight(), false);
        }
        return bytes(out);
    }

    private static void write(FriendlyByteBuf out, @Nullable LevelChunkSection section, @Nullable DataLayer block, @Nullable DataLayer sky, boolean fullSky) {
        // a layer of all 15s needs no bytes; do not touch a live light engine layer (getData fills a uniform layer itself)
        if (sky != null && sky.isDefinitelyFilledWith(15)) fullSky = true;
        if (block != null) block = block.copy();
        if (sky != null) sky = sky.copy();
        boolean blocks = section != null && !section.hasOnlyAir();
        boolean hasBlock = block != null && !block.isEmpty();
        boolean hasSky = !fullSky && sky != null && !sky.isEmpty();
        out.writeByte((blocks ? BLOCKS : 0) | (hasBlock ? BLOCK_LIGHT : 0) | (hasSky ? SKY_LIGHT : 0) | (fullSky ? FULL_SKY : 0));
        if (blocks) section.write(out);
        if (hasBlock) out.writeBytes(block.getData());
        if (hasSky) out.writeBytes(sky.getData());
    }

    private static byte[] bytes(FriendlyByteBuf out) {
        byte[] b = new byte[out.readableBytes()];
        out.readBytes(b);
        out.release();
        return b;
    }

    /** Sections from {@link #ofLoaded}/{@link #ofCopy} bytes; {@code biomes} is the client world's biome registry. */
    public static List<Section> read(byte[] data, Registry<Biome> biomes) {
        FriendlyByteBuf in = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {
        int min = in.readVarInt(), count = in.readVarInt();
        if (count < 0 || count > 4096) throw new IllegalArgumentException("sections: " + count);
        List<Section> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int flags = in.readUnsignedByte();
            if (flags == UNCHANGED) continue;
            if ((flags & ~15) != 0) throw new IllegalArgumentException("section flags: " + flags);
            LevelChunkSection section = new LevelChunkSection(biomes);
            if ((flags & BLOCKS) != 0) section.read(in);
            DataLayer block = (flags & BLOCK_LIGHT) != 0 ? layer(in) : null;
            DataLayer sky = (flags & SKY_LIGHT) != 0 ? layer(in) : (flags & FULL_SKY) != 0 ? new DataLayer(15) : null;
            out.add(new Section(min + i, section, block, sky));
        }
        if (in.isReadable()) throw new IllegalArgumentException(in.readableBytes() + " bytes left");
        return out;
        } finally {
            in.release();
        }
    }

    private static DataLayer layer(FriendlyByteBuf in) {
        byte[] b = new byte[DataLayer.SIZE];
        in.readBytes(b);
        return new DataLayer(b);
    }
}
