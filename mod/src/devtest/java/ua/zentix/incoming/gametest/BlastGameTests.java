package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.model.BlastDamage;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.CraterModel;
import ua.zentix.incoming.nuclear.model.FuelFireball;
import ua.zentix.incoming.nuclear.model.PulseIgnition;
import ua.zentix.incoming.nuclear.world.BlockResponse;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.Warheads;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

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
     * Столбики из одного материала, каждый на своём луче (сломанный блок гасит часть волны — за ним проверять открытое
     * поле нельзя), на 0,6–1,35 радиуса Джаррета своего порога: дальний сломанный и ближний уцелевший — в ±20 % от
     * него. Порог у каждого блока свой — разброс ±15 % по хэшу места ({@link BlockResponse#jitterAt}), а площадка
     * GameTest каждый раз в новом месте: радиус Джаррета считается по порогу самого столбика, а места выбираются так,
     * чтобы столбики стояли на одних и тех же долях своего радиуса при любом месте площадки.
     */
    private static void radiusTest(GameTestHelper h, Block material, double psi, double tnt) {
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(CENTER);
        Vec3 at = Vec3.atCenterOf(c);
        double nominal = BlastDamage.breakRange(BlastModel.kpa(psi), tnt);
        List<Post> posts = layPosts(c, at, psi, tnt, nominal, List.of(), b -> true);
        h.assertTrue(posts.size() == POST_AT.length, "места для столбиков на своих лучах не нашлось: " + posts.size());
        // трава вокруг (до 1,7 радиуса): уцелевшая трава волну не держит — столбики за ней ломаются так же
        int n = (int) Math.ceil(nominal * 1.7);
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-n, 0, -n), c.offset(n, 0, n))) {
            if (level.getBlockState(p).isAir()) level.setBlock(p, Blocks.SHORT_GRASS.defaultBlockState(), 2);
        }
        for (Post p : posts) level.setBlock(p.pos(), material.defaultBlockState(), 2);
        Warheads.Probe blast = Warheads.testCharge(level, at, tnt);
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "взрыв не кончился");
            // доли радиуса Джаррета своего порога; сломан — материала на месте нет (вал воронки может лечь на снесённое)
            double broken = 0, kept = Double.MAX_VALUE;
            StringBuilder rows = new StringBuilder();
            for (Post p : posts) {
                boolean gone = !level.getBlockState(p.pos()).is(material);
                if (gone) broken = Math.max(broken, p.share());
                else kept = Math.min(kept, p.share());
                rows.append(String.format(Locale.ROOT, " %.2f%s", p.share(), gone ? "x" : "|"));
            }
            String got = String.format(Locale.ROOT, "%s, %.1f кг: сломан до %.2f, цел с %.2f своего радиуса Джаррета (%.1f м без разброса); столбики:%s",
                    material, tnt, broken, kept, nominal, rows);
            Incoming.LOG.info("BLASTBENCH {}", got);
            h.assertTrue(broken >= 0.7 && broken <= 1.2, got);
            h.assertTrue(kept >= 0.7 && kept <= 1.3, got);
        });
    }

    /**
     * Доли радиуса Джаррета своего порога, на которых стоят столбики: по обе стороны от краёв допуска (0,7 и 1,2–1,3),
     * глубоко в зоне и за ней. Край модели — 0,9–1,11 радиуса своего порога (закон давление–импульс делит на разброс и
     * давление, и импульс). Места выбираются в этом порядке: на тесном круге кладки у шахеда (столбики в 2–4,5 м,
     * каждый закрывает 12–28° азимута) лучшие места достаются столбикам у краёв допуска.
     */
    static final double[] POST_AT = {0.75, 1.25, 0.8, 1.2, 0.7, 1.35, 0.6, 0.9, 1.0, 1.1};
    /** Зазор между столбиками по азимуту сверх их угловых полуширин, рад: луч к дальнему не задевает ближний. */
    private static final double POST_GAP = Math.toRadians(3);

    /**
     * Столбик: место, расстояние от заряда до ближней точки блока (луч ломает блок, войдя в него), радиус Джаррета его
     * порога, азимут и угловая полуширина блока от заряда.
     */
    record Post(BlockPos pos, double distance, double jarrett, double bearing, double halfWidth) {
        double share() {
            return distance / jarrett;
        }
    }

    /**
     * Места столбиков в слое заряда: для каждой доли {@link #POST_AT} — блок, у которого расстояние, делённое на радиус
     * Джаррета его порога, ближе всего к ней, и который по азимуту не перекрывает уже выбранные. Зависит только от
     * порогов мест, а их разброс — от места площадки: доли выходят одни и те же везде. {@code taken} — уже стоящие
     * столбики другого материала: новые не перекрывают и их; {@code side} — какие азимуты (рад, от +x к +z) годятся.
     */
    static List<Post> layPosts(BlockPos c, Vec3 at, double psi, double tnt, double nominal, List<Post> taken,
                               java.util.function.DoublePredicate side) {
        int n = (int) Math.ceil(nominal * 1.6);
        Set<BlockPos> sampled = sampledPosts(c, at, n);
        List<Post> candidates = new ArrayList<>();
        for (int dx = -n; dx <= n; dx++) {
            for (int dz = -n; dz <= n; dz++) {
                BlockPos p = c.offset(dx, 0, dz);
                if (!sampled.contains(p)) continue;
                double distance = Math.sqrt(new AABB(p).distanceToSqr(at));
                if (distance < 1 || !side.test(Math.atan2(p.getZ() + 0.5 - at.z, p.getX() + 0.5 - at.x))) continue;
                double jarrett = BlastDamage.breakRange(BlastModel.kpa(psi * BlockResponse.jitterAt(p)), tnt);
                double bearing = Math.atan2(p.getZ() + 0.5 - at.z, p.getX() + 0.5 - at.x), half = 0;
                for (int cx = 0; cx <= 1; cx++) {
                    for (int cz = 0; cz <= 1; cz++) {
                        half = Math.max(half, Math.abs(Mth.wrapDegrees(Math.toDegrees(
                                Math.atan2(p.getZ() + cz - at.z, p.getX() + cx - at.x) - bearing))));
                    }
                }
                candidates.add(new Post(p, distance, jarrett, bearing, Math.toRadians(half)));
            }
        }
        List<Post> posts = new ArrayList<>(), all = new ArrayList<>(taken);
        for (double share : POST_AT) {
            Post best = null;
            for (Post q : candidates) {
                boolean free = true;
                for (Post o : all) {
                    double apart = Math.abs(Math.toRadians(Mth.wrapDegrees(Math.toDegrees(q.bearing() - o.bearing()))));
                    if (apart < q.halfWidth() + o.halfWidth() + POST_GAP) free = false;
                }
                if (free && (best == null || Math.abs(q.share() - share) < Math.abs(best.share() - share))) best = q;
            }
            if (best == null) break;
            posts.add(best);
            all.add(best);
        }
        return posts;
    }

    /**
     * A one-block post must intersect an actual 0.3 m sample of the 1352-ray charge wave. The even 16³ direction
     * lattice has no zero component: (0, 9), for example, can lie between rays from the bottom centre of a block.
     * The position hash changes which post is closest to 0.6 R; it used to select such a gap on some test origins.
     * Keep every requested share and threshold, but choose measurable posts rather than changing the pressure law.
     */
    static Set<BlockPos> sampledPosts(BlockPos c, Vec3 at, int n) {
        Set<BlockPos> sampled = new HashSet<>();
        for (int j = 0; j < 16; j++) for (int k = 0; k < 16; k++) for (int l = 0; l < 16; l++) {
            if (j != 0 && j != 15 && k != 0 && k != 15 && l != 0 && l != 15) continue;
            double x = (float) j / 15.0F * 2.0F - 1.0F;
            double y = (float) k / 15.0F * 2.0F - 1.0F;
            double z = (float) l / 15.0F * 2.0F - 1.0F;
            double len = Math.sqrt(x * x + y * y + z * z);
            x /= len; y /= len; z /= len;
            for (double d = 0; d <= 2 * n + 2; d += 0.3) {
                BlockPos p = BlockPos.containing(at.x + x * d, at.y + y * d, at.z + z * d);
                if (p.getY() != c.getY()) break;
                sampled.add(p);
            }
        }
        return sampled;
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
     * Воронка ракеты — по закону заряда (D = 0,65·∛W, глубина — четверть) в своём грунте: сухой (почва, песок, гравий
     * насыпи) — множитель 1 (жалоба 23.10: гравий под путями считался скалой, ×0,7, песок — насыщенным водой, ×1,3).
     * Одна воронка ракеты — ~4 м³ блоками со случайной добавкой (1–8 блоков), поэтому девять воронок сеткой через 12 м и
     * сумма: в среднем 9 × 4,0 = 36 м³; скала дала бы 12, насыщенный грунт — 79. Обломки на время проверки не ложатся
     * блоками — считается только снятый грунт; каждая яма — не дальше вала (2 r + 1).
     */
    private static void craterTest(GameTestHelper h, Block ground, CraterModel.Soil soil) {
        ServerLevel level = h.getLevel();
        boolean debris = IncomingConfig.SERVER.debrisStay.get();
        IncomingConfig.SERVER.debrisStay.set(false);
        StrikeGameTests.afterTest(h, () -> IncomingConfig.SERVER.debrisStay.set(debris));
        BlockPos grass = h.absolutePos(GRASS);
        h.assertTrue(ua.zentix.incoming.warhead.GroundMaterial.of(ground.defaultBlockState()).soil() == soil, ground + " — не " + soil);
        // три верхних слоя — проверяемый грунт: воронка ракеты глубиной меньше метра целиком в нём
        for (BlockPos p : BlockPos.betweenClosed(grass.offset(-18, -2, -18), grass.offset(18, 0, 18))) level.setBlock(p, ground.defaultBlockState(), 2);
        List<BlockPos> soilBlocks = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(grass.offset(-18, -4, -18), grass.offset(18, 0, 18))) soilBlocks.add(p.immutable());
        double tnt = WeaponType.MISSILE.spec().charge(), rc = CraterModel.chargeRadius(tnt, soil);
        List<Vec3> hits = new ArrayList<>();
        for (int i = -1; i <= 1; i++) {
            for (int k = -1; k <= 1; k++) hits.add(Vec3.atBottomCenterOf(grass.offset(12 * i, 1, 12 * k)));
        }
        for (Vec3 at : hits) Warheads.detonate(level, WeaponType.MISSILE, at, null, null);
        double law = hits.size() * CraterModel.chargeVolume(tnt, soil);
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "очередь попаданий не пуста");
            int dug = 0;
            double far = 0;
            for (BlockPos p : soilBlocks) {
                if (!level.getBlockState(p).isAir()) continue;
                dug++;
                double near = Double.MAX_VALUE;
                for (Vec3 at : hits) near = Math.min(near, Math.hypot(p.getX() + 0.5 - at.x, p.getZ() + 0.5 - at.z));
                far = Math.max(far, near);
            }
            String got = String.format(Locale.ROOT, "%s: снято %d блоков в %d воронках до %.1f м (закон: %.1f м³, r %.1f)",
                    ground, dug, hits.size(), far, law, rc);
            Incoming.LOG.info("BLASTBENCH воронка {}", got);
            h.assertTrue(dug >= 0.6 * law && dug <= 1.45 * law, got);
            h.assertTrue(far <= 2 * rc + 1, got);
        });
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_crater", skyAccess = true)
    public static void craterMatchesChargeLaw(GameTestHelper h) {
        craterTest(h, Blocks.DIRT, CraterModel.Soil.DRY);
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_crater_gravel", skyAccess = true)
    public static void craterInRailBallastIsLooseGround(GameTestHelper h) {
        craterTest(h, Blocks.GRAVEL, CraterModel.Soil.DRY);
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_crater_sand", skyAccess = true)
    public static void craterInDrySandIsDryGround(GameTestHelper h) {
        craterTest(h, Blocks.SAND, CraterModel.Soil.DRY);
    }

    /**
     * Огонь после шара топлива (жалоба 23.10: после ракеты горел почти каждый блок в круге — крыши, песок, рельсы).
     * Вокруг удара на траве: столбы брёвен (толстое дерево) на лучах 3–20 м, столбы листвы и сена (тонкое) на своих лучах
     * внутри шара, бревно у самой воронки, трава и маки на траве (горят на своём месте). Огонь ставится только у горючего: у листвы и сена — с долей их порога
     * ({@link PulseIgnition}), у брёвен — только в круге горящего топлива воронки; ни один огонь не стоит на полу, что сам
     * не горит (пол — трава-дёрн; так «горели» песок и рельсы), и все — в шаре. У «Града» топлива нет — огня нет. Огонь ванили на время проверки не растёт
     * ({@code doFireTick}), волна блоков не ломает: считается только поджиг.
     */
    private static void fireTest(GameTestHelper h, WeaponType weapon) {
        ServerLevel level = h.getLevel();
        boolean debris = IncomingConfig.SERVER.debrisStay.get(), damage = IncomingConfig.SERVER.blockDamage.get();
        IncomingConfig.SERVER.debrisStay.set(false);
        // волна не ломает: листву и сено взрыв снёс бы раньше шара, а проверяется поджиг
        IncomingConfig.SERVER.blockDamage.set(false);
        GameRules.BooleanValue tick = level.getGameRules().getRule(GameRules.RULE_DOFIRETICK);
        boolean ticked = tick.get();
        tick.set(false, level.getServer());
        StrikeGameTests.afterTest(h, () -> {
            IncomingConfig.SERVER.debrisStay.set(debris);
            IncomingConfig.SERVER.blockDamage.set(damage);
            tick.set(ticked, level.getServer());
        });
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        WeaponSpec spec = weapon.spec();
        double ballKg = FuelFireball.ballKg(spec.fuelAtImpact());
        double r = FuelFireball.diameter(ballKg) / 2, wet = CraterModel.chargeRadius(spec.charge(), CraterModel.Soil.DRY);
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        // брёвна: 16 лучей, 3–20 м; тонкое — между ними, 2,5 м до края шара
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8, d = 3 + i * 17 / 15.0;
            column(level, at, a, d, Blocks.OAK_LOG.defaultBlockState());
            double thin = 2.5 + (Math.max(r, 3) - 3) * i / 15.0;
            column(level, at, a + Math.PI / 16, thin, i % 2 == 0 ? leaves : Blocks.HAY_BLOCK.defaultBlockState());
        }
        // бревно у самой воронки — в круге горящего топлива
        level.setBlock(BlockPos.containing(at.x + 1, at.y, at.z), Blocks.OAK_LOG.defaultBlockState(), 2);
        // растения на траве (трава, цветы) по кругу в 2,5 м до края шара: горят на своём месте
        java.util.Set<BlockPos> plants = new java.util.HashSet<>();
        for (int i = 0; i < 24; i++) {
            double a = (i + 0.25) * Math.PI / 12, d = 2.5 + (Math.max(r, 3) - 3) * (i % 6) / 5.0;
            BlockPos p = BlockPos.containing(at.x + Math.cos(a) * d, at.y, at.z + Math.sin(a) * d);
            if (!level.getBlockState(p).isAir()) continue;
            level.setBlock(p, (i % 2 == 0 ? Blocks.SHORT_GRASS : Blocks.POPPY).defaultBlockState(), 2);
            plants.add(p.immutable());
        }
        Warheads.detonate(level, weapon, at, null, null);
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "очередь попаданий не пуста");
            int fires = 0, thin = 0, solid = 0, floor = 0, plant = 0;
            double far = 0;
            for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(CENTER.offset(-24, -1, -24)), h.absolutePos(CENTER.offset(24, 6, 24)))) {
                if (!(level.getBlockState(p).getBlock() instanceof BaseFireBlock)) continue;
                fires++;
                far = Math.max(far, Vec3.atCenterOf(p).distanceTo(at));
                boolean byThin = false, bySolid = false;
                for (Direction d : Direction.values()) {
                    BlockState n = level.getBlockState(p.relative(d));
                    if (n.is(Blocks.OAK_LEAVES) || n.is(Blocks.HAY_BLOCK)) byThin = true;
                    else if (n.is(Blocks.OAK_LOG)) bySolid = true;
                }
                // огонь на месте растения — растение горит само; иначе огонь на твёрдом полу, что сам не горит (песок,
                // камень, дёрн), — огонь «на земле» рядом с горючим
                BlockPos below = p.below();
                BlockState under = level.getBlockState(below);
                if (plants.contains(p)) plant++;
                else if (under.isFaceSturdy(level, below, Direction.UP) && !under.isFlammable(level, below, Direction.UP)) floor++;
                else if (byThin) thin++;
                else if (bySolid) {
                    solid++;
                    // бревно горит только там, где на нём лежит горящее топливо воронки
                    h.assertTrue(Vec3.atCenterOf(p).distanceTo(at) <= wet + 1.5,
                            String.format(Locale.ROOT, "%s: огонь у бревна в %.1f м — дальше круга топлива %.1f м", weapon, Vec3.atCenterOf(p).distanceTo(at), wet));
                } else floor++;

            }
            String got = String.format(Locale.ROOT, "%s: огня %d (у тонкого %d, растений %d, у брёвен %d, на пустом полу %d) до %.1f м (шар %.1f м, топливо %.1f м)",
                    weapon, fires, thin, plant, solid, floor, far, r, wet);
            Incoming.LOG.info("BLASTBENCH {}", got);
            h.assertTrue(floor == 0, got);
            if (r > 0) {
                h.assertTrue(thin >= 4, got);
                // растений в шаре — 24 (половина — трава, у шахеда загорается треть-половина, у ракеты — почти все)
                h.assertTrue(plant >= 3, got);
                h.assertTrue(far <= r + 1.5, got);
            } else h.assertTrue(fires == 0, got);
        });
    }

    /** Столб в три блока на луче под углом {@code a} в {@code d} м от {@code at}. */
    private static void column(ServerLevel level, Vec3 at, double a, double d, BlockState state) {
        BlockPos p = BlockPos.containing(at.x + Math.cos(a) * d, at.y, at.z + Math.sin(a) * d);
        for (int y = 0; y < 3; y++) level.setBlock(p.above(y), state, 2);
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_fire_drone", skyAccess = true)
    public static void droneFuelIgnitesOnlyInFireball(GameTestHelper h) {
        fireTest(h, WeaponType.DRONE);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "blast_fire_missile", skyAccess = true)
    public static void missileFireballSparesSolidWood(GameTestHelper h) {
        fireTest(h, WeaponType.MISSILE);
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
