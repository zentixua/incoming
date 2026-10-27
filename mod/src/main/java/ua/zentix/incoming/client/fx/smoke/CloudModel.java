package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.util.LocalWind;
import ua.zentix.incoming.util.Stability;
import ua.zentix.incoming.warhead.BuriedBlast;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.CraterModel;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.FuelFireball;
import ua.zentix.incoming.nuclear.model.FuelPool;

/**
 * Conventional explosion cloud from laws, without Minecraft: what rises, where, and how long it stays visible follow charge, fuel, ground
 * and air. Meters, seconds, kilograms; a block is a meter. Rendered by the smoke grid ({@link SmokeGrid}: the cloud is
 * Gaussian clumps {@link #blobs}), the flash and distant view ({@code FarBlasts}).
 * <p>
 * <b>Heat.</b> The cloud rises from heat left in the gas: {@link #HE_HEAT} per kg TNT for explosives; for fuel,
 * heat of combustion minus the fireball's radiated share ({@link #RADIANT}). Buoyancy of total heat Q is
 * B = g·Q / (ρ·c_p·T) (m⁴/s²).
 * <p>
 * <b>Rise</b> is a Morton–Taylor–Turner thermal (Proc. R. Soc. A 234, 1956; equations in Bhamidipati &amp; Woods,
 * JFM 2023, eqs. 2.1–2.3): a sphere of radius R rises at W, entraining air through its surface at αW
 * (α = {@value #ALPHA}, MTT experiments), accelerates from buoyancy g', and loses it in stable air (Brunt–Väisälä frequency N):
 * d(R³)/dt = 3α·R²W, d(R³W)/dt = R³g', d(R³g')/dt = −N²·R³W. A point source's maximum height is
 * (8F/(α³N²))^¼ with F = R³g' (B = 4π/3·F), reached in π/N seconds; radius is α·height. The cloud then stays at this height. From the first
 * second, air turbulence spreads it (Pasquill–Gifford, Briggs open-country formulas over wind travel
 * x = u·t, in quadrature with the thermal radius), and wind grows with height: a stack of layers ({@link #LAYERS}), each with
 * its own wind, tilts and stretches downwind. The fireball sets height and radius in the first seconds: the initial volume is where
 * heat Q maintains the ball temperature ({@link #FUEL_BALL_K} or the end of the explosive ball).
 * <p>
 * <b>Fireballs.</b> Fuel ball: CCPS (1994), “Guidelines for Evaluating the Characteristics of Vapor Cloud
 * Explosions, Flash Fires and BLEVEs”: D = 5.8·M^⅓ m, burns for 0.45·M^⅓ s (M &lt; 30 t; M is ball fuel excluding the crater share,
 * {@link #POOL_SHARE}), center rises to 0.75·D; emits
 * 200–350 kW/m²: a {@value #FUEL_BALL_K} K black body (midpoint). Detonation-product ball: radius 1.7·W^⅓ m, shines for
 * 0.2·W^0.35 s (as in {@code FarBlasts}; original source not found, est.), 3000 K → 1500 K (detonation products:
 * 3000–5000 K, Ciccarelli et al. review in Shock Waves 2021; end value is est.). After burning, the ball does not go dark;
 * it cools by entraining air: the same MTT thermal ({@link #cooling}), starting from the ball at burn end (its radius and
 * temperature). Gas temperature T = T_a/(1 − g'/g) falls as the ball grows; it shines while hotter than the Draper point
 * ({@value #DRAPER_K} K). Spectrum follows Planck, but emissivity comes from the {@link FireballRadiation} budget: visible
 * energy for HE, total radiated energy for fuel, including cooling. Size and timing stay unchanged. Additional
 * radiative gas cooling is not modeled; temperature and temporal profile shape remain approximations.
 * <p>
 * <b>Crater fuel.</b> Fuel not sprayed into the ball remains in the crater, soaking the ground and burning out there:
 * share {@link #POOL_SHARE}, shared server law {@link FuelPool} (area is the crater circle, thin-layer burning):
 * ~2.5 min at ~2 MW for Shahed, ~16 min at ~3 MW for missile. The server decides where it burns: Minecraft fire on blocks where
 * fuel landed and drained ({@code warhead/FuelFires}); above it, a Briggs plume ({@link FirePlume}) with fuel soot while
 * it burns ({@code FireSmoke.pool}).
 * <p>
 * <b>Matter.</b> Explosive soot is free carbon in detonation products ({@link BlastSource.Explosive#freeCarbon}), of
 * which afterburning leaves share {@link #SOOT_SURVIVES} (est.); fuel soot uses the {@link FuelPool.Fuel} yield.
 * Crater dust is material destroyed at the target ({@link BlastTarget}: a {@link CraterModel#chargeVolume} bowl for the material's soil,
 * diameter 0.65·W^⅓ m, depth one quarter, paraboloid, the same law the server digs with; rock and concrete use 0.7 diameter;
 * a slab over void uses a breach; × target material bulk density). The material's fines share becomes airborne
 * ({@link BlastTarget.Matter#fines}): loose material and planks use {@link #DUST_LOFTED} (2%, est.), cohesive material (rock, concrete,
 * masonry) uses {@code BlastTarget.FINES_CRUSHED} (PM10 from contact blasts on concrete, Costa 2025; ⚠ est.).
 * Color is the target material's ({@link BlastTarget.Matter}), rather than soil for every target. Fines split into two flows: those entrained in hot
 * gas rise with the cloud; gas carries the rest along surrounding surfaces as a ground skirt ({@link #crushed},
 * {@link GroundDust#skirt}). Split: {@link #CRUSHED_HOT_SHARE}. Detonations above
 * {@link #CRATER_HOB} product-ball radii (airbursts) form no crater and have no crater dust.
 * <p>
 * <b>Wave dust</b> is {@link GroundDust}: the nuke's law ({@code DustModel}: wind entrainment behind the front, Kuhl layer,
 * bins, stem inflow), from surrounding surfaces, each with its own burst height and material, using the nuclear
 * yield with the same surface pressure ({@link #equivalentKt}); the layer is heavier than air and drains and descends toward
 * the surface below it; an airburst has no dust when ground pressure is below the entrainment threshold. Church supplies only stabilized-cloud size and
 * height ({@link #HE_HEAT}).
 * <p>
 * <b>Optics.</b> Mass extinction: soot {@link #SOOT_K} m²/kg with albedo 0.25 and asymmetry 0.5 (Bond, Bergstrom 2006:
 * absorption 7.5 m²/g at 550 nm); dust {@code DustModel.K_MIX} (geometric optics of nuclear dust grains; as coarse grains settle,
 * extinction follows the fine share, {@code DustModel.extinctionAloft}); condensation drops (5 µm): 3Q/(2ρr) =
 * 600 m²/kg. The cloud stays visible while its transverse optical depth exceeds {@link #SEEN} (Koschmieder contrast threshold).
 */
