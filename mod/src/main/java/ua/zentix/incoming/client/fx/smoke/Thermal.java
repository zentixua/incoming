package ua.zentix.incoming.client.fx.smoke;

/**
 * Warm cloud as one Morton–Taylor–Turner thermal (Proc. R. Soc. A 234, 1956; equations in Bhamidipati &amp; Woods,
 * JFM 2023 form, eq. 2.1–2.3), without Minecraft: sphere radius R rises at speed W, entraining air through
 * surface at αW (α = {@value CloudModel#ALPHA}), accelerates by buoyancy g', and loses buoyancy in stable air
 * (Brunt–Väisälä frequency N): d(R³)/dt = 3α·R²W, d(R³W)/dt = R³g', d(R³g')/dt = −N²·R³W. After apex, cloud stays
 * there, drifting with wind at its altitude; air turbulence (Pasquill–Gifford, Briggs open-country formulas,
 * wind path x = u·t) spreads it from first second ({@link #sigmaH}). One law for blast cloud ({@link CloudModel})
 * and launch cloud ({@link LaunchCloud}). Metres, seconds; heights from starting point (centre starts at half
 * initial radius: ground cloud is a hemisphere).
 */
public final class Thermal {
    /** Rise integration step, s. */
    static final double DT = 0.05;

    public final CloudModel.Air air;
    /** Buoyancy of total heat B = g·Q/(ρ·c_p·T), m⁴/s². */
    public final double buoyancy;
    /** Initial radius, m. */
    public final double r0;
    /** Rise at {@link #DT} steps: centre height, radius, speed, g'; drift x and z. */
    private final double[] z, r, w, gp, dx, dz;
    /** Maximum rise speed, m/s. */
    public final double maxRise;
    /** Maximum centre height and time reached, s; radius there. */
    public final double topZ, topSeconds, topR;

    /** Buoyancy of heat heat, J: B = g·Q/(ρ·c_p·T), m⁴/s². */
    public static double buoyancy(double heat) {
        return CloudModel.G * heat / (CloudModel.RHO * CloudModel.CP * CloudModel.T_AIR);
    }

    /**
     * Thermal with buoyancy buoyancy (m⁴/s²), starting at g' = g0 (volume B/g0), with radius at least rMin, m:
     * sub-block sphere unresolved, and R = 0 would divide by zero.
     */
    public Thermal(double buoyancy, double g0, double rMin, CloudModel.Air air) {
        this.air = air;
        this.buoyancy = buoyancy;
        // no heat (dust raised without heating) — sphere does not rise: g' = 0
        double volume = buoyancy > 0 && g0 > 0 ? buoyancy / g0 : 0;
        if (buoyancy <= 0) g0 = 0;
        r0 = Math.max(rMin, Math.cbrt(3 * volume / (4 * Math.PI)));
        int n = (int) Math.ceil(Math.PI / air.n() / DT) + 1;
        z = new double[n];
        r = new double[n];
        w = new double[n];
        gp = new double[n];
        dx = new double[n];
        dz = new double[n];
        // MTT in variables u = R³, m = R³W, f = R³g'; centre starts half radius above ground (ground ball is hemisphere)
        double u = r0 * r0 * r0, m = 0, f = u * g0, h = 0.5 * r0, x = 0, y = 0;
        double[] k1 = new double[4], k2 = new double[4], k3 = new double[4], k4 = new double[4];
        for (int i = 0; i < n; i++) {
            double rr = Math.cbrt(u);
            z[i] = h;
            r[i] = rr;
            w[i] = m / u;
            gp[i] = f / u;
            dx[i] = x;
            dz[i] = y;
            // RK4 over (u, m, f, h)
            rate(u, m, f, k1);
            rate(u + 0.5 * DT * k1[0], m + 0.5 * DT * k1[1], f + 0.5 * DT * k1[2], k2);
            rate(u + 0.5 * DT * k2[0], m + 0.5 * DT * k2[1], f + 0.5 * DT * k2[2], k3);
            rate(u + DT * k3[0], m + DT * k3[1], f + DT * k3[2], k4);
            u += DT / 6 * (k1[0] + 2 * k2[0] + 2 * k3[0] + k4[0]);
            m += DT / 6 * (k1[1] + 2 * k2[1] + 2 * k3[1] + k4[1]);
            f += DT / 6 * (k1[2] + 2 * k2[2] + 2 * k3[2] + k4[2]);
            h += DT / 6 * (k1[3] + 2 * k2[3] + 2 * k3[3] + k4[3]);
            double along = air.wind(h) * DT;
            x += air.windX() * along;
            y += air.windZ() * along;
            if (m <= 0 && i > 0) {
                // apex: cloud rises no farther
                for (int j = i + 1; j < n; j++) {
                    z[j] = z[i];
                    r[j] = r[i];
                    w[j] = 0;
                    gp[j] = gp[i];
                    dx[j] = dx[j - 1] + air.windX() * air.wind(z[i]) * DT;
                    dz[j] = dz[j - 1] + air.windZ() * air.wind(z[i]) * DT;
                }
                break;
            }
        }
        int top = 0;
        for (int i = 1; i < n; i++) if (z[i] > z[top]) top = i;
        double fastest = 0;
        for (double v : w) fastest = Math.max(fastest, v);
        maxRise = fastest;
        topZ = z[top];
        topR = r[top];
        topSeconds = top * DT;
    }

