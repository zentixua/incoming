package ua.zentix.incoming.nuclear.model;

/**
 * Fuel remaining in the crater after the fireball ({@link FuelFireball#POOL_SHARE}), burning as a thin layer on soil.
 * One law for the server (where and how long block fire burns, {@code warhead/FuelFires}) and client smoke and sound.
 * Without Minecraft.
 * <p>
 * Area is the charge crater disk ({@link CraterModel#chargeRadius} for dry soil): fuel soaks into ejected, mixed bowl
 * soil. Mass burning rate per area is a large pool's ({@link Fuel#poolBurn}) × {@link #THIN_LAYER}: a millimeters-thin
 * layer on soil burns slower than a deep pool. Burns for {@link #seconds}, heat release {@link #watts}: Shahed ~2.5 min
 * at ~2 MW, missile ~16 min at ~3 MW. Near the end (last fifth), the layer burns out in patches — burning area and heat
 * fade to zero ({@link #share}).
 *
 * @param kg      crater fuel, kg
 * @param radius  radius of the disk it covers, m
 * @param seconds burn duration, s (0 — no fuel)
 * @param watts   heat release while burning fully, W
 * @param fuel    fuel type
 */
public record FuelPool(double kg, double radius, double seconds, double watts, Fuel fuel) {
    /**
     * Thin fuel layer on soil versus deep-pool burning: for a millimeters-thin spill, maximum burning rate is about one
     * fifth of a deep pool (Mealy, Benfer, Gottuk, “Liquid fuel spill fire dynamics”, Fire Safety Science 10, 2011: heat lost
     * to substrate); fuel in porous sand also burns slower than an equally sized pool. Shahed crater — ~2 mm gasoline;
     * missile — ~8 mm JP-10 in mixed soil (⚠ one fraction for both: thickness and soil dependence from their correlations
     * not used).
     */
    public static final double THIN_LAYER = 0.2;
    /** Lifetime fraction over which the layer burns out in patches to zero (⚠ decay shape is an estimate). */
    static final double FADE = 0.2;
    public static final FuelPool NONE = new FuelPool(0, 0, 0, 0, Fuel.NONE);

    /**
     * Fuel: lower heating value (reference), flame soot yield — estimate: liquid hydrocarbons 0.01–0.06 g/g
     * (SFPE Handbook, ch. 36, Khan, Tewarson, Chaos; table not checked), midpoint used — and large-pool mass burning rate per
     * area (Babrauskas, SFPE Handbook, “Heat Release Rates”, table for pools over 1 m diameter).
     */
    public enum Fuel {
        NONE(0, 0, 0),
        /** Gasoline for Shahed's two-stroke engine (MD-550): 43.4 MJ/kg; pool burning rate 0.055 kg/(m²·s). */
        PETROL(43.4e6, 0.04, 0.055),
        /**
         * Cruise missile JP-10 (exo-tetrahydrodicyclopentadiene): 42.1 MJ/kg; pool burning rate as for kerosene,
         * 0.039 kg/(m²·s) (⚠ no JP-10 measurements — similar composition and volatility).
         */
        JP10(42.1e6, 0.04, 0.039);

        /** Heat of combustion, J/kg; soot yield, kg/kg; large-pool mass burning rate per area, kg/(m²·s). */
        public final double heatJPerKg, sootYield, poolBurn;

        Fuel(double heatJPerKg, double sootYield, double poolBurn) {
            this.heatJPerKg = heatJPerKg;
            this.sootYield = sootYield;
            this.poolBurn = poolBurn;
        }
    }

    /**
     * Tank fuel {@code fuel} at impact, {@code fuelKg} kg, with charge {@code chargeKg} kg TNT, on ground: fraction
     * {@link FuelFireball#POOL_SHARE} remains across the charge crater disk. No fuel or charge — {@link #NONE}.
     */
    public static FuelPool of(Fuel fuel, double fuelKg, double chargeKg) {
        double kg = FuelFireball.POOL_SHARE * Math.max(0, fuelKg);
        double radius = CraterModel.chargeRadius(chargeKg, CraterModel.Soil.DRY);
        double burn = THIN_LAYER * fuel.poolBurn * Math.PI * radius * radius;
        if (kg <= 0 || burn <= 0) return NONE;
        return new FuelPool(kg, radius, kg / burn, burn * fuel.heatJPerKg, fuel);
    }

    /** Area covered by fuel, m². */
    public double area() {
        return Math.PI * radius * radius;
    }

    /**
     * Heat release and burning area share at age t, s: full while burning, fading in patches over the last {@link #FADE}
     * of lifetime (smooth decay 3u² − 2u³); 0 — no fuel or burned out.
     */
    public double share(double t) {
        if (seconds <= 0 || t < 0 || t >= seconds) return 0;
        double u = (seconds - t) / (FADE * seconds);
        return u >= 1 ? 1 : u * u * (3 - 2 * u);
    }

    /** Heskestad flame height, m: L = 0.235·Q^⅖ − 1.02·D (Q — kW), at least half a meter. */
    public double flame() {
        return watts <= 0 ? 0 : Math.max(0.5, 0.235 * Math.pow(watts / 1e3, 0.4) - 1.02 * 2 * radius);
    }

    /**
     * Blocks (1 m² cells) covered by fuel: rounded area, at least one cell if fuel exists. A block is a meter:
     * Shahed — 5 cells, missile — 9.
     */
    public int cells() {
        return seconds <= 0 ? 0 : (int) Math.max(1, Math.round(area()));
    }

    /**
     * Same fuel on {@code m} of {@code n} cells given by the law ({@link #cells}): smaller area (narrow pit, shaft, no room
     * for the rest) — thicker layer, burning that many times longer and weaker; m ≥ n — unchanged.
     */
    public FuelPool spread(int m, int n) {
        if (seconds <= 0 || m <= 0 || m >= n) return this;
        double share = (double) m / n;
        return new FuelPool(kg, radius * Math.sqrt(share), seconds / share, watts * share, fuel);
    }

    /**
     * When cell {@code k} of {@code n} goes out (0 — longest burning: lowest, where fuel drained), s: burns while burning
     * area share ({@link #share}) exceeds k/n. Cell 0 burns the full layer lifetime, edge goes out first.
     */
    public double cellSeconds(int k, int n) {
        if (seconds <= 0 || n <= 0 || k >= n) return 0;
        double s = Math.max(0, (double) k / n);
        if (s <= 0) return seconds;
        // inverse of 3u² − 2u³ = s on [0, 1]
        double u = 0.5 - Math.sin(Math.asin(1 - 2 * s) / 3);
        return seconds - FADE * seconds * u;
    }
}
