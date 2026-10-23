package ua.zentix.incoming.warhead;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.CraterModel;
import ua.zentix.incoming.nuclear.model.FuelFireball;
import ua.zentix.incoming.nuclear.model.HearingModel;
import ua.zentix.incoming.nuclear.world.ColumnScar;
import ua.zentix.incoming.registry.ModDamageTypes;
import ua.zentix.incoming.registry.ModDataMaps;
import ua.zentix.incoming.registry.ModParticles;
import ua.zentix.incoming.registry.ModTags;
import ua.zentix.incoming.strike.ImpactCost;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.Timeline;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.nuclear.world.BlockResponse;
import ua.zentix.incoming.nuclear.world.CraterJob;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.work.UnitQueue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Боевые части. Разрушения и урон — на сервере по таймлайну: основной взрыв — ванильный {@link Explosion} (его
 * уважают приваты, Sable сам толкает им аппараты), вторичные подрывы, стёкла, обломки, ударная волна на скорости звука.
 * Всё, что видно и слышно, клиент строит сам по пакету {@link S2C.Blast}: частицы, вспышку, звук с задержкой по
 * расстоянию, тряску.
 */
public final class Warheads {
    /** Фронт звука и ударной волны: 343 м/с = 17.15 блока за тик. */
    public static final double FRONT_SPEED = BlastModel.SOUND_SPEED / 20;

    private Warheads() {}

    // ---------------------------------------------------------------- вход

    public static void detonate(ServerLevel level, WeaponType weapon, Vec3 pos, @Nullable Entity projectile, @Nullable UUID owner) {
        long t0 = System.nanoTime();
        StrikeWorld world = StrikeWorld.get(level);
        world.add(new SurfaceBlast(level, weapon, pos, projectile, owner));
        world.impactCost().step(System.nanoTime() - t0);
    }

    /**
     * Бетонобойная бомба вошла в грунт со скоростью {@code speed} м/с: взрыва нет (взрыватель замедленный) — удар корпуса
     * по земле: клиентам — пыль выброса из входа и звук удара, тем, кто рядом, — толчок земли по энергии удара.
     */
    public static void bunkerEntry(ServerLevel level, Vec3 point, double speed, @Nullable Entity bomb, @Nullable UUID owner) {
        long t0 = System.nanoTime();
        GroundMaterial mat = GroundMaterial.sample(level, BlockPos.containing(point));
        double hit = BunkerImpact.equivalentTnt(WeaponSpec.BUNKER.payload().article().dryMass(), speed);
        double reach = Math.max(IncomingConfig.SERVER.farRange.get(), Math.min(IncomingConfig.FAR_RANGE_MAX,
                HearingModel.audibleRangeM(BunkerImpact.airTnt(hit))));
        PacketDistributor.sendToPlayersNear(level, null, point.x, point.y, point.z, reach, new S2C.BunkerImpact(point, mat.ordinal(), (float) speed));
        quake(level, point, hit);
        StrikeWorld.get(level).impactCost().step(System.nanoTime() - t0);
    }

    /**
     * Толчок земли от удара или взрыва в грунте ({@code tntKg} кг ТНТ, что ушло в грунт): сейсмическая волна быстрее звука
     * — трясёт сразу. Сила и длительность — по приведённому расстоянию Z = R/W^⅓ (оценка, удобство игрока, не урон): до
     * Z 3 — 4 с, до 7,5 — 3 с, до 24 — 2 с (как было у 2,4 т в 40, 100 и 320 блоках).
     */
    static void quake(ServerLevel level, Vec3 at, double tntKg) {
        double cube = Math.cbrt(Math.max(tntKg, 1e-3));
        for (ServerPlayer p : level.players()) {
            double z = p.position().distanceTo(at) / cube;
            if (z <= QUAKE_FELT) PacketDistributor.sendToPlayer(p, new S2C.Quake(z <= 3 ? 80 : z <= 7.5 ? 60 : 40, true));
        }
    }

    /** Докуда толчок земли ощутим, Z = R/W^⅓ (оценка). */
    static final double QUAKE_FELT = 24;

    /** Подрыв бетонобойной бомбы под землёй. */
    public static void bunker(ServerLevel level, Vec3 pos, Vec3 entry, @Nullable Entity bomb, @Nullable UUID owner) {
        long t0 = System.nanoTime();
        StrikeWorld world = StrikeWorld.get(level);
        world.add(new BunkerBlast(level, pos, entry, bomb, owner));
        world.impactCost().step(System.nanoTime() - t0);
    }

    // ---------------------------------------------------------------- общие средства

    /**
     * Докуда читает мир ванильный взрыв силы {@code power}: лучи по блокам гаснут не дальше 1.3·power / 0.225 шагов
     * по 0.3 блока (≈ 1.73·power), урон ищет сущности в 2·power и пускает к каждой луч видимости.
     */
    public static double reach(float power) {
        return power * 2 + 1;
    }

    /**
     * Сделать то, что читает мир в {@code reach} блоков от {@code centre} (ванильный взрыв), когда там всё готово:
     * сразу, если готово уже сейчас, иначе — когда тикет {@link BlastArea} догрузит район в фоне. Чтение неготового
     * чанка грузило бы его прямо в тике: вторичный подрыв залпа у края загруженного мира вставал на 1,2 с.
     */
    public static void whenReady(ServerLevel level, Vec3 centre, double reach, Consumer<ServerLevel> action) {
        if (Terrain.readyAround(level, centre, reach)) {
            action.accept(level);
        } else {
            StrikeWorld.get(level).add(new Deferred(BlastArea.hold(level, centre, reach), action));
        }
    }

