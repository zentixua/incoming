package ua.zentix.incoming.nuclear.model;

/**
 * The mass fire's course over the strike clock S (seconds since the burst): growth, full strength, decay and burn-out of a
 * cell's flame and smoke. Pure law shared by the client's firestorm ({@code client/nuclear/FireStorm}) and the smoke parcels
 * both sides read ({@link SmokeParcels}).
 */
public final class FireStormLaw {
    private FireStormLaw() {}

    /**
     * Fire growth to full strength, s: t-squared growth (heat release α·t², SFPE Handbook; NFPA 72 Annex B) at the "medium"
     * rate α = 0.01172 kW/s² reaches 17 MW, a fully involved house (est.), in 1200 s. The same time as the record: at Hiroshima
     * fires started in shattered houses about 5 minutes after the burst and the mass fire formed in about 20 minutes
     * (Glasstone and Dolan 1977, §7.70; Uda's survey via Sutou 2020).
     */
    public static final double GROW_S = 1200;
    /**
     * Full strength holds this many seconds after {@link #GROW_S} (±30 % per cell), then decays e-fold over {@link #DECAY_S} to
     * smoldering {@link #EMBERS}: Hiroshima burned hardest 2–6 h after the burst and abated by evening (Uda via Sutou 2020; (est.)
     * as a cell law).
     */
    public static final double PEAK_S = 3 * 3600, DECAY_S = 2 * 3600, EMBERS = 0.2;
    /**
     * Smoldering burns out between these strike-clock times, s: Hiroshima's fires burned out by the evening after a
     * morning burst (est.).
     */
    public static final double OUT_FROM_S = 9 * 3600, OUT_S = 12 * 3600;
    /** Smoke decays less than flame: smoldering material emits smoke. */
    public static final double SMOKE_EMBERS = 0.45;

    /** Fire growth {@code ageS} seconds after ignition, fraction of full strength: t-squared to {@link #GROW_S}. */
    public static double growth(double ageS) {
        if (ageS <= 0) return 0;
        double u = Math.min(1, ageS / GROW_S);
        return u * u;
    }

    /** Cell flame {@code ageS} seconds after ignition, fraction of full strength ({@code peakS} is its decay start). */
    public static double flame(double ageS, double peakS) {
        return growth(ageS) * decay(ageS, peakS, EMBERS);
    }

    /** Cell smoke (emission into a column), fraction of full strength: smoke yield follows the burning rate. */
    public static double smoke(double ageS, double peakS) {
        return growth(ageS) * decay(ageS, peakS, SMOKE_EMBERS);
    }

    private static double decay(double ageS, double peakS, double floor) {
        if (ageS <= peakS) return 1;
        return (floor + (1 - floor) * Math.exp(-(ageS - peakS) / DECAY_S)) * (1 - smooth(OUT_FROM_S, OUT_S, ageS));
    }

    private static double smooth(double e0, double e1, double x) {
        double f = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return f * f * (3 - 2 * f);
    }
}
