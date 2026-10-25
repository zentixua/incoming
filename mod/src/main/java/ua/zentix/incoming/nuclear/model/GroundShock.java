package ua.zentix.incoming.nuclear.model;

/**
 * Conventional free-field ground motion in SI units; independent of the nuclear path and of Minecraft.
 * <p>
 * Drake &amp; Little, TM 5-855-1 (1986), ch. 5: V = 160 f (R/W^(1/3))^(-n) ft/s, R in ft, W in lb TNT.
 * Сверено с формулой TM через её опубликованное воспроизведение: Chew &amp; Leong, APCOM 2013, p. 2,
 * eqs. (1.2), (1.3), https://www.sci-en-tech.com/apcom2013/APCOM2013-Proceedings/PDF_FullPaper/1836.pdf .
 * Acceleration in (1.2) is in g, velocity in (1.3) in ft/s: a0/V0 = (50 g_ft/160) c/R, not V0/d0.
 * The coefficient is NOT an SI coefficient. This empirical homogeneous-medium law omits layers, cavities and
 * surface-wave conversions; it is not a seismic site survey or an injury model.
 */
public final class GroundShock {
    /** Exact international foot and avoirdupois pound in SI. */
    private static final double METRES_PER_FOOT = 0.3048, KG_PER_POUND = 0.45359237;
    /** Standard gravity, m/s²; converts TM (1.2)'s g to SI (g_ft = 32.17405 ft/s²). */
    private static final double GRAVITY = 9.80665;
    /** Dimensionless coefficient in omega = a0/V0 = coefficient*c/R, TM (1.2)/(1.3). */
    private static final double ACCELERATION_VELOCITY_RATIO = 50 * GRAVITY / (160 * METRES_PER_FOOT);
    /** Contact coupling ~0.14; full containment at scaled burial 1.4 ft/lb^(1/3), TM ch. 5 coupling chart. */
    private static final double CONTACT = 0.14, CONTAINED_DEPTH = 1.4;
    /**
     * Estimated transition for perception, m/s PPV: 0.1--0.3 mm/s, not a damage threshold. DIN 4150-2 perception
     * table reproduced in Melbourne Metro EES ch. 13, table 13-4 (2016): 0.1 imperceptible, 0.15 threshold,
     * 0.35 barely noticeable. Applying these vibration ranges to a single impulse is an estimate, frequency dependent.
     */
    public static final double PERCEPTION_START_MPS = 0.0001, PERCEPTION_FULL_MPS = 0.0003;
    /** DIN 4150-2 assessment domain, Hz: https://www.dinmedia.de/de/norm/din-4150-2/390807042 . */
    private static final double PERCEPTION_MIN_HZ = 1, PERCEPTION_MAX_HZ = 80;
    /** Near-field validity cutoff, estimate: do not extrapolate the singular empirical law inside Z = 1 ft/lb^(1/3). */
    private static final double MIN_SCALED_RANGE = 1;
    private GroundShock() {}

    /** GroundMaterial maps block materials to these homogeneous propagation media; enum order is not a wire id. */
    public enum Medium { LOOSE, SOIL, SATURATED, ROCK }

    /**
     * @param tntKg full TNT-equivalent charge/source energy, kg; depth coupling already accounts for venting to air
     * @param depthM burial below the local surface, m; zero means contact
     * @param medium homogeneous ground approximation selected at the source
     */
    public record Source(double tntKg, double depthM, Medium medium) {}

    /**
     * @param arrivalSeconds time since the source event, s, R/c (not rounded to ticks)
     * @param peakVelocityMps peak ground particle velocity, m/s, before perceptibility filtering
     * @param frequencyHz characteristic frequency a0/(2*pi*V0), Hz; one pulse lasts 1/frequencyHz
     * @param perceptibility smooth 0..1 presentation weight within 1--80 Hz; zero outside the supported assessment
     *                       domain, not a claim of physical imperceptibility there or a full DIN KB/F assessment
     */
    public record Sample(double arrivalSeconds, double peakVelocityMps, double frequencyHz, double perceptibility) {
        /** Pulse length after arrival, s; zero for a zero-energy source. */
        public double durationSeconds() {
            return frequencyHz > 0 ? 1 / frequencyHz : 0;
        }

        /** Peak acceleration, m/s², shared by the TM prediction and the time-domain pulse. */
        public double peakAccelerationMps2() {
            return 2 * Math.PI * frequencyHz * peakVelocityMps;
        }
    }

