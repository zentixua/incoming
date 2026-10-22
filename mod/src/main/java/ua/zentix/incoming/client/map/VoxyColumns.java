package ua.zentix.incoming.client.map;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

import java.util.function.IntUnaryOperator;
import java.util.function.LongToIntFunction;
import java.util.function.Predicate;

/**
 * Верх колонок из секций базы Voxy (см. {@link VoxyTerrain}): то же, что слой чанков клиента ({@link LoadedChunksTerrain})
 * и LSS ({@link LssColumns}) берут из блоков, — первый блок карты высот {@code MOTION_BLOCKING} сверху, ниже — первый блок
 * с цветом ванильной карты, глубина воды под ним. Без классов Voxy: считается здесь, поэтому проверяется юнит-тестами.
 * <p>
 * Секция Voxy уровня {@code L} — 32³ вокселя по {@code 2^L} блоков (индекс {@code y·1024 + z·32 + x}); воксель уровня
 * выше нуля Voxy собирает из восьми меньших: непрозрачнейший, при равных — верхний, воздух — только если все воздух
 * ({@code Mipper}). Значит, верхний не-воздух столбца вокселей — самый высокий блок клетки {@code 2^L × 2^L}, а где
 * именно в своём вокселе по высоте он лежит, неизвестно: высота — середина вокселя ({@link #height}), ошибка до
 * ±{@code 2^L/2} блоков. На уровне 0 — точно, как у чанков.
 * <p>
 * Колонка упакована как у {@link LssColumns#pack}; 0 — не знаем (секций нет: Voxy этого места не видел).
 */
final class VoxyColumns {
    /** Сторона секции Voxy, вокселей. */
    static final int SIDE = 32;
    /** Колонок в секции. */
    static final int AREA = SIDE * SIDE;
    /** Вокселей в секции ({@code WorldSection.SECTION_VOLUME}). */
    static final int VOLUME = AREA * SIDE;
    /** Сведения о блоке ({@link #info}): известен ли он вообще (0 — не знаем, как воздух). */
    static final int KNOWN = 1 << 8;
    /** Блок карты высот {@code MOTION_BLOCKING}: преграждает движение или с жидкостью. */
    static final int MOTION = 1 << 9;
    /** Вода (и блок с водой). */
    static final int WATER = 1 << 10;
    private static final Predicate<BlockState> MOTION_BLOCKING = Heightmap.Types.MOTION_BLOCKING.isOpaque();

    private static final byte TOP = 0, COLOR = 1, DEPTH = 2, DONE = 3;

    private VoxyColumns() {}

    /** Сведения о блоке для разбора: номер цвета карты (биты 0–5), {@link #KNOWN}, {@link #MOTION}, {@link #WATER}. */
    static int info(BlockState state) {
        MapColor color = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        FluidState fluid = state.getFluidState();
        boolean water = fluid.is(FluidTags.WATER) || fluid.getType().isSame(Fluids.WATER);
        return KNOWN | color.id & 0x3F | (MOTION_BLOCKING.test(state) ? MOTION : 0) | (water ? WATER : 0);
    }

    /**
     * Высота колонки (первый воздух над землёй) по вокселю уровня {@code level} с низом {@code bottom}: верх блока
     * лежит где-то от {@code bottom + 1} до {@code bottom + 2^L} — середина.
     */
    static int height(int level, int bottom) {
        return bottom + ((1 << level) + 1) / 2;
    }

    /**
     * Сводка одного столбца секций уровня {@code level} (32×32 колонки), секции — сверху вниз ({@link #section}), пока не
     * {@link #done}; итог — {@link #finish}.
     */
    static final class Summary {
        final int level;
        private final int scale, colorless;
        private final int[] out = new int[AREA];
        private final byte[] state = new byte[AREA];
        /** Сколько вокселей ещё смотреть в поисках цвета; глубина воды, вокселей; цвет и высота найденного. */
        private final int[] budget = new int[AREA], depth = new int[AREA], color = new int[AREA], top = new int[AREA];
        private int open = AREA;
        /** Секция, которую дали последней: следующая — ниже на одну. */
        private int lastY = Integer.MAX_VALUE;

        Summary(int level) {
            this.level = level;
            this.scale = 1 << level;
            // под прозрачными (стекло, свет) цвет ищется на 8 блоков вниз, как у слоя чанков
            this.colorless = Math.max(1, LssColumns.MAX_COLORLESS >> level);
        }

        boolean done() {
            return open == 0;
        }

        /**
         * Секция {@code sy} (номер по высоте на этом уровне), следующая под прошлой; {@code data} — её воксели, null —
         * секции в базе нет (воздух или Voxy её не видел). {@code blockId} — номер блока вокселя, {@code info} —
         * сведения о блоке по номеру (0 — не знаем).
         */
        void section(int sy, @Nullable long[] data, LongToIntFunction blockId, IntUnaryOperator info) {
            if (sy >= lastY) throw new IllegalArgumentException("sections go top down: " + sy + " after " + lastY);
            lastY = sy;
            for (int i = 0; i < AREA && open > 0; i++) {
                if (state[i] == DONE) continue;
                for (int ly = SIDE - 1; ly >= 0 && state[i] != DONE; ly--) {
                    int bottom = ((sy << 5) + ly) << level;
                    int id = data == null ? 0 : blockId.applyAsInt(data[ly << 10 | i]);
                    int b = id == 0 ? 0 : info.applyAsInt(id);
                    step(i, b, bottom);
                }
            }
        }

        /** Один воксель колонки {@code i} сверху вниз: сведения о его блоке {@code b} (0 — воздух или не знаем). */
        private void step(int i, int b, int bottom) {
            switch (state[i]) {
                case TOP -> {
                    if ((b & MOTION) == 0) return;
                    state[i] = COLOR;
                    budget[i] = colorless;
                    colour(i, b, bottom);
                }
                case COLOR -> colour(i, b, bottom);
                case DEPTH -> {
                    if ((b & WATER) == 0) {
                        close(i);
                        return;
                    }
                    // глубже 16 блоков вода на карте одинаково тёмная — дальше не смотрим
                    if (++depth[i] * scale >= LssColumns.MAX_WATER_DEPTH) close(i);
                }
                default -> {}
            }
        }

        private void colour(int i, int b, int bottom) {
            int c = b & 0x3F;
            if ((b & KNOWN) != 0 && c != MapColor.NONE.id) {
                color[i] = c;
                top[i] = height(level, bottom);
                if ((b & WATER) != 0) {
                    depth[i] = 1;
                    state[i] = DEPTH;
                    if (scale >= LssColumns.MAX_WATER_DEPTH) close(i);
                    return;
                }
                close(i);
                return;
            }
            if (--budget[i] > 0) return;
            // цвета нет и под прозрачными — колонка неизвестна, как у слоя чанков
            state[i] = DONE;
            open--;
        }

        private void close(int i) {
            out[i] = LssColumns.pack(top[i], color[i], depth[i] * scale);
            state[i] = DONE;
            open--;
        }

        /** Колонки (индекс {@code z·32 + x}): вода, дно которой ниже последней секции, — с той глубиной, что набралась. */
        int[] finish() {
            for (int i = 0; i < AREA; i++) {
                if (state[i] == DEPTH) close(i);
            }
            return out;
        }
    }
}
