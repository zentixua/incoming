package ua.zentix.incoming.warhead;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.compat.DhUpdates;
import ua.zentix.incoming.nuclear.model.BlastInjury;
import ua.zentix.incoming.registry.ModDamageTypes;
import ua.zentix.incoming.strike.ImpactCost;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.work.UnitQueue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Осколки наземного взрыва ({@link FragmentModel}) единицами в очереди попаданий, после главного взрыва удара: каждый
 * осколок — луч из точки взрыва с массой и скоростью по закону (тысячи лучей, но не сущности), по готовым чанкам
 * ({@link FragmentTrace}). Луч останавливается в первом теле или твёрдом блоке; по пути бьёт стёкла, в кладке
 * оставляет выбоину (блок становится треснувшим — не по физике: ямка от осколка — сантиметры, а меняется весь блок).
 * Тело ранит по энергии удара каждого попавшего осколка ({@link FragmentModel#injury}, {@link FragmentModel#death}),
 * сколько здоровья это стоит — как у волны ({@link BlastInjury#lostShare}); за стеной, за листвой и в воде осколки не
 * достают. Аппараты Sable осколки не задерживают и не ломают (их блоки — не в мире).
 * <p>
 * Единицы: список тел (снимок живых в мире, не дальше {@link FragmentModel#reach}), лучи — порциями по
 * {@link #TRACE_NANOS}, стёкла и выбоины — порциями по {@link #BLOCK_PORTION}, урон — по {@link #BODY_PORTION} тел.
 * Зерно и ось — те же, что в пакете взрыва: первые лучи клиент ведёт сам и рисует пыль и искры там же.
 */
public final class FragmentStrike implements UnitQueue.Job {
    /** Лучи одной единицы — не дольше, нс (и хоть {@link #MIN_RAYS}). */
    static final long TRACE_NANOS = 4_000_000;
    static final int MIN_RAYS = 64;
    static final int BLOCK_PORTION = 64;
    static final int BODY_PORTION = 8;
    /**
     * Силуэт тела занимает такую долю его рамки: человек стоя — ~0,6 м² сбоку (G&D §12.44, {@link BlastInjury}) из
     * 0,6 × 1,8 м рамки игрока; у мобов — так же (оценка).
     */
    static final double BODY_FILL = 0.55;
    /** Кладка с треснувшим видом в ванили: выбоина — она. */
    static final Map<Block, Block> CRACKED = Map.of(
            Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS,
            Blocks.DEEPSLATE_BRICKS, Blocks.CRACKED_DEEPSLATE_BRICKS,
            Blocks.DEEPSLATE_TILES, Blocks.CRACKED_DEEPSLATE_TILES,
            Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS,
            Blocks.NETHER_BRICKS, Blocks.CRACKED_NETHER_BRICKS);

    @Nullable
    private final BlastArea area;
    private final List<StagedExplosion> after;
    private final BooleanSupplier happened;
    private final FragmentModel model;
    private final Vec3 centre, axis;
    private final long seed;
    @Nullable
    private final Entity direct, owner;
    private final boolean glassRule, blockRule;

    private List<Body> bodies;
    private int next, bodyNext;
    private final LongOpenHashSet glass = new LongOpenHashSet();
    private final Long2ObjectOpenHashMap<BlockState> chips = new Long2ObjectOpenHashMap<>();
    private long[] blockList;
    private int blockNext;
    private final LongOpenHashSet touched = new LongOpenHashSet();
    /** Для проверок: попаданий в тело по UUID (с весом лучей), сколько стёкол и выбоин. */
    private final Map<UUID, Double> hits = new HashMap<>();
    private int glassBroken, chipped;
    private boolean done;

    private FragmentStrike(@Nullable BlastArea area, List<StagedExplosion> after, BooleanSupplier happened, FragmentModel model, Vec3 centre,
                           Vec3 axis, long seed, @Nullable Entity direct, @Nullable Entity owner, boolean glassRule, boolean blockRule) {
        this.area = area;
        this.after = after;
        this.happened = happened;
        this.model = model;
        this.centre = centre;
        this.axis = axis;
        this.seed = seed;
        this.direct = direct;
        this.owner = owner;
        this.glassRule = glassRule;
        this.blockRule = blockRule;
    }

    /** Поставить осколки удара в очередь попаданий: после взрывов {@code after}, если главный был ({@code happened}). */
    static FragmentStrike queue(ServerLevel level, @Nullable BlastArea area, List<StagedExplosion> after, BooleanSupplier happened,
                                FragmentModel model, Vec3 centre, Vec3 axis, long seed, @Nullable Entity direct, @Nullable Entity owner,
                                boolean glassRule, boolean blockRule) {
        FragmentStrike job = new FragmentStrike(area == null ? null : area.retain(), after, happened, model, centre, axis, seed, direct, owner,
                glassRule, blockRule);
        StrikeWorld.get(level).impacts().add(level, job);
        return job;
    }

    @Override
    public boolean ready(ServerLevel level) {
        return area == null || area.ready(level);
    }

    @Override
    public boolean blocked() {
        return Warheads.pending(after);
    }

    @Override
    public int unitKind() {
        return ImpactCost.Kind.FRAGMENTS.ordinal();
    }

    @Override
    public boolean step(ServerLevel level) {
        long t0 = System.nanoTime();
        int n = 0;
        if (bodies == null) {
            if (!happened.getAsBoolean()) {
                done = true;
                return false;
            }
            bodies = bodies(level);
        } else if (next < model.traced()) {
            n = trace(level, t0);
        } else if (blockList == null) {
            blockList = new long[glass.size() + chips.size()];
            int i = 0;
            for (long p : glass) blockList[i++] = p;
            for (long p : chips.keySet()) blockList[i++] = p;
        } else if (blockNext < blockList.length) {
            n = applyBlocks(level);
        } else if (bodyNext < bodies.size()) {
            n = hurt(level);
        }
        record(level, System.nanoTime() - t0, n);
        done = bodies != null && next >= model.traced() && blockList != null && blockNext >= blockList.length && bodyNext >= bodies.size();
        return !done;
    }

    private void record(ServerLevel level, long took, int n) {
        if (area != null) area.record(level, ImpactCost.Kind.FRAGMENTS, took, n);
        else StrikeWorld.get(level).impactCost().add(ImpactCost.Kind.FRAGMENTS, took, n);
    }

    /** Живые тела, до которых долетает хоть один осколок: снимок (лут и смерти не меняют список). */
    private List<Body> bodies(ServerLevel level) {
        double reach = model.reach(), r2 = reach * reach;
        List<Body> out = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (!(e instanceof LivingEntity le) || !le.isAlive() || le.isSpectator()) continue;
            if (le.getBoundingBox().distanceToSqr(centre) > r2) continue;
            out.add(new Body(le, le.getBoundingBox()));
        }
        return out;
    }

    /** Порция лучей: каждый — настоящий осколок; тело на пути раньше блока забирает его. */
    private int trace(ServerLevel level, long t0) {
        int n = 0;
        int total = model.traced();
        double w = model.weight();
        while (next < total && (n < MIN_RAYS || System.nanoTime() - t0 < TRACE_NANOS)) {
            int i = next++;
            n++;
            FragmentModel.Fragment f = model.fragment(seed, i, axis);
            double range = f.range();
            if (range <= 0) continue;
            SplittableRandom r = new SplittableRandom(seed * 31 + i);
            // тело на пути: ближайшее, чей силуэт осколок задел (рамка шире силуэта — доля BODY_FILL)
            Body hit = null;
            double at = range;
            for (Body b : bodies) {
                double t = enter(b.box, centre, f.dir(), at);
                if (t >= 0 && t < at && r.nextDouble() < BODY_FILL) {
                    at = t;
                    hit = b;
                }
            }
            double stopped = FragmentTrace.trace(level, centre, f.dir(), at, new FragmentTrace.Visitor() {
                @Override
                public void glass(BlockPos pos, BlockState state, double s) {
                    if (glassRule && f.energyAt(s) >= FragmentModel.GLASS_J) glass.add(pos.asLong());
                }

                @Override
                public void stop(BlockPos pos, BlockState state, double s, Vec3 point, Direction entry) {
                    Block cracked = CRACKED.get(state.getBlock());
                    if (blockRule && cracked != null && FragmentModel.chips(f.mass(), f.speedAt(s))) chips.put(pos.asLong(), cracked.defaultBlockState());
                }
            });
            // тело забирает осколок, только если до него ничего твёрдого и всё по пути готово
            if (hit != null && stopped >= at) hit.add(f.energyAt(at), w);
        }
        return n;
    }

    /** Путь по лучу до входа в рамку, м; −1 — мимо (или дальше {@code max}). */
    static double enter(AABB box, Vec3 o, Vec3 d, double max) {
        double t0 = 0, t1 = max;
        double[] lo = {box.minX, box.minY, box.minZ}, hi = {box.maxX, box.maxY, box.maxZ};
        double[] p = {o.x, o.y, o.z}, v = {d.x, d.y, d.z};
        for (int k = 0; k < 3; k++) {
            if (Math.abs(v[k]) < 1e-12) {
                if (p[k] < lo[k] || p[k] > hi[k]) return -1;
                continue;
            }
            double a = (lo[k] - p[k]) / v[k], b = (hi[k] - p[k]) / v[k];
            if (a > b) {
                double c = a;
                a = b;
                b = c;
            }
            t0 = Math.max(t0, a);
            t1 = Math.min(t1, b);
            if (t0 > t1) return -1;
        }
        return t0;
    }

    /** Порция стёкол и выбоин: только в готовых чанках, блок тот же, что видел луч. */
    private int applyBlocks(ServerLevel level) {
        int n = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (; blockNext < blockList.length && n < BLOCK_PORTION; blockNext++, n++) {
            long p = blockList[blockNext];
            m.set(p);
            int cx = m.getX() >> 4, cz = m.getZ() >> 4;
            if (!Terrain.ready(level, cx, cz)) continue;
            BlockState now = level.getBlockState(m);
            BlockState to;
            if (glass.contains(p)) {
                if (!now.is(ua.zentix.incoming.registry.ModTags.SHATTERS)) continue;
                to = Blocks.AIR.defaultBlockState();
            } else {
                to = chips.get(p);
                if (CRACKED.get(now.getBlock()) != to.getBlock()) continue;
            }
            boolean edgesReady = Terrain.neighbourhoodReady(level, cx, cz);
            if (!edgesReady && !Terrain.readyAround(level, Vec3.atCenterOf(m), Warheads.EDGE)) continue;
            level.setBlock(m, to, edgesReady ? Block.UPDATE_ALL : Warheads.EDGE_FLAGS);
            touched.add(ChunkPos.asLong(cx, cz));
            if (to.isAir()) glassBroken++;
            else chipped++;
        }
        return n;
    }

    /** Урон по телам: по всем попаданиям сразу, как у волны — гибель или доля здоровья по своей случайности тела. */
    private int hurt(ServerLevel level) {
        int n = 0;
        for (; bodyNext < bodies.size() && n < BODY_PORTION; bodyNext++) {
            Body b = bodies.get(bodyNext);
            if (b.hits <= 0) continue;
            n++;
            hits.merge(b.entity.getUUID(), b.hits, Double::sum);
            LivingEntity e = b.entity;
            if (!e.isAlive() || e instanceof Player p && p.getAbilities().invulnerable) continue;
            double fate = new SplittableRandom(seed ^ e.getUUID().getLeastSignificantBits() ^ e.getUUID().getMostSignificantBits()).nextDouble();
            double lost = BlastInjury.lostShare(1 - b.noDeath, 1 - b.noInjury, fate);
            float dmg = lost >= 1 ? Float.MAX_VALUE : (float) (lost * e.getMaxHealth());
            if (dmg < 0.5f) continue;
            // осколки — свои раны, а не повтор удара волны: окно неуязвимости после волны их не съедает
            e.invulnerableTime = 0;
            e.hurt(ModDamageTypes.source(level, ModDamageTypes.FRAGMENT, direct, owner), dmg);
        }
        return n;
    }

    @Override
    public void end(ServerLevel level) {
        long at = level.getGameTime() + DhUpdates.SETTLE;
        touched.forEach(c -> DhUpdates.mark(level, new ChunkPos(c), at));
        if (area != null) area.release(level);
    }

    @Override
    public String describe() {
        return "Fragments at " + Mth.floor(centre.x) + " " + Mth.floor(centre.y) + " " + Mth.floor(centre.z);
    }

    // ---- для проверок

    public boolean done() {
        return done;
    }

    /** Попаданий в тело {@code id} (с весом лучей). */
    public double hits(UUID id) {
        return hits.getOrDefault(id, 0.0);
    }

    public int glassBroken() {
        return glassBroken;
    }

    public int chipped() {
        return chipped;
    }

    /** Тело в списке: рамка на момент взрыва и что в него попало. */
    private static final class Body {
        final LivingEntity entity;
        final AABB box;
        double hits, noDeath = 1, noInjury = 1;

        Body(LivingEntity entity, AABB box) {
            this.entity = entity;
            this.box = box;
        }

        void add(double joules, double weight) {
            hits += weight;
            noDeath *= Math.pow(1 - FragmentModel.death(joules), weight);
            noInjury *= Math.pow(1 - FragmentModel.injury(joules), weight);
        }
    }
}
