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
        StrikeGameTests.afterTest(h, () -> {
            IncomingConfig.SERVER.debrisStay.set(debris);
            IncomingConfig.SERVER.blockDamage.set(damage);
        });
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
     * огня не остаётся. Огонь ванили на время проверки идёт как в игре.
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
        });
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
