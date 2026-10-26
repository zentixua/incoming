package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.compat.SableTerrain;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.flash.FlashChar;
import ua.zentix.incoming.nuclear.model.ThermalModel;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.util.Palettes;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

/**
 * Ground effects at flash time (“cinema-level nuke” plan, step 3): everything thermal fluence does to the world is applied
 * during the pulse itself, not with the wave — burned turf and char, burned plants, melted snow, scorched biomes, face
 * char and fires ({@link RuinPlanner#flash}: flash layer is the ruin plan without dynamic pressure). The wave later
 * applies ruins as before (from the pre-flash world), changing beyond the layer only what it causes: stripped turf,
 * rubble, breaches, extinguished fire share ({@link RuinPlan#apply}).
 * <p>
 * One job per detonation (server thread, shared budget nuclear lane, after ruins reached by the wave, using their
 * remainder: front ruins matter more than distant flash layers, {@link NuclearWorld#tick}). First unit collects
 * in-memory chunks that ruins will reach ({@link ScarQueue#inRange}) and light will mark ({@link #reach}), player-visible
 * first (nearest player first), then others outward from epicenter; then one unit per chunk for snapshots (detonation
 * ruin cache: same snapshots as ruins) and background layer plan ({@link RuinWorkers}: visible priority for visible
 * chunks); one unit per ready layer writes it like ruins ({@link RuinPlan#applyFlash}: directly to sections, same-tick
 * section packets to players, Distant Horizons LODs). A chunk is applied no earlier than light at its ground point
 * nearest the ball reaches ignition threshold ({@link Detonation#flashTicks}): immediately near epicenter, near pulse
 * end at the edge (15 kt — 1.24 s, 1 Mt — 10 s). Layer fire does not tick before chunk ruins: neither grows nor goes out
 * until then ({@link RuinPlan#applyFlash}).
 * <p>
 * No layer for: chunks the wave reached before the layer (at epicenter; their ruins apply everything at once as before);
 * chunks near Sable craft without in-memory neighbors (Sable reads neighbors at every position — writing would load
 * them; ruins wait for neighbors themselves); new chunks while detonation snapshots and layers exceed
 * {@link NuclearPrep#PLANS_CAP} or heap is tight ({@link HeapWatch}). Chunks loaded after detonation get everything with
 * ruins. Job is non-persistent: pulse lasts seconds; the chunk remembers that the layer lit fire ({@link #mark}).
 */
public final class FlashScorch {
    final Detonation d;
    /** Chunks specified by a check (null — in-memory chunks within scorch radius). */
    @Nullable
    private final long[] only;
    @Nullable
    private final ColumnScar.Budget fixedBudget;
    /** Chunks in order (player-visible first) and each application time (game time); null — not collected yet. */
    @Nullable
    private long[] order;
    private long[] due;
    private boolean[] seen;
    private int next, seenCount;
    /** Layers submitted to background: chunk index in {@link #order} → task. In submission order. */
    private final Map<Integer, Build> building = new LinkedHashMap<>();
    private int applied, stale, late, gone, waiting, skipped, failures, blocks, fires;
    private long nanos, lastApplied = -1;
    private final HeapWatch.Tracker heap = new HeapWatch.Tracker();

    /** Background layer: task and work start time (game time; −1 — still waiting in pool queue). */
    private static final class Build {
        final Future<RuinPlan> task;
        long since = -1;

        Build(Future<RuinPlan> task) {
            this.task = task;
        }
    }

    FlashScorch(Detonation d) {
        this(d, null, null);
    }

    private FlashScorch(Detonation d, @Nullable long[] only, @Nullable ColumnScar.Budget budget) {
        this.d = d;
        this.only = only;
        this.fixedBudget = budget;
    }

    /** For checks: flash layer only for these chunks (fully in memory), with its own fire counter. */
    public static FlashScorch forTest(Detonation d, long[] chunks, ColumnScar.Budget budget) {
        return new FlashScorch(d, chunks.clone(), budget);
    }

    public Detonation detonation() {
        return d;
    }

    /** Chunks with layer applied (checks). */
    public int appliedChunks() {
        return applied;
    }

    /** Chunks without layer: near Sable craft whose neighbors are not in memory (checks). */
    public int waitingChunks() {
        return waiting;
    }

    // ---------------------------------------------------------------- chunk mark

    /** Detonation {@code id}'s flash layer lit fire in the chunk (server thread; saved with chunk). */
    static void mark(LevelChunk chunk, int id) {
        chunk.setData(ModAttachments.FLASH_LAYER, id);
        chunk.setUnsaved(true);
    }

