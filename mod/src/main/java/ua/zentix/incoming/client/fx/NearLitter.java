package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.far.FarBlasts;
import ua.zentix.incoming.client.fx.particle.Fx;
import ua.zentix.incoming.client.fx.particle.FxBudget;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.LocalLitter;
import ua.zentix.incoming.nuclear.model.ShockAtBody;
import ua.zentix.incoming.warhead.GroundMaterial;

import java.util.Locale;

/**
 * Сор у самой камеры: в миг, когда фронт обычного взрыва проходит игрока, всё лёгкое вокруг него (до {@link #REACH} м)
 * разом вздрагивает и одним выдохом летит от взрыва — травинки и дёрн, листья с крон, крошка песка, гравия и земли,
 * тонкая пыль; потом висит, оседает и уходит по обычному ветру мира. Сорвёт ли и как бросит — модель ({@link LocalLitter}:
 * срыв по порогу своей поверхности, бросок за положительную фазу, сдвиг воздуха u·t₊ — дециметры, оседание); на что
 * смотреть — настоящие блоки рядом (их текстура и цвет: {@link Fragments}); сколько кусков на экране — выборка
 * (<b>подача</b>: {@link #PER_M2}, не больше {@link #MAX} на взрыв). Кольцо срыва дёрна по всей округе — {@link FrontLitter},
 * пыль с голой земли по всей округе — {@code GroundDust}; здесь — только ближний план у камеры, там, где их выборки редки.
 * <p>
 * Места — открытые сверху поверхности в шаре {@link #REACH} вокруг глаза ({@link #exposed}) со светом неба (фронт идёт по
 * наружному воздуху: пол пещеры под ногами он не трогает): трава у ног под кроной и сама крона, не крыша в двадцати
 * метрах над головой; ближние — первыми.
 * <p>
 * Поверхность места: рыхлый грунт ({@link DustModel.Surface#FIELD}: песок, гравий, земля, снег), дёрн, трава и крона
 * ({@link DustModel.Surface#COVERED}: держит до 3 м/с), твёрдое ({@link DustModel.Surface#HARD}: только осевшая пыль —
 * без кусков, тонкой пылью); вода — ничего. Крона и то, что выше места взрыва, видят волну в воздухе — весь напор, а не
 * его долю вдоль земли.
 */
public final class NearLitter {
    /** Докуда от камеры, м: ближний план (дальше — выборки {@link FrontLitter} и {@code GroundDust}). */
    static final double REACH = 16;
    /** Шаг выборки мест, м. */
    static final double STEP = 1;
    /** Кусков на квадратный метр при полной доле срыва (подача: настоящих травинок и песчинок — тысячи). */
    static final double PER_M2 = 1.2;
    /** Кусков на взрыв не больше (подача; общий предел — {@link Fragments#share}). */
    static final int MAX = 400;
    /** Клок травы и листвы, м (подача: мельче его с нескольких метров не видно; траекторию задаёт масса на площадь). */
    static final float CLUMP_MIN = 0.25f, CLUMP_MAX = 0.5f;
    /** Крошка грунта, м: зерно гравия, комок земли (подача — песчинку не видно). */
    static final float GRAIN_MIN = 0.06f, GRAIN_MAX = 0.12f;
    /**
     * Медленнее этого бросок кусок за тик не сдвигает и на сантиметр — на экране он просто появился бы на месте (у «Града»
     * в 200 м доля срыва рыхлого есть, а броска нет): кусков нет, пыль — по своей массе. Подача.
     */
    static final double MIN_KICK_MPS = 0.2;
    /** Подъём броска к земле, тангенс (19–35°): подача, модуль скорости — модели ({@link FrontLitter#launch}). */
    static final double LIFT_MIN = 0.35, LIFT_MAX = 0.7;
    /** Плотность крошки, кг/м³ (комок сухого грунта). */
    static final double GRAIN_DENSITY = 1600;
    /**
     * Настоящий комочек рыхлой почвы, м (оценка: крошка 1–5 мм — то, что лежит сверху и что бросает фронт): бросок и
     * оседание закона — у него; рисуется он крупнее ({@link #GRAIN_MIN}…{@link #GRAIN_MAX}, подача): ком в 10 см падает
     * со скоростью ~50 м/с, и фронт за миллисекунды его почти не трогает.
     */
    static final double CRUMB_M = 0.002;
    /** Тонкая пыль: облачко на место, м, и непрозрачность не больше (подача: толстая — бурая стена у глаз). */
    static final float PUFF_MIN = 0.6f, PUFF_MAX = 1.2f, PUFF_ALPHA = 0.5f;
    /**
     * Оседание тонкой пыли, м/с: по ослаблению, Σ k·доля·v / Σ k·доля по корзинам {@link DustModel} (3–400 мкм, k ∝ 1/D) —
     * 0,027: видно её мелкие зёрна, а они почти не оседают; облачко не падает, а расходится и уходит по ветру.
     */
    static final double DUST_SETTLE_MPS = 0.027;
    /** Облачко растёт за жизнь вчетверо по площади и тает с середины жизни, за 4–6 с — расходится (подача, оценка). */
    static final float PUFF_GROWTH = 2f;
    static final int PUFF_LIFE = 80, PUFF_LIFE_SPREAD = 40;
    /** Последний выброс у камеры (строка сценария клиента). */
    private static String last = "-";