public final class CloudModel {
    static final double G = 9.80665, RHO = 1.225, CP = 1005, T_AIR = 288.15;
    /** Ambient air around the cloud, K. */
    public static final double T_AIR_K = T_AIR;
    /** Thermal entrainment (MTT 1956). */
    public static final double ALPHA = 0.285;
    /**
     * ISO 2533 standard-atmosphere stability up to 11 km: N² = g/T·(dT/dz + g/c_p), lapse rate −6.5 K/km gives N ≈ 0.0105 s⁻¹.
     */
    public static final double N_STANDARD = Math.sqrt(G / T_AIR * (-0.0065 + G / CP));
    /**
     * Explosive heat in cloud gas, J per kg TNT, from cloud measurements: stabilized chemical-explosion cloud top
     * 92.6·W^0.25 m (W in kg TNT, “average atmosphere”; Church H. W., “Cloud Rise from High-Explosives Detonations”,
     * Sandia SC-RR-68-903, 1969). For an MTT thermal in standard atmosphere this top is (1 + α)·maximum height. This gives
     * 1.3 MJ/kg, 0.31 of TNT's 4.184 MJ/kg energy; blast wave and radiation carry the rest. The MTT law itself yields
     * height ∝ W^¼, as in Church.
     */
    public static final double HE_HEAT;
    /** Fuel-fireball heat share lost to radiation: 0.25–0.4 (CCPS), midpoint, est. */
    public static final double RADIANT = 0.3;
    /** Fuel-ball black body, K: 275 kW/m² (midpoint of CCPS 200–350) = σT⁴. */
    public static final double FUEL_BALL_K = Math.pow(FuelFireball.SURFACE_POWER / 5.670374e-8, 0.25);
    /** Explosive ball: start and end of life, K (⚠ est.: no visible-temperature measurements by age). */
    public static final double HE_BALL_HOT = 3000, HE_BALL_END = 1500;
    /** Soot: mass extinction (before delta-Eddington), m²/kg; albedo and asymmetry. */
    public static final double SOOT_K = 7.5e3 / (1 - 0.25), SOOT_W = 0.25, SOOT_G = 0.5;
    /** Condensation drops, 5 µm: 3·2/(2·1000·5·10⁻⁶), m²/kg; asymmetry. */
    public static final double DROP_K = 600, DROP_G = 0.85;
    /** Free detonation carbon remaining as soot after ball afterburning, est. */
    public static final double SOOT_SURVIVES = 0.2;
    /** Soil density, kg/m³ (crater: {@link CraterModel#chargeVolume}). */
    public static final double SOIL_DENSITY = 1600;
    /** Loose crater-soil share becoming airborne dust finer than ~50 µm, est. (cohesive material: {@code BlastTarget.Matter#fines}). */
    public static final double DUST_LOFTED = 0.02;
    /**
     * Launch cloud ({@link LaunchCloud}): jet-entrained dust share rising with the cloud (the rest stays at ground),
     * est.: no measured partition for launches. Explosion ground dust is the wave layer ({@link GroundDust}).
     */
    public static final double CLOUD_SHARE = 0.5;
    /**
     * Destroyed-material fines share leaving with hot cloud gas; the rest forms a ground skirt ({@link GroundDust#skirt}).
     * Brown et al., “SCM”, ARI-RR-1384 (2004), table 5: η_partition 0.2–0.8, adopted 0.5 (⚠ est.: also
     * marked uncertain there for lack of data).
     */
    public static final double CRUSHED_HOT_SHARE = 0.5;
    /** Destroyed-material ground dust skirt ({@link GroundDust#skirt}), separate from dust deposited on surfaces. */
    public final GroundDust crushed;
    /**
     * Nuclear-explosion energy share in blast: ~0.5 (Glasstone, Dolan, “The Effects of Nuclear Weapons”, 1977, §1.24:
     * about half blast, a third light, the rest radiation); HE TNT equivalent is the blast itself. Ground pressure from
     * W kg TNT matches a ground nuclear W/0.5 kg: Brode ({@code HobBlast}) versus Kinney–Graham
     * ({@code BlastModel}) gives yield ratios 1.4–2.4 over 1–50 psi for 6–1000 kg (CloudModelTest).
     */
    public static final double NUCLEAR_BLAST_SHARE = 0.5;
    /**
     * No crater above this fireball-radius share, as for nuclear explosions
     * ({@code CraterModel.formsCrater}: Glasstone and Dolan, 0.1 ball radius); HE uses the detonation-product ball
     * ({@link #heBall}). ⚠ Our sources give no HE experimental threshold: Kinney and Graham (1985) only state that a charge above
     * ground may form no crater.
     */
    public static final double CRATER_HOB = 0.1;
    /** Cloud stays visible while transverse center optical depth exceeds this (contrast threshold 0.02). */
    public static final double SEEN = 0.02;
    /** Draper point: colder gas does not glow, K. */
    public static final double DRAPER_K = 798;
    /**
     * Gas hotter than this fully shines as fire body: a 1200 K black body is about one hundredth of the fuel ball's brightness
     * (by Wien at 555 nm); beyond this the cooling ball is more smoke than fire, K.
     */
    public static final double GLOW_FULL_K = 1200;
    /**
     * Impact fuel share remaining in the crater to burn out (shared with server, {@link FuelFireball#POOL_SHARE}): the ball
     * burns the rest ({@link FuelFireball#ballKg}), determining its size, cloud-rise heat and soot; crater heat and soot
     * leave in a fire plume ({@code FireSmoke.pool}).
     */
    public static final double POOL_SHARE = FuelFireball.POOL_SHARE;
    /** Maximum cloud lifetime, s (beyond this it is haze over the area). */
    public static final double LIFE_MAX = 3600;

    static {
        // point source in standard atmosphere: top (1 + α)·(8F/(α³N²))^¼ = 92.6·W^¼ gives F per kg TNT
        double top = 92.6 / (1 + ALPHA);
        double f = Math.pow(top, 4) * ALPHA * ALPHA * ALPHA * N_STANDARD * N_STANDARD / 8;
        HE_HEAT = 4 * Math.PI / 3 * f * RHO * CP * T_AIR / G;
    }

    /**
     * Air at the explosion site.
     *
     * @param n       Brunt–Väisälä frequency, 1/s
     * @param windX   x wind 10 m above open field, m/s (world wind; local near-ground wind uses {@link #wind})
     * @param windZ   and z wind
     * @param shear   wind-profile exponent u(z) = u₁₀·(z/10)^p (0.1 daytime, 0.14 neutral, 0.3 night)
     * @param spread  Pasquill stability class (0 is A … 5 is F)
     * @param humidity humidity 0..1 (condensation cloud)
     * @param site    local near-ground wind: buildings, canopy, hill ({@link LocalWind}; field: {@link LocalWind#OPEN})
     */
    public record Air(double n, double windX, double windZ, double shear, int spread, double humidity, LocalWind site, double lift) {
        /** In open field. */
        public Air(double n, double windX, double windZ, double shear, int spread, double humidity) {
            this(n, windX, windZ, shear, spread, humidity, LocalWind.OPEN);
        }

        /** With local near-ground wind {@code site}; heights above the explosion surface. */
        public Air(double n, double windX, double windZ, double shear, int spread, double humidity, LocalWind site) {
            this(n, windX, windZ, shear, spread, humidity, site, 0);
        }

        /**
         * For callers measuring height from a point {@code dz} m above the explosion surface (airburst thermal measures from the
         * burst point): site correction at its actual height above the surface, profile unchanged.
         */
        public Air lifted(double dz) {
            return new Air(n, windX, windZ, shear, spread, humidity, site, lift + dz);
        }

        /** Windless standard atmosphere for checks. */
        public static final Air STANDARD = of(ua.zentix.incoming.util.Air.CALM, 0.5);

        /**
         * Air at the explosion site from world air ({@link ua.zentix.incoming.util.Air}, shared by flight, sound and smoke): stability
         * N, wind-profile exponent and spread class from its stability class ({@link Stability}), wind at 10 m
         * above ground, humidity from it (saturated in rain) or 1 at water.
         *
         * @param humidity humidity at the explosion site, 0..1
         */
        public static Air of(ua.zentix.incoming.util.Air air, double humidity) {
            return of(air, humidity, LocalWind.OPEN);
        }

