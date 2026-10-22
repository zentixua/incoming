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
 * Секции чанка со светом — байтами, для LOD Voxy у игрока ({@link VoxyLods} → {@code S2C.VoxyChunk} →
 * {@code client/VoxyIngest}): то же, что Voxy берёт из чанка клиента (блоки и биомы секции, свет блоков и неба), — для
 * чанков, которых у клиента нет. Секция — в формате пакета чанка ванили ({@code LevelChunkSection.write}: число блоков,
 * палитры блоков и биомов по номерам реестров, общим у сервера и клиента), свет — слоями по 2048 байт. Все секции чанка
 * снизу вверх, и пустые: секция воздуха на месте снесённой башни стирает её из LOD.
 * <p>
 * Формат: номер нижней секции, число секций; у каждой — байт признаков ({@link #BLOCKS}, {@link #BLOCK_LIGHT},
 * {@link #SKY_LIGHT}, {@link #FULL_SKY}) и что они называют, по порядку.
 */
public final class LodSections {
    /** В секции есть не только воздух: дальше — её блоки и биомы. */
    static final int BLOCKS = 1;
    /** Дальше — свет блоков (2048 байт); нет — темно. */
    static final int BLOCK_LIGHT = 2;
    /** Дальше — свет неба (2048 байт). */
    static final int SKY_LIGHT = 4;
    /** Свет неба 15 везде (секция под открытым небом), слоя нет. */
    static final int FULL_SKY = 8;

    private LodSections() {}

    /** Секция, как её отдаёт Voxy: номер по высоте, блоки (воздух — пустая), свет блоков и неба (null — темно). */
    public record Section(int y, LevelChunkSection blocks, @Nullable DataLayer blockLight, @Nullable DataLayer skyLight) {}

    /**
     * Загруженный чанк мира — со светом движка мира, как Voxy берёт чанк клиента; поток сервера (читает живые секции:
     * снимок — сами байты).
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
            // у движка света нет слоя неба над верхом рельефа: там свет неба 15 (как его читает ваниль)
            write(out, sections[i], light.getLayerListener(LightLayer.BLOCK).getDataLayerData(pos), sky, hasSky && sky == null);
        }
        return bytes(out);
    }

    /**
     * Копия чанка не из мира (её после отдачи никто не меняет) — со светом, посчитанным по её блокам ({@link CopyLight});
     * любой поток.
     *
     * @param hasSky у измерения есть небо
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

    private static void write(FriendlyByteBuf out, @Nullable LevelChunkSection section, @Nullable DataLayer block, @Nullable DataLayer sky, boolean fullSky) {
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

    /** Секции из байтов {@link #ofLoaded}/{@link #ofCopy}; {@code biomes} — реестр биомов мира клиента. */
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
