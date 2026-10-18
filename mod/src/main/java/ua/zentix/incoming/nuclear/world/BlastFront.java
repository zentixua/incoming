package ua.zentix.incoming.nuclear.world;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.registry.ModDamageTypes;
import ua.zentix.incoming.registry.ModParticles;
import ua.zentix.incoming.registry.ModSounds;
import ua.zentix.incoming.warhead.Warheads;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntPredicate;

/**
 * Фронт ударной волны по сущностям и аппаратам (DESIGN-nuke §4): кольцо между прошлым и текущим радиусом — удар по
 * телу ({@link BlastBody}: лёгкие, бросок с ударом, стёкла), через положительную фазу — лёгкий обратный ветер к центру
 * (только тех, кого достал прямой ветер: в доме без окна к шару и в бункере его нет); тела, которые засыпали руины
 * чанка, — завал (тоже {@link BlastBody}). Руины чанка встают на тик раньше фронта: тела рядом запоминают окно и стену,
 * какими их застанет фронт ({@link #remember}).
 * <p>
 * Где фронт за тик, считается каждый тик (снимок сущностей в круге — доли миллисекунды), а удары по тем, кого он
 * накрыл, — очередь под общим бюджетом ({@link WorkClock}), ближние первыми. Удар — это урон, а урон — смерть,
 * лут и обработчики смерти всех модов сборки: у эпицентра волна за один тик накрывает сотни мобов, и разом это
 * сотня миллисекунд тика (у хоста — 84 мс в тике подрыва). Очередь ждёт, пока по этому подрыву пройдёт световой
 * импульс: свет приходит раньше волны, и кого волна убьёт, тот уже получил свои ожоги и дозу.
 * <p>
 * Аппараты Create Aeronautics (DESIGN §3.6) волна бьёт ванильным взрывом в ближайшей к эпицентру точке аппарата:
 * лучи взрыва Sable проводит и по блокам аппарата — так ломается корпус и толкается тело. Взрыв — тоже в очереди
 * под бюджетом (свет им не нужен — не ждут его), ломает только блоки аппаратов (земля — дело {@link ScarQueue}
 * по давлению), сущностей не трогает (их волна бьёт сама, по давлению) и слушается {@code block_damage}.
 */
final class BlastFront {
    /** С какого давления волна бьёт по аппарату. */
    private static final double AIRCRAFT_PSI = 3;
    /**
     * Наибольшая сила взрыва по аппарату (DESIGN §3.6). У эпицентра аппарат должен разлетаться: сила 16 ломает
     * в корпусе 17×3×17 из досок меньше половины того, что 26 (97 блоков против 224). Взрыв ломает только блоки
     * аппарата, поэтому его цена — лучи по корпусу, и он идёт единицей бюджета, одной на аппарат.
     */
    private static final float MAX_AIRCRAFT_POWER = 60;

    /**
     * @param owner  кто запустил (урон записывается на него), null — неизвестно
     * @param buried завал: руины засыпали тело ({@link BlastBody#bury}), иначе удар фронта
     * @param before окно и стена до руин, со сдвигом тела к приходу фронта; null — не запомнены
     */
    private record Hit(Detonation d, LivingEntity entity, @Nullable UUID owner, boolean buried, BlastBody.@Nullable Before before) {}

    /** Взрыв по аппарату: в ближайшей к эпицентру точке аппарата, когда его накрыл фронт. */
    private record AircraftHit(Detonation d, Vec3 at, float power) {}

