package ua.zentix.incoming.compat;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Свет копии чанка не из мира (руины, след подрыва, лампы квартала — {@code nuclear/world/FarLods}) для LOD Voxy: у копии
 * света нет (мир его не считал), а Voxy берёт свет вокселя как есть — без него LOD чёрные, а со светом с диска у
 * погашенного квартала горели бы прежние лампы. Считается по правилам ванили внутри одного чанка (так же свой свет
 * LOD считает Distant Horizons, {@code DhLightingEngine}): свет блоков — от светимости блока, свет неба — 15 сверху
 * вниз сквозь прозрачное без потерь, дальше во все шесть сторон с потерей не меньше 1 на блок, у мутного (вода,
 * листва) — на его непрозрачность. Не по ванили: соседние чанки не светят внутрь (у края чанка свет соседа не виден —
 * на дальности LOD это доли блока), форма блока не учитывается (полублок гасит, как целый, если непрозрачен).
 */
public final class CopyLight {
    /** Свет каждого места: {@code [секция][y·256 + z·16 + x]}, 0–15. */
    final byte[][] block, sky;
    private final int sections;

    private CopyLight(int sections) {
        this.sections = sections;
        block = new byte[sections][4096];
        sky = new byte[sections][4096];
    }

    /**
     * Свет секций чанка снизу вверх ({@code sections[i]} — секция с номером {@code minSection + i}; null — воздух).
     *
     * @param hasSky у измерения есть небо ({@code DimensionType.hasSkyLight}); нет — свет неба 0
     */
    public static CopyLight of(LevelChunkSection[] sections, boolean hasSky) {
        CopyLight l = new CopyLight(sections.length);
        int cells = sections.length * 4096;
        // непрозрачность (0–15) и светимость каждого места: состояний в чанке — сотни, мест — сотни тысяч
        byte[] opacity = new byte[cells];
        byte[] emission = new byte[cells];
        Map<BlockState, Integer> props = new IdentityHashMap<>();
        for (int s = 0; s < sections.length; s++) {
            LevelChunkSection section = sections[s];
            if (section == null || section.hasOnlyAir()) continue;
            for (int i = 0; i < 4096; i++) {
                BlockState state = section.getBlockState(i & 15, i >> 8, i >> 4 & 15);
                int p = props.computeIfAbsent(state, CopyLight::properties);
                opacity[s * 4096 + i] = (byte) (p & 15);
                emission[s * 4096 + i] = (byte) (p >> 4 & 15);
            }
        }
        int[] queue = new int[cells];
        if (hasSky) l.skyLight(opacity, queue);
        l.blockLight(opacity, emission, queue);
        return l;
    }

    /** Непрозрачность (биты 0–3) и светимость (4–7) состояния; свет неба, идущий вниз без потерь, — бит 8. */
    private static int properties(BlockState state) {
        int opacity = Math.max(0, Math.min(15, state.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)));
        int emission = Math.max(0, Math.min(15, state.getLightEmission(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)));
        return opacity | emission << 4;
    }

    private int get(byte[][] layer, int cell) {
        return layer[cell >> 12][cell & 4095];
    }

    private void skyLight(byte[] opacity, int[] queue) {
        int height = sections * 16, tail = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int light = 15;
                for (int y = height - 1; y >= 0; y--) {
                    int cell = cell(x, y, z), o = opacity[cell];
                    // 15 идёт вниз сквозь прозрачное без потерь, меньше — теряет хотя бы 1 на блок
                    light = light == 15 && o == 0 ? 15 : Math.max(0, light - Math.max(1, o));
                    if (light == 0) break;
                    sky[cell >> 12][cell & 4095] = (byte) light;
                    queue[tail++] = cell;
                }
            }
        }
        spread(sky, opacity, queue, tail);
    }

    private void blockLight(byte[] opacity, byte[] emission, int[] queue) {
        int tail = 0;
        for (int cell = 0; cell < emission.length; cell++) {
            if (emission[cell] <= 0) continue;
            block[cell >> 12][cell & 4095] = emission[cell];
            queue[tail++] = cell;
        }
        spread(block, opacity, queue, tail);
    }

    /**
     * Свет из мест очереди — во все шесть сторон внутри чанка, с потерей не меньше 1 на блок (у мутного — его
     * непрозрачность): место встаёт в очередь снова, только когда свет в нём вырос (не больше 15 раз).
     */
    private void spread(byte[][] layer, byte[] opacity, int[] seeds, int count) {
        int height = sections * 16;
        IntArrayFIFOQueue ring = new IntArrayFIFOQueue(Math.max(16, count));
        for (int i = 0; i < count; i++) ring.enqueue(seeds[i]);
        while (!ring.isEmpty()) {
            int cell = ring.dequeueInt(), light = get(layer, cell);
            if (light <= 1) continue;
            int x = cell & 15, z = cell >> 4 & 15, y = cell >> 8;
            for (int d = 0; d < 6; d++) {
                int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15 || ny < 0 || ny >= height) continue;
                int next = cell(nx, ny, nz);
                int lit = light - Math.max(1, opacity[next]);
                if (lit <= get(layer, next)) continue;
                layer[next >> 12][next & 4095] = (byte) lit;
                ring.enqueue(next);
            }
        }
    }

    private static final int[] DX = {1, -1, 0, 0, 0, 0}, DY = {0, 0, 1, -1, 0, 0}, DZ = {0, 0, 0, 0, 1, -1};

    /** Место в чанке: y от низа первой секции; индекс внутри секции — как у {@link DataLayer} и палитр (y·256 + z·16 + x). */
    static int cell(int x, int y, int z) {
        return (y >> 4) << 12 | (y & 15) << 8 | z << 4 | x;
    }

    /** Свет места (x, y от низа первой секции, z). */
    int block(int x, int y, int z) {
        return get(block, cell(x, y, z));
    }

    int sky(int x, int y, int z) {
        return get(sky, cell(x, y, z));
    }

    /** Свет блоков секции {@code i} слоем Minecraft; null — темно везде. */
    @Nullable
    public DataLayer blockLayer(int i) {
        return layer(block[i]);
    }

    /** Свет неба секции {@code i}; null — темно везде (под землёй или без неба). */
    @Nullable
    public DataLayer skyLayer(int i) {
        return layer(sky[i]);
    }

    /** Секция {@code i} вся под открытым небом (свет неба 15 везде). */
    public boolean fullSky(int i) {
        for (byte b : sky[i]) if (b != 15) return false;
        return true;
    }

    @Nullable
    private static DataLayer layer(byte[] light) {
        boolean any = false;
        for (byte b : light) if (b != 0) {
            any = true;
            break;
        }
        if (!any) return null;
        DataLayer out = new DataLayer();
        for (int i = 0; i < 4096; i++) if (light[i] != 0) out.set(i & 15, i >> 8, i >> 4 & 15, light[i]);
        return out;
    }
}
