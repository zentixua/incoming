package ua.zentix.incoming.client.map;

/**
 * Presentation: how the targeting map draws a live shock front from the overpressure it carries now. Colour runs on
 * log pressure through the pre-launch contour colours (20 psi red, 5 psi orange, 1 psi pale orange) to near-white at
 * the 0.3 psi hit edge; the ring thins as the wave weakens. The pressure itself is the model's, not set here.
 */
public final class FrontLook {
    /** Anchors: psi and ARGB, ascending psi. */
    private static final double[] PSI = {0.3, 1, 5, 20};
    private static final int[] COLOR = {0xFFE8E8E8, 0xFFFFD0A0, 0xFFFF8050, 0xFFFF3030};
    /** Band behind the front where the positive phase still blows: the front colour at this alpha. */
    private static final int WAKE_ALPHA = 0x50;

    private FrontLook() {}

    /** Ring colour for front overpressure {@code psi}. */
    public static int color(double psi) {
        if (!(psi > PSI[0])) return COLOR[0];
        if (psi >= PSI[PSI.length - 1]) return COLOR[COLOR.length - 1];
        int i = 0;
        while (psi >= PSI[i + 1]) i++;
        double f = Math.log(psi / PSI[i]) / Math.log(PSI[i + 1] / PSI[i]);
        return lerp(COLOR[i], COLOR[i + 1], f);
    }

    /** Wake band colour: the ring colour, translucent so terrain shows through. */
    public static int wake(double psi) {
        return (color(psi) & 0x00FFFFFF) | WAKE_ALPHA << 24;
    }

    /** Ring thickness, GUI pixels: 4 at 5 psi and above, 3 from 1 psi, 2 below. */
    public static int width(double psi) {
        return psi >= 5 ? 4 : psi >= 1 ? 3 : 2;
    }

    private static int lerp(int a, int b, double f) {
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int ca = a >>> shift & 0xFF, cb = b >>> shift & 0xFF;
            out |= (int) Math.round(ca + (cb - ca) * f) << shift;
        }
        return out;
    }
}