    /**
     * Докуда (радиус, блоки) фронт уже прошёлся по сущностям: прямой фронт и обратный ветер. Не сохраняется: после
     * перезапуска (или новой {@code BlastFront}) посреди волны отсчёт — от фронта прошлого тика, кто уже внутри,
     * второй раз не бьётся.
     */
    private final Map<Integer, double[]> reached = new HashMap<>();
    /** Кто запустил подрыв (по номеру), пока идёт его фронт. После перезапуска неизвестен: урон без виновника. */
    private final Map<Integer, UUID> owners = new HashMap<>();
    /** Окно и стена у тел (по номеру подрыва, затем сущности), какими их застал фронт: до руин их чанков. */
    private final Map<Integer, Int2ObjectMap<BlastBody.Before>> before = new HashMap<>();
    /** Кого фронт этого подрыва застал в ветре (номера сущностей): только их тянет обратный ветер. */
    private final Map<Integer, IntSet> windy = new HashMap<>();
    /**
     * Кого фронт этого подрыва уже накрыл (номера сущностей): второй раз не бьёт — брошенное тело может обогнать
     * медленный фронт и снова оказаться перед ним.
     */
    private final Map<Integer, IntSet> struck = new HashMap<>();
    /** Удары фронта в очереди: номер подрыва и сущности ({@link #key}). */
    private final LongOpenHashSet queued = new LongOpenHashSet();
    /** Кого фронт накрыл, а удар ещё не нанесён: по тикам прихода, в тике — от ближних к дальним. */
    private final ArrayDeque<Hit> hits = new ArrayDeque<>();
    /** Аппараты, которые фронт накрыл, а взрыва по ним ещё не было: по тикам прихода. */
    private final ArrayDeque<AircraftHit> aircraft = new ArrayDeque<>();

    /** Удары, которые ждут бюджета. */
    int pending() {
        return hits.size() + aircraft.size();
    }

    /** Фронт идёт или удары фронта ждут очереди (полоса NUCLEAR общего бюджета). */
    boolean busy() {
        return !reached.isEmpty() || !hits.isEmpty() || !aircraft.isEmpty();
    }

    /** Подрыв: запомнить, кто его запустил. */
    void onDetonation(Detonation d, @Nullable UUID owner) {
        if (owner != null) owners.put(d.id(), owner);
    }

    /** Где фронт этого тика: кого накрыл (сущности и аппараты) — в очередь, обратный ветер — сразу (он дёшев). */
    void advance(ServerLevel level, List<Detonation> detonations, long now) {
        Set<Integer> active = new HashSet<>();
        for (Detonation d : detonations) {
            long since = now - d.gameTime();
            double reach = d.blastReach();
            if (since >= 0 && since <= d.arrivalTicks(reach) + d.positivePhaseTicks(reach) + 2) {
                advance(level, d, since);
                active.add(d.id());
            }
        }
        reached.keySet().retainAll(active);
        owners.keySet().retainAll(active);
        before.keySet().retainAll(active);
        windy.keySet().retainAll(active);
        struck.keySet().retainAll(active);
    }

