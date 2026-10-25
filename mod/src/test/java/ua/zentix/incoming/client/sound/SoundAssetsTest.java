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
        float[][] pcm = new float[1][];
        int rate = Envelope.read(open(ASSETS + "sounds/blast_fire.ogg"), pcm);
        float[] envelope = Envelope.of(pcm[0], rate);
        double peak = java.util.stream.IntStream.range(0, envelope.length).mapToDouble(i -> envelope[i]).max().orElseThrow();
        assertTrue(pcm[0].length >= 10 * rate, "a varied recording, not a short repeated grain");
        int window = rate / 5;
        for (int start : new int[]{0, pcm[0].length - window}) {
            double ms = 0;
            for (int i = start; i < start + window; i++) ms += pcm[0][i] * pcm[0][i];
            assertTrue(10 * Math.log10(ms / window) > -35, "audible at both sides of the loop seam");
        }
        assertTrue(Envelope.lufs(envelope[envelope.length - 1]) > Envelope.lufs(peak) - 12,
                "the model controls extinction; no five-second fade baked into the recording");
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