        /** Same with local near-ground wind {@code site}. */
        public static Air of(ua.zentix.incoming.util.Air air, double humidity, LocalWind site) {
            double c = air.stability(), u = air.wind().at(10, Stability.alpha(c));
            return new Air(air.brunt(), u * air.wind().x(), u * air.wind().z(), Stability.alpha(c), (int) Math.round(c), humidity, site);
        }

        /** Wind at height z (m) above the explosion surface: share of wind at 10 m over field (with site correction). */
        public double wind(double z) {
            return Math.pow(Math.max(z, 1) / 10, shear) * site.factor(z + lift);
        }
    }

    /** Clump sink: Gaussian matter clump (center relative to explosion point, m; horizontal and vertical σ, m). */
    public interface Blobs {
        /**
         * @param x       center relative to explosion point, m
         * @param sh      horizontal σ, m
         * @param sv      vertical σ, m
         * @param soot    soot, kg
         * @param dust    dust, kg
         * @param water   drops, kg
         * @param kelvin  gas temperature, K
         * @param rise    matter rise speed, m/s (clump noise follows it)
         */
        void blob(double x, double y, double z, double sh, double sv, double soot, double dust, double water, double kelvin, double rise);

        /**
         * Dust tube segment (cloud stem) between ends a and b relative to explosion point: cross-section σ, end dust per axis meter, kg/m.
         * A sink without tubes receives Gaussians of the same cross-section spaced by σ along the axis (same matter).
         */
        /**
         * Ground dust clump lifted by the wave from surrounding surfaces ({@link GroundDust}): surface matter, not target matter.
         * A sink without this distinction receives an ordinary clump without soot or rise.
         */
        default void ground(double x, double y, double z, double sh, double sv, double dust, double kelvin) {
            blob(x, y, z, sh, sv, 0, dust, 0, kelvin, 0);
        }

        /**
         * Destroyed-material ground dust skirt clump ({@link #crushed}): target material color and frames. A sink without this distinction receives
         * an ordinary clump without soot or rise.
         */
        default void crushed(double x, double y, double z, double sh, double sv, double dust, double kelvin) {
            blob(x, y, z, sh, sv, 0, dust, 0, kelvin, 0);
        }

        default void tube(double ax, double ay, double az, double sa, double dustA, double bx, double by, double bz, double sb, double dustB,
                          double kelvin, double rise) {
            double tx = bx - ax, ty = by - ay, tz = bz - az, len = Math.sqrt(tx * tx + ty * ty + tz * tz);
            int n = (int) Math.max(1, Math.ceil(len / Math.max(Math.min(sa, sb), 1e-3)));
            for (int i = 0; i < n; i++) {
                double f = (i + 0.5) / n, s = sa + (sb - sa) * f;
                blob(ax + tx * f, ay + ty * f, az + tz * f, s, s, 0, (dustA + (dustB - dustA) * f) * len / n, 0, kelvin, rise);
            }
        }
    }

    public final BlastSource source;
    public final Air air;
    /** What the charge hit: family and matter ({@link BlastTarget}). */
    public final BlastTarget target;
    /** Burst height above ground below the explosion point, m (0 is surface impact). */
    public final double hob;
    /** Stem tube segments (bending with wind shear). */
    static final int STEM_PIECES = 6;
    /**
     * After the fire stage, the cloud is a vertical stack: Gaussian σv splits into {@value #LAYERS} Gaussians σ = {@link #SLICE}·σv
     * at heights {@link #LAYER_Q}·σv (same second moment, σv); each layer follows wind at its
     * height ({@link #shear}): top outruns bottom, tilting and stretching the cloud downwind (Saffman, QJRMS 88,
     * 1962: cloud dispersion in wind shear; also SCIPUFF and CALPUFF puffs). Previously the cloud was one Gaussian and
     * drifted as a whole (23.10 complaint: “like one rigid body”). ⚠ The rising thermal vortex partly resists shear;
     * this is absent here, so the cloud tilts slightly earlier than in reality.
     */
    static final int LAYERS = 3;
    static final double SLICE = 0.85;
    /** Output layer order: middle (heaviest, also the fire-stage ball clump) first. */
    private static final int[] LAYER_ORDER = {1, 0, 2};
    /**
     * Layer heights (in cloud σv) and mass shares: three-point Gauss–Hermite quadrature for centers with variance
     * 1 − {@link #SLICE}²: 0 and ±√(3·(1 − SLICE²)), shares 2/3 and 1/6. Including layer width, same second and fourth
     * moments as one σv Gaussian; no gaps in the vertical profile (edge layer is visible through the middle's edge).
     */
    static final double[] LAYER_Q = {-Math.sqrt(3 * (1 - SLICE * SLICE)), 0, Math.sqrt(3 * (1 - SLICE * SLICE))},
            LAYER_W = {1 / 6.0, 2 / 3.0, 1 / 6.0};
    /** Ground wave dust and stem. */
    public final GroundDust ground;
    /** Total-heat buoyancy, m⁴/s²; F = 3B/(4π). */
    public final double buoyancy;
    /** Soot and crater dust (in cloud), kg. */
    public final double soot, dust;
    /** Fuel ball: diameter, m, and burn duration, s (0 means no fuel). */
    public final double fuelBall, fuelBallSeconds;
    /** Explosive ball: radius, m, and glow duration, s. */
    public final double heBall, heBallSeconds;
    /** End of ball burning (fuel and detonation products), s. */
    public final double burnSeconds;
    /** End of ball glow, s: cooled to Draper point; afterward the cloud is a thermal. */
    public final double fireSeconds;
    /** Energy-budget radiation: HE pulse and fuel emissivity, without eye adaptation. */
    final FireballRadiation radiation;
    /** Ball cooling after burning: MTT thermal from the ball at burn end ({@link #burnSeconds}). */
    public final Thermal cooling;
    /** Ball radius at burn end, m, and its center height then. */
    final double burnRadius, burnHeight;
    /**
     * Crater fuel ({@link FuelPool}; server decides where it burns: fire on blocks, {@code warhead/FuelFires}):
     * law, mass, kg; radius, m; burn duration, s; heat release, W (0 means no fuel).
     */
    public final FuelPool pool;
    public final double poolKg, poolRadius, poolSeconds, poolWatts;
    /** Cloud rise ({@link Thermal}). */
    public final Thermal thermal;
    /** Maximum rise speed, m/s. */
    final double maxRise;
    /** Maximum center height and time reached, s; radius there. */
    public final double topZ, topSeconds, topR;
    /** Cloud visibility duration, s. */
    public final double life;

    public CloudModel(BlastSource source, Air air) {
        this(source, air, false);
    }

    /** {@code onWater}: water explosion, no soil crater or dust (water becomes spray, {@link WaterBlast}). */
    public CloudModel(BlastSource source, Air air, boolean onWater) {
        this(source, air, onWater, 0);
    }

    /**
     * Explosion at {@code hobM} above ground (0 is surface impact; SAM burst uses height above terrain):
     * crater only if the charge digs one ({@link #CRATER_HOB}); wave dust follows ground pressure ({@link GroundDust}).
     */
    public CloudModel(BlastSource source, Air air, boolean onWater, double hobM) {
        this(source, air, onWater, hobM, GroundDust.Ground.BARE);
    }

