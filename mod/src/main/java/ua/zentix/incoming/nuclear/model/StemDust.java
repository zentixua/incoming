package ua.zentix.incoming.nuclear.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Mushroom stem dust is drawn near the epicenter from the blast dust layer ({@link DustModel}: share
 * {@link DustModel.Column#stem}, from {@link DustModel.Column#stemFrom}, e-folding over {@link DustModel#STEM_S} s).
 * Only dust lofted from ground by post-front wind and the heated layer feeds the stem (NRC 1985, “The Effects on the
 * Atmosphere of a Major Nuclear Exchange”, ch. 4, p. 22: “sweep-up and thermal dust”); axial ball downdraft suppresses
 * inflow in the first seconds (Rosenblatt 1981, fig. 13e), hence onset no earlier than {@code stemFrom}. Dust leaving
 * the layer equals dust entering the stem: one mass for both, no independent stem curve.
 * <p>
 * In the stem, dust rises with its air ({@link MushroomModel#stemRise}): a release entering at t' reaches height
 * stemRise(t) − stemRise(t') at t; above cap bottom, it is in the cap. Thus an airburst's dusty stem may not catch the
 * fast-rising cap after delayed inflow (NRC, p. 22: 10 kt at 450 m, “dusty stems that did not merge with the ring vortex”);
 * a surface burst touches ground and carries dust upward behind it immediately. Stem depth is dust extinction per
 * meter height, Λ = Σk·m (m²/m), giving 2Λ/(πr) across the center of a circle of radius r. A ground-touching ball
 * (surface and low burst) has its own wake: dust beneath it fills the stem to the cap immediately (ball wake flow not
 * measured — ⚠ estimate), with Λ equal to all entered dust divided by height to cap bottom.
 * Coarse dust settling in the stem is ignored: upflow carries it.
 * <p>
 * Table step {@link #STEP} s, up to {@link #SPAN} cloud stabilizations; inflow rings {@link #RINGS} to the inflow edge
 * ({@code STEM_M}·∛(Y/15)). The immutable source snapshots the supplying layer's clock, in model seconds.
 * Cached by geometry, visibility and every source arrival; no world access during construction or sampling.
 */
public final class StemDust {
    /** Table step, s; inflow rings; cloud stabilizations covered by the table. */
    static final double STEP = 1;
    static final int RINGS = 24;
    static final double SPAN = 1.5;
    /** Default visibility (clear), m — for checks without a detonation. */
    public static final double CLEAR = 20_000;

    static final int CACHE_LIMIT = 8;
    /** Includes pending and failed computations; pending entries cannot be evicted and resubmitted every frame. */
    private static final Map<Source, CompletableFuture<StemDust>> CACHE = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * Immutable per-detonation source clock. Front distances are slant blocks and times are ticks;
     * dust uses horizontal model meters and model seconds. The profile itself is immutable.
     * Equality includes every sampled arrival used by the dust table, not the profile's object identity.
     */
    public static final class Source {
        private final double yieldKt, hob, visibility, scale;
        private final boolean surface;
        private final FrontProfile front;
        private final double[] arrival = new double[RINGS];
        private final int hash;

        public Source(double yieldKt, double hobM, boolean surface, double visibilityM, FrontProfile front, double scale) {
            this.yieldKt = yieldKt;
            this.hob = Math.max(0, hobM);
            this.surface = surface;
            this.visibility = visibilityM;
            this.front = front;
            this.scale = scale;
            for (int i = 0; i < RINGS; i++) arrival[i] = arrivalSeconds(radius(i));
            hash = 31 * Objects.hash(yieldKt, hob, surface, visibility) + Arrays.hashCode(arrival);
        }

        /** Ground range of the middle of source annulus i, model meters. */
        double radius(int i) {
            return DustModel.STEM_M * Math.cbrt(yieldKt / 15) * (i + 0.5) / RINGS;
        }

        /** Local front arrival in model seconds, also used at the moving stem foot. */
        public double arrivalSeconds(double groundMetres) {
            return front == null ? HobBlast.arrivalSeconds(groundMetres, hob, yieldKt)
                    : front.arrivalTicks(Math.hypot(groundMetres, hob) * scale) / (20 * scale);
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof Source s && Double.compare(yieldKt, s.yieldKt) == 0 && Double.compare(hob, s.hob) == 0
                    && surface == s.surface && Double.compare(visibility, s.visibility) == 0 && Arrays.equals(arrival, s.arrival);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    /**
     * Caller-owned request. The shared cache bounds reuse and pending builders, not a live consumer's result:
     * another detonation must not evict this future before its owner can use it. Read/release on the caller thread.
     * One table is retained through the consumer's cloud lifetime, even when it leaves the eight-entry shared cache.
     */
    public static final class Request {
        private final Source source;
        private final Executor worker;
        private CompletableFuture<StemDust> entry;

        private Request(Source source, Executor worker) {
            this.source = source;
            this.worker = worker;
        }

        @org.jetbrains.annotations.Nullable
        public StemDust ready() {
            if (entry == null) entry = start(source, worker);
            return value(entry);
        }

        /** Detach this consumer without cancelling a computation shared with other consumers. */
        public void release() {
            entry = null;
        }
    }

    /** Allocate once for a consumer; its immutable Source may be shared with the moving foot. */
    public static Request request(Source source) {
        return request(source, ForkJoinPool.commonPool());
    }

    static Request request(Source source, Executor worker) {
        return new Request(source, worker);
    }

    private final double yieldKt, hob;
    private final boolean surface;
    /** Stem dust extinction per meter height (m²/m) and dust top above ground (m), by step. */
    private final double[] time, lambda, top;

    private StemDust(Source source) {
        this.yieldKt = source.yieldKt;
        this.hob = source.hob;
        this.surface = source.surface;
        double ys = Math.cbrt(yieldKt / 15), reach = DustModel.STEM_M * ys;
        DustModel.Column[] col = new DustModel.Column[RINGS];
        double[] area = new double[RINGS], onset = new double[RINGS];
        for (int i = 0; i < RINGS; i++) {
            double r0 = reach * i / RINGS, r1 = reach * (i + 1) / RINGS, r = 0.5 * (r0 + r1);
            col[i] = DustModel.column(r, hob, yieldKt, surface, source.visibility, DustModel.Surface.REFERENCE);
            area[i] = Math.PI * (r1 * r1 - r0 * r0);
            onset[i] = source.arrival[i] + col[i].stemFrom();
        }
        int n = (int) Math.ceil(SPAN * CloudModel.stabilizationSeconds(yieldKt) / STEP) + 1;
        boolean wake = hob <= FireballModel.maxRadius(yieldKt, surface);
        double[] inflow = new double[n], rise = new double[n];
        double[][] release = new double[n][RINGS];
        for (int k = 0; k < n; k++) {
            double t = k * STEP, flux = 0;
            for (int i = 0; i < RINGS; i++) {
                DustModel.Column c = col[i];
                double age = t - source.arrival[i];
                // inflow takes stem/STEM_S of layer optical depth per second — the same term removed by DustModel.Column.left
                if (age > c.stemFrom()) {
                    release[k][i] = area[i] * c.stem() / DustModel.STEM_S * c.depth(age) * STEP;
                    flux += release[k][i];
                }
            }
            inflow[k] = flux;
            rise[k] = MushroomModel.stemRise(t, hob, yieldKt, surface);
        }
        // Keep the existing one-second quadrature at every integer node. Insert each source onset so linear
        // reconstruction cannot borrow its first release from a future sample before that source can supply it.
        double[] knots = new double[n + RINGS];
        int count = n;
        for (int k = 0; k < n; k++) knots[k] = k * STEP;
        for (int i = 0; i < RINGS; i++) {
            if (col[i].mass() > 0 && col[i].stem() > 0 && onset[i] > 0 && onset[i] < (n - 1) * STEP)
                knots[count++] = onset[i];
        }
        Arrays.sort(knots, 0, count);
        int unique = 0;
        for (int i = 0; i < count; i++) if (unique == 0 || knots[i] > knots[unique - 1]) knots[unique++] = knots[i];
        time = Arrays.copyOf(knots, unique);
        lambda = new double[unique];
        top = new double[unique];
        for (int p = 0; p < unique; p++) {
            double t = time[p], capBot = Math.max(hob, CloudModel.capBottom(t, hob, yieldKt, surface)), held = 0, oldest = 0;
            int k = Math.min(n - 1, (int) (t / STEP));
            double lifted = MushroomModel.stemRise(t, hob, yieldKt, surface);
            for (int j = 0; j <= k; j++) {
                if (inflow[j] <= 0) continue;
                double z = lifted - rise[j];
                if (z >= capBot) continue;
                held += inflow[j];
                oldest = Math.max(oldest, z);
            }
            if (k + 1 < n && t > k * STEP) {
                for (int i = 0; i < RINGS; i++) {
                    double from = Math.max(k * STEP, onset[i]);
                    if (t > from) held += release[k + 1][i] * (t - from) / ((k + 1) * STEP - from);
                }
            }
            if (wake) {
                // ball on ground: stem is its wake, dust beneath it fills the stem to the cap
                top[p] = capBot;
                lambda[p] = held / Math.max(capBot, 1);
            } else {
                top[p] = oldest;
                // fresh release already follows the air over the step: first inflow step does not divide by zero height
                double stepRise = lifted - MushroomModel.stemRise(Math.max(0, t - STEP), hob, yieldKt, surface);
                lambda[p] = held / Math.max(Math.max(oldest, stepRise), 1);
            }
        }
    }

    /** Burst stem dust (cached; if absent, computed now). */
    public static StemDust of(double yieldKt, double hobM, boolean surface, double visibilityM) {
        return of(new Source(yieldKt, hobM, surface, visibilityM, null, 1));
    }

    /** Synchronous model/test entry point; the client frame uses {@link #ready(Source)}. */
    public static StemDust of(Source source) {
        CompletableFuture<StemDust> entry;
        boolean build = false;
        synchronized (CACHE) {
            entry = CACHE.get(source);
            if (entry == null && room()) {
                entry = new CompletableFuture<>();
                CACHE.put(source, entry);
                build = true;
            }
        }
        if (entry == null) return new StemDust(source); // synchronous caller; all cached workers are occupied
        if (build) compute(source, entry);
        return entry.join();
    }

    /** Under CACHE's lock; never evict a running computation and spawn its replacement. */
    private static boolean room() {
        if (CACHE.size() < CACHE_LIMIT) return true;
        var it = CACHE.values().iterator();
        while (it.hasNext()) {
            if (it.next().isDone()) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    /**
     * Stem dust if table is ready; otherwise starts background computation (tens of ms, outside the frame) and returns null:
     * stem has no dust for the first burst frames (inflow starts seconds later anyway).
     */
    @org.jetbrains.annotations.Nullable
    public static StemDust ready(Source source) {
        return value(start(source, ForkJoinPool.commonPool()));
    }

    private static CompletableFuture<StemDust> start(Source source, Executor worker) {
        CompletableFuture<StemDust> entry;
        synchronized (CACHE) {
            entry = CACHE.get(source);
            if (entry != null) return entry;
            if (!room()) return null;
            entry = new CompletableFuture<>();
            CACHE.put(source, entry);
        }
        CompletableFuture<StemDust> pending = entry;
        try {
            worker.execute(() -> compute(source, pending));
        } catch (RuntimeException | Error e) {
            pending.completeExceptionally(e);
            org.slf4j.LoggerFactory.getLogger(StemDust.class).error("Stem dust worker submission failed", e);
        }
        return entry;
    }

    @org.jetbrains.annotations.Nullable
    private static StemDust value(@org.jetbrains.annotations.Nullable CompletableFuture<StemDust> entry) {
        // A terminal result cannot change. Checking failure on a pending future before getNow races
        // exceptional completion; return early while pending, then inspect only a terminal result.
        return entry == null || !entry.isDone() || entry.isCompletedExceptionally() ? null : entry.getNow(null);
    }

    private static void compute(Source source, CompletableFuture<StemDust> entry) {
        try {
            entry.complete(new StemDust(source));
        } catch (RuntimeException | Error e) {
            entry.completeExceptionally(e);
            org.slf4j.LoggerFactory.getLogger(StemDust.class).error("Stem dust table failed", e);
        }
    }

    private double at(double[] a, double t) {
        if (t <= 0) return a[0];
        if (t >= time[time.length - 1]) return a[a.length - 1];
        int i = Arrays.binarySearch(time, t);
        if (i >= 0) return a[i];
        i = -i - 2;
        double f = (t - time[i]) / (time[i + 1] - time[i]);
        return a[i] + (a[i + 1] - a[i]) * f;
    }

    /** Stem dust extinction per meter height at t, m²/m (beyond table, last value). */
    public double lambda(double t) {
        return at(lambda, t);
    }

    /** Dust top in the stem at t, m above ground (no higher than cap bottom). */
    public double top(double t) {
        return at(top, t);
    }

    /** Cross-stem center optical depth at t and radius r, m: 2Λ/(πr). */
    public double tau(double t, double r) {
        return 2 * lambda(t) / (Math.PI * Math.max(r, 1));
    }

    double yieldKt() {
        return yieldKt;
    }

    double hob() {
        return hob;
    }

    boolean surface() {
        return surface;
    }
}
