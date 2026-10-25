package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.nuclear.model.DustModel;

/**
 * Particles leaving the carrier, never an independent initial cold source. Brown et al., ARI-RR-1384 (2004),
 * §§3.5.2–3.5.3: exit from a rising puff and deposition on the ground are different events.
 * Uniform mixing gives exit flux v A M/V. Each size bin keeps its own settling speed after exit.
 * The lower ellipsoid surface has a uniform projected-area flux: its moments determine release geometry.
 * ⚠ Integral closure: terminal settling outside the gas, planar support at the impact, ambient Briggs diffusion;
 * directed slab jets, turbulent deposition and gas/solid heat exchange are unresolved.
 */
final class DustFallout {
    /** Numerical quadrature in cumulative released mass, grouped by moments; not physical parameters. */
    private final int samples, groups;
    // Reused by the numerical integrator, like the carrier/ground tables on the client thread.
    // Endpoint-aware refinement resolves narrow surviving tails; uniform mass midpoints can miss both tails.
    private static final int DEPTH = 24, STRIDE = 30;
    private static final double RELATIVE_ERROR = 1e-3, ABSOLUTE_ERROR = 1e-12;
    private final double[] work = new double[(DEPTH + 1) * STRIDE], integral = new double[6];
    private final Thermal gas;
    private final double initial;

    DustFallout(Thermal gas, double initial) {
        this(gas, initial, 4, 4);
    }

    DustFallout(Thermal gas, double initial, int samples, int groups) {
        this.samples = samples;
        this.groups = groups;
        this.gas = gas;
        this.initial = initial;
    }

    interface Sink {
        void blob(double x, double y, double z, double sh, double sv, double physical, double optical);
    }

    double airborne(double time) {
        double[] total = {gas.airborneDust(time)};
        blobs(time, (x, y, z, sh, sv, mass, optical) -> total[0] += mass);
        return total[0];
    }

    double tauUpper(double time) {
        double[] total = {0};
        blobs(time, (x, y, z, sh, sv, mass, optical) -> total[0] += DustModel.K_MIX * optical / (2 * Math.PI * sh * sv));
        return total[0];
    }

    double airborneBin(int bin, double time) {
        double[] total = {initial * DustModel.binShare(bin) * Math.exp(-DustModel.binSettle(bin) * gas.settling(time))};
        blobs(time, bin, bin + 1, (x, y, z, sh, sv, mass, optical) -> total[0] += mass);
        return total[0];
    }

    void blobs(double time, Sink sink) {
        blobs(time, 0, DustModel.bins(), sink);
    }

    private void blobs(double time, int from, int to, Sink sink) {
        if (time <= 0 || initial <= 0) return;
        for (int bin = from; bin < to; bin++) {
            double v = DustModel.binSettle(bin), m0 = initial * DustModel.binShare(bin);
            double released = -Math.expm1(-v * gas.settling(time));
            if (released <= 0) continue;
            for (int group = 0; group < groups; group++) {
                java.util.Arrays.fill(integral, 0);
                for (int j = group * samples / groups; j < (group + 1) * samples / groups; j++) {
                    double lo = (double) j / samples, hi = (double) (j + 1) / samples;
                    point(time, bin, released, lo, 0);
                    point(time, bin, released, 0.5 * (lo + hi), 6);
                    point(time, bin, released, hi, 12);
                    integrate(time, bin, released, lo, hi, 0);
                }
                double mass = m0 * released * integral[0];
                if (mass <= 0) continue;
                double x = integral[1] / integral[0], y = integral[2] / integral[0], z = integral[3] / integral[0];
                double sh = Math.sqrt(Math.max(1e-12, integral[4] / integral[0] - 0.5 * (x * x + z * z)));
                double sv = Math.sqrt(Math.max(1e-12, integral[5] / integral[0] - y * y));
                sink.blob(x, y, z, sh, sv, mass, mass * DustModel.binExtinction(bin) / DustModel.K_MIX);
            }
        }
    }