    /**
     * Same above surrounding {@code ground} by sectors: bare loose soil, turf or hard surface, water ({@link GroundDust}).
     */
    public CloudModel(BlastSource source, Air air, boolean onWater, double hobM, GroundDust.Ground ground) {
        // without target information, bare loose soil (or water)
        this(source, air, BlastTarget.of(onWater ? ua.zentix.incoming.warhead.GroundMaterial.WATER : ua.zentix.incoming.warhead.GroundMaterial.DIRT,
                source.chargeKg(), hobM), hobM, ground);
    }

    /**
     * Explosion at {@code target}, height {@code hobM} above {@code ground}: crushed dust is the material's fines share
     * ({@link BlastTarget.Matter#fines}: loose uses {@link #DUST_LOFTED}; crushed rock and concrete use {@link BlastTarget#FINES_CRUSHED}, less) of destroyed mass
     * ({@link BlastTarget#mass}: material-soil crater law × bulk density; slab uses breach; same law
     * the server digs with), rather than a dry-soil bowl for every target (concrete, planks and slabs previously yielded 1600 kg/m³ dry
     * soil, front F physics.md).
     */
    public CloudModel(BlastSource source, Air air, BlastTarget target, double hobM, GroundDust.Ground ground) {
        this(source, air, target, target.dust(source.chargeKg()), 0.5, hobM, ground);
    }


    /** Whether a {@code tntKg} kg charge at {@code hobM} above ground digs a crater ({@link #CRATER_HOB}). */
    public static boolean digs(double tntKg, double hobM) {
        return tntKg > 0 && hobM <= CRATER_HOB * 1.7 * Math.cbrt(tntKg);
    }

    /** Nuclear yield, kt, with the same ground pressure as {@code tntKg} kg TNT ({@link #NUCLEAR_BLAST_SHARE}). */
    public static double equivalentKt(double tntKg) {
        return Math.max(0, tntKg) * 1e-6 / NUCLEAR_BLAST_SHARE;
    }

    /**
     * Explosion cloud {@code source} whose dust is not its charge crater: {@code dustKg} kg airborne soil, initial
     * cloud size at least {@code size} m (dust lifted without heat starts as a cloud of this size at ground).
     */
    public CloudModel(BlastSource source, Air air, double dustKg, double size) {
        this(source, air, BlastTarget.SOIL, dustKg, size, 0, GroundDust.Ground.BARE);
    }

    private CloudModel(BlastSource source, Air air, BlastTarget target, double dustKg, double size, double hobM, GroundDust.Ground surface) {
        boolean onWater = target.family() == BlastTarget.Family.WATER;
        this.source = source;
        this.air = air;
        this.target = target;
        this.hob = Math.max(0, hobM);
        // the ball burns fuel excluding the crater share; its heat and soot belong to the crater fire plume
        double ballKg = FuelFireball.ballKg(source.fuelKg());
        double fuelHeat = ballKg * source.fuel().heatJPerKg * (1 - RADIANT);
        double heat = source.chargeKg() * HE_HEAT + fuelHeat;
        buoyancy = G * heat / (RHO * CP * T_AIR);
        // contact follows the target (server found packet support; absent that, same height-based digs check), not another height check:
        // coarse distant height would otherwise remove cold dust from a server-confirmed breach (front F review 2)
        boolean contact = !source.underground() && source.chargeKg() > 0 && target.family() != BlastTarget.Family.AIR;
        double cold = contact ? dustKg * (1 - CRUSHED_HOT_SHARE) : 0;
        dust = dustKg - cold;
        soot = source.chargeKg() * source.explosive().freeCarbon() * SOOT_SURVIVES + ballKg * source.fuel().sootYield;
        fuelBall = FuelFireball.diameter(ballKg);
        fuelBallSeconds = FuelFireball.seconds(ballKg);
        heBall = 1.7 * Math.cbrt(source.chargeKg());
        heBallSeconds = 0.2 * Math.pow(source.chargeKg(), 0.35);
        burnSeconds = Math.max(fuelBallSeconds, heBallSeconds);
        // initial volume: heat maintains ball temperature (ideal gas at equal pressure gives g' = g·(1 − T_a/T))
        double ballK = source.fuelKg() > 0 ? FUEL_BALL_K : HE_BALL_END;
        // thermal heights are relative to explosion point: a burst 100 m above a street is not in street shelter
        thermal = new Thermal(buoyancy, G * (1 - T_AIR / ballK), size, air.lifted(hob));
        maxRise = thermal.maxRise;
        topZ = thermal.topZ;
        topR = thermal.topR;
        topSeconds = thermal.topSeconds;
        // cooling: ball at burn end is a thermal with the same heat, its radius and temperature
        boolean fuelLast = source.fuelKg() > 0 && fuelBallSeconds >= heBallSeconds;
        burnRadius = fuelLast ? 0.5 * fuelBall : heBall;
        // same center as the burning ball at the last burning instant ({@link #ballHeight}); previously an airburst had
        // 0.45 radius above explosion point versus center at that point, causing a jump at burn end
        burnHeight = onGround(burnRadius) + lift(burnSeconds);
        cooling = new Thermal(buoyancy, G * (1 - T_AIR / (fuelLast ? FUEL_BALL_K : HE_BALL_END)), Math.max(burnRadius, size), air.lifted(hob));
        fireSeconds = glowEnd();
        radiation = FireballRadiation.of(this);
        // no soil crater on water: fuel spreads as a film; thinner than ~1 mm it cannot sustain burning, as water
        // removes heat (Buist et al., “In situ burning”, oil-spill review: extinction at 1–2 mm; ⚠ from memory)
        pool = onWater ? FuelPool.NONE : FuelPool.of(source.fuel(), source.fuelKg(), source.chargeKg());
        poolKg = pool.kg();
        poolRadius = pool.radius();
        poolSeconds = pool.seconds();
        poolWatts = pool.watts();
        // wave dust: no soil above water (spray is WaterBlast)
        ground = new GroundDust(onWater ? 0 : source.chargeKg(), hob, heBall, surface, air);
        // skirt follows surrounding surfaces (heights relative to explosion point, as wave dust); at a server-found face, from
        // explosion point, not coarse distant ground height (unknown surface heights mean exactly at the face)
        crushed = GroundDust.skirt(onWater ? 0 : source.chargeKg(), target.atFace() ? 0 : hob, cold, surface, air);
        life = Math.max(lifeSeconds(), Math.min(LIFE_MAX, Math.max(ground.life, crushed.life)));
        // The wind integral is source data, like Thermal's table, not work for an invisible source in each frame.
        shear(Math.min(1, life));
    }

    /**
     * Cloud from a bomb breaching outward ({@code source} is its charge share reaching air): dust is ejecta-crater soil
     * ({@link BuriedBlast#craterVolume}; packet carries no soil type, use dry as for surface), share {@link #DUST_LOFTED} airborne.
     */
    public static CloudModel buried(BlastSource source, Air air, BuriedBlast law, boolean onWater) {
        return buried(source, air, law, onWater, GroundDust.Ground.BARE);
    }

    /** Same above surrounding {@code ground} ({@link GroundDust}). */
    public static CloudModel buried(BlastSource source, Air air, BuriedBlast law, boolean onWater, GroundDust.Ground ground) {
        return new CloudModel(source, air, onWater ? BlastTarget.of(ua.zentix.incoming.warhead.GroundMaterial.WATER, source.chargeKg(), 0) : BlastTarget.SOIL,
                onWater ? 0 : law.craterVolume(CraterModel.Soil.DRY) * SOIL_DENSITY * DUST_LOFTED, 0.5, 0, ground);
    }

