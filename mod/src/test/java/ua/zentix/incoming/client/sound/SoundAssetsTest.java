package ua.zentix.incoming.client.sound;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.sounds.JOrbisAudioStream;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponSpec;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sounds built by tools/build_sounds.py match the code: each ModSounds event is in sounds.json; every file exists
 * and is mono (Minecraft spatializes only mono; stereo would play "inside the head"); recording credits live
 * beside the sounds.
 */
class SoundAssetsTest {
    private static final String ASSETS = "/assets/incoming/";
    /** Angles of one explosion: the option selects one seed, so there are equal numbers of options. */
    private static final List<String> PERSPECTIVES = List.of("blast.near", "blast.mid", "blast.far");
    /** Explosions are played at the tick of the arrival of the front: the files are parsed in advance, without delay on the first sound. */
    private static final List<String> PRELOAD = List.of("blast.near", "blast.mid", "blast.far", "blast.sub");

    @Test
    void craterFireHasNoBakedFadeOrSilentLoopBoundary() throws IOException {
        for (String file : files("blast.fire")) {
            float[][] pcm = new float[1][];
            int rate = Envelope.read(open(file), pcm);
            float[] envelope = Envelope.of(pcm[0], rate);
            double peak = java.util.stream.IntStream.range(0, envelope.length).mapToDouble(i -> envelope[i]).max().orElseThrow();
            assertTrue(pcm[0].length >= 10 * rate, file + ": a varied recording, not a short repeated grain");
            int window = rate / 5;
            for (int start : new int[]{0, pcm[0].length - window}) {
                double ms = 0;
                for (int i = start; i < start + window; i++) ms += pcm[0][i] * pcm[0][i];
                assertTrue(10 * Math.log10(ms / window) > -35, file + ": audible at both sides of the loop seam");
            }
            assertTrue(Envelope.lufs(envelope[envelope.length - 1]) > Envelope.lufs(peak) - 12,
                    file + ": the model controls extinction; no five-second fade baked into the recording");
        }
    }

    /**
     * Loops of the roar of fires ({@link Quarters}) add up in power in the ear only if their sounds are incoherent for any
     *  difference in starts: each quarter has its own version of {@code blast.fire} - its own piece of the recording 483323, and not a shift of one record
     *  (the shifts are coherent when the difference in the starts of the loops coincides with a shift). One recording in two loops started in one tick,
     *  added up in amplitude: the fire between them was 2 dB louder than the sum of the powers (review R8, OpenAL loopback). Check
     *  - on the sound of the files themselves, on all shifts in a circle in full frequency: the largest |ρ| ≤ 0,05 (for two independent noises with
     *  this spectrum on 12 s - about 0,02), with simultaneous start ≤ 0,02; fire in the middle between two quarters - a pair of
     *  loops with volumes {@link Quarters} against the same power in one loop. The average volume of the loops is the same and is equal to
     * , according to which the client leads them to the law ({@code Spectra.BLAST_FIRE_LUFS}): the powers of the quarters are equal.
     */
    @Test
    void craterFireLoopsAreIncoherent() throws IOException {
        List<String> files = files("blast.fire");
        assertEquals(Quarters.N, files.size(), "according to the quarter option");
        for (String event : List.of("blast.fire", "blast.rumble")) {
            int n = files(event).size();
            Set<Integer> picked = new HashSet<>();
            for (int q = 0; q < Quarters.N; q++) {
                int got = net.minecraft.util.RandomSource.create(Quarters.seed(n, q)).nextInt(n);
                assertEquals(q % n, got, event + ": quarter " + q + " takes his option");
                picked.add(got);
            }
            assertEquals(Quarters.N, picked.size(), event + ": quarters have different options");
        }
        float[][] loops = new float[files.size()][];
        for (int i = 0; i < loops.length; i++) {
            float[][] pcm = new float[1][];
            int rate = Envelope.read(open(files.get(i)), pcm);
            loops[i] = pcm[0];
            assertEquals(ua.zentix.incoming.sound.Spectra.BLAST_FIRE_LUFS, loopLoudness(loops[i], rate), 0.3, files.get(i) + ": medium loop volume, LUFS");
        }
        int n = loops[0].length, size = Integer.highestOneBit(2 * n - 1) << 1;
        double[][] re = new double[loops.length][], im = new double[loops.length][];
        double[] energy = new double[loops.length];
        for (int i = 0; i < loops.length; i++) {
            assertEquals(n, loops[i].length, "loops of the same length");
            re[i] = new double[size];
            im[i] = new double[size];
            for (int t = 0; t < n; t++) {
                re[i][t] = loops[i][t];
                energy[i] += (double) loops[i][t] * loops[i][t];
            }
            EchoMix.fft(re[i], im[i], false);
        }
        Quarters mid = new Quarters();
        mid.add(Math.PI / 4, 1, 1, 1);
        double ga = mid.volume(0), gb = mid.volume(1);
        for (int i = 0; i < loops.length; i++) {
            for (int j = i + 1; j < loops.length; j++) {
                double[] cr = new double[size], ci = new double[size];
                for (int k = 0; k < size; k++) {
                    cr[k] = re[i][k] * re[j][k] + im[i][k] * im[j][k];
                    ci[k] = im[i][k] * re[j][k] - re[i][k] * im[j][k];
                }
                EchoMix.fft(cr, ci, true);
                double norm = Math.sqrt(energy[i] * energy[j]), start = cr[0] / norm, worst = 0;
                // shift τ in a circle: linear shifts τ and τ − n
                for (int tau = 0; tau < n; tau++) worst = Math.max(worst, Math.abs(cr[tau] + (tau == 0 ? 0 : cr[size + tau - n])) / norm);
                // fire between quarters: loops i and j with volumes ga and gb against the same power in one loop
                double one = (ga * ga + gb * gb) * (energy[i] + energy[j]) / 2, together = ga * ga * energy[i] + gb * gb * energy[j];
                double pair = 10 * Math.log10((together + 2 * ga * gb * worst * norm) / one);
                System.out.printf(java.util.Locale.ROOT, "blast.fire %d+%d: rho %+.4f at one start, max %.4f over all start offsets; pair vs one loop up to %+.3f dB%n",
                        i + 1, j + 1, start, worst, pair);
                assertTrue(Math.abs(start) <= 0.02, files.get(i) + " + " + files.get(j) + ": with simultaneous start |ρ| " + start);
                assertTrue(worst <= 0.05, files.get(i) + " + " + files.get(j) + ": at the worst start shift |ρ| " + worst);
                assertEquals(0, pair, 0.25, files.get(i) + " + " + files.get(j) + ": pair versus single loop, dB");
            }
        }
    }

