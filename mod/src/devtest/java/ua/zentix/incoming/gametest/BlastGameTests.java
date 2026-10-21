package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.model.BlastDamage;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.CraterModel;
import ua.zentix.incoming.nuclear.model.FuelFireball;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.Warheads;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Взрыв боевой части по заряду (S3): блоки ломаются до радиусов Джаррета своего порога, газы выбивают комнату,
 * воронка — по закону заряда, огонь — только в шаре топлива, тела — по давлению. Радиусы — из паспорта
 * ({@code WeaponSpec.charge}) и закона ({@link BlastDamage}), допуск ±20 % (разброс порогов блоков — ±15 %).
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BlastGameTests {
    /**
     * Середина площадки «range»: блок травы (пол — 11 слоёв: камень, земля, трава; GameTest ставит шаблон на блок выше)
     * и первый воздух над ним.
     */
    private static final BlockPos GRASS = new BlockPos(32, 11, 32), CENTER = GRASS.above();

    private BlastGameTests() {}

    /**
     * Столбики из одного материала на восьми направлениях через 45°, каждый на своём луче (сломанный блок гасит часть
     * волны — за ним проверять открытое поле нельзя), на 0,6–1,4 радиуса Джаррета порога этого материала: дальний
     * сломанный и ближний уцелевший — в ±20 % от него.
     */
    private static void radiusTest(GameTestHelper h, Block material, double psi, double tnt) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER)).add(0, 0.5, 0);
        double expected = BlastDamage.breakRange(BlastModel.kpa(psi), tnt);
        List<BlockPos> posts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4, d = expected * (0.6 + 0.8 * i / 7.0);
            posts.add(BlockPos.containing(at.x + Math.cos(a) * d, at.y, at.z + Math.sin(a) * d));
        }
        // трава вокруг (до 1,5 радиуса): уцелевшая трава волну не держит — столбики за ней ломаются так же
        BlockPos c = h.absolutePos(CENTER);
        int n = (int) Math.ceil(expected * 1.5);
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-n, 0, -n), c.offset(n, 0, n))) {
            if (level.getBlockState(p).isAir()) level.setBlock(p, Blocks.SHORT_GRASS.defaultBlockState(), 2);
        }
        for (BlockPos p : posts) level.setBlock(p, material.defaultBlockState(), 2);
        Warheads.Probe blast = Warheads.testCharge(level, at, tnt);
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "взрыв не кончился");
            double broken = 0, kept = Double.MAX_VALUE;
            for (BlockPos p : posts) {
                // луч ломает блок, войдя в него: расстояние — до ближней точки блока
                double d = new net.minecraft.world.phys.AABB(p).distanceToSqr(at);
                d = Math.sqrt(d);
                if (level.getBlockState(p).isAir()) broken = Math.max(broken, d);
                else kept = Math.min(kept, d);
            }
            String got = String.format(Locale.ROOT, "%s, %.1f кг: сломан до %.1f, цел с %.1f, Джаррет %.1f", material, tnt, broken, kept, expected);
            Incoming.LOG.info("BLASTBENCH {}", got);
            // шаг столбиков — 0,11 радиуса: край зоны между сломанным и уцелевшим
            h.assertTrue(broken >= 0.75 * expected && broken <= 1.2 * expected, got);
            h.assertTrue(kept >= 0.75 * expected && kept <= 1.3 * expected, got);
        });
    }

    /** Шахед (50 кг): кладка ломается до ~3,4 м (Джаррет A). */
    @GameTest(template = "range", timeoutTicks = 200, batch = "blast_radius_brick", skyAccess = true)
    public static void droneBreaksBrickToJarrettA(GameTestHelper h) {
        radiusTest(h, Blocks.STONE_BRICKS, 12, WeaponType.DRONE.spec().charge());
    }

    /** Шахед: доски (лёгкие постройки, 4 psi) ломаются до ~6 м. */
    @GameTest(template = "range", timeoutTicks = 200, batch = "blast_radius_wood", skyAccess = true)
    public static void droneBreaksWoodToJarrettB(GameTestHelper h) {
        radiusTest(h, Blocks.OAK_PLANKS, 4, WeaponType.DRONE.spec().charge());
    }

    /** «Томагавк» (150 кг): кладка — до ~7 м. */
    @GameTest(template = "range", timeoutTicks = 200, batch = "blast_radius_missile", skyAccess = true)
    public static void missileBreaksBrickToJarrettA(GameTestHelper h) {
        radiusTest(h, Blocks.STONE_BRICKS, 12, WeaponType.MISSILE.spec().charge());
    }

    /**
     * Заряд в закрытой комнате (кладка, 5×3×5 воздуха): газы держат давление на всех стенах (UFC 3-340-02) — стены
     * выбиты целиком, хотя волна в открытом поле сломала бы кладку лишь в метре от 10 кг.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "blast_room", skyAccess = true)
    public static void roomBlastBlowsOutWalls(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos base = h.absolutePos(CENTER.offset(-3, 0, -3));
        List<BlockPos> walls = new ArrayList<>();
        for (int x = 0; x < 7; x++) for (int y = 0; y < 5; y++) for (int z = 0; z < 7; z++) {
            boolean wall = x == 0 || x == 6 || y == 0 || y == 4 || z == 0 || z == 6;
            BlockPos p = base.offset(x, y, z);
            level.setBlock(p, wall ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
            if (wall && y > 0) walls.add(p);
        }
        // стены — блоки, к которым газы прилегают гранью (рёбра и углы коробки остаются столбами)
        walls.removeIf(p -> {
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                BlockPos n = p.relative(d);
                if (n.getX() > base.getX() && n.getX() < base.getX() + 6 && n.getY() > base.getY() && n.getY() < base.getY() + 4
                        && n.getZ() > base.getZ() && n.getZ() < base.getZ() + 6) return false;
            }
            return true;
        });
        Vec3 at = Vec3.atCenterOf(base.offset(3, 2, 3));
        h.assertTrue(BlastDamage.breakRange(BlastModel.kpa(12), 10) < 1.5, "в открытом поле 10 кг ломают кладку дальше метра — проверка пустая");
        Warheads.Probe blast = Warheads.testCharge(level, at, 10);
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "взрыв не кончился");
            int gone = 0;
            for (BlockPos p : walls) if (level.getBlockState(p).isAir()) gone++;
            h.assertTrue(gone >= walls.size() * 0.9, "газы выбили " + gone + " из " + walls.size() + " блоков стен и потолка");
        });
    }

    /**
     * Воронка ракеты в сухом грунте — по закону заряда (D = 0,65·∛W, глубина — четверть): снятого грунта в среднем
     * π/4·r³ ≈ 4 м³ (блоками со случайной добавкой — 1–12), и не дальше вала (2 r + 1). У шахеда (≈1,4 м³) ямы в блок
     * может и не быть — проверка по нему была бы монеткой.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "blast_crater", skyAccess = true)
    public static void craterMatchesChargeLaw(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        double tnt = WeaponType.MISSILE.spec().charge();
        BlockPos ground = h.absolutePos(GRASS);
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        List<BlockPos> soil = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(ground.offset(-8, -4, -8), ground.offset(8, 0, 8))) soil.add(p.immutable());
        Warheads.detonate(level, WeaponType.MISSILE, at, null, null);
        double rc = CraterModel.chargeRadius(tnt, CraterModel.Soil.DRY);
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "очередь попаданий не пуста");
            int dug = 0;
            double far = 0;
            for (BlockPos p : soil) {
                if (!level.getBlockState(p).isAir()) continue;
                dug++;
                far = Math.max(far, Math.hypot(p.getX() + 0.5 - at.x, p.getZ() + 0.5 - at.z));
            }
            String got = "снято " + dug + " блоков грунта до " + String.format(Locale.ROOT, "%.1f", far) + " м (закон: "
                    + String.format(Locale.ROOT, "%.1f м³, r %.1f", CraterModel.chargeVolume(tnt, CraterModel.Soil.DRY), rc) + ")";
            Incoming.LOG.info("BLASTBENCH воронка {}", got);
            h.assertTrue(dug >= 1 && dug <= 12, got);
            h.assertTrue(far <= 2 * rc + 1, got);
        });
    }

    /**
     * Шар топлива шахеда (30 кг — 18 м): поленницы на 5–20 м — огонь только у тех, что в шаре (радиус 9 м), и не дальше;
     * у «Града» топлива нет — у тех же поленниц огня нет (обломки не ложатся блоками).
     */
    private static void fireTest(GameTestHelper h, WeaponType weapon) {
        ServerLevel level = h.getLevel();
        boolean debris = IncomingConfig.SERVER.debrisStay.get();
        IncomingConfig.SERVER.debrisStay.set(false);
        StrikeGameTests.afterTest(h, () -> IncomingConfig.SERVER.debrisStay.set(debris));
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8, d = 5 + i;
            BlockPos p = BlockPos.containing(at.x + Math.cos(a) * d, at.y, at.z + Math.sin(a) * d);
            for (int y = 0; y < 3; y++) level.setBlock(p.above(y), Blocks.OAK_LOG.defaultBlockState(), 2);
        }
        Warheads.detonate(level, weapon, at, null, null);
        double r = FuelFireball.diameter(weapon.spec().fuelAtImpact()) / 2;
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "очередь попаданий не пуста");
            int fires = 0;
            double far = 0;
            for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(CENTER.offset(-24, -1, -24)), h.absolutePos(CENTER.offset(24, 6, 24)))) {
                BlockState s = level.getBlockState(p);
                if (!(s.getBlock() instanceof BaseFireBlock)) continue;
                fires++;
                far = Math.max(far, Vec3.atCenterOf(p).distanceTo(at));
            }
            String got = weapon + ": огня " + fires + " до " + String.format(Locale.ROOT, "%.1f м (шар %.1f м)", far, r);
            Incoming.LOG.info("BLASTBENCH {}", got);
            if (r > 0) {
                h.assertTrue(fires > 0, got);
                h.assertTrue(far <= r + 1.5, got);
            } else h.assertTrue(fires == 0, got);
        });
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_fire_drone", skyAccess = true)
    public static void droneFuelIgnitesOnlyInFireball(GameTestHelper h) {
        fireTest(h, WeaponType.DRONE);
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_fire_rocket", skyAccess = true)
    public static void rocketWithoutFuelLightsNothing(GameTestHelper h) {
        fireTest(h, WeaponType.ROCKET);
    }

    /** Тела — по давлению волны ({@code BlastBody}): свинья в 2 м от шахеда погибает, в 30 м цела. */
    @GameTest(template = "range", timeoutTicks = 200, batch = "blast_bodies", skyAccess = true)
    public static void bodiesHurtByPressure(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        Pig near = h.spawn(EntityType.PIG, CENTER.offset(2, 0, 0));
        Pig far = h.spawn(EntityType.PIG, CENTER.offset(-28, 0, 10));
        near.setNoAi(true);
        far.setNoAi(true);
        Warheads.Probe blast = Warheads.testCharge(level, at, WeaponType.DRONE.spec().charge());
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "взрыв не кончился");
            h.assertTrue(!near.isAlive(), "в 2 м жива: " + near.getHealth());
            h.assertTrue(far.isAlive() && far.getHealth() >= far.getMaxHealth() - 0.5f, "в 30 м ранена: " + far.getHealth());
        });
    }
}