    /** Ground motion at slant range in m; source and listener are assumed to be in the same medium. */
    public static Sample sample(Source source, double rangeM) {
        double r = Math.max(0, rangeM), c = waveSpeed(source.medium());
        if (!(source.tntKg() > 0)) return new Sample(r / c, 0, 0, 0);
        double length = scaledLengthM(source), z = Math.max(MIN_SCALED_RANGE, r / length);
        double peak = 160 * METRES_PER_FOOT * coupling(source) * Math.pow(z, -attenuation(source.medium()));
        // TM (1.2): a0*W^(1/3) = 50*f*c*z^(-n-1) g. Dividing by V0 cancels the charge/soil amplitude terms.
        // One characteristic cycle lasts ~0.625 R/c, not the ~19.6 R/c obtained by treating V0/d0 as omega.
        // Frequency/spectrum and resulting displacement remain estimates; the pulse preserves TM V0 AND a0,
        // not the separately fitted TM residual/peak displacement d0. Same near-field cutoff as PPV.
        double frequency = ACCELERATION_VELOCITY_RATIO * c / (2 * Math.PI * z * length);
        double felt = smooth((peak - PERCEPTION_START_MPS) / (PERCEPTION_FULL_MPS - PERCEPTION_START_MPS)) * perceptionBand(frequency);
        return new Sample(r / c, peak, frequency, felt);
    }

    /** Depth coupling f, dimensionless; source mass is kg and depth is m. */
    public static double coupling(Source source) {
        if (!(source.tntKg() > 0)) return 0;
        // Estimate between the two endpoints of the TM burial chart: smooth interpolation, not a digitised soil curve.
        double depth = Math.max(0, source.depthM()) / scaledLengthM(source);
        return CONTACT + (1 - CONTACT) * smooth(depth / CONTAINED_DEPTH);
    }

    /** Propagation speed c in m/s; this is not peak ground particle velocity. */
    public static double waveSpeed(Medium medium) {
        // TM soil classes: loose dry sand ~600 ft/s, sandy loam ~1000, saturated sandy clay ~5000.
        // ROCK is an estimate, c=3000 m/s for fractured rock/masonry, not the TM soil table's measured rock value.
        return switch (medium) {
            case LOOSE -> 600 * METRES_PER_FOOT;
            case SOIL -> 1000 * METRES_PER_FOOT;
            case SATURATED -> 5000 * METRES_PER_FOOT;
            case ROCK -> 3000;
        };
    }

    /** Signed ground velocity in m/s at elapsed seconds since source; zero before arrival, smooth onset and end. */
    public static double velocityMps(Sample sample, double elapsedSeconds) {
        double cycles = (elapsedSeconds - sample.arrivalSeconds()) * sample.frequencyHz();
        if (!(cycles > 0 && cycles < 1)) return 0;
        // Estimated bipolar shape: quarter-cycle sin² ramps join the central half-sine with continuous velocity
        // and acceleration. max|v|=V0, max|dv/dt|=2*pi*f*V0=a0; zero velocity/slope at both ends and no residual
        // displacement. Applying an arbitrary envelope to a sine would change its peak acceleration again.
        double s = Math.sin(2 * Math.PI * cycles);
        return sample.peakVelocityMps() * (cycles < 0.25 ? s * s : cycles > 0.75 ? -s * s : s);
    }

    /** Outer recipient bound, m: both PPV and frequency must remain in the supported perception domain. */
    public static double feltRangeM(Source source) {
        if (!(source.tntKg() > 0)) return 0;
        double velocityBound = scaledLengthM(source) * Math.pow(160 * METRES_PER_FOOT * coupling(source) / PERCEPTION_START_MPS,
                1 / attenuation(source.medium()));
        double frequencyBound = ACCELERATION_VELOCITY_RATIO * waveSpeed(source.medium()) / (2 * Math.PI * PERCEPTION_MIN_HZ);
        return Math.min(velocityBound, frequencyBound);
    }

    private static double perceptionBand(double frequency) {
        // Presentation estimate: one-octave tapers INSIDE the DIN domain (1--2 and 40--80 Hz), zero outside.
        // They prevent a new step at the limits without extending the PPV criterion to unsupported frequencies.
        return smooth((frequency - PERCEPTION_MIN_HZ) / PERCEPTION_MIN_HZ)
                * smooth((PERCEPTION_MAX_HZ - frequency) / (PERCEPTION_MAX_HZ / 2));
    }

    /** TM soil attenuation classes; choices within published ranges are estimates, not fitted game distances. */
    private static double attenuation(Medium medium) {
        return switch (medium) {
            case LOOSE -> 3.125; // midpoint of dry loose sand 3--3.25
            case SOIL -> 2.75; // sandy loam / medium-density dry sand
            case SATURATED -> 2.375; // midpoint of saturated sandy clay 2.25--2.5
            case ROCK -> 1.5; // estimate using the low-attenuation clay/shale limit; rock is not homogeneous soil
        };
    }

    private static double scaledLengthM(Source source) {
        return METRES_PER_FOOT * Math.cbrt(source.tntKg() / KG_PER_POUND);
    }

    private static double smooth(double x) {
        x = Math.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }
}