    /**
     * Ванильный взрыв без его звука и частиц (звук с задержкой и картинку взрыва даёт клиент): разрушения по правилам TNT,
     * урон с нашим типом («жертва авиаудара»), приваты и Sable работают как обычно. Делается единицами в очереди
     * попаданий ({@link StagedExplosion}) под общим бюджетом тика, только по готовым чанкам.
     *
     * @param area  район таймлайна, который накрывает и этот взрыв, или null — взрыв возьмёт свой
     * @param after взрывы, которые должны кончиться раньше (главный взрыв удара); блоки снимаются, когда взрывы того же
     *              района и тика выбрали лучами и побили сущности ({@link BlastArea#peersPicking})
     */
    static StagedExplosion explode(ServerLevel level, @Nullable BlastArea area, List<StagedExplosion> after, Vec3 at, float power,
                                   boolean fire, @Nullable Entity direct, @Nullable Entity owner, @Nullable ExplosionDamageCalculator calculator) {
        boolean blocks = IncomingConfig.SERVER.blockDamage.get();
        boolean burns = fire && IncomingConfig.SERVER.fire.get();
        BlastArea held = area != null ? area.retain() : BlastArea.hold(level, at, reach(power));
        StagedExplosion job = new StagedExplosion(held, after, at, power, burns, blocks, direct, owner, calculator);
        held.queued(job, level.getGameTime());
        StrikeWorld.get(level).impacts().add(level, job);
        return job;
    }

    /**
     * Проверки: взрыв без разрушений (выборку лучей видно в {@code ExplosionEvent.Detonate}) с сидом {@code seed}
     * у {@code level.random} перед лучами — ванильным {@code explode()} или своим циклом по {@code raysPerUnit} лучей.
     */
    public static Probe testRays(ServerLevel level, Vec3 at, float power, @Nullable ExplosionDamageCalculator calculator, long seed,
                                 boolean vanilla, int raysPerUnit) {
        StagedExplosion job = new StagedExplosion(BlastArea.hold(level, at, reach(power)), List.of(), at, power, false, false, null, null, calculator);
        job.seed = seed;
        job.forceVanilla = vanilla;
        job.raysPerUnit = raysPerUnit;
        StrikeWorld.get(level).impacts().add(level, job);
        return new Probe(job);
    }

    /**
     * Проверки: взрыв (с разрушениями — {@code blocks}), ванильным {@code explode()} ({@code vanilla}, как у аппарата)
     * или своим циклом, порции блоков — на часах {@code clock} со сроком {@code portionNanos}.
     */
    public static Probe testBlast(ServerLevel level, Vec3 at, float power, boolean blocks, boolean vanilla, LongSupplier clock,
                                  long portionNanos) {
        StagedExplosion job = new StagedExplosion(BlastArea.hold(level, at, reach(power)), List.of(), at, power, false, blocks, null, null, null);
        job.forceVanilla = vanilla;
        job.clock = clock;
        job.portionNanos = portionNanos;
        StrikeWorld.get(level).impacts().add(level, job);
        return new Probe(job);
    }

    /** Проверки: что сделал взрыв {@link #testBlast} или {@link #testRays}. */
    public static final class Probe {
        private final StagedExplosion job;

        private Probe(StagedExplosion job) {
            this.job = job;
        }

        public boolean done() {
            return job.done();
        }

        /** Взрыв шёл ванильным {@code explode()} (аппарат рядом или так велели). */
        public boolean vanilla() {
            return job.vanilla();
        }

        /** Ещё выбирает лучами или бьёт сущности (блоки мира не снимает). */
        public boolean picking() {
            return job.picking();
        }

        /** Ванильный путь отдал урон моду: сущности из {@code Detonate} били свои единицы, а не цикл {@code explode()}. */
        public boolean handedDamage() {
            return job.handedDamage();
        }

        /** Порций блоков мира. */
        public int portions() {
            return job.portions;
        }

        /** Самая большая порция, блоков. */
        public int largestPortion() {
            return job.largestPortion;
        }

        /** Блоков дольше {@code SLOW_BLOCK_NANOS} за взрыв. */
        public int slowBlocks() {
            return job.slowBlocks();
        }

        /** Блоков, снесённых пробоем преграды ({@link SlabBreach}). */
        public int breached() {
            return job.breached;
        }
    }

    /** {@code ExplosionEvent.Detonate}, приоритет {@code LOWEST}: урон ванильного взрыва у аппарата — в единицы мода. */
    public static void onDetonate(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate event) {
        StagedExplosion.onDetonate(event);
    }

    /** Кончились ли все взрывы {@code after}. */
    static boolean pending(List<StagedExplosion> after) {
        for (StagedExplosion e : after) if (!e.done()) return true;
        return false;
    }

    /**
     * Работа попадания одной единицей в очереди попаданий: после взрывов {@code after} (главного взрыва удара), когда
     * район {@code area} готов.
     *
     * @param work возвращает, сколько сделано (блоков, обломков) — для замера
     */
    static void unit(ServerLevel level, BlastArea area, List<StagedExplosion> after, ImpactCost.Kind kind, String what,
                     ToIntFunction<ServerLevel> work) {
        StrikeWorld.get(level).impacts().add(level, new Unit(area.retain(), after, kind, what, work));
    }

