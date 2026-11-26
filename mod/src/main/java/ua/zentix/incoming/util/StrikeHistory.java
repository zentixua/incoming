package ua.zentix.incoming.util;

import net.minecraft.nbt.CompoundTag;

import java.util.Arrays;

/**
 * A strike's world-clock history ({@code docs/design/time.md} §8.3 rule 4): the clock without jumps, the counted jumps and
 * the unseen jumps ({@link WorldClock#unseen}) at one real moment per real minute until the motion age passes
 * {@link #UNTIL_S}, and at the show's end, at the blend's end and on both sides of each jump. With the strike's anchors
 * ({@link StrikeClock}) it gives the strike clock S and the motion age M at any past real moment, and the moment at which
 * the motion age reached a value ({@link #ageAtMotion}): what lands from the air (fallout, black rain) at a
 * motion age ages on the ground by the clocks since. Between samples the clock without jumps runs evenly; a jump belongs
 * to the sample at which it was counted.
 *
 * <p>Size: at most about 730 samples while the strike's air lives, plus two per jump. Once nothing lands any more
 * ({@link #compact}) samples on a straight line are merged and at most {@link #MAX_COMPACT} are kept.
 */
public final class StrikeHistory {
    /** Real ticks between regular samples: a real minute. */
    public static final long EVERY = 1200;
    /**
     * Motion age after which no samples are taken, seconds: the last fallout arrival, about 11 h (time.md §8.3 rule 4),
     * rounded up (est.). Later moments interpolate between the last sample and now.
     */
    public static final double UNTIL_S = 12 * 3600;

    /** Most samples a compacted history keeps ({@link #compact}): saved, under 4 KB (32 bytes a sample). */
    public static final int MAX_COMPACT = 96;
    /** Clock-without-jumps error a compacted history accepts when it merges samples, world-clock ticks (est.: 1/20 s). */
    static final double COMPACT_TOLERANCE = 1.0 / 20;

    private long[] ages = new long[16];
    private double[] rates = new double[16], jumps = new double[16], unseens = new double[16];
    private int n;
    /** Samples before this index are compacted; later ones are only jumps ({@link #compact}); −1 — not compacted. */
    private int compactedTo = -1;
    /** Loaded without unseen jumps: they read as unknown until {@link #settleUnseen}. */
    private boolean unseenUnknown;

    /** Samples taken so far. */
    public int size() {
        return n;
    }

    /**
     * Take a sample at real age {@code age} if one is due: the first, a real minute since the last, a changed count of jumps
     * (then both sides: the moment before it was counted and after), or {@code force} (the show's or the blend's end). After
     * the motion age {@code motionS} passes {@link #UNTIL_S} only jumps and {@code force} are sampled.
     */
    public void sample(long age, double rate, double counted, double motionS, boolean force) {
        sample(age, rate, counted, n > 0 ? unseens[n - 1] : 0, motionS, force);
    }

    /**
     * The same with the unseen jumps {@code unseen} ({@link WorldClock#unseen}): a change of either jump count is sampled on
     * both sides. A compacted history ({@link #compact}) samples only jumps, each once (the side before it adds nothing to a
     * reading: a moment before the jump reads the sample before); at most {@link #MAX_COMPACT} samples, the oldest jump
     * after the compaction then belongs to the sample before it, so it is read earlier, never later ({@link #trim}).
     */
    public void sample(long age, double rate, double counted, double unseen, double motionS, boolean force) {
        if (n > 0 && age < ages[n - 1]) return;
        boolean jumped = n > 0 && (counted != jumps[n - 1] || unseen != unseens[n - 1]);
        // past the last arrival only the rare moments that change the clock's course: a jump, a show's end
        if ((motionS > UNTIL_S || compactedTo >= 0) && n > 0 && !force && !jumped) return;
        if (jumped) {
            if (compactedTo < 0) add(age, rate, jumps[n - 1], unseens[n - 1]);
            add(age, rate, counted, unseen);
        } else if (n == 0 || force || age - ages[n - 1] >= EVERY) {
            add(age, rate, counted, unseen);
        }
        if (compactedTo >= 0) trim();
    }

    private void add(long age, double rate, double counted, double unseen) {
        if (n == ages.length) {
            ages = Arrays.copyOf(ages, n * 2);
            rates = Arrays.copyOf(rates, n * 2);
            jumps = Arrays.copyOf(jumps, n * 2);
            unseens = Arrays.copyOf(unseens, n * 2);
        }
        ages[n] = age;
        rates[n] = rate;
        jumps[n] = counted;
        unseens[n] = unseen;
        n++;
    }

