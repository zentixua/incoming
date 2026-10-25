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
 * Звуки, собранные tools/build_sounds.py, сходятся с кодом: каждое событие ModSounds есть в sounds.json, каждый
 * файл есть и он моно (Minecraft размещает в пространстве только моно — стерео звучал бы «в голове»), авторы записей
 * лежат рядом со звуками.
 */
class SoundAssetsTest {
    private static final String ASSETS = "/assets/incoming/";
    /** Ракурсы одного взрыва: вариант выбирает одно зерно, поэтому вариантов поровну. */
    private static final List<String> PERSPECTIVES = List.of("blast.near", "blast.mid", "blast.far");
    /** Взрывы играются в тик прихода фронта: файлы разобраны заранее, без задержки на первом звуке. */
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
     * Петли гула пожаров ({@link Quarters}) складываются в ухе по мощности, только если их звуки некогерентны при любой
     * разнице стартов: у каждой четверти свой вариант {@code blast.fire} — свой кусок записи 483323, а не сдвиг одной записи
     * (сдвиги когерентны, когда разница стартов петель совпадёт со сдвигом). Одна запись в двух петлях, начатых в один тик,
     * складывалась по амплитуде: пожар между ними был на 2 дБ громче суммы мощностей (ревью R8, OpenAL loopback). Проверка
     * — на звуке самих файлов, на всех сдвигах по кругу в полной частоте: наибольший |ρ| ≤ 0,05 (у двух независимых шумов с
     * этим спектром на 12 с — около 0,02), при одновременном старте ≤ 0,02; пожар посередине между двумя четвертями — пара
     * петель с громкостями {@link Quarters} против той же мощности в одной петле. Средняя громкость у петель одна и равна
     * той, по которой клиент приводит их к закону ({@code Spectra.BLAST_FIRE_LUFS}): мощности четвертей равны.
     */
    @Test
    void craterFireLoopsAreIncoherent() throws IOException {
        List<String> files = files("blast.fire");
        assertEquals(Quarters.N, files.size(), "по варианту на четверть");
        for (String event : List.of("blast.fire", "blast.rumble")) {
            int n = files(event).size();
            Set<Integer> picked = new HashSet<>();
            for (int q = 0; q < Quarters.N; q++) {
                int got = net.minecraft.util.RandomSource.create(Quarters.seed(n, q)).nextInt(n);
                assertEquals(q % n, got, event + ": четверть " + q + " берёт свой вариант");
                picked.add(got);
            }
            assertEquals(Quarters.N, picked.size(), event + ": у четвертей разные варианты");
        }
        float[][] loops = new float[files.size()][];
        for (int i = 0; i < loops.length; i++) {
            float[][] pcm = new float[1][];
            int rate = Envelope.read(open(files.get(i)), pcm);
            loops[i] = pcm[0];
            assertEquals(ua.zentix.incoming.sound.Spectra.BLAST_FIRE_LUFS, loopLoudness(loops[i], rate), 0.3, files.get(i) + ": средняя громкость петли, LUFS");
        }
        int n = loops[0].length, size = Integer.highestOneBit(2 * n - 1) << 1;
        double[][] re = new double[loops.length][], im = new double[loops.length][];
        double[] energy = new double[loops.length];
        for (int i = 0; i < loops.length; i++) {
            assertEquals(n, loops[i].length, "петли одной длины");
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
                // сдвиг τ по кругу: линейные сдвиги τ и τ − n
                for (int tau = 0; tau < n; tau++) worst = Math.max(worst, Math.abs(cr[tau] + (tau == 0 ? 0 : cr[size + tau - n])) / norm);
                // пожар между четвертями: петли i и j с громкостями ga и gb против той же мощности в одной петле
                double one = (ga * ga + gb * gb) * (energy[i] + energy[j]) / 2, together = ga * ga * energy[i] + gb * gb * energy[j];
                double pair = 10 * Math.log10((together + 2 * ga * gb * worst * norm) / one);
                System.out.printf(java.util.Locale.ROOT, "blast.fire %d+%d: rho %+.4f at one start, max %.4f over all start offsets; pair vs one loop up to %+.3f dB%n",
                        i + 1, j + 1, start, worst, pair);
                assertTrue(Math.abs(start) <= 0.02, files.get(i) + " + " + files.get(j) + ": при одновременном старте |ρ| " + start);
                assertTrue(worst <= 0.05, files.get(i) + " + " + files.get(j) + ": на худшем сдвиге старта |ρ| " + worst);
                assertEquals(0, pair, 0.25, files.get(i) + " + " + files.get(j) + ": пара против одной петли, дБ");
            }
        }
    }

    /** Средняя громкость вариантов события {@code event} (средняя по мощности их средних громкостей), LUFS. */
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

    /** Средняя громкость петли целиком (BS.1770: К-фильтр, разогнанный хвостом петли), LUFS. */
    private static double loopLoudness(float[] x, int rate) {
        double[] y = new double[x.length + rate];
        for (int i = 0; i < rate; i++) y[i] = x[x.length - rate + i];
        for (int i = 0; i < x.length; i++) y[rate + i] = x[i];
        Envelope.kWeight(y, rate);
        double ms = 0;
        for (int i = rate; i < y.length; i++) ms += y[i] * y[i];
        return -0.691 + 10 * Math.log10(ms / x.length);
    }

    /** Файлы вариантов события {@code event} в sounds.json. */
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
                    assertEquals(1, vorbisChannels(in.readNBytes(4096)), file + ": каналы");
                }
            }
        }
        Set<String> registered = new HashSet<>();
        Matcher m = Pattern.compile("register\\(\"([a-z0-9_.]+)\"\\)")
                .matcher(Files.readString(Path.of("src/main/java/ua/zentix/incoming/registry/ModSounds.java")));
        while (m.find()) registered.add(m.group(1));
        assertTrue(registered.size() > 30);
        assertEquals(registered, json.keySet(), "события ModSounds и sounds.json");
        for (String p : PERSPECTIVES) {
            assertEquals(variants(json, PERSPECTIVES.get(0)), variants(json, p), "вариантов у ракурсов поровну: " + p);
            for (JsonElement s : json.getAsJsonObject(p).getAsJsonArray("sounds")) {
                assertTrue(!s.isJsonObject() || !s.getAsJsonObject().has("weight"), p + ": веса равные — иначе зерно выберет разные записи");
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
     * Петля шахеда — мотор на номинальных оборотах в своей скорости: самый сильный тон — вал MD-550 ({@code
     * WeaponSpec.DRONE_PISTON}: 7500 об/мин — 125 Гц, винт прямо на валу). У 3.5.0 запись ускорили втрое до 250 Гц —
     * вместе с тоном ушли вверх резонансы корпуса и выхлопа («мухи», проба P 21.10.2026).
     */
    @Test
    void shahedLoopSoundsAtTheShaftOrder() throws IOException {
        float[] x;
        int rate;
        try (InputStream in = open(ASSETS + "sounds/drone_engine.ogg"); JOrbisAudioStream ogg = new JOrbisAudioStream(in)) {
            rate = (int) ogg.getFormat().getSampleRate();
            FloatArrayList samples = new FloatArrayList();
            while (ogg.readChunk(samples::add)) {
                // вся петля
            }
            x = samples.toFloatArray();
        }
        // средний спектр окон по 2¹⁵ отсчётов (~0,74 с): 1,35 Гц на полосу
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
        assertEquals(shaft, best * (double) rate / n, shaft * 0.03, "самый сильный тон петли — вал");
    }

    /**
     * Файлы моторов и стартов — той громкости, на которую клиент их рассчитывает ({@link Loudness#MOTOR_LUFS} у событий
     * {@code MOTOR} сборки, {@link Loudness#ROCKET_LAUNCH_LUFS} у схода снаряда РСЗО, {@link Loudness#ENGINE_LUFS} у
     * хлопка отделения и катапульты): файл тише расчёта — громкость упирается в него раньше потолка, и всё громче звучит
     * одинаково (3.5.0: удар поджига ракеты и ЗРК ближе ~45 м к пусковой). Мера — наибольшая мгновенная
     * громкость EBU R128, у петель — по кругу, как в сборке.
     */
    @Test
    void motorFilesAtTheirLoudness() throws IOException {
        JsonObject json = JsonParser.parseReader(new InputStreamReader(open(ASSETS + "sounds.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        Matcher m = Pattern.compile("(?ms)^MOTOR = \\{(.*?)}").matcher(Files.readString(Path.of("../tools/build_sounds.py")));
        assertTrue(m.find(), "MOTOR в build_sounds.py");
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
                assertEquals(want, got, 0.5, file + ": громкость, LUFS");
            }
        }
    }

    /**
     * Петли гула взрывов ({@link Rumble}): по варианту на четверть вокруг уха, каждая — на громкости, по которой клиент
     * ведёт гул ({@link BlastSounds#RUMBLE_LUFS}); ровность (СКО мгновенной громкости ≤ 1 дБ) проверяет сборка.
     */
    @Test
    void rumbleLoopsAtTheirLoudness() throws IOException {
        JsonObject json = JsonParser.parseReader(new InputStreamReader(open(ASSETS + "sounds.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(Rumble.QUADRANTS, variants(json, "blast.rumble"));
        for (JsonElement s : json.getAsJsonObject("blast.rumble").getAsJsonArray("sounds")) {
            String name = s.isJsonObject() ? s.getAsJsonObject().get("name").getAsString() : s.getAsString();
            String file = ASSETS + "sounds/" + name.substring("incoming:".length()) + ".ogg";
            assertEquals(BlastSounds.RUMBLE_LUFS, momentaryMax(file, true), 0.5, file + ": громкость, LUFS");
        }
    }

    /**
     * Звук мода в мире — только по закону источника и пути ({@code Loudness}, {@code BlastMix}, {@code Sources}): ни одного
     * ванильного проигрывания с громкостью на глаз (громкость больше 1 у Minecraft — дальность 16 блоков на единицу). Так
     * 3.4.1 играл гул огня в воронке громкостью 5 до 90 блоков: хвост взрыва в 50 м — 135 дБА-эквивалента, громче
     * реактивного двигателя, хотя у пожара ~2 МВт там ~80 дБ. UI-звуки без места ({@code forUI}) — не в счёт.
     * Событие в переменной или своя обёртка этим поиском не ловятся; обёртки с ручной громкостью у клиента нет
     * ({@code ClientSounds.at} убран).
     */
    @Test
    void worldSoundsHaveNoHandVolume() throws IOException {
        Pattern hand = Pattern.compile("(playSound|playLocalSound|playSeededSound|new SimpleSoundInstance|SimpleSoundInstance\\.forAmbientAddition)\\(");
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                // по операторам, а не по строкам: вызов, разбитый на строки, — тоже
                for (String st : Files.readString(f).split(";")) {
                    assertTrue(!(st.contains("ModSounds.") && hand.matcher(st).find()), f + ": звук мода громкостью на глаз — " + st.strip());
                }
            }
        }
    }

    /** Наибольшая мгновенная громкость файла (BS.1770: K-фильтр, окно 400 мс через 100 мс), LUFS; петля — по кругу. */
    static double momentaryMax(String file, boolean loop) throws IOException {
        float[] x;
        int rate;
        try (InputStream in = open(file); JOrbisAudioStream ogg = new JOrbisAudioStream(in)) {
            rate = (int) ogg.getFormat().getSampleRate();
            FloatArrayList samples = new FloatArrayList();
            while (ogg.readChunk(samples::add)) {
                // весь файл
            }
            x = samples.toFloatArray();
        }
        int n = x.length, w = (int) (0.4 * rate), h = rate / 10;
        // петля: фильтр разогнан хвостом петли, окна идут через шов
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

    /** Число каналов из заголовка Vorbis: «\1vorbis», версия (4 байта), каналы (1 байт). */
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