    /** MTT thermal derivatives d/dt (u, m, f, h), m = R³W, f = R³g', into {@code out}. */
    private void rate(double u, double m, double f, double[] out) {
        double rr = Math.cbrt(u);
        double wv = m / u;
        out[0] = 3 * CloudModel.ALPHA * rr * rr * wv;
        out[1] = f;
        out[2] = -air.n() * air.n() * m;
        out[3] = wv;
    }

    /** Rise-step index at age t, s (beyond table, last). */
    private double step(double t) {
        return Math.max(0, Math.min(z.length - 1.0, t / DT));
    }

    private static double at(double[] a, double s) {
        int i = (int) Math.min(a.length - 2, Math.max(0, Math.floor(s)));
        if (a.length == 1) return a[0];
        double f = Math.min(1, s - i);
        return a[i] + (a[i + 1] - a[i]) * f;
    }

    /** Centre height at age t, m. */
    public double height(double t) {
        return at(z, step(t));
    }

    /** Thermal radius (MTT top-hat) at age t, m; unchanged after apex. */
    public double radius(double t) {
        return at(r, step(t));
    }

    /** Rise speed, m/s. */
    public double rise(double t) {
        return Math.max(0, at(w, step(t)));
    }

    /** Centre wind drift over t, m (x and z). */
    public double driftX(double t) {
        return at(dx, step(t)) + air.windX() * air.wind(topZ) * beyond(t);
    }

    public double driftZ(double t) {
        return at(dz, step(t)) + air.windZ() * air.wind(topZ) * beyond(t);
    }

    /** Seconds by which age t exceeds rise table. */
    private double beyond(double t) {
        return Math.max(0, t - (z.length - 1) * DT);
    }

    /** Thermal gas temperature at age t, K: T = T_a / (1 − g'/g). */
    public double kelvin(double t) {
        return CloudModel.T_AIR / (1 - Math.min(0.9, Math.max(0, at(gp, step(t))) / CloudModel.G));
    }

    /**
     * Horizontal cloud σ at age t, m: thermal (sphere R — σ = R/√5) and air turbulence from first second, in
     * quadrature (air vortices and thermal entrainment mix independently). Previously air acted only after
     * apex (missile: 2–5 min), keeping cloud a smooth growing sphere throughout (complaint 23.10: “holds its shape”).
     * ⚠ Briggs is for a plume (with meanders; a single-puff cloud grows slower initially) — approximation.
     */
    public double sigmaH(double t) {
        double s0 = radius(t) / Math.sqrt(5);
        double s = CloudModel.spreadH(air.spread(), after(t));
        return Math.sqrt(s0 * s0 + s * s);
    }

    public double sigmaV(double t) {
        double s0 = radius(t) / Math.sqrt(5);
        double s = CloudModel.spreadV(air.spread(), after(t));
        return Math.sqrt(s0 * s0 + s * s);
    }

    /**
     * Cloud wind path over age t, m: wind at current altitude (at least 1 m/s: air mixes even in calm) for all
     * time since release.
     */
    private double after(double t) {
        double u = Math.max(1, Math.hypot(air.windX(), air.windZ()) * air.wind(height(t)));
        return u * Math.max(0, t);
    }
}