    /** Whether the history is compacted ({@link #compact}). */
    public boolean compacted() {
        return compactedTo >= 0;
    }

    /**
     * Nothing lands from the strike's air any more: merge samples that lie on a straight line between their neighbours
     * (the clock without jumps within {@link #COMPACT_TOLERANCE}, the same jumps), so a steady day keeps only its bends and
     * jumps; if more than half of {@link #MAX_COMPACT} remain, keep that many spread over them, each kept sample taking the
     * jumps of those dropped after it (a jump is read earlier, never later). Later samples are jumps only.
     */
    public void compact() {
        if (compactedTo >= 0 || n <= 2) {
            compactedTo = Math.max(compactedTo, n);
            return;
        }
        int[] keep = new int[n];
        int k = 0, last = 0;
        keep[k++] = 0;
        for (int i = 1; i < n - 1; i++) {
            // keep i if the line from the last kept sample to i + 1 misses any sample between them
            boolean straight = jumps[i + 1] == jumps[last] && unseens[i + 1] == unseens[last] && ages[i + 1] > ages[last];
            for (int j = last + 1; straight && j <= i; j++) {
                double line = rates[last] + (rates[i + 1] - rates[last]) * (ages[j] - ages[last]) / (double) (ages[i + 1] - ages[last]);
                straight = Math.abs(line - rates[j]) <= COMPACT_TOLERANCE;
            }
            if (!straight) {
                keep[k++] = i;
                last = i;
            }
        }
        keep[k++] = n - 1;
        int limit = MAX_COMPACT / 2;
        if (k > limit) {
            int[] spread = new int[limit];
            for (int i = 0; i < limit; i++) spread[i] = keep[(int) Math.round(i * (k - 1) / (double) (limit - 1))];
            keep = spread;
            k = limit;
        }
        keepOnly(keep, k);
        compactedTo = n;
    }

    /**
     * Over {@link #MAX_COMPACT}: the second jump after the compaction joins the first, read from the first's moment (earlier,
     * never later).
     */
    private void trim() {
        while (n > MAX_COMPACT && n - compactedTo >= 2) {
            int[] keep = new int[n - 1];
            int k = 0;
            for (int i = 0; i < n; i++) if (i != compactedTo + 1) keep[k++] = i;
            keepOnly(keep, k);
        }
    }

    /**
     * Keep samples {@code keep[0..k)}: each takes the jumps of the last sample before the next kept one, so a dropped jump
     * is read from the kept sample before it; the last sample is always kept.
     */
    private void keepOnly(int[] keep, int k) {
        long[] a = new long[Math.max(16, k)];
        double[] r = new double[a.length], j = new double[a.length], u = new double[a.length];
        for (int i = 0; i < k; i++) {
            int carry = i + 1 < k ? keep[i + 1] - 1 : keep[i];
            a[i] = ages[keep[i]];
            r[i] = rates[keep[i]];
            j[i] = jumps[carry];
            u[i] = unseens[carry];
        }
        ages = a;
        rates = r;
        jumps = j;
        unseens = u;
        n = k;
    }

    /**
     * The clock without jumps and the counted jumps at real age {@code age}, from the samples and the reading now
     * ({@code ageNow}, {@code rateNow}, {@code countedNow}): {rate, counted}.
     */
    public double[] at(double age, long ageNow, double rateNow, double countedNow) {
        if (n == 0 || age >= ageNow) return new double[]{rateNow, countedNow};
        int i = last(age);
        // before the first sample (a strike saved before its history began there): as a vanilla day, no jumps before it
        if (i < 0) return new double[]{rates[0] - (ages[0] - age), jumps[0]};
        long a0 = ages[i], a1 = i + 1 < n ? ages[i + 1] : ageNow;
        double r0 = rates[i], r1 = i + 1 < n ? rates[i + 1] : rateNow;
        double rate = a1 <= a0 ? r1 : r0 + (r1 - r0) * (age - a0) / (a1 - a0);
        return new double[]{rate, jumps[i]};
    }

    /** The unseen jumps at real age {@code age} ({@link WorldClock#unseen}), from the samples and {@code unseenNow} at {@code ageNow}. */
    public double unseenAt(double age, long ageNow, double unseenNow) {
        if (n == 0 || age >= ageNow) return unseenNow;
        double u = unseens[Math.max(last(age), 0)];
        return Double.isNaN(u) ? unseenNow : u;
    }

    /** Real age, ticks, of the moment the history was compacted at ({@link #compact}: its last sample then); −1 — not compacted. */
    public long compactedAge() {
        return compactedTo > 0 ? ages[compactedTo - 1] : -1;
    }

