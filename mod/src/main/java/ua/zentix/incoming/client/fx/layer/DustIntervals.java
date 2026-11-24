package ua.zentix.incoming.client.fx.layer;

/**
 * The dust wave's transmittance along a view ray for its readers (GLSL {@code wall_layer.glsl}: {@code wallPrefixT},
 * {@code wallPrefixShare}, held equal by {@code DustIntervalsTest}): up to two occupied intervals per march pixel, A from the first
 * dust to the last before the first clear gap, B the rest. Inside an interval its optical depth is spread evenly; the gap holds
 * no dust, so an object between two crossings takes the first crossing's share only. Depths along the view axis, blocks.
 */
public final class DustIntervals {
    private DustIntervals() {}

    /**
     * Transmittance of the dust in front of view depth z: A over [aIn, aOut] with transmittance tA, B over [bIn, bOut] with the
     * rest of the whole's t (B empty: bIn = bOut = aOut, tA = t).
     */
    public static double transmittance(double z, double aIn, double aOut, double tA, double bIn, double bOut, double t) {
        if (z <= aIn) return 1;
        if (z < aOut) return Math.pow(Math.max(tA, 1e-7), (z - aIn) / Math.max(aOut - aIn, 1e-4));
        if (z <= bIn) return tA;
        if (z < bOut) return tA * Math.pow(Math.max(t / Math.max(tA, 1e-7), 1e-7), (z - bIn) / Math.max(bOut - bIn, 1e-4));
        return t;
    }

    /** Share of the dust's light in front of view depth z; s is A's share of the whole's light. */
    public static double lightShare(double z, double aIn, double aOut, double tA, double bIn, double bOut, double t, double s) {
        if (z <= aIn) return 0;
        if (z < aOut) return s * (1 - Math.pow(Math.max(tA, 1e-7), (z - aIn) / Math.max(aOut - aIn, 1e-4))) / Math.max(1 - tA, 1e-6);
        if (z <= bIn) return s;
        double tB = t / Math.max(tA, 1e-7);
        if (z < bOut) return s + (1 - s) * (1 - Math.pow(Math.max(tB, 1e-7), (z - bIn) / Math.max(bOut - bIn, 1e-4))) / Math.max(1 - tB, 1e-6);
        return 1;
    }
}