    /**
     * Dust without heat ({@code source} has charge 0; event supplies sound and position): {@code volume} m³ soil torn by impact or
     * collapse, share {@link #DUST_LOFTED} airborne; ground cloud of {@code size} m spreads and drifts with wind.
     */
    public static CloudModel dust(BlastSource source, Air air, double volume, double size) {
        return new CloudModel(source, air, volume * SOIL_DENSITY * DUST_LOFTED, size);
    }

    /**
     * Gas share at temperature kelvin glowing as fire body (0 is smoke): smooth from Draper point to {@link #GLOW_FULL_K}.
     * Fireball body fades into smoke as smoke appears from the ball by the same share, without a seam.
     */
    public static double glowShare(double kelvin) {
        double u = Math.max(0, Math.min(1, (kelvin - DRAPER_K) / (GLOW_FULL_K - DRAPER_K)));
        return u * u * (3 - 2 * u);
    }

    /**
     * Thick cloud smoke light on screen at noon, white wall is 1: soot reflects R∞ ~0.03, dust ~0.3 (two streams,
     * {@code SmokePuffs.thick}); ⚠ est.: geometric mean 0.1. Cooling-ball glow is seen against it.
     */
    public static final double SMOKE_SEEN = 0.1;

    /**
     * Gas share at temperature kelvin visible as fire body when smoke is lit by sky strength sky (white wall at noon is 1;
     * day 1, night ~8·10⁻⁴, {@code FarView.skyLuminance}): gas glow B(T) and sky light scattered by its
     * smoke ({@link #SMOKE_SEEN}·sky) add in a pixel. Fire is invisible below the contrast threshold ({@link #SEEN},
     * 0.02, Blackwell 1946) against smoke light; smoke is invisible below the same threshold against fire light; between, smooth
     * in log ratio (half at equality), capped by {@link #glowShare(double)} (whether gas glows at all).
     * Exactly 0 and 1 at the ends, not 1 − ε: a bomb-ball clump carries tons of dust, even 0.04% is opaque (dark lump over
     * the ball in daytime's first second, F6 frames). Eye adaptation is excluded: both get the same screen multiplier (with
     * adaptation to the ball at the eye, nearby cold gas would become a black lump at night). A 1260 K bomb ball in daytime (3 s after
     * 1.3 t explosion, brightness ~0.009 white wall) is already smoke, still fire at night; temperature alone kept it fully
     * orange in daytime until 3.7 s (RV2 look gate, bomb at 150 m). ⚠ Fire light on the smoke itself is excluded.
     */
    public static double glowShare(double kelvin, double sky) {
        double g = glowShare(kelvin);
        if (g <= 0) return 0;
        double b = glowLuminance(kelvin), smoke = SMOKE_SEEN * Math.max(sky, 0);
        if (smoke <= 0) return g;
        double edge = -Math.log(SEEN), u = Math.max(0, Math.min(1, (Math.log(b / smoke) + edge) / (2 * edge)));
        return g * u * u * (3 - 2 * u);
    }

    /**
     * Black-body luminance ({@link BlackBody#luminance}) from a log table in 10 K steps from Draper point (colder is
     * 0): fire share is computed for each frame clump; by Wien, brightness between nodes is exponential.
     */
    static double glowLuminance(double kelvin) {
        if (kelvin <= DRAPER_K) return 0;
        double i = Math.min(GLOW_LOG.length - 1.001, (kelvin - DRAPER_K) / 10);
        int k = (int) i;
        return Math.exp(GLOW_LOG[k] + (GLOW_LOG[k + 1] - GLOW_LOG[k]) * (i - k));
    }

    private static final double[] GLOW_LOG = new double[421];

    static {
        for (int i = 0; i < GLOW_LOG.length; i++) GLOW_LOG[i] = Math.log(BlackBody.luminance(DRAPER_K + 10.0 * i));
    }

    /**
     * Cloud soot share at age t already in the cloud thermal rather than cooling ball: 0 at burn end, 1 when the ball
     * cools to Draper point ({@link #fireSeconds}), smooth between (~0.8 s Shahed, ~1.2 s missile, ~1.4 s bomb).
     * ⚠ Joining two approximations: cloud MTT thermal starts at explosion, cooling ball at burn end, and their centers
     * diverge by then (~17 m for bomb); while the ball glows, actual soot is in it.
     */
    double handoff(double t) {
        if (t <= burnSeconds) return 0;
        if (t >= fireSeconds || fireSeconds <= burnSeconds) return 1;
        double u = (t - burnSeconds) / (fireSeconds - burnSeconds);
        return u * u * (3 - 2 * u);
    }

    /** Cooling-ball temperature at age t ≥ {@link #burnSeconds}, K. */
    double coolKelvin(double t) {
        return cooling.kelvin(t - burnSeconds);
    }

    /** When cooling ball drops below Draper point, s (step is a tick). */
    private double glowEnd() {
        double t = burnSeconds;
        while (t < burnSeconds + 120 && coolKelvin(t) > DRAPER_K) t += 0.05;
        return t;
    }

    /** Cloud center height above explosion point at age t, m. */
    public double height(double t) {
        return thermal.height(t);
    }

    /** Thermal radius (MTT top-hat) at age t, m; unchanged after apex. */
    public double radius(double t) {
        return thermal.radius(t);
    }

    /** Rise speed, m/s. */
    public double rise(double t) {
        return thermal.rise(t);
    }

    /** Center wind drift over t, m (x and z). */
    public double driftX(double t) {
        return thermal.driftX(t);
    }

    public double driftZ(double t) {
        return thermal.driftZ(t);
    }

    /** Shear table step, s. */
    private static final double SHEAR_DT = 0.5;
    /** Downwind offset of layer σv above center relative to center, m, in {@link #SHEAR_DT} steps (computed on first call). */
    private double[] shearTable;

    /**
     * How far a layer σv above cloud center outruns it downwind by age t, m: ∫ ½·(u(z + σv) − u(z − σv)) dt,
     * u is wind at height above ground (stability-class power profile, {@link Air#wind}), z is cloud center
     * (above ground including burst height). A layer at q·σv uses q·offset.
     */
    public double shear(double t) {
        if (t <= 0) return 0;
        double[] a = shearTable;
        if (a == null) {
            int n = (int) Math.ceil(Math.min(life, LIFE_MAX) / SHEAR_DT) + 2;
            a = new double[n];
            double w = Math.hypot(air.windX(), air.windZ());
            for (int i = 1; i < n; i++) {
                double tm = (i - 0.5) * SHEAR_DT, z = height(tm) + hob, v = sigmaV(tm);
                a[i] = a[i - 1] + 0.5 * w * (air.wind(z + v) - air.wind(Math.max(z - v, 1))) * SHEAR_DT;
            }
            shearTable = a;
        }
        double f = t / SHEAR_DT;
        int i = (int) Math.min(a.length - 2, Math.floor(f));
        return a[i] + (a[i + 1] - a[i]) * Math.min(1, f - i);
    }

    /**
     * Cloud gas temperature at age t, K: in fire stage, ball (explosive ball cools from {@link #HE_BALL_HOT}
     * to {@link #HE_BALL_END}, fuel ball holds {@link #FUEL_BALL_K}); then cooling ball ({@link #cooling}), then
     * thermal: T = T_a / (1 − g'/g).
     */
    public double kelvin(double t) {
        double gas = thermal.kelvin(t);
        if (t < fuelBallSeconds) return Math.max(gas, FUEL_BALL_K);
        if (t < heBallSeconds) return Math.max(gas, heBallKelvin(t));
        if (t < fireSeconds) return Math.max(gas, coolKelvin(t));
        return gas;
    }

