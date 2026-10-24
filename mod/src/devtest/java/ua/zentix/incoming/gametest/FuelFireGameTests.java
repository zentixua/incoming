package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import net.minecraft.world.level.ChunkPos;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.model.FuelPool;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.FuelFires;
import ua.zentix.incoming.warhead.Warheads;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Топливо в воронке горит огнём Minecraft на блоках ({@link FuelFires}). Класс жалобы (снимок Артёма 24.10: клубы пламени
 * висели в воздухе там, где крыши уже не было): огня топлива без опоры нет — в каждом тике под каждой горящей клеткой блок
 * с формой и в клетке огонь; и горит оно не дольше своего запаса — последняя клетка гаснет в срок закона
 * ({@link FuelPool#seconds}), и огня, который держало только топливо, после не остаётся.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FuelFireGameTests {
    /** Середина площадки «range»: трава (пол — камень, земля, трава) и первый воздух над ней. */
    private static final BlockPos GRASS = new BlockPos(32, 11, 32), CENTER = GRASS.above();
    /** Клетки чужих проверок партии не в счёт: свои — ближе этого к своему удару, блоков. */
    private static final double MINE = 24;

    private FuelFireGameTests() {}

    /**
     * Обломки не остаются (комья на клетке гасили бы её топливо — так и в игре, но проверка не об этом), погода — ясно:
     * огонь ванили гаснет в дождь, и клетка ждала бы нового огня до {@code FuelFires.RELIGHT} тиков.
     */
    private static void calm(GameTestHelper h) {
        boolean debris = IncomingConfig.SERVER.debrisStay.get(), damage = IncomingConfig.SERVER.blockDamage.get();
        IncomingConfig.SERVER.debrisStay.set(false);
        IncomingConfig.SERVER.blockDamage.set(true);
        h.getLevel().setWeatherParameters(24000, 0, false, false);
        fireTicks(h);
        StrikeGameTests.afterTest(h, () -> {
            IncomingConfig.SERVER.debrisStay.set(debris);
            IncomingConfig.SERVER.blockDamage.set(damage);
        });
    }

    /**
     * Огонь ванили на время проверки идёт, как в игре ({@code doFireTick}: у сервера GameTest он выключен): стареет, гаснет
     * сам на голой земле, в дождь, растёт на горючее. Без этого проверка держания огня была бы пустой.
     */
    private static void fireTicks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        net.minecraft.world.level.GameRules.BooleanValue rule = level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOFIRETICK);
        boolean was = rule.get();
        rule.set(true, level.getServer());
        StrikeGameTests.afterTest(h, () -> rule.set(was, level.getServer()));
    }

    /** Клетки топлива у {@code at} (свои), их нынешние места. */
    private static List<BlockPos> cells(ServerLevel level, Vec3 at) {
        return FuelFires.get(level).cells().stream().filter(p -> Vec3.atCenterOf(p).distanceTo(at) < MINE).toList();
    }

    /** Каждая горящая клетка — огонь на опоре: под ней блок с формой столкновений, в ней — блок огня. */
    private static void standsOnSupport(GameTestHelper h, ServerLevel level, Vec3 at, Set<BlockPos> ever) {
        for (BlockPos c : cells(level, at)) {
            ever.add(c);
            BlockPos below = c.below();
            h.assertTrue(!level.getBlockState(below).getCollisionShape(level, below).isEmpty(),
                    "огонь топлива в воздухе: " + c.toShortString() + " над " + level.getBlockState(below));
            h.assertTrue(level.getBlockState(c).getBlock() instanceof BaseFireBlock,
                    "клетка топлива без огня: " + c.toShortString() + " — " + level.getBlockState(c));
        }
    }

    /**
     * Удар {@code weapon} в траву: топливо ложится на дно воронки — столько клеток, сколько площадь закона; пока горит,
     * каждая — огонь на опоре; гаснут они по закону (до 0,8 срока горят все), последняя — в срок, и на голой земле после
     * огня не остаётся. Огонь ванили на время проверки идёт как в игре ({@link #fireTicks}).
     */
    private static void groundTest(GameTestHelper h, WeaponType weapon) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        FuelPool law = weapon.spec().pool();
        long born = level.getGameTime(), end = born + Math.round(20 * law.seconds());
        Set<BlockPos> ever = new HashSet<>();
        long[] out = {Long.MIN_VALUE};
        int[] most = {0};
        calm(h);
        Warheads.detonate(level, weapon, at, null, null);
        h.onEachTick(() -> {
            standsOnSupport(h, level, at, ever);
            int n = cells(level, at).size();
            most[0] = Math.max(most[0], n);
            long t = level.getGameTime();
            if (t < born + Math.round(16 * law.seconds()) && t > born + 40) {
                h.assertTrue(n == law.cells(), String.format(Locale.ROOT, "%s: на %.0f с горит %d клеток из %d", weapon, (t - born) / 20.0, n, law.cells()));
            }
            if (n == 0 && !ever.isEmpty() && out[0] == Long.MIN_VALUE) out[0] = t;
        });
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "очередь попаданий не пуста");
            h.assertTrue(out[0] != Long.MIN_VALUE, "топливо ещё горит");
            for (BlockPos c : ever) {
                h.assertTrue(!(level.getBlockState(c).getBlock() instanceof BaseFireBlock), "после топлива огонь остался: " + c.toShortString());
            }
            String got = String.format(Locale.ROOT, "%s: клеток %d (закон %d), погасло на %.1f с (закон %.1f с)", weapon, most[0], law.cells(),
                    (out[0] - born) / 20.0, law.seconds());
            Incoming.LOG.info("FUELFIRE {}", got);
            h.assertTrue(most[0] == law.cells(), got);
            h.assertTrue(Math.abs(out[0] - end) <= 2, got);
        });
    }

    @GameTest(template = "range", timeoutTicks = 3600, batch = "fuel_fire_drone", skyAccess = true)
    public static void droneFuelBurnsOnTheCraterFloorAsLongAsItLasts(GameTestHelper h) {
        groundTest(h, WeaponType.DRONE);
    }

    @GameTest(template = "range", timeoutTicks = 21000, batch = "fuel_fire_missile", skyAccess = true)
    public static void missileFuelBurnsOnTheCraterFloorAsLongAsItLasts(GameTestHelper h) {
        groundTest(h, WeaponType.MISSILE);
    }

    /**
     * Шахед в дощатую крышу дома из каменного кирпича: крышу пробивает, топливо падает сквозь пролом на пол дома и горит
     * там — ни одной клетки в воздухе на высоте бывшей крыши (жалоба 24.10), и пока горит, каждая — на опоре.
     */
    @GameTest(template = "range", timeoutTicks = 600, batch = "fuel_fire_roof", skyAccess = true)
    public static void fuelFallsThroughABrokenRoofToTheFloor(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        calm(h);
        BlockPos c = h.absolutePos(CENTER);
        int roof = c.getY() + 5;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-6, 0, -6), c.offset(6, 4, 6))) {
            boolean wall = Math.abs(p.getX() - c.getX()) == 6 || Math.abs(p.getZ() - c.getZ()) == 6;
            level.setBlock(p, wall ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
        }
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-6, 5, -6), c.offset(6, 5, 6))) level.setBlock(p, Blocks.OAK_PLANKS.defaultBlockState(), 2);
        Vec3 at = new Vec3(c.getX() + 0.5, roof + 1, c.getZ() + 0.5);
        Set<BlockPos> ever = new HashSet<>();
        Warheads.detonate(level, WeaponType.DRONE, at, null, null);
        h.onEachTick(() -> standsOnSupport(h, level, at, ever));
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "очередь попаданий не пуста");
            List<BlockPos> now = cells(level, at);
            String got = "клетки " + now.stream().map(BlockPos::toShortString).toList() + ", пол — y " + c.getY() + ", крыша — y " + roof;
            Incoming.LOG.info("FUELFIRE roof: {}", got);
            h.assertTrue(now.size() == WeaponType.DRONE.spec().pool().cells(), got);
            for (BlockPos p : now) h.assertTrue(p.getY() < roof, "топливо на высоте крыши: " + got);
        });
    }

    /**
     * Топливо на дощатом настиле над землёй; настил пропал (сгорел, сломан) — топливо капает вниз в своей колонке до
     * земли и горит там; на высоте настила огня не остаётся (горючего рядом нет).
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "fuel_fire_drip", skyAccess = true)
    public static void fuelDripsDownWhenItsSupportGoes(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        fireTicks(h);
        BlockPos c = h.absolutePos(CENTER);
        int deck = c.getY() + 3;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-2, 3, -2), c.offset(2, 3, 2))) level.setBlock(p, Blocks.OAK_PLANKS.defaultBlockState(), 2);
        Vec3 at = new Vec3(c.getX() + 0.5, deck + 1, c.getZ() + 0.5);
        FuelFires.testPour(level, at, WeaponType.DRONE);
        int n = WeaponType.DRONE.spec().pool().cells();
        h.assertTrue(cells(level, at).size() == n, "на настиле не " + n + " клеток: " + cells(level, at));
        for (BlockPos p : cells(level, at)) h.assertTrue(p.getY() == deck + 1, "клетка не на настиле: " + p.toShortString());
        Set<BlockPos> ever = new HashSet<>();
        long gone = level.getGameTime() + 5;
        level.setWeatherParameters(24000, 0, false, false);
        // настил пропадает в одном тике проверки, а клетки догоняют его в следующем тике мира: в тот тик опору не проверять
        h.onEachTick(() -> {
            if (level.getGameTime() == gone) {
                for (BlockPos p : BlockPos.betweenClosed(c.offset(-2, 3, -2), c.offset(2, 3, 2))) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            } else standsOnSupport(h, level, at, ever);
        });
        h.succeedWhen(() -> {
            h.assertTrue(level.getGameTime() > gone + 2, "настил ещё стоит");
            List<BlockPos> now = cells(level, at);
            h.assertTrue(now.size() == n, "клеток " + now.size());
            for (BlockPos p : now) h.assertTrue(p.getY() == c.getY(), "топливо не на земле: " + p.toShortString());
            for (BlockPos p : BlockPos.betweenClosed(c.offset(-3, 4, -3), c.offset(3, 4, 3))) {
                h.assertTrue(!(level.getBlockState(p).getBlock() instanceof BaseFireBlock), "огонь на высоте пропавшего настила: " + p.toShortString());
            }
            // клиент знает, где топливо горит теперь: в его состоянии — новые места (очаг дыма, свет и гул — там)
            S2C.FuelFire state = state(level, at);
            h.assertTrue(state != null && state.cells().stream().allMatch(p -> p.getY() == c.getY()) && state.smothered() == 0,
                    "состояние для клиента не сдвинулось: " + state);
        });
    }

    /**
     * Клетку засыпало (блок на огне) или залило водой — её топливо кончилось раньше срока: она уходит из учёта, а клиент
     * получает это в состоянии воронки (горит меньшая площадь), прочие горят дальше.
     */
    @GameTest(template = "range", timeoutTicks = 100, batch = "fuel_fire_smother", skyAccess = true)
    public static void coveredOrWashedCellsAreOutForTheClientToo(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        fireTicks(h);
        level.setWeatherParameters(24000, 0, false, false);
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        FuelFires.testPour(level, at, WeaponType.DRONE);
        S2C.FuelFire first = state(level, at);
        h.assertTrue(first != null && first.cells().size() == 5, "нет воронки: " + first);
        BlockPos sand = first.cells().get(1), water = first.cells().get(2);
        long when = level.getGameTime() + 3;
        h.onEachTick(() -> {
            if (level.getGameTime() != when) return;
            level.setBlock(sand, Blocks.SAND.defaultBlockState(), 3);
            level.setBlock(water, Blocks.WATER.defaultBlockState(), 3);
        });
        h.succeedWhen(() -> {
            h.assertTrue(level.getGameTime() > when + 1, "ещё не засыпало");
            S2C.FuelFire now = state(level, at);
            h.assertTrue(now != null && now.smothered() == 0b110L, "засыпанная и залитая клетки не погасли для клиента: " + now);
            List<BlockPos> live = cells(level, at);
            h.assertTrue(live.size() == 3 && !live.contains(sand) && !live.contains(water), "в учёте " + live);
        });
    }

    /**
     * Дождь горящую лужу топлива не гасит (`FuelFires.RELIGHT`), а ванильный дождь гасит огонь в его тик: 30 воронок ракет
     * под дождём горят почти всё время (огонь на клетке — не меньше 90 % тиков), а снова зажигаются клетки не чаще раза в
     * секунду на клетку (ваниль гасит огонь в дождь в среднем раз в ~9 с). Цена учёта за тик — в строку FUELFIRE (на
     * машинах CI время не мерим).
     */
    @GameTest(template = "range", timeoutTicks = 800, batch = "fuel_fire_rain", skyAccess = true)
    public static void rainDoesNotPutOutTheFuelOrStartALoop(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        fireTicks(h);
        BlockPos c = h.absolutePos(CENTER);
        level.setWeatherParameters(0, 24000, true, false);
        List<Vec3> craters = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) for (int k = 0; k < 5; k++) craters.add(Vec3.atBottomCenterOf(c.offset(-20 + 8 * i, 0, -16 + 8 * k)));
        long relights0 = FuelFires.get(level).relights();
        for (Vec3 at : craters) FuelFires.testPour(level, at, WeaponType.MISSILE);
        long start = level.getGameTime();
        long[] cellTicks = {0}, fireTicks = {0};
        boolean[] rained = {false};
        h.onEachTick(() -> {
            for (Vec3 at : craters) {
                for (BlockPos p : cells(level, at)) {
                    cellTicks[0]++;
                    if (level.getBlockState(p).getBlock() instanceof BaseFireBlock) fireTicks[0]++;
                    rained[0] |= level.isRainingAt(p);
                }
            }
        });
        h.succeedWhen(() -> {
            long t = level.getGameTime() - start;
            h.assertTrue(t >= 600, "идёт дождь");
            double share = fireTicks[0] / (double) Math.max(1, cellTicks[0]);
            long relights = FuelFires.get(level).relights() - relights0;
            int cells = craters.size() * WeaponType.MISSILE.spec().pool().cells();
            String got = String.format(Locale.ROOT, "rain: %d воронок, %d клеток, огонь %.1f %% тиков, зажиганий снова %d за %.0f с (%.2f на клетку в секунду); %s",
                    craters.size(), cells, 100 * share, relights, t / 20.0, relights / (cells * t / 20.0), FuelFires.get(level).stats());
            Incoming.LOG.info("FUELFIRE {}", got);
            h.assertTrue(rained[0], "дождя над клетками нет — проверка пуста: " + got);
            h.assertTrue(share >= 0.9, got);
            h.assertTrue(relights <= cells * t / 20.0, got);
            level.setWeatherParameters(24000, 0, false, false);
        });
    }

    /**
     * Клетка — только там, где готовы и соседние чанки: ваниль, решая, держится ли огонь, смотрит соседей клетки, и у края
     * чанка рядом с неготовым соседом это грузило бы его прямо в тике. Чанк далеко от площадки держится один (тикет без
     * соседей); топливо у его края не ложится, и сосед так и не грузится.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "fuel_fire_edge", skyAccess = true)
    public static void fuelAtAChunkEdgeDoesNotLoadTheNeighbour(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.gameSpeed(h);
        var chunks = level.getChunkSource();
        ChunkPos f = new ChunkPos(h.absolutePos(CENTER).east(1600));
        chunks.addRegionTicket(HOLD, f, 0, f);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(chunks.getChunkNow(f.x, f.z) != null, "чанк не загрузился"))
                .thenExecute(() -> {
                    h.assertTrue(chunks.getChunkNow(f.x + 1, f.z) == null, "сосед уже загружен — проверка пуста");
                    // у восточного края чанка, на его рельефе
                    int x = f.getMaxBlockX(), z = f.getMiddleBlockZ();
                    int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
                    int lit = FuelFires.testPour(level, new Vec3(x + 0.5, y, z + 0.5), WeaponType.MISSILE);
                    h.assertTrue(chunks.getChunkNow(f.x + 1, f.z) == null, "топливо у края загрузило соседний чанк");
                    h.assertTrue(lit == 0, "клетки у края без готовых соседей: " + lit);
                })
                .thenExecute(() -> chunks.removeRegionTicket(HOLD, f, 0, f))
                .thenSucceed();
    }

    /**
     * Топливо выгорело, пока чанк клетки не готов (игрок ушёл), — клетка ждёт, и огонь снимается, когда чанк снова готов
     * (у ванили с doFireTick = false он стоял бы вечно). Чанк далеко от площадки: тикет с соседями — готов, без соседей —
     * нет.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "fuel_fire_unload", skyAccess = true)
    public static void fuelThatBurnsOutUnloadedIsPutOutWhenItLoads(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        fireTicks(h);
        StrikeGameTests.gameSpeed(h);
        var chunks = level.getChunkSource();
        ChunkPos f = new ChunkPos(h.absolutePos(CENTER).west(1600));
        BlockPos mid = new BlockPos(f.getMiddleBlockX(), 250, f.getMiddleBlockZ());
        Vec3 at = Vec3.atBottomCenterOf(mid.above());
        long[] pouredAt = {0};
        chunks.addRegionTicket(HOLD, f, 1, f);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(ua.zentix.incoming.util.Terrain.neighbourhoodReady(level, f.x, f.z), "чанк с соседями не готов"))
                .thenExecute(() -> {
                    for (BlockPos p : BlockPos.betweenClosed(mid.offset(-3, 0, -3), mid.offset(3, 0, 3))) level.setBlock(p, Blocks.STONE.defaultBlockState(), 3);
                    // топливо уже почти выгорело: клетки гаснут по закону в ближайшие 10 тиков
                    long burned = Math.round(20 * WeaponType.DRONE.spec().pool().seconds()) - 10;
                    h.assertTrue(FuelFires.testPour(level, at, WeaponType.DRONE, burned) == 5, "топливо не легло на площадку");
                    pouredAt[0] = level.getGameTime();
                    // и чанк сразу перестаёт быть готовым: соседи — ниже полной загрузки
                    chunks.addRegionTicket(HOLD, f, 0, f);
                    chunks.removeRegionTicket(HOLD, f, 1, f);
                    chunks.runDistanceManagerUpdates();
                    h.assertTrue(!ua.zentix.incoming.util.Terrain.neighbourhoodReady(level, f.x, f.z), "чанк всё ещё готов");
                })
                .thenWaitUntil(() -> h.assertTrue(level.getGameTime() > pouredAt[0] + 60, "ждём срока"))
                .thenExecute(() -> {
                    h.assertTrue(cells(level, at).size() == 5, "выгоревшие клетки неготового чанка ушли из учёта: " + cells(level, at));
                    for (BlockPos p : cells(level, at)) h.assertTrue(level.getBlockState(p).getBlock() instanceof BaseFireBlock, "огонь в неготовом чанке снят");
                    chunks.addRegionTicket(HOLD, f, 1, f);
                    chunks.removeRegionTicket(HOLD, f, 0, f);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(cells(level, at).isEmpty(), "клетки не сняты, когда чанк готов");
                    for (BlockPos p : BlockPos.betweenClosed(mid.offset(-3, 1, -3), mid.offset(3, 1, 3))) {
                        h.assertTrue(!(level.getBlockState(p).getBlock() instanceof BaseFireBlock), "огонь сверх запаса топлива: " + p.toShortString());
                    }
                })
                .thenExecute(() -> chunks.removeRegionTicket(HOLD, f, 1, f))
                .thenSucceed();
    }

    /** Тикет проверок: держит дальний чанк, пока проверка идёт. */
    private static final net.minecraft.server.level.TicketType<ChunkPos> HOLD = net.minecraft.server.level.TicketType.create("incoming_test_fuel",
            java.util.Comparator.comparingLong(ChunkPos::toLong));

    /** Состояние воронки у {@code at} для клиента (своей), null — её нет. */
    private static S2C.FuelFire state(ServerLevel level, Vec3 at) {
        return FuelFires.get(level).state().stream().filter(s -> Vec3.atCenterOf(s.cells().getFirst()).distanceTo(at) < MINE).findFirst().orElse(null);
    }

    /** На воде топливо плёнкой не держит горение (как у дыма клиента): огня нет. */
    @GameTest(template = "range", timeoutTicks = 40, batch = "fuel_fire_water", skyAccess = true)
    public static void fuelOnWaterDoesNotBurn(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(CENTER);
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-4, -2, -4), c.offset(4, -1, 4))) level.setBlock(p, Blocks.WATER.defaultBlockState(), 2);
        Vec3 at = new Vec3(c.getX() + 0.5, c.getY() - 0.1, c.getZ() + 0.5);
        int lit = FuelFires.testPour(level, at, WeaponType.DRONE);
        h.assertTrue(lit == 0 && cells(level, at).isEmpty(), "на воде горит " + lit + " клеток");
        h.succeed();
    }
}
