package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.sound.Spectra;
import ua.zentix.incoming.sound.Spectrum;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sound of destruction from the listener ({@link Clatter}): law of the source, arrival at the speed of sound, sum of power. */
class ClatterTest {
    private static final Spectrum[] SPECTRA = {Spectra.DEBRIS_FALL, Spectra.DEBRIS_FALL};
    private static final Propagation.Weather NIGHT = Propagation.Weather.NIGHT;

    /** Not a single destruction is heard before the front reaches it and the sound comes from it. */
    @Test
    void heardOnlyAfterFrontAndSoundPath() {
        Clatter c = new Clatter(SPECTRA);
        double lw = Sources.lw(Clatter.watts(2300, 10, 5));
        c.add(new Clatter.Event(Noise.SOUND, 0, 0, 100, lw, 1, Clatter.DEBRIS));
        List<Clatter.Play> out = new ArrayList<>();
        for (int t = 0; t < 120; t++) c.tick(t, 0, 0, 0, NIGHT, out);
        assertTrue(out.isEmpty(), "until the sound arrives - silence");
        c.tick(120, 0, 0, 0, NIGHT, out);
        assertEquals(1, out.size(), "sound - through 343 m / 343 m/s after the front");
    }

    /** Sounds from different places are incoherent: two identical destructions at once - +3 dB, not +6. */
    @Test
    void sameMomentAddsByPower() {
        double one = level(1), two = level(2);
        assertEquals(10 * Math.log10(2), two - one, 0.01);
    }

    private static double level(int n) {
        Clatter c = new Clatter(SPECTRA);
        for (int i = 0; i < n; i++) c.add(new Clatter.Event(80, 0, 10, 0, 120, 1, Clatter.GLASS));
        List<Clatter.Play> out = new ArrayList<>();
        for (int t = 0; t < 40 && out.isEmpty(); t++) c.tick(t, 0, 0, 0, NIGHT, out);
        assertEquals(1, out.size());
        return out.getFirst().level();
    }

    /** Destruction from different sides - different sounds, each from its own side; views - their sounds. */
    @Test
    void sidesAndKindsApart() {
        Clatter c = new Clatter(SPECTRA);
        c.add(new Clatter.Event(100, 0, 0, 0, 120, 1, Clatter.DEBRIS));
        c.add(new Clatter.Event(-100, 0, 0, 0, 120, 1, Clatter.DEBRIS));
        c.add(new Clatter.Event(100, 0, 0, 0, 110, 1, Clatter.GLASS));
        List<Clatter.Play> out = new ArrayList<>();
        for (int t = 0; t < 10; t++) c.tick(t, 0, 0, 0, NIGHT, out);
        assertEquals(3, out.size());
        for (Clatter.Play p : out) {
            assertEquals(100, Math.hypot(p.x(), p.z()), 1e-6, "distance - like a place");
            assertTrue(p.kind() == Clatter.GLASS ? p.x() > 0 : true);
        }
        assertTrue(out.stream().anyMatch(p -> p.kind() == Clatter.DEBRIS && p.x() < 0) && out.stream().anyMatch(p -> p.kind() == Clatter.DEBRIS && p.x() > 0));
    }

    /**
     * The first sound of the quarter - immediately, coming after it - with one sound through {@link Clatter#HOLD} tick, in total: the event
     *  is shorter than the window, stretched over the window (its energy is the same).
     */
    @Test
    void holdGathersWithEnergyKept() {
        Clatter c = new Clatter(SPECTRA);
        List<Clatter.Play> out = new ArrayList<>();
        c.add(new Clatter.Event(20, 0, 0, 0, 120, 1, Clatter.DEBRIS));
        int firstTick = -1;
        for (int t = 0; t < 3; t++) {
            c.tick(t, 0, 0, 0, NIGHT, out);
            if (firstTick < 0 && !out.isEmpty()) firstTick = t;
        }
        assertEquals(1, out.size());
        double first = out.getFirst().level();
        double played = Double.NaN;
        // two more - one shorter than the window (0,1 s), one longer
        c.add(new Clatter.Event(20, 0, 0, 2, 120, 0.1, Clatter.DEBRIS));
        c.add(new Clatter.Event(20, 0, 0, 2, 120, 1, Clatter.DEBRIS));
        for (int t = 3; t < 30; t++) {
            int was = out.size();
            c.tick(t, 0, 0, 0, NIGHT, out);
            if (out.size() > was) {
                assertTrue(Double.isNaN(played), "with one sound");
                played = t;
            }
        }
        assertEquals(firstTick + Clatter.HOLD, played, 1e-9, "through the window after the first");
        double want = first + 10 * Math.log10(1 + 0.1 / (Clatter.HOLD / 20.0));
        assertEquals(want, out.get(1).level(), 0.01);
    }

    /** Further - quieter: scattering and air. */
    @Test
    void fartherIsQuieter() {
        double prev = Double.POSITIVE_INFINITY;
        for (double d : new double[]{10, 30, 100, 300, 1000}) {
            Clatter c = new Clatter(SPECTRA);
            c.add(new Clatter.Event(d, 0, 0, 0, 130, 1, Clatter.DEBRIS));
            List<Clatter.Play> out = new ArrayList<>();
            for (int t = 0; t < 80 && out.isEmpty(); t++) c.tick(t, 0, 0, 0, NIGHT, out);
            assertEquals(1, out.size(), "heard on " + d);
            assertTrue(out.getFirst().level() < prev - 5, "quieter on " + d);
            prev = out.getFirst().level();
        }
    }

    /**
     * Source law: a window made of 25 kg of glass thrown by pressure 10 m/s with 5 m, - energy 25·(50 + 49) J, sound - 10⁻⁴ for
     * √(2·5/9,81) s ≈ 1 s: 0,25 W, L<sub>W</sub> ≈ 114 dB.
     */
    @Test
    void sourceLawOfThrownGlass() {
        double w = Clatter.watts(25, 10, 5);
        assertEquals(1e-4 * 25 * (50 + 9.81 * 5) / Math.sqrt(2 * 5 / 9.81), w, 1e-9);
        assertEquals(114, Sources.lw(w), 0.5);
        assertEquals(Clatter.seconds(Clatter.FALL_MIN), Clatter.seconds(0), 1e-12, "near the ground - fall from half a meter");
    }
}
