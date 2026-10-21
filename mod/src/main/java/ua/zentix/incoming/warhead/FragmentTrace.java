package ua.zentix.incoming.warhead;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.registry.ModTags;

/**
 * Путь осколка по блокам ({@link FragmentModel}): общий у сервера (стёкла, выбоины) и клиента (пыль и искры), чтобы
 * у обоих осколок останавливался в одном и том же блоке. Обход клеток по прямой (Amanatides–Woo), только по готовым
 * чанкам: неготовый чанк — осколок дальше не ведут (на сервере его чтение грузило бы чанк).
 * <ul>
 *   <li>стекло ({@link ModTags#SHATTERS}) осколок пробивает и летит дальше (теряет мало: оценка — без потери);</li>
 *   <li>то, у чего нет формы столкновений (трава, цветы, факелы), — пролетает;</li>
 *   <li>жидкость, листва и всё твёрдое — останавливают;</li>
 *   <li>клетку, где взрыв, не смотрят: к осколкам её блок уже снёс контактный взрыв (у клиента он ещё стоит).</li>
 * </ul>
 */
public final class FragmentTrace {
    private FragmentTrace() {}

    /** Что встретил осколок. */
    public interface Visitor {
        /** Пробил стекло в {@code pos} на пути {@code s} м. */
        default void glass(BlockPos pos, BlockState state, double s) {}

        /** Остановился в блоке {@code pos} на пути {@code s} м, войдя через грань со стороны {@code entry} (нормаль наружу). */
        void stop(BlockPos pos, BlockState state, double s, Vec3 point, net.minecraft.core.Direction entry);
    }

    /**
     * Провести осколок из {@code from} по {@code dir} (единичный) не дальше {@code maxS} м.
     *
     * @return путь до остановки, м; {@code maxS} — долетел, не встретив твёрдого; −1 — упёрся в неготовый чанк (дальше
     *         не известно)
     */
    public static double trace(Level level, Vec3 from, Vec3 dir, double maxS, Visitor visitor) {
        int x = floor(from.x), y = floor(from.y), z = floor(from.z);
        int sx = (int) Math.signum(dir.x), sy = (int) Math.signum(dir.y), sz = (int) Math.signum(dir.z);
        double tdx = sx == 0 ? Double.MAX_VALUE : Math.abs(1 / dir.x);
        double tdy = sy == 0 ? Double.MAX_VALUE : Math.abs(1 / dir.y);
        double tdz = sz == 0 ? Double.MAX_VALUE : Math.abs(1 / dir.z);
        double tx = sx == 0 ? Double.MAX_VALUE : ((sx > 0 ? x + 1 - from.x : from.x - x) * tdx);
        double ty = sy == 0 ? Double.MAX_VALUE : ((sy > 0 ? y + 1 - from.y : from.y - y) * tdy);
        double tz = sz == 0 ? Double.MAX_VALUE : ((sz > 0 ? z + 1 - from.z : from.z - z) * tdz);
        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
        int cx = Integer.MIN_VALUE, cz = Integer.MIN_VALUE;
        LevelChunk chunk = null;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        net.minecraft.core.Direction entry = net.minecraft.core.Direction.UP;
        double s = 0;
        while (true) {
            // шаг в следующую клетку
            if (tx <= ty && tx <= tz) {
                s = tx;
                x += sx;
                tx += tdx;
                entry = sx > 0 ? net.minecraft.core.Direction.WEST : net.minecraft.core.Direction.EAST;
            } else if (ty <= tz) {
                s = ty;
                y += sy;
                ty += tdy;
                entry = sy > 0 ? net.minecraft.core.Direction.DOWN : net.minecraft.core.Direction.UP;
            } else {
                s = tz;
                z += sz;
                tz += tdz;
                entry = sz > 0 ? net.minecraft.core.Direction.NORTH : net.minecraft.core.Direction.SOUTH;
            }
            if (s >= maxS || y < minY || y >= maxY) return maxS;
            if (x >> 4 != cx || z >> 4 != cz) {
                cx = x >> 4;
                cz = z >> 4;
                chunk = chunkNow(level, cx, cz);
            }
            if (chunk == null) return -1;
            m.set(x, y, z);
            BlockState st = chunk.getBlockState(m);
            if (st.isAir()) continue;
            if (st.is(ModTags.SHATTERS)) {
                visitor.glass(m.immutable(), st, s);
                continue;
            }
            if (st.getFluidState().isEmpty() && st.getCollisionShape(chunk, m).isEmpty()) continue;
            visitor.stop(m.immutable(), st, s, from.add(dir.scale(s)), entry);
            return s;
        }
    }

    /** Готовый чанк или null (на сервере — {@code getChunkNow}: не грузит и не ждёт загрузки). */
    static @Nullable LevelChunk chunkNow(Level level, int cx, int cz) {
        if (level instanceof ServerLevel server) return server.getChunkSource().getChunkNow(cx, cz);
        return level.getChunkSource().getChunk(cx, cz, false);
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
