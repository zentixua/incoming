package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.client.fx.plume.Motor;
import ua.zentix.incoming.nuclear.model.DustModel;

/**
 * Launch cloud at launcher: engine products while jet strikes ground and the dust it raises form one warm
 * cloud ({@link Thermal}, same law as blast cloud): rises, drifts with wind and spreads like any client cloud
 * (drawn by baked effects-layer billows, {@code far/SmokePuffs}; those ticks' trail ribbon points carry only
 * nozzle-to-ground jet column, {@link #column}). Without Minecraft: caller supplies ground position, soil and air.
 * <p>
 * <b>When products form a cloud rather than trail.</b> A jet remains a jet while momentum carries it: at Morton
 * length L_M = M^¾/F^½ (M = ṁ·u/ρ — momentum flux, F = g·Q̇/(ρ·c_p·T) — buoyancy flux; Morton 1959, Fischer et al.,
 * “Mixing in Inland and Coastal Waters”, 1979, §9.1), buoyancy overtakes momentum. If it reaches ground sooner
 * (half-width 0.094 of length — Pope, “Turbulent Flows”, 2000, table 5.1), it spreads along ground as a wall jet,
 * and products remain by launcher as cloud; farther, trail ({@code TrailModel}). Rocket ground launch clouds
 * work this way in REEDM too (Bjorklund et al., NASA CR-3646, 1982: ground cloud is exhaust while rocket is below
 * cloud-formation height; (est.) from memory), but criterion here is jet length, not height: Grad and Shahed leave at an angle.
 * <p>
 * <b>Heat</b> is engine heat released into air ({@link Motor#heatFlow}), like trail; <b>initial state</b> is jet
 * at ground: radius is its half-width there, temperature is axial excess, ΔT = ΔT₀·{@value #DECAY}·d′/ℓ
 * (d′ = d·√(ρ_exit/ρ) — variable-density diameter, Chen and Rodi 1980; (est.) decay constant 5 is for a scalar in a
 * round jet, from memory). Feed is shorter than rise (fractions of seconds to seconds vs minutes): cloud is
 * one thermal containing all heat since feed start.
 * <p>
 * <b>Dust</b> is an order-of-magnitude estimate (est.): {@value #DUST_PER_KG} kg dust per kg exhaust striking loose
 * soil (Apollo landings: tonnes of regolith per ~tonne fuel in final tens of seconds, Metzger et al., 2011),
 * × soil looseness (porosity, as for ground sound: stone and concrete 0). Half the dust rises with cloud, half
 * stays near ground — same estimate as blast cloud ({@link CloudModel#CLOUD_SHARE}); settles by {@link DustModel} bins.
 * <p>
 * <b>Ground dust</b> is carried by wall jet ({@link WallJet}): an oblique jet (Grad, Shahed, missile on inclined
 * rail) carries more forward, along jet horizontal component (Hasson–Peck); vertical jet distributes equally
 * in all directions. Ground-dust billow is a Gaussian elongated by a segment along jet: its mean and along/
 * crosswise spread match dust in the puff by direction; air then spreads it (Briggs), wind carries it.
 */
public final class LaunchCloud implements SmokeGrid.Source {
    /** Round-jet half-width per length (Pope 2000, table 5.1). */
    public static final double SPREAD = 0.094;
    /** Axial jet temperature-excess decay: ΔT/ΔT₀ = DECAY·d′/x (est.) (Chen, Rodi 1980 — from memory). */
    static final double DECAY = 5;
    /** Dust per kg exhaust striking loose soil, kg (est.) (order of magnitude). */
    public static final double DUST_PER_KG = 1;
    /** Round-jet axial speed decay: u/u₀ = B·d′/x, B = 5.8 (Pope 2000, table 5.1; Hussein et al. 1994). */
    static final double JET_DECAY = 5.8;

    private final CloudModel.Air air;
    private final double groundY;
    private final float dustR, dustG, dustB;
    private final double loose;
    /** Grid clock at feed start, s. */
    private final double born;
    /** Fed: heat, J; product soot and droplets, dust, kg; jet impact position (heat-weighted), m; axial ΔT and radius there. */
    private double heat, soot, water, dust, sumX, sumZ, sumT, sumR;
    /**
     * Jet impulse delivered to ground during feed, N·s: total and world components (x, down, z); also Σ dI·τ
     * (τ — feed instant since cloud start, s): ∫₀ᵗ I dt = I·t − Σ dI·τ for the puff ({@link WallJet#reach}).
     */
    private double impulse, pushX, pushDown, pushZ, pushMoment;
    /** Wall jet from build ({@link #build}): “forward” (horizontal unit vector), equal-share azimuths and flow share per radian at each. */
    private double aheadX = 1, aheadZ;
    private double[] fanAzimuth = new double[0], fanShare = new double[0];
    private Thermal thermal;
    private double life;
    private final double[] matter = new double[2];
    private boolean gone;
    /** Heat used at last thermal build and feed start, for rebuild ({@link #REBUILD}). */
    private double builtHeat;
    /** Grid clock at last feed, s: while feeding, thermal rebuilds only by {@link #REBUILD}. */
    private double lastFeed;

