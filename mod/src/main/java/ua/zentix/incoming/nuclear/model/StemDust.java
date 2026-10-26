package ua.zentix.incoming.nuclear.model;

import java.util.LinkedHashMap;
import java.util.Map;

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
 * ({@code STEM_M}·∛(Y/15)). One per burst (cached by yield, height, burst type and visibility).
 */
public final class StemDust {
    /** Table step, s; inflow rings; cloud stabilizations covered by the table. */
    static final double STEP = 1;
    static final int RINGS = 24;
    static final double SPAN = 1.5;
    /** Default visibility (clear), m — for checks without a detonation. */
    public static final double CLEAR = 20_000;

    private static final Map<String, StemDust> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, StemDust> e) {
            return size() > 8;
        }
    };

    private final double yieldKt, hob;
    private final boolean surface;
    /** Stem dust extinction per meter height (m²/m) and dust top above ground (m), by step. */
    private final double[] lambda, top;

    private StemDust(double yieldKt, double hobM, boolean surface, double visibilityM) {
        this.yieldKt = yieldKt;
        this.hob = Math.max(0, hobM);
        this.surface = surface;
        double ys = Math.cbrt(yieldKt / 15), reach = DustModel.STEM_M * ys;
        DustModel.Column[] col = new DustModel.Column[RINGS];
        double[] area = new double[RINGS], arrival = new double[RINGS];
        for (int i = 0; i < RINGS; i++) {
            double r0 = reach * i / RINGS, r1 = reach * (i + 1) / RINGS, r = 0.5 * (r0 + r1);
            col[i] = DustModel.column(r, hob, yieldKt, surface, visibilityM, DustModel.Surface.REFERENCE);
            area[i] = Math.PI * (r1 * r1 - r0 * r0);
            arrival[i] = HobBlast.arrivalSeconds(r, hob, yieldKt);
        }
        int n = (int) Math.ceil(SPAN * CloudModel.stabilizationSeconds(yieldKt) / STEP) + 1;
        boolean wake = hob <= FireballModel.maxRadius(yieldKt, surface);
        double[] inflow = new double[n], rise = new double[n];
        lambda = new double[n];
        top = new double[n];
        for (int k = 0; k < n; k++) {
            double t = k * STEP, flux = 0;
            for (int i = 0; i < RINGS; i++) {
                DustModel.Column c = col[i];
                double age = t - arrival[i];
                // inflow takes stem/STEM_S of layer optical depth per second — the same term removed by DustModel.Column.left
                if (age > c.stemFrom()) flux += area[i] * c.stem() / DustModel.STEM_S * c.depth(age);
            }
            inflow[k] = flux * STEP;
            rise[k] = MushroomModel.stemRise(t, hob, yieldKt, surface);
        }
        for (int k = 0; k < n; k++) {
            double t = k * STEP, capBot = Math.max(hob, CloudModel.capBottom(t, hob, yieldKt, surface)), held = 0, oldest = 0;
            for (int j = 0; j <= k; j++) {
                if (inflow[j] <= 0) continue;
                double z = rise[k] - rise[j];
                if (z >= capBot) continue;
                held += inflow[j];
                oldest = Math.max(oldest, z);
            }
            if (wake) {
                // ball on ground: stem is its wake, dust beneath it fills the stem to the cap
                top[k] = capBot;
                lambda[k] = held / Math.max(capBot, 1);
            } else {
                top[k] = oldest;
                // fresh release already follows the air over the step: first inflow step does not divide by zero height
                lambda[k] = held / Math.max(Math.max(oldest, k > 0 ? rise[k] - rise[k - 1] : 0), 1);
            }
        }
    }

    /** Burst stem dust (cached; if absent, computed now). */
    public static StemDust of(double yieldKt, double hobM, boolean surface, double visibilityM) {
        String key = key(yieldKt, hobM, surface, visibilityM);
        synchronized (CACHE) {
            StemDust d = CACHE.get(key);
            if (d != null) return d;
        }
        StemDust d = new StemDust(yieldKt, hobM, surface, visibilityM);
        synchronized (CACHE) {
            CACHE.put(key, d);
        }
        return d;
    }

    /** Keys already computing in the background and keys whose computation failed. */
    private static final java.util.Set<String> PENDING = new java.util.HashSet<>(), FAILED = new java.util.HashSet<>();

    /**
     * Stem dust if table is ready; otherwise starts background computation (tens of ms, outside the frame) and returns null:
     * stem has no dust for the first burst frames (inflow starts seconds later anyway).
     */
    @org.jetbrains.annotations.Nullable
    public static StemDust ready(double yieldKt, double hobM, boolean surface, double visibilityM) {
        String key = key(yieldKt, hobM, surface, visibilityM);
        synchronized (CACHE) {
            StemDust d = CACHE.get(key);
            if (d != null || FAILED.contains(key) || !PENDING.add(key)) return d;
        }
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                of(yieldKt, hobM, surface, visibilityM);
            } catch (RuntimeException e) {
                // log error once; stem stays dust-free (key among failures, no retry)
                org.slf4j.LoggerFactory.getLogger(StemDust.class).error("Stem dust table failed for {}", key, e);
                synchronized (CACHE) {
                    FAILED.add(key);
                }
            } finally {
                synchronized (CACHE) {
                    PENDING.remove(key);
                }
            }
        });
        return null;
    }

    private static String key(double yieldKt, double hobM, boolean surface, double visibilityM) {
        return yieldKt + "/" + Math.max(0, hobM) + "/" + surface + "/" + visibilityM;
    }

    private double at(double[] a, double t) {
        double s = Math.max(0, t / STEP);
        int i = (int) Math.min(a.length - 2, Math.floor(s));
        if (i < 0) return a[0];
        double f = Math.min(1, s - i);
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
