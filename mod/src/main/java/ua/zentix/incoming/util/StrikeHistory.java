package ua.zentix.incoming.util;

import net.minecraft.nbt.CompoundTag;

import java.util.Arrays;

/**
 * A strike's world-clock history ({@code docs/design/time.md} §8.3 rule 4): the clock without jumps and the counted jumps at
 * one real moment per real minute, at the show's end, at the blend's end and on both sides of each counted jump, until the
 * motion age passes {@link #UNTIL_S}. With the strike's anchors ({@link StrikeClock}) it gives the strike clock S at any past
 * real moment: what lands from the air (fallout, black rain) at a motion age ages on the ground by the strike clock since.
 * Between samples the clock without jumps runs evenly; a jump belongs to the sample at which it was counted.
 */
public final class StrikeHistory {
    /** Real ticks between regular samples: a real minute. */
    public static final long EVERY = 1200;
    /**
     * Motion age after which no samples are taken, seconds: the last fallout arrival, about 11 h (time.md §8.3 rule 4),
     * rounded up (est.). Later moments interpolate between the last sample and now.
     */
    public static final double UNTIL_S = 12 * 3600;

    private long[] ages = new long[16];
    private double[] rates = new double[16], jumps = new double[16];
    private int n;

    /** Samples taken so far. */
    public int size() {
        return n;
    }

    /**
     * Take a sample at real age {@code age} if one is due: the first, a real minute since the last, a changed count of jumps
     * (then both sides: the moment before it was counted and after), or {@code force} (the show's or the blend's end); never
     * after the motion age {@code motionS} passes {@link #UNTIL_S}.
     */
    public void sample(long age, double rate, double counted, double motionS, boolean force) {
        if (motionS > UNTIL_S && n > 0) return;
        if (n > 0 && age < ages[n - 1]) return;
        if (n > 0 && counted != jumps[n - 1]) {
            add(age, rate, jumps[n - 1]);
            add(age, rate, counted);
            return;
        }
        if (n == 0 || force || age - ages[n - 1] >= EVERY) add(age, rate, counted);
    }

    private void add(long age, double rate, double counted) {
        if (n == ages.length) {
            ages = Arrays.copyOf(ages, n * 2);
            rates = Arrays.copyOf(rates, n * 2);
            jumps = Arrays.copyOf(jumps, n * 2);
        }
        ages[n] = age;
        rates[n] = rate;
        jumps[n] = counted;
        n++;
    }

    /**
     * The clock without jumps and the counted jumps at real age {@code age}, from the samples and the reading now
     * ({@code ageNow}, {@code rateNow}, {@code countedNow}): {rate, counted}.
     */
    public double[] at(double age, long ageNow, double rateNow, double countedNow) {
        if (n == 0 || age >= ageNow) return new double[]{rateNow, countedNow};
        int i = last(age);
        if (i < 0) return new double[]{rates[0], jumps[0]};
        long a0 = ages[i], a1 = i + 1 < n ? ages[i + 1] : ageNow;
        double r0 = rates[i], r1 = i + 1 < n ? rates[i + 1] : rateNow;
        double rate = a1 <= a0 ? r1 : r0 + (r1 - r0) * (age - a0) / (a1 - a0);
        return new double[]{rate, jumps[i]};
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
        long[] r = new long[n], j = new long[n];
        for (int i = 0; i < n; i++) {
            r[i] = Double.doubleToRawLongBits(rates[i]);
            j[i] = Double.doubleToRawLongBits(jumps[i]);
        }
        t.putLongArray("ages", Arrays.copyOf(ages, n));
        t.putLongArray("rates", r);
        t.putLongArray("jumps", j);
        return t;
    }

    public static StrikeHistory load(CompoundTag t) {
        StrikeHistory h = new StrikeHistory();
        long[] a = t.getLongArray("ages"), r = t.getLongArray("rates"), j = t.getLongArray("jumps");
        int n = Math.min(a.length, Math.min(r.length, j.length));
        for (int i = 0; i < n; i++) h.add(a[i], Double.longBitsToDouble(r[i]), Double.longBitsToDouble(j[i]));
        return h;
    }
}