    /**
     * Rebuild thermal when heat grows by this factor since last build: height ∝ Q^¼, rise error ≤ 6 % (build
     * takes thousands of RK4 steps; ICBM feed lasts hundreds of ticks); after feed, use all heat ({@link #emit}).
     */
    static final double REBUILD = 1.25;

    /**
     * @param groundY ground height at launcher
     * @param loose   soil looseness 0..1 (porosity)
     * @param dustR   soil screen colour (dust albedo from {@link SmokeGrid#dustAlbedo})
     * @param seconds current grid clock
     */
    public LaunchCloud(CloudModel.Air air, double groundY, double loose, float dustR, float dustG, float dustB, double seconds) {
        this.air = air;
        this.groundY = groundY;
        this.loose = Math.max(0, Math.min(1, loose));
        this.dustR = SmokeGrid.dustAlbedo(dustR);
        this.dustG = SmokeGrid.dustAlbedo(dustG);
        this.dustB = SmokeGrid.dustAlbedo(dustB);
        this.born = seconds;
    }

    /** Morton length of engine motor jet at ground, m. */
    public static double mortonLength(Motor motor) {
        double m = motor.exhaust() * motor.velocity() / CloudModel.RHO;
        double f = Thermal.buoyancy(motor.heatFlow());
        return f <= 0 ? Double.POSITIVE_INFINITY : Math.pow(m, 0.75) / Math.sqrt(f);
    }

    /**
     * Where jet from nozzle height metres above ground, pointing with vertical component jetY (−1 — vertically
     * down), touches ground with its half-width: axial path, m; infinity — no contact.
     */
    public static double reach(double height, double jetY) {
        double down = -jetY + SPREAD * Math.sqrt(Math.max(0, 1 - jetY * jetY));
        if (down <= 1e-6) return Double.POSITIVE_INFINITY;
        return Math.max(0, height) / down;
    }

    /** Engine jet strikes ground before becoming a plume: products form launch cloud rather than trail. */
    public static boolean impinges(Motor motor, double height, double jetY) {
        return reach(height, jetY) <= mortonLength(motor);
    }

    /**
     * Another dt seconds of engine motor with nozzle at (x, y, z), jet unit direction (jx, jy, jz): strikes
     * ground before Morton length — products and dust enter cloud, true; otherwise false (trail).
     */
    public boolean feed(Motor motor, double dt, double x, double y, double z, double jx, double jy, double jz) {
        return feed(motor, dt, SmokeGrid.seconds(0), x, y, z, jx, jy, jz);
    }

    /**
     * Same; seconds is this feed instant on grid clock, same time base as cloud start (packet path includes its
     * lag, like {@code born}): drives puff impulse history.
     */
    public boolean feed(Motor motor, double dt, double seconds, double x, double y, double z, double jx, double jy, double jz) {
        double l = reach(y - groundY, jy);
        if (!(l <= mortonLength(motor)) || dt <= 0) return false;
        double q = motor.heatFlow() * dt;
        if (motor.smoke() != null) {
            Matter.of(motor.smoke(), motor.smokeFlow() * dt, matter);
            soot += matter[0];
            water += matter[1];
        }
        dust += DUST_PER_KG * loose * motor.exhaust() * dt;
        double d = diameter(motor);
        double hot = motor.exitTemperature() - CloudModel.T_AIR;
        double dT = Math.max(1, hot * Math.min(1, DECAY * d / Math.max(l, 1e-6)));
        heat += q;
        double push = motor.exhaust() * motor.velocity() * dt;
        impulse += push;
        pushMoment += push * Math.max(0, seconds - born);
        pushX += push * jx;
        pushDown -= push * jy;
        pushZ += push * jz;
        sumX += q * (x + jx * l);
        sumZ += q * (z + jz * l);
        sumT += q * dT;
        sumR += q * (SPREAD * l + motor.exitRadius());
        lastFeed = SmokeGrid.seconds(0);
        if (thermal == null || heat >= REBUILD * builtHeat) build();
        return true;
    }