    /** Positive Simpson weights; endpoints detect surviving mass even when every old midpoint has deposited. */
    private void integrate(double time, int bin, double released, double lo, double hi, int depth) {
        int base = depth * STRIDE;
        double mid = 0.5 * (lo + hi);
        point(time, bin, released, 0.5 * (lo + mid), base + 18);
        point(time, bin, released, 0.5 * (mid + hi), base + 24);
        boolean converged = true;
        for (int k = 0; k < 6; k++) {
            double coarse = (hi - lo) / 6 * (work[base + k] + 4 * work[base + 6 + k] + work[base + 12 + k]);
            double fine = (hi - lo) / 12 * (work[base + k] + 4 * work[base + 18 + k] + 2 * work[base + 6 + k]
                    + 4 * work[base + 24 + k] + work[base + 12 + k]);
            if (Math.abs(fine - coarse) > RELATIVE_ERROR * Math.abs(fine) + ABSOLUTE_ERROR * (hi - lo)) converged = false;
        }
        if (converged || depth == DEPTH) {
            for (int k = 0; k < 6; k++) integral[k] += (hi - lo) / 12
                    * (work[base + k] + 4 * work[base + 18 + k] + 2 * work[base + 6 + k]
                    + 4 * work[base + 24 + k] + work[base + 12 + k]);
            return;
        }
        System.arraycopy(work, base, work, base + STRIDE, 6);
        System.arraycopy(work, base + 18, work, base + STRIDE + 6, 6);
        System.arraycopy(work, base + 6, work, base + STRIDE + 12, 6);
        integrate(time, bin, released, lo, mid, depth + 1);
        System.arraycopy(work, base + 6, work, base + STRIDE, 6);
        System.arraycopy(work, base + 24, work, base + STRIDE + 6, 6);
        System.arraycopy(work, base + 12, work, base + STRIDE + 12, 6);
        integrate(time, bin, released, mid, hi, depth + 1);
    }

    /** Mass-normalized zeroth, first and second moments at one release quantile. */
    private void point(double time, int bin, double released, double fraction, int at) {
        double v = DustModel.binSettle(bin);
        double birth = fraction == 1 ? time : gas.timeForSettling(-Math.log1p(-released * fraction) / v);
        double age = Math.max(0, time - birth);
        double a = gas.horizontalRadius(birth), b = gas.verticalRadius(birth), h = gas.height(birth);
        double relaxation = v / CloudModel.G;
        double dy = gas.velocity(birth) * relaxation * -Math.expm1(-age / relaxation) - v * age;
        double q0 = Math.min(1, Math.max(0, h / b));
        double q = Math.min(q0, Math.max(0, (h + dy) / b));
        double weight = q * q;
        if (weight <= 0) {
            java.util.Arrays.fill(work, at, at + 6, 0);
            return;
        }
        double y0 = h - 2 * b * q / 3, y = y0 + dy;
        double windFactor = gas.air.wind(Math.max(0, 0.5 * (y0 + y)));
        double x = gas.driftX(birth) + gas.air.windX() * windFactor * age;
        double z = gas.driftZ(birth) + gas.air.windZ() * windFactor * age;
        double path = Math.max(1, Math.hypot(gas.air.windX(), gas.air.windZ()) * windFactor) * age;
        double inheritedH = Math.max(0, Math.pow(gas.sigmaH(birth), 2) - a * a / 5);
        double inheritedV = Math.max(0, Math.pow(gas.sigmaV(birth), 2) - b * b / 5);
        double sh2 = inheritedH + a * a * (0.5 - 0.25 * q * q) + Math.pow(CloudModel.spreadH(gas.air.spread(), path), 2);
        double sv2 = inheritedV + b * b * q * q / 18 + Math.pow(CloudModel.spreadV(gas.air.spread(), path), 2);
        work[at] = weight;
        work[at + 1] = weight * x;
        work[at + 2] = weight * y;
        work[at + 3] = weight * z;
        work[at + 4] = weight * (sh2 + 0.5 * (x * x + z * z));
        work[at + 5] = weight * (sv2 + y * y);
    }
}
