package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.util.PoiRecords;

/**
 * Общее для разрушений ядерного удара: замена блока через мир (блок-сущности, воронка), счётчик пожаров подрыва,
 * световой импульс с тенью. Что волна делает со столбцом — {@link RuinPlanner}, подмена чанка — {@link RuinPlan}.
 */
public final class ColumnScar {
    /** Без каскада обновлений соседей и без выпадения предметов; клиенты получают изменения пачками по секциям. */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private ColumnScar() {}

    /** Счётчики на подрыв: пожаров не больше заданного. */
    public static final class Budget {
        /** Поджигает ли подрыв (свежий — да, забытый — только выжигает). */
        final boolean ignites;
        int fires;
        /**
         * Огонь слоя вспышки в счёте, по чанкам, где руины ещё не встали ({@code RuinPlan.write}): руины поверх слоя без
         * его плана ({@link FlashScorch#marked}: руины подрыва забыты после простоя) не знают, какой огонь чанка в счёте, — он
         * уходит из счёта весь, а входит тот, что горит дальше. Счётчик после перезапуска — новый, слоя чанка он не брал.
         */
        final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap layer = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

        public Budget(boolean ignites) {
            this.ignites = ignites;
        }

        /** Для проверок: пожаров в счёте. */
        public int firesForTest() {
            return fires;
        }

        /** Для проверок: огня слоя вспышки чанка в счёте ({@link #layer}). */
        public int layerForTest(net.minecraft.world.level.ChunkPos chunk) {
            return layer.get(chunk.toLong());
        }
    }

    /**
     * Заменить блок без выпадения и без осиротевших данных блок-сущности. Все замены блоков выжигания и воронки
     * идут здесь.
     * <p>
     * Сперва блок-сущность берётся через {@link ServerLevel#getBlockEntity}: у чанка, который ещё ни разу не тикал
     * (свежая генерация у края прорисовки), она лежит отложенными данными — после генерации это заглушка «DUMMY»
     * у каждой кровати, колокола, сундука деревни ({@code WorldGenRegion.setBlock}), после загрузки — сохранённые
     * данные с {@code keepPacked}. {@code onRemove} старого блока снимает только живую блок-сущность, отложенные
     * данные остаются при воздухе или воде, и при первом тике или сохранении чанка ваниль пишет «Tried to load
     * a DUMMY block entity … found air». Запрос поднимает их в живую блок-сущность, и замена её снимает.
     * У блок-сущности сооружения или улья свежая генерация оставляет и живую сущность, и заглушку на том же месте
     * ({@code ProtoChunk.setBlockEntity} заглушку не снимает, ваниль чистит их первым тиком чанка или сохранением, а зона
     * за волной грузит чанки без тика): запрос отдаёт живую, и заглушка снимается рядом с ней — иначе сохранение
     * чанка подняло бы её над воздухом (21 место в 600–840 блоках от подрыва 15 кт).
     * <p>
     * {@code UPDATE_SUPPRESS_DROPS} не спасает от содержимого контейнеров: сундук, бочка, печь высыпают его
     * в {@code onRemove} — в деревне это тысячи предметов на земле, которые потом тикают. Как {@code /setblock}
     * и {@code /fill}: {@link Clearable#tryClear} очищает блок-сущность до замены.
     * <p>
     * Сундук генерации, который ещё не открывали, хранит не предметы, а таблицу добычи: она разворачивается при первом
     * чтении содержимого — и {@code onRemove} читает его, чтобы высыпать. Карта исследователя в добыче ищет
     * сооружение и рисует биомы прямо в потоке сервера (на стенде — 567 мс за один сундук). Волна испаряет сундук,
     * добыча не выпадает: таблица снимается до замены.
     */
    public static void replace(ServerLevel level, BlockPos pos, BlockState old, BlockState with) {
        replace(level, pos, old, with, FLAGS);
    }

    /** То же с флагами {@code flags} ({@code setBlock}): с обновлением соседей сыпучее над местом падает. */
    public static void replace(ServerLevel level, BlockPos pos, BlockState old, BlockState with, int flags) {
        if (old.hasBlockEntity()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof RandomizableContainer loot) loot.setLootTable(null);
            Clearable.tryClear(be);
            level.getChunkAt(pos).pendingBlockEntities.remove(pos);
        }
        if (!old.is(with.getBlock())) PoiRecords.recordIfMissing(level, pos, old);
        level.setBlock(pos, with, flags);
    }

    /** Световой импульс в точке с учётом тени от рельефа и построек, кал/см². */
    static double lit(Level level, Detonation d, BlockPos pos) {
        Vec3 p = Vec3.atCenterOf(pos).add(0, 0.5, 0);
        return ThermalShadow.visible(level, d.burst(), p) ? d.fluence(p) : 0;
    }
}