    /** Variable-density diameter d′ = d·√(ρ_exit/ρ) of all nozzles together, m (Chen, Rodi 1980; Thring, Newby 1953). */
    static double diameter(Motor motor) {
        return 2 * motor.exitRadius() * Math.sqrt(motor.nozzles() * motor.exitDensity() / CloudModel.RHO);
    }

    /**
     * Trail-matter fraction in the jet column x metres behind nozzle while jet strikes ground. These ticks'
     * products go into ground cloud, but not instantaneously: jet carries them from nozzle to ground, forming
     * visible rocket-to-cloud launch column (without it, rear launch frame was empty under missile while jet
     * reached ground — empty up to ~38 m altitude for missile booster). Matter follows axial jet speed
     * u(x) = u₀·B·d′/x (no faster than exit), ṁ/u(x) per column metre; projectile-laid trail carries ṁ·inverse
     * (inverse — seconds per metre of projectile path, {@code TrailModel.inverseSpeed}). Fraction is
     * 1/(u(x)·inverse), at most 1. Jet speed relative to air, without projectile velocity (during launch small
     * compared with hundreds of m/s jet).
     *
     * @param x           axial path from nozzle, m (path projectile travelled since it was at this point)
     * @param inverseSpeed seconds per trail metre when point was laid
     */
    public static double column(Motor motor, double x, double inverseSpeed) {
        double u = motor.velocity() * Math.min(1, JET_DECAY * diameter(motor) / Math.max(x, 1e-6));
        return Math.min(1, 1 / Math.max(u * inverseSpeed, 1e-12));
    }

    /** Thermal from all heat fed. */
    private void build() {
        double t0 = CloudModel.T_AIR + sumT / heat;
        thermal = new Thermal(Thermal.buoyancy(heat), CloudModel.G * (1 - CloudModel.T_AIR / t0), Math.max(0.5, sumR / heat), air);
        builtHeat = heat;
        life = lifeSeconds();
        fan();
    }

    /**
     * Wall jet from total impulse fed: ground angle from summed jet impulses (vector), “forward” from its
     * horizontal component; equal-flow-share azimuths and impulse per radian at each ({@link WallJet}).
     */
    private void fan() {
        double flat = Math.hypot(pushX, pushZ), all = Math.hypot(flat, pushDown);
        if (impulse <= 0 || all <= 0) {
            fanAzimuth = new double[0];
            fanShare = new double[0];
            return;
        }
        if (flat > 1e-9 * all) {
            aheadX = pushX / flat;
            aheadZ = pushZ / flat;
        }
        double sinA = Math.max(0, pushDown) / all;
        fanAzimuth = WallJet.azimuths(sinA, WallJet.DIRECTIONS);
        // share per radian for each direction; impulse from instant (fanMoments)
        fanShare = new double[fanAzimuth.length];
        for (int k = 0; k < fanAzimuth.length; k++) fanShare[k] = WallJet.share(sinA, fanAzimuth[k]);
    }

    /**
     * Wall-jet dust after t seconds: centre from impact along “forward” and across, along/cross spread (σ²),
     * mean radius, m, from {@link WallJet#DIRECTIONS} equal-share directions, each with puff up to its front
     * (mass ∝ r′²: mean ¾ r, second moment ⅗ r²); r0 — jet radius at ground, wind — surface wind, m/s.
     */
    double[] fanMoments(double t, double r0, double wind) {
        int n = fanAzimuth.length;
        double[] out = new double[5];
        if (n == 0) {
            out[2] = out[3] = 0.6 * r0 * r0;
            out[4] = 0.75 * r0;
            return out;
        }
        double mx = 0, my = 0, xx = 0, yy = 0, mr = 0, integral = Math.max(0, impulse * t - pushMoment);
        for (int k = 0; k < n; k++) {
            double w = fanShare[k];
            double r = WallJet.reach(impulse * w, integral * w, r0, wind), c = Math.cos(fanAzimuth[k]), s = Math.sin(fanAzimuth[k]);
            mx += 0.75 * r * c;
            my += 0.75 * r * s;
            xx += 0.6 * r * r * c * c;
            yy += 0.6 * r * r * s * s;
            mr += 0.75 * r;
        }
        mx /= n;
        my /= n;
        out[0] = mx;
        out[1] = my;
        out[2] = Math.max(0, xx / n - mx * mx);
        out[3] = Math.max(0, yy / n - my * my);
        out[4] = mr / n;
        return out;
    }

    /** Billow seed from position and feed start (two nearby clouds have different billows). */
    @Override
    public long seed() {
        return Double.doubleToLongBits(born) * 31 + Double.doubleToLongBits(groundY);
    }

