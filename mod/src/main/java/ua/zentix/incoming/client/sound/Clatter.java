package ua.zentix.incoming.client.sound;

import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.sound.Spectrum;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Destruction sound at the listener — glass and debris the wave actually tore off nearby (without Minecraft,
 * covered by a unit test). Each event has a position, time the front reached it, and sound power from source law
 * ({@link #watts}: a fraction of the torn material's mechanical energy); sound travels to the ear at sound speed,
 * along an ISO 9613 path ({@link Propagation}: spreading, air, ground, daytime shadow). Volume uses {@link Loudness},
 * as for all world sounds.
 * <p>
 * City destruction has thousands of sites over seconds, but the sound engine has hundreds of channels. So arrivals
 * sum by power (sounds from different sites are incoherent) into {@link #QUADRANTS} quarters around the ear,
 * separately for each kind: the quarter's first sound plays immediately; arrivals in the next {@link #HOLD} ticks
 * play next, summed. An event lasts {@code seconds} while torn material falls; a window longer than the event
 * spreads its energy: window power is P·min(1, duration / window). Sound direction and distance are weighted means
 * by the strength of its contributions.
 */
public final class Clatter {
    /** Destruction kinds: each has its own sound and recording. */
    public static final int GLASS = 0, DEBRIS = 1, KINDS = 2;
    static final int QUADRANTS = 4;
    /** A quarter and kind play at most once per this many ticks; intervening arrivals play next (0.5 s). */
    static final int HOLD = 10;
    /** Client carries sound no farther than {@link ua.zentix.incoming.strike.Hearing#RANGE}; event is forgotten too. */
    static final double MAX_TICKS = 60 * 20;

    /**
     * Destruction event: position, start time (caller clock ticks), sound power level L<sub>W</sub>, dB (not A),
     * duration, s, kind ({@link #GLASS}, {@link #DEBRIS}).
     */
    public record Event(double x, double y, double z, double startTick, double lw, double seconds, int kind) {}

    /**
     * Sound due to play: kind, point (direction and distance weighted by strength), A-level at the ear, dBA,
     * headroom above background in the best octave, dB, number of events it contains.
     */
    public record Play(int kind, double x, double y, double z, double level, double margin, int events) {}

    /** Arrivals at the ear in a quarter: octave power (fractions, A), direction and distance weighted by strength. */
    private static final class Bin {
        final double[] bands = new double[Propagation.BANDS];
        double weight, dx, dy, dz, dist;
        int events;
        /** When the quarter last played, ticks; NaN — silent (next plays immediately). */
        double since = Double.NaN;

        boolean empty() {
            return events == 0;
        }

        void clear() {
            java.util.Arrays.fill(bands, 0);
            weight = dx = dy = dz = dist = 0;
            events = 0;
        }
    }

    private final Spectrum[] spectra;
    private final List<Event> pending = new ArrayList<>();
    private final Bin[] bins = new Bin[KINDS * QUADRANTS];
    private final double[] scratch = new double[Propagation.BANDS];

    /** {@code spectra} — octave spectrum of each kind's recording ({@link ua.zentix.incoming.sound.Spectra}). */
    /** Gravitational acceleration, m/s². */
    static final double G = 9.81;
    /** Torn material falls from at least half a metre: material torn near the ground still falls and strikes. */
    static final double FALL_MIN = 0.5;

    /**
     * Sound duration for material torn off {@code height} m above ground, s: while falling, √(2h/g) (a sideways
     * pressure-driven throw does not lengthen the fall).
     */
    public static double seconds(double height) {
        return Math.sqrt(2 * Math.max(FALL_MIN, height) / G);
    }

    /**
     * Sound power of torn material, W: {@code kg} thrown by pressure at {@code speed} m/s and falling from
     * {@code height} m has energy m(v²/2 + g·h); fraction {@link Sources#IMPACT_EFFICIENCY} becomes sound over
     * the fall time ({@link #seconds}).
     */
    public static double watts(double kg, double speed, double height) {
        double h = Math.max(FALL_MIN, height);
        return Sources.IMPACT_EFFICIENCY * kg * (speed * speed / 2 + G * h) / seconds(h);
    }

    public Clatter(Spectrum[] spectra) {
        if (spectra.length != KINDS) throw new IllegalArgumentException("each kind needs a spectrum");
        this.spectra = spectra;
        for (int i = 0; i < bins.length; i++) bins[i] = new Bin();
    }

    public void add(Event e) {
        if (e.lw > Noise.SILENT) pending.add(e);
    }

    /** Events travelling to the ear. */
    public int pending() {
        return pending.size();
    }

    public void clear() {
        pending.clear();
        for (Bin b : bins) {
            b.clear();
            b.since = Double.NaN;
        }
    }

    /**
     * Tick {@code now}: events whose sound reached ear (ex, ey, ez) enter their quarters; quarters due to play
     * enter {@code out}. Audible ones have positive headroom above background.
     */
    public void tick(double now, double ex, double ey, double ez, Propagation.Weather weather, List<Play> out) {
        for (Iterator<Event> it = pending.iterator(); it.hasNext(); ) {
            Event e = it.next();
            if (now - e.startTick > MAX_TICKS) {
                it.remove();
                continue;
            }
            double dx = e.x - ex, dy = e.y - ey, dz = e.z - ez, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // sound travels from the site starting when the front reached it
            if (now < e.startTick + 20 * d / Noise.SOUND) continue;
            it.remove();
            Bin b = bins[e.kind * QUADRANTS + quadrant(dx, dz)];
            Propagation.level(e.lw, spectra[e.kind], Propagation.Path.over(d, Math.max(0, e.y - ey + Propagation.EAR), weather), scratch);
            // window longer than event: spread its energy over the whole window (quiet quarter's first sound plays in its own tick)
            double window = Double.isNaN(b.since) ? 0.05 : HOLD / 20.0;
            double share = Math.min(1, e.seconds / window);
            double w = 0;
            for (int k = 0; k < scratch.length; k++) {
                double p = Math.pow(10, scratch[k] / 10) * share;
                b.bands[k] += p;
                w += p;
            }
            double len = Math.max(d, 1e-6);
            b.weight += w;
            b.dx += w * dx / len;
            b.dy += w * dy / len;
            b.dz += w * dz / len;
            b.dist += w * d;
            b.events++;
        }
        for (int i = 0; i < bins.length; i++) {
            Bin b = bins[i];
            boolean quiet = Double.isNaN(b.since);
            if (quiet && b.empty()) continue;
            // silent quarter plays immediately; a previously playing one waits for its window to end
            if (!quiet && now - b.since < HOLD) continue;
            if (b.empty()) {
                b.since = Double.NaN;
                continue;
            }
            double sum = 0;
            for (int k = 0; k < b.bands.length; k++) {
                sum += b.bands[k];
                scratch[k] = 10 * Math.log10(Math.max(b.bands[k], 1e-30));
            }
            double margin = Propagation.margin(scratch, weather);
            if (margin > 0) {
                double r = Math.sqrt(b.dx * b.dx + b.dy * b.dy + b.dz * b.dz), dist = b.dist / b.weight;
                double ux = r > 1e-9 ? b.dx / r : 1, uy = r > 1e-9 ? b.dy / r : 0, uz = r > 1e-9 ? b.dz / r : 0;
                out.add(new Play(i / QUADRANTS, ex + ux * dist, ey + uy * dist, ez + uz * dist, 10 * Math.log10(sum), margin, b.events));
            }
            b.clear();
            b.since = now;
        }
    }

    /** Quarter around the ear by direction to the site (0 — east, then counterclockwise in x, z). */
    static int quadrant(double dx, double dz) {
        double a = Math.atan2(dz, dx) + Math.PI / 4;
        int q = (int) Math.floor(a / (Math.PI / 2));
        return Math.floorMod(q, QUADRANTS);
    }

    /** Game volume of sound {@code p} for a {@code fileLufs} file, with {@code ceilingLufs} ceiling ({@link Loudness}). */
    public static float volume(Play p, double fileLufs, double ceilingLufs) {
        return Loudness.volume(Loudness.gain(p.level, p.margin, ceilingLufs), fileLufs);
    }
}
