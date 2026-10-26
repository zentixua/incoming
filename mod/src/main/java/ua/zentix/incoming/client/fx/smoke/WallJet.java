package ua.zentix.incoming.client.fx.smoke;

/**
 * Ground wall jet from an engine jet striking at an angle: where it carries dust. Without Minecraft.
 * <p>
 * <b>Directional shares.</b> Jet striking plane at angle α spreads in all directions unequally: more forward
 * (along jet horizontal component). Flow share per azimuth radian φ from “forward” is
 * w(φ) = sin³α / (2π·(1 − cos α·cos φ)²) (Hasson, Peck, “Thickness distribution in a sheet formed by impinging jets”,
 * AIChE J. 10(5), 1964: inviscid turning, sheet speed same as jet; one jet on plane gives same half-pattern as
 * two opposing jets). Vertical (α = 90°): equal in all directions; shallow: almost all forward. All shares have
 * same speed, so momentum by direction follows same w(φ), summing along plane to M·cos α: exactly what jet
 * carried along ground (frictionless ground removes only vertical component). ⚠ Law for inviscid turning;
 * submerged turbulent jet follows it only in turning region (Beltaos 1976 measured inclined air jets —
 * description says impact pattern nearly axisymmetric shifted forward; we do not have data).
 * <p>
 * w(φ) is Kepler's mean-anomaly law: dM/dν = (1 − e²)^{3/2}/(1 + e·cos ν)² at e = cos α, ν = φ − π. So equal-share
 * directions are exact: equal M steps, inverse Kepler equation ({@link #azimuths}).
 * <p>
 * <b>Range.</b> Wall jet is a “puff”: impulse per radian i(φ, t) = I(t)·w(φ) (I(t) — impulse jet delivered to ground
 * by this instant, ṁ·u over feed) carries entrained air, a layer of thickness S·r for radial wall jet
 * (S = {@value #SPREAD}: plane wall jet 0.073 — Launder, Rodi, Annu. Rev. Fluid Mech. 15, 1983, (est.) from memory;
 * radial growth 1/0.83 times larger — Lin, CSME 2008), mass ρ·S·r³/3 per radian. Without ground friction
 * (est.) (upper bound), i(t) = (ρSr³/3)·dr/dt: r⁴ = r₀⁴ + 12·∫i dt/(ρS) (short impulse: ∫i dt = i·t; constant feed
 * of duration T with final impulse i, while ongoing: i·t²/(2T)). Puff pushes dust while front faster than
 * surface wind (dr/dt = 3i/(ρSr³)); afterward wind carries dust — front stops at (3i/(ρSU))^{1/3}. Puff dust
 * follows its mass (∝ r′² radially): mean at ¾ r, second moment ⅗ r².
 */
public final class WallJet {
    /** Radial wall-jet thickness growth: plane 0.073 (Launder, Rodi 1983) / 0.83 (Lin 2008). */
    public static final double SPREAD = 0.073 / 0.83;
    /** Equal-share directions. */
    public static final int DIRECTIONS = 32;
    /** cos α stays this far below 1: a ground-parallel jet's “forward” share is an arbitrarily narrow peak. */
    static final double GRAZING = 1e-3;

    private WallJet() {}

    /**
     * Wall-jet flow share per azimuth radian φ from “forward” for jet at ground angle α (sin α = sinA; shallower
     * than {@link #GRAZING} uses that limit, same as {@link #azimuths}).
     */
    public static double share(double sinA, double phi) {
        double c = Math.min(1 - GRAZING, Math.sqrt(Math.max(0, 1 - sinA * sinA)));
        double s2 = 1 - c * c, k = 1 - c * Math.cos(phi);
        return s2 * Math.sqrt(s2) / (2 * Math.PI * k * k);
    }

    /**
     * {@link #DIRECTIONS} azimuths from “forward”, each equal flow share (1/n) by {@link #share}: midpoints of n
     * equal mean-anomaly steps, inverse Kepler equation (e = cos α, ν = φ − π).
     */
    public static double[] azimuths(double sinA, int n) {
        double e = Math.min(1 - GRAZING, Math.sqrt(Math.max(0, 1 - sinA * sinA)));
        double[] out = new double[n];
        double ratio = Math.sqrt((1 + e) / (1 - e));
        for (int k = 0; k < n; k++) {
            double m = 2 * Math.PI * (k + 0.5) / n - Math.PI;
            // E − e·sin E = M, Newton from ±π: converges for any e < 1 (Charles, Tatum, Celest. Mech. 69, 1998)
            double big = Math.signum(m) * Math.PI;
            for (int it = 0; it < 50; it++) {
                double f = big - e * Math.sin(big) - m, d = 1 - e * Math.cos(big);
                double step = f / d;
                big -= step;
                if (Math.abs(step) < 1e-13) break;
            }
            double nu = 2 * Math.atan(ratio * Math.tan(big / 2));
            out[k] = nu + Math.PI;
        }
        return out;
    }

    /**
     * Puff front in direction with impulse i per radian (N·s/rad) and time integral iTime (N·s²/rad), starting
     * at radius r0; stops where surface wind wind (m/s) overtakes it.
     */
    public static double reach(double i, double iTime, double r0, double wind) {
        if (i <= 0 || iTime <= 0) return r0;
        double r = Math.pow(Math.pow(r0, 4) + 12 * iTime / (CloudModel.RHO * SPREAD), 0.25);
        if (wind > 0) r = Math.min(r, Math.max(r0, Math.cbrt(3 * i / (CloudModel.RHO * SPREAD * wind))));
        return r;
    }
}
