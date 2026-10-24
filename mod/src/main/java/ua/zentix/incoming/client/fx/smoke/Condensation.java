package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.ChemicalFront;
import ua.zentix.incoming.nuclear.model.HobBlast;

/**
 * Wilson cloud ordinary HE: air is heated at the shock, then cools on isentropic expansion in the negative phase.
 * Condensation requires saturation, not a fixed RH threshold. Birk 2017, doi:10.1016/j.psep.2017.06.017.
 * SI units. Ambient 15°C is the same atmosphere as CloudModel. Aerosol nuclei are assumed available.
 * The negative phase is an estimate: continuation of Friedlander's b=1 profile, limited to half ambient pressure;
 * it is not a measured TNT negative-phase fit. Latent heating is included; droplet growth time is neglected.
 * The existing 20 kPa outer envelope is retained as a conservative rendering estimate, not a universal condensation
 * threshold: the equilibrium calculation alone would predict kilometre-wide clouds at exactly RH=1 and neglect
 * finite droplet growth. Neither this estimate nor the presentation is used by the nuclear cloud.
 */
public final class Condensation {
    private static final int RINGS = 72, DIRECTIONS = 96;
    private static final double P0 = BlastModel.P0 * 1000, T0 = CloudModel.T_AIR_K;
    private static final double RV = 461.5, RD = 287.05, CP = 1005, LV = 2.45e6;
    private static final double DROP_EXTINCTION = CloudModel.DROP_K * (1 - CloudModel.DROP_G);
    private final ChemicalFront front;
    private final double humidity;
    private final double[] radius = new double[RINGS], volume = new double[RINGS], arrival = new double[RINGS], phase = new double[RINGS];
    public final double reach, life;

    public Condensation(double kg, boolean surface, double humidity) {
        front = ChemicalFront.cached(kg, surface);
        this.humidity = Math.max(0, Math.min(1, humidity));
        if (kg <= 0 || this.humidity <= 0) {
            reach = life = 0;
            return;
        }
        double low = Math.max(0.5, 0.6 * Math.cbrt(kg));
        double high = BlastModel.rangeForSurfaceOverpressure(20, kg);
        double max = 0, last = 0;
        for (int i = 0; i < RINGS; i++) {
            double a = low * Math.pow(high / low, (double) i / RINGS), b = low * Math.pow(high / low, (i + 1.0) / RINGS);
            double r = Math.sqrt(a * b);
            radius[i] = r;
            volume[i] = (surface ? 2 : 4) * Math.PI / 3 * (b * b * b - a * a * a);
            arrival[i] = front.arrivalSeconds(r);
            phase[i] = BlastModel.chemicalPhaseSeconds(r, kg, surface);
            // Tangential view through the rarefaction shell; below the particle layer's visibility threshold no cloud.
            double tau = DROP_EXTINCTION * water(front.kpa(r), -Math.min(P0 * 0.5, 1000 * front.kpa(r) / Math.exp(2)), this.humidity)
                    * 2 * Math.sqrt(2 * r * 3 * phase[i] * BlastModel.SOUND_SPEED);
            if (tau >= CloudModel.SEEN) {
                max = b;
                last = arrival[i] + 8 * phase[i];
            }
        }
        reach = max;
        life = last;
    }

    /** Saturation pressure over water, Pa: Magnus (Alduchov & Eskridge 1996), appropriate near ambient temperature. */
    public static double saturation(double kelvin) {
        double c = kelvin - 273.15;
        return 610.94 * Math.exp(17.625 * c / (243.04 + c));
    }

    /** Dry parcel temperature, K, following a shock of peak kPa and expansion to ambient + deltaPa. */
    public static double temperature(double peakKpa, double deltaPa) {
        double p1 = P0 + 1000 * peakKpa;
        double rho = HobBlast.densityRatio(1000 * peakKpa);
        return T0 * p1 / P0 / rho * Math.pow((P0 + deltaPa) / p1, 2.0 / 7);
    }

    /** Condensed water, kg/m³ of expanded air, including the latent heat released to that parcel. */
    public static double water(double peakKpa, double deltaPa, double rh) {
        if (deltaPa >= 0 || rh <= 0) return 0;
        double p = P0 + deltaPa, dry = temperature(peakKpa, deltaPa);
        double vapor = Math.max(0, Math.min(1, rh)) * saturation(T0) * p / P0;
        if (vapor <= saturation(dry)) return 0;
        double rho = p / (RD * dry), lo = dry, hi = T0 + 100;
        for (int i = 0; i < 24; i++) {
            double t = 0.5 * (lo + hi), m = Math.max(0, (vapor - saturation(t)) / (RV * t));
            if (t - dry < LV * m / (rho * CP)) lo = t;
            else hi = t;
        }
        double t = 0.5 * (lo + hi);
        return Math.max(0, (vapor - saturation(t)) / (RV * t));
    }

    /** Rarefaction only, no water before the local positive phase is over. */
    public double density(double r, double seconds) {
        double plus = BlastModel.chemicalPhaseSeconds(r, front.tntKg, front.surface);
        double x = (seconds - front.arrivalSeconds(r)) / plus;
        if (x <= 1 || x >= 8) return 0;
        double peak = front.kpa(r), under = Math.min(P0 * 0.5, 1000 * peak * (x - 1) * Math.exp(-x));
        // Smooth truncation of the very weak tail; pressure returns to ambient.
        double tail = Math.min(1, 8 - x);
        return water(peak, -under * tail * tail * (3 - 2 * tail), humidity);
    }

    /** Shell cells follow the local pressure history; shape discretization only, no static sphere at t=0. */
    public void blobs(double seconds, CloudModel.Blobs out) {
        if (seconds <= 0 || seconds >= life || reach <= 0) return;
        for (int j = 0; j < RINGS; j++) {
            double r = radius[j];
            double water = r > reach ? 0 : density(r, seconds) * volume[j] / DIRECTIONS;
            if (water <= 0) {
                // Preserve each cell’s puff identity as other shells appear or evaporate.
                for (int i = 0; i < DIRECTIONS; i++) out.blob(0, 0, 0, 0, 0, 0, 0, 0, T0, 0);
                continue;
            }
            double sigma = Math.min(0.10 * r, Math.max(0, front.radiusAt(seconds) - r) / 4);
            if (sigma <= 0) continue;
            for (int i = 0; i < DIRECTIONS; i++) {
                double vertical = (i + 0.5) / DIRECTIONS;
                if (!front.surface) vertical = 2 * vertical - 1;
                double horizontal = Math.sqrt(1 - vertical * vertical), az = i * Math.PI * (3 - Math.sqrt(5));
                out.blob(r * horizontal * Math.cos(az), r * vertical, r * horizontal * Math.sin(az), sigma, sigma,
                        0, 0, water, T0, 0);
            }
        }
    }
}