    private record Unit(BlastArea area, List<StagedExplosion> after, ImpactCost.Kind kind, String what,
                        ToIntFunction<ServerLevel> work) implements UnitQueue.Job {
        @Override
        public boolean ready(ServerLevel level) {
            return area.ready(level);
        }

        @Override
        public boolean blocked() {
            return pending(after);
        }

        @Override
        public int unitKind() {
            return kind.ordinal();
        }

        @Override
        public boolean step(ServerLevel level) {
            long t0 = System.nanoTime();
            int n = work.applyAsInt(level);
            area.record(level, kind, System.nanoTime() - t0, n);
            return false;
        }

        @Override
        public void end(ServerLevel level) {
            area.release(level);
        }

        @Override
        public String describe() {
            Vec3 c = area.centre();
            return what + " at " + Mth.floor(c.x) + " " + Mth.floor(c.y) + " " + Mth.floor(c.z);
        }
    }

    /**
     * Сколько отложенный взрыв ({@link #whenReady}) ждёт своего района: тикет грузит район за секунды; если за 5 минут
     * не вышло (мир не грузится), взрыв отменяется.
     */
    public static final int DEFERRED_GIVE_UP_TICKS = 6000;

    /** Взрыв, который ждёт готовности своего района ({@link #whenReady}). */
    private static final class Deferred implements Timeline {
        private final BlastArea area;
        private final Consumer<ServerLevel> action;
        private int waited;

        Deferred(BlastArea area, Consumer<ServerLevel> action) {
            this.area = area;
            this.action = action;
        }

        @Override
        public boolean tick(ServerLevel level) {
            if (area.ready(level)) {
                action.accept(level);
                return false;
            }
            if (++waited < DEFERRED_GIVE_UP_TICKS) return true;
            Vec3 c = area.centre();
            Incoming.LOG.warn("Blast at {} {} {} cancelled: area did not load within {} ticks",
                    Mth.floor(c.x), Mth.floor(c.y), Mth.floor(c.z), DEFERRED_GIVE_UP_TICKS);
            return false;
        }

        @Override
        public void end(ServerLevel level) {
            area.release(level);
        }
    }

    /** Частицы, которые видно издалека (как «force» в команде particle). */
    static void forced(ServerLevel level, ParticleOptions options, Vec3 p, int count, double dx, double dy, double dz, double speed, double range) {
        for (ServerPlayer pl : level.players()) {
            if (pl.distanceToSqr(p) <= range * range) {
                level.sendParticles(pl, options, true, p.x, p.y, p.z, count, dx, dy, dz, speed);
            }
        }
    }

    /**
     * Выбить всё из тега {@code tag} в коробке вокруг {@code center} сразу, без очереди ({@link Shatter}: только готовые
     * чанки, у края загрузки — без обновлений соседей). Проверки.
     *
     * @return сколько блоков выбито
     */
    public static int shatter(ServerLevel level, Vec3 center, int radius, int below, int above, TagKey<Block> tag) {
        return Shatter.now(level, center, Shatter.Box.around(center, radius, below, above, tag));
    }

    /**
     * Выбить стёкла волной наземного взрыва заряда {@code tntKg} кг ТНТ сразу, без очереди ({@link Shatter.Wave}).
     * Проверки.
     *
     * @return сколько стёкол выбито
     */
    public static int shatterGlass(ServerLevel level, Vec3 center, double tntKg) {
        return Shatter.now(level, center, Shatter.Wave.of(center, tntKg));
    }

    /** Докуда волна заряда {@code tntKg} кг ТНТ может выбить стекло, блоков. */
    public static double glassReach(double tntKg) {
        return Shatter.Wave.of(Vec3.ZERO, tntKg).radius();
    }