    /** Detonation-product ball temperature at age t, K. */
    public double heBallKelvin(double t) {
        double u = Math.min(1, t / Math.max(heBallSeconds, 1e-6));
        return HE_BALL_HOT + (HE_BALL_END - HE_BALL_HOT) * u;
    }

    /**
     * Glowing-ball radius (larger of the two) at age t, m: balls during burning, then cooling ball (grows by
     * entrainment); 0 when cooled to Draper point.
     */
    public double ballRadius(double t) {
        // cooling ball is a thermal from the volume maintaining ball temperature with its heat (with warhead heat, Shahed's is
        // 4% larger than CCPS ball): transition from ball radius at burn end over cooling, without size jump ({@link #handoff})
        if (t >= burnSeconds) return t < fireSeconds ? cooling.radius(t - burnSeconds) + (burnRadius - cooling.radius(0)) * (1 - handoff(t)) : 0;
        return Math.max(fuelBallRadius(t), heBallRadius(t));
    }

    /**
     * Hottest glowing-ball temperature, K: detonation-product ball while it shines, then fuel ball; 0 means
     * balls are dark.
     */
    public double ballKelvin(double t) {
        if (t < heBallSeconds) return heBallKelvin(t);
        if (t < fuelBallSeconds) return FUEL_BALL_K;
        return t < fireSeconds ? coolKelvin(t) : 0;
    }

    /** Detonation-product ball radius at age t, m (0 when dark): almost full in first percent of life, like ball frames. */
    public double heBallRadius(double t) {
        return t < heBallSeconds ? heBall * (0.3 + 0.7 * (1 - Math.exp(-t / (0.05 * heBallSeconds)))) : 0;
    }

    /** Fuel-ball radius at age t, m (0 when dark): {@link FuelFireball#radius}, same ball causes server burns. */
    public double fuelBallRadius(double t) {
        return FuelFireball.radius(fuelBall, fuelBallSeconds, t);
    }

    /** One body's light for {@link #ballLight} (called every frame on render thread, no new arrays). */
    private final double[] light = new double[4];

    /**
     * Surface light of glowing ball of radius {@link #ballRadius} at age t into {@code out} (r, g, b, luminance Y; white
     * wall at noon is 1): detonation-product and fuel balls together, each a gray body at its temperature with
     * area share (r/R)² (same flux as two balls) and emissivity from {@link #radiation}.
     */
    public void ballLight(double t, double[] out) {
        java.util.Arrays.fill(out, 0, 4, 0);
        double big = ballRadius(t);
        if (big <= 0) return;
        if (t >= burnSeconds) {
            BlackBody.light(coolKelvin(t), out);
            for (int i = 0; i < 4; i++) out[i] *= radiation.cooling();
            return;
        }
        double[] c = light;
        double he = heBallRadius(t), fuel = fuelBallRadius(t);
        if (he > 0) {
            BlackBody.light(heBallKelvin(t), c);
            double emissivity = radiation.he(t, he, heBallKelvin(t));
            for (int i = 0; i < 4; i++) out[i] += c[i] * emissivity * he * he / (big * big);
        }
        if (fuel > 0) {
            BlackBody.light(FUEL_BALL_K, c);
            for (int i = 0; i < 4; i++) out[i] += c[i] * radiation.fuel() * fuel * fuel / (big * big);
        }
    }

    /**
     * Source light at age t, without exposure or sky-light dependence. Smooth fading at Draper point
     * ({@link #glowShare(double)}) is presentation, only reducing the budget. Day/night body-to-smoke transition
     * ({@link #glowShare(double, double)}) is separate screen presentation, not a source-flux change.
     */
    public void emittedLight(double t, double[] out) {
        ballLight(t, out);
        double g = glowShare(ballKelvin(t));
        for (int i = 0; i < 4; i++) out[i] *= g;
    }

    /**
     * Fuel-ball center height, m: grows from impact point, resting on ground at its current radius ({@link #onGround}), and
     * rises to 0.75·D at burn end (CCPS), {@link #lift}. Previously center was D/2 from the first instant, with the ball
     * still one meter: fire appeared in air above the crater, and at 0.1–0.2 s visible-ball center jumped 6 m for Shahed and
     * 14 m for missile (BLASTSEQ D01).
     */
    public double fuelBallHeight(double t) {
        double r = fuelBallRadius(t);
        return r <= 0 ? 0 : onGround(r) + lift(t);
    }

    /** Detonation-product ball center height above explosion point, m: resting at its radius ({@link #onGround}), rising with fuel ball. */
    public double heBallHeight(double t) {
        double r = heBallRadius(t);
        return r <= 0 ? 0 : onGround(r) + lift(t);
    }

    /**
     * Center of ball of radius r growing from explosion point, above it, m: {@link FuelFireball#onGround} (front F physics.md:
     * hemispherical placement at an airburst is unjustified).
     */
    double onGround(double r) {
        return FuelFireball.onGround(r, hob);
    }

    /**
     * Burning fuel-ball rise by age t, m: {@link FuelFireball#lift} (to 0.75·D at burn end). Detonation-product ball
     * inside fuel ball rises with it; without fuel, 0: product rise is the cooling ball ({@link #cooling}).
     */
    double lift(double t) {
        return FuelFireball.lift(fuelBall, fuelBallSeconds, t, hob);
    }

    /**
     * Glowing-body center height, m: while balls burn, larger ball center ({@link #onGround} at its radius and shared
     * rise: smaller follows the same law, so swapping larger ball causes no center jump); during cooling, cooling-ball
     * thermal. Ball image, smoke/world light, heat and ball smoke ({@link #blobs}) share it.
     */
    public double ballHeight(double t) {
        if (t >= burnSeconds) return burnHeight + cooling.height(t - burnSeconds) - cooling.height(0);
        return onGround(Math.max(fuelBallRadius(t), heBallRadius(t))) + lift(t);
    }

    /**
     * Glowing-body wind drift by age t, m (x and z): same as its smoke ({@link #driftX}); ball image,
     * light and heat travel with the clumps it cools into. Previously the ball stayed over impact while smoke drifted: for
     * missile at glow end with 5 m/s wind, 21 m, more than ball radius (BLASTSEQ D01).
     */
    public double ballX(double t) {
        return driftX(t);
    }

    public double ballZ(double t) {
        return driftZ(t);
    }

    /**
     * Crater fuel at age t: heat-release share 0..1 ({@link FuelPool#share}); 0 means no fuel or burnt out.
     */
    public double poolShare(double t) {
        return pool.share(t);
    }

    /**
     * Post-apex Briggs spread (open country, class {@link Air#spread}), horizontal and vertical σ,
     * m, over travel x = u·t, but no shorter than travel in 1 m/s wind: calm air mixes too.
     */
    static double spreadH(int cls, double x) {
        double[] a = {0.22, 0.16, 0.11, 0.08, 0.06, 0.04};
        return a[cls] * x / Math.sqrt(1 + 0.0001 * x);
    }

    static double spreadV(int cls, double x) {
        return switch (cls) {
            case 0 -> 0.20 * x;
            case 1 -> 0.12 * x;
            case 2 -> 0.08 * x / Math.sqrt(1 + 0.0002 * x);
            case 3 -> 0.06 * x / Math.sqrt(1 + 0.0015 * x);
            case 4 -> 0.03 * x / (1 + 0.0003 * x);
            default -> 0.016 * x / (1 + 0.0003 * x);
        };
    }

    /** Horizontal cloud σ at age t, m: thermal (sphere R gives σ = R/√5) and post-apex spread. */
    public double sigmaH(double t) {
        return thermal.sigmaH(t);
    }

