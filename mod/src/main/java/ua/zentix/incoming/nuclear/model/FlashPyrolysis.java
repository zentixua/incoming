package ua.zentix.incoming.nuclear.model;

/**
 * Volatiles a flash drives out of a surface fuel bed before the wave (pyrolysis smoke, {@code client/nuclear/FlashAftermath}):
 * absorbed light first dries the bed, then gasifies it, and no bed gives more than it holds.
 * <ul>
 * <li>absorbed energy α·q, α = {@value #ABSORB} (est.) (wood and foliage 0.6–0.9);</li>
 * <li>drying: {@value #DRYING} J per kg of water, the moisture term of Rothermel's heat of preignition
 * Q<sub>ig</sub> = 250 + 1116·M<sub>f</sub> Btu/lb (Rothermel 1972, USDA INT-115, eq. 12);</li>
 * <li>gasification: {@value #GASIFY} J/kg (Tewarson, SFPE Handbook, table 3-4.x: Douglas fir; (est.) from memory);</li>
 * <li>bound: {@value #VOLATILE} of the bed's dry mass is volatile matter (est.: wood and grass proximate analyses
 * 75–85 %, Babrauskas, “Ignition Handbook” 2003, ch. 14).</li>
 * </ul>
 * Beds ({@link Bed}): load and moisture of what lies on the exposed surface.
 */
public final class FlashPyrolysis {
    public static final double ABSORB = 0.7;
    public static final double DRYING = 1116 * 1055.06 / 0.45359;
    public static final double GASIFY = 1.8e6;
    public static final double VOLATILE = 0.8;
    /** Joules per square meter in one calorie per square centimeter. */
    public static final double CAL_CM2 = 41_868;
    /** Softwood thermal diffusivity, m²/s (pine, as in {@link PulseIgnition}), and oven-dry density, kg/m³ (est.: pine/spruce 400–550). */
    static final double WOOD_DIFFUSIVITY = 1.7e-7, WOOD_DENSITY = 500;

    private FlashPyrolysis() {}

    /** Surface fuel bed: oven-dry load, kg/m², and moisture, fraction of dry mass. */
    public enum Bed {
        /**
         * Green turf: short grass 0.74 t/acre = 0.166 kg/m² (Anderson 1982, USDA INT-122, fuel model 1), live herbaceous moisture
         * 120 %, “fully green” (Scott &amp; Burgan 2005, RMRS-GTR-153, live moisture scenarios).
         */
        GREEN_GRASS(0.166, 1.2),
        /** Cured grass (dry climate, hay): the same load, dead 1-hour fuel moisture 6 % (Scott &amp; Burgan scenario D2; BUSTER's Nevada fuels 4–12 %). */
        CURED_GRASS(0.166, 0.06),
        /**
         * Outer canopy: bulk density ~0.1 kg/m³ over the first meter of crown (Scott &amp; Reinhardt 2001, RMRS-RP-29: 0.05–0.3), foliar
         * moisture 100 % (Van Wagner 1977).
         */
        FOLIAGE(0.1, 1.0),
        /** Wood surface: the layer the pulse heats ({@link #woodLoad}), seasoned outdoors at 12 % (USDA Wood Handbook 2010, ch. 4). */
        WOOD(Double.NaN, 0.12);

        final double load, moisture;

        Bed(double load, double moisture) {
            this.load = load;
            this.moisture = moisture;
        }
    }

    /** Dry mass of wood the pulse heats, kg/m²: density × thermal penetration √(a·t) over the pulse ({@link PulseIgnition}: 0.7 mm in 3 s). */
    public static double woodLoad(double pulseSeconds) {
        return WOOD_DENSITY * Math.sqrt(WOOD_DIFFUSIVITY * Math.max(0, pulseSeconds));
    }

    /** Dry load of {@code bed}, kg/m², for a pulse of {@code pulseSeconds}. */
    public static double load(Bed bed, double pulseSeconds) {
        return bed == Bed.WOOD ? woodLoad(pulseSeconds) : bed.load;
    }

    /** Volatiles driven out of {@code bed} by {@code q} cal/cm², kg/m². */
    public static double volatiles(Bed bed, double q, double pulseSeconds) {
        double load = load(bed, pulseSeconds);
        double left = ABSORB * Math.max(0, q) * CAL_CM2 - bed.moisture * load * DRYING;
        return left <= 0 ? 0 : Math.min(VOLATILE * load, left / GASIFY);
    }
}