    /**
     * Сколько блоков от изменённого блока должно быть в готовых чанках, когда он меняется без обновлений соседей
     * ({@link #EDGE_FLAGS}): сам блок и его соседи, которые читает Sable, — с запасом в блок.
     */
    static final int EDGE = 2;
    /** Изменение у края загрузки: клиентам, без форм соседей и без их обновлений. */
    static final int EDGE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /**
     * Поджечь землю: огонь на верхнем блоке столба (как упавшие огненные шары). Только в готовых чанках: кольцо огня
     * ракеты — в 7 блоках от точки удара, и у края загрузки оно доставало до чанка, который ещё генерируется, — чтение
     * блока из него ({@code igniteAt}) грузило бы его прямо в тике, а у чанка, который уже грузится, высота и блок
     * ждали бы загрузки в {@code managedBlock}.
     */
    public static void igniteGround(ServerLevel level, double x, double z) {
        if (!IncomingConfig.SERVER.fire.get() || !Terrain.readyAround(level, new Vec3(x, 0, z), EDGE)) return;
        BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, BlockPos.containing(x, 0, z));
        igniteAt(level, top);
    }

    /**
     * Огонь в блоке, если там воздух и огонь держится. Только когда готовы чанк блока и все его соседи: {@code setBlock}
     * обновляет соседей (и цепочкой — их соседей: дверь рядом проверяет сигнал редстоуна), а Sable читает соседние
     * блоки ({@code SableCommonEvents.handleBlockChange}) — у края загрузки это грузило бы чанк синхронно.
     */
    static boolean igniteAt(ServerLevel level, BlockPos pos) {
        if (!IncomingConfig.SERVER.fire.get() || !Terrain.neighbourhoodReady(level, pos.getX() >> 4, pos.getZ() >> 4)) return false;
        return level.getBlockState(pos).isAir() && igniteIn(level, pos);
    }

    /**
     * Огонь на месте {@code pos} вместо того, что там (воздух или растение без формы, которое горит на своём месте, как
     * его сжигает огонь ванили), если огонь держится; те же условия готовности, что у {@link #igniteAt}. Поставлен ли.
     */
    static boolean igniteIn(ServerLevel level, BlockPos pos) {
        if (!IncomingConfig.SERVER.fire.get() || !Terrain.neighbourhoodReady(level, pos.getX() >> 4, pos.getZ() >> 4)) return false;
        BlockState fire = BaseFireBlock.getState(level, pos);
        if (!fire.canSurvive(level, pos)) return false;
        level.setBlock(pos, fire, 11);
        return true;
    }

    // ================================================================ шахед, ракета, «Град», «Ланцет»

    /**
     * Природная земля под точкой удара, м (первый воздух над грунтом, ниже построек и деревьев, как {@link CraterJob#naturalSurface}):
     * клиент ставит пыль волны на неё, а не на крышу или стену, куда пришёлся удар (взрыв на крыше — разрыв на её
     * высоте над улицей). В неготовом чанке — сама точка удара: чтение высоты грузило бы его или ждало загрузки. Вниз —
     * до дна мира (столбец одного готового чанка: башня выше сотни блоков; аппарата над рельефом в столбце мира нет).
     * Вода — тоже земля (гладь). Земля выше точки (подвал, удар в склон) — тоже точка: взрыв не под землёй; грунта в
     * столбце нет (пустота) — точка.
     * ⚠ Дом из природного камня (песчаник, камень) считается грунтом — его крыша будет землёй.
     */
    public static double groundUnder(ServerLevel level, Vec3 pos) {
        BlockPos at = BlockPos.containing(pos);
        if (!Terrain.ready(level, at)) return pos.y;
        int top = Math.max(Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()), at.getY() + 1);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(at.getX(), 0, at.getZ());
        for (int y = top - 1; y >= level.getMinBuildHeight(); y--) {
            BlockState s = level.getBlockState(m.setY(y));
            // вода — земля взрыва: над ней разрыв на воде, а не над дном (пыли над водой нет, брызги — у WaterBlast)
            // (сама жидкость, а не блок с водой внутри: ступень или плита в доме — не гладь)
            if (s.getBlock() instanceof LiquidBlock || BlockResponse.of(s).kind() == BlockResponse.Kind.GROUND) return Math.min(pos.y, y + 1);
        }
        return pos.y;
    }

    /**
     * Взрыв заряда {@code tntKg} кг ТНТ по физике ({@link StagedExplosion}: волна Кинни–Грэхема, кривая давление–импульс
     * блоков, газы в помещении, тела — {@code BlastBody}) единицами в очереди попаданий.
     */
    static StagedExplosion charge(ServerLevel level, BlastArea area, Vec3 at, double tntKg, SlabBreach.Placement placement,
                                  @Nullable Entity direct, @Nullable Entity owner) {
        boolean blocks = IncomingConfig.SERVER.blockDamage.get();
        StagedExplosion job = new StagedExplosion(area.retain(), List.of(), at, tntKg, placement, blocks, direct, owner);
        area.queued(job, level.getGameTime());
        StrikeWorld.get(level).impacts().add(level, job);
        return job;
    }

    /** Проверки: взрыв заряда {@code tntKg} кг ТНТ по физике, со своим районом. */
    public static Probe testCharge(ServerLevel level, Vec3 at, double tntKg) {
        BlastArea area = BlastArea.hold(level, at, chargeReach(tntKg, 0));
        Probe probe = new Probe(charge(level, area, at, tntKg, SlabBreach.Placement.NONE, null, null));
        area.release(level);
        return probe;
    }

    /**
     * Проверки: заряд {@code tntKg} кг ТНТ в толще (как у бомбы, вошедшей в грунт) — волна в пустотах и пробой к ним
     * ({@link SlabBreach}); район — до {@code reach} блоков (как у бомбы, не весь охват волны над землёй).
     */
    public static Probe testBuriedCharge(ServerLevel level, Vec3 at, double tntKg, double reach) {
        return testPlaced(level, at, tntKg, SlabBreach.Placement.BURIED, reach);
    }

    /** Проверки: заряд {@code tntKg} кг ТНТ, приложенный к грани (как боевая часть, ударившая в неё), с районом {@code reach}. */
    public static Probe testSurfaceCharge(ServerLevel level, Vec3 at, double tntKg, double reach) {
        return testPlaced(level, at, tntKg, SlabBreach.Placement.SURFACE, reach);
    }

    private static Probe testPlaced(ServerLevel level, Vec3 at, double tntKg, SlabBreach.Placement placement, double reach) {
        BlastArea area = BlastArea.hold(level, at, reach);
        Probe probe = new Probe(charge(level, area, at, tntKg, placement, null, null));
        area.release(level);
        return probe;
    }

    /** Проверки: докуда волна заряда ломает блок с порогом {@code psi} в открытом поле (нижний край разброса), блоков. */
    public static double breakReach(double psi, double tntKg) {
        return StagedExplosion.reachFor(psi, tntKg);
    }

    /** Заряд взрыва мода по физике, кг ТНТ; 0 — ванильный или чужой взрыв (обработчики {@code ExplosionEvent}). */
    public static double chargeOf(Explosion e) {
        return StagedExplosion.chargeOf(e);
    }

    /**
     * Район удара заряда: лучи волны ({@link StagedExplosion#breakReach}), вал воронки и шар топлива; тела дальше бьются
     * там, где мир загружен.
     */
    static double chargeReach(double tntKg, double fuelKg) {
        double crater = 2 * CraterModel.chargeRadius(tntKg, CraterModel.Soil.WET) + 1;
        return Math.max(StagedExplosion.breakReach(tntKg), Math.max(crater, FuelFireball.diameter(FuelFireball.ballKg(fuelKg)) / 2)) + EDGE;
    }

    /**
     * Ось боевой части у удара: последний шаг снаряда; стоял (ждал свой район) — куда смотрит нос; снаряда нет — вниз.
     */
    static Vec3 axis(@Nullable Entity projectile) {
        if (projectile == null) return S2C.Blast.DOWN;
        Vec3 v = projectile.getDeltaMovement();
        if (v.lengthSqr() < 1e-8) v = projectile.getLookAngle();
        return v.lengthSqr() < 1e-8 ? S2C.Blast.DOWN : v.normalize();
    }

    /** Проверки: осколки боевой части оружия {@code weapon} из {@code at} с осью {@code axis}, без взрыва и района. */
    public static FragmentStrike testFragments(ServerLevel level, WeaponType weapon, Vec3 at, Vec3 axis, long seed) {
        FragmentModel model = java.util.Objects.requireNonNull(FragmentModel.of(weapon.spec()), "у оружия нет осколков");
        return FragmentStrike.queue(level, null, List.of(), () -> true, model, at, axis, seed, null, null,
                IncomingConfig.SERVER.shatterGlass.get(), IncomingConfig.SERVER.blockDamage.get());
    }

    /**
     * Наземный взрыв боевой части: заряд из паспорта ({@link WeaponSpec#charge}) — волна, газы, тела (главный взрыв); за
     * ним — воронка по закону обычного заряда, поджиг шаром топлива из бака ({@link WeaponSpec#fuelAtImpact}), обломки,
     * осколки корпуса ({@link FragmentStrike}), стёкла. Вторичных подрывов и колец огня нет: вторичное — только топливо и горящие обломки.
     */
    static final class SurfaceBlast implements Timeline {
        private final WeaponType weapon;
        private final Vec3 pos;
        @Nullable
        private final Entity direct;
        @Nullable
        private final Entity owner;
        private final GroundMaterial mat;
        private final BlastArea area;
        /** Главный взрыв: остальная работа удара ждёт, пока он снесёт своё. */
        private final List<StagedExplosion> main;

        SurfaceBlast(ServerLevel level, WeaponType weapon, Vec3 pos, @Nullable Entity direct, @Nullable UUID ownerId) {
            this.weapon = weapon;
            this.pos = pos;
            WeaponSpec spec = weapon.spec();
            double held = chargeReach(spec.charge(), spec.fuelAtImpact());
            // аппарат рядом: взрыв по нему ванильными лучами читает мир дальше ({@code StagedExplosion.crafts})
            float craft = StagedExplosion.craftPower(level, pos, spec.charge());
            if (craft > 0) held = Math.max(held, StagedExplosion.craftReach(craft));
            this.area = BlastArea.hold(level, pos, held);
            this.direct = direct;
            this.owner = ownerId == null ? null : level.getPlayerByUUID(ownerId);
            this.mat = GroundMaterial.sample(level, BlockPos.containing(pos));
            int kind = switch (spec.blast()) {
                case MISSILE -> S2C.Blast.MISSILE;
                case ROCKET -> S2C.Blast.ROCKET;
                case DRONE, NONE -> S2C.Blast.DRONE;
            };
            float surface = (float) groundUnder(level, pos);
            // видно — до far_range, слышно — докуда звук взрыва его заряда тихой ночью выше фона (HearingModel), но не
            // дальше, чем сервер шлёт что-либо далёкое
            double reach = Math.max(IncomingConfig.SERVER.farRange.get(),
                    Math.min(IncomingConfig.FAR_RANGE_MAX, HearingModel.audibleRangeM(spec.charge())));
            this.seed = level.random.nextLong();
            this.axis = axis(direct);
            PacketDistributor.sendToPlayersNear(level, null, pos.x, pos.y, pos.z, reach,
                    new S2C.Blast(kind, pos, mat.ordinal(), surface, seed, Optional.ofNullable(direct).map(Entity::getUUID), weapon.id(), axis));
            // заряд на аппарате: воронку и шар топлива там не ставить — это корпус, а не земля. Снаряд отдаёт точку удара о
            // аппарат уже в мире (StrikeProjectile: SubLevels.toWorld), поэтому — и аппарат, чей габарит у точки
            inPlots = SubLevels.inPlotGrid(level, new net.minecraft.world.level.ChunkPos(BlockPos.containing(pos)));
            onCraft = inPlots || !SubLevels.near(level, pos, 1).isEmpty();
            // боевая часть рвётся на грани, в которую ударила: плиту над пустотой она пробивает (SlabBreach); корпус
            // аппарата — не преграда мира
            main = spec.charge() > 0 ? List.of(charge(level, area, pos, spec.charge(),
                    onCraft ? SlabBreach.Placement.NONE : SlabBreach.Placement.SURFACE, direct, owner)) : List.of();
        }

        /** Взрыв на аппарате Sable. */
        private final boolean onCraft;
        /** Точка взрыва — в сетке плотов Sable (координаты аппарата, а не мира). */
        private final boolean inPlots;
        /** Зерно взрыва (оно же в пакете взрыва): по нему осколки у сервера и клиента одни. */
        private final long seed;
        /** Ось боевой части — курс снаряда у удара. */
        private final Vec3 axis;

        /** Главный взрыв был (обработчик-приват его не отменил). */
        private boolean happened() {
            return !main.isEmpty() && main.getFirst().happened();
        }

        @Override
        public boolean tick(ServerLevel level) {
            boolean missile = weapon.spec().blast() == WeaponSpec.Blast.MISSILE;
            // всё после главного взрыва — только если он был: взрыв, отменённый приватом, не роет и не жжёт
            if (main.isEmpty()) return false;
            if (IncomingConfig.SERVER.blockDamage.get() && !onCraft) {
                unit(level, area, main, ImpactCost.Kind.GROUND, "Crater", l -> happened() ? Craters.dig(l, pos, weapon.spec().charge(), mat) : 0);
            }
            if (weapon.spec().fuelAtImpact() > 0 && !onCraft) {
                unit(level, area, main, ImpactCost.Kind.GROUND, "Fuel fireball", l -> happened() ? Craters.ignite(l, pos, weapon.spec().fuelAtImpact(), weapon.spec().charge()) : 0);
            }
            unit(level, area, main, ImpactCost.Kind.DEBRIS, "Debris",
                    l -> !happened() ? 0 : missile ? DebrisSpawner.missile(l, pos, mat) : DebrisSpawner.drone(l, pos, mat));
            FragmentModel fragments = FragmentModel.of(weapon.spec());
            // в сетке плотов Sable лучи шли бы по блокам аппарата: осколки аппараты не ломают
            if (fragments != null && !inPlots) FragmentStrike.queue(level, area, main, this::happened, fragments, pos, axis, seed, direct, owner,
                    IncomingConfig.SERVER.shatterGlass.get(), IncomingConfig.SERVER.blockDamage.get());
            if (IncomingConfig.SERVER.shatterGlass.get()) shatterGlass(level, missile);
            return false;
        }

        @Override
        public void end(ServerLevel level) {
            area.release(level);
        }

        /**
         * Стёкла — по давлению волны заряда боевой части — единицами по секциям; звук и частицы — с первой секцией, где
         * что-то выбито. Листву ломают лучи волны по её кривой, как и всё остальное.
         */
        private void shatterGlass(ServerLevel level, boolean missile) {
            Shatter.queue(level, area, main, pos, Shatter.Wave.of(pos, weapon.spec().charge()), (l, n) -> {
                float vol = missile ? 6 : 4;
                l.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, vol, missile ? 0.7f : 0.8f);
                l.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, vol, missile ? 1.0f : 1.2f);
                if (missile) l.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, vol, 1.3f);
                forced(l, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.GLASS.defaultBlockState()), pos.add(0, missile ? 4 : 3, 0),
                        missile ? 500 : 250, missile ? 16 : 10, missile ? 6 : 5, missile ? 16 : 10, 0, 256);
            });
        }
    }

    // ================================================================ бетонобойная бомба

    /**
     * Подземный взрыв заряда бомбы ({@link WeaponSpec#warheadTnt}) по приведённой глубине ({@link BuriedBlast}). Сразу —
     * волна заряда по физике ({@link #charge}) из точки подрыва: в грунте ей идти некуда (природный грунт — тень), а в
     * пустоте — бункере, пещере, полости — она бьёт тела и стены, газы держат давление в помещении. За ней единицами:
     * полость и зона дробления, своды ходов и пещер в зоне трещин, на малой глубине — воронка выброса (и волна над
     * землёй той доли заряда, что ушла в воздух), иначе — газы и пыль из хода; через ~1 с — труба обрушения свода.
     */
    static final class BunkerBlast implements Timeline {
        /** Когда рушится свод полости, тиков (как было: обрушение идёт за взрывом, ~1 с; оценка). */
        static final int CHIMNEY_TICK = 22;
        /** Слоёв зоны трещин на единицу работы. */
        static final int FRACTURE_SLICES = 1;
        private final Vec3 pos;
        private final Vec3 entry;
        @Nullable
        private final Entity direct;
        @Nullable
        private final Entity owner;
        private final GroundMaterial ventMat;
        private final BuriedBlast law;
        /** Верх над зарядом (без своей скважины). */
        private final int cover;
        /** Сечение хода бомбы, м². */
        private final double hole;
        private final BlastArea area;
        /** Волна заряда (в пустотах под землёй и, при прорыве, над землёй): остальная работа ждёт её. */
        private final List<StagedExplosion> main;
        private int t;

        BunkerBlast(ServerLevel level, Vec3 pos, Vec3 entry, @Nullable Entity direct, @Nullable UUID ownerId) {
            this.pos = pos;
            this.entry = entry;
            this.direct = direct;
            this.owner = ownerId == null ? null : level.getPlayerByUUID(ownerId);
            BlockPos c = BlockPos.containing(pos);
            GroundMaterial mat = GroundMaterial.sample(level, c);
            this.ventMat = GroundMaterial.sample(level, BlockPos.containing(entry));
            this.cover = surfaceAbove(level, c);
            WeaponSpec.Airframe bomb = WeaponSpec.BUNKER.payload();
            this.law = new BuriedBlast(WeaponSpec.BUNKER.warheadTnt(), BunkerCover.depth(cover, pos.y));
            this.hole = Math.PI * bomb.article().diameter() * bomb.article().diameter() / 4;
            double air = law.airCharge(hole);
            Vec3 top = new Vec3(pos.x, cover, pos.z);
            // район — то, что взрыв делает с грунтом (трещины, труба, воронка); волна над землёй при прорыве ломает дальше
            // там, где мир загружен (луч кончается в неготовом чанке): охват волны 2,4 т — ~650 блоков, тысячи чанков, и
            // весь удар ждал бы их загрузки
            double held = Math.max(law.fracture(), Math.max(law.chimney(), 2 * law.craterRadius(CraterModel.Soil.WET) + 1)) + EDGE;
            this.area = BlastArea.hold(level, pos, held);

            // видно — до far_range, слышно — докуда звук той доли заряда, что вышла в воздух (HearingModel)
            double reach = Math.max(IncomingConfig.SERVER.farRange.get(), Math.min(IncomingConfig.FAR_RANGE_MAX, HearingModel.audibleRangeM(air)));
            PacketDistributor.sendToPlayersNear(level, null, pos.x, pos.y, pos.z, reach,
                    new S2C.Blast(S2C.Blast.BUNKER, pos, mat.ordinal(), cover, level.random.nextLong(), Optional.ofNullable(direct).map(Entity::getUUID),
                            WeaponType.BUNKER.id()));

            // заряд один: в пустотах у заряда — то, что не ушло в воздух, над землёй — доля, что ушла (у самой поверхности —
            // весь заряд волной над землёй)
            List<StagedExplosion> blasts = new ArrayList<>(2);
            double below = law.breaches() ? law.tnt() - air : law.tnt();
            // в толще заряд забит: пустоты рядом (бункер, пещера) он пробивает (SlabBreach); доля, вышедшая наружу, — волна
            if (below > 0) blasts.add(charge(level, area, pos, below, SlabBreach.Placement.BURIED, direct, owner));
            if (law.breaches() && air > 0) blasts.add(charge(level, area, top.add(0, 0.5, 0), air, SlabBreach.Placement.NONE, direct, owner));
            main = List.copyOf(blasts);
        }

        @Override
        public boolean tick(ServerLevel level) {
            t++;
            if (t == 1) {
                boolean blocks = IncomingConfig.SERVER.blockDamage.get();
                // в зоне дробления давление в породе — сотни МПа: кто там (в ходе, в пустоте за стеной), не выживет
                unit(level, area, main, ImpactCost.Kind.HITS, "Crush zone", l -> {
                    if (!happened()) return 0;
                    quake(l, pos, law.tnt() - law.airCharge(hole));
                    return crushBodies(l);
                });
                if (blocks) {
                    // воронка — по верху, пока полость не выгрызла столбец заряда
                    if (law.breaches()) unit(level, area, main, ImpactCost.Kind.GROUND, "Ejection crater", l -> happened() ? crater(l) : 0);
                    int r = Mth.ceil(law.crush());
                    for (int dy = -r; dy <= r; dy++) {
                        int y = dy;
                        unit(level, area, main, ImpactCost.Kind.GROUND, "Cavity", l -> happened() ? cavitySlice(l, y) : 0);
                    }
                }
                if (law.breaches()) {
                    if (IncomingConfig.SERVER.shatterGlass.get()) {
                        Vec3 top = new Vec3(pos.x, cover, pos.z);
                        Shatter.queue(level, area, main, top, Shatter.Wave.of(top, law.airCharge(hole)), (l, n) -> {});
                    }
                } else {
                    // газы полости выходят по ходу бомбы
                    unit(level, area, main, ImpactCost.Kind.DEBRIS, "Vent", l -> {
                        if (!happened()) return 0;
                        double air = law.airCharge(hole);
                        PacketDistributor.sendToPlayersNear(l, null, entry.x, entry.y, entry.z,
                                Math.max(IncomingConfig.SERVER.farRange.get(), Math.min(IncomingConfig.FAR_RANGE_MAX, HearingModel.audibleRangeM(air))),
                                new S2C.Vent(entry, ventMat.ordinal(), (float) air));
                        return 1;
                    });
                }
            }
            if (t == 2 && IncomingConfig.SERVER.blockDamage.get()) {
                // своды в зоне трещин — следующим тиком: полости всех подрывов этого тика встают в очередь раньше (осыпание
                // у воронок залпа идёт вместе, а не по очереди); по FRACTURE_SLICES слоёв на единицу (~3 тыс. клеток слой; падающий щебень и вода, что затекает в полость, — обновления соседей)
                int f = Mth.ceil(law.fracture());
                for (int dy = -f; dy <= f; dy += FRACTURE_SLICES) {
                    int lo = dy, hi = Math.min(f, dy + FRACTURE_SLICES - 1);
                    unit(level, area, main, ImpactCost.Kind.GROUND, "Fractures", l -> {
                        int n = 0;
                        for (int y = lo; y <= hi && happened(); y++) n += fractureSlice(l, y);
                        return n;
                    });
                }
            }
            if (t == CHIMNEY_TICK && IncomingConfig.SERVER.collapse.get() && IncomingConfig.SERVER.blockDamage.get() && !law.breaches()) {
                unit(level, area, main, ImpactCost.Kind.GROUND, "Roof collapse", l -> happened() ? chimney(l) : 0);
            }
            return t < CHIMNEY_TICK;
        }

        @Override
        public void end(ServerLevel level) {
            area.release(level);
        }

        /** Волна заряда была (приват её не отменил): взрыв, отменённый приватом, не роет и не рушит. */
        private boolean happened() {
            return !main.isEmpty() && main.getFirst().happened();
        }

        /**
         * Верх над зарядом без его же скважины ({@link BunkerCover}); листва не в счёт (кроны — не укрытие). Только сервер:
         * карта {@code …_NO_LEAVES} есть лишь на нём.
         */
        private static int surfaceAbove(ServerLevel level, BlockPos c) {
            int[] h = new int[BunkerCover.RING.length];
            for (int i = 0; i < h.length; i++) {
                h[i] = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX() + BunkerCover.RING[i][0], c.getZ() + BunkerCover.RING[i][1]);
            }
            return BunkerCover.surface(h);
        }

        /** Тела в зоне дробления. Сколько задето. */
        private int crushBodies(ServerLevel level) {
            double r = law.crush();
            int n = 0;
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(pos, pos).inflate(r), LivingEntity::isAlive)) {
                if (e.position().distanceTo(pos) > r) continue;
                e.hurt(ModDamageTypes.source(level, ModDamageTypes.SHOCKWAVE, direct, owner), Float.MAX_VALUE);
                n++;
            }
            return n;
        }

        /**
         * Слой {@code dy} над зарядом: полость (всё, что не держит {@link Penetration.Kind#STOP}, — воздух) и зона
         * дробления (скала — в булыжник, бетон и кладка — в гравий; грунт уплотняется — без смены). Сколько блоков смотрели.
         */
        private int cavitySlice(ServerLevel level, int dy) {
            BlockPos c = BlockPos.containing(pos);
            double rc = law.cavity(), rk = law.crush();
            int r = Mth.ceil(rk), n = 0;
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d > rk) continue;
                    p.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    if (!level.isInWorldBounds(p) || !Terrain.ready(level, p)) continue;
                    n++;
                    BlockState s = level.getBlockState(p);
                    if (s.isAir()) continue;
                    ModDataMaps.Penetrability m = ModDataMaps.Penetrability.of(s);
                    if (m.kind() == Penetration.Kind.STOP || m.kind() == Penetration.Kind.FLUID) continue;
                    if (d <= rc) {
                        ColumnScar.replace(level, p.immutable(), s, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        continue;
                    }
                    // дробится порода и кладка, что в карте данных; прочее (сундук, механизм мода) — нет
                    BlockState rubble = rubble(s, ModDataMaps.Penetrability.find(s));
                    if (rubble != null && !s.is(rubble.getBlock())) ColumnScar.replace(level, p.immutable(), s, rubble, Block.UPDATE_ALL);
                }
            }
            return n;
        }

        /**
         * Во что дробится блок в зоне дробления: камень — в булыжник, глубинный сланец — в свой булыжник, прочая скала, бетон
         * и кладка — в гравий (щебень); грунт уплотняется — без смены; блок без записи в карте — без смены.
         */
        @Nullable
        private static BlockState rubble(BlockState s, @Nullable ModDataMaps.Penetrability m) {
            if (m == null) return null;
            if (s.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)) return Blocks.COBBLED_DEEPSLATE.defaultBlockState();
            if (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.STONE_ORE_REPLACEABLES)) return Blocks.COBBLESTONE.defaultBlockState();
            return m.kind() == Penetration.Kind.ROCK || m.kind() == Penetration.Kind.CONCRETE ? Blocks.GRAVEL.defaultBlockState() : null;
        }

        /**
         * Слой {@code dy} зоны трещин: свод хода или пещеры (воздух под природным грунтом или скалой) рушится щебнем —
         * гравием, который падает сам, — с вероятностью 1 − r/R трещин (оценка: ближе — почти всегда, у края — редко).
         * Полость и зона дробления — не здесь. Сколько блоков смотрели.
         */
        private int fractureSlice(ServerLevel level, int dy) {
            BlockPos c = BlockPos.containing(pos);
            double rf = law.fracture(), rk = law.crush();
            int r = Mth.ceil(rf), n = 0;
            RandomSource random = level.random;
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(), up = new BlockPos.MutableBlockPos();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d > rf || d <= rk) continue;
                    p.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    up.set(p.getX(), p.getY() + 1, p.getZ());
                    if (!level.isInWorldBounds(up) || !Terrain.ready(level, p)) continue;
                    n++;
                    if (!level.getBlockState(p).isAir()) continue;
                    BlockState roof = level.getBlockState(up);
                    if (!roof.is(ModTags.NUKE_GROUND) || random.nextDouble() > 1 - d / rf) continue;
                    ModDataMaps.Penetrability m = ModDataMaps.Penetrability.of(roof);
                    if (m.kind() != Penetration.Kind.ROCK && m.kind() != Penetration.Kind.SOIL) continue;
                    ColumnScar.replace(level, up.immutable(), roof, Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
            return n;
        }

        /**
         * Свод полости рушится трубой высотой {@link BuriedBlast#chimney} (радиус — полости): порода над полостью — щебень
         * (гравий), он осыпается в неё; труба дошла до верха — провал на поверхности и пыль над ним. Сколько блоков смотрели.
         */
        private int chimney(ServerLevel level) {
            BlockPos c = BlockPos.containing(pos);
            double rc = law.cavity();
            int r = Mth.ceil(rc), top = Math.min(cover, c.getY() + Mth.ceil(law.chimney())), n = 0;
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int y = c.getY() + 1; y <= top; y++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (dx * dx + dz * dz > rc * rc) continue;
                        p.set(c.getX() + dx, y, c.getZ() + dz);
                        if (!level.isInWorldBounds(p) || !Terrain.ready(level, p)) continue;
                        n++;
                        BlockState s = level.getBlockState(p);
                        if (s.isAir()) continue;
                        ModDataMaps.Penetrability m = ModDataMaps.Penetrability.of(s);
                        if (m.kind() == Penetration.Kind.STOP || m.kind() == Penetration.Kind.FLUID || s.is(Blocks.GRAVEL)) continue;
                        ColumnScar.replace(level, p.immutable(), s, Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
            if (law.subsides()) {
                Vec3 sv = new Vec3(pos.x, cover, pos.z);
                // пыль провала — доля грунта, что ушёл в провал (объём полости), поднятая падением (как у воронки)
                double cavity = 4.0 / 3 * Math.PI * Math.pow(rc, 3);
                PacketDistributor.sendToPlayersNear(level, null, sv.x, sv.y, sv.z, IncomingConfig.SERVER.farRange.get(),
                        new S2C.Collapse(sv, ventMat.ordinal(), (float) cavity));
            }
            return n;
        }

        /**
         * Воронка выброса ({@link BuriedBlast#craterRadius} по грунту у устья) — та же чаша и вал, что у
         * воронок ({@link Craters#dig}), до центра полости. Сколько столбцов смотрели.
         */
        private int crater(ServerLevel level) {
            CraterModel.Soil soil = ventMat.soil();
            return Craters.dig(level, new Vec3(pos.x, cover, pos.z), law.craterRadius(soil), law.craterDepth(soil), ventMat);
        }
    }
}
