package ua.zentix.incoming.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntBinaryOperator;

/**
 * Высота рельефа без ожидания: на сервере {@code Level.getHeight} для чанка, который загружен, но ещё не готов
 * к выдаче, ждёт его в {@code managedBlock} — и выполняет в это время чужие задачи загрузки (до сотен мс за вызов).
 * Здесь берётся только уже готовый чанк ({@code getChunkNow}); нет чанка — как у ванили, нижняя граница мира.
 */
public final class Terrain {
    private Terrain() {}

    /**
     * Чанк уже загружен и готов: на сервере — {@code getChunkNow}, а не {@code hasChunk}/{@code isLoaded}
     * (те верны и для чанка, который ещё грузится, и чтение из него ждёт загрузку).
     */
    public static boolean ready(Level level, int chunkX, int chunkZ) {
        if (level instanceof ServerLevel server) return server.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
        return level.hasChunk(chunkX, chunkZ);
    }

    public static boolean ready(Level level, BlockPos pos) {
        return ready(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * Чанк в памяти: полностью загруженный, опущенный ниже (у края видимости) или ждущий выгрузки, — пока не пришёл
     * его {@code ChunkEvent.Unload}; null — его нет в памяти или он ещё не бывал полностью загружен ({@code ChunkEvent.Load}
     * поставит его сам). Держатель, у которого не осталось тикетов, ваниль убирает из видимой карты чанков сразу, а сам
     * чанк выгружает позже, когда его можно сохранить ({@code ChunkMap.processUnloads} → {@code pendingUnloads} →
     * {@code scheduleUnload}); вернувшийся за это время тикет возвращает держатель из {@code pendingUnloads} с тем же
     * чанком и без нового {@code ChunkEvent.Load}. Поэтому «нет в видимой карте» ещё не значит «выгружен».
     * Читать из такого чанка его же данные (карта высот) можно — загрузки это не вызывает. AT: {@code ChunkMap.pendingUnloads},
     * только чтение.
     */
    @Nullable
    public static LevelChunk inMemory(ServerLevel level, long pos) {
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        ChunkHolder holder = chunkMap.getVisibleChunkIfPresent(pos);
        if (holder == null) holder = chunkMap.pendingUnloads.get(pos);
        return holder != null && holder.getChunkIfPresentUnchecked(ChunkStatus.FULL) instanceof LevelChunk chunk ? chunk : null;
    }

    /** Готовы чанк и все восемь соседей: цепочка обновлений от блока в нём (до 16 блоков) не выходит за готовые. */
    public static boolean neighbourhoodReady(Level level, int chunkX, int chunkZ) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) if (!ready(level, chunkX + dx, chunkZ + dz)) return false;
        }
        return true;
    }

    /** Готовы все чанки, которых касается квадрат со стороной {@code 2r} вокруг точки. */
    public static boolean readyAround(Level level, Vec3 centre, double r) {
        for (int cx = Mth.floor(centre.x - r) >> 4; cx <= Mth.floor(centre.x + r) >> 4; cx++) {
            for (int cz = Mth.floor(centre.z - r) >> 4; cz <= Mth.floor(centre.z + r) >> 4; cz++) {
                if (!ready(level, cx, cz)) return false;
            }
        }
        return true;
    }

    /**
     * Докуда отрезок {@code from → to} идёт по готовым чанкам: точка чуть до входа в первый неготовый чанк (или
     * {@code to}, если весь путь готов). Луч {@code Level.clip} читает каждый блок на пути, а на сервере чтение
     * незагруженного чанка грузит и генерирует его прямо в тике — длинный луч (прицел до 1024 блоков, нос снаряда)
     * вставал бы на секунды. На клиенте незагруженный чанк — пустой, обрезать нечего.
     */
    public static Vec3 readyUntil(Level level, Vec3 from, Vec3 to) {
        if (!(level instanceof ServerLevel)) return to;
        double t = readyFraction(from.x, from.z, to.x, to.z, (x, z) -> ready(level, x, z));
        return t >= 1 ? to : from.lerp(to, t);
    }

    /** Весь отрезок идёт по готовым чанкам (см. {@link #readyUntil}). */
    public static boolean readyAlong(Level level, Vec3 from, Vec3 to) {
        return !(level instanceof ServerLevel) || readyFraction(from.x, from.z, to.x, to.z, (x, z) -> ready(level, x, z)) >= 1;
    }

    /** Готов ли чанк (x, z). */
    @FunctionalInterface
    public interface ChunkReady {
        boolean test(int chunkX, int chunkZ);
    }

    /**
     * Доля отрезка (0..1) до первой неготовой колонки чанков на его пути. Колонки обходятся по сетке
     * ({@link GridWalk}): ни одна пересечённая колонка не пропускается, даже если отрезок срезает угол.
     * Конец берётся на {@link #EDGE} блока раньше границы, чтобы последний проверяемый блок остался в готовом чанке.
     */
    public static double readyFraction(double fromX, double fromZ, double toX, double toZ, ChunkReady ready) {
        double t = GridWalk.walk(fromX, fromZ, toX, toZ, 16, ready::test);
        return t >= 1 ? 1 : Math.max(0, t - EDGE / Math.max(1.0e-9, Math.hypot(toX - fromX, toZ - fromZ)));
    }

    private static final double EDGE = 0.01;

    /**
     * Доля отрезка {@code from → to} (0..1), на которой он впервые уходит под поверхность по картам высот
     * {@code MOTION_BLOCKING} из набора {@code allowed}; 1 — весь над ней. Колонка блоков, которую пересекает отрезок
     * ({@link GridWalk}), закрыта, если поверхность в ней выше нижней точки отрезка в этой колонке. Это проверка по
     * верху рельефа, а не по блокам: навес и пещеру она не видит. Где рельеф неизвестен (низ мира), — открыто.
     */
    public static double underRelief(Level level, Vec3 from, Vec3 to, Allowed allowed) {
        double dy = to.y - from.y;
        double[] hit = {1};
        GridWalk.walk(from.x, from.z, to.x, to.z, 1, (GridWalk.SpanVisitor) (x, z, t0, t1) -> {
            double low = from.y + dy * (dy < 0 ? t1 : t0);
            if (estimate(level, Heightmap.Types.MOTION_BLOCKING, x, z, allowed).y() > low) {
                hit[0] = t0;
                return false;
            }
            return true;
        });
        return hit[0];
    }

    /**
     * Первый воздух над картой высот (как {@link Level#getHeight}) — источник {@link Source#CHUNK} без выбора: для кода,
     * который сам знает, что чанк готов (район взрыва, очередь ядерки), или которому низ мира — верный ответ. «Где земля»
     * там, где чанк может быть не готов, — {@link #estimate}.
     */
    public static int height(Level level, Heightmap.Types type, int x, int z) {
        if (level instanceof ServerLevel server) {
            LevelChunk chunk = server.getChunkSource().getChunkNow(x >> 4, z >> 4);
            return chunk == null ? level.getMinBuildHeight() : chunk.getHeight(type, x & 15, z & 15) + 1;
        }
        return level.getHeight(type, x, z);
    }

    /** Откуда известна высота поверхности — от точного к грубому; {@link #estimate} берёт первый разрешённый. */
    public enum Source {
        /** Карта высот готового чанка. */
        CHUNK,
        /**
         * Карта высот готового чанка в мире с потолком (Незер): это верх потолка из коренной породы, а не земля. В мире
         * она годится для обхода препятствий, для полёта вне мира и точки на земле — нет.
         */
        CEILING,
        /**
         * Сохранённая карта высот чанка, который не готов: копия в памяти (чанк ждёт выгрузки или опущен у края
         * видимости) или файл чанка на диске, прочитанный в потоке ввода-вывода чанков без загрузки чанка
         * ({@link TerrainSurvey}). Точна, как карта высот чанка на момент записи; ответ — не сразу, а когда съёмка
         * дочитает его (тик не ждёт). Только в мире без потолка.
         */
        DISK,
        /** Верх по карте клиента, на которой игрок выбрал место (Distant Horizons или чанки клиента), — подсказка приказа. */
        CLIENT_MAP,
        /**
         * Рельеф генератора мира ({@code ChunkGenerator.getBaseHeight}: шум без загрузки чанка, без деревьев и построек;
         * ≈3 мс на точку — только для приказа, не для тика), не ниже его уровня моря (над водой поверхность — сама вода).
         * У мира, построенного не этим генератором, он далёк от поверхности: город с карты мира (63 под крышей на 107),
         * мир 1.17, поднятый до 1.21 (генератор даёт дно мира);
         * уровень моря держит оценку хотя бы у суши. В съёмке рельефа ({@link TerrainSurvey}, набор с фоновыми
         * источниками) — для чанка, которого нет на диске или он там недогенерирован: считается в фоне, по узлам через
         * {@link TerrainSurvey#GENERATOR_STEP} блоков.
         */
        GENERATOR,
        /**
         * Уровень моря генератора — дёшево, для тика: ниже него суша почти не бывает. Не {@code Level.getSeaLevel}:
         * в 1.21.1 он всегда 63, а у плоского мира море −63.
         */
        SEA,
        /** Ни один разрешённый источник не знает: низ мира, как у ванили для незагруженного чанка. */
        UNKNOWN
    }

    /** Высота поверхности (первый воздух над ней) и откуда она известна. */
    public record Surface(int y, Source source) {
        /** Высоту дал какой-то источник (не {@link Source#UNKNOWN}). */
        public boolean known() {
            return source != Source.UNKNOWN;
        }
    }

    /**
     * Какие источники вызывающему разрешены и подсказка карты клиента, если она есть. Цена — в самом наборе:
     * {@link #CHUNK}, {@link #FLIGHT} и {@link #RELIEF} годятся для тика, {@link #ORDER} читает генератор — только для приказа.
     * Набор с фоновыми источниками ({@link #RELIEF}) берёт {@link Source#DISK} и {@link Source#GENERATOR} только из
     * готовой съёмки ({@link TerrainSurvey}) и заказывает её, если ответа ещё нет: сам он ни диска, ни генератора не ждёт.
     */
    public static final class Allowed {
        /** В мире, в тике: только готовый чанк (и потолок, где он есть), иначе низ мира. */
        public static final Allowed CHUNK = new Allowed(Set.of(Source.CHUNK, Source.CEILING), Optional.empty(), false);
        /** Полёт вне мира, в тике: готовый чанк, иначе уровень моря; потолок — не земля. */
        public static final Allowed FLIGHT = new Allowed(Set.of(Source.CHUNK, Source.SEA), Optional.empty(), false);
        /** Приказ (раз на пуск): готовый чанк, иначе рельеф генератора. */
        public static final Allowed ORDER = new Allowed(Set.of(Source.CHUNK, Source.CEILING, Source.GENERATOR), Optional.empty(), false);
        /**
         * Рельеф для полёта, в тике, в мире и вне его: готовый чанк (и потолок, где он есть), иначе съёмка — карта
         * высот с диска или из памяти, у чанка, которого на диске нет, — рельеф генератора, посчитанный в фоне; пока
         * съёмка не ответила — низ мира, как у ванили для незагруженного чанка.
         */
        public static final Allowed RELIEF = new Allowed(Set.of(Source.CHUNK, Source.CEILING, Source.DISK, Source.GENERATOR), Optional.empty(), true);

        /** Вне мира потолок Незера — неизвестный рельеф, как у FLIGHT; верх коренной породы не земля. */
        public static final Allowed VIRTUAL_RELIEF = new Allowed(Set.of(Source.CHUNK, Source.DISK, Source.GENERATOR), Optional.empty(), true);

        private final Set<Source> sources;
        private final Optional<Integer> map;
        /** {@link Source#DISK} и {@link Source#GENERATOR} — только из готовой съёмки ({@link TerrainSurvey}). */
        private final boolean survey;

        /** Снаружи — только наборы выше и {@link #withMap}. */
        Allowed(Set<Source> sources, Optional<Integer> map, boolean survey) {
            if (sources.contains(Source.CLIENT_MAP) != map.isPresent()) throw new IllegalArgumentException("CLIENT_MAP only with a map hint");
            if (sources.contains(Source.DISK) && !survey) throw new IllegalArgumentException("DISK only from the background survey");
            this.sources = Set.copyOf(sources);
            this.map = map;
            this.survey = survey;
        }

        /**
         * Те же источники и ещё {@link Source#CLIENT_MAP}: {@code top} — первый воздух над землёй по карте клиента;
         * пустая подсказка или верх, чей блок вне высот мира, не в счёт.
         */
        public Allowed withMap(Optional<Integer> top) {
            if (top.isEmpty()) return this;
            Set<Source> s = EnumSet.of(Source.CLIENT_MAP);
            s.addAll(sources);
            return new Allowed(s, top, survey);
        }

        boolean permits(Source source) {
            return sources.contains(source);
        }

        /** Фоновые источники: {@link Source#DISK} и {@link Source#GENERATOR} — из съёмки, не в вызове. */
        boolean survey() {
            return survey;
        }

        /** Подсказка карты клиента (есть, только если разрешён {@link Source#CLIENT_MAP}). */
        Optional<Integer> map() {
            return map;
        }
    }

    /**
     * Высота поверхности в колонке (x, z) — единственный ответ мода на «где земля»: первый источник из {@code allowed}
     * по порядку {@link Source}, который её знает. Чанк не грузится: готовность — {@link #ready}. На клиенте генератора
     * нет — только чанк и карта; там же нет карт высот {@code …_NO_LEAVES} (клиенту приходят лишь
     * {@code MOTION_BLOCKING} и {@code WORLD_SURFACE}).
     * Не высота цели: цель бывает в воздухе (игрок в полёте, аппарат), а поверхность под ней — нет.
     */
    public static Surface estimate(Level level, Heightmap.Types type, int x, int z, Allowed allowed) {
        return choose(new LevelColumn(level, type, x >> 4, z >> 4), allowed).at(x, z);
    }

    /**
     * MOTION_BLOCKING heights for one synchronous sensor measurement. Both individual estimates and chunk views
     * use {@link #choose}. Views and heights must not outlive the measurement; background answers still become
     * visible only when TerrainSurvey.publish runs at the end of the world tick.
     */
    public static Relief relief(Level level, Allowed allowed) {
        if (allowed != Allowed.RELIEF && allowed != Allowed.VIRTUAL_RELIEF)
            throw new IllegalArgumentException("Relief survey requires a relief source set");
        return new Relief.Survey((cx, cz) ->
                choose(new LevelColumn(level, Heightmap.Types.MOTION_BLOCKING, cx, cz), allowed).height());
    }

    /** A selected source and its heights, resolved lazily at world column coordinates. */
    record View(IntBinaryOperator height, Source source) {
        Surface at(int x, int z) {
            return new Surface(height.applyAsInt(x, z), source);
        }

        static View constant(int height, Source source) {
            return new View((x, z) -> height, source);
        }
    }

    /** Available sources in one chunk; the generator is expensive and must only run when selected. */
    interface Column {
        boolean ready();

        /** First air above the ready chunk's heightmap, at world coordinates. */
        int chunkHeight(int x, int z);

        boolean ceiling();

        boolean hasGenerator();

        int sea();

        int generatorBase(int x, int z);

        boolean outside(int y);

        int bottom();

        /** Survey view, or null if pending, unavailable on the client, or excluded by a ceiling. */
        @Nullable
        default View surveyed() {
            return null;
        }
    }

    /** The single source-selection order for individual estimates and whole-chunk relief views. */
    static View choose(Column c, Allowed allowed) {
        if (c.ready()) {
            Source chunk = c.ceiling() ? Source.CEILING : Source.CHUNK;
            if (allowed.permits(chunk)) return new View(c::chunkHeight, chunk);
        }
        if (allowed.survey()) {
            View s = c.surveyed();
            if (s != null && allowed.permits(s.source())) return s;
        }
        if (allowed.permits(Source.CLIENT_MAP)) {
            int top = allowed.map().orElseThrow();
            if (!c.outside(top - 1)) return View.constant(top, Source.CLIENT_MAP);
        }
        if (c.hasGenerator()) {
            if (allowed.permits(Source.GENERATOR) && !allowed.survey())
                return new View((x, z) -> Math.max(c.sea(), c.generatorBase(x, z)), Source.GENERATOR);
            if (allowed.permits(Source.SEA)) return View.constant(c.sea(), Source.SEA);
        }
        return View.constant(c.bottom(), Source.UNKNOWN);
    }

    private static final class LevelColumn implements Column {
        private final Level level;
        private final Heightmap.Types type;
        private final int cx, cz;
        @Nullable
        private LevelChunk readyChunk;

        LevelColumn(Level level, Heightmap.Types type, int cx, int cz) {
            this.level = level;
            this.type = type;
            this.cx = cx;
            this.cz = cz;
        }

        @Override
        public boolean ready() {
            if (level instanceof ServerLevel server) {
                readyChunk = server.getChunkSource().getChunkNow(cx, cz);
                return readyChunk != null;
            }
            return level.hasChunk(cx, cz);
        }

        @Override
        public int chunkHeight(int x, int z) {
            if (level instanceof ServerLevel)
                return readyChunk == null ? level.getMinBuildHeight() : readyChunk.getHeight(type, x & 15, z & 15) + 1;
            return level.getHeight(type, x, z);
        }

        @Override
        public boolean ceiling() {
            return level.dimensionType().hasCeiling();
        }

        @Override
        public boolean hasGenerator() {
            return level instanceof ServerLevel;
        }

        @Override
        public int sea() {
            return generator().getSeaLevel();
        }

        @Override
        public int generatorBase(int x, int z) {
            ServerLevel server = (ServerLevel) level;
            return generator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, server, server.getChunkSource().randomState());
        }

        @Override
        @Nullable
        public View surveyed() {
            if (!(level instanceof ServerLevel server) || ceiling()) return null;
            return TerrainSurvey.get(server).view(server, type, cx, cz);
        }

        @Override
        public boolean outside(int y) {
            return level.isOutsideBuildHeight(y);
        }

        @Override
        public int bottom() {
            return level.getMinBuildHeight();
        }

        private ChunkGenerator generator() {
            return ((ServerLevel) level).getChunkSource().getGenerator();
        }
    }
}