    /** Chunk has detonation {@code id}'s flash layer with fire, but no ruins yet. */
    public static boolean marked(LevelChunk chunk, int id) {
        return marks(chunk.getExistingData(ModAttachments.FLASH_LAYER).orElse(0), id);
    }

    /** {@code marker} (from chunk or disk data) belongs to detonation {@code id}'s layer. */
    static boolean marks(int marker, int id) {
        return id > 0 && marker == id;
    }

    /** Detonation {@code id}'s ruins applied: remove its layer mark (and older marks). */
    static void unmark(LevelChunk chunk, int id) {
        int was = chunk.getExistingData(ModAttachments.FLASH_LAYER).orElse(0);
        if (was == 0 || was > id) return;
        chunk.removeData(ModAttachments.FLASH_LAYER);
        chunk.setUnsaved(true);
    }

    /**
     * Chunk loaded with layer mark but no ruins will follow — detonation forgotten ({@link NuclearEvents}, neither active
     * nor past) — true: layer fire needs ticks ({@link #burnOut}, during world tick: ticks cannot yet be scheduled on load).
     * If already ruined, remove mark here too.
     */
    static boolean forgotten(LevelChunk chunk, NuclearEvents events) {
        int id = chunk.getExistingData(ModAttachments.FLASH_LAYER).orElse(0);
        if (id == 0) return false;
        if (chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) >= id) {
            unmark(chunk, id);
            return false;
        }
        return events.detonations().stream().noneMatch(e -> e.id() == id) && events.past().stream().noneMatch(e -> e.id() == id);
    }

    /**
     * The detonation whose layer is in the chunk will not produce ruins: schedule fire ticks as for placement
     * ({@code FireBlock.onPlace}: 30–39 ticks), remove mark (server thread, chunk fully in memory).
     */
    static void burnOut(ServerLevel level, LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        ChunkPos pos = chunk.getPos();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            if (s.hasOnlyAir() || !Palettes.contains(s.getStates(), st -> st.getBlock() instanceof BaseFireBlock)) continue;
            int y0 = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int k = 0; k < 4096; k++) {
                BlockState st = s.getBlockState(k & 15, k >> 8, (k >> 4) & 15);
                if (!(st.getBlock() instanceof BaseFireBlock)) continue;
                m.set(pos.getMinBlockX() + (k & 15), y0 + (k >> 8), pos.getMinBlockZ() + ((k >> 4) & 15));
                level.scheduleTick(m.immutable(), st.getBlock(), 30 + (int) (RuinPlanner.hash(m.getX(), m.getY(), m.getZ(), 31) * 10));
            }
        }
        chunk.removeData(ModAttachments.FLASH_LAYER);
        chunk.setUnsaved(true);
    }

    /**
     * For checks: marked chunk reloaded ({@link #forgotten}), detonation forgotten: schedule fire ticks immediately
     * ({@link #burnOut}). false — detonation still known or no mark.
     */
    public static boolean burnOutForTest(ServerLevel level, LevelChunk chunk) {
        if (!forgotten(chunk, NuclearEvents.get(level))) return false;
        burnOut(level, chunk);
        return true;
    }

    // ---------------------------------------------------------------- work

    /**
     * Light scorch reach, horizontal blocks from epicenter: ground scorch ({@link RuinPlanner#scorchReach}), fires (ignition
     * on slopes toward burst), face char (light at ball height); capped at ruin radius — no wave ruins beyond it.
     */
    static double reach(Detonation d) {
        double lo = 0, hi = d.ruinRadius();
        for (int i = 0; i < 40; i++) {
            double r = (lo + hi) / 2;
            Vec3 ground = new Vec3(d.burst().x + r, d.groundY() + 1, d.burst().z), high = new Vec3(d.burst().x + r, d.burst().y, d.burst().z);
            double q = d.fluence(ground) * 2.5;
            if (q >= ThermalModel.IGNITE || FlashChar.possible(d.fluence(high), FlashChar.CHARS) || FlashChar.possible(d.fluence(high), FlashChar.SOOTS)) lo = r;
            else hi = r;
        }
        return Math.max(lo, RuinPlanner.scorchReach(d));
    }

    /**
     * Work fitting within budget (server thread). Order: collect chunks, write ready layers whose time has come, submit
     * next layers to background. Log a chunk's failure and continue others.
     *
     * @return true — layer applied to all chunks (or no longer needed)
     */
    public boolean work(ServerLevel level, long now, WorkClock clock) {
        RuinContext ctx = NuclearWorld.get(level).ruins(level, d, now);
        if (order == null) {
            if (!clock.canStart()) return false;
            long t0 = clock.begin();
            list(level);
            nanos += clock.end(t0);
        }
        ScarQueue scars = NuclearWorld.get(level).scars();
        ColumnScar.Budget budget = fixedBudget != null ? fixedBudget : scars.budget(level, d);
        for (Iterator<Map.Entry<Integer, Build>> it = building.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Build> e = it.next();
            int k = e.getKey();
            Build b = e.getValue();
            if (!b.task.isDone()) {
                if (abandoned(b, k, now)) it.remove();
                continue;
            }
            if (due[k] > now) continue;
            if (!(seen[k] ? clock.canStartVisible(ScarQueue.SEEN_BUSY_NANOS) : clock.canStart())) return false;
            it.remove();
            long t0 = clock.begin();
            try {
                place(level, ctx, scars, k, b.task, budget, now);
            } catch (RuntimeException ex) {
                failed(k, ex);
            } finally {
                nanos += clock.end(t0);
            }
        }
        while (next < order.length) {
            int k = next;
            RuinWorkers.Priority priority = seen[k] ? RuinWorkers.Priority.SEEN : RuinWorkers.Priority.NEAR;
            if (!RuinWorkers.admit(priority) || !(seen[k] ? clock.canStartVisible(ScarQueue.SEEN_BUSY_NANOS) : clock.canStart())) return false;
            next++;
            long key = order[k];
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) {
                gone++;
                continue;
            }
            if (ruined(ctx, chunk)) {
                late++;
                continue;
            }
            // near Sable craft, writing reads neighbors: without them, wait for ruins when neighbors arrive (queue waits)
            if (SableTerrain.watched(level, chunk) && !NuclearTickets.neighbourhoodLoaded(level, chunk.getPos(), 1)) {
                waiting++;
                continue;
            }
            // snapshots and layer plans retain memory until the wave: no new ones beyond the limit or with a tight heap
            if (ctx.shotBytes() >= NuclearPrep.PLANS_CAP || heap.tight()) {
                skipped++;
                continue;
            }
            long t0 = clock.begin();
            try {
                RuinContext.Grid g = ctx.flashGrid(level, chunk.getPos(), true);
                building.put(k, new Build(RuinWorkers.submit(() -> {
                    RuinWorkers.checkStop();
                    return RuinPlanner.flash(g);
                }, priority)));
            } catch (RuntimeException ex) {
                failed(k, ex);
            } finally {
                nanos += clock.end(t0);
            }
        }
        if (!building.isEmpty()) return false;
        Incoming.LOG.info("Nuclear detonation #{}: flash scorch — {} of {} chunks ({} seen by players) in {} ticks (pulse {} ticks), {} ms on the server thread; "
                        + "{} blocks, {} fires lit; {} stale, {} after the wave, {} unloaded, {} at aircraft without neighbours, {} over the memory limit{}",
                d.id(), applied, order.length, seenCount, lastApplied < 0 ? 0 : lastApplied - d.gameTime(),
                String.format(java.util.Locale.ROOT, "%.1f", d.pulseTicks()), nanos / 1_000_000, blocks, fires, stale, late, gone, waiting, skipped,
                failures > 0 ? ", " + failures + " failed" : "");
        return true;
    }

    /** Cancel, stop: background layers no longer needed. */
    void cancel() {
        for (Build b : building.values()) b.task.cancel(false);
        building.clear();
    }

    /**
     * Background layer running over {@link RuinContext#TASK_AGE_LIMIT} ticks (from work start, not enqueue) is abandoned:
     * otherwise a stuck task would hold work (and {@link NuclearWorld#busy}) forever. Chunk gets everything with ruins.
     */
    private boolean abandoned(Build b, int k, long now) {
        if (!RuinWorkers.started(b.task)) {
            b.since = -1;
            return false;
        }
        if (b.since < 0) b.since = now;
        if (now - b.since < RuinContext.TASK_AGE_LIMIT) return false;
        b.task.cancel(true);
        RuinWorkers.expired();
        if (failures++ < 3) {
            Incoming.LOG.warn("Nuclear detonation #{}: flash scorch of chunk {} not ready in {} ticks — abandoned (the wave brings it with the ruins)",
                    d.id(), new ChunkPos(order[k]), RuinContext.TASK_AGE_LIMIT);
        }
        return true;
    }

    /** Chunk failed: log the first failures, continue other chunks; this one gets everything with ruins. */
    private void failed(int k, Throwable e) {
        if (failures++ < 3) Incoming.LOG.error("Nuclear detonation #{}: flash scorch of chunk {} failed", d.id(), new ChunkPos(order[k]), e);
    }

    /** Chunk already ruined (wave arrived before layer): no layer needed. */
    private boolean ruined(RuinContext ctx, LevelChunk chunk) {
        return ctx.applied(chunk.getPos().toLong()) != null || chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) >= d.id();
    }

    /** Write a ready layer to the chunk. */
    private void place(ServerLevel level, RuinContext ctx, ScarQueue scars, int k, Future<RuinPlan> task, ColumnScar.Budget budget, long now) {
        long key = order[k];
        RuinPlan plan;
        try {
            plan = task.get();
        } catch (java.util.concurrent.ExecutionException e) {
            failed(k, e.getCause());
            return;
        } catch (InterruptedException | java.util.concurrent.CancellationException e) {
            return;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
        if (chunk == null) {
            gone++;
            return;
        }
        if (ruined(ctx, chunk)) {
            late++;
            return;
        }
        // writing needs neighbors (nearby Sable craft, edge block entity), but absent: ruins wait for them themselves
        if (plan.waitsNeighbours(level, chunk) > 0) {
            waiting++;
            return;
        }
        int lit = plan.applyFlash(level, chunk, d, budget);
        if (lit < 0) {
            stale++;
            return;
        }
        applied++;
        blocks += plan.changedBlocks();
        fires += lit;
        lastApplied = now;
        // chunk ruins no longer need snapshot (plan ready or building): only neighbors would hold it until wave — retake it
        if (scars.hasPlan(ctx, d, key)) ctx.dropShot(key);
    }

    /**
     * Scorched chunks (fully in memory, within ruin radius), in order: player-visible by nearest-player distance,
     * others outward from epicenter; application time for each.
     */
    private void list(ServerLevel level) {
        LongArrayList in = new LongArrayList();
        if (only != null) {
            in.addElements(0, only);
        } else {
            double reach = reach(d);
            for (ChunkHolder holder : level.getChunkSource().chunkMap.getChunks()) {
                ChunkPos p = holder.getPos();
                // ruins only reach the ruin queue radius (ground range, as scorch): without them layer fire would never receive ticks
                if (NuclearPrep.horizontal(p, d) <= reach && ScarQueue.inRange(p, d) && level.getChunkSource().getChunkNow(p.x, p.z) != null) in.add(p.toLong());
            }
        }
        List<ServerPlayer> players = level.players();
        int n = in.size();
        double[] rank = new double[n];
        boolean[] watched = new boolean[n];
        for (int i = 0; i < n; i++) {
            ChunkPos p = new ChunkPos(in.getLong(i));
            double best = Double.MAX_VALUE;
            for (ServerPlayer player : players) {
                if (!player.getChunkTrackingView().contains(p)) continue;
                best = Math.min(best, Math.hypot(p.getMiddleBlockX() + 0.5 - player.getX(), p.getMiddleBlockZ() + 0.5 - player.getZ()));
            }
            watched[i] = best < Double.MAX_VALUE || ScarQueue.seenInTest(p.toLong());
            // visible before all invisible (offset greater than any world distance)
            rank[i] = watched[i] ? (best < Double.MAX_VALUE ? best : 0) : 1e9 + NuclearPrep.horizontal(p, d);
        }
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(rank[a], rank[b]));
        order = new long[n];
        due = new long[n];
        seen = new boolean[n];
        for (int j = 0; j < n; j++) {
            int i = idx[j];
            ChunkPos p = new ChunkPos(in.getLong(i));
            order[j] = p.toLong();
            seen[j] = watched[i];
            if (seen[j]) seenCount++;
            // light at the chunk's ground point nearest the ball: ignition threshold reached there before the rest of the chunk
            double x = Mth.clamp(d.burst().x, p.getMinBlockX(), p.getMaxBlockX() + 1), z = Mth.clamp(d.burst().z, p.getMinBlockZ(), p.getMaxBlockZ() + 1);
            due[j] = d.gameTime() + (long) Math.ceil(d.flashTicks(d.fluence(new Vec3(x, d.groundY() + 1, z)), ThermalModel.IGNITE));
        }
    }
}
