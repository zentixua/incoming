package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.ThermalModel;
import ua.zentix.incoming.nuclear.model.Yield;
import ua.zentix.incoming.nuclear.world.ColumnScar;
import ua.zentix.incoming.nuclear.world.FlashScorch;
import ua.zentix.incoming.nuclear.world.NuclearWorld;
import ua.zentix.incoming.nuclear.world.RuinPlan;
import ua.zentix.incoming.nuclear.world.RuinPlanner;
import ua.zentix.incoming.nuclear.world.RuinWorkers;
import ua.zentix.incoming.nuclear.world.ScarQueue;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * След светового импульса на земле: выжженная трава, гарь, сгоревшие растения и опалённые биомы — ступенями от центра к
 * краю, с рваным краем (кривые поражения с шумом мира), а не кольцом по порогу.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ScorchGameTests {
    private ScorchGameTests() {}

    private static final ResourceKey<Biome> SINGED = key("singed"), SCORCHED = key("scorched"), CHARRED = key("charred");
    /**
     * Эпицентр — у западного края площадки, на уровне её земли (блок структуры под шаблоном: дёрн шаблона — y 11, верх
     * — 12): стена барьеров вокруг площадки иначе закрывала бы ближнюю землю от шара.
     */
    private static final BlockPos GROUND_ZERO = new BlockPos(2, 12, 32);
    /** Кольца по расстоянию от эпицентра, блоки: от 0 до 72. */
    private static final int RING = 8, RINGS = 9, FIRST = 0;
    /** Высота проверки биома под землёй: клетка там целиком ниже {@code ScorchedBiomes.BELOW} от верха дёрна. */
    private static final int DEEP = -1;

    private static ResourceKey<Biome> key(String name) {
        return ResourceKey.create(Registries.BIOME, Incoming.id(name));
    }

    /**
     * 15 кт на оптимальной высоте в масштабе, где площадка — от эпицентра (свет ~100 кал/см², дёрн содран) до ~3.5 км
     * (~3 кал/см²). Дёрн выгорает у центра и уцелел у края, а между — пятнами (кольцо, где есть и трава, и земля); гарь —
     * только ближе к центру; трава и цветы над землёй сгорели у центра (верхние половины высоких — вместе с нижними,
     * от части остался сухой куст), у края стоят. Биом — ступенями от обугленного к опалённому, моря-реки, пустыня и
     * снежные равнины не тронуты, под землёй — тоже; в данных чанка — новые биомы.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_scorch", skyAccess = true)
    public static void scorchFadesOutwardInPatches(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // биомы: ближние ряды — равнина (её свет меняет), дальние полосы — снег, река, пустыня (их — нет)
        fillBiome(h, 0, 39, Biomes.PLAINS);
        fillBiome(h, 40, 47, Biomes.SNOWY_PLAINS);
        fillBiome(h, 48, 55, Biomes.RIVER);
        fillBiome(h, 56, 63, Biomes.DESERT);
        plants(h);
        // биом в 12 блоках под землёй (под камнем площадки) — до удара; равнина ли у земли: клетки биома — 4×4 по сетке
        // мира, а площадка стоит как придётся — у границы полос в «равнинном» ряду бывает клетка снега
        int[] deep = new int[64 * 64];
        for (int x = 0; x < 64; x += 4) for (int z = 0; z < 64; z += 4) deep[x * 64 + z] = biomeAt(h, x, DEEP, z).hashCode();
        boolean[] plains = new boolean[64 * 64];
        for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) plains[x * 64 + z] = biomeAt(h, x, 12, z).is(Biomes.PLAINS);
        double scale = 0.0182;
        Detonation d = NuclearGameTests.detonation(h, GROUND_ZERO, Yield.optimalBurstHeight(15) * scale, 15, (float) scale);
        NuclearGameTests.scarAll(h, d, new ColumnScar.Budget(true));

        int[] grass = new int[RINGS], burnt = new int[RINGS], charred = new int[RINGS], plants = new int[RINGS], plantsLeft = new int[RINGS];
        int[] cells = new int[RINGS], levels = new int[RINGS];
        int bushes = 0;
        double[] fluence = new double[RINGS], psi = new double[RINGS];
        int[] coarse = new int[RINGS], seen = new int[RINGS];
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                int ring = (int) ((Math.hypot(x - GROUND_ZERO.getX(), z - GROUND_ZERO.getZ()) - FIRST) / RING);
                if (ring < 0 || ring >= RINGS) continue;
                BlockState top = h.getBlockState(new BlockPos(x, 11, z)), above = h.getBlockState(new BlockPos(x, 12, z));
                BlockState upper = h.getBlockState(new BlockPos(x, 13, z));
                if (top.is(Blocks.GRASS_BLOCK)) grass[ring]++;
                else if (top.is(Blocks.PODZOL)) charred[ring]++;
                else if (top.is(Blocks.DIRT) || top.is(Blocks.COARSE_DIRT)) burnt[ring]++;
                plants[ring]++;
                if (above.is(Blocks.SHORT_GRASS) || above.is(Blocks.TALL_GRASS)) plantsLeft[ring]++;
                if (above.is(Blocks.DEAD_BUSH)) bushes++;
                // верхняя половина без нижней висела бы в воздухе
                if (upper.is(Blocks.TALL_GRASS) && !above.is(Blocks.TALL_GRASS)) {
                    h.fail("верх высокой травы без низа в " + x + ", " + z + " (внизу " + above + ")");
                }
                net.minecraft.world.phys.Vec3 at = net.minecraft.world.phys.Vec3.atCenterOf(h.absolutePos(new BlockPos(x, 12, z)));
                fluence[ring] = Math.max(fluence[ring], d.fluence(at));
                psi[ring] = Math.max(psi[ring], d.psi(at));
                if (top.is(Blocks.COARSE_DIRT)) coarse[ring]++;
                if (ua.zentix.incoming.nuclear.world.ThermalShadow.visible(level, d.burst(), at.add(0, -0.5, 0))) seen[ring]++;
                // биом — у равнины, без клеток у края площадки (в них и столбцы за стеной барьеров, в тени)
                if (plains[x * 64 + z] && z >= 4 && x >= 4 && x < 60) {
                    cells[ring]++;
                    levels[ring] += level(biomeAt(h, x, 12, z));
                }
            }
        }
        StringBuilder table = new StringBuilder();
        for (int r = 0; r < RINGS; r++) {
            int n = grass[r] + burnt[r] + charred[r];
            table.append(String.format(Locale.ROOT, "%n  %d–%d блоков, свет до %.1f, давление до %.1f, видят шар %d: дёрн %d, земля %d (крупн. %d), гарь %d; растения %d из %d; биом %.2f",
                    FIRST + r * RING, FIRST + (r + 1) * RING, fluence[r], psi[r], seen[r], grass[r], burnt[r], coarse[r], charred[r], plantsLeft[r], plants[r],
                    cells[r] == 0 ? 0 : levels[r] / (double) cells[r]));
            h.assertTrue(n == plants[r], "в кольце " + r + " верх площадки не дёрн и не земля: " + (plants[r] - n) + table);
        }
        String why = table.toString();
        Incoming.LOG.info("След света по кольцам:{}", why);
        double first = 1 - grass[0] / (double) plants[0], last = 1 - grass[RINGS - 1] / (double) plants[RINGS - 1];
        h.assertTrue(first >= 0.8, "у центра дёрн уцелел: " + why);
        h.assertTrue(last <= 0.05, "у края дёрн выгорел: " + why);
        boolean patchy = false;
        double prev = 1;
        for (int r = 0; r < RINGS; r++) {
            double share = 1 - grass[r] / (double) plants[r];
            if (share >= 0.1 && share <= 0.9) patchy = true;
            h.assertTrue(share <= prev + 0.1, "выгоревшего дальше от центра больше, чем ближе (кольцо " + r + "): " + why);
            prev = share;
        }
        h.assertTrue(patchy, "край выжженного — кольцом по порогу, без пятен: " + why);
        h.assertTrue(charred[1] + charred[2] + charred[3] > 0, "у центра нет гари: " + why);
        h.assertTrue(charred[RINGS - 1] + charred[RINGS - 2] + charred[RINGS - 3] == 0, "гарь у края: " + why);
        h.assertTrue(plantsLeft[0] <= plants[0] * 0.1, "трава у центра не сгорела: " + why);
        h.assertTrue(plantsLeft[RINGS - 1] + plantsLeft[RINGS - 2] >= (plants[RINGS - 1] + plants[RINGS - 2]) * 0.75, "трава у края сгорела: " + why);
        h.assertTrue(bushes > 0, "ни одного сухого куста: " + why);
        // биом: обугленный у центра, к краю — ступенями слабее
        double prevLevel = 4;
        for (int r = 0; r < RINGS; r++) {
            if (cells[r] < 16) continue;
            double mean = levels[r] / (double) cells[r];
            h.assertTrue(mean <= prevLevel + 0.3, "биом дальше от центра опалён сильнее (кольцо " + r + "): " + why);
            prevLevel = mean;
        }
        h.assertTrue(levels[0] >= cells[0] * 2.5, "у центра биом не обуглен: " + why);
        h.assertTrue(levels[RINGS - 2] <= cells[RINGS - 2] * 1.5, "у края биом опалён сильно: " + why);
        // полосы, которые свет не перекрашивает, и биомы под землёй; клетки биома у края площадки берут и столбцы за её
        // стеной (площадка стоит в чанках как придётся) — их земля и биом не те, что у площадки, поэтому от края отступ
        for (int x = 4; x < 60; x += 2) {
            assertBiome(h, x, 44, Biomes.SNOWY_PLAINS);
            assertBiome(h, x, 51, Biomes.RIVER);
            assertBiome(h, x, 60, Biomes.DESERT);
        }
        for (int x = 4; x < 60; x += 4) {
            for (int z = 4; z < 60; z += 4) {
                if (biomeAt(h, x, DEEP, z).hashCode() != deep[x * 64 + z]) h.fail("биом под землёй изменился в " + x + ", " + z + ": " + biomeAt(h, x, DEEP, z));
            }
        }
        // в данных чанка у центра — новые биомы (к сохранению чанк руин помечен отметкой руин)
        LevelChunk chunk = level.getChunkAt(h.absolutePos(new BlockPos(2, 11, 32)));
        String saved = net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level, chunk).toString();
        h.assertTrue(saved.contains(CHARRED.location().toString()), "в данных чанка нет обугленного биома");
        h.succeed();
    }

    /**
     * След света — свойство местности, а не подрыва: руины заранее строятся по месту подрыва без его сида
     * ({@code NuclearWarhead.geometry}), а чанк, загруженный после того, как руины подготовки забыты, — по самому
     * подрыву; пятна и биомы у них одни и те же, иначе на границе таких чанков след обрывался бы прямой.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_scorch_seed", skyAccess = true)
    public static void scorchSameForAnySeed(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        double scale = 0.0182;
        Detonation d = NuclearGameTests.detonation(h, GROUND_ZERO, Yield.optimalBurstHeight(15) * scale, 15, (float) scale);
        Detonation other = new Detonation(NuclearGameTests.IDS.incrementAndGet(), d.burst(), d.groundY(), d.yieldKt(), d.surface(),
                d.gameTime(), d.windDir(), d.windSpeed(), d.visibility(), d.seed() * 31 + 12345, d.scale(), d.fallout(), d.wave());
        int changed = 0;
        for (LevelChunk chunk : NuclearGameTests.chunks(h)) {
            RuinPlan a = RuinPlanner.plan(level, d, chunk), b = RuinPlanner.plan(level, other, chunk);
            String diff = b.differs(a);
            if (diff != null) h.fail("чанк " + chunk.getPos() + ": след другого сида не тот (другой | этот; y — от низа мира): " + diff);
            changed += a.changedBlocks();
        }
        h.assertTrue(changed > 0, "след не лёг");
        h.succeed();
    }

    /**
     * След без руин ({@code RuinPlanner.scorch}: LOD вдали за тяжёлой зоной) — тот же, что у плана руин, там, где волна
     * ничего не сломала: поле травы на ровной земле за тяжёлой зоной, от ~1,4 км (дёрн выгорает) до ~6 км. Свет, склон,
     * пятна, растения, пожары и биомы — одни и те же; план без руин — по снимку одного чанка, без окна соседей. Подрыв —
     * над дальним от блока структуры углом площадки: свет от подрыва за её краем шёл бы сквозь постройки соседних партий
     * (их порядок и место в каждой части CI свои — тень на всю площадку). Сравниваются чанки целиком на площадке (в
     * крайних — стена барьеров и соседи), где волна не ломает и растений: у ближнего столба давление ниже того, от
     * которого отражённое на открытой грани достаёт до их порога (~3,1 psi, у 15 кт — 2,1 км). Масштаб (1 блок — 71 м) —
     * чтобы таких чанков было не меньше 5 при любом сдвиге площадки к сетке чанков (место площадки случайное).
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_scorch_far", skyAccess = true)
    public static void scorchWithoutRuinsSameAsRuins(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        fillBiome(h, 0, 63, Biomes.PLAINS);
        plants(h);
        double scale = 0.014;
        Detonation d = NuclearGameTests.detonation(h, new BlockPos(60, 12, 60), Yield.optimalBurstHeight(15) * scale, 15, (float) scale);
        int changed = 0, biomes = 0, compared = 0;
        double plantBreak = RuinPlanner.testPlantBreakPsi();
        BlockPos a = h.absolutePos(BlockPos.ZERO), b = h.absolutePos(new BlockPos(63, 0, 63));
        for (LevelChunk chunk : NuclearGameTests.chunks(h)) {
            net.minecraft.world.level.ChunkPos c = chunk.getPos();
            if (c.getMinBlockX() < Math.min(a.getX(), b.getX()) || c.getMaxBlockX() > Math.max(a.getX(), b.getX())
                    || c.getMinBlockZ() < Math.min(a.getZ(), b.getZ()) || c.getMaxBlockZ() > Math.max(a.getZ(), b.getZ())) continue;
            double nx = Math.clamp(d.burst().x, c.getMinBlockX(), c.getMaxBlockX() + 1), nz = Math.clamp(d.burst().z, c.getMinBlockZ(), c.getMaxBlockZ() + 1);
            if (d.psi(new Vec3(nx, d.groundY() + 1, nz)) >= plantBreak) continue;
            compared++;
            RuinPlan ruins = RuinPlanner.plan(level, d, chunk), scorch = RuinPlanner.testScorch(level, d, chunk);
            String diff = scorch.differs(ruins);
            if (diff != null) h.fail("чанк " + chunk.getPos() + ": след без руин не тот, что у руин (след | руины; y — от низа мира): " + diff);
            changed += ruins.changedBlocks();
            if (ruins.recolours()) biomes++;
        }
        h.assertTrue(compared >= 4 && changed > 0 && biomes > 0, "след не лёг: чанков " + compared + ", мест " + changed + ", чанков с биомами " + biomes);
        Incoming.LOG.info("След без руин совпал с руинами: чанков {}, мест {}, чанков с биомами {}", compared, changed, biomes);
        h.succeed();
    }

    /**
     * Свет — со вспышкой, волна — потом: чанк A при ~15 кал/см² выжжен и горит раньше, чем до него дойдёт волна (слой
     * вспышки {@code FlashScorch}), а после волны в нём тот же мир, что дал бы один план руин по миру до вспышки, — через
     * очередь руин (план в фоне по снимкам чанков, на которых уже стоит слой). Чанк B, ближний к эпицентру из тех, до
     * кого волна доходит после слоя (сильнее волна — сбивает часть огня), — планом, готовым до вспышки в руинах подрыва
     * (как у подготовки руин за полёт МБР): он не устаревает от слоя и даёт тот же мир. Подрыв — за углом трёх на три чанков
     * площадки, место — от сетки чанков ({@link #gridSide});
     * масштаб — такой, чтобы у земли в середине A было 15 кал/см². Слой — только чанкам целиком на площадке: огонь за
     * её краем горел бы у соседних партий.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_flash_scorch", skyAccess = true)
    public static void flashScorchesBeforeTheWaveAndRuinsMatch(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        fillBiome(h, 0, 63, Biomes.PLAINS);
        plants(h);
        // Чанки и подрыв — от сетки чанков, а не от площадки: площадка встаёт где угодно относительно сетки, а в масштабе
        // проверки слой у A встаёт на 1-м тике (свет набирает порог за 0,1 тика), волна к чанкам — через 0,6–3 тика, и
        // срок волны в тиках (ScarQueue.due) у соседей A то 1, то 2: при ~5 % сдвигов площадки (CI 19.10, выпуск 3.4.1)
        // ни один чанк, кроме A, не получал волну позже слоя. От сетки картина одна при любой раскладке партий: 3 × 3
        // чанка целиком на площадке и подрыв на их диагонали в 6 блоках за углом: B получает волну через 2,0 тика, двое
        // соседей A — через 2,3, A — через 2,8.
        BlockPos lo = h.absolutePos(BlockPos.ZERO), hi = h.absolutePos(new BlockPos(63, 0, 63));
        int[] sx = gridSide(Math.min(lo.getX(), hi.getX()), Math.max(lo.getX(), hi.getX()));
        int[] sz = gridSide(Math.min(lo.getZ(), hi.getZ()), Math.max(lo.getZ(), hi.getZ()));
        BlockPos g = new BlockPos(sx[1], h.absolutePos(new BlockPos(0, 12, 0)).getY(), sz[1]);
        List<LevelChunk> inside = new ArrayList<>();
        for (int cx = sx[0]; cx < sx[0] + 3; cx++) {
            for (int cz = sz[0]; cz < sz[0] + 3; cz++) inside.add(level.getChunk(cx, cz));
        }
        inside.sort(Comparator.comparingDouble(c -> Math.hypot(c.getPos().getMiddleBlockX() - g.getX(), c.getPos().getMiddleBlockZ() - g.getZ())));
        LevelChunk a = inside.get(inside.size() - 1);
        Vec3 midA = new Vec3(a.getPos().getMiddleBlockX() + 0.5, g.getY() + 1, a.getPos().getMiddleBlockZ() + 0.5);
        // масштаб: 15 кал/см² у земли в середине A (меньше масштаб — больше метров в блоке, меньше света)
        double sLo = 0.002, sHi = 1;
        for (int i = 0; i < 50; i++) {
            double s = Math.sqrt(sLo * sHi);
            Detonation probe = new Detonation(0, new Vec3(g.getX() + 0.5, g.getY() + Yield.optimalBurstHeight(15) * s, g.getZ() + 0.5), g.getY(), 15, false,
                    0, 0, 0, 20_000, 7, (float) s, false, Detonation.OLD_WAVE);
            if (probe.fluence(midA) > 15) sHi = s;
            else sLo = s;
        }
        Detonation d = NuclearGameTests.detonationAt(h, g, Yield.optimalBurstHeight(15) * sHi, 15, (float) sHi);
        // слой A встаёт, когда свет у ближней к шару точки A набрал порог поджига (как в FlashScorch); ближние чанки — не позже
        double nx = Mth.clamp(d.burst().x, a.getPos().getMinBlockX(), a.getPos().getMaxBlockX() + 1);
        double nz = Mth.clamp(d.burst().z, a.getPos().getMinBlockZ(), a.getPos().getMaxBlockZ() + 1);
        long flashAt = d.gameTime() + (long) Math.ceil(d.flashTicks(d.fluence(new Vec3(nx, d.groundY() + 1, nz)), ThermalModel.IGNITE));
        // B — ближний к эпицентру чанк, до которого волна доходит после слоя (в игре до ближних она доходит раньше конца
        // импульса, и они получают всё с руинами; масштаб проверки сжимает время в тики)
        LevelChunk b = null;
        for (LevelChunk c : inside) {
            if (c != a && ScarQueue.due(d, c.getPos()) > flashAt) {
                b = c;
                break;
            }
        }
        h.assertTrue(b != null, "нет чанка, до которого волна доходит после слоя");
        Vec3 midB = new Vec3(b.getPos().getMiddleBlockX() + 0.5, g.getY() + 1, b.getPos().getMiddleBlockZ() + 0.5);
        // окна руин A и B — в памяти сразу: за краем площадки чанки грузятся в фоне
        for (LevelChunk c : List.of(a, b)) {
            for (int dx = -RuinPlanner.REACH; dx <= RuinPlanner.REACH; dx++) {
                for (int dz = -RuinPlanner.REACH; dz <= RuinPlanner.REACH; dz++) level.getChunk(c.getPos().x + dx, c.getPos().z + dz);
            }
        }
        // мир до вспышки: каким его сделали бы одни руины (с чистого листа, свои руины подрыва)
        LevelChunkSection[] expectA = RuinPlanner.planFresh(level, d, a, false).testAppliedCopy(level, a);
        LevelChunkSection[] expectB = RuinPlanner.planFresh(level, d, b, false).testAppliedCopy(level, b);
        RuinPlan preparedB = RuinPlanner.plan(level, d, b);
        long[] keys = inside.stream().mapToLong(c -> c.getPos().toLong()).toArray();
        FlashScorch flash = FlashScorch.forTest(d, keys, new ColumnScar.Budget(true));
        WorkClock clock = WorkClock.counting(1_000_000L);
        long waveA = ScarQueue.due(d, a.getPos()), waveB = ScarQueue.due(d, b.getPos());
        String setup = String.format(Locale.ROOT, "масштаб %.4f, свет у A %.1f, у B %.1f кал/см², давление у A %.2f, у B %.2f psi; импульс %.2f тика, слой A на %d, волна у A %d, у B %d",
                sHi, d.fluence(midA), d.fluence(midB), d.psi(midA), d.psi(midB), d.pulseTicks(), flashAt - d.gameTime(), waveA - d.gameTime(), waveB - d.gameTime());
        h.assertTrue(Math.abs(d.fluence(midA) - 15) < 0.5, "свет у A не тот: " + setup);
        h.assertTrue(flashAt < waveA, "волна у A раньше слоя — проверка ни о чём: " + setup);
        ScarQueue q = new ScarQueue();
        try {
            // слой всех чанков, чьё время пришло к слою A (дальних, кроме A, нет: остальные — не позже)
            boolean done = false;
            for (int i = 0; i < 20 && !done; i++) {
                clock.start(10_000_000_000L);
                done = flash.work(level, flashAt, clock);
                if (!done) RuinWorkers.awaitForTest(RuinWorkers.Priority.NEAR, 60_000);
            }
            h.assertTrue(flash.appliedChunks() >= 2, "слой вспышки встал в " + flash.appliedChunks() + " чанках из " + keys.length + "; " + setup);
            int[] before = new int[2];
            for (int k = 0; k < 2; k++) {
                LevelChunk c = k == 0 ? a : b;
                String name = k == 0 ? "A" : "B";
                h.assertTrue(c.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) < d.id(), "руины " + name + " встали со вспышкой");
                int[] n = surface(h, c);
                before[k] = n[1];
                h.assertTrue(n[0] > 0, "до волны в " + name + " нет выжженной земли; " + setup);
                h.assertTrue(n[1] > 0, "до волны в " + name + " нет пожаров; " + setup);
                h.assertTrue(n[2] < 256, "до волны в " + name + " трава цела; " + setup);
                // огонь слоя — без тиков до руин: не растёт и не гаснет, пока план руин по миру до вспышки ждёт волну
                int[] f = fires(h, c, 4);
                h.assertTrue(f[1] == 0, "до волны у " + f[1] + " из " + f[0] + " пожаров в " + name + " уже есть тик");
                h.assertTrue(c.getExistingData(ModAttachments.FLASH_LAYER).orElse(0) == d.id(), "у " + name + " нет отметки слоя вспышки");
            }
            // волна: A — очередью руин (план в фоне), B — планом, готовым до вспышки
            q.offer(a, d);
            for (long now = waveA - 1; now < waveA + 20 && a.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) != d.id(); now++) {
                clock.start(10_000_000_000L);
                q.work(level, now, clock);
                RuinWorkers.awaitForTest(RuinWorkers.Priority.NEAR, 60_000);
            }
            h.assertTrue(a.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) == d.id(), "руин A нет: " + q.diagState(a.getPos().toLong(), waveA + 20));
            h.assertTrue(preparedB.apply(level, b, d, new ColumnScar.Budget(true)), "план B, готовый до вспышки, устарел от слоя вспышки");
            String diffA = differ(a, expectA), diffB = differ(b, expectB);
            if (diffA != null) h.fail("A после волны не тот, что от одних руин (мир | руины): " + diffA + "; " + setup);
            if (diffB != null) h.fail("B после волны не тот, что от одних руин (мир | руины): " + diffB + "; " + setup);
            int[] afterA = surface(h, a), afterB = surface(h, b);
            h.assertTrue(afterA[1] <= before[0] && afterB[1] <= before[1], "после волны пожаров больше, чем со вспышкой: A " + afterA[1] + " из " + before[0]
                    + ", B " + afterB[1] + " из " + before[1]);
            if (d.psi(midB) >= 2.5) h.assertTrue(afterB[1] < before[1], "волна не сбила огонь в B: " + afterB[1] + " из " + before[1] + "; " + setup);
            // с руинами огонь горит дальше: тик — каждому, отметка слоя снята
            for (LevelChunk c : List.of(a, b)) {
                int[] f = fires(h, c, 4);
                h.assertTrue(f[0] == f[1], "после руин в " + c.getPos() + " без тика " + (f[0] - f[1]) + " из " + f[0] + " пожаров");
                h.assertTrue(c.getExistingData(ModAttachments.FLASH_LAYER).isEmpty(), "после руин у " + c.getPos() + " осталась отметка слоя вспышки");
            }
            Incoming.LOG.info("Слой вспышки до волны, руины после — тот же мир: {}; пожаров в A {} → {}, в B {} → {}", setup, before[0], afterA[1], before[1], afterB[1]);
        } finally {
            q.clear(level);
            // огонь площадки — прочь: дальше он горел бы и тикал в соседних партиях
            for (LevelChunk c : inside) {
                for (int x = c.getPos().getMinBlockX(); x <= c.getPos().getMaxBlockX(); x++) {
                    for (int z = c.getPos().getMinBlockZ(); z <= c.getPos().getMaxBlockZ(); z++) {
                        BlockPos p = new BlockPos(x, g.getY(), z);
                        if (level.getBlockState(p).getBlock() instanceof BaseFireBlock) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        h.succeed();
    }

    /**
     * Руины поверх слоя вспышки, когда снимков до вспышки у руин подрыва уже нет (вытеснены, взяты заново): снимки окна —
     * по миру со слоем, который правка снимка возвращает к миру до вспышки ({@code RuinPlan.unflash}: огонь — воздух,
     * места слоя — старые, карты высот столбцов — по копии). Поле травы и деревянные дома (огонь вспышки на крышах; у
     * эпицентра волна крыши сносит) во всех чанках целиком на площадке; слой — во всех сразу. План каждого чанка — в
     * потоке сервера, в фоне и с диска — тот же, что по миру до вспышки (с диска — что с диска до вспышки), а мир после
     * руин — тот же, что дали бы одни руины: огонь слоя, которого нет у руин, погас, остальной горит с тиком; место,
     * которого руины не меняют, — как после слоя (свет у стены, которую потом снесла волна), ступени биома — не слабее
     * слоя. Чанк, у которого руины подрыва забыты совсем (после простоя, перезапуска), знает о слое по отметке: огонь в
     * снимке — воздух, руины встают, весь огонь — с тиком, отметка снята; в счёте пожаров подрыва — огонь, что горит
     * дальше (после простоя счётчик жив, и огонь слоя чанка, что он взял, из счёта уходит; после перезапуска счётчик
     * новый). Весь счёт — огонь чанков с руинами и огонь слоя в счёте там, где руин нет. Чанк с отметкой, чей подрыв
     * забыт и руин не будет, отдаёт огню тики сам.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_flash_unflash", skyAccess = true)
    public static void ruinsOverFlashWithoutShots(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        fillBiome(h, 0, 63, Biomes.PLAINS);
        plants(h);
        BlockPos zero = new BlockPos(60, 12, 60), g = h.absolutePos(zero);
        int ground = h.absolutePos(new BlockPos(0, 11, 0)).getY();
        List<LevelChunk> inside = inside(h, g);
        h.assertTrue(inside.size() >= 4, "чанков целиком на площадке " + inside.size());
        // дом 6×6 посреди каждого чанка: стены в три блока, крыша из досок, внутри пусто
        for (LevelChunk c : inside) {
            for (int lx = 5; lx <= 10; lx++) {
                for (int lz = 5; lz <= 10; lz++) {
                    boolean wall = lx == 5 || lx == 10 || lz == 5 || lz == 10;
                    for (int dy = 1; dy <= 4; dy++) {
                        BlockPos p = new BlockPos(c.getPos().getMinBlockX() + lx, ground + dy, c.getPos().getMinBlockZ() + lz);
                        BlockState st = dy == 4 || wall ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState();
                        level.setBlock(p, st, Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        LevelChunk far = inside.get(inside.size() - 1);
        Detonation d = NuclearGameTests.detonation(h, zero, Yield.optimalBurstHeight(15) * scaleFor(g, far, 15), 15, (float) scaleFor(g, far, 15));
        for (LevelChunk c : inside) {
            for (int dx = -RuinPlanner.REACH; dx <= RuinPlanner.REACH; dx++) {
                for (int dz = -RuinPlanner.REACH; dz <= RuinPlanner.REACH; dz++) level.getChunk(c.getPos().x + dx, c.getPos().z + dz);
            }
        }
        // руины по миру до вспышки: план, мир после него, план с диска
        int n = inside.size();
        RuinPlan[] before = new RuinPlan[n], beforeDisk = new RuinPlan[n];
        LevelChunkSection[][] expect = new LevelChunkSection[n][];
        for (int i = 0; i < n; i++) {
            LevelChunk c = inside.get(i);
            before[i] = RuinPlanner.planFresh(level, d, c, false);
            expect[i] = before[i].testAppliedCopy(level, c);
            beforeDisk[i] = RuinPlanner.testPlanOverFlash(level, d, c, 2);
            String same = beforeDisk[i].differs(before[i]);
            if (same != null) Incoming.LOG.info("Чанк {}: план с диска до вспышки не тот, что из памяти (диск | память): {}", c.getPos(), same);
        }
        int[][] world = new int[n][];
        for (int i = 0; i < n; i++) world[i] = blocks(inside.get(i));
        long[] keys = inside.stream().mapToLong(c -> c.getPos().toLong()).toArray();
        // счётчик пожаров подрыва — один на слой и руины, как в игре (ScarQueue.budget)
        ColumnScar.Budget budget = new ColumnScar.Budget(true);
        FlashScorch flash = FlashScorch.forTest(d, keys, budget);
        WorkClock clock = WorkClock.counting(1_000_000L);
        try {
            boolean done = false;
            for (int i = 0; i < 40 && !done; i++) {
                clock.start(10_000_000_000L);
                done = flash.work(level, d.gameTime() + 10_000, clock);
                if (!done) RuinWorkers.awaitForTest(RuinWorkers.Priority.NEAR, 60_000);
            }
            h.assertTrue(done && flash.appliedChunks() == n, "слой вспышки встал в " + flash.appliedChunks() + " чанках из " + n);
            int roofFires = 0, lit = 0;
            LevelChunk marked = null, forgotten = null, idle = null;
            for (LevelChunk c : inside) {
                int[] f = fires(h, c, 8);
                h.assertTrue(f[1] == 0, "огонь слоя с тиком до руин в " + c.getPos() + ": " + f[1] + " из " + f[0]);
                lit += f[0];
                for (int lx = 5; lx <= 10; lx++) {
                    for (int lz = 5; lz <= 10; lz++) {
                        if (level.getBlockState(new BlockPos(c.getPos().getMinBlockX() + lx, ground + 5, c.getPos().getMinBlockZ() + lz)).getBlock() instanceof BaseFireBlock) roofFires++;
                    }
                }
                if (f[0] > 0) {
                    idle = forgotten;
                    forgotten = marked;
                    marked = c;
                }
            }
            h.assertTrue(roofFires > 0, "огня вспышки на крышах нет: дома ни о чём (пожаров всего " + lit + ")");
            // ступени биома после слоя: руины их не понижают (свет был раньше), где волна сменила землю — остаются
            int[][] flashBiomes = new int[n][], flashed = new int[n][];
            for (int i = 0; i < n; i++) {
                flashBiomes[i] = biomeLevels(inside.get(i).getSections());
                flashed[i] = blocks(inside.get(i));
            }
            h.assertTrue(idle != null, "слой вспышки зажёг огонь меньше чем в трёх чанках");
            String roofs = roofs(inside, ground, expect);
            // план каждого чанка без снимков до вспышки — тремя путями
            RuinPlan[] background = new RuinPlan[n];
            String[] ways = {"в потоке сервера", "в фоне", "с диска"};
            for (int i = 0; i < n; i++) {
                LevelChunk c = inside.get(i);
                for (int way = 0; way < 3; way++) {
                    RuinPlan p = RuinPlanner.testPlanOverFlash(level, d, c, way);
                    String diff = p.differs(way == 2 ? beforeDisk[i] : before[i]);
                    if (diff != null) h.fail("чанк " + c.getPos() + ": план поверх слоя " + ways[way] + " не тот, что по миру до вспышки (поверх | до; y — от низа мира): "
                            + diff + "; " + roofs);
                    if (way == 1) background[i] = p;
                }
            }
            // руины: все чанки, кроме трёх, — планами в фоне; ещё два — когда руины подрыва забыты (только отметка):
            // после простоя и после перезапуска; последний — подрыв забыт совсем
            int[] kept = new int[1];
            for (int i = 0; i < n; i++) {
                LevelChunk c = inside.get(i);
                if (c == marked || c == forgotten || c == idle) continue;
                h.assertTrue(background[i].apply(level, c, d, budget), "план " + c.getPos() + " поверх слоя устарел");
                // место, которого руины не меняют, а слой сменил (свет у стены, которую потом снесла волна, — склон к
                // шару по земле до волны), остаётся следом слоя; огонь слоя — нет
                String diff = overFlash(c, expect[i], world[i], flashed[i], kept), biomes = differ(c, expect[i], flashBiomes[i], false);
                if (biomes != null) diff = (diff == null ? "" : diff + "; ") + biomes;
                if (diff != null) h.fail("чанк " + c.getPos() + " после руин поверх слоя не тот, что от одних руин (мир | руины): " + diff + "; " + roofs);
                int[] f = fires(h, c, 8);
                h.assertTrue(f[0] == f[1], "после руин в " + c.getPos() + " без тика " + (f[0] - f[1]) + " из " + f[0] + " пожаров");
                h.assertTrue(c.getExistingData(ModAttachments.FLASH_LAYER).isEmpty(), "после руин у " + c.getPos() + " осталась отметка слоя");
            }
            NuclearWorld.get(level).forgetRuinsForTest(d.id());
            // руины подрыва забыты после простоя (RUINS_IDLE), а счётчик пожаров подрыва жив: огонь слоя чанка, что он взял,
            // уходит из счёта, входит тот, что горит дальше (иначе оставшийся огонь слоя считался бы дважды, и предел
            // резал бы новые пожары раньше срока)
            h.assertTrue(FlashScorch.marked(idle, d.id()), "у чанка с огнём слоя нет отметки");
            int layerIdle = budget.layerForTest(idle.getPos()), others = budget.firesForTest() - layerIdle;
            h.assertTrue(layerIdle > 0, "у " + idle.getPos() + " огня слоя в счёте пожаров нет: проверять нечего");
            h.assertTrue(RuinPlanner.plan(level, d, idle).apply(level, idle, d, budget), "план чанка с отметкой слоя (руины забыты, счётчик жив) устарел");
            int[] f = fires(h, idle, 8);
            h.assertTrue(f[0] == f[1], "руины по отметке слоя при живом счётчике: без тика " + (f[0] - f[1]) + " из " + f[0] + " пожаров");
            h.assertTrue(budget.firesForTest() - others == f[0], "руины по отметке слоя при живом счётчике: горит " + f[0] + ", в счёте пожаров подрыва за чанк "
                    + (budget.firesForTest() - others) + " (слой взял " + layerIdle + ")");
            h.assertFalse(FlashScorch.marked(idle, d.id()), "руины по отметке слоя при живом счётчике: отметка осталась");
            // весь счёт подрыва — то, что горит в чанках с руинами, и огонь слоя, что он взял, где руин ещё нет
            int counted = 0;
            for (LevelChunk c : inside) counted += c == marked || c == forgotten ? budget.layerForTest(c.getPos()) : fires(h, c, 8)[0];
            h.assertTrue(budget.firesForTest() == counted, "в счёте пожаров подрыва " + budget.firesForTest() + ", а горит в чанках с руинами "
                    + "вместе с огнём слоя в счёте там, где руин нет, " + counted);
            h.assertTrue(FlashScorch.marked(marked, d.id()), "у чанка с огнём слоя нет отметки");
            RuinPlan alone = RuinPlanner.plan(level, d, marked);
            // счётчик пожаров подрыва после перезапуска — новый: огонь слоя, что горит дальше, входит в него
            ColumnScar.Budget restarted = new ColumnScar.Budget(true);
            h.assertTrue(alone.apply(level, marked, d, restarted), "план чанка с отметкой слоя (руины забыты) устарел");
            f = fires(h, marked, 8);
            h.assertTrue(f[0] == f[1], "руины по отметке слоя: без тика " + (f[0] - f[1]) + " из " + f[0] + " пожаров");
            h.assertTrue(f[0] > 0 && restarted.firesForTest() == f[0], "руины по отметке слоя: горит " + f[0] + ", в счёте пожаров подрыва "
                    + restarted.firesForTest());
            h.assertFalse(FlashScorch.marked(marked, d.id()), "руины по отметке слоя: отметка осталась");
            // чанк с отметкой, чей подрыв забыт (у проверки подрыва нет в NuclearEvents): руин не будет — огню тики сразу
            h.assertTrue(FlashScorch.burnOutForTest(level, forgotten), "чанк с отметкой слоя забытого подрыва не отдал огню тики");
            f = fires(h, forgotten, 8);
            h.assertTrue(f[0] > 0 && f[0] == f[1], "огонь слоя забытого подрыва: без тика " + (f[0] - f[1]) + " из " + f[0]);
            h.assertTrue(forgotten.getExistingData(ModAttachments.FLASH_LAYER).isEmpty(), "огонь слоя забытого подрыва: отметка осталась");
            Incoming.LOG.info("Руины поверх слоя вспышки без снимков до вспышки — те же: {} чанков, пожаров слоя {} (на крышах {}), мест со следом слоя, которых руины не меняют, {}; "
                    + "руины по отметке при живом счётчике: слой взял {}, горит {}; {}", n, lit, roofFires, kept[0], layerIdle, budget.firesForTest() - others, roofs);
        } finally {
            NuclearWorld.get(level).forgetRuinsForTest(d.id());
            unfire(level, inside, ground, 8);
        }
        h.succeed();
    }

    /**
     * Блоки чанка после руин поверх слоя: каждое место — как в {@code expected} (одни руины) или, если руины его не
     * меняют ({@code expected} = {@code world}, мир до вспышки), — как после слоя ({@code flashed}), кроме огня. null —
     * так; {@code kept} — сколько мест со следом слоя.
     */
    @Nullable
    private static String overFlash(LevelChunk chunk, LevelChunkSection[] expected, int[] world, int[] flashed, int[] kept) {
        int[] live = blocks(chunk);
        int n = 0;
        StringBuilder out = new StringBuilder();
        for (int k = 0; k < live.length; k++) {
            int i = k >> 12, c = k & 4095;
            int want = Block.getId(expected[i].getBlockState(c & 15, c >> 8, (c >> 4) & 15));
            if (live[k] == want) continue;
            if (want == world[k] && live[k] == flashed[k] && !(Block.stateById(live[k]).getBlock() instanceof BaseFireBlock)) {
                kept[0]++;
                continue;
            }
            if (n++ < 4) out.append(String.format(Locale.ROOT, " [x %d y %d z %d: %s | %s, до вспышки %s, после слоя %s]", c & 15,
                    chunk.getMinBuildHeight() + (i << 4) + (c >> 8), (c >> 4) & 15, Block.stateById(live[k]), Block.stateById(want),
                    Block.stateById(world[k]), Block.stateById(flashed[k])));
        }
        return n == 0 ? null : n + " мест:" + out;
    }

    /** Все места чанка (номера состояний), снизу вверх по секциям. */
    private static int[] blocks(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int[] out = new int[sections.length * 4096];
        for (int i = 0; i < sections.length; i++) {
            for (int k = 0; k < 4096; k++) out[i * 4096 + k] = Block.getId(sections[i].getBlockState(k & 15, k >> 8, (k >> 4) & 15));
        }
        return out;
    }

    /** Чанки целиком на площадке, от ближнего к {@code g}. */
    private static List<LevelChunk> inside(GameTestHelper h, BlockPos g) {
        BlockPos lo = h.absolutePos(BlockPos.ZERO), hi = h.absolutePos(new BlockPos(63, 0, 63));
        List<LevelChunk> inside = new ArrayList<>();
        for (LevelChunk chunk : NuclearGameTests.chunks(h)) {
            ChunkPos c = chunk.getPos();
            if (c.getMinBlockX() >= Math.min(lo.getX(), hi.getX()) && c.getMaxBlockX() <= Math.max(lo.getX(), hi.getX())
                    && c.getMinBlockZ() >= Math.min(lo.getZ(), hi.getZ()) && c.getMaxBlockZ() <= Math.max(lo.getZ(), hi.getZ())) inside.add(chunk);
        }
        inside.sort(Comparator.comparingDouble(c -> Math.hypot(c.getPos().getMiddleBlockX() - g.getX(), c.getPos().getMiddleBlockZ() - g.getZ())));
        return inside;
    }

    /** Масштаб 15 кт на оптимальной высоте над {@code g}, при котором у земли в середине чанка {@code c} — {@code cal} кал/см². */
    private static double scaleFor(BlockPos g, LevelChunk c, double cal) {
        Vec3 mid = new Vec3(c.getPos().getMiddleBlockX() + 0.5, g.getY() + 1, c.getPos().getMiddleBlockZ() + 0.5);
        double lo = 0.002, hi = 1;
        for (int i = 0; i < 50; i++) {
            double s = Math.sqrt(lo * hi);
            Detonation probe = new Detonation(0, new Vec3(g.getX() + 0.5, g.getY() + Yield.optimalBurstHeight(15) * s, g.getZ() + 0.5), g.getY(), 15, false,
                    0, 0, 0, 20_000, 7, (float) s, false, Detonation.OLD_WAVE);
            if (probe.fluence(mid) > cal) hi = s;
            else lo = s;
        }
        return hi;
    }

    /** Сколько досок крыш сносят одни руины ({@code expect} — чанки после них), по чанкам: строка для лога и сообщений. */
    private static String roofs(List<LevelChunk> inside, int ground, LevelChunkSection[][] expect) {
        StringBuilder out = new StringBuilder("крыши снесены:");
        for (int i = 0; i < inside.size(); i++) {
            LevelChunk c = inside.get(i);
            LevelChunkSection[] after = expect[i];
            int gone = 0;
            for (int lx = 5; lx <= 10; lx++) {
                for (int lz = 5; lz <= 10; lz++) {
                    int y = ground + 4, s = c.getSectionIndex(y);
                    if (!after[s].getBlockState(lx, y & 15, lz).is(Blocks.OAK_PLANKS)) gone++;
                }
            }
            out.append(' ').append(gone);
        }
        return out.toString();
    }

    /** Огонь в чанке от верха площадки до {@code up} блоков над ним: всего и сколько с тиком в расписании. */
    private static int[] fires(GameTestHelper h, LevelChunk c, int up) {
        ServerLevel level = h.getLevel();
        int ground = h.absolutePos(new BlockPos(0, 11, 0)).getY();
        int[] n = new int[2];
        for (int x = c.getPos().getMinBlockX(); x <= c.getPos().getMaxBlockX(); x++) {
            for (int z = c.getPos().getMinBlockZ(); z <= c.getPos().getMaxBlockZ(); z++) {
                for (int y = ground; y <= ground + up; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState st = level.getBlockState(p);
                    if (!(st.getBlock() instanceof BaseFireBlock)) continue;
                    n[0]++;
                    if (level.getBlockTicks().hasScheduledTick(p, st.getBlock())) n[1]++;
                }
            }
        }
        return n;
    }

    /** Огонь чанков — прочь (дальше он горел бы и тикал в соседних партиях). */
    private static void unfire(ServerLevel level, List<LevelChunk> chunks, int ground, int up) {
        for (LevelChunk c : chunks) {
            for (int x = c.getPos().getMinBlockX(); x <= c.getPos().getMaxBlockX(); x++) {
                for (int z = c.getPos().getMinBlockZ(); z <= c.getPos().getMaxBlockZ(); z++) {
                    for (int y = ground; y <= ground + up; y++) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (level.getBlockState(p).getBlock() instanceof BaseFireBlock) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    /**
     * По одной оси площадки {@code [a, b]}: первый из трёх чанков целиком на ней и место подрыва в 6 блоках за ними — со
     * стороны, где у площадки запас больше (из 16 блоков сверх трёх чанков на эту сторону приходится не меньше 8). Отступы
     * 6 и 5 — одно расстояние до середин чанков с обеих сторон ({@code getMiddleBlockX} + 0,5 — не середина чанка).
     */
    private static int[] gridSide(int a, int b) {
        int first = (a + 15) >> 4, last = ((b + 1) >> 4) - 1;
        int after = b - ((first + 3) << 4) + 1, before = ((last - 2) << 4) - a;
        return after >= before ? new int[]{first, ((first + 3) << 4) + 5} : new int[]{last - 2, ((last - 2) << 4) - 5};
    }

    /** Верх площадки в чанке: выжженной земли (земля, гарь), пожаров над ней, уцелевших растений. */
    private static int[] surface(GameTestHelper h, LevelChunk c) {
        ServerLevel level = h.getLevel();
        int ground = h.absolutePos(new BlockPos(0, 11, 0)).getY();
        int[] n = new int[3];
        for (int x = c.getPos().getMinBlockX(); x <= c.getPos().getMaxBlockX(); x++) {
            for (int z = c.getPos().getMinBlockZ(); z <= c.getPos().getMaxBlockZ(); z++) {
                BlockState top = level.getBlockState(new BlockPos(x, ground, z)), above = level.getBlockState(new BlockPos(x, ground + 1, z));
                if (top.is(Blocks.DIRT) || top.is(Blocks.COARSE_DIRT) || top.is(Blocks.PODZOL)) n[0]++;
                if (above.getBlock() instanceof BaseFireBlock) n[1]++;
                if (above.is(Blocks.SHORT_GRASS) || above.is(Blocks.TALL_GRASS)) n[2]++;
            }
        }
        return n;
    }

    /** Места и клетки биома, где чанк не тот, что секции {@code expected} (до 4 примеров), или null. */
    @Nullable
    private static String differ(LevelChunk chunk, LevelChunkSection[] expected) {
        return differ(chunk, expected, null, true);
    }

    /** Ступени опалённого биома ({@link #level}) клеток секций: секция × 64 + клетка. */
    private static int[] biomeLevels(LevelChunkSection[] sections) {
        int[] out = new int[sections.length * 64];
        for (int i = 0; i < sections.length; i++) {
            for (int k = 0; k < 64; k++) out[i * 64 + k] = level(sections[i].getNoiseBiome(k & 3, k >> 4, (k >> 2) & 3));
        }
        return out;
    }

    /**
     * То же; {@code floor} — ступени биома, что уже стоят (слой вспышки): клетка — не слабее их и того, что в
     * {@code expected} (руины ступень не понижают), а без ступени — биом {@code expected}. {@code withBlocks} — false:
     * только клетки биома.
     */
    @Nullable
    private static String differ(LevelChunk chunk, LevelChunkSection[] expected, @Nullable int[] floor, boolean withBlocks) {
        LevelChunkSection[] live = chunk.getSections();
        int blocks = 0, biomes = 0;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < live.length; i++) {
            int y0 = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState was = live[i].getBlockState(x, y, z), want = expected[i].getBlockState(x, y, z);
                        if (withBlocks && was != want && blocks++ < 4) out.append(String.format(Locale.ROOT, " [x %d y %d z %d: %s | %s]", x, y0 + y, z, was, want));
                    }
                }
            }
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        Holder<Biome> was = live[i].getNoiseBiome(x, y, z), want = expected[i].getNoiseBiome(x, y, z);
                        int least = floor == null ? 0 : Math.max(level(want), floor[i * 64 + (y << 4 | z << 2 | x)]);
                        boolean same = least == 0 ? was.equals(want) : level(was) == least;
                        if (!same && biomes++ < 4) out.append(String.format(Locale.ROOT, " [биом x %d y %d z %d: %s | %s%s]", x * 4, y0 + y * 4, z * 4,
                                was.unwrapKey().map(k -> k.location().toString()).orElse("?"), want.unwrapKey().map(k -> k.location().toString()).orElse("?"),
                                least > level(want) ? ", слой — ступень " + least : ""));
                    }
                }
            }
        }
        return blocks + biomes == 0 ? null : blocks + " мест, " + biomes + " клеток биома:" + out;
    }

    /** Растения над дёрном площадки: трава, через два — высокая трава в два блока. */
    private static void plants(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                BlockPos p = h.absolutePos(new BlockPos(x, 12, z));
                if ((x + z) % 3 == 0) {
                    level.setBlock(p, Blocks.TALL_GRASS.defaultBlockState(), Block.UPDATE_CLIENTS);
                    level.setBlock(p.above(), Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS);
                } else {
                    level.setBlock(p, Blocks.SHORT_GRASS.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /** Биом клеток рядов {@code z0..z1} площадки во всю высоту — как {@code /fillbiome}. */
    static void fillBiome(GameTestHelper h, int z0, int z1, ResourceKey<Biome> key) {
        ServerLevel level = h.getLevel();
        Holder<Biome> biome = level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(key);
        BlockPos a = h.absolutePos(new BlockPos(0, -16, z0)), b = h.absolutePos(new BlockPos(63, 39, z1));
        int qx0 = QuartPos.fromBlock(Math.min(a.getX(), b.getX())), qx1 = QuartPos.fromBlock(Math.max(a.getX(), b.getX()));
        int qy0 = QuartPos.fromBlock(Math.min(a.getY(), b.getY())), qy1 = QuartPos.fromBlock(Math.max(a.getY(), b.getY()));
        int qz0 = QuartPos.fromBlock(Math.min(a.getZ(), b.getZ())), qz1 = QuartPos.fromBlock(Math.max(a.getZ(), b.getZ()));
        for (int cx = qx0 >> 2; cx <= qx1 >> 2; cx++) {
            for (int cz = qz0 >> 2; cz <= qz1 >> 2; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                chunk.fillBiomesFromNoise((x, y, z, s) -> x >= qx0 && x <= qx1 && y >= qy0 && y <= qy1 && z >= qz0 && z <= qz1 ? biome
                        : chunk.getNoiseBiome(x, y, z), level.getChunkSource().randomState().sampler());
                chunk.setUnsaved(true);
            }
        }
    }

    private static Holder<Biome> biomeAt(GameTestHelper h, int x, int y, int z) {
        BlockPos p = h.absolutePos(new BlockPos(x, y, z));
        return h.getLevel().getNoiseBiome(QuartPos.fromBlock(p.getX()), QuartPos.fromBlock(p.getY()), QuartPos.fromBlock(p.getZ()));
    }

    private static int level(Holder<Biome> b) {
        return b.is(CHARRED) ? 3 : b.is(SCORCHED) ? 2 : b.is(SINGED) ? 1 : 0;
    }

    private static void assertBiome(GameTestHelper h, int x, int z, ResourceKey<Biome> key) {
        Holder<Biome> b = biomeAt(h, x, 12, z);
        if (!b.is(key)) h.fail("биом в " + x + ", " + z + " — " + b.unwrapKey().map(k -> k.location().toString()).orElse("?") + ", а не " + key.location());
    }
}
