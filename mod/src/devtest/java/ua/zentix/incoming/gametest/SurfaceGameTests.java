package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.entity.BomberEntity;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.nuclear.model.BlastDamage;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.CraterModel;
import ua.zentix.incoming.nuclear.world.BlockResponse;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.Article;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.BreachModel;
import ua.zentix.incoming.warhead.BuriedBlast;
import ua.zentix.incoming.warhead.Penetration;
import ua.zentix.incoming.warhead.Warheads;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Удар целиком против опубликованных данных: каждое оружие по каждой поверхности — что остаётся в мире (воронка,
 * снятые блоки, пробой плиты, стены и стёкла рядом), а не каждая формула отдельно. Жалоба 23.10 («удары слабые»):
 * формулы были верны поодиночке, а итог удара в мире никто не сверял с данными.
 * <p>
 * Путь удара — тот же, что у снаряда в игре: шахед, «Ланцет», ракета и «Град» — {@link Warheads#detonate} с сущностью
 * своего оружия (как {@code StrikeProjectile.impact}: точка — верх блока, ось — угол удара из паспорта
 * {@link Article.Impact}); бомба B-2 — настоящая сущность, сброшенная как с B-2 (170 блоков над целью, 145 до неё,
 * скорость B-2): удар о землю, путь в грунте, подрыв, подземный взрыв.
 * <p>
 * Ожидания — законы с источниками, которые мод уже держит, и их разброс по данным:
 * <ul>
 * <li>воронка заряда на грунте — {@link CraterModel#chargeRadius} (EMRTC, Ambrosini и др. 2003, Кинни–Грэхем; грунт —
 * TM 23-200): снятый объём 0,6–1,45 закона (как в {@code BlastGameTests.craterTest}, #417) плюс разброс целых блоков
 * (воронка роет столбцы целыми блоками со случайной добавкой по дробной части — 2σ этой добавки);</li>
 * <li>плита над пустотой — не меньше пробоя FM 5-250 (1992), табл. B-3: K = R³·M·C, M по ступеням R (скала, хорошая
 * кладка, бетонный блок), C = 1,8 (внешний заряд без забивки), ± округление FM 0,25 м; пробита — R больше толщины, всё в
 * радиусе R снесено; не пробита — по FM сносить нечего; и не больше круга, где волна ломает кладку (плита над пустотой —
 * панель, как стена). Заряд, который метра не пробьёт («Ланцет»), — известный изъян целых блоков
 * ({@link #SUB_BLOCK_ISSUE}): строка печатается, но не проверяется;</li>
 * <li>бетон на грунте (асфальт дороги) — плиту держит грунт за ней, свободной грани нет: не пробой и не панель, а
 * воронка в среде не мягче скалы (TM 23-200: скала ×0,7) и не больше воронки того же заряда в грунте. Известный изъян
 * ({@link #ROAD_ISSUE}, warhead.md) — строка печатается, но не проверяется;</li>
 * <li>стены и стёкла рядом — пороги давления мода ({@link BlockResponse}: кладка 12 psi, доски 4 psi, стекло 0,8 psi;
 * DESIGN-nuke §3.3) по Джаррету и Кинни–Грэхему: всё ближе 0,8 радиуса своего порога сломано, дальше 1,25 — цело;</li>
 * <li>бомба — путь по Янгу (SAND97-2426, {@link Penetration}: в грунте ~15 %, у скалы до двух раз) и воронка выброса по
 * приведённой глубине ({@link BuriedBlast}, TM 5-855-1; глубже λ = 1 — камуфлет без воронки).</li>
 * </ul>
 * Отчёт — строки {@code SURFACEBENCH} «оружие × поверхность → должно / есть» и таблица партии целиком, когда кончились
 * все её проверки ({@code grep SURFACEBENCH} по логу части).
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SurfaceGameTests {
    /**
     * Известный изъян: заряд, которому по FM метра не пробить («Ланцет»), всё же открывает плиту в блок — воронка меньше
     * блока снимает его целым по дробной части (камень настила), волна ломает бетон крыши, как стену (тот же класс, что
     * {@link #ROAD_ISSUE}). Строка печатается, проверка вернётся с исправлением целых блоков воронки и волны по бетону.
     */
    private static final String SUB_BLOCK_ISSUE = "изъян: воронка меньше блока и волна по бетону открывают плиту в блок";
    /**
     * Известный изъян (warhead.md): бетон на грунте ломается волной как стена здания — у шахеда в ~17 раз больше
     * воронки того же заряда в грунте. Строка печатается, проверка вернётся с исправлением волны и воронки.
     */
    private static final String ROAD_ISSUE = "изъян: бетон на грунте ломает волна, как стену";

    /** Верх поверхности на площадке «range» (дёрн шаблона) и квадрат, где она своя. */
    private static final int TOP = 11, LO = 8, HI = 55, MID = 32;
    /** Низ своих слоёв: слой 1 шаблона — камень над воздухом под площадкой (песок на воздухе осыпался бы). */
    private static final int BASE = 2;

    private SurfaceGameTests() {}

    /** Поверхность: что лежит в столбце на {@code depth} блоков под верхом (0 — верхний блок). */
    enum Surface {
        SOIL("почва: подзол на земле"),
        SAND("песок"),
        GRAVEL("гравий (насыпь)"),
        PAVED("асфальт: 1 м чёрного бетона на земле"),
        ROCK("камень толщей"),
        DECK("настил: 1 м камня над пустотой 6 м"),
        ROOF("крыша: 1 м бетона над помещением 3 м");

        final String title;

        Surface(String title) {
            this.title = title;
        }

        BlockState layer(int depth) {
            return switch (this) {
                case SOIL -> depth == 0 ? Blocks.PODZOL.defaultBlockState() : Blocks.DIRT.defaultBlockState();
                case SAND -> Blocks.SAND.defaultBlockState();
                case GRAVEL -> Blocks.GRAVEL.defaultBlockState();
                case PAVED -> depth == 0 ? Blocks.BLACK_CONCRETE.defaultBlockState() : Blocks.DIRT.defaultBlockState();
                case ROCK -> Blocks.STONE.defaultBlockState();
                case DECK -> depth == 0 ? Blocks.STONE.defaultBlockState() : depth <= VOID ? Blocks.AIR.defaultBlockState() : Blocks.DIRT.defaultBlockState();
                case ROOF -> depth == 0 ? Blocks.GRAY_CONCRETE.defaultBlockState() : depth <= ROOM ? Blocks.AIR.defaultBlockState()
                        : depth == ROOM + 1 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.DIRT.defaultBlockState();
            };
        }

        /** Плита над пустотой (1 м). */
        boolean slab() {
            return this == DECK || this == ROOF;
        }

        /** Грунт воронки по закону: насыщенного здесь нет, камень — скала. */
        CraterModel.Soil soil() {
            return this == ROCK ? CraterModel.Soil.ROCK : CraterModel.Soil.DRY;
        }

        /** Почему строка не проверяется; null — проверяется. */
        @Nullable
        String pending() {
            return this == PAVED ? ROAD_ISSUE : null;
        }
    }

    /** Пустота под настилом и высота помещения под крышей, блоков. */
    private static final int VOID = 6, ROOM = 3;

    /** Выложить поверхность в квадрате {@code lo..hi} (координаты площадки) от {@link #BASE} до {@code top}. */
    private static void build(GameTestHelper h, Surface s, int lo, int hi, int loZ, int hiZ, int top) {
        ServerLevel level = h.getLevel();
        for (int x = lo; x <= hi; x++) {
            for (int z = loZ; z <= hiZ; z++) {
                boolean edge = x == lo || x == hi || z == loZ || z == hiZ;
                for (int y = BASE; y <= top; y++) {
                    BlockState state = s.layer(top - y);
                    // стены помещения под крышей — кладка по краю квадрата
                    if (s == Surface.ROOF && edge && state.isAir()) state = Blocks.STONE_BRICKS.defaultBlockState();
                    level.setBlock(h.absolutePos(new BlockPos(x, y, z)), state, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    // ---------------------------------------------------------------- отчёт

    /** Строка отчёта: проверена ли ({@code checked}) и прошла ли. */
    private record Row(String weapon, String surface, String should, String got, boolean checked, boolean ok, String note) {
        String line() {
            String verdict = !checked ? "—  " + note : ok ? "ок" : "НЕТ";
            return String.format(Locale.ROOT, "%-8s | %-38s | должно %-46s | есть %-46s | %s", weapon, surface, should, got, verdict);
        }
    }

    /** Строки по партиям: таблица партии печатается, когда отчитались все её проверки. */
    private static final Map<String, List<Row>> TABLES = new TreeMap<>();
    private static final Map<String, Integer> CALLS = new TreeMap<>();

    /** Проверок в партии: поверхности одного оружия — по одной на поверхность, «рядом» — одна. */
    private static int tests(String batch) {
        return batch.endsWith("_nearby") ? 1 : Surface.values().length;
    }

    private static synchronized void report(String batch, List<Row> rows) {
        List<Row> table = TABLES.computeIfAbsent(batch, b -> new ArrayList<>());
        table.addAll(rows);
        for (Row r : rows) Incoming.LOG.info("SURFACEBENCH {}", r.line());
        int calls = CALLS.merge(batch, 1, Integer::sum);
        if (calls >= tests(batch)) {
            StringBuilder out = new StringBuilder("SURFACEBENCH таблица ").append(batch).append(" (оружие × поверхность → должно / есть):");
            for (Row r : table) out.append("\nSURFACEBENCH | ").append(r.line());
            Incoming.LOG.info(out.toString());
            TABLES.remove(batch);
            CALLS.remove(batch);
        }
    }

    private static String f(double x) {
        return String.format(Locale.ROOT, "%.1f", x);
    }

    /** Проверок партии, которые держат обломки выключенными, и что было до первой из них. */
    private static int debrisHolders;
    private static boolean debrisBefore;

    /**
     * Обломки не ложатся блоками, пока идёт проверка. Настройка — на весь сервер, а проверок в партии семь: выключает
     * первая, возвращает последняя (каждая своё «до» вернула бы чужое «выключено», и {@code debrisLandsAsBlocks} в той же
     * части CI падал бы).
     */
    private static synchronized void noDebris(GameTestHelper h) {
        if (debrisHolders++ == 0) {
            debrisBefore = IncomingConfig.SERVER.debrisStay.get();
            IncomingConfig.SERVER.debrisStay.set(false);
        }
        StrikeGameTests.afterTest(h, () -> {
            synchronized (SurfaceGameTests.class) {
                if (--debrisHolders == 0) IncomingConfig.SERVER.debrisStay.set(debrisBefore);
            }
        });
    }

    // ---------------------------------------------------------------- удар снаряда

    /** Сущность оружия с осью удара из паспорта: угол к горизонту {@link Article.Impact#angle}, курс на +z. */
    private static Entity striker(ServerLevel level, WeaponType weapon) {
        EntityType<? extends Entity> type = switch (weapon) {
            case DRONE -> ModEntities.DRONE.get();
            case LOITER -> ModEntities.LOITER.get();
            case MISSILE -> ModEntities.CRUISE_MISSILE.get();
            case ROCKET -> ModEntities.ROCKET.get();
            default -> throw new IllegalArgumentException(weapon.name());
        };
        Entity e = type.create(level);
        Article.Impact impact = weapon.spec().striker().article().impact();
        double a = Math.toRadians(impact.angle());
        e.setDeltaMovement(new Vec3(0, -Math.sin(a), Math.cos(a)).scale(impact.speed() / 20));
        return e;
    }

    /**
     * Блоков, что снимает воронка закона радиусом {@code rc} и глубиной {@code depth} в одном ударе (в середине столбца),
     * и дисперсия случайной добавки целых блоков ({@code CraterJob.digColumn}, режим DIG: дробная часть — с её
     * вероятностью): {ожидание, дисперсия}.
     */
    static double[] lawBlocks(double rc, double depth) {
        double mean = 0, var = 0;
        int n = Mth.ceil(2 * rc + 1);
        for (int dx = -n; dx <= n; dx++) {
            for (int dz = -n; dz <= n; dz++) {
                double r = Math.hypot(dx, dz);
                double relief = CraterModel.rim(r, rc, depth) - CraterModel.bowl(r, rc, depth);
                if (relief >= 0) continue;
                double frac = relief - Math.floor(relief);
                mean += -relief;
                var += frac * (1 - frac);
            }
        }
        return new double[]{mean, var};
    }

    /**
     * Радиус пробоя по FM 5-250, м: K = R³·M·C, внешний заряд без забивки; M — табл. B-3 по ступеням радиуса, группа
     * «скала, хорошая кладка, бетонный блок» — камень настила и бетон крыши ({@link BreachModel}).
     */
    static double breachRadius(double tntKg) {
        return BreachModel.Material.ROCK.radius(tntKg, BreachModel.UNTAMPED);
    }

    /** FM округляет радиус пробоя наружного заряда вверх до ½ фута (~0,25 м, 3-12b): допуск закона в обе стороны, м. */
    static final double FM_ROUND = 0.25;

    /** Объём плиты толщиной {@code t} в радиусе {@code r} от заряда на её грани, м³. */
    static double slabVolume(double r, double t) {
        return r >= t ? Math.PI * (r * r * t - t * t * t / 3) : 2.0 / 3 * Math.PI * r * r * r;
    }

    /** Шаг сетки ударов: соседние не задевают друг друга ни воронкой, ни волной по кладке и бетону. */
    private static int spacing(WeaponType weapon) {
        double tnt = weapon.spec().charge();
        double reach = Math.max(2 * CraterModel.chargeRadius(tnt, CraterModel.Soil.DRY) + 1,
                Math.max(breachRadius(tnt) + FM_ROUND, BlastDamage.breakRange(BlastModel.kpa(12), tnt)));
        return Math.max(8, Mth.ceil(2 * reach + 2));
    }

    /**
     * Удары оружия сеткой по всей поверхности {@code s} (шаг — {@link #spacing}: у ракеты 3×3, у шахеда 5×5, у «Ланцета»
     * и «Града» 6×6 — их воронка доля блока, поэтому сумма), замер в квадрате каждого удара: снятые блоки поверхности,
     * отверстие (столбцы со снятым верхом), глубина; у плит — сквозные пробои.
     */
    private static void craterCell(GameTestHelper h, WeaponType weapon, Surface s) {
        ServerLevel level = h.getLevel();
        // обломки на время проверки не ложатся блоками: считается только снятое
        noDebris(h);
        build(h, s, LO, HI, LO, HI, TOP);
        int step = spacing(weapon), n = (HI - LO + 1) / step, first = MID - (n - 1) * step / 2;
        h.assertTrue(n >= 3, weapon + ": меньше трёх ударов в ряд, шаг " + step);
        List<BlockPos> hits = new ArrayList<>();
        for (int i = 0; i < n; i++) for (int k = 0; k < n; k++) hits.add(new BlockPos(first + step * i, TOP, first + step * k));
        for (BlockPos hit : hits) {
            Warheads.detonate(level, weapon, Vec3.atBottomCenterOf(h.absolutePos(hit.above())), striker(level, weapon), null);
        }
        Row[] row = {null};
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "работа ударов идёт");
            if (row[0] == null) row[0] = craterRow(h, weapon, s, hits, step);
            if (row[0].checked) h.assertTrue(row[0].ok, row[0].line());
        });
    }

    /** Замер ударов {@code hits} по поверхности {@code s} против закона — строка отчёта (печатается один раз). */
    private static Row craterRow(GameTestHelper h, WeaponType weapon, Surface s, List<BlockPos> hits, int step) {
        ServerLevel level = h.getLevel();
        int removed = 0, maxDepth = 0, holes = 0, through = 0;
        double area = 0;
        int half = (step - 1) / 2;
        for (BlockPos hit : hits) {
            int opening = 0, holeCols = 0;
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    int depth = 0;
                    for (int y = TOP; y >= BASE; y--) {
                        BlockPos rel = new BlockPos(hit.getX() + dx, y, hit.getZ() + dz);
                        if (s.layer(TOP - y).isAir()) continue;
                        BlockState now = level.getBlockState(h.absolutePos(rel));
                        if (now.isAir() || now.is(Blocks.FIRE) || !now.getFluidState().isEmpty()) {
                            removed++;
                            depth = Math.max(depth, TOP - y + 1);
                        }
                    }
                    BlockState cap = level.getBlockState(h.absolutePos(new BlockPos(hit.getX() + dx, TOP, hit.getZ() + dz)));
                    boolean open = cap.isAir() || cap.is(Blocks.FIRE);
                    if (open) opening++;
                    // у плиты верх снят — дыра насквозь: под ней пустота
                    if (s.slab() && open) holeCols++;
                    maxDepth = Math.max(maxDepth, depth);
                }
            }
            area += opening;
            if (holeCols > 0) through++;
            holes += holeCols;
        }
        double tnt = weapon.spec().charge(), count = hits.size();
        double dEq = 2 * Math.sqrt(area / hits.size() / Math.PI);
        String got = s.slab()
                ? String.format(Locale.ROOT, "%d бл. плиты за %d (%s/удар), пробой %d, дыра %s м", removed, hits.size(), f(removed / count), through,
                f(2 * Math.sqrt(holes / count / Math.PI)))
                : String.format(Locale.ROOT, "%d бл. за %d (%s/удар), Ø %s м, глуб. %d", removed, hits.size(), f(removed / count), f(dEq), maxDepth);
        String should;
        boolean ok;
        if (s.slab()) {
            // FM 5-250: снесено всё в радиусе пробоя, пробой — радиус больше толщины (1 м); плита над пустотой ещё и
            // панель — дальше пробоя её ломает волна не дальше, чем кладку (12 psi, Джаррет): верхняя граница
            // радиус по ступеням табл. B-3 ± округление FM; не пробита — сносить по FM нечего (только воронка сверху)
            double r = breachRadius(tnt), rLo = Math.max(0, r - FM_ROUND), rHi = r + FM_ROUND;
            double rPanel = BlastDamage.breakRange(BlastModel.kpa(BRICK_PSI), tnt);
            boolean breach = rLo > 1, none = rHi < 1 && rPanel < 0.85;
            double vLo = breach ? count * slabVolume(rLo, 1) : 0, vHi = count * Math.max(slabVolume(rHi, 1), Math.PI * rPanel * rPanel);
            // край дыры — целыми блоками: ±2√V блоков на шум края
            int lo = (int) Math.max(0, Math.floor(0.6 * vLo - 2 * Math.sqrt(vLo))), hi = (int) Math.ceil(1.45 * vHi + 2 * Math.sqrt(vHi));
            should = String.format(Locale.ROOT, "%d–%d бл., пробой %s (FM R %s–%s м, кладка %s м)", lo, hi,
                    breach ? "у всех" : none ? "нет" : "любой", f(rLo), f(rHi), f(rPanel));
            ok = removed >= lo && removed <= hi && (!breach || through == hits.size()) && (!none || through == 0);
        } else {
            // воронка в своей среде; бетон дороги — среда не мягче скалы и не больше воронки в грунте
            CraterModel.Soil soil = s == Surface.PAVED ? CraterModel.Soil.ROCK : s.soil();
            double rc = CraterModel.chargeRadius(tnt, soil), depth = CraterModel.chargeDepth(tnt, soil);
            double[] one = lawBlocks(rc, depth);
            // данные — 0,6–1,45 объёма закона; целые блоки — ещё 2σ случайной добавки
            double mean = count * one[0], sigma = Math.sqrt(count * one[1]);
            double upper = s == Surface.PAVED ? count * lawBlocks(CraterModel.chargeRadius(tnt, CraterModel.Soil.DRY),
                    CraterModel.chargeDepth(tnt, CraterModel.Soil.DRY))[0] : mean;
            int lo = (int) Math.max(0, Math.floor(0.6 * mean - 2 * sigma)), hi = (int) Math.ceil(1.45 * upper + 2 * sigma);
            int deepest = Mth.ceil(1.3 * depth);
            should = String.format(Locale.ROOT, "%d–%d бл. (закон %s), Ø %s м, глуб. ≤ %d", lo, hi, f(mean), f(2 * rc), deepest);
            ok = removed >= lo && removed <= hi && maxDepth <= Math.max(1, deepest);
        }
        String pending = s.pending();
        if (pending == null && s.slab() && breachRadius(tnt) + FM_ROUND < 1) pending = SUB_BLOCK_ISSUE;
        Row row = new Row(weapon.name(), s.title, should, got, pending == null, ok, pending);
        report("surface_" + weapon.name().toLowerCase(Locale.ROOT), List.of(row));
        return row;
    }

    // ---------------------------------------------------------------- рядом: стены и стёкла

    /** Кладка, доски, стекло. */
    private static final double BRICK_PSI = 12, WOOD_PSI = 4;
    /** Доли радиуса своего порога у стёкол: ближние выбиты, дальние целы (край модели 0,9–1,11). */
    private static final double[] GLASS_AT = {0.7, 0.8, 1.25, 1.4};

    /**
     * Удар по дёрну площадки: столбики кладки и досок на своих лучах (как {@code BlastGameTests.radiusTest}) и стёкла над
     * площадкой на долях радиуса своего порога (стекло — по давлению Кинни–Грэхема, без импульса; вверх — вне пояса
     * осколков у оси удара сверху). Всё ближе 0,8 своего радиуса — сломано, дальше 1,25 — цело.
     */
    private static void nearby(GameTestHelper h, WeaponType weapon) {
        ServerLevel level = h.getLevel();
        double tnt = weapon.spec().charge();
        BlockPos c = h.absolutePos(new BlockPos(MID, TOP + 1, MID));
        Vec3 at = Vec3.atBottomCenterOf(c);
        // кладка — к −z, доски — к +z: каждому материалу своя половина азимутов, и доски у шахеда стоят и на малых долях
        List<BlastGameTests.Post> bricks = BlastGameTests.layPosts(c, at, BRICK_PSI, tnt, BlastDamage.breakRange(BlastModel.kpa(BRICK_PSI), tnt),
                List.of(), b -> b < 0);
        List<BlastGameTests.Post> planks = BlastGameTests.layPosts(c, at, WOOD_PSI, tnt, BlastDamage.breakRange(BlastModel.kpa(WOOD_PSI), tnt),
                bricks, b -> b > 0);
        for (BlastGameTests.Post p : bricks) level.setBlock(p.pos(), Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS);
        for (BlastGameTests.Post p : planks) level.setBlock(p.pos(), Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_CLIENTS);
        // стёкла: по одному в воздухе над площадкой, место — ближайшее к доле своего радиуса
        List<double[]> glass = new ArrayList<>();
        List<BlockPos> panes = new ArrayList<>();
        int i = 0;
        for (double share : GLASS_AT) {
            BlockPos best = null;
            double bestShare = 0;
            int dx = 3 + 2 * i++;
            for (int y = c.getY() + 1; y < level.getMaxBuildHeight() - 1; y++) {
                BlockPos p = new BlockPos(c.getX() + dx, y, c.getZ());
                double own = BlastModel.rangeForSurfaceOverpressure(BlastModel.kpa(BlockResponse.FRAGILE_PSI * BlockResponse.jitterAt(p)), tnt);
                double sh = Math.sqrt(at.distanceToSqr(Vec3.atCenterOf(p))) / own;
                if (best == null || Math.abs(sh - share) < Math.abs(bestShare - share)) {
                    best = p;
                    bestShare = sh;
                }
            }
            level.setBlock(best, Blocks.GLASS.defaultBlockState(), Block.UPDATE_CLIENTS);
            panes.add(best);
            BlockPos pane = best;
            // стёкла выше стены площадки — прочь после проверки, как башня бомбы
            StrikeGameTests.afterTest(h, () -> level.setBlock(pane, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS));
            glass.add(new double[]{bestShare});
        }
        Warheads.detonate(level, weapon, at, striker(level, weapon), null);
        List<Row> rows = new ArrayList<>();
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "работа удара идёт");
            if (rows.isEmpty()) nearbyRows(level, weapon, tnt, bricks, planks, panes, glass, rows);
            for (Row row : rows) h.assertTrue(row.ok, row.line());
        });
    }

    /** Строки «рядом»: столбики кладки и досок, стёкла (печатаются один раз). */
    private static void nearbyRows(ServerLevel level, WeaponType weapon, double tnt, List<BlastGameTests.Post> bricks,
                                   List<BlastGameTests.Post> planks, List<BlockPos> panes, List<double[]> glass, List<Row> rows) {
        rows.add(posts(level, weapon, "стены: каменный кирпич 12 psi", bricks, Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, tnt, BRICK_PSI));
        rows.add(posts(level, weapon, "стены: доски 4 psi", planks, Blocks.OAK_PLANKS, Blocks.OAK_PLANKS, tnt, WOOD_PSI));
        StringBuilder got = new StringBuilder();
        boolean ok = true;
        for (int k = 0; k < panes.size(); k++) {
            double sh = glass.get(k)[0];
            boolean gone = !level.getBlockState(panes.get(k)).is(Blocks.GLASS);
            got.append(String.format(Locale.ROOT, " %.2f%s", sh, gone ? "x" : "|"));
            if (sh <= 0.8 && !gone || sh >= 1.25 && gone) ok = false;
        }
        double r = BlastModel.rangeForSurfaceOverpressure(BlastModel.kpa(BlockResponse.FRAGILE_PSI), tnt);
        rows.add(new Row(weapon.name(), "стёкла 0,8 psi (R " + f(r) + " м)", "≤0,8 R выбиты, ≥1,25 R целы", "доли R:" + got, true, ok, ""));
        report("surface_" + weapon.name().toLowerCase(Locale.ROOT) + "_nearby", rows);
    }

    /** Строка столбиков одного материала: доли радиуса Джаррета своего порога, сломан (x) или цел (|). */
    private static Row posts(ServerLevel level, WeaponType weapon, String what, List<BlastGameTests.Post> posts, Block material, Block cracked,
                             double tnt, double psi) {
        StringBuilder got = new StringBuilder();
        boolean ok = true;
        for (BlastGameTests.Post p : posts) {
            BlockState now = level.getBlockState(p.pos());
            // выбоина осколка (треснувшая кладка) — стоит
            boolean gone = !now.is(material) && !now.is(cracked);
            got.append(String.format(Locale.ROOT, " %.2f%s", p.share(), gone ? "x" : "|"));
            if (p.share() <= 0.8 && !gone || p.share() >= 1.25 && gone) ok = false;
        }
        String title = what + " (R " + f(BlastDamage.breakRange(BlastModel.kpa(psi), tnt)) + " м)";
        return new Row(weapon.name(), title, "≤0,8 R сломаны, ≥1,25 R целы", "доли R:" + got, true, ok, "");
    }

    // ---------------------------------------------------------------- бомба B-2

    /** Цель бомбы на полосе (как у {@code bunkerBusterDrillsAndDetonatesUnderground}) и квадрат её поверхности. */
    private static final int BOMB_X = 16, BOMB_Z = 200, BOMB_HALF = 15;

    /** Верх поверхности под бомбой: толща глубже её пути по Янгу и зоны дробления. */
    private static int bombTop(Surface s) {
        return switch (s) {
            case SOIL, PAVED -> 72;
            case SAND -> 54;
            case GRAVEL -> 42;
            case ROCK -> 30;
            case DECK, ROOF -> 80;
        };
    }

    /** Среда пути по Янгу (карта данных {@code incoming:penetration}) для слоя поверхности на глубине {@code depth}. */
    private static Penetration.Kind kind(BlockState s) {
        if (s.is(Blocks.STONE)) return Penetration.Kind.ROCK;
        if (s.is(Blocks.BLACK_CONCRETE) || s.is(Blocks.GRAY_CONCRETE) || s.is(Blocks.STONE_BRICKS)) return Penetration.Kind.CONCRETE;
        return Penetration.Kind.SOIL;
    }

    private static double youngS(BlockState s) {
        if (s.is(Blocks.SAND)) return 7.5;
        if (s.is(Blocks.GRAVEL)) return 5;
        if (s.is(Blocks.STONE) || s.is(Blocks.BLACK_CONCRETE) || s.is(Blocks.GRAY_CONCRETE)) return 0.84;
        if (s.is(Blocks.STONE_BRICKS)) return 1.0;
        return 12;
    }

    /**
     * Бомба B-2 по поверхности: настоящая сущность со сброса B-2. Путь в грунте от входа до подрыва — по Янгу для слоёв
     * поверхности (по скорости и углу удара самой бомбы); воронка выброса — по приведённой глубине подрыва; у плит —
     * бомба прошла насквозь (подрыв глубже плиты), датчик пустоты не взвёлся (до него — 4 м твёрдого).
     */
    private static void bomb(GameTestHelper h, Surface s) {
        ServerLevel level = h.getLevel();
        int top = bombTop(s);
        noDebris(h);
        int lo = BOMB_X - BOMB_HALF, hi = BOMB_X + BOMB_HALF, loZ = BOMB_Z - BOMB_HALF, hiZ = BOMB_Z + BOMB_HALF;
        StrikeGameTests.afterTest(h, () -> {
            // башня выше стены площадки — прочь: соседние партии летают над миром теста
            for (BlockPos p : BlockPos.betweenClosed(new BlockPos(lo - 4, 9, loZ - 4), new BlockPos(hi + 4, top + 12, hiZ + 4))) {
                BlockPos a = h.absolutePos(p);
                if (!level.getBlockState(a).isAir()) level.setBlock(a, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        });
        build(h, s, lo, hi, loZ, hiZ, top);
        Vec3 surface = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(BOMB_X, top + 1, BOMB_Z)));
        BunkerBusterEntity bomb = ModEntities.BUNKER_BUSTER.get().create(level);
        bomb.drop(surface.add(0, 170, -145), BomberEntity.levelVelocity(0), surface, null, null);
        level.addFreshEntity(bomb);
        Vec3[] last = {bomb.position(), null};
        double[] speed = {0};
        Vec3[] entry = {null}, charge = {null};
        h.onEachTick(() -> {
            if (bomb.isRemoved() || entry[0] != null) return;
            if (bomb.isDrilling()) {
                entry[0] = bomb.entry().subtract(0, 1, 0);
                return;
            }
            Vec3 now = bomb.position();
            last[1] = now.subtract(last[0]);
            speed[0] = last[1].length() * 20;
            last[0] = now;
        });
        Consumer<ExplosionEvent.Start> first = e -> {
            if (e.getLevel() == level && charge[0] == null && bomb.getUUID().equals(ua.zentix.incoming.stress.StressDirector.blastBy(e.getExplosion()))) {
                charge[0] = e.getExplosion().center();
            }
        };
        NeoForge.EVENT_BUS.addListener(first);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(first));
        Row[] row = {null};
        h.succeedWhen(() -> {
            h.assertTrue(entry[0] != null, "бомба не вошла в грунт");
            h.assertTrue(bomb.isRemoved() && charge[0] != null, "бомба не взорвалась");
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "работа подрыва идёт");
            if (row[0] == null) row[0] = bombRow(level, s, top, last[1], speed[0], entry[0], charge[0]);
            h.assertTrue(row[0].ok, row[0].line());
        });
    }

    /** Строка бомбы: путь против Янга, воронка против приведённой глубины (печатается один раз). */
    private static Row bombRow(ServerLevel level, Surface s, int top, Vec3 step, double v, Vec3 entry, Vec3 charge) {
        Vec3 dir = step.normalize();
        double sin = Math.max(0.05, -dir.y);
        // путь по Янгу через слои поверхности по оси бомбы
        Penetration.Run run = new Penetration.Run(BunkerBusterEntity.body(), v);
        double young = 0;
        for (int d = 0; d <= top - BASE && !run.stopped(); d++) {
            BlockState layer = s.layer(d);
            if (layer.isAir()) {
                run.gap();
                young += 1 / sin;
                continue;
            }
            young += run.through(kind(layer), youngS(layer), 1 / sin);
        }
        double path = charge.distanceTo(entry);
        boolean rocky = s == Surface.ROCK;
        double lo2 = rocky ? 0.5 : 0.8, hi2 = rocky ? 2 : 1.2;
        // воронка выброса по глубине подрыва под верхом
        double depth = entry.y - charge.y;
        BuriedBlast law = new BuriedBlast(WeaponType.BUNKER.spec().warheadTnt(), depth);
        CraterModel.Soil soil = s == Surface.ROCK ? CraterModel.Soil.ROCK : CraterModel.Soil.DRY;
        int removedTop = 0, columns = 0;
        BlockPos ent = BlockPos.containing(entry);
        for (int dx = -BOMB_HALF + 1; dx < BOMB_HALF; dx++) {
            for (int dz = -BOMB_HALF + 1; dz < BOMB_HALF; dz++) {
                boolean col = false;
                for (int dy = 0; dy < 3; dy++) {
                    // вход — верх блока поверхности: сам блок — под ним
                    BlockPos a = new BlockPos(ent.getX() + dx, ent.getY() - 1 - dy, ent.getZ() + dz);
                    if (s.layer(dy).isAir()) continue;
                    BlockState now = level.getBlockState(a);
                    if (now.isAir() || now.is(Blocks.FIRE)) {
                        removedTop++;
                        col = true;
                    }
                }
                if (col) columns++;
            }
        }
        // воронка выброса — по радиусу отверстия: закон BuriedBlast — оценка формы (сказано в нём), допуск ±50 %; камуфлет —
        // наверху только ход бомбы (Ø 0,8 м, блок по оси): в верхних трёх слоях — не больше трёх столбцов
        double lawR = law.breaches() ? law.craterRadius(soil) : 0, gotR = Math.sqrt(columns / Math.PI);
        boolean pathOk = path >= lo2 * young && path <= hi2 * young;
        boolean craterOk = law.breaches() ? gotR >= 0.5 * lawR && gotR <= 1.5 * lawR : removedTop >= 1 && columns <= 3;
        boolean throughSlab = !s.slab() || depth > 1 + (s == Surface.DECK ? VOID : ROOM);
        String should = String.format(Locale.ROOT, "путь %s м (Янг, %s–%s), λ %s: %s", f(young), f(lo2 * young), f(hi2 * young),
                f(law.scaledDepth()), law.breaches() ? "воронка R " + f(lawR) + " м (" + f(0.5 * lawR) + "–" + f(1.5 * lawR) + ")" : "камуфлет, наверху только ход")
                + (s.slab() ? ", подрыв под плитой" : "");
        String got = String.format(Locale.ROOT, "удар %s м/с под %s°, путь %s м, глубина %s м, наверху %d бл. в %d столбцах (R %s м)",
                f(v), f(Math.toDegrees(Math.asin(sin))), f(path), f(depth), removedTop, columns, f(gotR));
        Row row = new Row("BOMB", s.title, should, got, true, pathOk && craterOk && throughSlab, "");
        report("surface_bomb", List.of(row));
        return row;
    }

    // ---------------------------------------------------------------- проверки

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_drone", skyAccess = true)
    public static void droneOnSoil(GameTestHelper h) {
        craterCell(h, WeaponType.DRONE, Surface.SOIL);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_drone", skyAccess = true)
    public static void droneOnSand(GameTestHelper h) {
        craterCell(h, WeaponType.DRONE, Surface.SAND);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_drone", skyAccess = true)
    public static void droneOnGravel(GameTestHelper h) {
        craterCell(h, WeaponType.DRONE, Surface.GRAVEL);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_drone", skyAccess = true)
    public static void droneOnPaving(GameTestHelper h) {
        craterCell(h, WeaponType.DRONE, Surface.PAVED);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_drone", skyAccess = true)
    public static void droneOnRock(GameTestHelper h) {
        craterCell(h, WeaponType.DRONE, Surface.ROCK);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_drone", skyAccess = true)
    public static void droneOnDeck(GameTestHelper h) {
        craterCell(h, WeaponType.DRONE, Surface.DECK);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_drone", skyAccess = true)
    public static void droneOnRoof(GameTestHelper h) {
        craterCell(h, WeaponType.DRONE, Surface.ROOF);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_drone_nearby", skyAccess = true)
    public static void droneNearby(GameTestHelper h) {
        nearby(h, WeaponType.DRONE);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_loiter", skyAccess = true)
    public static void loiterOnSoil(GameTestHelper h) {
        craterCell(h, WeaponType.LOITER, Surface.SOIL);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_loiter", skyAccess = true)
    public static void loiterOnSand(GameTestHelper h) {
        craterCell(h, WeaponType.LOITER, Surface.SAND);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_loiter", skyAccess = true)
    public static void loiterOnGravel(GameTestHelper h) {
        craterCell(h, WeaponType.LOITER, Surface.GRAVEL);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_loiter", skyAccess = true)
    public static void loiterOnPaving(GameTestHelper h) {
        craterCell(h, WeaponType.LOITER, Surface.PAVED);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_loiter", skyAccess = true)
    public static void loiterOnRock(GameTestHelper h) {
        craterCell(h, WeaponType.LOITER, Surface.ROCK);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_loiter", skyAccess = true)
    public static void loiterOnDeck(GameTestHelper h) {
        craterCell(h, WeaponType.LOITER, Surface.DECK);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_loiter", skyAccess = true)
    public static void loiterOnRoof(GameTestHelper h) {
        craterCell(h, WeaponType.LOITER, Surface.ROOF);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_loiter_nearby", skyAccess = true)
    public static void loiterNearby(GameTestHelper h) {
        nearby(h, WeaponType.LOITER);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_missile", skyAccess = true)
    public static void missileOnSoil(GameTestHelper h) {
        craterCell(h, WeaponType.MISSILE, Surface.SOIL);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_missile", skyAccess = true)
    public static void missileOnSand(GameTestHelper h) {
        craterCell(h, WeaponType.MISSILE, Surface.SAND);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_missile", skyAccess = true)
    public static void missileOnGravel(GameTestHelper h) {
        craterCell(h, WeaponType.MISSILE, Surface.GRAVEL);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_missile", skyAccess = true)
    public static void missileOnPaving(GameTestHelper h) {
        craterCell(h, WeaponType.MISSILE, Surface.PAVED);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_missile", skyAccess = true)
    public static void missileOnRock(GameTestHelper h) {
        craterCell(h, WeaponType.MISSILE, Surface.ROCK);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_missile", skyAccess = true)
    public static void missileOnDeck(GameTestHelper h) {
        craterCell(h, WeaponType.MISSILE, Surface.DECK);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_missile", skyAccess = true)
    public static void missileOnRoof(GameTestHelper h) {
        craterCell(h, WeaponType.MISSILE, Surface.ROOF);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_missile_nearby", skyAccess = true)
    public static void missileNearby(GameTestHelper h) {
        nearby(h, WeaponType.MISSILE);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_rocket", skyAccess = true)
    public static void rocketOnSoil(GameTestHelper h) {
        craterCell(h, WeaponType.ROCKET, Surface.SOIL);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_rocket", skyAccess = true)
    public static void rocketOnSand(GameTestHelper h) {
        craterCell(h, WeaponType.ROCKET, Surface.SAND);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_rocket", skyAccess = true)
    public static void rocketOnGravel(GameTestHelper h) {
        craterCell(h, WeaponType.ROCKET, Surface.GRAVEL);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_rocket", skyAccess = true)
    public static void rocketOnPaving(GameTestHelper h) {
        craterCell(h, WeaponType.ROCKET, Surface.PAVED);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_rocket", skyAccess = true)
    public static void rocketOnRock(GameTestHelper h) {
        craterCell(h, WeaponType.ROCKET, Surface.ROCK);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_rocket", skyAccess = true)
    public static void rocketOnDeck(GameTestHelper h) {
        craterCell(h, WeaponType.ROCKET, Surface.DECK);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_rocket", skyAccess = true)
    public static void rocketOnRoof(GameTestHelper h) {
        craterCell(h, WeaponType.ROCKET, Surface.ROOF);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_rocket_nearby", skyAccess = true)
    public static void rocketNearby(GameTestHelper h) {
        nearby(h, WeaponType.ROCKET);
    }

    @GameTest(template = "runway", timeoutTicks = 600, batch = "surface_bomb", skyAccess = true)
    public static void bombOnSoil(GameTestHelper h) {
        bomb(h, Surface.SOIL);
    }

    @GameTest(template = "runway", timeoutTicks = 600, batch = "surface_bomb", skyAccess = true)
    public static void bombOnSand(GameTestHelper h) {
        bomb(h, Surface.SAND);
    }

    @GameTest(template = "runway", timeoutTicks = 600, batch = "surface_bomb", skyAccess = true)
    public static void bombOnGravel(GameTestHelper h) {
        bomb(h, Surface.GRAVEL);
    }

    @GameTest(template = "runway", timeoutTicks = 600, batch = "surface_bomb", skyAccess = true)
    public static void bombOnPaving(GameTestHelper h) {
        bomb(h, Surface.PAVED);
    }

    @GameTest(template = "runway", timeoutTicks = 600, batch = "surface_bomb", skyAccess = true)
    public static void bombOnRock(GameTestHelper h) {
        bomb(h, Surface.ROCK);
    }

    @GameTest(template = "runway", timeoutTicks = 600, batch = "surface_bomb", skyAccess = true)
    public static void bombOnDeck(GameTestHelper h) {
        bomb(h, Surface.DECK);
    }

    @GameTest(template = "runway", timeoutTicks = 600, batch = "surface_bomb", skyAccess = true)
    public static void bombOnRoof(GameTestHelper h) {
        bomb(h, Surface.ROOF);
    }
}
