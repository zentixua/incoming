package ua.zentix.incoming.nuclear.world;

import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.work.WorkScheduler;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Background ruin-plan threads: fractures, collapse and completion from chunk snapshots ({@link ChunkShot}), costing
 * tens of ms per city chunk, plus parsing disk-read chunks ({@link DiskShots}), outside server tick. Own
 * pool instead of shared {@code Util.backgroundExecutor} (also loads/generates chunks).
 * <p>
 * Thread count: {@code ruin_threads}, 0 automatic: all cores except game reserve, {@code cores − 1 (server thread)
 * − 1 (own game: client render thread for integrated server) − 1 (vanilla: chunk I/O, generation, garbage
 * collector)}, at least one. This is an upper bound: solver-buffer memory also limits parallelism. Java thread priorities have no
 * effect on Linux, so only count and tick adaptation leave room for game: once per second, external tick
 * time over last second (tick minus mod lanes) above threshold ({@link #MARGIN_MS}) reduces active threads by
 * a quarter (at least one), below adds one (at most pool thread count). Chosen count and every
 * reduction/recovery logged.
 * <p>
 * At most {@link #capacity} tasks in pool (running and queued): queue keeps threads busy between ticks
 * (task 10–50 ms, tick 50 ms). Server thread submits tasks and collects results; background reads no world.
 * <p>
 * Queue ordered by importance ({@link Priority}): player-visible chunk ruins, other wave ruins, then
 * distant work (prebuilt disk ruins, distant LODs). {@link #capacity} counts each priority plus higher priorities separately:
 * distant tasks keep pool full while wave travels (15 kt trace ring ~200 thousand chunks); a shared count would
 * make player-chunk plan (stale ready plan or none) await distant tasks freeing slots, then stand
 * behind them in queue.
 * <p>
 * Memory: each thread has solver buffers ({@code ThreadLocal}: {@code Collapse.Buffers} contains {@code before},
 * {@code after}, {@code ring}; {@code Blast.Buffers} contains {@code sky}, {@code gone}), 48×48 window through full world height;
 * {@link #bufferBytes} counts them toward ruin-preparation memory cap.
 * <p>
 * Server/world stop closes pool ({@code shutdownNow}); running tasks finish into void, nobody collects
 * results. Daemon threads: game exit does not await them.
 */
public final class RuinWorkers {
    /**
     * External tick time (server tick minus mod lanes, {@link WorkScheduler#ownNanos}) above {@code 50 − work_ms_per_tick +}
     * {@link #MARGIN_MS} reduces active threads: server cannot hold 20 TPS even with mod lanes
     * using exactly their budget; below {@code 50 − work_ms_per_tick −} {@link #MARGIN_MS} increases them. Own lanes do not
     * count: budgeted by design; full tick (41–49 ms, up to 30 nuke) would reduce pool to one thread
     * while nuclear lane has work, forcing on-site plans.
     */
    static final double MARGIN_MS = 5;
    private static final double TICK_MS = 50;
    /** Adapt every this many ticks. */
    private static final int ADAPT_EVERY = 20;
    /** Pool tasks per active thread: queue one tick ahead. */
    private static final int QUEUE_PER_THREAD = 4;

    /** Pool queue priorities: earlier means more important. */
    public enum Priority {
        /** Player-visible chunk ruins (ruin queue): background plan and disk parsing of window neighbors. */
        SEEN,
        /** Other queued ruins and prebuilt ruins for in-memory chunks. */
        NEAR,
        /** Prebuilt disk ruins (heavy zone outside memory), distant LOD copies ({@link FarLods}). */
        FAR
    }

    private static final AtomicInteger NUMBER = new AtomicInteger();
    /** Same-priority task order follows submission. */
    private static final AtomicLong SEQUENCE = new AtomicLong();
    /**
     * Pool tasks per priority (running/queued). Separate per pool lifetime until {@link #shutdown}:
     * task missing shutdown counts toward its own pool lifetime, not next world.
     */
    private static volatile AtomicIntegerArray IN_FLIGHT = new AtomicIntegerArray(Priority.values().length);
    @Nullable
    private static ThreadPoolExecutor pool;
    /** Memory-bounded thread count and currently active count (tick adaptation). */
    private static int size, active;
    /** Split the shared cap between solver workspace and retained inputs/results; transition room stays free. */
    static final long BUFFER_CAP = (NuclearPrep.PLANS_CAP - RuinMemory.TRANSITION_ROOM) / 2;
    /** Highest dimension seen this pool lifetime: ThreadLocal arrays do not shrink between tasks. */
    private static int bufferHeight = 384;
    /** Thread busy time since pool start, ns (thread utilization in summary). */
    private static final AtomicLong BUSY = new AtomicLong();
    private static long startedAt;
    private static long lastAdapt = -ADAPT_EVERY;
    /** Reductions/recoveries since pool start; stuck tasks. */
    private static int lowered, raised, expired;

    private RuinWorkers() {}

    /** Cores reserved for game: server thread, integrated-server client rendering, vanilla. */
    static int reserve(boolean integrated) {
        return 1 + (integrated ? 1 : 0) + 1;
    }

    /** Configured thread count: 0 all cores minus game reserve ({@link #reserve}). */
    static int threads(boolean integrated) {
        int n = IncomingConfig.SERVER.nukeRuinThreads.get();
        if (n > 0) return n;
        return Math.max(1, Runtime.getRuntime().availableProcessors() - reserve(integrated));
    }

    /** Game with client (single-player, LAN world): own core for rendering. GameTest/dedicated server have no client. */
    private static boolean integrated() {
        return net.neoforged.fml.loading.FMLEnvironment.dist.isClient();
    }

    private static ThreadPoolExecutor pool() {
        boolean integrated = integrated();
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) for (var level : server.getAllLevels()) configureHeight(level.getHeight());
        int desired = threads(integrated);
        int n = memoryThreads(desired, bufferHeight);
        if (pool != null && size == n) return pool;
        if (pool != null) {
            // Resize the same lifetime: queued work and its captured snapshots must not drain in a
            // second pool outside adaptation, capacity and buffer accounting.
            resize(pool, n);
            size = active = n;
            return pool;
        }
        size = active = n;
        lowered = raised = 0;
        pool = newPool(n);
        startedAt = System.nanoTime();
        BUSY.set(0);
        int manual = IncomingConfig.SERVER.nukeRuinThreads.get();
        Incoming.LOG.info("Ruins: background threads {} (desired {}; {}; cores {}, left to the game {}{})", n, desired,
                manual > 0 ? "from the ruin_threads setting" : "automatic", Runtime.getRuntime().availableProcessors(),
                manual > 0 ? 0 : reserve(integrated), integrated ? ", integrated server" : "");
        return pool;
    }

    static ThreadPoolExecutor newPool(int threads) {
        var executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new PriorityBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "Incoming ruins #" + NUMBER.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /** Tasks held in pool at once (running/queued). */
    static int capacity() {
        if (pool == null) pool();
        return Math.max(2, active * QUEUE_PER_THREAD);
    }

    /**
     * Whether another {@code priority} task admitted: pool tasks at its priority and higher below {@link #capacity}.
     * Lower priorities excluded from count: queue puts this task before them.
     */
    static boolean admit(Priority priority) {
        return inFlight(priority) < capacity();
    }

    /** Pool tasks at {@code priority} and higher (running/queued). */
    static int inFlight(Priority priority) {
        AtomicIntegerArray counts = IN_FLIGHT;
        int n = 0;
        for (int i = 0; i <= priority.ordinal(); i++) n += counts.get(i);
        return n;
    }

    /** Total pool tasks (running/queued). */
    static int inFlight() {
        return inFlight(Priority.FAR);
    }

    /**
     * For tests: fill pool with {@code priority} tasks waiting on {@code release} until it no longer
     * admits that priority ({@link #admit}).
     */
    public static void fillForTest(java.util.concurrent.CountDownLatch release, Priority priority) {
        while (admit(priority)) {
            submit(() -> {
                release.await();
                return null;
            }, priority);
        }
    }

    /**
     * For tests: wait (blocking server thread, at most {@code millis}) until no pool tasks at
     * {@code priority} or higher.
     *
     * @return wait succeeded
     */
    public static boolean awaitForTest(Priority priority, long millis) {
        long until = System.nanoTime() + millis * 1_000_000L;
        while (inFlight(priority) > 0) {
            if (System.nanoTime() > until) return false;
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
        return true;
    }

    /** For tests: submit task at {@code priority} ({@link #submit}). */
    public static <T> Future<T> submitForTest(Callable<T> task, Priority priority) {
        return submit(task, priority);
    }

    /** For tests: {@link #promote}. */
    @Nullable
    public static <T> Future<T> promoteForTest(Future<T> task, Priority priority) {
        return promote(task, priority);
    }

    /** For tests: active pool threads. */
    public static int threadsForTest() {
        if (pool == null) pool();
        return active;
    }

    /** Submit task at {@code priority} (server thread). */
    static <T> Future<T> submit(Callable<T> task, Priority priority) {
        ThreadPoolExecutor executor = pool();
        Task<T> t = new Task<>(task, priority, IN_FLIGHT, executor);
        execute(executor, t);
        return t;
    }

    /**
     * Executor at {@code priority}: disk-chunk parsing (I/O thread submits into pool), distant LOD copies.
     * Current pool (server thread): task arriving after shutdown rejected, never starts new pool from external thread.
     */
    static Executor executor(Priority priority) {
        ThreadPoolExecutor p = pool();
        AtomicIntegerArray counts = IN_FLIGHT;
        // Capture accounting with the executor, not when a late I/O callback submits in the next world.
        return r -> execute(p, new Task<>(java.util.concurrent.Executors.callable(r, null), priority, counts, p));
    }

    private static void execute(ThreadPoolExecutor p, Task<?> t) {
        try {
            p.execute(t);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // pool closed (shutdown): task absent, so not counted
            t.cancel(false);
            t.uncount();
            throw e;
        }
    }

    /**
     * Promote still-queued task to higher priority (server thread): same work at new queue
     * position. Otherwise a chunk whose preparation submitted its plan at far priority would await all player-chunk
     * tasks when wave reaches it.
     *
     * @return task at new priority; null if started, already at least that priority, or not from this pool: unchanged
     */
    @Nullable
    static <T> Future<T> promote(Future<T> future, Priority priority) {
        ThreadPoolExecutor p = pool;
        if (p == null || !(future instanceof Task<T> t) || t.owner != p || t.isDone()
                || t.priority <= priority.ordinal() || !p.remove(t)) return null;
        // The removed future is terminal and releases its captured callable; only the new task owns it.
        Callable<T> callable = t.callable;
        t.cancel(false);
        t.uncount();
        if (callable == null) return null; // cancelled concurrently after removal
        Task<T> moved = new Task<>(callable, priority, t.counts, p);
        try {
            execute(p, moved);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // shutdown: abandoned like queued tasks
            t.cancel(false);
            return null;
        }
        return moved;
    }

    /** Pool task already started (or not a pool task): background-plan deadline measured from work start. */
    static boolean started(Future<?> future) {
        return !(future instanceof Task<?> t) || t.started;
    }

    /**
     * Pool task: queue by priority, within priority by submission; priority count from submission to
     * actual completion; cancelled queued tasks release their slot immediately. Running cancellation
     * retains the slot until the worker exits, even though Future.isDone() is already true.
     */
    static final class Task<T> extends FutureTask<T> implements Comparable<Task<?>> {
        private volatile Callable<T> callable;
        private final int priority;
        private final long sequence;
        private final AtomicIntegerArray counts;
        @Nullable private final ThreadPoolExecutor owner;
        private final AtomicBoolean counted = new AtomicBoolean(true);
        private volatile boolean started;

        Task(Callable<T> task, Priority priority) {
            this(task, priority, IN_FLIGHT, null);
        }

        Task(Callable<T> task, Priority priority, AtomicIntegerArray counts, @Nullable ThreadPoolExecutor owner) {
            super(task);
            this.callable = task;
            this.priority = priority.ordinal();
            this.sequence = SEQUENCE.getAndIncrement();
            this.counts = counts;
            this.owner = owner;
            counts.incrementAndGet(this.priority);
        }

        @Override
        public void run() {
            started = true;
            long t0 = System.nanoTime();
            try {
                super.run();
            } finally {
                BUSY.addAndGet(System.nanoTime() - t0);
                uncount();
            }
        }

        @Override
        protected void done() {
            callable = null;
            // Running cancellation is not completion: retain its slot until run() actually exits.
            // A queued cancellation, however, must immediately free queue ownership and snapshots.
            if (owner != null && owner.remove(this)) uncount();
        }

        private void uncount() {
            if (counted.compareAndSet(true, false)) counts.decrementAndGet(priority);
        }

        @Override
        public int compareTo(Task<?> o) {
            return priority != o.priority ? Integer.compare(priority, o.priority) : Long.compare(sequence, o.sequence);
        }
    }

    /**
     * Tick adaptation (server thread, each tick; active once per {@link #ADAPT_EVERY}): average external tick time
     * over last second (server tick minus mod lanes) above {@code 50 − work_ms_per_tick +} {@link #MARGIN_MS}
     * reduces active threads by quarter; below {@code 50 − work_ms_per_tick −} {@link #MARGIN_MS} adds one.
     * No action while pool unused.
     */
    public static void adapt(MinecraftServer server) {
        ThreadPoolExecutor p = pool;
        if (p == null) return;
        int tick = server.getTickCount();
        if (tick >= lastAdapt && tick - lastAdapt < ADAPT_EVERY) return;
        lastAdapt = tick;
        if (inFlight() == 0 && active == size) return;
        long[] times = server.getTickTimesNanos();
        long sum = 0;
        int n = Math.min(ADAPT_EVERY, times.length);
        for (int i = 1; i <= n; i++) sum += Math.max(0, times[Math.floorMod(tick - i, times.length)] - WorkScheduler.ownNanos(tick - i));
        double ms = sum / (double) n / 1e6;
        double room = TICK_MS - IncomingConfig.SERVER.workBudgetMs.get();
        int now = active;
        if (ms > room + MARGIN_MS && active > 1) {
            active = Math.max(1, active - Math.max(1, active / 4));
            lowered++;
        } else if (ms < room - MARGIN_MS && active < size) {
            active++;
            raised++;
        }
        if (active == now) return;
        resize(p, active);
        Incoming.LOG.info("Ruins: background threads working {} of {} (server tick without mod lanes {} ms)", active, size, String.format(java.util.Locale.ROOT, "%.1f", ms));
    }

    static void resize(ThreadPoolExecutor executor, int threads) {
        if (threads < executor.getCorePoolSize()) {
            executor.setCorePoolSize(threads);
            executor.setMaximumPoolSize(threads);
        } else {
            executor.setMaximumPoolSize(threads);
            executor.setCorePoolSize(threads);
        }
    }

    /** Background plan abandoned: not ready by deadline ({@link RuinContext#TASK_AGE_LIMIT}). */
    static void expired() {
        expired++;
    }

    /** For “ready” line: threads, active, reductions, recoveries, abandoned tasks. */
    static String summary() {
        return size + " (working " + active + ", lowered " + lowered + ", raised " + raised + ", tasks abandoned " + expired + ")";
    }

    /** Full-height solver workspace estimate, including an uncompressed property reference (15 bytes/cell). */
    static long threadBufferBytes(int height) {
        return (long) RuinWindow.SIDE * RuinWindow.SIDE * height * 15;
    }

    static int memoryThreads(int desired, int height) {
        return (int) Math.max(1, Math.min(desired, BUFFER_CAP / threadBufferBytes(height)));
    }

    /** Observe all server dimension heights before admission, including before a pool exists. */
    static void configureHeight(int height) {
        bufferHeight = Math.max(bufferHeight, height);
        if (pool == null) return;
        int bounded = memoryThreads(threads(integrated()), bufferHeight);
        if (bounded == size) return;
        size = bounded;
        active = Math.min(active, size);
        resize(pool, active);
    }

    /** Future admitted workers plus still-live downsized threads; never reserve the raw configured count. */
    static long bufferBytes(int height) {
        configureHeight(height);
        return bufferBytes(pool, threads(integrated()), bufferHeight);
    }

    static long bufferBytes(@Nullable ThreadPoolExecutor executor, int desired, int height) {
        return (long) Math.max(memoryThreads(desired, height), executor == null ? 0 : executor.getPoolSize())
                * threadBufferBytes(height);
    }

    /**
     * Conservative buffer estimate for currently live pool threads. Core threads expire after 30 idle seconds;
     * using configured size here would permanently stop settlement on large-core machines even after they exit.
     */
    static long residentBufferBytes(int height) {
        configureHeight(height);
        return residentBufferBytes(pool, bufferHeight);
    }

    static long residentBufferBytes(@Nullable ThreadPoolExecutor executor, int height) {
        return executor == null ? 0 : (long) executor.getPoolSize() * threadBufferBytes(height);
    }

    /** Fraction of thread time busy since pool start (for “ready” line). */
    static double utilisation() {
        if (pool == null) return 0;
        long wall = System.nanoTime() - startedAt;
        return wall <= 0 ? 0 : Math.min(1, BUSY.get() / (double) wall / size);
    }

    /** Wait for running tasks to notice shutdown (task checks between plan stages, tens of ms). */
    private static final long JOIN_MS = 3000;

    /**
     * Server stop (and tests): abandon queued tasks, interrupt running ones ({@code shutdownNow}: task
     * checks interruption between plan stages, {@link #checkStop}); server thread waits at most {@link #JOIN_MS};
     * log if unfinished (daemon threads, so game exit still does not await them).
     *
     * @return no threads remain (or no pool existed)
     */
    public static boolean shutdown() {
        ThreadPoolExecutor p = pool;
        pool = null;
        lastAdapt = -ADAPT_EVERY;
        bufferHeight = 384;
        size = active = 0;
        if (p == null) return true;
        java.util.List<Runnable> queued = p.shutdownNow();
        // run will not be called: remove from priority counts
        for (Runnable r : queued) if (r instanceof Task<?> t) {
            t.cancel(false);
            t.uncount();
        }
        IN_FLIGHT = new AtomicIntegerArray(Priority.values().length);
        int dropped = queued.size();
        boolean done;
        try {
            done = p.awaitTermination(JOIN_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            done = false;
        }
        if (done) Incoming.LOG.info("Ruins: background threads stopped (queued tasks dropped: {})", dropped);
        else Incoming.LOG.warn("Ruins: background threads did not stop within {} ms — still working {}", JOIN_MS, p.getActiveCount());
        return done;
    }

    /** Pool closed and all its threads gone (tests). */
    public static boolean stopped() {
        return pool == null;
    }

    /** Shutdown or abandoned task (interruption): stop building plan. */
    static void checkStop() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("ruins: stopping");
    }
}
