package ua.zentix.incoming.nuclear.model;

/** Nuclear visible light before camera exposure. No server energy, burn or retinal-injury state. */
public final class FlashLight {
    /** (est.) Sky glare at unit transmitted pulse, inherited screen calibration; no separate shelter shares. */
    private static final double SKY_GLARE = 0.6;

    private FlashLight() {}

    /**
     * Vanilla LightTexture.getBrightness without dimension ambient light: x/(4-3x), x=sky/15.
     * (est.) Aperture throughput from the light engine's falloff; it does not measure aperture solid angle.
     */
    public static double opening(double skyLevel) {
        double x = Math.clamp(skyLevel / 15, 0, 1);
        return x / (4 - 3 * x);
    }

    /**
     * Seen source: existing direct+diffuse transmission. Hidden source: only scattered light through openings.
     * (est.) Angularly unresolved diffuse component, total minus beam, as MushroomField.ballFace; the empirical
     * total-transmission fit can fall below the beam nearby, so the difference cannot be negative.
     */
    public static double transmission(boolean seen, double skyLevel, double total, double beam) {
        return seen ? total : Math.max(0, total - beam) * opening(skyLevel);
    }

    /** Source-to-eye input shared by flash, afterimage, sky flash and dazzle; fluence remains cal/cm². */
    public record Eye(double light, double peakLight, double fluence, double transmitted, boolean seen) {
        public static final Eye DARK = new Eye(0, 0, 0, 0, false);

        public double strength() { return Math.min(1, fluence / 2 + SKY_GLARE * transmitted); }
        public double peak(double beforeExposure) { return 1 + peakLight * beforeExposure; }
    }

    /** Shared geometric dilution, capped at the emitting surface. */
    public static double dilution(double radius, double distance) {
        return radius * radius / Math.max(distance * distance, radius * radius);
    }

    /**
     * Apparent emitting temperature from the thermal pulse and projected source area (G&D 1977 §§7.83–7.86,
     * fig. 7.84: P proportional to R²T⁴). Peak calibration and timing are the existing fireball's.
     * (est.) Equivalent spherical emitting area for a rolled cap; temperature is escaping brightness temperature,
     * not the mixed outer gas temperature. The empirical pulse already describes escaping power, so do not
     * absorb it through the whole NO2 cap a second time. Beyond 10 t_max the pulse fit is an extrapolation.
     */
    public static double temperature(double t, double yieldKt, boolean surface, double radius) {
        if (t <= 0 || radius <= 0) return FireballModel.AMBIENT_K;
        double tm = FireballModel.secondMaximumSeconds(yieldKt);
        double ratio = FireballModel.brightness(t, yieldKt) / FireballModel.brightness(tm, yieldKt);
        double peakR = FireballModel.maxRadius(yieldKt, surface);
        double k = FireballModel.PEAK_K * Math.pow(ratio * peakR * peakR / (radius * radius), 0.25);
        return Math.clamp(k, FireballModel.AMBIENT_K, FireballModel.HOTTEST_K);
    }

    /** Escaping RGB and photopic luminance, noon white = 1. Cooling changes the spectrum, not only its amplitude. */
    public static void source(MushroomModel.Shape s, double yieldKt, boolean surface, double[] into) {
        BlackBody.light(temperature(s.t(), yieldKt, surface, s.capR()), into);
    }

    /** Log-space camera follow. dt and lag are game seconds; repeated/paused samples do not advance it. */
    public static double follow(double previous, double target, double dt, double lag) {
        if (dt <= 0) return previous;
        return Math.exp(Math.log(target) + (Math.log(previous) - Math.log(target)) * Math.exp(-dt / lag));
    }
}
