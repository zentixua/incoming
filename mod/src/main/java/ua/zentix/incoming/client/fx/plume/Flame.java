package ua.zentix.incoming.client.fx.plume;

/**
 * Engine plume at one altitude — {@link FlameModel} output: one record for the nearby jet ({@code PlumeRenderer}) and distant
 * light ({@code FarFlightView}), so the same plume spans the render boundary. Metres (= blocks) aft along the axis from the nozzle exit.
 * Three parts: core (supersonic centre), afterburning flame (until the axial mixture becomes stoichiometric),
 * cooling tail (to the Draper point). Jet width grows linearly: {@link #width} at the exit, twice that at the core end.
 * Luminance in frame units: white wall at noon — 1 (like {@code BlackBody}); colour — black-body chromaticity in
 * sRGB, maximum channel 255.
 *
 * @param core            core length
 * @param flame           afterburning flame length (core if no afterburning)
 * @param length          visible jet length
 * @param width           jet half-width at exit (fully expanded jet, all nozzles together)
 * @param diamonds        shock diamonds: exit pressure differs significantly from ambient air
 * @param coreLuminance   core luminance
 * @param flameLuminance  afterburning flame luminance
 * @param tailLuminance   cooling tail luminance (at its midpoint)
 * @param coreColor       core colour
 * @param flameColor      flame colour
 * @param tailColor       tail colour
 * @param temperature     core gas temperature, K
 * @param flameTemperature afterburning flame temperature, K
 * @param emissivity      core emissivity across the jet
 * @param sheath          axial jet sheath (afterburning flame and tail): luminance and colour at positions from the exit — same
 *                        law used by {@link FlameModel} for mean part luminance
 * @param middle          light centre aft of the nozzle exit, m: luminous flux centroid (core, flame and tail
 *                        by the axial law) — source of distant plume light, smoke and world light
 * @param tailMiddle      tail luminous flux centroid aft of exit, m: tail world light uses a uniform segment with this
 *                        midpoint ({@link #tailEnd})
 * @param speed           exit gas speed (fully expanded jet), m/s; axial speed beyond core scales as core/x (Pope)
 */
public record Flame(double core, double flame, double length, double width, boolean diamonds, double coreLuminance, double flameLuminance,
                    double tailLuminance, int coreColor, int flameColor, int tailColor, double temperature, double flameTemperature,
                    double emissivity, Sheath sheath, double middle, double tailMiddle, double speed) {
    /**
     * Axial jet sheath — afterburning flame around the core, cooling tail behind it: at {@code x} positions (metres from nozzle
     * exit, increasing from 0 to {@link #length}), side luminance (frame units, like parts) and colour (sRGB,
     * maximum channel 255). Linear between positions. Screen jet narrows toward its end through model
     * light rather than texture: white flame, then a yellow and orange tail fading toward the Draper point.
     */
    public record Sheath(double[] x, double[] luminance, int[] color) {
        static final Sheath NONE = new Sheath(new double[0], new double[0], new int[0]);

        public int size() {
            return x.length;
        }
    }

    /** Same plume, light reduced by factor k (same jet shape, k times fewer particles; optically thin). */
    public Flame dimmed(double k) {
        double[] lum = sheath.luminance().clone();
        for (int i = 0; i < lum.length; i++) lum[i] *= k;
        return new Flame(core, flame, length, width, diamonds, coreLuminance * k, flameLuminance * k, tailLuminance * k, coreColor, flameColor,
                tailColor, temperature, flameTemperature, 1 - Math.pow(1 - emissivity, k), new Sheath(sheath.x(), lum, sheath.color()), middle,
                tailMiddle, speed);
    }

    /**
     * Jet gas distance from the exit after seconds seconds since ignition, m: exit speed in the core; axial speed beyond it
     * speed·core/x (Pope), so x² grows linearly with time. (est.) Starting jet head travels slower than the established
     * axis (about half speed) — an upper estimate.
     */
    public double gasReach(double seconds) {
        if (speed <= 0 || core <= 0) return 0;
        double t = Math.max(0, seconds), inCore = core / speed;
        return t <= inCore ? speed * t : Math.sqrt(core * core + 2 * speed * core * (t - inCore));
    }

    /** Jet half-width at x metres from exit. */
    public double widthAt(double x) {
        return width * (1 + x / core);
    }

    /**
     * Tail light segment end aft of exit, m: uniform segment from the flame end with the same flux midpoint as the tail
     * ({@link #tailMiddle}), capped by visible jet length. Extending to the Draper point would place tail light four times beyond the flame,
     * although its start emits most of the light.
     */
    public double tailEnd() {
        double fl = Math.max(flame, core);
        return Math.min(length, Math.max(fl, 2 * tailMiddle - fl));
    }

    /** Half-width at visible jet end. */
    public double spread() {
        return widthAt(length);
    }

    /** Side area from exit to x (trapezoid), m². */
    public double areaTo(double x) {
        return x * (width + widthAt(x));
    }

    /** Luminous jet side area, m². */
    public double area() {
        return areaTo(length);
    }

    /** Radius of a disc matching jet side area: distant light and smoke light source, m. */
    public double lightRadius() {
        return Math.sqrt(area() / Math.PI);
    }

    /** Part side areas, m²: core (its cone), flame excluding core (surrounds core), tail. */
    public double[] partAreas() {
        double c = core * width, f = Math.max(0, areaTo(flame) - c), t = Math.max(0, area() - areaTo(flame));
        return new double[]{c, f, t};
    }

    /** Mean jet side luminance: each part over its own area. */
    public double brightness() {
        double[] a = partAreas();
        return (coreLuminance * a[0] + flameLuminance * a[1] + tailLuminance * a[2]) / (a[0] + a[1] + a[2]);
    }

    /** Jet light colour: parts weighted by their light flux (linear), renormalized to maximum channel 255. */
    public int lightColor() {
        double[] a = partAreas(), w = {coreLuminance * a[0], flameLuminance * a[1], tailLuminance * a[2]};
        int[] colors = {coreColor, flameColor, tailColor};
        double[] rgb = new double[3];
        for (int i = 0; i < 3; i++) {
            int shift = 16 - 8 * i;
            for (int k = 0; k < 3; k++) rgb[i] += w[k] * FlameModel.linear(colors[k] >> shift & 255);
        }
        return FlameModel.pack(rgb);
    }

}
