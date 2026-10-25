package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.registry.ModDataMaps;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.BreachModel;
import ua.zentix.incoming.warhead.Warheads;

import java.util.Locale;

/**
 * Пробой плиты приложенным зарядом (FM 5-250, {@link BreachModel}; жалоба 23.10: ракета по настилу моста в метр камня
 * над рекой и по полю стадиона над помещением оставила яму в блок). Плита из камня над пустотой: тоньше радиуса пробоя
 * — дыра по закону, толще — не пробита (только воронка сверху). Грунт полупространством — обычная воронка
 * ({@code BlastGameTests.craterTest}): пустоты под ним нет — пробоя нет.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BreachGameTests {
    /** Середина площадки «range»: верхний блок пола (пол — 11 слоёв, y 1…11 площадки). */
    private static final BlockPos TOP = new BlockPos(32, 11, 32);
    /** Полуширина плиты и пустоты под ней, блоков: дальше радиуса пробоя любой боевой части. */
    private static final int HALF = 8;

    private BreachGameTests() {}

    /**
     * Плита из камня толщиной {@code thickness} (верх — {@link #TOP}) над пустотой в 3 блока; удар боевой части
     * {@code weapon} в середину плиты: ракета — носом о грань (точка на верхней грани), шахед — в точку прицела (полблока
     * в верхний блок, как {@code Target}). Радиус пробоя камня — R; путь от грани до низа плиты — не длиннее R: дыра
     * на нижней грани — круг радиусом √(R² − t²).
     */
    private static void slab(GameTestHelper h, WeaponType weapon, int thickness, boolean breaks) {
        slab(h, weapon, Blocks.STONE.defaultBlockState(), thickness, breaks, true);
    }

    /**
     * То же плитой из блока {@code block}. Опора, которую сервер шлёт клиенту в пакете взрыва ({@link Warheads#support}:
     * картинка цели — плита над пустотой или опора), — тот же итог: плита там и только там, где лучи пробоя её сносят,
     * толщиной в плиту (ревью фронта F: клиент мерил плиту своим правилом и показывал пролом там, где сервер не пробивал).
     * Без {@code blast} — только опора: у досок волна ломает и то, что дальше пробоя (их кривая давление–импульс), и снесённое
     * не отличить от пробитого; луч опоры — тот же {@code SlabBreach.march}, что у пробоя камня с {@code blast}.
     */
    private static void slab(GameTestHelper h, WeaponType weapon, BlockState block, int thickness, boolean breaks, boolean blast) {
        ServerLevel level = h.getLevel();
        boolean debris = IncomingConfig.SERVER.debrisStay.get();
        IncomingConfig.SERVER.debrisStay.set(false);
        StrikeGameTests.afterTest(h, () -> IncomingConfig.SERVER.debrisStay.set(debris));
        BlockPos top = h.absolutePos(TOP);
        int bottom = top.getY() - thickness + 1;
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-HALF - 1, -thickness + 1, -HALF - 1), top.offset(HALF + 1, 0, HALF + 1))) {
            level.setBlock(p, block, Block.UPDATE_CLIENTS);
        }
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-HALF, -thickness - 2, -HALF), top.offset(HALF, -thickness, HALF))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        double tnt = weapon.spec().charge();
        BreachModel.Material material = ModDataMaps.Penetrability.of(block).breaching();
        h.assertTrue(material != null, "у блока нет группы пробоя: " + block);
        double r = material.radius(tnt, BreachModel.UNTAMPED);
        h.assertTrue(breaks == r > thickness, String.format(Locale.ROOT, "проверка пустая: R %.2f м, плита %d м", r, thickness));
        Vec3 at = weapon == WeaponType.MISSILE ? Vec3.atBottomCenterOf(top.above()) : Vec3.atCenterOf(top);
        // опора пакета — до взрыва: пакет уходит раньше лучей
        S2C.Blast.Support support = Warheads.support(level, at, tnt, S2C.Blast.DOWN, null).orElse(null);
        h.assertTrue(support != null, "заряд у грани плиты, а опоры в пакете нет");
        h.assertTrue(!support.loose(), "плита — не рыхлое");
        h.assertTrue(breaks == support.slab() > 0, String.format(Locale.ROOT, "опора пакета: плита %.2f м, а пробой %s", support.slab(), breaks));
        if (breaks) {
            h.assertTrue(Math.abs(support.slab() - thickness) < 0.15, String.format(Locale.ROOT, "толщина в пакете %.2f м, плита %d м", support.slab(), thickness));
            h.assertTrue(Math.abs(support.breach() - r) < 0.01, String.format(Locale.ROOT, "радиус пробоя в пакете %.2f м, закон %.2f м", support.breach(), r));
        }
        if (!blast) {
            h.succeed();
            return;
        }
        Warheads.detonate(level, weapon, at, null, null);
        double hole = breaks ? Math.sqrt(r * r - thickness * thickness) : 0;
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "очередь попаданий не пуста");
            int gone = 0, missed = 0, far = 0, bottomGone = 0;
            for (int dx = -HALF; dx <= HALF; dx++) {
                for (int dz = -HALF; dz <= HALF; dz++) {
                    // ближняя точка блока к оси удара в плане
                    double near = Math.hypot(Math.max(Math.abs(dx) - 0.5, 0), Math.max(Math.abs(dz) - 0.5, 0));
                    for (int y = bottom; y <= top.getY(); y++) {
                        BlockState s = level.getBlockState(new BlockPos(top.getX() + dx, y, top.getZ() + dz));
                        if (!s.isAir()) {
                            // на нижней грани внутри круга пробоя — должно быть снесено; запас полблока: 1352 луча у края
                            // дыры ложатся через ~0,3 м, и в угол блока, который заходит в круг на десятки сантиметров,
                            // луч может не попасть
                            if (breaks && y == bottom && near <= hole - 0.5) missed++;
                            continue;
                        }
                        gone++;
                        if (y == bottom) bottomGone++;
                        // за радиусом пробоя (и воронкой в камне) ничего не снесено: пролёт стоит
                        if (near > Math.max(hole, r) + 0.6) far++;
                    }
                }
            }
            String got = String.format(Locale.ROOT, "%s (%.0f кг) по плите %d м: R %.2f м, дыра снизу r %.2f м; снесено %d, на нижней грани %d, "
                    + "не снесено в круге %d, снесено за ним %d", weapon, tnt, thickness, r, hole, gone, bottomGone, missed, far);
            Incoming.LOG.info("BLASTBENCH пробой {}", got);
            h.assertTrue(far == 0, got);
            if (breaks) {
                h.assertTrue(missed == 0, got);
            } else {
                h.assertTrue(bottomGone == 0, got);
            }
        });
    }

    /** Ракета (150 кг): метр камня над пустотой — дыра снизу ~5 м поперёк (настил моста, жалоба 23.10). */
    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_missile", skyAccess = true)
    public static void missileBreachesMetreOfStone(GameTestHelper h) {
        slab(h, WeaponType.MISSILE, 1, true);
    }

    /** Ракета: три метра камня над пустотой держат (R 2,7 м) — сверху воронка, снизу целое. */
    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_missile_thick", skyAccess = true)
    public static void missileDoesNotBreachThreeMetres(GameTestHelper h) {
        slab(h, WeaponType.MISSILE, 3, false);
    }

    /** Шахед (50 кг): метр камня пробит (R 1,75 м). */
    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_drone", skyAccess = true)
    public static void droneBreachesMetreOfStone(GameTestHelper h) {
        slab(h, WeaponType.DRONE, 1, true);
    }

    /** Шахед (50 кг): метр досок над пустотой пробит (R дерева ~1,8 м) — крыша: опора в пакете — плита. */
    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_drone_planks", skyAccess = true)
    public static void droneBreachesMetreOfPlanks(GameTestHelper h) {
        slab(h, WeaponType.DRONE, Blocks.OAK_PLANKS.defaultBlockState(), 1, true, false);
    }

    /**
     * «Град» (6,4 кг): метр досок над пустотой держит (R дерева ~0,9 м) — опора в пакете не плита (ревью фронта F: клиент
     * мерил толщину от точки взрыва, а не от грани, и показывал пролом, которого сервер не делал).
     */
    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_rocket_planks", skyAccess = true)
    public static void rocketDoesNotBreachMetreOfPlanks(GameTestHelper h) {
        slab(h, WeaponType.ROCKET, Blocks.OAK_PLANKS.defaultBlockState(), 1, false, false);
    }

    /** Шахед: два метра камня держат. */
    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_drone_thick", skyAccess = true)
    public static void droneDoesNotBreachTwoMetres(GameTestHelper h) {
        slab(h, WeaponType.DRONE, 2, false);
    }

    /**
     * Удар ракеты в землю в 3 м от уступа рельефа в блок (жалоба ревью: пологие лучи по верхнему слою выходили в
     * пустоту у уступа и срезали клин земли до 4 м — вчетверо дальше воронки). Пустота у уступа — под открытым небом: это
     * поверхность земли, грань закона воронки, а не тыльная грань плиты: пробоя нет. Без воронки и обломков — только
     * главный взрыв заряда, приложенного к грани.
     */
    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_terrace", skyAccess = true)
    public static void terraceStepIsNoBreachFace(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos top = h.absolutePos(TOP);
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-HALF, -2, -HALF), top.offset(HALF, 0, HALF))) {
            level.setBlock(p, Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        for (BlockPos p : BlockPos.betweenClosed(top.offset(3, 0, -HALF), top.offset(HALF, 0, HALF))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        double tnt = WeaponType.MISSILE.spec().charge();
        double r = BreachModel.Material.EARTH.radius(tnt, BreachModel.UNTAMPED);
        h.assertTrue(r > 3.5, String.format(Locale.ROOT, "проверка пустая: R земли %.2f м", r));
        S2C.Blast.Support support = Warheads.support(level, Vec3.atBottomCenterOf(top.above()), tnt, S2C.Blast.DOWN, null).orElse(null);
        h.assertTrue(support != null && support.loose() && support.slab() == 0, "опора пакета у уступа — грунт, не плита: " + support);
        Warheads.Probe blast = Warheads.testSurfaceCharge(level, Vec3.atBottomCenterOf(top.above()), tnt, 2 * r + 4);
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "взрыв не кончился");
            h.assertTrue(blast.breached() == 0, "у уступа под открытым небом снесено пробоем " + blast.breached() + " блоков");
        });
    }

    /**
     * Заряд в земле на глубине 6,5 м под открытым небом (как бомба на приведённой глубине ~0,5; 1210 кг — доля, что
     * осталась в земле): радиус пробоя земли ~10 м больше глубины, но поверхность — грань закона воронки
     * ({@code BuriedBlast}), пробой к ней не идёт (жалоба ревью: конус земли до поверхности сверх воронки выброса).
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "blast_breach_buried_open", skyAccess = true)
    public static void buriedChargeLeavesTheSurfaceToTheCrater(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(new BlockPos(16, 1, 16)), h.absolutePos(new BlockPos(47, 20, 47)))) {
            level.setBlock(p, Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        Vec3 at = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 14, 32)));
        double tnt = 1210;
        double r = BreachModel.Material.EARTH.radius(tnt, BreachModel.TAMPED);
        h.assertTrue(r > 6.5 + 1 && r < 12.5, String.format(Locale.ROOT, "проверка пустая: R %.2f м", r));
        ChunkPos c = new ChunkPos(BlockPos.containing(at));
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) level.getChunk(c.x + dx, c.z + dz);
        Warheads.Probe blast = Warheads.testBuriedCharge(level, at, tnt, r + 4);
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "взрыв не кончился");
            h.assertTrue(blast.breached() == 0, "к открытой поверхности снесено пробоем " + blast.breached() + " блоков");
            h.assertTrue(h.getBlockState(new BlockPos(32, 20, 32)).is(Blocks.DIRT), "верх над зарядом снесён пробоем");
        });
    }

    /**
     * Заряд бомбы (2423 кг) в толще камня (забит, C = 1: R ≈ 8,2 м): пустота в 4,5 м — пробита (порода между зарядом и
     * ею снесена), пустота в 9,5 м — нет (стена к ней цела). Закон и путь — те же, что у подрыва бомбы
     * ({@code Warheads.BunkerBlast}: волна заряда в толще); полость, дробление и трещины — его же работа, здесь их нет.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "blast_breach_buried", skyAccess = true)
    public static void buriedChargeBreachesNearVoidOnly(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(new BlockPos(16, 1, 16)), h.absolutePos(new BlockPos(47, 28, 47)))) {
            level.setBlock(p, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        // пустоты 3×3×3: ближняя — грань в x 37 (4,5 м от центра заряда), дальняя — грань в x 23 (9,5 м)
        for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(new BlockPos(37, 13, 31)), h.absolutePos(new BlockPos(39, 15, 33)))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(new BlockPos(20, 13, 31)), h.absolutePos(new BlockPos(22, 15, 33)))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        Vec3 at = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 14, 32)));
        double tnt = WeaponType.BUNKER.spec().warheadTnt();
        double r = BreachModel.Material.ROCK.radius(tnt, BreachModel.TAMPED);
        h.assertTrue(r > 4.5 + 0.5 && r < 9.5, String.format(Locale.ROOT, "проверка пустая: R %.2f м", r));
        ChunkPos c = new ChunkPos(BlockPos.containing(at));
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) level.getChunk(c.x + dx, c.z + dz);
        Warheads.Probe blast = Warheads.testBuriedCharge(level, at, tnt, r + 4);
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "взрыв не кончился");
            int near = 0, far = 0;
            for (int x = 33; x <= 36; x++) if (h.getBlockState(new BlockPos(x, 14, 32)).isAir()) near++;
            for (int x = 23; x <= 31; x++) if (h.getBlockState(new BlockPos(x, 14, 32)).isAir()) far++;
            String got = String.format(Locale.ROOT, "бомба %.0f кг в камне: R %.2f м; снесено пробоем %d, к ближней пустоте %d из 4, к дальней %d",
                    tnt, r, blast.breached(), near, far);
            Incoming.LOG.info("BLASTBENCH пробой {}", got);
            h.assertTrue(near == 4, got);
            h.assertTrue(far == 0, got);
        });
    }

    /**
     * Опора пакета ({@link Warheads#support}: вид взрыва у клиента — плита над пустотой или опора) — то же решение, что сделал
     * пробой сервера этим зарядом ({@code Probe.breached}): взрыв с тем же курсом снимает лучами плиту там и только там, где
     * опора — плита. {@code cell} — блок площадки, в середину которого удар (точка прицела — полблока в блок); {@code breaks} —
     * что ждём по закону.
     */
    private static void oneDecision(GameTestHelper h, String what, Vec3 at, Vec3 axis, double tnt, boolean breaks) {
        ServerLevel level = h.getLevel();
        ChunkPos c = new ChunkPos(BlockPos.containing(at));
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.getChunk(c.x + dx, c.z + dz);
        // опора пакета — до взрыва: пакет уходит раньше лучей
        S2C.Blast.Support support = Warheads.support(level, at, tnt, axis, null).orElse(null);
        boolean slab = support != null && support.slab() > 0;
        Warheads.Probe blast = Warheads.testSurfaceCharge(level, at, tnt, 2 * BreachModel.Material.EARTH.radius(tnt, BreachModel.UNTAMPED) + 4, axis);
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "взрыв не кончился");
            String got = String.format(Locale.ROOT, "%s (%.1f кг): опора пакета %s, пробой снёс %d блоков", what, tnt, support, blast.breached());
            Incoming.LOG.info("BLASTBENCH пробой опора {}", got);
            h.assertTrue(slab == blast.breached() > 0, got);
            h.assertTrue(slab == breaks, got);
        });
    }

    /** Крыша в один слой {@code roof} над помещением 9×9×3, вырытым в полу площадки; верх крыши — {@link #TOP}. */
    private static void roof(GameTestHelper h, BlockState roof) {
        ServerLevel level = h.getLevel();
        BlockPos top = h.absolutePos(TOP);
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-4, -3, -4), top.offset(4, -1, 4))) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-5, 0, -5), top.offset(5, 0, 5))) level.setBlock(p, roof, Block.UPDATE_CLIENTS);
    }

    /**
     * Ракета (150 кг) под углом паспорта (45° к горизонту) по настилу в 2 м камня над пустотой: R камня 2,7 м — пробит (дыра
     * ~1,8 м). По курсу путь сквозь настил — 2,8 м, длиннее R: опора по лучу вдоль курса (14f6c04) «не пробивала» то, что лучи
     * сносят; пробой решают лучи у нормали — по ней и опора.
     */
    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_support", skyAccess = true)
    public static void obliqueMissileOnTwoMetresOneDecision(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos top = h.absolutePos(TOP);
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-HALF - 1, -1, -HALF - 1), top.offset(HALF + 1, 0, HALF + 1))) {
            level.setBlock(p, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-HALF, -4, -HALF), top.offset(HALF, -2, HALF))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        double tnt = WeaponType.MISSILE.spec().charge(), r = BreachModel.Material.ROCK.radius(tnt, BreachModel.UNTAMPED);
        Vec3 axis = new Vec3(0, -1, 1).normalize();
        h.assertTrue(r > 2 && r < 2 * Math.sqrt(2), String.format(Locale.ROOT, "проверка пустая: R %.2f м", r));
        oneDecision(h, "ракета под 45° по 2 м камня", Vec3.atBottomCenterOf(top.above()), axis, tnt, true);
    }

    /** «Град» (6,4 кг) по метру досок, точка — полблока в крышу: R досок 0,885 м — не пробит. */
    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_support", skyAccess = true)
    public static void gradOnPlanksOneDecision(GameTestHelper h) {
        double tnt = WeaponType.ROCKET.spec().charge();
        h.assertTrue(BreachModel.Material.POOR.radius(tnt, BreachModel.UNTAMPED) < 1, "проверка пустая: «Град» пробивает метр досок");
        roof(h, Blocks.OAK_PLANKS.defaultBlockState());
        oneDecision(h, "«Град» по доскам", Vec3.atCenterOf(h.absolutePos(TOP)), S2C.Blast.DOWN, tnt, false);
    }

    /**
     * Крыша из нижних полублоков (ревью 1, B3): у прежнего зонда клиента — не твёрдое (верх не полная грань), вида крыши не было;
     * для пробоя блок с формой столкновений — преграда. Шахед — пробит, «Град» — нет.
     */
    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_support", skyAccess = true)
    public static void droneOnHalfSlabRoofOneDecision(GameTestHelper h) {
        roof(h, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        oneDecision(h, "шахед по полублокам", Vec3.atCenterOf(h.absolutePos(TOP)), S2C.Blast.DOWN, WeaponType.DRONE.spec().charge(), true);
    }

    /** То же «Градом». */
    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_support", skyAccess = true)
    public static void gradOnHalfSlabRoofOneDecision(GameTestHelper h) {
        roof(h, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        oneDecision(h, "«Град» по полублокам", Vec3.atCenterOf(h.absolutePos(TOP)), S2C.Blast.DOWN, WeaponType.ROCKET.spec().charge(), false);
    }

    /** Обсидиан (сопротивление 1200, ревью 1, B3 — STOP): заряд его не смещает — не пробит. */
    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_support", skyAccess = true)
    public static void blastProofRoofOneDecision(GameTestHelper h) {
        roof(h, Blocks.OBSIDIAN.defaultBlockState());
        oneDecision(h, "шахед по обсидиану", Vec3.atCenterOf(h.absolutePos(TOP)), S2C.Blast.DOWN, WeaponType.DRONE.spec().charge(), false);
    }

    /**
     * Стена из камня в метр на полу площадки, удар шахеда сбоку (курс +x с наклоном вниз) в середину блока; за стеной —
     * пустота. Над ней одна крона (листва, которую не обрывает распад) — открытое небо для пробоя (карта высот без листвы,
     * ревью 1, B3): опора; под каменным навесом — перекрытие, метр камня пробит (R 1,75 м). Нормаль грани — +x, не курс.
     */
    private static void wall(GameTestHelper h, boolean crown) {
        ServerLevel level = h.getLevel();
        BlockPos top = h.absolutePos(TOP);
        for (BlockPos p : BlockPos.betweenClosed(top.offset(0, 1, -6), top.offset(0, 4, 6))) level.setBlock(p, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        BlockState over = crown ? Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true) : Blocks.STONE.defaultBlockState();
        for (BlockPos p : BlockPos.betweenClosed(top.offset(1, 6, -6), top.offset(8, crown ? 7 : 6, 6))) level.setBlock(p, over, Block.UPDATE_CLIENTS);
        oneDecision(h, crown ? "шахед в стену под кроной" : "шахед в стену под навесом", Vec3.atCenterOf(top.offset(0, 2, 0)),
                new Vec3(1, -0.3, 0).normalize(), WeaponType.DRONE.spec().charge(), !crown);
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_support", skyAccess = true)
    public static void crownOverTheExitOneDecision(GameTestHelper h) {
        wall(h, true);
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_support", skyAccess = true)
    public static void roofOverTheExitOneDecision(GameTestHelper h) {
        wall(h, false);
    }
}