    /** Cloud growth factor since start (horizontal σ), for baked billow frame. */
    @Override
    public double puffGrowth(double seconds) {
        if (thermal == null) return 1;
        double t = Math.max(0, seconds - born);
        return thermal.sigmaH(t) / Math.max(thermal.sigmaH(0), 1e-6);
    }

    /** Cloud received something. */
    public boolean fed() {
        return thermal != null;
    }

    /** Cloud rise (null — nothing fed). */
    public Thermal thermal() {
        return thermal;
    }

    public double heat() {
        return heat;
    }

    /** Soot, droplets and dust, kg. */
    public double soot() {
        return soot;
    }

    public double water() {
        return water;
    }

    public double dust() {
        return dust;
    }

    /** Cloud visible duration, s (optical-depth threshold, like blast cloud). */
    public double life() {
        return life;
    }

    /** Optical depth across cloud centre at age t (raw extinction of products and lofted dust). */
    double tau(double t) {
        double ext = CloudModel.SOOT_K * soot + CloudModel.DROP_K * water
                + DustModel.K_MIX * CloudModel.CLOUD_SHARE * dust * DustModel.aloft(t, thermal.height(t));
        return ext / (2 * Math.PI * thermal.sigmaH(t) * thermal.sigmaV(t));
    }

    private double lifeSeconds() {
        double lo = 0, hi = CloudModel.LIFE_MAX;
        if (tau(hi) >= CloudModel.SEEN) return hi;
        for (int i = 0; i < 40; i++) {
            double mid = 0.5 * (lo + hi);
            if (tau(mid) >= CloudModel.SEEN) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /** No more feed and cloud faded, or world changed ({@link #end}). */
    @Override
    public boolean ended() {
        return gone || thermal != null && SmokeGrid.seconds(0) - born >= life;
    }

    /** Cloud no longer drawn (world change, rewind). */
    public void end() {
        gone = true;
    }

    /**
     * Billows: cloud (products and dust share, thermal) and ground dust — wall jet spreads along ground, then
     * disperses by Briggs (like blast-cloud ground dust), settling by bins.
     */
    @Override
    public boolean emit(double seconds, SmokeGrid.Sink out) {
        if (gone) return false;
        if (thermal == null) return true;
        // feed ended (two ticks without it) — thermal from all heat
        if (heat > builtHeat && seconds - lastFeed > 0.1) build();
        double t = Math.max(0, seconds - born);
        if (t >= life) return false;
        // cloud still dense at lifetime limit fades over final tenth of life (like blast cloud)
        double fade = life < CloudModel.LIFE_MAX ? 1 : Math.min(1, (life - t) / (0.1 * life));
        double cx = sumX / heat + thermal.driftX(t), cz = sumZ / heat + thermal.driftZ(t), cy = groundY + thermal.height(t);
        double cloudDust = CloudModel.CLOUD_SHARE * dust * DustModel.aloft(t, thermal.height(t)) * fade;
        out.blob(cx, cy, cz, thermal.sigmaH(t), thermal.sigmaV(t), soot * fade, cloudDust, water * fade, dustR, dustG, dustB,
                thermal.kelvin(t), thermal.rise(t));
        double ground2 = Math.hypot(air.windX(), air.windZ()) * air.wind(2);
        double x = Math.max(1, ground2) * t;
        double s0 = thermal.r0 / Math.sqrt(5);
        double[] fan = fanMoments(t, sumR / heat, ground2);
        // across: puff and Briggs spread; along: also segment stretching, Gaussian σ over segment ±A gives σ² + A²/3
        double bh2 = Math.pow(CloudModel.spreadH(air.spread(), x), 2), along = Math.sqrt(3 * Math.max(0, fan[2] - fan[3]));
        double bh = Math.max(s0, Math.sqrt(fan[3] + bh2));
        // wall-jet thickness at its dust centre is S·r at half speed (Gaussian: half-height 1.18σ)
        double bv = Math.max(s0, Math.hypot(CloudModel.spreadV(air.spread(), x), WallJet.SPREAD * fan[4] / 1.18));
        double ground = (1 - CloudModel.CLOUD_SHARE) * dust * DustModel.aloft(t, 2 * bv) * fade;
        double lean = air.wind(2) * t;
        double gx = sumX / heat + aheadX * fan[0] - aheadZ * fan[1] + air.windX() * lean;
        double gz = sumZ / heat + aheadZ * fan[0] + aheadX * fan[1] + air.windZ() * lean;
        out.segment(gx, groundY + bv, gz, aheadX * along, 0, aheadZ * along, bh, bv, 0, ground, 0, dustR, dustG, dustB, CloudModel.T_AIR, 0);
        return true;
    }
}