    public double sigmaV(double t) {
        return thermal.sigmaV(t);
    }

    /**
     * Transverse cloud-center optical depth at age t: horizontal line through center of mass-m 3D Gaussian,
     * Σκ'·m / (2π·σh·σv) over cloud substances (ground cloud is separate, excluded).
     */
    public double tau(double t) {
        double ext = SOOT_K * soot + DustModel.K_MIX * capDust(t);
        return ext / (2 * Math.PI * sigmaH(t) * sigmaV(t));
    }

    /**
     * Cloud dust at age t (extinction in kg of {@code DustModel.K_MIX} mixture): crater dust and wave dust lifted under
     * the fireball that has not settled from the cloud (layer height is its center), plus stem dust reaching its bottom.
     */
    double capDust(double t) {
        return (dust + ground.underBall(t)) * DustModel.extinctionAloft(t / Math.max(height(t), 1)) + ground.drawn(stemEntered(t));
    }

    /**
     * Cloud dust share at age t that is crushed target matter (rest is surrounding surface dust from under the ball and
     * stem): by extinction, as {@link #capDust}. Cloud dust color mixes their colors by this share.
     */
    public double capMatterShare(double t) {
        double all = capDust(t);
        return all > 0 ? dust * DustModel.extinctionAloft(t / Math.max(height(t), 1)) / all : 1;
    }