    private void advance(ServerLevel level, Detonation d, long since) {
        double[] done = reached.computeIfAbsent(d.id(), k -> new double[]{d.frontRadius(since - 1), d.phaseEndRadius(since - 1)});
        double r = d.frontRadius(since);
        double back = d.phaseEndRadius(since);
        if (r <= done[0] && back <= done[1]) return;
        // снимок списка: удар может убить моба, и из него выпадет лут — живую карту сущностей трогать нельзя
        double reach = Math.max(r, back);
        List<LivingEntity> ring = new ArrayList<>();
        IntSet hitBy = struck.computeIfAbsent(d.id(), k -> new IntOpenHashSet());
        for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, AABB.ofSize(d.burst(), reach * 2, reach * 2, reach * 2))) {
            if (!living.isAlive() || living.isSpectator() || living.isPassenger()) continue;
            double dist = living.position().distanceTo(d.burst());
            if (dist >= done[0] && dist < r) {
                if (hitBy.add(living.getId())) ring.add(living);
            } else if (dist >= done[1] && dist < back) suck(level, d, living);
        }
        ring.sort(Comparator.comparingDouble(e -> e.distanceToSqr(d.burst())));
        Int2ObjectMap<BlastBody.Before> seen = before.get(d.id());
        for (LivingEntity e : ring) {
            // сдвиг тела от места, где запомнило окно и стену, — к приходу фронта, а не к удару: удар ждёт импульса и бюджета
            BlastBody.Before b = seen == null ? null : seen.remove(e.getId());
            hits.add(new Hit(d, e, owners.get(d.id()), false, b == null ? null : b.drifted(e.position())));
            queued.add(key(d, e));
        }
        sweepAircraft(level, d, done[0], r);
        done[0] = Math.max(done[0], r);
        done[1] = Math.max(done[1], back);
    }

    /**
     * Удары из очереди, сколько успеем за бюджет.
     *
     * @param lit по подрыву с этим номером световой импульс уже прошёл
     */
    void work(ServerLevel level, WorkClock clock, IntPredicate lit) {
        // аппараты — первыми: взрыв там, где аппарат был под фронтом, пока он не улетел
        while (!aircraft.isEmpty() && clock.canStart()) {
            long t0 = clock.begin();
            blast(level, aircraft.removeFirst());
            clock.end(t0);
        }
        while (!hits.isEmpty() && lit.test(hits.peekFirst().d().id()) && clock.canStart()) {
            Hit h = hits.removeFirst();
            if (!h.buried()) queued.remove(key(h.d(), h.entity()));
            // за время в очереди моб мог умереть, выгрузиться или сесть в лодку — как при снимке
            LivingEntity e = h.entity();
            if (!e.isAlive() || e.isSpectator() || e.isPassenger()) continue;
            long t0 = clock.begin();
            Entity owner = h.owner() == null ? null : level.getPlayerByUUID(h.owner());
            if (h.buried()) BlastBody.bury(level, h.d(), e, owner);
            else {
                // руины встали, пока удар ждал в очереди: запомнено уже после прихода фронта
                Int2ObjectMap<BlastBody.Before> seen = before.get(h.d().id());
                BlastBody.Before late = h.before() != null || seen == null ? null : seen.remove(e.getId());
                BlastBody.Before b = h.before() != null ? h.before() : late == null ? null : late.drifted(e.position());
                if (BlastBody.hit(level, h.d(), e, owner, b)) windy.computeIfAbsent(h.d().id(), k -> new IntOpenHashSet()).add(e.getId());
            }
            clock.end(t0);
        }
    }

    /**
     * Руины чанка вот-вот встанут: тела в нём и рядом (окно до {@link BlastBody#GLASS_REACH} — может быть и в этом
     * чанке), до которых фронт ещё не дошёл или чей удар ещё в очереди, запоминают окно и стену. Запомненное первым —
     * мир, тронутый меньше всего.
     */
    void remember(ServerLevel level, Detonation d, LevelChunk chunk) {
        double[] done = reached.get(d.id());
        if (done == null) return;
        Int2ObjectMap<BlastBody.Before> seen = before.computeIfAbsent(d.id(), k -> new Int2ObjectOpenHashMap<>());
        int x = chunk.getPos().getMinBlockX(), z = chunk.getPos().getMinBlockZ();
        double r = BlastBody.GLASS_REACH + 1;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(x - r, level.getMinBuildHeight(), z - r, x + 16 + r, level.getMaxBuildHeight(), z + 16 + r))) {
            if (!e.isAlive() || e.isSpectator() || e.isPassenger() || seen.containsKey(e.getId())) continue;
            // фронт уже прошёл: удар нанесён или ждёт в очереди с прежним миром — тогда запомнить его сейчас и нужно
            if (e.position().distanceTo(d.burst()) < done[0] && !queued.contains(key(d, e))) continue;
            if (d.overpressureKpa(e.position()) < Detonation.HIT_KPA) continue;
            seen.put(e.getId(), BlastBody.before(level, d, e));
        }
    }

    /** Руины засыпали тело: удар завалом — в очередь, за ударами фронта этого тика. */
    void bury(Detonation d, LivingEntity e) {
        hits.add(new Hit(d, e, owners.get(d.id()), true, null));
    }

    /** Отбой. */
    void clear() {
        reached.clear();
        owners.clear();
        before.clear();
        windy.clear();
        struck.clear();
        dropHits();
    }

    /** Удары в очереди — сбросить; докуда фронт прошёл, остаётся (иначе он заново ударил бы всех внутри). */
    void dropHits() {
        hits.clear();
        queued.clear();
        aircraft.clear();
    }

    private static long key(Detonation d, LivingEntity e) {
        return (long) d.id() << 32 | e.getId() & 0xFFFFFFFFL;
    }

    /**
     * Обратный ветер: воздух возвращается к эпицентру и тянет за собой тех, кого достал прямой ветер. Удар ещё в
     * очереди (ждёт импульса и бюджета) — ветер по небу над телом.
     */
    private void suck(ServerLevel level, Detonation d, LivingEntity e) {
        double psi = d.psi(e.position());
        if (psi < 1) return;
        IntSet blown = windy.get(d.id());
        boolean wind = blown != null && blown.contains(e.getId())
                || queued.contains(key(d, e)) && BlastBody.windAt(level, e.getEyePosition());
        if (!wind) return;
        Vec3 to = new Vec3(d.burst().x - e.getX(), 0, d.burst().z - e.getZ());
        if (to.lengthSqr() < 1e-4) return;
        to = to.normalize().scale(Math.min(0.6, 0.08 * psi));
        e.push(to.x, 0.05, to.z);
        e.hurtMarked = true;
    }

    /** Аппараты, чья ближайшая к эпицентру точка — в кольце фронта этого тика и под давлением от 3 psi, — в очередь. */
    private void sweepAircraft(ServerLevel level, Detonation d, double from, double to) {
        if (to <= from) return;
        for (SubLevelAccess sub : SubLevels.near(level, d.burst(), to)) {
            AABB box = sub.boundingBox().toMojang();
            Vec3 nearest = new Vec3(Mth.clamp(d.burst().x, box.minX, box.maxX), Mth.clamp(d.burst().y, box.minY, box.maxY),
                    Mth.clamp(d.burst().z, box.minZ, box.maxZ));
            double dist = nearest.distanceTo(d.burst());
            if (dist < from || dist >= to) continue;
            double psi = d.psi(nearest);
            if (psi < AIRCRAFT_PSI) continue;
            aircraft.add(new AircraftHit(d, nearest, (float) Mth.clamp(6 + (psi - AIRCRAFT_PSI) * 1.15, 6, MAX_AIRCRAFT_POWER)));
        }
    }

    /**
     * Взрыв по аппарату. Без {@code block_damage} — без разрушений ({@code NONE}): Sable всё равно толкает аппарат
     * по лучам взрыва. Лучи ванильного взрыва читают блоки — только по готовым чанкам. Выпадение — с затуханием, как
     * у взрывов боевых частей ({@code BLOCK}: правило {@code blockExplosionDropDecay}).
     */
    private static void blast(ServerLevel level, AircraftHit a) {
        boolean blocks = IncomingConfig.SERVER.nukeBlockDamage.get();
        Warheads.whenReady(level, a.at(), Warheads.reach(a.power()), l -> l.explode(null,
                ModDamageTypes.source(l, ModDamageTypes.NUCLEAR_BLAST, null, null), new AircraftOnly(l),
                a.at().x, a.at().y, a.at().z, a.power(), false,
                blocks ? Level.ExplosionInteraction.BLOCK : Level.ExplosionInteraction.NONE,
                ModParticles.NONE.get(), ModParticles.NONE.get(), ModSounds.SILENT));
    }

    /**
     * Взрыв по аппарату ломает только блоки аппаратов (они лежат в плотах Sable) и не трогает сущности: ни урона,
     * ни броска — сущности волна уже бьёт по давлению ({@link #hit}).
     */
    private static final class AircraftOnly extends ExplosionDamageCalculator {
        private final Level level;

        AircraftOnly(Level level) {
            this.level = level;
        }

        @Override
        public boolean shouldBlockExplode(Explosion explosion, BlockGetter reader, BlockPos pos, BlockState state, float power) {
            return !state.isAir() && SubLevels.isInPlot(level, pos.getCenter());
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
}
