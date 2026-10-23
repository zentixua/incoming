package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.entity.CruiseMissileEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.guidance.Route;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.FlightTickets;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.util.TerrainSurvey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * Съёмка рельефа без загрузки чанков ({@link TerrainSurvey}, источник {@link Terrain.Source#DISK} и фоновый
 * {@link Terrain.Source#GENERATOR}): высота незагруженного чанка — с его файла, у чанка, которого на диске нет, — от
 * генератора; вопрос в тике не ждёт ни диска, ни генератора и ничего не грузит; крылатая ракета, летящая вне мира,
 * видит холм перед целью, которого нет в мире, перелетает его и пикирует за ним на цель.
 * <p>
 * Места — далеко за площадкой (вокруг ничего не загружено), у каждого теста своё и в тысяче блоков и больше от мест
 * {@code StrikeGameTests.FarSite}.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TerrainSurveyGameTests {
    private static final BlockPos CENTER = new BlockPos(32, 11, 32);

    private TerrainSurveyGameTests() {}

    private enum Site {
        DISK(-6400, 0),
        GENERATOR(0, -6400),
        NO_WAIT(-6400, -6400),
        HILL(-6400, 6400);

        private final int dx, dz;

        Site(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        BlockPos at(GameTestHelper h) {
            return h.absolutePos(CENTER).offset(dx, 0, dz);
        }
    }

    /** Чанка нет в памяти (и в очереди выгрузки): записанное им уже видно чтению с диска. */
    private static boolean unloaded(ServerLevel level, List<ChunkPos> chunks) {
        for (ChunkPos c : chunks) if (Terrain.inMemory(level, c.toLong()) != null
                || level.getChunkSource().chunkMap.getVisibleChunkIfPresent(c.toLong()) != null
                || level.getChunkSource().chunkMap.pendingUnloads.containsKey(c.toLong())) return false;
        return true;
    }

    /** Рельеф генератора узла, как его считает съёмка: первый воздух не ниже уровня моря. */
    private static int generatorNode(ServerLevel level, int x, int z) {
        ChunkGenerator g = level.getChunkSource().getGenerator();
        return Math.max(g.getSeaLevel(), g.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState()));
    }

    /**
     * Чанк сгенерирован, на нём столб на 40 блоков, и он выгружен (записан). Уходя из памяти, он оставил съёмке свою
     * карту: ответ сразу, без пробела. Забыв её, съёмка читает файл: высота колонки столба — с диска, точно, как карта
     * высот чанка; соседняя колонка — своя поверхность; первый вопрос ответа в тике не даёт. Вопрос чанк не грузит.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "survey_disk", skyAccess = true)
    public static void diskHeightOfUnloadedChunk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos c = new ChunkPos(Site.DISK.at(h));
        level.getChunk(c.x, c.z);
        int x = c.getMiddleBlockX(), z = c.getMiddleBlockZ();
        int surface = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, x, z);
        int beside = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, x + 3, z);
        for (int y = surface; y < surface + 40; y++) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
        int top = surface + 40;
        TerrainSurvey survey = TerrainSurvey.get(level);
        // До выгрузки не спрашиваем карту: MOTION_BLOCKING должна сохраняться и без прежнего полёта/вопроса.
        int[] stage = {0};
        h.onEachTick(() -> {
            if (stage[0] == 0) {
                if (!unloaded(level, List.of(c))) return;
                // выгружен; карту, которую он оставил съёмке при выгрузке, забыть — проверяется чтение файла.
                // Первый вопрос заказывает чтение и ответа сразу не даёт
                Terrain.Surface left = survey.at(level, Heightmap.Types.MOTION_BLOCKING, x, z);
                h.assertTrue(left != null && left.source() == Terrain.Source.DISK && left.y() == top, "выгруженный чанк не оставил карту: " + left);
                survey.forget(c.toLong());
                h.assertTrue(survey.at(level, Heightmap.Types.MOTION_BLOCKING, x, z) == null, "съёмка ответила в тике вопроса: она ждала диск");
                stage[0] = 1;
            }
            h.assertTrue(unloaded(level, List.of(c)), "вопрос загрузил чанк");
        });
        h.succeedWhen(() -> {
            h.assertTrue(stage[0] == 1, "чанк не выгрузился");
            Terrain.Surface s = survey.at(level, Heightmap.Types.MOTION_BLOCKING, x, z);
            h.assertTrue(s != null, "нет ответа с диска");
            h.assertTrue(s.source() == Terrain.Source.DISK && s.y() == top, "столб: " + s + " вместо " + top + " с диска");
            Terrain.Surface next = Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING, x + 3, z, Terrain.Allowed.RELIEF);
            h.assertTrue(next.source() == Terrain.Source.DISK && next.y() == beside, "соседняя колонка: " + next + " вместо " + beside);
            h.assertTrue(unloaded(level, List.of(c)), "чанк загружен");
        });
    }

    /**
     * Чанка на диске нет (никогда не генерировался): рельеф генератора, посчитанный в фоне, — наибольший из четырёх
     * узлов клетки {@link TerrainSurvey#GENERATOR_STEP}; чанк при этом не появляется в памяти.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "survey_generator", skyAccess = true)
    public static void generatorWhereNothingOnDisk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos c = new ChunkPos(Site.GENERATOR.at(h));
        int x = c.getMinBlockX() + 5, z = c.getMinBlockZ() + 11;
        int step = TerrainSurvey.GENERATOR_STEP;
        int x0 = Math.floorDiv(x, step) * step, z0 = Math.floorDiv(z, step) * step;
        int expected = Math.max(Math.max(generatorNode(level, x0, z0), generatorNode(level, x0 + step, z0)),
                Math.max(generatorNode(level, x0, z0 + step), generatorNode(level, x0 + step, z0 + step)));
        TerrainSurvey survey = TerrainSurvey.get(level);
        h.assertTrue(survey.at(level, Heightmap.Types.MOTION_BLOCKING, x, z) == null, "съёмка ответила в тике вопроса: генератор считался в тике");
        h.succeedWhen(() -> {
            Terrain.Surface s = survey.at(level, Heightmap.Types.MOTION_BLOCKING, x, z);
            h.assertTrue(s != null, "нет ответа генератора");
            h.assertTrue(s.source() == Terrain.Source.GENERATOR && s.y() == expected, "генератор: " + s + " вместо " + expected);
            h.assertTrue(unloaded(level, List.of(c)), "чанк появился в памяти");
        });
    }

    /** Чанков в замере {@link #surveyNeverWaitsInTick}: квадрат 16×16. */
    private static final int NO_WAIT_SIDE = 16;
    /**
     * Наибольшее время вопросов о {@code NO_WAIT_SIDE²} незнакомых чанках в тике, мс — с запасом на общий раннер CI:
     * заказ — несколько записей в карты и задача потоку ввода-вывода. Чтение файла чанка в тике (~0,1–1 мс на чанк) или
     * генератор (~3 мс на узел, 9 узлов на чанк в обычном мире) стоили бы сотни мс и секунды.
     */
    private static final long NO_WAIT_LIMIT_MS = 200;

    /**
     * Вопросы о 256 незнакомых чанках (половина записана на диск, половины на диске нет) в одном тике: ни один ответ не
     * приходит в тике вопроса (съёмка не ждёт ни диска, ни генератора), время вопросов — малое, ни один чанк не
     * загружен ни тогда, ни потом, все ответы приходят в следующих тиках.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "survey_no_wait", skyAccess = true)
    public static void surveyNeverWaitsInTick(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos base = new ChunkPos(Site.NO_WAIT.at(h));
        List<ChunkPos> all = new ArrayList<>();
        for (int dx = 0; dx < NO_WAIT_SIDE; dx++)
            for (int dz = 0; dz < NO_WAIT_SIDE; dz++) all.add(new ChunkPos(base.x + dx, base.z + dz));
        // половина — на диске (сгенерированы и выгружены), половина — нет
        List<ChunkPos> saved = all.subList(0, all.size() / 2);
        for (ChunkPos c : saved) level.getChunk(c.x, c.z);
        TerrainSurvey survey = TerrainSurvey.get(level);
        int[] stage = {0};
        long[] took = {0};
        h.onEachTick(() -> {
            if (stage[0] == 0) {
                if (!unloaded(level, all)) return;
                for (ChunkPos c : all) survey.forget(c.toLong());
                long t0 = System.nanoTime();
                int answered = 0;
                for (ChunkPos c : all) if (survey.at(level, Heightmap.Types.MOTION_BLOCKING, c.getMiddleBlockX(), c.getMiddleBlockZ()) != null) answered++;
                took[0] = System.nanoTime() - t0;
                Incoming.LOG.info(String.format(Locale.ROOT, "Замер съёмки рельефа: %d вопросов о незнакомых чанках в тике — %.2f мс, ответов в тике %d",
                        all.size(), took[0] / 1e6, answered));
                h.assertTrue(answered == 0, "съёмка ответила в тике вопроса " + answered + " раз: ждала диск или генератор");
                // заказы в пути: при чтении в тике их бы не осталось (половина чанков ждёт ещё и генератора)
                h.assertTrue(survey.pendingCount() > 0, "заказов в пути нет: съёмка дочитала в тике вопроса");
                h.assertTrue(took[0] / 1_000_000 < NO_WAIT_LIMIT_MS, String.format(Locale.ROOT, "вопросы в тике заняли %.1f мс", took[0] / 1e6));
                stage[0] = 1;
            }
            h.assertTrue(unloaded(level, all), "съёмка загрузила чанк");
        });
        h.succeedWhen(() -> {
            h.assertTrue(stage[0] == 1, "чанки не выгрузились");
            int disk = 0, generator = 0;
            for (ChunkPos c : all) {
                Terrain.Surface s = survey.at(level, Heightmap.Types.MOTION_BLOCKING, c.getMiddleBlockX(), c.getMiddleBlockZ());
                h.assertTrue(s != null, "нет ответа о чанке " + c);
                if (s.source() == Terrain.Source.DISK) disk++;
                else if (s.source() == Terrain.Source.GENERATOR) generator++;
            }
            h.assertTrue(disk == saved.size() && generator == all.size() - saved.size(),
                    "с диска " + disk + " (записано " + saved.size() + "), генератор " + generator);
        });
    }

    /** Район цели теста — с первого тика, как у {@code StrikeGameTests.returnsUnderOwnTerrain}: возврат в мир всегда у края района. */
    private static final TicketType<ChunkPos> AIM_AREA = TicketType.create("incoming_test_survey_aim", Comparator.comparingLong(ChunkPos::toLong));
    /** Гребень холма — за столько блоков до цели против курса: за краем района цели (±72 блока загружено). */
    private static final int HILL_CREST = 140;
    /** Высота холма над землёй: выше бреющего полёта над целью (+12) и горки (+32). */
    private static final int HILL_HEIGHT = 45;
    /** Полуширина холма поперёк курса, блоков. */
    private static final int HILL_HALF_WIDTH = 16;
    /** Пуск — за столько блоков до цели. */
    private static final int MISSILE_START = 600;
    /** Ближе к цели по горизонтали — взрыв у цели. */
    private static final double BURST_NEAR_AIM = 5;

    /** Высота холма над землёй в {@code u} блоках от гребня по курсу: склоны 1 : 1, гребень 4 блока. */
    private static int hill(int u) {
        return Math.max(0, HILL_HEIGHT - Math.max(0, Math.abs(u) - 2));
    }

    /**
     * Крылатая ракета вне мира (у цели и за ней никого нет, полосы подлёта нет) идёт на цель на ровном поле, а за 140
     * блоков до неё поперёк пути — холм на 45 блоков, которого нет в мире: его чанки записаны на диск. Без съёмки ракета
     * вне мира рельефа не знала и шла бы на 12 блоках над целью — сквозь холм. Теперь она видит его с диска,
     * перелетает с запасом над гребнем и пикирует за ним: взрыв — у цели. Полёт — в темпе игры: ответ диска приходит
     * между тиками, как на сервере.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "survey_missile_hill", skyAccess = true)
    public static void missileDivesOverHillFromDisk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos aimChunk = new ChunkPos(Site.HILL.at(h));
        level.getChunk(aimChunk.x, aimChunk.z);
        int ax = aimChunk.getMiddleBlockX(), az = aimChunk.getMiddleBlockZ();
        int ground = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, ax, az);
        Vec3 aim = new Vec3(ax + 0.5, ground, az + 0.5);
        // холм поперёк курса (+x): чанки — сразу, блоки — столбами от земли
        int crest = ax - HILL_CREST;
        List<ChunkPos> hillChunks = new ArrayList<>();
        for (int x = crest - HILL_HEIGHT - 2; x <= crest + HILL_HEIGHT + 2; x++)
            for (int z = az - HILL_HALF_WIDTH; z <= az + HILL_HALF_WIDTH; z++) {
                ChunkPos c = new ChunkPos(new BlockPos(x, 0, z));
                if (!hillChunks.contains(c)) {
                    hillChunks.add(c);
                    level.getChunk(c.x, c.z);
                }
                int top = hill(x - crest);
                for (int y = ground; y < ground + top; y++) level.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), 2);
            }
        Vec3[] burst = {null};
        Consumer<ExplosionEvent.Start> onBlast = e -> {
            Vec3 c = e.getExplosion().center();
            if (burst[0] == null && e.getLevel() == level && Math.hypot(c.x - aim.x, c.z - aim.z) < 16) burst[0] = c;
        };
        NeoForge.EVENT_BUS.addListener(onBlast);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(onBlast));
        UUID[] id = {null};
        Vec3[] last = {null};
        // наименьший запас над холмом и где; полёт вне мира над холмом
        double[] worst = {Double.POSITIVE_INFINITY};
        String[] where = {""};
        long[] paced = {0};
        h.onEachTick(() -> {
            if (id[0] == null) {
                // холм записан на диск и выгружен: район цели (его тикет держит в памяти и чанки на 15 чанков вокруг —
                // ниже полной загрузки, поэтому он берётся только теперь; холм оттуда — недогруженные копии с диска,
                // не готовые и не полные) и пуск
                if (!unloaded(level, hillChunks)) return;
                // карту, которую холм оставил съёмке при выгрузке, забыть: ракета читает его файл
                TerrainSurvey survey = TerrainSurvey.get(level);
                for (ChunkPos c : hillChunks) survey.forget(c.toLong());
                for (int dx = -FlightTickets.DISTANCE; dx <= FlightTickets.DISTANCE; dx++)
                    for (int dz = -FlightTickets.DISTANCE; dz <= FlightTickets.DISTANCE; dz++) level.getChunk(aimChunk.x + dx, aimChunk.z + dz);
                level.getChunkSource().addRegionTicket(AIM_AREA, aimChunk, FlightTickets.DISTANCE, aimChunk);
                StrikeGameTests.afterTest(h, () -> level.getChunkSource().removeRegionTicket(AIM_AREA, aimChunk, FlightTickets.DISTANCE, aimChunk));
                CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(level);
                // как пуск издалека (StrikeService.fromAfar): над целью на 12 блоков
                m.launch(aim.add(-MISSILE_START, 12, 0), new Target.Point(aim), aim, null);
                m.setRoute(Route.direct());
                VirtualFlights.launch(level, m);
                id[0] = m.getUUID();
                paced[0] = System.nanoTime();
                return;
            }
            // темп игры с пуска: съёмка отвечает между тиками, как на сервере
            long deadline = paced[0] + 50_000_000L, left;
            while ((left = deadline - System.nanoTime()) > 0) LockSupport.parkNanos(left);
            paced[0] = System.nanoTime();
            for (ChunkPos c : hillChunks) h.assertFalse(Terrain.ready(level, c.x, c.z), "чанк холма загружен: " + c);
            StrikeProjectile p = flight(level, id[0]);
            if (p == null) return;
            last[0] = p.position();
            int u = (int) Math.floor(p.getX()) - crest;
            if (Math.abs(u) <= HILL_HEIGHT + 2) {
                double clear = p.getY() - (ground + hill(u));
                if (clear < worst[0]) {
                    worst[0] = clear;
                    where[0] = String.format(Locale.ROOT, "%d блоков от гребня, y %.1f, вне мира %s, фаза %s", u, p.getY(), p.isVirtual(), p.flightPhase());
                }
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(id[0] != null, "холм не выгрузился");
            h.assertFalse(flight(level, id[0]) != null, "ракета ещё летит, до цели " + (last[0] == null ? "?" : (int) last[0].distanceTo(aim)));
            Incoming.LOG.info("Ракета над холмом с диска: наименьший запас {} ({})", String.format(Locale.ROOT, "%.1f", worst[0]), where[0]);
            h.assertTrue(worst[0] > 0, "ракета прошла сквозь холм: запас " + String.format(Locale.ROOT, "%.1f", worst[0]) + " (" + where[0] + ")");
            h.assertTrue(burst[0] != null, "ракета убрана без взрыва у цели, последний раз у " + (last[0] == null ? "?" : last[0].subtract(aim)));
            Vec3 off = burst[0].subtract(aim);
            h.assertTrue(Math.hypot(off.x, off.z) < BURST_NEAR_AIM, "взрыв не у цели: " + off + " от неё");
        });
    }

    private static StrikeProjectile flight(ServerLevel level, UUID id) {
        return VirtualFlights.get(level).flights().stream().filter(v -> v.getUUID().equals(id)).findFirst()
                .orElseGet(() -> level.getEntity(id) instanceof StrikeProjectile e && !e.isRemoved() ? e : null);
    }
}