    /**
     * Real age, ticks, at which strike clock {@code c}'s motion age reached {@code motionS} seconds; NaN — not yet (now at
     * {@code ageNow} with counted jumps {@code countedNow}). Exact: between samples the motion age runs at real time; a
     * moment inside a counted jump is the jump's sample.
     */
    public double ageAtMotion(StrikeClock c, double motionS, long ageNow, double countedNow) {
        if (c.motion(ageNow, countedNow) < motionS) return Double.NaN;
        if (n == 0) return clamp(20 * (motionS - Math.max(0, countedNow - c.jumpsAtStrike()) * WorldClock.SECONDS_PER_TICK), 0, ageNow);
        // knots: the samples, then now; the motion age is non-decreasing over them
        int lo = 0, hi = n, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (c.motion(knotAge(mid, ageNow), knotJumps(mid, countedNow)) <= motionS) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        // before the first sample: no jumps before it (as at())
        double j = found < 0 ? jumps[0] : jumps[Math.min(found, n - 1)];
        double age = 20 * (motionS - Math.max(0, j - c.jumpsAtStrike()) * WorldClock.SECONDS_PER_TICK);
        if (found >= n) return ageNow;
        double from = found < 0 ? 0 : ages[found], to = found + 1 < n ? ages[found + 1] : ageNow;
        return clamp(age, from, to);
    }

    /**
     * How far the ground clock leads the motion age at real age {@code age}, seconds: S − M plus the unseen jumps since the
     * burst. Constant across a counted jump (it moves both), growing as the world's day outruns real time or someone stays
     * awake through a jump. A deposit landing then has ground age G − lead ever after ({@code FalloutModel.Landing}).
     */
    public double groundLead(StrikeClock c, double age, long ageNow, double rateNow, double countedNow, double unseenNow) {
        double[] r = at(age, ageNow, rateNow, countedNow);
        double unseen = unseenAt(age, ageNow, unseenNow) - unseenAt(0, ageNow, unseenNow);
        return Math.max(0, c.strike(age, r[0], r[1]) - c.motion(age, r[1]) + unseen * WorldClock.SECONDS_PER_TICK);
    }

    /** Strike clock {@code c} at real age {@code age}, seconds, read from the history. */
    public double strikeAt(StrikeClock c, double age, long ageNow, double rateNow, double countedNow) {
        double[] r = at(age, ageNow, rateNow, countedNow);
        return c.strike(age, r[0], r[1]);
    }

    private long knotAge(int i, long ageNow) {
        return i < n ? ages[i] : ageNow;
    }

    private double knotJumps(int i, double countedNow) {
        return i < n ? jumps[i] : countedNow;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Index of the last sample at or before {@code age}; −1 — none. */
    private int last(double age) {
        int lo = 0, hi = n - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (ages[mid] <= age) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        long[] r = new long[n], j = new long[n], u = new long[n];
        for (int i = 0; i < n; i++) {
            r[i] = Double.doubleToRawLongBits(rates[i]);
            j[i] = Double.doubleToRawLongBits(jumps[i]);
            u[i] = Double.doubleToRawLongBits(unseens[i]);
        }
        t.putLongArray("ages", Arrays.copyOf(ages, n));
        t.putLongArray("rates", r);
        t.putLongArray("jumps", j);
        t.putLongArray("unseen", u);
        if (compactedTo >= 0) t.putInt("compacted", compactedTo);
        return t;
    }

    /**
     * Unseen jumps unknown (a history saved before they were sampled) become {@code unseenNow}: no unseen jump is credited
     * before the history learned of them. Call before sampling.
     */
    public void settleUnseen(double unseenNow) {
        if (!unseenUnknown) return;
        for (int i = 0; i < n; i++) if (Double.isNaN(unseens[i])) unseens[i] = unseenNow;
        unseenUnknown = false;
    }

    /** A history saved without unseen jumps reads them as unknown until {@link #settleUnseen}. */
    public static StrikeHistory load(CompoundTag t) {
        StrikeHistory h = new StrikeHistory();
        long[] a = t.getLongArray("ages"), r = t.getLongArray("rates"), j = t.getLongArray("jumps"), u = t.getLongArray("unseen");
        int n = Math.min(a.length, Math.min(r.length, j.length));
        for (int i = 0; i < n; i++) h.add(a[i], Double.longBitsToDouble(r[i]), Double.longBitsToDouble(j[i]), i < u.length ? Double.longBitsToDouble(u[i]) : Double.NaN);
        if (t.contains("compacted")) h.compactedTo = Math.min(t.getInt("compacted"), h.n);
        h.unseenUnknown = u.length < n;
        return h;
    }
}