    /**
     * Latest stem-entry age of dust already reaching cloud bottom at age t, s: stem air rises by share
     * {@code MushroomModel.STEM_INFLOW} of cloud rise (as in nuke); travel of dust entering at t' is
     * share·(height(t) − height(t')); cloud bottom is center − 1.5σ.
     */
    double stemEntered(double t) {
        double bottom = height(t) - 1.5 * sigmaV(t), k = ua.zentix.incoming.nuclear.model.MushroomModel.STEM_INFLOW;
        if (bottom <= 0 || k * (height(t) - height(0)) < bottom) return 0;
        double lo = 0, hi = t;
        for (int i = 0; i < 30; i++) {
            double mid = 0.5 * (lo + hi);
            if (k * (height(t) - height(mid)) >= bottom) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /**
     * How high stem dust rises above ground layer by age t, m: earliest portion (entrainment began at
     * {@code GroundDust.drawnFrom}) rises by {@code MushroomModel.STEM_INFLOW} of cloud rise since then, same
     * path as {@link #stemEntered}. Previously stem stood full-height to the cloud from first entrainment (BLASTSEQ D09).
     */
    double stemTop(double t) {
        double from = ground.drawnFrom;
        if (!(t > from)) return 0;
        return ua.zentix.incoming.nuclear.model.MushroomModel.STEM_INFLOW * Math.max(0, height(t) - height(from));
    }

    /** Cloud visibility duration, s: while {@link #tau} exceeds {@link #SEEN}, no longer than {@link #LIFE_MAX}. */
    private double lifeSeconds() {
        double lo = 0, hi = LIFE_MAX;
        if (tau(hi) >= SEEN) return hi;
        if (tau(1) < SEEN) return Math.max(fireSeconds, 1);
        lo = 1;
        for (int i = 0; i < 40; i++) {
            double mid = 0.5 * (lo + hi);
            if (tau(mid) >= SEEN) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /**
     * Cloud matter share rendered at age t: a cloud thinning to visibility threshold is already invisible at end of life,
     * so 1; a cloud still dense at {@link #LIFE_MAX} fades smoothly over its final tenth (no jump).
     */
    double fade(double t) {
        if (t >= life) return 0;
        if (life < LIFE_MAX) return 1;
        return Math.min(1, (life - t) / (0.1 * life));
    }

    /** Young condensation participates in culling too, without generating its shell cells. */
    public void condensationBounds(double t, SmokeBounds out) { condensationModel().bounds(t, out); }

    /** Conservative support at absolute age, without emitting dust or querying terrain. */
    public void bounds(double t, double floor, double ceiling, SmokeBounds out) {
        if (t < 0 || t >= life) return;
        double cx = driftX(t), cz = driftZ(t), cy = height(t), sh = sigmaH(t), sv = sigmaV(t);
        double he = heBallRadius(t), fuel = fuelBallRadius(t);
        if (he > 0) out.puff(cx, heBallHeight(t), cz, he / Math.sqrt(5), he / Math.sqrt(5));
        if (fuel > 0) out.puff(cx, fuelBallHeight(t), cz, fuel / Math.sqrt(5), fuel / Math.sqrt(5));
        if (he <= 0 && fuel <= 0) {
            double lean = shear(t), w = Math.hypot(air.windX(), air.windZ());
            double ux = w > 0 ? air.windX() / w : 0, uz = w > 0 ? air.windZ() / w : 0;
            double hand = handoff(t), ball = ballRadius(t) / Math.sqrt(5), by = ballHeight(t);
            for (double q : LAYER_Q) out.puff(cx + hand * ux * q * lean, by + hand * (cy + q * sv - by),
                    cz + hand * uz * q * lean, ball + hand * (sh - ball), ball + hand * (SLICE * sv - ball));
        }
        // The stem uses these very same seven cross-sections; no dust mass or terrain is needed for support.
        double bottom = cy - 1.5 * sv + hob, ss = Math.max(0.35 * sh, 1), h0 = Math.min(ss, 0.5 * bottom);
        double top = Math.min(bottom, h0 + stemTop(t));
        if (bottom > 2 && top > h0) for (int i = 0; i <= STEM_PIECES; i++) {
            double h = h0 + (top - h0) * i / STEM_PIECES, lean = air.wind(h) / air.wind(cy + hob);
            out.puff(cx * lean, h - hob, cz * lean, ss, ss);
        }
        ground.bounds(t, floor, ceiling, out);
        crushed.bounds(t, floor, ceiling, out);
    }

    /** Advance only terrain-dependent dust history; no cap, condensation, colour or puff preparation. */
    public void maintain(double t) {
        if (t <= 0 || t >= life) return;
        ground.maintain(t);
        crushed.maintain(t);
    }

    /**
     * Cloud clumps at age t (s), relative to explosion point (y upward from impact): fireball (hot, in fire
     * stage), cloud (thermal: soot, crater dust and stem dust reaching its bottom), stem from ground up to where
     * its dust has reached, no higher than cloud bottom (dust entrained from wave layer but not yet delivered to cloud), ground wave
     * dust layer ({@link GroundDust}).
     * Each clump carries mass; dust as extinction in kg of {@code DustModel.K_MIX} mixture.
     */
    public void blobs(double t, Blobs out) {
        if (t < 0 || t >= life) return;
        double fade = fade(t);
        double cx = driftX(t), cz = driftZ(t), cy = height(t), sh = sigmaH(t), sv = sigmaV(t);
        double k = kelvin(t), up = rise(t);
        // stem: dust entrained from ground layer but not yet delivered to cloud (arrived dust is in cloud); bottom up, no
        // higher than the earliest portion reached ({@link #stemTop})
        double entered = stemEntered(t), stem = Math.max(0, ground.drawn(t) - ground.drawn(entered));
        // cloud bottom above ground (airburst ground is hob below explosion point)
        double bottom = cy - 1.5 * sv + hob, ss = Math.max(0.35 * sh, 1), h0 = Math.min(ss, 0.5 * bottom), top = Math.min(bottom, h0 + stemTop(t));
        boolean stemShown = bottom > 2 && stem > 0 && top > h0;
        double cloudDust = capDust(t) + (stemShown ? 0 : stem);
        // fire stage: balls shine at their radius and temperature; detonation-product ball (all explosive soot from
        // detonation), fuel ball (soot from fuel already burnt, {@link #sootBurnt}); cloud dust is in the larger ball
        double he = heBallRadius(t), fuel = fuelBallRadius(t);
        double heSoot = heSoot(), fuelSoot = sootBurnt(t) - heSoot;
        if (he > 0 || fuel > 0) {
            boolean heBig = he > fuel;
            if (he > 0) {
                double s = he / Math.sqrt(5);
                out.blob(cx, heBallHeight(t), cz, s, s, (fuel > 0 ? heSoot : heSoot + fuelSoot) * fade, heBig ? cloudDust * fade : 0, 0,
                        Math.max(k, heBallKelvin(t)), up);
            }
            if (fuel > 0) {
                double s = fuel / Math.sqrt(5);
                out.blob(cx, fuelBallHeight(t), cz, s, s, (he > 0 ? fuelSoot : heSoot + fuelSoot) * fade, heBig ? 0 : cloudDust * fade, 0,
                        Math.max(k, FUEL_BALL_K), up);
            }
        } else {
            // height layers: each uses wind at its height (offset relative to center is downwind)
            double lean = shear(t), wx = air.windX(), wz = air.windZ(), w = Math.hypot(wx, wz);
            double ux = w > 0 ? wx / w : 0, uz = w > 0 ? wz / w : 0;
            // Cooling-ball soot remains in the ball (its center and size); cloud thermal accepts it over cooling:
            // one substance in two approximations (CloudModel.cooling while ball glows, thermal afterward). Instant swap at
            // burn end created a dark dome in one tick (review #426: missile edge +32%, bomb center 17 m higher).
            // Middle layer first: clump number keeps its SmokePuffs child seeds; merging retains the same ball clump.
            double hand = handoff(t), ball = ballRadius(t) / Math.sqrt(5), by = ballHeight(t);
            for (int i = 0; i < LAYERS; i++) {
                int j = LAYER_ORDER[i];
                double q = LAYER_Q[j], m = LAYER_W[j] * fade;
                double sx = hand * ux * q * lean, sz = hand * uz * q * lean, y = by + hand * (cy + q * sv - by);
                out.blob(cx + sx, y, cz + sz, ball + hand * (sh - ball), ball + hand * (SLICE * sv - ball), soot * m, cloudDust * m, 0,
                        k, up);
            }
        }
        if (stemShown) {
            // stem is a dust tube up to risen-dust top, no higher than cloud bottom (σ: ⚠ est., third of cloud σ), leaning with wind: drift at its height is
            // a share of cloud drift; dust uniform by height (per segment-axis meter reduced by ratio of axis length to
            // rise). Tube segments, not separate clumps every 2σ: those made beads (F5 frames)
            // stem bottom is its σ above ground: below is wave dust layer (own clumps), while power-profile wind drift falls to
            // zero only at ground itself; segment from impact to tilted stem base would form a strip along ground
            double perHeight = stem * fade / (top - h0);
            double lean0 = air.wind(h0) / air.wind(cy + hob);
            double px = cx * lean0, py = h0 - hob, pz = cz * lean0;
            for (int i = 1; i <= STEM_PIECES; i++) {
                double f = (double) i / STEM_PIECES, h = h0 + f * (top - h0), lean = air.wind(h) / air.wind(cy + hob);
                double qx = cx * lean, qy = h - hob, qz = cz * lean;
                double dx = qx - px, dy = qy - py, dz = qz - pz, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double dust = len > 0 ? perHeight * dy / len : 0, fm = f - 0.5 / STEM_PIECES;
                out.tube(px, py, pz, ss, dust, qx, qy, qz, ss, dust, inBall(t, 0.5 * (px + qx), 0.5 * (py + qy), 0.5 * (pz + qz),
                        T_AIR + (k - T_AIR) * fm * 0.5), up * fm);
                px = qx;
                py = qy;
                pz = qz;
            }
        }
        // wave dust layer on surfaces it rose from and where it drained (heights relative to explosion point)
        ground.blobs(t, fade, (x, y, z, gh, gv, d) -> out.ground(x, y, z, gh, gv, d, inBall(t, x, y, z, T_AIR)));
        crushed.blobs(t, fade, (x, y, z, gh, gv, d) -> out.crushed(x, y, z, gh, gv, d, inBall(t, x, y, z, T_AIR)));
    }

    /** Explosive soot, kg: free detonation carbon surviving ball afterburning ({@link #SOOT_SURVIVES}). */
    double heSoot() {
        return source.chargeKg() * source.explosive().freeCarbon() * SOOT_SURVIVES;
    }

    /**
     * Cloud soot already born by age t, kg: explosive soot from detonation (microseconds); fuel soot as
     * the ball burns fuel: burnt share equals elapsed burn share (CCPS ball burns its fuel throughout its
     * duration; ⚠ uniform flow is est., CCPS gives no burn history). Previously all future fuel soot was in the ball from the first
     * instant (BLASTSEQ D02): a one-meter ball in daytime was a dark spot twice its radius.
     */
    public double sootBurnt(double t) {
        double he = heSoot();
        if (fuelBallSeconds <= 0 || t >= fuelBallSeconds) return soot;
        return he + (soot - he) * Math.max(0, t) / fuelBallSeconds;
    }

    /**
     * Temperature of matter at (x, y, z) relative to explosion point with own temperature kelvin: inside glowing ball it is part of the ball
     * (its dust appears by the same share as ball fades); outside, its own temperature; smooth at surface (0.8–1.2 radii). In daytime, using own temperature made stem and ground dust inside
     * the ball a dark lump over it in the first second (F6 frames, bomb at 150 m).
     */
    private double inBall(double t, double x, double y, double z, double kelvin) {
        double ball = ballRadius(t), hot = ballKelvin(t);
        if (ball <= 0 || hot <= kelvin) return kelvin;
        double dx = x - ballX(t), dy = y - ballHeight(t), dz = z - ballZ(t), d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // smooth at ball surface (0.8–1.2 radii): a piece leaving a growing ball does not abruptly turn opaque
        double u = Math.max(0, Math.min(1, (1.2 * ball - d) / (0.4 * ball)));
        return kelvin + (hot - kelvin) * u * u * (3 - 2 * u);
    }

    /** Wilson cloud: shock heating, expansion, saturation and latent heat, see {@link Condensation}. */
    public double condensation() {
        return condensationModel().reach;
    }

    public double condensationSeconds() {
        return condensationModel().life;
    }

    public void condensation(double t, Blobs out) {
        double ball = ballRadius(t), cx = ballX(t), cy = ballHeight(t), cz = ballZ(t);
        condensationModel().blobs(t, (x, y, z, sh, sv, soot, dust, water, kelvin, rise) -> {
            double dx = x - cx, dy = y - cy, dz = z - cz, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // The pressure history describes ambient air, not the hot products/fuel inside the fireball.
            // Smooth mixing across one cell prevents an opacity step at its moving boundary (shape estimate).
            double cold = Math.max(0, Math.min(1, (d - ball) / Math.max(sh, 1e-6)));
            out.blob(x, y, z, sh, sv, soot, dust, water * cold * cold * (3 - 2 * cold), kelvin, rise);
        });
    }

    private Condensation condensationModel() {
        if (condensation == null) condensation = new Condensation(source.chargeKg(), source.surface(), air.humidity());
        return condensation;
    }

    /** Densest wave-dust-layer clump optical depth at age t (horizontal through center). */
    public double groundTauForTest(double t) {
        return ground.tau(t);
    }

    private Condensation condensation;
}
