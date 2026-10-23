package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.warhead.BuriedBlast;
import ua.zentix.incoming.warhead.Penetration;
import ua.zentix.incoming.warhead.Warheads;

import java.util.Locale;

/**
 * Бетонобойная бомба по физике: путь в грунте по Янгу по материалу блоков (карта данных {@code incoming:penetration}),
 * датчик пустоты, подземный взрыв по приведённой глубине — глубоко без воронки, мелко с воронкой выброса.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BombGameTests {
    private static final Vec3 DOWN = new Vec3(0, -1, 0);

    private BombGameTests() {}

    /** Столб {@code state} 3×3 под {@code top} (в координатах площадки) высотой {@code height}. */
    private static void column(GameTestHelper h, BlockPos top, int height, BlockState state) {
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-1, -height + 1, -1), top.offset(1, 0, 1))) {
            h.getLevel().setBlock(h.absolutePos(p), state, Block.UPDATE_CLIENTS);
        }
    }

    /** Путь бомбы отвесно вниз со скоростью {@code v} м/с с верха блока {@code top}. */
    private static BunkerBusterEntity.Path down(GameTestHelper h, BlockPos top, double v) {
        Vec3 face = Vec3.atBottomCenterOf(h.absolutePos(top)).add(0, 1, 0);
        return BunkerBusterEntity.plan(h.getLevel(), face, DOWN, v, 0);
    }

    private static String m(double x) {
        return String.format(Locale.ROOT, "%.2f", x);
    }

    /**
     * Путь по материалу: камень и бетон (S 0,84), глубинный сланец (0,67), земля (12) — ровно путь Янга для своей среды
     * (карта данных читается по блокам), бомба встала; бетонная плита над землёй — земля за ней с S/2 (тот же ход
     * {@link Penetration.Run}, что сверяют юнит-тесты).
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "bomb_penetration", skyAccess = true)
    public static void penetrationByMaterial(GameTestHelper h) {
        Penetration.Body mop = BunkerBusterEntity.body();
        record Case(String name, BlockState state, double s, double v, BlockPos top) {}
        Case[] cases = {
                new Case("камень", Blocks.STONE.defaultBlockState(), 0.84, 250, new BlockPos(16, 30, 16)),
                new Case("бетон", Blocks.GRAY_CONCRETE.defaultBlockState(), 0.84, 250, new BlockPos(24, 30, 16)),
                new Case("сланец", Blocks.DEEPSLATE.defaultBlockState(), 0.67, 250, new BlockPos(32, 30, 16)),
                new Case("земля", Blocks.DIRT.defaultBlockState(), 12, 100, new BlockPos(40, 30, 16))};
        for (Case c : cases) column(h, c.top, 29, c.state);
        for (Case c : cases) {
            BunkerBusterEntity.Path path = down(h, c.top, c.v);
            double expect = Penetration.depth(mop, c.s, c.v);
            h.assertTrue(path.end() == BunkerBusterEntity.Path.End.REST, c.name + ": бомба не встала — " + path.end());
            h.assertTrue(Math.abs(path.length() - expect) < 1e-6, c.name + ": путь " + m(path.length()) + ", по Янгу " + m(expect));
        }
        // в камне на 235 м/с — ~3,9 м: удар после игрового падения (сброс с B-2 на 12 блоках/тик с 170 блоков), вдвое
        // медленнее настоящего
        double stone = down(h, cases[0].top, 235).length();
        h.assertTrue(stone > 3.4 && stone < 4.5, "камень на 235 м/с: " + m(stone));
        // бетонная плита в блок над землёй
        BlockPos slab = new BlockPos(48, 30, 16);
        column(h, slab.below(), 28, Blocks.DIRT.defaultBlockState());
        column(h, slab, 1, Blocks.GRAY_CONCRETE.defaultBlockState());
        Penetration.Run run = new Penetration.Run(mop, 100);
        double expect = run.through(Penetration.Kind.CONCRETE, 0.84, 1);
        for (int i = 0; i < 28 && !run.stopped(); i++) expect += run.through(Penetration.Kind.SOIL, 12, 1);
        double under = down(h, slab, 100).length();
        h.assertTrue(Math.abs(under - expect) < 1e-6, "плита и земля: путь " + m(under) + ", ждали " + m(expect));
        h.assertTrue(under < Penetration.depth(mop, 12, 100), "за плитой земля не твёрже");
        // ковёр на земле — 1/16 блока прочего твёрдого (S 1), а не метр кирпича; и не бетонная плита: земля за ним обычная
        BlockPos rug = new BlockPos(56, 30, 16);
        column(h, rug.below(), 28, Blocks.DIRT.defaultBlockState());
        column(h, rug, 1, Blocks.WHITE_CARPET.defaultBlockState());
        Penetration.Run soft = new Penetration.Run(mop, 100);
        double rugExpect = soft.through(Penetration.Kind.ROCK, 1, 1 / 16.0);
        for (int i = 0; i < 28 && !soft.stopped(); i++) rugExpect += soft.through(Penetration.Kind.SOIL, 12, 1);
        double onRug = BunkerBusterEntity.plan(h.getLevel(), Vec3.atBottomCenterOf(h.absolutePos(rug)).add(0, 1 / 16.0, 0), DOWN, 100, 0).length();
        h.assertTrue(Math.abs(onRug - rugExpect) < 1e-6, "ковёр и земля: путь " + m(onRug) + ", ждали " + m(rugExpect));
        h.succeed();
    }

    /**
     * Путь в однородной земле под углом — тот же путь Янга, что отвесно: ось, пересекая грани блоков, не начинает слой
     * заново. Жалоба ET 23.10: бомба B-2 входит под 89°, и путь в земле был на 25–40 % длиннее закона (отвесно — точно):
     * хвост оси вне формы блока длиной в погрешность float считался пустотой. Скорость — 160 м/с, путь ~35 м: в высоту
     * площадки.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "bomb_oblique", skyAccess = true)
    public static void obliquePathIsYoung(GameTestHelper h) {
        Penetration.Body mop = BunkerBusterEntity.body();
        BlockPos top = new BlockPos(32, 38, 32);
        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(8, 2, 8), new BlockPos(55, 38, 55))) {
            h.getLevel().setBlock(h.absolutePos(p), (p.getY() == 38 ? Blocks.PODZOL : Blocks.DIRT).defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        double v = 160, expect = Penetration.depth(mop, 12, v);
        h.assertTrue(expect > 25 && expect < 36, "путь по Янгу " + m(expect) + " не помещается в куб земли");
        Vec3 face = Vec3.atBottomCenterOf(h.absolutePos(top)).add(0.13, 1, 0.29);
        for (double pitch : new double[]{90, 89, 80, 70}) {
            for (double yaw : new double[]{0, 30, 45}) {
                double p = Math.toRadians(pitch), y = Math.toRadians(yaw);
                Vec3 dir = new Vec3(Math.cos(p) * Math.sin(y), -Math.sin(p), Math.cos(p) * Math.cos(y));
                BunkerBusterEntity.Path path = BunkerBusterEntity.plan(h.getLevel(), face, dir, v, 0);
                h.assertTrue(path.end() == BunkerBusterEntity.Path.End.REST, pitch + "°/" + yaw + "°: бомба не встала — " + path.end());
                h.assertTrue(Math.abs(path.length() - expect) < 1e-3 * expect,
                        pitch + "°/" + yaw + "°: путь " + m(path.length()) + ", по Янгу " + m(expect));
            }
        }
        h.succeed();
    }

    /**
     * Датчик пустоты: после 5 м камня пещера в 10 блоков — подрыв, когда бомба вошла в неё целиком (своя длина); за
     * перекрытием тоньше {@value BunkerBusterEntity#SENSOR_SOLID} м — не срабатывает, бомба идёт дальше.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "bomb_void", skyAccess = true)
    public static void voidSensor(GameTestHelper h) {
        BlockPos top = new BlockPos(20, 30, 20);
        column(h, top, 5, Blocks.STONE.defaultBlockState());
        column(h, top.below(15), 10, Blocks.STONE.defaultBlockState());
        BunkerBusterEntity.Path path = down(h, top, 400);
        h.assertTrue(path.end() == BunkerBusterEntity.Path.End.VOID, "датчик не сработал: " + path.end());
        h.assertTrue(Math.abs(path.length() - (5 + BunkerBusterEntity.voidDepth())) < 1e-6, "подрыв в " + m(path.length()) + " от входа");
        BlockPos thin = new BlockPos(40, 30, 20);
        column(h, thin, 2, Blocks.STONE.defaultBlockState());
        column(h, thin.below(12), 15, Blocks.STONE.defaultBlockState());
        BunkerBusterEntity.Path through = down(h, thin, 400);
        h.assertTrue(through.end() == BunkerBusterEntity.Path.End.REST && through.length() > 12,
                "крыша в 2 блока: датчик сработал или бомба встала раньше пола — " + through.end() + " на " + m(through.length()));
        // крона в 6 блоков листвы над поляной: листва замедляет едва-едва — датчик не взводится, бомба идёт в землю
        BlockPos crown = new BlockPos(30, 30, 40);
        column(h, crown, 6, Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
        column(h, crown.below(16), 15, Blocks.DIRT.defaultBlockState());
        BunkerBusterEntity.Path forest = down(h, crown, 250);
        h.assertTrue(forest.end() == BunkerBusterEntity.Path.End.REST && forest.length() > 16,
                "крона взвела датчик пустоты: " + forest.end() + " на " + m(forest.length()));
        h.succeed();
    }

    /** Грунт площадки: камень {@code lo}…{@code hi} (в координатах площадки). */
    private static void rock(GameTestHelper h, BlockPos lo, BlockPos hi) {
        for (BlockPos p : BlockPos.betweenClosed(lo, hi)) h.getLevel().setBlock(h.absolutePos(p), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    private static void generateNow(ServerLevel level, Vec3 at, int radius) {
        ChunkPos c = new ChunkPos(BlockPos.containing(at));
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) level.getChunk(c.x + dx, c.z + dz);
    }

    /**
     * Заряд на глубине ~20 м в камне (приведённая глубина 1,5): воронки нет — верх над зарядом цел; на месте заряда —
     * полость, рядом — дроблёная порода (булыжник), свод хода в зоне трещин обрушился щебнем.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "bomb_deep", skyAccess = true)
    public static void deepChargeLeavesNoCrater(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        rock(h, new BlockPos(16, 1, 16), new BlockPos(47, 28, 47));
        // ход 1×2 вдоль z в 12–17 блоках от заряда: за зоной дробления (10 м), в зоне трещин (27 м)
        for (int z = 22; z <= 42; z++) {
            for (int y = 8; y <= 9; y++) level.setBlock(h.absolutePos(new BlockPos(44, y, z)), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        Vec3 charge = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 8, 32)));
        generateNow(level, charge, 3);
        BuriedBlast law = new BuriedBlast(2423, 29 - charge.y + h.absolutePos(BlockPos.ZERO).getY());
        h.assertTrue(!law.breaches(), "заряд мелко: приведённая глубина " + m(law.scaledDepth()));
        Warheads.bunker(level, charge, charge.add(0, 20, 0), null, null);
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "работа подрыва идёт");
            h.assertTrue(h.getBlockState(new BlockPos(32, 28, 32)).is(Blocks.STONE), "верх над зарядом снесён — воронка на глубине 20");
            h.assertTrue(h.getBlockState(new BlockPos(32, 8, 32)).isAir(), "полости нет");
            h.assertTrue(h.getBlockState(new BlockPos(32, 8, 38)).is(Blocks.COBBLESTONE), "дробления нет в 6 блоках");
            int fallen = 0;
            for (int z = 22; z <= 42; z++) {
                BlockState roof = h.getBlockState(new BlockPos(44, 10, z)), floor = h.getBlockState(new BlockPos(44, 8, z));
                if (!roof.is(Blocks.STONE) || floor.is(Blocks.GRAVEL)) fallen++;
            }
            h.assertTrue(fallen > 0, "свод хода в зоне трещин цел");
        });
    }

    /** Взрыв, отменённый приватом (ExplosionEvent.Start), не роет: ни полости, ни дробления. */
    @GameTest(template = "range", timeoutTicks = 400, batch = "bomb_cancelled", skyAccess = true)
    public static void cancelledBlastDigsNothing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        rock(h, new BlockPos(16, 1, 16), new BlockPos(47, 28, 47));
        Vec3 charge = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 8, 32)));
        generateNow(level, charge, 3);
        java.util.function.Consumer<net.neoforged.neoforge.event.level.ExplosionEvent.Start> deny = e -> {
            if (e.getLevel() == level && e.getExplosion().center().distanceTo(charge) < 1) e.setCanceled(true);
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(deny);
        StrikeGameTests.afterTest(h, () -> net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(deny));
        Warheads.bunker(level, charge, charge.add(0, 20, 0), null, null);
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "работа подрыва идёт");
            h.assertTrue(h.getBlockState(new BlockPos(32, 8, 32)).is(Blocks.STONE), "отменённый взрыв выгрыз полость");
            h.assertTrue(h.getBlockState(new BlockPos(32, 8, 38)).is(Blocks.STONE), "отменённый взрыв раздробил породу");
        });
    }

    /** Заряд на глубине 3 м (приведённая глубина 0,22): воронка выброса — верх над зарядом и вокруг снесён. */
    @GameTest(template = "range", timeoutTicks = 400, batch = "bomb_shallow", skyAccess = true)
    public static void shallowChargeBlowsACrater(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        rock(h, new BlockPos(16, 1, 16), new BlockPos(47, 22, 47));
        Vec3 charge = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 19, 32)));
        generateNow(level, charge, 4);
        Warheads.bunker(level, charge, charge.add(0, 3, 0), null, null);
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "работа подрыва идёт");
            int gone = 0;
            for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) if (h.getBlockState(new BlockPos(32 + dx, 22, 32 + dz)).isAir()) gone++;
            // чаша воронки в камне (BuriedBlast.craterRadius): верх снят там, где она глубже блока, — круг радиусом r,
            // где h·(1 − (r/R)²) ≥ 1
            BuriedBlast law = new BuriedBlast(2423, 3);
            var rock = ua.zentix.incoming.nuclear.model.CraterModel.Soil.ROCK;
            double r = law.craterRadius(rock) * Math.sqrt(Math.max(0, 1 - 1 / law.craterDepth(rock)));
            int expect = (int) Math.floor(0.8 * Math.PI * r * r);
            h.assertTrue(gone >= expect, "воронки нет: снесено " + gone + " из 81 блока верха вокруг заряда, ждали не меньше " + expect);
            h.assertTrue(h.getBlockState(new BlockPos(32, 20, 32)).isAir(), "воронка не дошла до полости");
        });
    }
}
