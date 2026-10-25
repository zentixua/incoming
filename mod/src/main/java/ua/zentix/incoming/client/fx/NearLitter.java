package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
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
     * Фронт взрыва {@code charge} кг ТНТ ({@code surface} — на земле) из {@code blast} прошёл тело в {@code eye}
     * ({@code bodyArrival} с после взрыва): сор вокруг. Каждое место ждёт прихода фронта на себя.
     */
    static void spawn(ClientLevel level, double charge, boolean surface, Vec3 blast, Vec3 eye, double bodyArrival, RandomSource rnd) {
        if (!(charge > 0)) return;
        int budget = Fragments.share(MAX), born = 0, puffs = 0;
        double groundY = blast.y;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int reach = (int) Math.ceil(REACH / STEP);
        for (int i = -reach; i <= reach && born < budget; i++) {
            for (int j = -reach; j <= reach && born < budget; j++) {
                double dx = i * STEP + (rnd.nextDouble() - 0.5) * STEP, dz = j * STEP + (rnd.nextDouble() - 0.5) * STEP;
                if (dx * dx + dz * dz > REACH * REACH) continue;
                double x = eye.x + dx, z = eye.z + dz;
                int bx = Mth.floor(x), bz = Mth.floor(z);
                LevelChunk chunk = level.getChunkSource().getChunk(bx >> 4, bz >> 4, false);
                if (chunk == null) continue;
                // верхний блок (getHeight чанка — уже он, а не первый воздух над ним)
                int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15);
                if (top < level.getMinBuildHeight()) continue;
                BlockState coverState = chunk.getBlockState(m.set(bx, top, bz)), plant = chunk.getBlockState(m.set(bx, top + 1, bz));
                Cover c = cover(coverState, plant);
                if (c == null) continue;
                boolean high = c.torn == coverState && coverState.is(BlockTags.LEAVES);
                double y = top + 1.05, ground = Math.hypot(x - blast.x, z - blast.z), slant = Math.hypot(ground, y - blast.y);
                // крона и то, что выше места взрыва, видят волну в воздухе
                double along = high || y > groundY + 2 ? 1 : FrontLitter.along(ground, slant);
                ShockAtBody shock = ShockAtBody.chemical(charge, surface, slant);
                float size = c.kind == Fragments.Kind.LEAF ? CLUMP_MIN + (CLUMP_MAX - CLUMP_MIN) * rnd.nextFloat()
                        : GRAIN_MIN + (GRAIN_MAX - GRAIN_MIN) * rnd.nextFloat();
                double terminal = c.kind == Fragments.Kind.LEAF ? Fragments.terminal(c.kind, 0, size) : Fragments.terminal(c.kind, GRAIN_DENSITY, CRUMB_M);
                LocalLitter.Lift lift = LocalLitter.lift(shock, c.surface, along, terminal);
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
                    for (int k = 0; k < n && born < budget; k++) {
                        double[] kick = FrontLitter.launch(speed, LIFT_MIN + (LIFT_MAX - LIFT_MIN) * rnd.nextDouble());
                        if (Fragments.torn(level, c.torn, m.set(bx, c.torn == plant ? top + 1 : top, bz).immutable(), c.kind,
                                x + (rnd.nextDouble() - 0.5) * STEP, y, z + (rnd.nextDouble() - 0.5) * STEP, ox * kick[0], kick[1], oz * kick[0], size, AIR, delay, high)) born++;
                    }
                }
                if (dust(level, lift, coverState, x, y, z, ox, oz, delay, rnd)) puffs++;
            }
        }
        last = String.format(Locale.ROOT, "near litter pieces %d puffs %d", born, puffs);
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
        double tau = DustModel.K_MIX * lift.massKgPerM2() * STEP * STEP / (Math.PI * size * size);
        float alpha = (float) Math.min(PUFF_ALPHA, 1 - Math.exp(-tau));
        if (alpha < 0.02f) return false;
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