    /** Обычный ветер мира у куска ({@link ClientAir}): сор, брошенный фронтом, дальше оседает и уходит по нему. */
    private static final Fragments.Wind AIR = (x, y, z, into) -> {
        into[0] = ClientAir.windX(y);
        into[2] = ClientAir.windZ(y);
        into[1] = Math.hypot(into[0], into[2]);
    };

    private NearLitter() {}

    public static String describe() {
        return last;
    }

    /** Что лежит на месте: поверхность закона и вещество куска; null — ничего (вода, воздух). */
    record Cover(DustModel.Surface surface, Fragments.Kind kind, BlockState torn, boolean pieces) {}

    /**
     * Поверхность места по верхнему блоку {@code cover} и растению над ним {@code plant}: трава и цветы — клок травы;
     * крона — клок листвы; дёрн — клок травы; рыхлое — крошка своего блока; твёрдое — только пыль.
     */
    static Cover cover(BlockState cover, BlockState plant) {
        if (!cover.getFluidState().isEmpty()) return null;
        boolean grows = !plant.isAir() && plant.canBeReplaced() && plant.getFluidState().isEmpty();
        if (grows) return new Cover(DustModel.Surface.COVERED, Fragments.Kind.LEAF, plant, true);
        if (cover.is(BlockTags.LEAVES)) return new Cover(DustModel.Surface.COVERED, Fragments.Kind.LEAF, cover, true);
        if (cover.is(Blocks.GRASS_BLOCK)) return new Cover(DustModel.Surface.COVERED, Fragments.Kind.LEAF, Blocks.SHORT_GRASS.defaultBlockState(), true);
        if (FarBlasts.loose(cover)) return new Cover(DustModel.Surface.FIELD, Fragments.Kind.CHUNK, cover, true);
        if (FarBlasts.sod(cover)) return new Cover(DustModel.Surface.COVERED, Fragments.Kind.LEAF, Blocks.SHORT_GRASS.defaultBlockState(), true);
        if (cover.isAir()) return null;
        return new Cover(DustModel.Surface.HARD, Fragments.Kind.CHUNK, cover, false);
    }

