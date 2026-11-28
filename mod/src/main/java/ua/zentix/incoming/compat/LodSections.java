package ua.zentix.incoming.compat;

import io.netty.buffer.Unpooled;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.ticks.ProtoChunkTicks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.nuclear.world.ChunkShot;

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
 * <p>
 * A copy made from disk ({@link DiskCopy}) starts at the lowest section the strike can have changed ({@link #from}):
 * Voxy keeps the sections below, which hold the disk state it already has (from its own view or LOD Server Support).
 * Underground sections the wave never reached are most of a ruin chunk's bytes (60 % of 575 MB of {@code voxy_chunk}
 * in a 148 s Flashback recording of a 300 kt strike, 77 % once deflated: Zearth rig, 28.11.2026), and a recording keeps
 * every byte of them though a replay never uses them ({@code replay.md}).
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

    private LodSections() {}

    /**
     * A chunk copy built from disk sections ({@code nuclear/world/FarLods.build}) with each section's fingerprint as
     * read from disk ({@link #prints}), before ruins, trace, biomes or lamps changed it.
     */
    public static final class DiskCopy extends ProtoChunk {
        private final long[] disk;

        public DiskCopy(ChunkPos pos, LevelChunkSection[] sections, long[] disk, LevelHeightAccessor height, Registry<Biome> biomes) {
            super(pos, UpgradeData.EMPTY, sections, new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), height, biomes, null);
            if (disk.length != sections.length) throw new IllegalArgumentException(disk.length + " prints for " + sections.length + " sections");
            this.disk = disk;
        }
    }

    /**
     * Section fingerprints as they are ({@code ChunkShot.rawPrint} of blocks and of biomes: palettes by identity and index
     * storage, hundreds of {@code long}, cheap). A section written to gets a new fingerprint even where its blocks end up the same (palette
     * growth): such a section is sent, never wrongly kept.
     */
    public static long[] prints(LevelChunkSection[] sections) {
        long[] p = new long[sections.length];
        for (int i = 0; i < sections.length; i++) p[i] = print(sections[i]);
        return p;
    }

    private static long print(@Nullable LevelChunkSection s) {
        if (s == null) return 0;
        long blocks = ChunkShot.rawPrint(s.hasOnlyAir() ? null : s.getStates());
        return blocks * 0x9E3779B97F4A7C15L ^ (s.getBiomes() instanceof PalettedContainer<?> c ? ChunkShot.rawPrint(c) : System.identityHashCode(s.getBiomes()));
    }

    /**
     * Lowest section index of {@code copy} the strike can have changed in Voxy's copy of the chunk: one below the lowest
     * section whose blocks or biomes differ from disk (block light reaches at most 15 blocks, under one section), and
     * below that every section the copy's sky still lights (sky light falls through any opening the ruins made). A copy
     * the same as disk in every section is a restore (district light back, a saved chunk) and goes whole: Voxy may hold
     * an earlier copy there.
     *
     * @param sky whether section {@code i} has sky light in the copy
     */
    static int from(long[] disk, long[] now, java.util.function.IntPredicate sky) {
        int lowest = -1;
        for (int i = 0; i < now.length; i++) {
            if (now[i] != disk[i]) {
                lowest = i;
                break;
            }
        }
        if (lowest <= 0) return 0;
        int from = lowest - 1;
        while (from > 0 && sky.test(from - 1)) from--;
        return from;
    }

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
        int from = copy instanceof DiskCopy d ? from(d.disk, prints(sections), light::skyLit) : 0;
        FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
        out.writeVarInt(copy.getMinSection() + from);
        out.writeVarInt(sections.length - from);
        for (int i = from; i < sections.length; i++) {
            boolean full = hasSky && light.fullSky(i);
            write(out, sections[i], light.blockLayer(i), full ? null : light.skyLayer(i), full);
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
        int min = in.readVarInt(), count = in.readVarInt();
        if (count < 0 || count > 4096) throw new IllegalArgumentException("sections: " + count);
        List<Section> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int flags = in.readUnsignedByte();
            LevelChunkSection section = new LevelChunkSection(biomes);
            if ((flags & BLOCKS) != 0) section.read(in);
            DataLayer block = (flags & BLOCK_LIGHT) != 0 ? layer(in) : null;
            DataLayer sky = (flags & SKY_LIGHT) != 0 ? layer(in) : (flags & FULL_SKY) != 0 ? new DataLayer(15) : null;
            out.add(new Section(min + i, section, block, sky));
        }
        if (in.isReadable()) throw new IllegalArgumentException(in.readableBytes() + " bytes left");
        return out;
    }

    private static DataLayer layer(FriendlyByteBuf in) {
        byte[] b = new byte[DataLayer.SIZE];
        in.readBytes(b);
        return new DataLayer(b);
    }
}
