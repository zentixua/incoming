package ua.zentix.incoming.nuclear.world;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.flash.FlashShadows;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.radiation.MobFallout;
import ua.zentix.incoming.nuclear.model.CraterModel;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Live nuclear processes for a dimension: shock front on entities/craft; light pulse
 * and prompt radiation on entities, chunk damage queue, craters and mob fallout under shared time budget
 * (one per server tick across dimensions: {@link #clock(MinecraftServer)}).
 * No heavy work on detonation tick: snapshots (entities/chunks in range) and actual work all budgeted.
 * Unsaved: derived from {@link NuclearEvents} (detonations, crater progress) and chunk marks;
 * entity pulse not repeated after restart (lasts fractions of second), front continues from previous position.
 */
public final class NuclearWorld {
    /** Minimum interval between slow nuclear-tick logs, ticks. */
    private static final int SLOW_LOG_PERIOD = 100;

    private final ScarQueue scars = new ScarQueue();
    /** Detonation ruins by ID ({@link RuinContext}) while detonation has queued work. */
    private final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<RuinContext> ruins = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
    /**
     * Idle-ruin cleanup interval and retention, ticks (work means plan
     * or replacement; chunks awaiting neighbors do not count: world edge may await player for hours, detonation ruins take
     * tens of MB). Chunk whose neighbors arrive later builds ruins with new context.
     */
    private static final int RUINS_SWEEP = 100, RUINS_IDLE = 600;
    private final NuclearPrep prep = new NuclearPrep();
    private final List<PulseJob> pulses = new ArrayList<>();
    /** Flash silhouettes per detonation ({@link FlashShadows}): bodies snapshotted at detonation tick. */
    private final List<FlashShadows> shadows = new ArrayList<>();
    /** Ground light at flash ({@link FlashScorch}): flash layer for in-memory chunks, before wave. */
    private final List<FlashScorch> scorches = new ArrayList<>();
    private final List<CraterJob> craters = new ArrayList<>();
    private final MobFallout mobFallout = new MobFallout();
    private final BlastFront blast = new BlastFront();
    private long lastFrontNanos, lastPulseNanos, lastScorchNanos, lastCraterNanos, lastScarNanos, lastPrepNanos, lastFalloutNanos;
    /**
     * Chunks loaded since with flash-layer fire from forgotten detonation (no ruins coming): schedule fire ticks on world tick
     * ({@link FlashScorch#burnOut}; no ticks scheduled during chunk load).
     */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet unburnt = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    /** Unfinished saved craters resumed (after world load). */
    private boolean restored;
    /** World game time of last slow-tick log. */
    private long lastSlowLog = Long.MIN_VALUE / 2;

    /** For {@link ModAttachments#NUCLEAR_WORLD}: per world, lives while loaded, unsaved. */
    public NuclearWorld() {}

    /** Ruin queue (tests). */
    public ScarQueue scars() {
        return scars;
    }

    /** Chunk ruins about to appear: nearby bodies remember window/wall as front will find them. */
    void beforeRuins(ServerLevel level, Detonation d, net.minecraft.world.level.chunk.LevelChunk chunk) {
        blast.remember(level, d, chunk);
    }

    /** Chunk ruins buried body: queue burial injury with wave hits. */
    void buried(Detonation d, net.minecraft.world.entity.LivingEntity e) {
        blast.bury(d, e);
    }

    /** Ruin detonation geometry: preparation location if prepared ({@link NuclearPrep#planned}), otherwise {@code fresh}. */
    public Detonation planned(Detonation fresh) {
        return prep.planned(fresh);
    }

    /**
     * For tests: heavy-zone LOD copies for {@code strike} front while preparation alive: built, sent, late,
     * send minus front arrival (minimum/maximum), loaded, evicted, front early, build failures,
     * nothing to show, awaiting send, bytes, reading, awaiting read; null without preparation.
     */
    @Nullable
    public long[] frontLodStats(int strike) {
        return prep.frontStats(strike);
    }

    /** Prebuilt ruins: ready plans also needed by distant LODs ({@link FarLods}). */
    NuclearPrep prep() {
        return prep;
    }

    public static NuclearWorld get(ServerLevel level) {
        return level.getData(ModAttachments.NUCLEAR_WORLD);
    }

    /** Detonation ruins across chunks, shared by preparation (after detonation), queue and tests. */
    RuinContext ruins(ServerLevel level, Detonation d, long now) {
        RuinContext ctx = ruins.get(d.id());
        if (ctx == null) ruins.put(d.id(), ctx = new RuinContext(level, d));
        ctx.used = now;
        return ctx;
    }

    /** Detonation ruins if still in memory (does not create). */
    @Nullable
    RuinContext ruinsIfKept(int detonation) {
        return ruins.get(detonation);
    }

    /** For tests: forget detonation ruins as after idle/restart (flash layer remains only chunk mark). */
    public void forgetRuinsForTest(int detonation) {
        RuinContext ctx = ruins.remove(detonation);
        if (ctx != null) ctx.cancelTasks();
    }

    /** Unused for long and no work except waiting neighbors: forget (fracture/old ruin-block memory). */
    private void sweepRuins(long now) {
        if (now % RUINS_SWEEP != 0) return;
        ruins.int2ObjectEntrySet().removeIf(e -> {
            if (now - e.getValue().used <= RUINS_IDLE || scars.pending(e.getIntKey())) return false;
            e.getValue().cancelTasks();
            return true;
        });
        // no ruins/preparation: release traversal buffers (tall city window tens of MB)
        if (ruins.isEmpty() && prep.idle()) releaseBuffers();
    }

    private static void releaseBuffers() {
        Collapse.releaseBuffers();
        Blast.releaseBuffers();
    }

    /**
     * Whether nuclear queues have server-thread work (NUCLEAR lane {@code WorkScheduler}: while present, impacts
     * do not take entire shared budget).
     */
    public boolean busy() {
        return !pulses.isEmpty() || !shadows.isEmpty() || !scorches.isEmpty() || !craters.isEmpty() || scars.size() > 0 || !prep.idle() || blast.busy();
    }

    /** Ruin-queue jobs inspected last tick (budget tests). */
    public int scarInspectedLastTick() {
        return scars.inspectedLastTick();
    }

    public int queuedChunks() {
        return scars.size();
    }

    /** Prebuilt ruins: ready count across flying strikes. */
    public int plannedChunks() {
        return prep.plannedChunks();
    }

    /** Chunk squares held by ruin preparation. */
    public int prepTiles() {
        return prep.heldTiles();
    }

    /** For tests: stop prebuilding ruins, as under memory pressure. */
    public void stopPrepForHeap() {
        prep.stopAllForHeap();
    }

    /** Ready-plan, on-site and stale-plan counts. */
    public int[] ruinStats() {
        return scars.ruinStats();
    }

    /** Queued chunks holding neighbor ticket (tests). */
    public long[] scarHolds() {
        return scars.heldChunks();
    }

    public int craterJobs() {
        return craters.size();
    }

    /** Detonations whose entity light pulse still runs. */
    public int pulseJobs() {
        return pulses.size();
    }

    /**
     * Last-tick times for front on entities/craft, light/radiation (including flash silhouettes), ground flash layer,
     * craters, ruin queue, prebuilt ruins and mob fallout, ns.
     */
    public long[] lastNanos() {
        return new long[]{lastFrontNanos, lastPulseNanos, lastScorchNanos, lastCraterNanos, lastScarNanos, lastPrepNanos, lastFalloutNanos};
    }

    /** Last-tick ruin queue: “inspected N, waited M” (slow nuclear-tick line). */
    public String scarSummary() {
        return scars.tickSummary();
    }

    /**
     * Nuclear work-budget clock shared by server: common-budget nuclear deadline per server tick, not
     * dimension. Lives in overworld (like vanilla shared server data), present throughout server lifetime.
     */
    public static WorkClock clock(MinecraftServer server) {
        return server.overworld().getData(ModAttachments.NUCLEAR_CLOCK);
    }

    /** Replace budget clock (counting clock for tests, {@link WorkClock#counting}). */
    public static void useClock(MinecraftServer server, WorkClock clock) {
        server.overworld().setData(ModAttachments.NUCLEAR_CLOCK, clock);
    }

    /** Slow tick logged at most every 5 s (tools/logscan.py): true if due, updates mark. */
    public boolean slowLogDue(long now) {
        if (now - lastSlowLog < SLOW_LOG_PERIOD) return false;
        lastSlowLog = now;
        return true;
    }

    // ---------------------------------------------------------------- events

    /**
     * Detonation: process entities in light/radiation range and currently loaded chunks in destruction range
     * under budget, including snapshots (later-loaded chunks handled on load). Nothing heavy on detonation tick.
     *
     * @param owner launcher (credited for damage)
     */
    public void onDetonation(ServerLevel level, Detonation d, @Nullable UUID owner) {
        pulses.add(new PulseJob(level, d, owner));
        // snapshot bodies before walls now: pulse (PulseJob) and wave later burn, kill or move them
        FlashShadows flash = FlashShadows.of(level, d);
        if (flash != null) shadows.add(flash);
        // ground light during pulse, before wave: burned ground, scorch, fires (chunks collected by first work unit)
        scorches.add(new FlashScorch(d));
        blast.onDetonation(d, owner);
        NuclearPrep.Handoff ready = prep.handOff(level, d, level.getGameTime());
        if (ready != null) {
            // prebuilt and on-site ruins identical: preparation fractures/applied ruins pass to detonation
            ready.ruins().used = level.getGameTime();
            ruins.put(d.id(), ready.ruins());
            scars.scanLoaded(d, ready.plans(), ready.order());
        }
        else scars.scanLoaded(d);
        // distant ground trace into DH LODs: beyond heavy zone (its LODs use ready-plan ruins), or all if unprepared
        FarLods.scorch(level, d, ready != null);
        if (d.surface() && IncomingConfig.SERVER.nukeCrater.get() && IncomingConfig.SERVER.nukeBlockDamage.get()
                && CraterModel.formsCrater(d.hobMetres(), d.yieldKt())) {
            craters.add(new CraterJob(d, 0));
            NuclearEvents.get(level).craterProgress(d.id(), 0, false);
        }
    }

    public void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        // front LOD copy unnecessary: world supplies chunk ruins/LODs
        prep.loaded(chunk.getPos().toLong());
        NuclearEvents events = NuclearEvents.get(level);
        for (Detonation d : events.past()) scars.offer(chunk, d);
        for (Detonation d : events.detonations()) scars.offer(chunk, d);
        // flash-layer fire waits for ruins without ticking: forgotten detonation means no ruins, time for fire to burn
        if (FlashScorch.forgotten(chunk, events)) unburnt.add(chunk.getPos().toLong());
    }

    public void onChunkUnload(ServerLevel level, LevelChunk chunk) {
        scars.drop(level, chunk.getPos());
        // layer mark saved with chunk: reevaluate next load
        unburnt.remove(chunk.getPos().toLong());
        // unloaded chunk's background plans: snapshots differ from chunk that will reload
        for (RuinContext ctx : ruins.values()) ctx.forget(chunk.getPos().toLong());
        prep.forget(chunk.getPos().toLong());
    }

    /** Chunk sent to player (detonation summary: sent before ruins). */
    public void onChunkSent(ServerLevel level, LevelChunk chunk) {
        scars.sent(chunk.getPos().toLong(), level.getGameTime());
    }

    /** Withhold player chunk until wave-reached ruins applied ({@code PlayerChunkSenderMixin}). */
    public boolean withholds(ServerLevel level, long chunk) {
        return scars.withholds(chunk, level.getGameTime());
    }

    /** Whether any chunks withheld (fast check). */
    public boolean mayWithhold() {
        return scars.mayWithhold();
    }

    /**
     * Server stop (leaving single-player): abandon all ruin work, preparation (disk reads, squares behind
     * wave), queues, background plans; stop/await background threads ({@link RuinWorkers#shutdown}):
     * after exit no ruin computation or retained memory. None of this saved anyway.
     * Release zone/preparation tickets here before vanilla unload loop (also removes all tickets except
     * {@code UNKNOWN}, but only within its loops, and does not stop disk reads/ruin tasks). Generation
     * already started by tickets then completed by {@link ua.zentix.incoming.util.StopDrain}.
     *
     * @return background threads stopped within deadline
     */
    public static boolean onServerStopping(net.minecraft.server.MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            if (!level.hasData(ModAttachments.NUCLEAR_WORLD)) continue;
            try {
                get(level).clear(level);
            } catch (RuntimeException e) {
                Incoming.LOG.error("Ruins: shutdown in {} failed with an exception", level.dimension().location(), e);
            }
        }
        return RuinWorkers.shutdown();
    }

    /** Cancel: queues stopped (destruction not restored). */
    public void clear(ServerLevel level) {
        // preparation before FarLods: copies return claimed requests there, to be removed with its queues
        prep.clear(level, scars);
        FarLods.get(level).clear(level);
        scars.clear(level);
        ruins.values().forEach(RuinContext::cancelTasks);
        ruins.clear();
        pulses.clear();
        shadows.clear();
        scorches.forEach(FlashScorch::cancel);
        scorches.clear();
        unburnt.clear();
        craters.forEach(c -> c.release(level));
        craters.clear();
        blast.clear();
        mobFallout.clear();
        releaseBuffers();
    }

    // ---------------------------------------------------------------- tick

    /** Let forgotten detonations' flash-layer fire burn (chunks no longer fully loaded wait for next tick). */
    private void burnOut(ServerLevel level) {
        for (var it = unburnt.longIterator(); it.hasNext(); ) {
            long key = it.nextLong();
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) continue;
            it.remove();
            try {
                FlashScorch.burnOut(level, chunk);
            } catch (RuntimeException e) {
                Incoming.LOG.error("Flash scorch: fires of chunk {} could not be scheduled", new ChunkPos(key), e);
            }
        }
    }

    /** @param clock server-tick budget, shared across dimensions (already started) */
    public void tick(ServerLevel level, WorkClock clock) {
        long now = level.getGameTime();
        NuclearEvents events = NuclearEvents.get(level);
        if (!restored) restore(events);
        sweepRuins(now);
        // background plans: stuck ones fall back to server thread; active threads follow server tick
        for (RuinContext ctx : ruins.values()) ctx.expire(now);
        RuinWorkers.adapt(level.getServer());
        // light before wave: faster; wave-killed bodies already received pulse
        long pulseStart = System.nanoTime();
        while (!pulses.isEmpty()) {
            PulseJob job = pulses.getFirst();
            try {
                if (!job.work(level, clock)) break;
            } catch (RuntimeException e) {
                Incoming.LOG.error("Thermal pulse of detonation #{} failed with an exception; dropped", job.detonation().id(), e);
            }
            pulses.removeFirst();
        }
        // flash silhouettes after pulse (damage more important than visuals), before wave: ruins at body position do not await them
        while (!shadows.isEmpty()) {
            FlashShadows job = shadows.getFirst();
            try {
                if (!job.work(level, clock)) break;
            } catch (RuntimeException e) {
                Incoming.LOG.error("Flash shadows of detonation #{} failed with an exception; dropped", job.detonation().id(), e);
            }
            shadows.removeFirst();
        }
        long frontStart = System.nanoTime();
        lastPulseNanos = frontStart - pulseStart;
        try {
            blast.advance(level, events.detonations(), now);
            blast.work(level, clock, id -> pulses.stream().noneMatch(p -> p.detonation().id() == id));
        } catch (RuntimeException e) {
            // retain front reach, otherwise everyone inside hit again
            Incoming.LOG.error("Blast wave on entities failed with an exception; queued hits dropped", e);
            blast.dropHits();
        }
        lastFrontNanos = System.nanoTime() - frontStart;
        if (now % 1200 == 0) scars.retainBudgets(events.detonations().stream().map(Detonation::id).collect(java.util.stream.Collectors.toSet()));
        long craterStart = System.nanoTime();
        digCraters(level, events, clock);
        long scarStart = System.nanoTime();
        lastCraterNanos = scarStart - craterStart;
        scars.work(level, now, clock);
        long scorchStart = System.nanoTime();
        lastScarNanos = scorchStart - scarStart;
        // ground light after wave-reached ruins using their remaining budget: when front crosses
        // many chunks per tick, distant flash layers prioritized ahead delayed front ruins for ticks (25 tick units
        // to layers, none to ruins). Wave-reached layer unnecessary: ruins apply everything
        // (built from pre-flash world). Detonations progress independently: second layer does not await first
        // detonation's background plans
        for (java.util.Iterator<FlashScorch> it = scorches.iterator(); it.hasNext(); ) {
            FlashScorch job = it.next();
            try {
                if (job.work(level, now, clock)) it.remove();
            } catch (RuntimeException e) {
                Incoming.LOG.error("Flash scorch of detonation #{} failed with an exception; dropped (the wave brings it with the ruins)",
                        job.detonation().id(), e);
                job.cancel();
                it.remove();
            }
        }
        if (!unburnt.isEmpty()) burnOut(level);
        long prepStart = System.nanoTime();
        lastScorchNanos = prepStart - scorchStart;
        // prebuilt ruins use remaining budget: already traveling wave matters more
        try {
            prep.tick(level, events, scars, clock);
        } catch (RuntimeException e) {
            Incoming.LOG.error("Ruin preparation failed with an exception; dropped", e);
            prep.clear(level, scars);
        }
        long falloutStart = System.nanoTime();
        lastPrepNanos = falloutStart - prepStart;
        try {
            mobFallout.work(level, events.detonations(), clock);
        } catch (RuntimeException e) {
            Incoming.LOG.error("Mob fallout failed with an exception; pass reset", e);
            mobFallout.clear();
        }
        lastFalloutNanos = System.nanoTime() - falloutStart;
    }

    /**
     * Crater jobs in turn while budget permits. A failed crater removed alone (with tickets);
     * saved progress remains, so restart resumes digging from same chunk.
     */
    private void digCraters(ServerLevel level, NuclearEvents events, WorkClock clock) {
        while (!craters.isEmpty() && clock.canStart()) {
            CraterJob job = craters.getFirst();
            CraterJob.Step s;
            long u0 = clock.begin();
            try {
                s = job.step(level, level.random);
            } catch (RuntimeException e) {
                Incoming.LOG.error("Crater of detonation #{} failed with an exception; dropped", job.detonation().id(), e);
                job.release(level);
                craters.removeFirst();
                continue;
            } finally {
                clock.end(u0);
            }
            events.craterProgress(job.detonation().id(), job.progress(), s == CraterJob.Step.DONE);
            if (s == CraterJob.Step.DONE) craters.removeFirst();
            else if (s == CraterJob.Step.WAIT) break;
        }
    }

    /** After world load: resume unfinished craters from stopped chunk. */
    private void restore(NuclearEvents events) {
        restored = true;
        events.craters().forEach((id, done) -> events.detonations().stream().filter(d -> d.id() == id).findFirst()
                .ifPresent(d -> craters.add(new CraterJob(d, done))));
    }
}