    /**
     * Фронт взрыва {@code s} из {@code blast} прошёл тело в {@code body} ({@code bodyArrival} с после взрыва): сор вокруг
     * камеры, если она у тела (камера снаряда далеко — у неё свой вид, {@code FarBlasts}). Взрыв под землёй волной в воздух
     * не выходит.
     */
    static void front(BlastSource s, Vec3 blast, Vec3 body, double bodyArrival, long seed) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        if (level == null || s.underground() || eye.distanceTo(body) > CAMERA_AT_BODY) return;
        spawn(level, s.chargeKg(), s.surface(), blast, eye, bodyArrival, RandomSource.create(seed ^ 0x5EEDL));
    }

    /** Камера у тела, если ближе стольких блоков (вид от третьего лица — до 4 блоков за головой). */
    static final double CAMERA_AT_BODY = 6;

    /**
     * Места выборки: клетки шага {@link #STEP} в круге {@link #REACH} вокруг камеры, от ближних к дальним — бюджет кусков
     * достаётся сперва тому, что у ног.
     */
    private static final int[][] COLUMNS = columns();

    private static int[][] columns() {
        int r = (int) Math.ceil(REACH / STEP);
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (int i = -r; i <= r; i++) for (int j = -r; j <= r; j++) if ((i * i + j * j) * STEP * STEP <= REACH * REACH) out.add(new int[]{i, j});
        out.sort(java.util.Comparator.comparingInt(c -> c[0] * c[0] + c[1] * c[1]));
        return out.toArray(new int[0][]);
    }

    /** Над блоком открыто: воздух или растение без формы (трава, цветы) — фронт проходит, сорванное взлетает. */
    static boolean open(BlockState s) {
        return s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty();
    }

    /**
     * Открытые сверху поверхности колонки {@code at}(y), сверху вниз от {@code from} до {@code to}: блок, на котором может
     * лежать сорванное (не воздух и не растение), с воздухом или растением над ним. Под кроной — и сама крона, и трава под
     * ней; под крышей — крыша и пол открытого места под ней.
     */
    static int[] exposed(java.util.function.IntFunction<BlockState> at, int from, int to) {
        int[] out = new int[8];
        int n = 0;
        BlockState above = at.apply(from + 1);
        for (int y = from; y >= to; y--) {
            BlockState s = at.apply(y);
            if (!open(s) && open(above)) {
                if (n == out.length) out = java.util.Arrays.copyOf(out, 2 * n);
                out[n++] = y;
            }
            above = s;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /**
     * Скорость падения настоящего тела куска в тихом воздухе, м/с: клок травы и листвы — от массы на площадь (размер не
     * важен), крошка — комочек почвы {@link #CRUMB_M}. По ней закон бросает кусок, и с ней же он летит и оседает
     * ({@link Fragments#torn} со скоростью падения): рисуется он крупнее, а летит как настоящий.
     */
    static double terminal(Fragments.Kind kind) {
        return kind == Fragments.Kind.LEAF ? Fragments.terminal(kind, 0, CLUMP_MIN) : Fragments.terminal(kind, GRAIN_DENSITY, CRUMB_M);
    }

    /**
     * Фронт взрыва {@code charge} кг ТНТ ({@code surface} — на земле) из {@code blast} прошёл тело в {@code eye}
     * ({@code bodyArrival} с после взрыва): сор вокруг — на открытых поверхностях в шаре {@link #REACH} вокруг глаза (чанки,
     * что у клиента уже есть). Каждое место ждёт прихода фронта на себя.
     */
    static void spawn(ClientLevel level, double charge, boolean surface, Vec3 blast, Vec3 eye, double bodyArrival, RandomSource rnd) {
        if (!(charge > 0) || !visible(charge, surface, Math.max(0.5, eye.distanceTo(blast) - REACH))) return;
        int budget = Fragments.share(MAX), born = 0, puffs = 0;
        java.util.Map<Integer, ShockAtBody> shocks = new java.util.HashMap<>();
        java.util.Map<Long, LocalLitter.Lift> lifts = new java.util.HashMap<>();
        double groundY = blast.y;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int low = Math.max(level.getMinBuildHeight(), Mth.floor(eye.y - REACH)), high = Mth.floor(eye.y + REACH);
        for (int[] col : COLUMNS) {
            if (born >= budget) break;
            double dx = col[0] * STEP + (rnd.nextDouble() - 0.5) * STEP, dz = col[1] * STEP + (rnd.nextDouble() - 0.5) * STEP;
            double x = eye.x + dx, z = eye.z + dz;
            int bx = Mth.floor(x), bz = Mth.floor(z);
            LevelChunk chunk = level.getChunkSource().getChunkNow(bx >> 4, bz >> 4);
            if (chunk == null) continue;
            // выше верхнего блока колонки (getHeight чанка — уже он) открытых мест нет
            int from = Math.min(high, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15));
            for (int top : exposed(y -> chunk.getBlockState(m.set(bx, y, bz)), from, low)) {
                double y = top + 1.05;
                if (dx * dx + dz * dz + (y - eye.y) * (y - eye.y) > REACH * REACH) continue;
                // фронт идёт по наружному воздуху: место без света неба (пещера под ногами, закрытая комната) он не трогает —
                // тот же признак, что у пелены ядерки (свет неба Minecraft — связь места с небом, OpenAir)
                if (level.getBrightness(LightLayer.SKY, m.set(bx, top + 1, bz)) <= 0) continue;
                BlockState coverState = chunk.getBlockState(m.set(bx, top, bz)), plant = chunk.getBlockState(m.set(bx, top + 1, bz));
                Cover c = cover(coverState, plant);
                if (c == null) continue;
                boolean crown = c.torn == coverState && coverState.is(BlockTags.LEAVES);
                double ground = Math.hypot(x - blast.x, z - blast.z), slant = Math.hypot(ground, y - blast.y);
                // крона и то, что выше места взрыва, видят волну в воздухе
                double along = crown || y > groundY + 2 ? 1 : FrontLitter.along(ground, slant);
                // закон — на шаг расстояния и доли напора (у рыхлого грунта интеграл выноса — ~6 мкс на место, а мест — до
                // тысячи на взрыв): ShockAtBody зависит только от расстояния, клок и крошка — от вещества, не от размера
                int ri = (int) Math.round(slant / SLANT_STEP), ai = (int) Math.round(along / ALONG_STEP);
                long key = ((long) ri << 16) | ((long) ai << 8) | ((long) surfaceIndex(c.surface) << 1) | (c.kind == Fragments.Kind.LEAF ? 1 : 0);
                ShockAtBody shock = shocks.computeIfAbsent(ri, k -> ShockAtBody.chemical(charge, surface, k * SLANT_STEP));
                LocalLitter.Lift lift = lifts.computeIfAbsent(key, k -> LocalLitter.lift(shock, c.surface, ai * ALONG_STEP, terminal(c.kind)));
                int delay = Math.max(0, (int) Math.ceil(20 * (shock.arrivalSeconds() - bodyArrival)));
                if (delay > Fragments.DELAY_MAX) continue;
                double ox = ground > 1e-6 ? (x - blast.x) / ground : 0, oz = ground > 1e-6 ? (z - blast.z) / ground : 0;
                // куски: доля срыва × выборка на площадь места (дробная часть — жребием)
                double want = lift.releaseShare() * PER_M2 * STEP * STEP;
                int n = (int) want + (rnd.nextDouble() < want - (int) want ? 1 : 0);
                if (c.pieces && pieces(lift)) {
                    // бросок модели (её доля вверх — турбулентность у земли, 0,1) повёрнут вверх на 19–35° к земле, модуль
                    // скорости — её же: подача (как у FrontLitter), «подпрыгивают» — так вблизи и видно, что сорвало
                    double speed = Math.hypot(lift.horizontalMps(), lift.verticalMps()) / 20;
                    BlockPos from0 = new BlockPos(bx, c.torn == plant ? top + 1 : top, bz);
                    for (int k = 0; k < n && born < budget; k++) {
                        double[] kick = FrontLitter.launch(speed, LIFT_MIN + (LIFT_MAX - LIFT_MIN) * rnd.nextDouble());
                        float size = c.kind == Fragments.Kind.LEAF ? CLUMP_MIN + (CLUMP_MAX - CLUMP_MIN) * rnd.nextFloat()
                                : GRAIN_MIN + (GRAIN_MAX - GRAIN_MIN) * rnd.nextFloat();
                        // рисуется крупнее, летит и оседает настоящим телом закона
                        if (Fragments.torn(level, c.torn, from0, c.kind, x + (rnd.nextDouble() - 0.5) * STEP, y, z + (rnd.nextDouble() - 0.5) * STEP,
                                ox * kick[0], kick[1], oz * kick[0], size, lift.settlingMps(), AIR, delay, crown)) born++;
                    }
                }
                if (dust(level, lift, coverState, x, y, z, ox, oz, delay, rnd)) puffs++;
            }
        }
        last = String.format(Locale.ROOT, "near litter pieces %d puffs %d", born, puffs);
    }

    /**
     * Шаг расстояния до взрыва, м, и доли напора вдоль земли, на которых закон считается один раз: на 0,25 м давление
     * фронта меняется на ~5 % в 10 м и на десятые доли процента в сотнях метров, а места кусков и так разбросаны по метровой
     * клетке. Залп из 8 шахедов над песком считал бы закон у каждой колонки — ~7 мс на взрыв в тике удара.
     */
    static final double SLANT_STEP = 0.25, ALONG_STEP = 0.05;

    /** Номер поверхности для ключа закона. */
    private static int surfaceIndex(DustModel.Surface s) {
        return s == DustModel.Surface.FIELD ? 0 : s == DustModel.Surface.COVERED ? 1 : 2;
    }

    /**
     * Поднимет ли фронт хоть что-то видимое там, где он сильнее всего в круге, — в {@code slant} м от взрыва (на рыхлом
     * грунте и на траве): нет — не поднимет нигде дальше, и колонки не перебираются (у «Града» в 200 м — доля срыва
     * без броска и без пыли).
     */
    static boolean visible(double charge, boolean surface, double slant) {
        ShockAtBody shock = ShockAtBody.chemical(charge, surface, slant);
        LocalLitter.Lift loose = LocalLitter.lift(shock, DustModel.Surface.FIELD, 1, terminal(Fragments.Kind.CHUNK));
        LocalLitter.Lift grass = LocalLitter.lift(shock, DustModel.Surface.COVERED, 1, terminal(Fragments.Kind.LEAF));
        return pieces(loose) || pieces(grass) || dustAlpha(loose.massKgPerM2(), PUFF_MIN) >= PUFF_SEEN;
    }

    /** Облачко тонкой пыли прозрачнее этого не рисуется. */
    static final float PUFF_SEEN = 0.02f;

    /**
     * Непрозрачность облачка тонкой пыли размера {@code size} м с поднятой массой {@code kgPerM2} с клетки места:
     * 1 − e^(−τ), τ = {@link DustModel#K_MIX}·m / площадь облачка, не больше {@link #PUFF_ALPHA}.
     */
    static float dustAlpha(double kgPerM2, float size) {
        double tau = DustModel.K_MIX * Math.max(0, kgPerM2) * STEP * STEP / (Math.PI * size * size);
        return (float) Math.min(PUFF_ALPHA, 1 - Math.exp(-tau));
    }

    /** Куски видно, только если их бросило ({@link #MIN_KICK_MPS}) и сорвало. */
    static boolean pieces(LocalLitter.Lift lift) {
        return lift.releaseShare() > 0 && Math.hypot(lift.horizontalMps(), lift.verticalMps()) >= MIN_KICK_MPS;
    }

    /**
     * Тонкая пыль места: облачко, которое воздух фазы сдвигает на {@link LocalLitter.Lift#airDisplacementM} от взрыва
     * (быстро — за фазу), потом оно почти не оседает ({@link #DUST_SETTLE_MPS}), расходится и уходит по ветру. Непрозрачность — толща поднятой массы
     * на площади облачка ({@link DustModel#K_MIX}), не больше {@link #PUFF_ALPHA} (подача).
     */
    private static boolean dust(ClientLevel level, LocalLitter.Lift lift, BlockState cover, double x, double y, double z, double ox, double oz,
                                int delay, RandomSource rnd) {
        if (!(lift.massKgPerM2() > 0)) return false;
        float size = PUFF_MIN + (PUFF_MAX - PUFF_MIN) * rnd.nextFloat();
        float alpha = dustAlpha(lift.massKgPerM2(), size);
        if (alpha < PUFF_SEEN) return false;
        float[] c = FarBlasts.dustColour(GroundMaterial.of(cover));
        // сопротивление вдвое за тик: сдвиг за фазу (миллисекунды) ложится за первые тики суммой v·(1 + ½ + ¼ …) = 2v, а
        // скорость падения выходит g·d/(1 − d) = g — оседание со своей скоростью
        double shift = lift.airDisplacementM() / 2;
        Fx.smoke().vel(ox * shift, lift.verticalMps() / 40, oz * shift).size(size, size * PUFF_GROWTH).life(PUFF_LIFE + rnd.nextInt(PUFF_LIFE_SPREAD))
                .color(c, c).alpha(alpha).drag(0.5f).gravity((float) (DUST_SETTLE_MPS / 20)).wind(1).fadeIn(1, delay).fadeFrom(0.5f)
                .budget(FxBudget.GROUND).spawn(level, new Vec3(x, y - 0.6, z));
        return true;
    }
}
