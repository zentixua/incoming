package ua.zentix.incoming.client.fx.layer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Кадры запекания ({@code tools/bake_fx/pack.py}: {@code fireball.json}, {@code puff6.json} и картинки) — на месте, того
 * размера и числа, что ждёт лист слоя ({@link FxAtlas}), и всё вместе не тяжелее предела в jar.
 */
class FxAtlasTest {
    private static final String DIR = "/assets/incoming/textures/fx/particle/";
    /** Кадры эффектов в jar — не больше, байт (задание V: всё запечённое вместе — до 8 МБ). */
    static final long JAR_BUDGET = 8L << 20;

    private static Reader open(String name) {
        InputStream in = FxAtlasTest.class.getResourceAsStream(DIR + name);
        assertNotNull(in, name);
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    private static JsonObject json(String name) throws IOException {
        try (Reader r = open(name)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    private static void image(String name, int size) throws IOException {
        try (InputStream in = FxAtlasTest.class.getResourceAsStream(DIR + name)) {
            assertNotNull(in, name);
            BufferedImage img = ImageIO.read(in);
            assertEquals(size, img.getWidth(), name);
            assertEquals(size, img.getHeight(), name);
        }
    }

    /**
     * Огненный шар: 48 кадров по 256 (32 — горение) и их векторы движения по 128, время горения растёт, остывая — холоднее,
     * огонь на экране гаснет к саже; кадр не уже прежнего (1,3 радиуса шара).
     */
    @Test
    void fireballFramesMatchTheBake() throws IOException {
        JsonObject o = json("fireball.json");
        int tile = o.get("tile").getAsInt();
        assertEquals(FxAtlas.FIREBALL_FRAMES, o.get("frames").getAsInt());
        assertEquals(FxAtlas.FIREBALL_BURN, o.get("burn_frames").getAsInt());
        assertEquals(FxAtlas.FIREBALL_SIZE, tile, "место кадра шара на листе — 256");
        assertEquals(FxAtlas.FIREBALL_FLOW, o.get("flow").getAsFloat(), 1e-6, "предел векторов движения — тот, что ждёт шейдер");
        assertEquals(FxAtlas.FIREBALL_FLOW_SIZE, o.get("flow_tile").getAsInt());
        for (int i = 0; i < FxAtlas.FIREBALL_FRAMES; i++) {
            image(String.format(Locale.ROOT, "fireball_%02d.png", i), tile);
            image(String.format(Locale.ROOT, "fireballv_%02d.png", i), FxAtlas.FIREBALL_FLOW_SIZE);
        }
        FxAtlas.Fireball f;
        try (Reader r = open("fireball.json")) {
            f = FxAtlas.parseFire(r);
        }
        // шар топлива светится весь с первого мига (CCPS): первый кадр — почти самый яркий; кадры — после экспозиции по огню
        // (pack.py film_exposure, подача), в мере экрана: ярче середины тонов, но не плоский белый диск (с ним складок и
        // сажи не видно — шар 3.6)
        int peak = 0;
        for (int i = 1; i < FxAtlas.FIREBALL_FRAMES; i++) if (f.fire()[i] > f.fire()[peak]) peak = i;
        assertTrue(f.fire()[0] >= 0.85 * f.fire()[peak], "вспышка — почти самый яркий кадр");
        // (кадры из рендера 512 по веществу — пик 0,345, круг 2 — 0,37, 3.6 — 0,87: ровный диск)
        assertTrue(f.fire()[peak] >= 0.33 && f.fire()[peak] <= 0.75, "огонь на экране — ярко, но со складками: " + f.fire()[peak]);
        assertTrue(peak < FxAtlas.FIREBALL_FRAMES / 4, "ярче всего — в начале горения: кадр " + peak);
        for (int i = 1; i < FxAtlas.FIREBALL_FRAMES; i++) {
            assertTrue(f.burn()[i] > f.burn()[i - 1], "время кадров растёт: " + i);
            if (i > peak) assertTrue(f.fire()[i] <= f.fire()[i - 1] + 0.02, "огонь гаснет: кадр " + i);
            if (f.burn()[i - 1] >= 1) assertTrue(f.kelvin()[i] <= f.kelvin()[i - 1], "остывая — холоднее: кадр " + i);
        }
        assertTrue(f.fire()[FxAtlas.FIREBALL_FRAMES - 1] < 0.01, "последний кадр — сажа");
        assertTrue(f.burn()[FxAtlas.FIREBALL_BURN - 1] >= 1 && f.burn()[FxAtlas.FIREBALL_BURN - 2] < 1, "горение — 32 кадра");
        for (int i = 0; i < FxAtlas.FIREBALL_FRAMES; i++) assertTrue(f.half()[i] >= 1.3 - 1e-3, "кадр не уже 1,3 радиуса шара: " + i);
        // кадр по состоянию шара: горит — по времени, остывает — по температуре, к точке Дрейпера — последние
        assertEquals(0, FxAtlas.fireballFrameOf(f, 0, 1484, 1484), 1e-9);
        assertTrue(FxAtlas.fireballFrameOf(f, 0.5, 1484, 1484) > 2, "середина горения — не вспышка");
        assertTrue(FxAtlas.fireballFrameOf(f, 2, 800, 1484) >= FxAtlas.FIREBALL_FRAMES - 3, "остывший — у сажи");
        // остывание идёт с конца горения сразу, без стоп-кадра: шар топлива (1484 K) и взрывчатки (1500 K) — по доле
        // остывания до точки Дрейпера, а не ждут, пока остынут до пламени симуляции в её конец
        for (double endK : new double[]{1484, 1500}) {
            double end = FxAtlas.fireballFrameOf(f, 1, endK, endK);
            assertEquals(FxAtlas.FIREBALL_BURN - 1, end, 0.05, "конец горения — последний кадр горения");
            double last = end;
            for (double k = endK - 20; k > 820 && last < FxAtlas.FIREBALL_FRAMES - 1; k -= 20) {
                double frame = FxAtlas.fireballFrameOf(f, 1.5, k, endK);
                assertTrue(frame > last, "остывая на 20 K, кадр идёт дальше: " + k + " K, кадр " + frame);
                assertTrue(frame - last < 2.5, "и без скачка через кадры: " + k + " K");
                last = frame;
            }
        }
    }

    /** Средняя яркость не ловит ровный диск: складки должны различаться в непрозрачном ядре; кайму отдельно проверяет fireballRimIsWarmAndNotGhostly. */
    @Test
    void burningFireballKeepsItsInteriorContrast() throws IOException {
        for (int i = 0; i < 10; i++) {
            try (InputStream in = FxAtlasTest.class.getResourceAsStream(DIR + String.format(Locale.ROOT, "fireball_%02d.png", i))) {
                assertNotNull(in);
                BufferedImage img = ImageIO.read(in);
                double[] light = new double[img.getWidth() * img.getHeight()];
                int count = 0;
                double middle = (img.getWidth() - 1) / 2.0, radius = 0.35 * img.getWidth();
                for (int y = 0; y < img.getHeight(); y++) {
                    for (int x = 0; x < img.getWidth(); x++) {
                        int rgba = img.getRGB(x, y);
                        if ((rgba >>> 24) <= 230 || Math.hypot(x - middle, y - middle) >= radius) continue;
                        light[count++] = (0.2126 * (rgba >> 16 & 255) + 0.7152 * (rgba >> 8 & 255) + 0.0722 * (rgba & 255)) / 255;
                    }
                }
                assertTrue(count > 100, "внутри шара есть непрозрачный огонь: кадр " + i);
                Arrays.sort(light, 0, count);
                // Разница в 15 % белого экрана: заметные складки, а не шум у почти срезанного белого (3.6: 4–11 %).
                double contrast = light[9 * count / 10] - light[count / 10];
                assertTrue(contrast > 0.15, "складки внутри огня видны: кадр " + i + ", контраст " + contrast);
            }
        }
    }

    /** Даже тонкое пламя остаётся тёплым: не жёлтая прозрачная плёнка, смешивающаяся с фоном в хаки. */
    @Test
    void fireballRimIsWarmAndNotGhostly() throws IOException {
        for (int i = 0; i < 10; i++) {
            try (InputStream in = FxAtlasTest.class.getResourceAsStream(DIR + String.format(Locale.ROOT, "fireball_%02d.png", i))) {
                assertNotNull(in);
                BufferedImage img = ImageIO.read(in);
                int orange = 0, hot = 0, white = 0;
                for (int y = 0; y < img.getHeight(); y++) {
                    for (int x = 0; x < img.getWidth(); x++) {
                        int rgba = img.getRGB(x, y);
                        double a = (rgba >>> 24) / 255.0, r = (rgba >> 16 & 255) / 255.0,
                                g = (rgba >> 8 & 255) / 255.0, b = (rgba & 255) / 255.0;
                        if (a < 0.05) continue;
                        double light = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                        assertTrue(a + 2.0 / 255 >= light, "яркий огонь не прозрачная плёнка: кадр " + i);
                        if (a < 0.6 && r > 0.3) assertTrue(g < 0.7 * r, "тонкое пламя краснеет: кадр " + i);
                        if (a > 0.6 && r > 0.5) {
                            hot++;
                            if (g < 0.75 * r) orange++;
                            if (b > 0.1 && r > 0.8) white++;
                        }
                    }
                }
                assertTrue(orange > hot / 2, "тело оранжевое, а не лимонное: кадр " + i);
                if (i < 4) assertTrue(white > 0, "в горячих кадрах есть светлое ядро: кадр " + i);
            }
        }
    }

    /**
     * Кадр вмещает всё вещество: у края кадра шара пусто (ревью #426: остывающая сажа обрезалась кругом по радиусу кадра,
     * и в игре висела «сфера-призрак» с ровным краем).
     */
    @Test
    void fireballFramesHoldAllTheirMatter() throws IOException {
        for (int i = 0; i < FxAtlas.FIREBALL_FRAMES; i++) {
            BufferedImage img = read(String.format(Locale.ROOT, "fireball_%02d.png", i));
            int w = img.getWidth(), h = img.getHeight(), edge = 0;
            for (int k = 0; k < w; k++) {
                for (int m : new int[]{0, 1, h - 2, h - 1}) edge = Math.max(edge, Math.max(img.getRGB(k, m) >>> 24, img.getRGB(m, k) >>> 24));
            }
            assertTrue(edge <= 4, "у края кадра " + i + " вещества нет: альфа " + edge);
        }
    }

    /**
     * Сажа остывания без зерна рендера (ревью #426: кадры остывания крапчатые). Причина — выборки: шумодав Cycles чистит
     * свет, но не непрозрачность прозрачной сажи, и при 32 выборках на пиксель и адаптивной выборке шум оставался в сажe;
     * увеличение кадра 1,3 радиуса шара из рендера 256 растягивало его в пятна. Рендер — не меньше 256 выборок без
     * адаптивной и вдвое крупнее кадра (кадр шара в горении — без увеличения, кадр остывания шире — пиксель его — среднее
     * нескольких пикселей рендера): так пишет {@code tools/bake_fx/pack.py} из {@code meta.json} запекания. Мера по
     * пикселям кадра зерно круга 2 не ловила: растянутый шум — пятна в 2–3 пикселя, а не разброс соседей.
     */
    @Test
    void coolingSootIsRenderedWithoutGrain() throws IOException {
        JsonObject o = json("fireball.json");
        assertTrue(o.has("samples") && o.get("samples").getAsInt() >= 256, "выборок на пиксель — не меньше 256");
        assertTrue(o.has("adaptive_sampling") && !o.get("adaptive_sampling").getAsBoolean(), "без адаптивной выборки");
        assertTrue(o.has("render") && o.get("render").getAsInt() >= 2 * o.get("tile").getAsInt(), "рендер вдвое крупнее кадра");
    }

    private static BufferedImage read(String name) throws IOException {
        try (InputStream in = FxAtlasTest.class.getResourceAsStream(DIR + name)) {
            assertNotNull(in, name);
            return ImageIO.read(in);
        }
    }

    /** Отражение не переворачивает верх; клетки 256² не пересекают карты дыма и друг друга. */
    @Test
    void fireballSlotsKeepTheTopAndTheirOwnSpace() {
        for (int i = 0; i < FxAtlas.FIREBALL_FRAMES; i++) {
            var a = FxAtlas.fireballAt(i, false);
            var b = FxAtlas.fireballAt(i, true);
            assertEquals(a.u0(), b.u1());
            assertEquals(a.u1(), b.u0());
            assertEquals(a.v0(), b.v0());
            assertEquals(a.v1(), b.v1());
            assertEquals(FxAtlas.FIREBALL_U, a.u1() - a.u0(), 1e-6);
            assertEquals(FxAtlas.FIREBALL_V, a.v1() - a.v0(), 1e-6);
            assertTrue(a.v0() >= FxAtlas.FIREBALL_TOP && a.v1() <= 1);
            assertTrue(FxAtlas.spray6(15, false).v1() + 2 * FxAtlas.PUFF6_STEP <= a.v0() + 1e-6);
            // векторы движения кадров — под кадрами, в своих клетках
            float fu = i % FxAtlas.FIREBALL_FLOW_COLUMNS * FxAtlas.FIREBALL_FLOW_U,
                    fv = FxAtlas.FIREBALL_FLOW_TOP + i / FxAtlas.FIREBALL_FLOW_COLUMNS * FxAtlas.FIREBALL_FLOW_V;
            assertTrue(fv >= FxAtlas.fireballAt(FxAtlas.FIREBALL_FRAMES - 1, false).v1() - 1e-6 && fv + FxAtlas.FIREBALL_FLOW_V <= 1 + 1e-6);
            assertTrue(fu + FxAtlas.FIREBALL_FLOW_U <= 1 + 1e-6);
            for (int j = 0; j < i; j++) {
                var c = FxAtlas.fireballAt(j, false);
                assertTrue(a.u0() >= c.u1() || a.u1() <= c.u0() || a.v0() >= c.v1() || a.v1() <= c.v0());
            }
        }
    }

    /** На границе кадров вес смеси доходит до следующего, без сброса яркости или непрозрачности. */
    @Test
    void fireballFrameBlendIsContinuous() {
        for (int i = 1; i < FxAtlas.FIREBALL_FRAMES; i++) {
            assertEquals(1, FxAtlas.fireballMix(i - 1e-5), 2e-5);
            assertEquals(0, FxAtlas.fireballMix(i), 0);
            assertEquals(FxAtlas.fireballAt(i, false), FxAtlas.fireballAt(i + 0.5, false));
        }
        assertEquals(0.5, FxAtlas.fireballMix(3.5), 1e-6);
        assertEquals(0, FxAtlas.fireballMix(FxAtlas.FIREBALL_FRAMES - 1));
        assertEquals(0, FxAtlas.fireballMix(FxAtlas.FIREBALL_FRAMES + 3));
        assertEquals(0, FxAtlas.fireballMix(-1));
    }

    /** Клуб: 16 кадров по три карты 128, рост края — с 1 и растёт, предел векторов движения — тот, что ждёт шейдер. */
    @Test
    void puffFramesMatchTheBake() throws IOException {
        JsonObject o = json("puff6.json");
        int tile = o.get("tile").getAsInt();
        assertEquals(FxAtlas.PUFF6_FRAMES, o.get("frames").getAsInt());
        assertEquals("abv", o.get("maps").getAsString());
        assertEquals(FxAtlas.PUFF6_FLOW, o.get("flow").getAsFloat(), 1e-6);
        assertEquals((float) tile / 1024, FxAtlas.PUFF6_U, 1e-6, "место кадра на листе — размер кадра запекания");
        assertEquals((float) tile / 6144, FxAtlas.PUFF6_V, 1e-6);
        for (int i = 0; i < FxAtlas.PUFF6_FRAMES; i++) {
            for (char m : "abv".toCharArray()) image(String.format(Locale.ROOT, "puff6%c_%02d.png", m, i), tile);
        }
        float[] growth;
        try (Reader r = open("puff6.json")) {
            growth = FxAtlas.parseGrowth(r);
        }
        assertEquals(1, growth[0], 1e-6);
        for (int i = 1; i < growth.length; i++) assertTrue(growth[i] >= growth[i - 1], "клуб растёт: кадр " + i);
        try (Reader r = open("puff6.json")) {
            float[] light = FxAtlas.parseLight(r);
            assertTrue(light[1] > 0 && light[2] > 0, "свет густого места спереди и средний — есть");
        }
    }

    /**
     * Облако берёт только клубящиеся кадры ({@link FxAtlas#turbulentLast}): у запечённого клуба к концу симуляции мелкая
     * структура уходит (рассеивается конечный клуб), и облака на 150–300 м были ровными размытыми шарами (шлагбаум RV2).
     * Последний кадр — с мелкой структурой не меньше 2/3 наибольшей; ровные кадры конца не берутся.
     */
    @Test
    void cloudsKeepTheirClumps() throws IOException {
        for (String name : new String[]{"puff6.json", "spray6.json"}) {
            float[] detail;
            try (Reader r = open(name)) {
                detail = FxAtlas.parseDetail(r);
            }
            assertNotNull(detail, name + ": мелкая структура кадров есть (tools/bake_fx/pack.py --detail)");
            int last = FxAtlas.turbulentLast(detail);
            float top = 0;
            for (float v : detail) top = Math.max(top, v);
            for (int i = 0; i <= last; i++) assertTrue(detail[i] >= FxAtlas.TURBULENT * top || i < 2, name + ": кадр " + i + " клубится");
            assertTrue(last < FxAtlas.PUFF6_FRAMES - 1 || detail[FxAtlas.PUFF6_FRAMES - 1] >= FxAtlas.TURBULENT * top, name);
        }
        float[] smoke;
        try (Reader r = open("puff6.json")) {
            smoke = FxAtlas.parseDetail(r);
        }
        assertTrue(FxAtlas.turbulentLast(smoke) <= 8, "у дыма рассеянные кадры конца не берутся");
        assertEquals(FxAtlas.PUFF6_FRAMES - 1, FxAtlas.turbulentLast(null), "нет данных — все кадры");
    }

    /** Клуб брызг: те же 16 кадров по три карты, что у дыма, в мере дыма (одна нормировка карт на все клубы слоя). */
    @Test
    void sprayFramesMatchTheBake() throws IOException {
        JsonObject o = json("spray6.json"), smoke = json("puff6.json");
        int tile = o.get("tile").getAsInt();
        assertEquals(FxAtlas.PUFF6_FRAMES, o.get("frames").getAsInt());
        assertEquals("abv", o.get("maps").getAsString());
        assertEquals(smoke.get("diffuse").getAsFloat(), o.get("diffuse").getAsFloat(), 1e-6, "карты брызг — в мере дыма");
        for (int i = 0; i < FxAtlas.PUFF6_FRAMES; i++) {
            for (char m : "abv".toCharArray()) image(String.format(Locale.ROOT, "spray6%c_%02d.png", m, i), tile);
        }
        try (Reader r = open("spray6.json")) {
            float[] growth = FxAtlas.parseGrowth(r);
            assertEquals(1, growth[0], 1e-6);
            for (int i = 1; i < growth.length; i++) assertTrue(growth[i] >= growth[i - 1], "клуб растёт: кадр " + i);
        }
    }

    /** Всё запечённое и нарисованное кодом для слоя эффектов в jar — не тяжелее {@link #JAR_BUDGET}. */
    @Test
    void framesFitTheJarBudget() throws IOException {
        Path dir = Path.of("src/main/resources" + DIR);
        long bytes;
        try (Stream<Path> files = Files.list(dir)) {
            bytes = files.mapToLong(p -> p.toFile().length()).sum();
        }
        assertTrue(bytes <= JAR_BUDGET, "кадры эффектов " + bytes / 1024 + " КБ");
    }
}
