package ua.zentix.incoming.warhead;

import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LiquidBlock;
import ua.zentix.incoming.nuclear.model.BlastDamage;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.world.BlastBody;
import ua.zentix.incoming.nuclear.world.BlockResponse;
import ua.zentix.incoming.registry.ModTags;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModDamageTypes;
import ua.zentix.incoming.registry.ModParticles;
import ua.zentix.incoming.registry.ModSounds;
import ua.zentix.incoming.strike.ImpactCost;
import ua.zentix.incoming.util.PoiRecords;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.work.UnitQueue;
import ua.zentix.incoming.work.WorkScheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Ванильный взрыв по единицам работы ({@link UnitQueue}): те же шаги, что у {@code ServerLevel.explode}, только
 * порциями в разных единицах. На сборке хоста (город, ракета) два взрыва силы 12–20 одним вызовом — 559 мс в одном
 * тике, а при делении только на подрыв и порции блоков одна единица «лучи и урон» стоила бы до 150 мс.
 * <ol>
 *     <li>{@link Explosion} с правилами TNT, {@link EventHooks#onExplosionStart} (приваты отменяют его, как обычно),
 *     один запрос аппаратов Sable на весь охват лучей. Аппарат рядом — ванильный {@link Explosion#explode()} в этой же
 *     единице: миксин Sable на каждом воздушном шаге луча ищет аппарат, переводит точку в плот, добавляет блоки аппарата
 *     и толкает его — это его внутренности, повторять их нельзя, и мод в этот метод не лезет; урон и отбрасывание
 *     уходят в свои единицы через {@code ExplosionEvent.Detonate} ({@link #onDetonate}). Нет аппарата — миксину Sable нечего
 *     менять, и лучи идут своим циклом (копия ванильного): здесь только событие {@code EXPLODE} и все 1352 множителя
 *     силы лучей из {@code level.random} в ванильном порядке — выборка не зависит от того, как лучи поделены.</li>
 *     <li>Лучи по {@link #RAYS_PER_UNIT} за единицу; множество выбранных копится. Внутри луча блок, в котором лежат
 *     подряд несколько шагов по 0,3 блока, читается один раз (как у Lithium {@code world.explosions}).</li>
 *     <li>Сбор сущностей и {@code ExplosionEvent.Detonate} (обработчики модов — одной единицей, со списком, по которому
 *     потом идёт урон).</li>
 *     <li>Разбор выбранного: воздух без огня отбрасывается, плоты аппаратов — раз на чанк, мир — от центра наружу по
 *     заранее посчитанному расстоянию.</li>
 *     <li>Урон и отбрасывание по {@link #ENTITIES_PER_UNIT} сущностей (копия ванильного цикла; живость, расстояние
 *     и видимость — на момент единицы); игроку пакет взрыва с его отбрасыванием уходит в той же единице. Потом —
 *     блоки аппаратов, одним разом ({@link #blowCrafts}).</li>
 *     <li>Блоки мира от центра наружу порциями по времени ({@link #PORTION_NANOS}) шагами {@code Explosion.finalizeExplosion}
 *     ({@code onExplosionHit}, затем огонь). Выпадение копится за весь взрыв и выпадает в конце. Блок, который сменился
 *     с момента лучей (натекла вода, поставил игрок), не трогается; сменившиеся свойства того же блока (забор потерял
 *     соседа) — не смена.</li>
 * </ol>
 * Между единицами мир может меняться (1–3 тика) — порции сверяют блок. Взрыв с центром в сетке плотов (на аппарате) —
 * одним ванильным {@code level.explode}: Sable переносит такой взрыв в мир своей обёрткой {@code ServerLevel.explode}.
 * <p>
 * Район держит {@link BlastArea} (свой или таймлайна), пока взрыв не кончится; единица ждёт, пока район готов, а блоки
 * в чанках, которые к её сроку не готовы, пропускает.
 */
final class StagedExplosion implements UnitQueue.Job {
    /** Порция блоков мира — по времени: блоки идут, пока единица короче этого. */
    static final long PORTION_NANOS = 5_000_000L;
    /** Блок дольше этого — строка в лог. */
    static final long SLOW_BLOCK_NANOS = 10_000_000L;
    /** Пачка блоков от центра наружу, внутри которой порядок перемешан. */
    static final int BATCH = 16;
    /** Лучей за единицу (всего 1352). */
    static final int RAYS_PER_UNIT = 128;
    /** Сущностей за единицу урона: смерть моба — лут и обработчики всех модов сборки. */
    static final int ENTITIES_PER_UNIT = 8;

    /** Направления 1352 лучей ванильного взрыва в ванильном порядке (грани куба 16³, нормированные). */
    private static final double[] RAY_X, RAY_Y, RAY_Z;

    static {
        List<double[]> dirs = new ArrayList<>(1352);
        for (int j = 0; j < 16; j++) {
            for (int k = 0; k < 16; k++) {
                for (int l = 0; l < 16; l++) {
                    if (j == 0 || j == 15 || k == 0 || k == 15 || l == 0 || l == 15) {
                        double d0 = (double) ((float) j / 15.0F * 2.0F - 1.0F);
                        double d1 = (double) ((float) k / 15.0F * 2.0F - 1.0F);
                        double d2 = (double) ((float) l / 15.0F * 2.0F - 1.0F);
                        double d3 = Math.sqrt(d0 * d0 + d1 * d1 + d2 * d2);
                        dirs.add(new double[]{d0 / d3, d1 / d3, d2 / d3});
                    }
                }
            }
        }
        RAY_X = new double[dirs.size()];
        RAY_Y = new double[dirs.size()];
        RAY_Z = new double[dirs.size()];
        for (int i = 0; i < dirs.size(); i++) {
            RAY_X[i] = dirs.get(i)[0];
            RAY_Y[i] = dirs.get(i)[1];
            RAY_Z[i] = dirs.get(i)[2];
        }
    }

    static int rays() {
        return RAY_X.length;
    }

    /** Что делает следующая единица. */
    private enum Phase { START, RAYS, GAS, DETONATE, SPLIT, DAMAGE, BLOCKS }

    /** Проверки: лучи ванильным {@code explode()} (как при аппарате рядом) — сравнить выборку своего цикла с ванильной. */
    boolean forceVanilla;
    /** Проверки: лучей за единицу. */
    int raysPerUnit = RAYS_PER_UNIT;
    /** Проверки: сид {@code level.random} перед лучами (выборка обоих путей из одних множителей). */
    @Nullable
    Long seed;
    /** Проверки: часы порций блоков; null — часы полосы попаданий ({@link WorkClock#sampler}: у считающих — 1 шаг на блок). */
    @Nullable
    LongSupplier clock;
    /** Проверки: срок порции блоков. */
    long portionNanos = PORTION_NANOS;
    /** Для проверок: порций блоков и самая большая из них. */
    int portions, largestPortion;

    private final BlastArea area;
    /**
     * Заряд боевой части по физике, кг ТНТ ({@link #charge}): волна Кинни–Грэхема ломает блоки по кривой давление–импульс
     * ({@code BlockResponse.breaksAt}), газы держат давление в помещении, тела бьёт {@link BlastBody}. 0 — ванильный взрыв
     * силы {@link #power} (бетонобойная бомба, её вход в грунт, проверки).
     */
    private final double charge;
    /** Центр волны в мире: у взрыва на аппарате Sable — его точка в мире ({@link SubLevels#toWorld}). */
    private Vec3 centre;
    /** Взрыв по заряду: докуда лучи могут сломать хоть что-то и докуда волна бьёт по телам, блоков. */
    private final double breakReach, hitReach;
    /** Случайность тел у этого взрыва ({@code BlastBody.fate}). */
    private long waveId;
    /** Помещение, где взорвался заряд (газы), или null — взрыв на открытом. */
    @Nullable
    private Room room;
    @Nullable
    private BlastBody.Wave wave;
    /** Взрывы, которые должны кончиться раньше (вторичные подрывы удара ждут главный). */
    private final List<StagedExplosion> after;
    private final Vec3 at;
    private final float power;
    private final boolean fire;
    private final boolean blocks;
    @Nullable
    private final Entity direct;
    @Nullable
    private final Entity owner;
    @Nullable
    private final ExplosionDamageCalculator calculator;

    private Phase phase = Phase.START;
    @Nullable
    private Explosion explosion;
    /** Калькулятор, которым считает сам {@link Explosion} (без своего — ванильный по умолчанию). */
    private ExplosionDamageCalculator damage = new ExplosionDamageCalculator();
    @Nullable
    private DamageSource source;
    /** Сила лучей: мощность × множитель из {@code level.random}, в ванильном порядке. */
    private float[] strength = new float[0];
    private int nextRay;
    private final Set<BlockPos> chosen = new HashSet<>();
    private List<Entity> entities = List.of();
    private int nextEntity;
    private final Set<ServerPlayer> told = new HashSet<>();
    /** Блоки мира, от центра наружу, и сами блоки на момент лучей. */
    private List<BlockPos> toBlow = List.of();
    private List<Block> blocksAtRays = List.of();
    private int next;
    /** Начатая пачка: индексы в {@link #toBlow}, перемешаны, берутся с конца. */
    private final IntArrayList batch = new IntArrayList();
    /** Позиции мира после раздела, до снимка ({@link #split}). */
    private List<BlockPos> picked = List.of();
    /** Блоки аппаратов после раздела и сами блоки на момент раздела — ждут конца урона ({@link #blowCrafts}). */
    private List<BlockPos> crafts = List.of();
    private List<Block> craftBlocks = List.of();
    /** Чей плот лежал в чанке блоков аппаратов при разделе: id аппарата или null. */
    private final Long2ObjectOpenHashMap<UUID> craftOwners = new Long2ObjectOpenHashMap<>();
    private boolean partitioned;
    /** Взрыв шёл ванильным {@code explode()} (аппарат рядом). */
    private boolean vanilla;
    /** Урон уже сделал ванильный {@code explode()}: список сущностей из {@code Detonate} к моду не попал. */
    private boolean vanillaDamage;
    /** Взрыв, чей ванильный {@code explode()} мод сейчас ведёт (поток сервера), и список, забранный у его {@code Detonate}. */
    @Nullable
    private static Explosion driving;
    @Nullable
    private static List<Entity> handed;
    /** Выпадение за весь взрыв (как {@code Explosion.addOrAppendStack}). */
    private final List<Pair<ItemStack, BlockPos>> drops = new ArrayList<>();
    private boolean done;

    /** Замер: время по шагам ({@link ExplosionStage}), счётчики лучей. */
    private final long[] stages = new long[ExplosionStage.values().length];
    private final RayCounts counts = new RayCounts();

    /** Счётчики лучей взрыва для итога удара. */
    static final class RayCounts {
        /** Шаги лучей (по 0,3 блока). */
        long steps;
        /** Из них в воздухе — на каждом таком шаге миксин Sable в ванильном {@code explode()} ищет аппарат. */
        long airSteps;
        /** Шаги, где блок взят из кэша луча (тот же блок, что на прошлом шаге). */
        long cached;
        /** Запросы аппаратов Sable (свои: один на взрыв). */
        int craftQueries;
        /** Взрывы, которые шли ванильным {@code explode()} целиком (аппарат рядом). */
        int vanilla;

        void add(RayCounts o) {
            steps += o.steps;
            airSteps += o.airSteps;
            cached += o.cached;
            craftQueries += o.craftQueries;
            vanilla += o.vanilla;
        }
    }

    StagedExplosion(BlastArea area, List<StagedExplosion> after, Vec3 at, float power, boolean fire, boolean blocks, @Nullable Entity direct,
                    @Nullable Entity owner, @Nullable ExplosionDamageCalculator calculator) {
        this(area, after, at, power, 0, fire, blocks, direct, owner, calculator);
    }

    /** Взрыв заряда {@code tntKg} кг ТНТ по физике. */
    StagedExplosion(BlastArea area, List<StagedExplosion> after, Vec3 at, double tntKg, boolean blocks, @Nullable Entity direct, @Nullable Entity owner) {
        this(area, after, at, (float) breakReach(tntKg), tntKg, false, blocks, direct, owner, null);
    }

    private StagedExplosion(BlastArea area, List<StagedExplosion> after, Vec3 at, float power, double charge, boolean fire, boolean blocks,
                            @Nullable Entity direct, @Nullable Entity owner, @Nullable ExplosionDamageCalculator calculator) {
        this.area = area;
        this.after = after;
        this.at = at;
        this.centre = at;
        this.charge = charge;
        this.breakReach = charge > 0 ? breakReach(charge) : 0;
        this.hitReach = charge > 0 ? hitReach(charge) : 0;
        this.power = power;
        this.fire = fire;
        this.blocks = blocks;
        this.direct = direct;
        this.owner = owner;
        this.calculator = calculator;
    }

    /** Взрыв кончился (или отменён): работы удара, которые ждут его, могут идти. */
    boolean done() {
        return done;
    }

    @Override
    public boolean ready(ServerLevel level) {
        // район удара целиком (он накрывает и этот взрыв): у работ одного удара одно условие — порядок не ломается
        return area.ready(level);
    }

    /** Шёл ванильным {@code explode()}. */
    boolean vanilla() {
        return vanilla;
    }

    /** Ванильный путь отдал урон моду ({@link #onDetonate}). */
    boolean handedDamage() {
        return vanilla && !vanillaDamage;
    }

    /** Ещё выбирает лучами или бьёт сущности (блоки не снимает). */
    boolean picking() {
        return !done && phase.ordinal() < Phase.BLOCKS.ordinal();
    }

    /** Ждёт взрывы {@code after} целиком. */
    boolean waiting() {
        return Warheads.pending(after);
    }

    @Override
    public boolean blocked() {
        return waiting() || phase == Phase.BLOCKS && area.peersPicking(this);
    }

    @Override
    public int unitKind() {
        return kind().ordinal();
    }

    private ImpactCost.Kind kind() {
        return switch (phase) {
            case START, RAYS, GAS -> ImpactCost.Kind.RAYS;
            case DETONATE, DAMAGE -> ImpactCost.Kind.HITS;
            case SPLIT, BLOCKS -> ImpactCost.Kind.BLOCKS;
        };
    }

    @Override
    public boolean step(ServerLevel level) {
        long t0 = System.nanoTime();
        ImpactCost.Kind kind = kind();
        long[] before = stages.clone();
        int count = 0;
        boolean more;
        switch (phase) {
            case START -> {
                count = 1;
                more = charge > 0 ? startCharge(level) : start(level);
            }
            case RAYS -> more = charge > 0 ? chargeRays(level) : rays(level);
            case GAS -> more = gas(level);
            case DETONATE -> more = detonate(level);
            case SPLIT -> more = split(level);
            case DAMAGE -> more = charge > 0 ? chargeDamage(level) : damage(level);
            default -> {
                more = portion(level);
                count = blown;
            }
        }
        area.record(level, kind, System.nanoTime() - t0, count);
        for (int i = 0; i < before.length; i++) before[i] = stages[i] - before[i];
        area.recordStages(level, before);
        if (!more) popDrops(level);
        return more;
    }

    private void time(ExplosionStage stage, long since) {
        stages[stage.ordinal()] += System.nanoTime() - since;
    }

    /** Первая единица; {@code true} — есть ещё единицы. */
    private boolean start(ServerLevel level) {
        long t = System.nanoTime();
        // выпадение с затуханием, как у крипера: снятый блок выпадает с вероятностью 1/сила (у TNT по умолчанию выпадает
        // всё, и после залпов по 30 тик тысяч предметов занимал бы две трети потока сервера). Level.explode берёт его из
        // правила: BLOCK — blockExplosionDropDecay, по умолчанию включено
        Level.ExplosionInteraction interaction = blocks ? Level.ExplosionInteraction.BLOCK : Level.ExplosionInteraction.NONE;
        if (SubLevels.inPlotGrid(level, new ChunkPos(BlockPos.containing(at)))) {
            counts.vanilla++;
            explosion = level.explode(null, ModDamageTypes.source(level, ModDamageTypes.STRIKE, direct, owner), calculator,
                    at.x, at.y, at.z, power, fire, interaction, ModParticles.NONE.get(), ModParticles.NONE.get(), ModSounds.SILENT);
            return false;
        }
        Explosion.BlockInteraction destroy = blocks ? Explosion.BlockInteraction.DESTROY_WITH_DECAY : Explosion.BlockInteraction.KEEP;
        source = ModDamageTypes.source(level, ModDamageTypes.STRIKE, direct, owner);
        Explosion e = new Explosion(level, null, source, calculator,
                at.x, at.y, at.z, power, fire, destroy, ModParticles.NONE.get(), ModParticles.NONE.get(), ModSounds.SILENT);
        explosion = e;
        if (calculator != null) damage = calculator;
        boolean cancelled = EventHooks.onExplosionStart(level, e);
        time(ExplosionStage.START, t);
        if (cancelled) return false;
        // в воздухе калькулятор сопротивления не даёт, и сила луча падает только на 0,225 за шаг в 0,3 блока: луч
        // уходит до 1,3 × 1,33 ≈ 1,73 силы — аппарат ищем в охвате района взрыва (2 силы + 1)
        double reach = Warheads.reach(power);
        counts.craftQueries++;
        if (seed != null) level.random.setSeed(seed);
        if (forceVanilla || SubLevels.mayHaveCraftNear(level, at, reach)) return vanilla(level, e);
        t = System.nanoTime();
        level.gameEvent(null, GameEvent.EXPLODE, at);
        time(ExplosionStage.GAME_EVENT, t);
        strength = new float[rays()];
        for (int i = 0; i < strength.length; i++) strength[i] = power * (0.7F + level.random.nextFloat() * 0.6F);
        phase = Phase.RAYS;
        return true;
    }

    /**
     * Аппарат рядом: ванильный {@code explode()} (лучи с миксином Sable, сбор сущностей, {@code Detonate}) — одним шагом
     * замера, снимок блоков мира — следующей единицей. Урон и отбрасывание — порциями ({@link Phase#DAMAGE}) по списку,
     * который обработчики {@code Detonate} оставили ({@link #onDetonate}): ванильный цикл урона идёт в той же единице, что
     * и лучи (ракета у дирижабля: весь {@code explode()} — 181,6 мс в одном тике). Блоки
     * аппаратов — после урона ({@link #blowCrafts}); если урон всё же сделал сам {@code explode()} — сразу.
     */
    private boolean vanilla(ServerLevel level, Explosion e) {
        counts.vanilla++;
        vanilla = true;
        long t = System.nanoTime();
        // взрыв, начатый изнутри (обработчиком события), ведёт свой: внешний — вернуть как был
        Explosion outer = driving;
        List<Entity> outerHanded = handed;
        driving = e;
        handed = null;
        List<Entity> got;
        try {
            e.explode();
        } finally {
            got = handed;
            handed = outerHanded;
            driving = outer;
        }
        time(ExplosionStage.VANILLA, t);
        if (got != null) {
            entities = got;
        } else {
            // список не забран (Detonate не пришёл): урон сделал сам explode() — его пакет игрокам, как у ванили
            vanillaDamage = true;
            for (ServerPlayer p : level.players()) {
                if (p.distanceToSqr(at.x, at.y, at.z) < 4096.0) tell(p, e.getHitPlayers().get(p));
            }
        }
        partition(level);
        // урон уже позади — блоки аппаратов сразу
        if (vanillaDamage) blowCrafts(level);
        phase = Phase.SPLIT;
        return true;
    }

    /** Лучи {@code [nextRay, nextRay + raysPerUnit)} — копия ванильного цикла {@code Explosion.explode}. */
    private boolean rays(ServerLevel level) {
        long t = System.nanoTime();
        Explosion e = explosion;
        int end = Math.min(rays(), nextRay + raysPerUnit);
        for (int r = nextRay; r < end; r++) {
            float f = strength[r];
            double x = at.x, y = at.y, z = at.z;
            double dx = RAY_X[r], dy = RAY_Y[r], dz = RAY_Z[r];
            BlockPos last = null;
            BlockState state = null;
            Optional<Float> resistance = Optional.empty();
            while (f > 0.0F) {
                BlockPos pos = BlockPos.containing(x, y, z);
                if (pos.equals(last)) {
                    counts.cached++;
                } else {
                    if (!level.isInWorldBounds(pos)) break;
                    state = level.getBlockState(pos);
                    FluidState fluid = level.getFluidState(pos);
                    resistance = damage.getBlockExplosionResistance(e, level, pos, state, fluid);
                    last = pos;
                }
                counts.steps++;
                if (state.isAir()) counts.airSteps++;
                if (resistance.isPresent()) f -= (resistance.get() + 0.3F) * 0.3F;
                if (f > 0.0F && damage.shouldBlockExplode(e, level, pos, state, f)) chosen.add(pos);
                x += dx * 0.3F;
                y += dy * 0.3F;
                z += dz * 0.3F;
                f -= 0.22500001F;
            }
        }
        nextRay = end;
        time(ExplosionStage.RAYS, t);
        if (nextRay >= rays()) phase = Phase.DETONATE;
        return true;
    }

    // ---------------------------------------------------------------- взрыв по заряду

    /** Сколько клеток воздуха обходит поиск помещения у заряда: больше — взрыв на открытом (UFC: помещение — замкнутый объём). */
    static final int ROOM_CELLS = 4000;
    /** Блок, которому ванильный взрыв не страшен (обсидиан 1200 и защищённые блоки модов), волну держит. */
    private static final float BLAST_PROOF = 1200;
    /** Шаг луча, блоков: как у ванили. */
    private static final double STEP = 0.3;

    /** Докуда волна заряда {@code tntKg} может сломать хоть что-то — самый слабый блок у нижнего края разброса, блоков. */
    static double breakReach(double tntKg) {
        return reachFor(BlockResponse.FRAGILE_PSI, tntKg) + 1;
    }

    /** Докуда волна заряда бьёт тела: до порога осколков стекла ({@code BlastBody}: ниже него ран нет), блоков. */
    static double hitReach(double tntKg) {
        return BlastModel.rangeForSurfaceOverpressure(BlastModel.kpa(BlockResponse.FRAGILE_PSI), tntKg);
    }

    /** Докуда в открытом поле ломается блок с порогом {@code psi} у нижнего края разброса, блоков. */
    static double reachFor(double psi, double tntKg) {
        double pc = BlastModel.kpa(psi);
        double lo = 0.05, hi = 1e4;
        if (!breaksOpen(lo, tntKg, pc)) return 0;
        for (int i = 0; i < 60; i++) {
            double m = Math.sqrt(lo * hi);
            if (breaksOpen(m, tntKg, pc)) lo = m;
            else hi = m;
        }
        return lo;
    }

    /** Как {@code BlockResponse.breaksAt} у нижнего края разброса: давление и импульс делятся на {@code JITTER_MIN}. */
    private static boolean breaksOpen(double r, double tntKg, double pcKpa) {
        double j = BlockResponse.JITTER_MIN;
        return BlastDamage.breaks(BlastModel.surfaceOverpressureKpa(r, tntKg) / j, BlastModel.chemicalImpulse(r, tntKg, true) / j, pcKpa);
    }

    /**
     * Первая единица взрыва по заряду: {@link Explosion} с радиусом {@link #breakReach} (его видят обработчики событий и
     * выпадение с затуханием), {@code ExplosionEvent.Start} (приваты отменяют его, как обычно), событие {@code EXPLODE}.
     * Ванильного пути нет: блоки аппаратов ломает тот же закон ({@link #crafts}).
     */
    private boolean startCharge(ServerLevel level) {
        long t = System.nanoTime();
        if (SubLevels.inPlotGrid(level, new ChunkPos(BlockPos.containing(at)))) centre = SubLevels.toWorld(level, at);
        Explosion.BlockInteraction destroy = blocks ? Explosion.BlockInteraction.DESTROY_WITH_DECAY : Explosion.BlockInteraction.KEEP;
        source = ModDamageTypes.source(level, ModDamageTypes.STRIKE, direct, owner);
        Explosion e = new Explosion(level, null, source, null, centre.x, centre.y, centre.z, power, false, destroy,
                ModParticles.NONE.get(), ModParticles.NONE.get(), ModSounds.SILENT);
        explosion = e;
        // обработчики начала уже видят заряд ({@link #chargeOf})
        CHARGES.put(e, charge);
        cancelled = EventHooks.onExplosionStart(level, e);
        time(ExplosionStage.START, t);
        if (cancelled) return false;
        waveId = level.random.nextLong();
        t = System.nanoTime();
        level.gameEvent(null, GameEvent.EXPLODE, centre);
        time(ExplosionStage.GAME_EVENT, t);
        phase = Phase.RAYS;
        return true;
    }

    /**
     * Лучи {@code [nextRay, nextRay + raysPerUnit)} волны заряда: вдоль луча давление и импульс — Кинни–Грэхем на земле
     * ({@link BlastModel}), блок ломается по своей кривой давление–импульс ({@code BlockResponse.breaksAt}), сломанный
     * пропускает дальше долю волны ({@code BlockResponse.passes}), уцелевший, грунт, жидкость и неразрушимое — тень: за
     * ними луч кончается. Приближение: волна не огибает дома, а у сломанного блока теряет ровно импульс его разрушения.
     */
    private boolean chargeRays(ServerLevel level) {
        long t = System.nanoTime();
        Explosion e = explosion;
        int end = Math.min(rays(), nextRay + raysPerUnit);
        // блок, в который снаряд ударил и в котором рвётся заряд, — его сносит контактный взрыв (грунт — воронка):
        // волна идёт от его граней
        long contact = BlockPos.containing(centre).asLong();
        if (nextRay == 0 && Terrain.ready(level, BlockPos.of(contact))) {
            BlockPos c = BlockPos.of(contact);
            BlockState s = level.getBlockState(c);
            BlockResponse response = BlockResponse.of(s);
            if (!s.isAir() && response.kind() != BlockResponse.Kind.NONE && response.kind() != BlockResponse.Kind.GROUND
                    && !(s.getBlock() instanceof LiquidBlock) && s.getExplosionResistance(level, c, e) < BLAST_PROOF
                    && damage.shouldBlockExplode(e, level, c, s, 1f)) chosen.add(c);
        }
        for (int r = nextRay; r < end; r++) {
            double dx = RAY_X[r], dy = RAY_Y[r], dz = RAY_Z[r];
            double share = 1;
            long last = contact;
            for (double d = 0; d <= breakReach; d += STEP) {
                double x = centre.x + dx * d, y = centre.y + dy * d, z = centre.z + dz * d;
                BlockPos pos = BlockPos.containing(x, y, z);
                long key = pos.asLong();
                counts.steps++;
                if (key == last) {
                    counts.cached++;
                    continue;
                }
                last = key;
                if (!level.isInWorldBounds(pos) || !Terrain.ready(level, pos)) break;
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) {
                    counts.airSteps++;
                    continue;
                }
                if (state.getBlock() instanceof LiquidBlock || state.getExplosionResistance(level, pos, e) >= BLAST_PROOF) break;
                BlockResponse response = BlockResponse.of(state);
                if (response.kind() == BlockResponse.Kind.NONE || response.kind() == BlockResponse.Kind.GROUND) break;
                double range = Math.max(0.25, d);
                double psi = BlastModel.psi(BlastModel.surfaceOverpressureKpa(range, charge)) * share;
                double impulse = BlastModel.chemicalImpulse(range, charge, true) * share;
                int seed = Mth.murmurHash3Mixer(Long.hashCode(key));
                boolean breaks = damage.shouldBlockExplode(e, level, pos, state, 1f) && response.breaksAt(psi, impulse, seed);
                // трава, цветы, посевы, факелы без формы столкновений — без массы на пути волны: сломаны они или нет,
                // волна идёт сквозь, ничего не теряя
                boolean massless = state.getCollisionShape(level, pos).isEmpty();
                if (breaks) chosen.add(pos);
                if (massless) continue;
                if (!breaks) break;
                share *= response.passes(psi, impulse, seed);
                if (share < 1e-3) break;
            }
        }
        nextRay = end;
        time(ExplosionStage.RAYS, t);
        if (nextRay >= rays()) phase = Phase.GAS;
        return true;
    }

    /**
     * Газы взрыва в помещении: обход воздуха от заряда не больше {@link #ROOM_CELLS} клеток. Кончился раньше — заряд в
     * замкнутом объёме: давление газов ({@link BlastDamage#quasiStaticKpa}) давит на все стены, пока не вытечет через
     * проёмы — стёкла, деревянные двери и люки и то, что уже пробили лучи ({@link BlastDamage#ventSeconds}); стена
     * ломается по той же кривой, с импульсом газов. Комнату выбивает целиком — выбитый этаж.
     */
    private boolean gas(ServerLevel level) {
        long t = System.nanoTime();
        room = Room.find(level, centre, chosen);
        if (room != null) {
            double kpa = BlastDamage.quasiStaticKpa(charge, room.cells().size());
            double seconds = BlastDamage.ventSeconds(room.cells().size(), room.vents());
            double psi = BlastModel.psi(kpa), impulse = Double.isInfinite(seconds) ? Double.MAX_VALUE : kpa * 1000 * seconds;
            room = new Room(room.cells(), room.walls(), room.vents(), kpa);
            Explosion e = explosion;
            for (BlockPos p : room.walls()) {
                BlockState s = level.getBlockState(p);
                BlockResponse response = BlockResponse.of(s);
                if (response.kind() == BlockResponse.Kind.NONE || response.kind() == BlockResponse.Kind.GROUND) continue;
                if (s.getExplosionResistance(level, p, e) >= BLAST_PROOF || !damage.shouldBlockExplode(e, level, p, s, 1f)) continue;
                if (response.breaksAt(psi, impulse, Mth.murmurHash3Mixer(Long.hashCode(p.asLong())))) chosen.add(p);
            }
        }
        time(ExplosionStage.RAYS, t);
        phase = Phase.DETONATE;
        return true;
    }

    /**
     * Помещение заряда: клетки воздуха, стены вокруг, проёмы (м²) и давление газов, кПа.
     */
    record Room(LongOpenHashSet cells, List<BlockPos> walls, int vents, double kpa) {
        /** Замкнутый объём у {@code centre}; null — воздух уходит дальше {@link #ROOM_CELLS} (взрыв на открытом) или в неготовый чанк. */
        @Nullable
        static Room find(ServerLevel level, Vec3 centre, Set<BlockPos> broken) {
            LongOpenHashSet seen = new LongOpenHashSet();
            LongOpenHashSet cells = new LongOpenHashSet();
            LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
            List<BlockPos> walls = new ArrayList<>();
            int vents = 0;
            BlockPos start = BlockPos.containing(centre);
            seen.add(start.asLong());
            queue.enqueue(start.asLong());
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            boolean first = true;
            while (!queue.isEmpty()) {
                p.set(queue.dequeueLong());
                if (!level.isInWorldBounds(p) || !Terrain.ready(level, p)) return null;
                BlockState s = level.getBlockState(p);
                // заряд рвётся у самой поверхности, в которую ударил: блок центра — тоже воздух помещения
                if (!first && !passes(level, p, s)) {
                    BlockPos wall = p.immutable();
                    walls.add(wall);
                    if (broken.contains(wall) || vent(s)) vents++;
                    continue;
                }
                first = false;
                cells.add(p.asLong());
                if (cells.size() > ROOM_CELLS) return null;
                for (Direction dir : Direction.values()) {
                    long n = BlockPos.offset(p.asLong(), dir);
                    if (seen.add(n)) queue.enqueue(n);
                }
            }
            return new Room(cells, walls, vents, 0);
        }

        private static boolean passes(ServerLevel level, BlockPos p, BlockState s) {
            return s.isAir() || s.getCollisionShape(level, p).isEmpty();
        }

        /** Проём: стекло и хрупкое (выбивает первым, при 0,8 psi), деревянные двери и люки. */
        private static boolean vent(BlockState s) {
            return s.is(ModTags.SHATTERS) || BlockResponse.of(s).thresholdPsi() <= BlockResponse.FRAGILE_PSI
                    || s.is(BlockTags.WOODEN_DOORS) || s.is(BlockTags.WOODEN_TRAPDOORS);
        }

        boolean contains(BlockPos pos) {
            return cells.contains(pos.asLong());
        }
    }

    /** Волна заряда по телу ({@link BlastBody}): давление открытого поля там, куда она прошла, газы — в помещении. */
    private BlastBody.Wave wave() {
        if (wave == null) wave = new BlastBody.Wave() {
            @Override
            public Vec3 centre() {
                return centre;
            }

            /** Волна открытого поля, а в помещении заряда — не меньше давления газов. */
            @Override
            public double overpressureKpa(Vec3 p) {
                double free = BlastModel.surfaceOverpressureKpa(p.distanceTo(centre), charge);
                return room != null && room.contains(BlockPos.containing(p)) ? Math.max(free, room.kpa()) : free;
            }

            @Override
            public double dynamicPressureKpa(Vec3 p) {
                return BlastModel.dynamicPressureKpa(BlastModel.surfaceOverpressureKpa(p.distanceTo(centre), charge));
            }

            @Override
            public double windSpeed(Vec3 p) {
                return BlastModel.windSpeed(BlastModel.surfaceOverpressureKpa(p.distanceTo(centre), charge));
            }

            @Override
            public double phaseSeconds(Vec3 p) {
                return BlastModel.chemicalPhaseSeconds(p.distanceTo(centre), charge, true);
            }

            @Override
            public double hitKpa() {
                return BlastModel.kpa(BlockResponse.FRAGILE_PSI);
            }

            @Override
            public boolean wind(ServerLevel level, LivingEntity e) {
                return Explosion.getSeenPercent(centre, e) > 0;
            }

            @Override
            public boolean reaches(ServerLevel level, LivingEntity e) {
                return room != null && (room.contains(e.blockPosition()) || room.contains(BlockPos.containing(e.getEyePosition())));
            }

            @Override
            public long id() {
                return waveId;
            }

            @Override
            public net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType> damage() {
                return ModDamageTypes.STRIKE;
            }
        };
        return wave;
    }

    /**
     * Урон по {@link #ENTITIES_PER_UNIT} сущностей волной заряда: живые — {@link BlastBody} (лёгкие, бросок напором за
     * положительную фазу, удар о стену, осколки окна; в помещении — давление газов), остальное (предметы, рамки,
     * вагонетки) разбивает там, где волна ломает лёгкие деревянные постройки ({@code nuke_light}, 4 psi), если оно открыто
     * взрыву. Тела, к которым путь из центра идёт через неготовый чанк, не трогаются (луч видимости грузил бы его).
     */
    private boolean chargeDamage(ServerLevel level) {
        long t = System.nanoTime();
        Explosion e = explosion;
        int end = Math.min(entities.size(), nextEntity + ENTITIES_PER_UNIT);
        if (lightReach < 0) lightReach = reachFor(LIGHT_PSI, charge);
        for (int i = nextEntity; i < end; i++) {
            Entity entity = entities.get(i);
            if (!entity.isAlive() || entity.level() != level || entity == direct || entity.ignoreExplosion(e)) continue;
            if (!Terrain.readyAlong(level, centre, entity.position())) continue;
            if (entity instanceof LivingEntity living) {
                if (living.isSpectator() || living.isPassenger()) continue;
                BlastBody.hit(level, wave(), living, direct, owner, null);
            } else if (entity.distanceToSqr(centre) <= lightReach * lightReach && Explosion.getSeenPercent(centre, entity) > 0) {
                entity.hurt(source, BREAKS_ANYTHING);
            }
        }
        nextEntity = end;
        time(ExplosionStage.DAMAGE, t);
        if (nextEntity < entities.size()) return true;
        entities = List.of();
        crafts(level);
        phase = Phase.BLOCKS;
        return !toBlow.isEmpty();
    }

    /** Докуда волна ломает лёгкие постройки, блоков (−1 — ещё не считали). */
    private double lightReach = -1;
    /** Порог лёгких деревянных построек ({@code nuke_light}), psi: так же волна разбивает незакреплённые вещи. */
    private static final double LIGHT_PSI = 4;
    /** Урон, после которого любая сущность без здоровья (предмет, вагонетка, рамка) разбита. */
    private static final float BREAKS_ANYTHING = 1000;

    /**
     * Аппараты Create Aeronautics в охвате: их блоки лежат в плотах Sable, и лучи по миру их не видят. Путь — как у
     * ядерной волны ({@code BlastFront}): ванильный взрыв в центре, лучи которого Sable проводит и по блокам аппарата
     * (и толкает его), а какой блок аппарата сломать, решает тот же закон давления и импульса по расстоянию до центра в
     * мире ({@link CraftCharge}); сущности он не трогает. Сила — чтобы ванильный луч в воздухе дошёл до {@link #breakReach}
     * при наименьшем множителе (0,7): дальше закон ничего не ломает. Толчок аппарата — Sable по ванильным лучам, не по
     * физике.
     */
    private void crafts(ServerLevel level) {
        float craftPower = craftPower(level, centre, charge);
        if (craftPower <= 0) return;
        Vec3 c = centre;
        double tnt = charge;
        boolean destroy = blocks;
        Warheads.whenReady(level, c, craftReach(craftPower), l -> {
            // пока идёт этот взрыв, обработчики событий видят заряд ({@link #chargeOf}), а список сущностей у его
            // {@code Detonate} пуст ({@link #onDetonate}): урона и толчка от него нет, лучей видимости к телам — тоже
            double outer = craftCharge;
            craftCharge = tnt;
            try {
                l.explode(null, ModDamageTypes.source(l, ModDamageTypes.STRIKE, null, null), new CraftCharge(l, c, tnt),
                        c.x, c.y, c.z, craftPower, false, destroy ? Level.ExplosionInteraction.BLOCK : Level.ExplosionInteraction.NONE,
                        ModParticles.NONE.get(), ModParticles.NONE.get(), ModSounds.SILENT);
            } finally {
                craftCharge = outer;
            }
        });
    }

    /** Заряд взрыва по аппаратам, который сейчас идёт в потоке сервера ({@link #crafts}); 0 — нет такого. */
    private static double craftCharge;

    /**
     * Сила ванильного взрыва по аппаратам ({@link #crafts}): луч в воздухе при множителе 0,7 уходит на 0,7 × 1,33 силы —
     * до самой дальней точки аппаратов в охвате волны, но не дальше охвата ({@link #breakReach}: дальше закон ничего не
     * ломает); 0 — аппаратов нет. Сила по всему охвату читала бы мир на ~1,9 охвата и стоила бы пика тика.
     */
    static float craftPower(Level level, Vec3 centre, double tntKg) {
        double reach = breakReach(tntKg), far = 0;
        if (!SubLevels.mayHaveCraftNear(level, centre, reach)) return 0;
        for (var sub : SubLevels.near(level, centre, reach)) {
            AABB box = sub.boundingBox().toMojang();
            double dx = Math.max(Math.abs(box.minX - centre.x), Math.abs(box.maxX - centre.x));
            double dy = Math.max(Math.abs(box.minY - centre.y), Math.abs(box.maxY - centre.y));
            double dz = Math.max(Math.abs(box.minZ - centre.z), Math.abs(box.maxZ - centre.z));
            far = Math.max(far, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return far <= 0 ? 0 : (float) Math.min(60, Math.max(1, Math.min(reach, far) / (0.7 * 1.33)));
    }

    /** Докуда читает мир взрыв по аппаратам силы {@code power}: луч при множителе 1,3 — 1,73 силы, с запасом в блок. */
    static double craftReach(float power) {
        return power * 1.73 + 1;
    }

    /** Взрыв по аппаратам: только блоки плотов, по закону давления и импульса в их точке мира; без урона и толчка сущностей. */
    private static final class CraftCharge extends ExplosionDamageCalculator {
        private final Level level;
        private final Vec3 centre;
        private final double tnt;

        CraftCharge(Level level, Vec3 centre, double tnt) {
            this.level = level;
            this.centre = centre;
            this.tnt = tnt;
        }

        @Override
        public boolean shouldBlockExplode(Explosion explosion, BlockGetter reader, BlockPos pos, BlockState state, float power) {
            if (state.isAir() || !SubLevels.isInPlot(level, pos.getCenter())) return false;
            double r = Math.max(0.25, SubLevels.toWorld(level, pos.getCenter()).distanceTo(centre));
            return BlockResponse.of(state).breaksAt(BlastModel.psi(BlastModel.surfaceOverpressureKpa(r, tnt)),
                    BlastModel.chemicalImpulse(r, tnt, true), Mth.murmurHash3Mixer(Long.hashCode(pos.asLong())));
        }

        @Override
        public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
            return false;
        }

        @Override
        public float getKnockbackMultiplier(Entity entity) {
            return 0;
        }
    }

    /** Заряд взрывов по заряду, пока они идут (обработчики событий: {@link #chargeOf}). */
    private static final java.util.Map<Explosion, Double> CHARGES = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Заряд взрыва мода по физике, кг ТНТ; 0 — ванильный или чужой взрыв. */
    static double chargeOf(Explosion e) {
        Double c = CHARGES.get(e);
        return c != null ? c : craftCharge;
    }

    /** Заряд не отменён ({@code ExplosionEvent.Start}): работа удара после главного взрыва идёт, только если он был. */
    boolean happened() {
        return !cancelled;
    }

    private boolean cancelled;

    /** Выбранное лучами — во взрыв, сбор сущностей, {@code ExplosionEvent.Detonate}. */
    private boolean detonate(ServerLevel level) {
        Explosion e = explosion;
        e.getToBlow().addAll(chosen);
        chosen.clear();
        strength = new float[0];
        long t = System.nanoTime();
        // взрыв по заряду бьёт тела, пока давление выше порога осколков стекла; ванильный — в 2 × силы
        float f2 = charge > 0 ? (float) hitReach : power * 2.0F;
        Vec3 c = centre;
        int x0 = Mth.floor(c.x - f2 - 1.0), x1 = Mth.floor(c.x + f2 + 1.0);
        int y0 = Mth.floor(c.y - f2 - 1.0), y1 = Mth.floor(c.y + f2 + 1.0);
        int z0 = Mth.floor(c.z - f2 - 1.0), z1 = Mth.floor(c.z + f2 + 1.0);
        entities = new ArrayList<>(level.getEntities((Entity) null, new AABB(x0, y0, z0, x1, y1, z1)));
        time(ExplosionStage.ENTITIES, t);
        t = System.nanoTime();
        EventHooks.onExplosionDetonate(level, e, entities, f2);
        time(ExplosionStage.DETONATE, t);
        phase = Phase.SPLIT;
        return true;
    }

    /**
     * Раздел выбранного: плот аппарата — вопрос к Sable раз на чанк, его блоки (кроме воздуха без огня) ждут конца урона
     * ({@link #blowCrafts}) вместе с тем, чей плот был в чанке; позиции мира ждут снимка ({@link #split}).
     */
    private void partition(ServerLevel level) {
        long t = System.nanoTime();
        Explosion e = explosion;
        List<BlockPos> plot = new ArrayList<>();
        List<Block> plotBlocks = new ArrayList<>();
        List<BlockPos> world = new ArrayList<>();
        // без разрушений блоков и без огня порциям нечего делать
        if (e.interactsWithBlocks() || fire) {
            Long2BooleanOpenHashMap plotChunk = new Long2BooleanOpenHashMap();
            for (BlockPos p : e.getToBlow()) {
                long chunk = ChunkPos.asLong(SectionPos.blockToSectionCoord(p.getX()), SectionPos.blockToSectionCoord(p.getZ()));
                boolean inPlot = plotChunk.computeIfAbsent(chunk, c -> SubLevels.inPlotGrid(level, new ChunkPos(c)));
                if (!inPlot) {
                    world.add(p);
                    continue;
                }
                BlockState s = level.getBlockState(p);
                if (!fire && s.isAir()) continue;
                plot.add(p);
                plotBlocks.add(s.getBlock());
                if (!craftOwners.containsKey(chunk)) craftOwners.put(chunk, SubLevels.containingId(level, new ChunkPos(chunk)));
            }
        }
        e.clearToBlow();
        picked = world;
        crafts = plot;
        craftBlocks = plotBlocks;
        partitioned = true;
        time(ExplosionStage.SPLIT, t);
    }

    /**
     * Блоки аппаратов — одним разом после урона, как у ванили ({@code explode()} бьёт сущности, {@code finalizeExplosion}
     * потом снимает блоки): урон и отбрасывание видят корпус целым (луч видимости {@code getSeenPercent} Sable ведёт и
     * через блоки аппаратов). С раздела прошло несколько тиков, и аппарат мог расколоться, а его плот уйти другому:
     * блок, сменившийся с раздела, и чанк, чей плот теперь не у того аппарата, не трогаются.
     */
    private void blowCrafts(ServerLevel level) {
        if (crafts.isEmpty()) return;
        long t = System.nanoTime();
        List<BlockPos> part = new ArrayList<>(crafts.size());
        Long2BooleanOpenHashMap same = new Long2BooleanOpenHashMap();
        for (int i = 0; i < crafts.size(); i++) {
            BlockPos p = crafts.get(i);
            long chunk = ChunkPos.asLong(SectionPos.blockToSectionCoord(p.getX()), SectionPos.blockToSectionCoord(p.getZ()));
            boolean owner = same.computeIfAbsent(chunk, c -> Objects.equals(craftOwners.get(c), SubLevels.containingId(level, new ChunkPos(c))));
            if (owner && level.getBlockState(p).is(craftBlocks.get(i))) part.add(p);
        }
        crafts = List.of();
        craftBlocks = List.of();
        craftOwners.clear();
        blow(level, part);
        time(ExplosionStage.CRAFTS, t);
    }

    /**
     * Разбор выбранного (после раздела): воздух без огня отбрасывается (у силы 20 выбранных — десятки тысяч, в основном
     * воздух), мир — от центра наружу по заранее посчитанному расстоянию. Блок для сверки порции ({@link #portion}) —
     * тот, что стоит на месте в момент разбора (после всех лучей), а не в момент луча.
     */
    private boolean split(ServerLevel level) {
        if (!partitioned) partition(level);
        long t = System.nanoTime();
        List<Target> world = new ArrayList<>(picked.size());
        for (BlockPos p : picked) {
            BlockState s = level.getBlockState(p);
            if (fire || !s.isAir()) world.add(new Target(p.distToCenterSqr(at), p, s.getBlock()));
        }
        picked = List.of();
        time(ExplosionStage.SPLIT, t);
        t = System.nanoTime();
        world.sort(Comparator.comparingDouble(Target::distSqr));
        List<BlockPos> kept = new ArrayList<>(world.size());
        List<Block> seen = new ArrayList<>(world.size());
        for (Target w : world) {
            kept.add(w.pos());
            seen.add(w.block());
        }
        toBlow = kept;
        blocksAtRays = seen;
        time(ExplosionStage.SNAPSHOT, t);
        phase = vanillaDamage ? Phase.BLOCKS : Phase.DAMAGE;
        return phase == Phase.DAMAGE || !toBlow.isEmpty();
    }

    /** Урон и отбрасывание по {@link #ENTITIES_PER_UNIT} сущностей — копия ванильного цикла {@code Explosion.explode}. */
    private boolean damage(ServerLevel level) {
        long t = System.nanoTime();
        Explosion e = explosion;
        float f2 = power * 2.0F;
        int end = Math.min(entities.size(), nextEntity + ENTITIES_PER_UNIT);
        for (int i = nextEntity; i < end; i++) {
            Entity entity = entities.get(i);
            // между единицами сущность могла умереть или уйти в другое измерение
            if (!entity.isAlive() || entity.level() != level || entity.ignoreExplosion(e)) continue;
            double d11 = Math.sqrt(entity.distanceToSqr(at)) / (double) f2;
            if (d11 > 1.0) continue;
            double d5 = entity.getX() - at.x;
            double d7 = (entity instanceof PrimedTnt ? entity.getY() : entity.getEyeY()) - at.y;
            double d9 = entity.getZ() - at.z;
            double d12 = Math.sqrt(d5 * d5 + d7 * d7 + d9 * d9);
            if (d12 == 0.0) continue;
            d5 /= d12;
            d7 /= d12;
            d9 /= d12;
            if (damage.shouldDamageEntity(e, entity)) entity.hurt(source, damage.getEntityDamageAmount(e, entity));
            double d13 = (1.0 - d11) * (double) Explosion.getSeenPercent(at, entity) * (double) damage.getKnockbackMultiplier(entity);
            double d10 = entity instanceof LivingEntity living ? d13 * (1.0 - living.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE)) : d13;
            Vec3 push = EventHooks.getExplosionKnockback(level, e, entity, new Vec3(d5 * d10, d7 * d10, d9 * d10));
            entity.setDeltaMovement(entity.getDeltaMovement().add(push));
            if (entity instanceof Player player && !player.isSpectator() && (!player.isCreative() || !player.getAbilities().flying)) {
                e.getHitPlayers().put(player, push);
                if (player instanceof ServerPlayer sp && sp.distanceToSqr(at.x, at.y, at.z) < 4096.0) tell(sp, push);
            }
            // как у ванили (источник взрыва — null): игрок помнит толчок — урон от падения после него, булава
            entity.onExplosionHit(null);
        }
        nextEntity = end;
        time(ExplosionStage.DAMAGE, t);
        if (nextEntity < entities.size()) return true;
        // ваниль шлёт пакет всем игрокам в 64 блоках, и без отбрасывания
        for (ServerPlayer p : level.players()) {
            if (!told.contains(p) && p.distanceToSqr(at.x, at.y, at.z) < 4096.0) tell(p, null);
        }
        entities = List.of();
        blowCrafts(level);
        phase = Phase.BLOCKS;
        return !toBlow.isEmpty();
    }

    /**
     * {@code ExplosionEvent.Detonate} (приоритет {@code LOWEST}: все обработчики уже видели и правили список): у взрыва,
     * чей ванильный {@code explode()} ведёт мод, — список сущностей забирается в урон мода, а сам список событию
     * остаётся пустым, и ванильный цикл урона после события ни по кому не идёт. Список у события изменяемый — так
     * NeoForge и задумал {@code getAffectedEntities}. Обработчики того же приоритета, зарегистрированные позже мода,
     * видят у этого взрыва пустой список — и только у него. Другие взрывы (и взрыв, начатый обработчиком внутри) —
     * другие объекты, их не трогаем.
     */
    static void onDetonate(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate event) {
        if (craftCharge > 0 && CHARGES.get(event.getExplosion()) == null) {
            event.getAffectedEntities().clear();
            return;
        }
        if (driving == null || event.getExplosion() != driving || handed != null) return;
        List<Entity> list = event.getAffectedEntities();
        handed = new ArrayList<>(list);
        list.clear();
    }

    /** Ванильный пакет взрыва игроку: его отбрасывание, без блоков (блоки клиенты видят по мере снятия). */
    private void tell(ServerPlayer p, @Nullable Vec3 push) {
        Explosion e = explosion;
        told.add(p);
        p.connection.send(new ClientboundExplodePacket(at.x, at.y, at.z, power, List.of(), push,
                e.getBlockInteraction(), e.getSmallExplosionParticles(), e.getLargeExplosionParticles(), e.getExplosionSound()));
    }

    /** Сколько блоков сняла последняя порция (блок, сменившийся с лучей, не в счёт) — для «blocks removed». */
    private int blown;

    /**
     * Порция блоков мира по времени: блоки идут, пока порция короче {@link #portionNanos}, хоть один — всегда. Порядок —
     * от центра наружу пачками по {@link #BATCH}, внутри пачки перемешан (как весь список у ванили); начатая пачка
     * доходит в следующей порции. Огонь — по снятому этой порцией. Блок дольше {@link #SLOW_BLOCK_NANOS} — в лог
     * ({@link BlastArea#slowBlock}).
     */
    private boolean portion(ServerLevel level) {
        Explosion e = explosion;
        List<BlockPos> hit = new ArrayList<>();
        LongSupplier clock = this.clock != null ? this.clock : WorkScheduler.impactClock(level.getServer()).sampler();
        long t0 = clock.getAsLong(), last = t0;
        while (!batch.isEmpty() || next < toBlow.size()) {
            if (batch.isEmpty()) {
                int end = Math.min(toBlow.size(), next + BATCH);
                for (int i = next; i < end; i++) batch.add(i);
                next = end;
                Util.shuffle(batch, level.random);
            }
            int i = batch.removeInt(batch.size() - 1);
            BlockPos p = toBlow.get(i);
            if (!Terrain.ready(level, p)) continue;
            BlockState s = level.getBlockState(p);
            if (!s.is(blocksAtRays.get(i))) continue;
            if (e.interactsWithBlocks()) {
                PoiRecords.recordWithNeighbours(level, p, s);
                s.onExplosionHit(level, p, e, (stack, at) -> addOrAppend(stack, at));
            }
            hit.add(p);
            long now = clock.getAsLong();
            if (now - last > SLOW_BLOCK_NANOS) area.slowBlock(level, s, p, now - last);
            last = now;
            if (now - t0 >= portionNanos) break;
        }
        blown = hit.size();
        portions++;
        largestPortion = Math.max(largestPortion, blown);
        if (fire) burn(level, hit);
        return !batch.isEmpty() || next < toBlow.size();
    }

    /** Позиция мира, выбранная лучами: расстояние до центра (ключ порядка) и блок в момент лучей. */
    private record Target(double distSqr, BlockPos pos, Block block) {}

    /** Шаги {@code Explosion.finalizeExplosion} на части списка: снятие блоков (выпадение — в общий список), огонь. */
    private void blow(ServerLevel level, List<BlockPos> part) {
        Explosion e = explosion;
        if (part.isEmpty() || e == null) return;
        if (e.interactsWithBlocks()) {
            Util.shuffle(part, level.random);
            for (BlockPos p : part) {
                BlockState s = level.getBlockState(p);
                PoiRecords.recordWithNeighbours(level, p, s);
                s.onExplosionHit(level, p, e, (stack, at) -> addOrAppend(stack, at));
            }
        }
        if (fire) burn(level, part);
    }

    /** Огонь {@code Explosion.finalizeExplosion}: треть снятого, где воздух над твёрдым. */
    private void burn(ServerLevel level, List<BlockPos> part) {
        for (BlockPos p : part) {
            if (level.random.nextInt(3) == 0 && level.getBlockState(p).isAir() && level.getBlockState(p.below()).isSolidRender(level, p.below())) {
                level.setBlockAndUpdate(p, BaseFireBlock.getState(level, p));
            }
        }
    }

    /** {@code Explosion.addOrAppendStack}: одинаковые предметы — в одну стопку (до 16), у места первого. */
    private void addOrAppend(ItemStack stack, BlockPos pos) {
        for (int i = 0; i < drops.size(); i++) {
            Pair<ItemStack, BlockPos> pair = drops.get(i);
            ItemStack have = pair.getFirst();
            if (ItemEntity.areMergable(have, stack)) {
                drops.set(i, Pair.of(ItemEntity.merge(have, stack, 16), pair.getSecond()));
                if (stack.isEmpty()) return;
            }
        }
        drops.add(Pair.of(stack, pos));
    }

    private void popDrops(ServerLevel level) {
        for (Pair<ItemStack, BlockPos> pair : drops) {
            if (Terrain.ready(level, pair.getSecond())) Block.popResource(level, pair.getSecond(), pair.getFirst());
        }
        drops.clear();
    }

    @Override
    public void end(ServerLevel level) {
        // отменён или упал посреди работы: выпавшее уже снятыми блоками — всё равно выпадает
        popDrops(level);
        done = true;
        area.recordCounts(counts);
        area.release(level);
    }

    int slowBlocks() {
        return area.slowBlocks();
    }

    @Override
    public String describe() {
        if (charge > 0) return String.format(Locale.ROOT, "Blast of %.1f kg TNT at %d %d %d", charge, Mth.floor(at.x), Mth.floor(at.y), Mth.floor(at.z));
        return String.format(Locale.ROOT, "Explosion of power %.0f at %d %d %d", power, Mth.floor(at.x), Mth.floor(at.y), Mth.floor(at.z));
    }
}
