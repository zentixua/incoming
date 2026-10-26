package ua.zentix.incoming.client.fx.smoke;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Smoke in brief puffs — client cloud source (drawn by baked effects-layer billows, {@code far/SmokePuffs}): each
 * puff is a warm billow in one thermal ({@link Thermal}, same MTT law as blast and launch clouds): rises by
 * its own heat, drifts with wind, spreads and fades while optical depth across centre exceeds
 * {@link CloudModel#SEEN}. Draws smoke from things burning or smouldering too briefly and small for a Briggs
 * plume: flying burning debris (a puff for each path segment), wisps from surfaces ignited by nuclear flash.
 * <p>
 * Puff matter is soot (black, {@link CloudModel#SOOT_K}) or light smouldering aerosol: own mass extinction and
 * albedo, enters billow as dust with same extinction ({@link SmokeGrid#dustK}).
 * <p>
 * Thermals of same buoyancy and initial radius (within {@link #BIN}) share one calculation: rise integrates
 * over thousands of steps, while there are hundreds of puffs.
 */
public final class Puffs implements SmokeGrid.Source {
    /** Thermals sharing calculation match buoyancy and radius within this factor (height error ∝ B^¼ — up to 4 %). */
    static final double BIN = Math.sqrt(2);
    /**
     * Thermal cache: air rounded to increments not affecting rise (N — 0.001 s⁻¹, wind — 0.5 m/s), buoyancy and
     * radius to {@link #BIN}; last {@value #CACHED} calculations. Each source has its own air object
     * ({@code FarBlasts.air} by time of day), each calculation thousands of steps: object identity would reset
     * cache on every puff.
     */
    static final int CACHED = 64;
    private static final Map<String, Thermal> CACHE = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Thermal> e) {
            return size() > CACHED;
        }
    };

    private record Puff(double x, double y, double z, double born, double until, Thermal thermal, double soot, double dust,
                        double life) {}

    private final CloudModel.Air air;
    private final float dustR, dustG, dustB;
    private final long seed;
    private final List<Puff> puffs = new ArrayList<>();
    /** No more new puffs (source exhausted or left world). */
    private boolean closed;
    /** Grid clock of last puff: no new ones beyond {@link #QUIET} exhausts source itself (debris fell or vanished). */
    private double lastFed = Double.NaN;
    static final double QUIET = 1;
    private boolean gone;

    /**
     * @param air    local air
     * @param albedo smouldering-aerosol screen colour (soot has its own black): albedo from {@link SmokeGrid#dustAlbedo}
     */
    public Puffs(CloudModel.Air air, float albedoR, float albedoG, float albedoB, long seed) {
        this.air = air;
        this.dustR = SmokeGrid.dustAlbedo(albedoR);
        this.dustG = SmokeGrid.dustAlbedo(albedoG);
        this.dustB = SmokeGrid.dustAlbedo(albedoB);
        this.seed = seed;
    }

    /** Thermal from buoyancy of {@code heat}, J, with initial {@code kelvin} temperature and radius at least {@code r0}. */
    static Thermal thermal(CloudModel.Air air, double heat, double kelvin, double r0) {
        long kb = Math.round(Math.log(Math.max(heat, 1)) / Math.log(BIN)), kr = Math.round(Math.log(Math.max(r0, 0.05)) / Math.log(BIN));
        long kt = Math.round(kelvin / 50);
        String key = kb + "/" + kr + "/" + kt + "/" + Math.round(air.n() * 1000) + "/" + Math.round(air.windX() * 2) + "/" + Math.round(air.windZ() * 2)
                + "/" + Math.round(air.shear() * 100) + "/" + air.spread() + "/" + air.site().key();
        return CACHE.computeIfAbsent(key, k -> new Thermal(Thermal.buoyancy(Math.pow(BIN, kb)), CloudModel.G * (1 - CloudModel.T_AIR / Math.max(kt * 50.0, CloudModel.T_AIR + 1)),
                Math.pow(BIN, kr), air));
    }

    /**
     * Puff at (x, y, z) at {@code seconds} (grid clock): {@code heat}, J, gas at {@code kelvin} in sphere at least
     * {@code r0} m; {@code soot}, kg; smouldering {@code aerosol}, kg, with extinction {@code k} m²/kg; visible
     * until at most {@code until} (grid clock; nuclear wave sweeps away wisps), NaN — no deadline.
     */
    public void add(double x, double y, double z, double seconds, double heat, double kelvin, double r0, double soot, double aerosol, double k,
                    double until) {
        if (closed || gone) return;
        lastFed = seconds;
        Thermal th = thermal(air, heat, kelvin, r0);
        double dust = aerosol * k / SmokeGrid.dustK(dustR, dustG, dustB);
        double life = life(th, soot, dust);
        if (life <= 0) return;
        puffs.add(new Puff(x, y, z, seconds, until, th, soot, dust, life));
    }

    /** Puff visible duration, s: while centre-crossing optical depth exceeds threshold ({@link CloudModel#SEEN}). */
    private double life(Thermal th, double soot, double dust) {
        double ext = CloudModel.SOOT_K * soot + SmokeGrid.dustK(dustR, dustG, dustB) * dust;
        if (tau(th, ext, 0) < CloudModel.SEEN) return 0;
        double lo = 0, hi = CloudModel.LIFE_MAX;
        if (tau(th, ext, hi) >= CloudModel.SEEN) return hi;
        for (int i = 0; i < 30; i++) {
            double mid = 0.5 * (lo + hi);
            if (tau(th, ext, mid) >= CloudModel.SEEN) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    private static double tau(Thermal th, double ext, double t) {
        return ext / (2 * Math.PI * th.sigmaH(t) * th.sigmaV(t));
    }

    /** No new puffs: source leaves after existing puffs fade. */
    public void close() {
        closed = true;
    }

    /** World change, rewind: smoke vanishes immediately. */
    public void end() {
        gone = true;
        puffs.clear();
    }

    /** Live puffs. */
    public int size() {
        return puffs.size();
    }

    @Override
    public boolean emit(double seconds, SmokeGrid.Sink out) {
        if (gone) return false;
        if (!Double.isNaN(lastFed) && seconds - lastFed > QUIET) closed = true;
        puffs.removeIf(p -> seconds - p.born >= p.life || !Double.isNaN(p.until) && seconds >= p.until);
        for (Puff p : puffs) {
            double t = Math.max(0, seconds - p.born);
            Thermal th = p.thermal;
            // puff still dense at lifetime limit fades over final tenth of life (like blast cloud)
            double fade = p.life < CloudModel.LIFE_MAX ? 1 : Math.min(1, (p.life - t) / (0.1 * p.life));
            out.blob(p.x + th.driftX(t), p.y + th.height(t), p.z + th.driftZ(t), th.sigmaH(t), th.sigmaV(t), p.soot * fade, p.dust * fade, 0,
                    dustR, dustG, dustB, th.kelvin(t), th.rise(t));
        }
        return !puffs.isEmpty() || !closed;
    }

    @Override
    public boolean ended() {
        return gone || closed && puffs.isEmpty();
    }

    @Override
    public long seed() {
        return seed;
    }

    /** Billow frame from youngest puff's growth (old billows already large, their frame barely changes). */
    @Override
    public double puffGrowth(double seconds) {
        if (puffs.isEmpty()) return 2;
        Puff p = puffs.getLast();
        Thermal th = p.thermal;
        return th.sigmaH(Math.max(0, seconds - p.born)) / Math.max(th.sigmaH(0), 1e-6);
    }
}