    /** Average volume of event options {@code event} (average of the power of their average volumes), LUFS. */
    static double loopMean(String event) throws IOException {
        double sum = 0;
        List<String> files = files(event);
        for (String file : files) {
            float[][] pcm = new float[1][];
            int rate = Envelope.read(open(file), pcm);
            sum += Math.pow(10, loopLoudness(pcm[0], rate) / 10);
        }
        return 10 * Math.log10(sum / files.size());
    }

    /** Average volume of the entire loop (BS.1770: K-filter accelerated by the tail of the loop), LUFS. */
    private static double loopLoudness(float[] x, int rate) {
        double[] y = new double[x.length + rate];
        for (int i = 0; i < rate; i++) y[i] = x[x.length - rate + i];
        for (int i = 0; i < x.length; i++) y[rate + i] = x[i];
        Envelope.kWeight(y, rate);
        double ms = 0;
        for (int i = rate; i < y.length; i++) ms += y[i] * y[i];
        return -0.691 + 10 * Math.log10(ms / x.length);
    }

    /** Event variant files {@code event} to sounds.json. */
    private static List<String> files(String event) throws IOException {
        JsonObject json = JsonParser.parseReader(new InputStreamReader(open(ASSETS + "sounds.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        List<String> out = new java.util.ArrayList<>();
        for (JsonElement s : json.getAsJsonObject(event).getAsJsonArray("sounds")) {
            String name = s.isJsonObject() ? s.getAsJsonObject().get("name").getAsString() : s.getAsString();
            out.add(ASSETS + "sounds/" + name.substring("incoming:".length()) + ".ogg");
        }
        return out;
    }

    @Test
    void everyEventHasMonoFiles() throws IOException {
        JsonObject json = JsonParser.parseReader(new InputStreamReader(open(ASSETS + "sounds.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        for (var e : json.entrySet()) {
            var sounds = e.getValue().getAsJsonObject().getAsJsonArray("sounds");
            assertTrue(sounds.size() > 0, e.getKey());
            for (JsonElement s : sounds) {
                String name = s.isJsonObject() ? s.getAsJsonObject().get("name").getAsString() : s.getAsString();
                String file = ASSETS + "sounds/" + name.substring("incoming:".length()) + ".ogg";
                try (InputStream in = open(file)) {
                    assertEquals(1, vorbisChannels(in.readNBytes(4096)), file + ": channels");
                }
            }
        }
        Set<String> registered = new HashSet<>();
        Matcher m = Pattern.compile("register\\(\"([a-z0-9_.]+)\"\\)")
                .matcher(Files.readString(Path.of("src/main/java/ua/zentix/incoming/registry/ModSounds.java")));
        while (m.find()) registered.add(m.group(1));
        assertTrue(registered.size() > 30);
        assertEquals(registered, json.keySet(), "events ModSounds and sounds.json");
        for (String p : PERSPECTIVES) {
            assertEquals(variants(json, PERSPECTIVES.get(0)), variants(json, p), "the options for angles are equal: " + p);
            for (JsonElement s : json.getAsJsonObject(p).getAsJsonArray("sounds")) {
                assertTrue(!s.isJsonObject() || !s.getAsJsonObject().has("weight"), p + ": weights are equal - otherwise the seed will select different records");
            }
        }
        for (String p : PRELOAD) {
            for (JsonElement s : json.getAsJsonObject(p).getAsJsonArray("sounds")) {
                assertTrue(s.isJsonObject() && s.getAsJsonObject().has("preload") && s.getAsJsonObject().get("preload").getAsBoolean(), p + ": preload");
            }
        }
        try (InputStream in = open(ASSETS + "sounds/credits.txt")) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("freesound.org"));
        }
    }

    /**
     * Shahed loop - motor at rated speed at its speed: the strongest tone - shaft MD-550 ({@code
     * WeaponSpec.DRONE_PISTON}: 7500 rpm - 125 Hz, screw directly on the shaft). For 3.5.0, the recording was accelerated three times to 250 Hz -
     * , together with the tone, the resonances of the body and exhaust (“flies”, sample P 21.10.2026) went up.
     */
    @Test
    void shahedLoopSoundsAtTheShaftOrder() throws IOException {
        float[] x;
        int rate;
        try (InputStream in = open(ASSETS + "sounds/drone_engine.ogg"); JOrbisAudioStream ogg = new JOrbisAudioStream(in)) {
            rate = (int) ogg.getFormat().getSampleRate();
            FloatArrayList samples = new FloatArrayList();
            while (ogg.readChunk(samples::add)) {
                // the whole loop
            }
            x = samples.toFloatArray();
        }
        // average spectrum of windows according to 2¹⁵ sample (~0,74 s): 1,35 Hz per band
        int n = 1 << 15;
        double[] power = new double[n / 2];
        for (int s = 0; s + n <= x.length; s += n / 2) {
            double[] re = new double[n], im = new double[n];
            for (int i = 0; i < n; i++) re[i] = x[s + i] * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / n));
            EchoMix.fft(re, im, false);
            for (int i = 0; i < n / 2; i++) power[i] += re[i] * re[i] + im[i] * im[i];
        }
        int lo = (int) (40.0 * n / rate), best = lo;
        for (int i = lo; i < (int) (1200.0 * n / rate); i++) if (power[i] > power[best]) best = i;
        double shaft = WeaponSpec.DRONE_PISTON.rpm() / 60;
        assertEquals(shaft, best * (double) rate / n, shaft * 0.03, "the strongest tone of the loop is the shaft");
    }

    /**
     * Files of motors and launches - the volume at which the client expects them ({@link Loudness#MOTOR_LUFS} for events
     * {@code MOTOR} assembly, {@link Loudness#ROCKET_LAUNCH_LUFS} for launch rail exit of an MLRS projectile, {@link Loudness#ENGINE_LUFS} for
     *  clap of a compartment and catapult): the file is quieter than the calculation - the volume rests on it earlier ceiling, and
     *  sounds the same louder (3.5.0: the impact of igniting the rocket and SAM is closer ~45 m to the launcher). The measure is the highest instantaneous
     *  volume EBU R128, at the hinges - in a circle, as in an assembly.
     */
    @Test
    void motorFilesAtTheirLoudness() throws IOException {
        JsonObject json = JsonParser.parseReader(new InputStreamReader(open(ASSETS + "sounds.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        Matcher m = Pattern.compile("(?ms)^MOTOR = \\{(.*?)}").matcher(Files.readString(Path.of("../tools/build_sounds.py")));
        assertTrue(m.find(), "MOTOR to build_sounds.py");
        Set<String> motor = new HashSet<>();
        Matcher q = Pattern.compile("\"([a-z.]+)\"").matcher(m.group(1));
        while (q.find()) motor.add(q.group(1));
        assertTrue(motor.contains("launch.booster") && motor.contains("booster.engine") && motor.size() > 15, "MOTOR: " + motor);
        Set<String> oneShots = Set.of("launch.booster", "rocket.launch", "booster.separate", "loiter.launch");
        Set<String> checked = new HashSet<>(motor);
        checked.addAll(oneShots);
        for (String ev : checked) {
            double want = motor.contains(ev) ? ev.equals("rocket.launch") ? Loudness.ROCKET_LAUNCH_LUFS : Loudness.MOTOR_LUFS : Loudness.ENGINE_LUFS;
            for (JsonElement s : json.getAsJsonObject(ev).getAsJsonArray("sounds")) {
                String name = s.isJsonObject() ? s.getAsJsonObject().get("name").getAsString() : s.getAsString();
                String file = ASSETS + "sounds/" + name.substring("incoming:".length()) + ".ogg";
                double got = momentaryMax(file, !oneShots.contains(ev));
                assertEquals(want, got, 0.5, file + ": volume, LUFS");
            }
        }
    }

    /**
     * Explosion hum loops ({@link Rumble}): optionally a quarter around the ear, each at the volume at which the client
     *  drives the hum ({@link BlastSounds#RUMBLE_LUFS}); evenness (RMS of instantaneous loudness ≤ 1 dB) is checked by the assembly.
     */
    @Test
    void rumbleLoopsAtTheirLoudness() throws IOException {
        JsonObject json = JsonParser.parseReader(new InputStreamReader(open(ASSETS + "sounds.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(Rumble.QUADRANTS, variants(json, "blast.rumble"));
        for (JsonElement s : json.getAsJsonObject("blast.rumble").getAsJsonArray("sounds")) {
            String name = s.isJsonObject() ? s.getAsJsonObject().get("name").getAsString() : s.getAsString();
            String file = ASSETS + "sounds/" + name.substring("incoming:".length()) + ".ogg";
            assertEquals(BlastSounds.RUMBLE_LUFS, momentaryMax(file, true), 0.5, file + ": volume, LUFS");
        }
    }

    /**
     * The sound of the mod in the world is only according to the law of source and path ({@code Loudness}, {@code BlastMix}, {@code Sources}): not a single
     *  vanilla playback with volume by eye (the volume is greater than 1 for Minecraft - the range of 16 blocks per unit). So
     * 3.4.1 played the roar of fire in the crater with a volume of 5 to 90 blocks: the tail of the explosion in 50 m - 135 dBA-equivalent, louder than a
     *  jet engine, although the fire ~2 MW there ~80 dB. UI-sounds without a place ({@code forUI}) do not count.
     * An event in a variable or its own wrapper is not caught by this search; The client does not have a wrapper with manual volume
     *  ({@code ClientSounds.at} has been removed).
     */
    @Test
    void worldSoundsHaveNoHandVolume() throws IOException {
        Pattern hand = Pattern.compile("(playSound|playLocalSound|playSeededSound|new SimpleSoundInstance|SimpleSoundInstance\\.forAmbientAddition)\\(");
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                // by operators, not by lines: a call divided into lines is also
                for (String st : Files.readString(f).split(";")) {
                    assertTrue(!(st.contains("ModSounds.") && hand.matcher(st).find()), f + ": sound mod volume by eye - " + st.strip());
                }
            }
        }
    }

    /** The highest instantaneous volume of the file (BS.1770: K filter, window 400 ms through 100 ms), LUFS; loop - in a circle. */
    static double momentaryMax(String file, boolean loop) throws IOException {
        float[] x;
        int rate;
        try (InputStream in = open(file); JOrbisAudioStream ogg = new JOrbisAudioStream(in)) {
            rate = (int) ogg.getFormat().getSampleRate();
            FloatArrayList samples = new FloatArrayList();
            while (ogg.readChunk(samples::add)) {
                // whole file
            }
            x = samples.toFloatArray();
        }
        int n = x.length, w = (int) (0.4 * rate), h = rate / 10;
        // loop: the filter is dispersed by the tail of the loop, the windows go through the seam
        int pre = loop ? Math.min(n, rate) : 0;
        double[] y = new double[pre + n + (loop ? w : 0)];
        for (int i = 0; i < y.length; i++) {
            int k = i - pre;
            y[i] = k < 0 ? x[n + k] : k < n ? x[k] : x[(k - n) % n];
        }
        Envelope.kWeight(y, rate);
        double[] c = new double[y.length + 1];
        for (int i = 0; i < y.length; i++) c[i + 1] = c[i] + y[i] * y[i];
        double best = 0;
        int end = loop ? n : Math.max(1, n - w);
        for (int i = 0; i < end; i += h) best = Math.max(best, (c[pre + Math.min(i + w, y.length - pre)] - c[pre + i]) / w);
        return -0.691 + 10 * Math.log10(best + 1e-15);
    }

    private static int variants(JsonObject json, String event) {
        return json.getAsJsonObject(event).getAsJsonArray("sounds").size();
    }

    private static InputStream open(String path) {
        InputStream in = SoundAssetsTest.class.getResourceAsStream(path);
        assertNotNull(in, path);
        return in;
    }

    /** Number of channels from the Vorbis header: “\1vorbis”, version (4 bytes), channels (1 bytes). */
    private static int vorbisChannels(byte[] head) {
        byte[] magic = "\u0001vorbis".getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i + magic.length + 5 < head.length; i++) {
            for (int j = 0; j < magic.length; j++) if (head[i + j] != magic[j]) continue outer;
            return head[i + magic.length + 4];
        }
        return -1;
    }
}
