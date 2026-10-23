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

    /** Огненный шар: 16 кадров по 128, время горения растёт, остывая — холоднее, огонь на экране гаснет к саже. */
    @Test
    void fireballFramesMatchTheBake() throws IOException {
        JsonObject o = json("fireball.json");
        int tile = o.get("tile").getAsInt();
        assertEquals(FxAtlas.FIREBALL_FRAMES, o.get("frames").getAsInt());
        assertEquals(2 * 64, tile, "место кадра шара на листе — 128");
        for (int i = 0; i < FxAtlas.FIREBALL_FRAMES; i++) image(String.format(Locale.ROOT, "fireball_%02d.png", i), tile);
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
        assertTrue(f.fire()[peak] >= 0.35 && f.fire()[peak] <= 0.75, "огонь на экране — ярко, но со складками: " + f.fire()[peak]);
        assertTrue(peak < FxAtlas.FIREBALL_FRAMES / 4, "ярче всего — в начале горения: кадр " + peak);
        for (int i = 1; i < FxAtlas.FIREBALL_FRAMES; i++) {
            assertTrue(f.burn()[i] > f.burn()[i - 1], "время кадров растёт: " + i);
            if (i > peak) assertTrue(f.fire()[i] <= f.fire()[i - 1] + 0.02, "огонь гаснет: кадр " + i);
            if (f.burn()[i - 1] >= 1) assertTrue(f.kelvin()[i] <= f.kelvin()[i - 1], "остывая — холоднее: кадр " + i);
        }
        assertTrue(f.fire()[FxAtlas.FIREBALL_FRAMES - 1] < 0.01, "последний кадр — сажа");
        // кадр по состоянию шара: горит — по времени, остывает — по температуре, к точке Дрейпера — последние
        assertEquals(0, FxAtlas.fireballFrameOf(f, 0, 1484), 1e-9);
        assertTrue(FxAtlas.fireballFrameOf(f, 0.5, 1484) > 2, "середина горения — не вспышка");
        assertTrue(FxAtlas.fireballFrameOf(f, 2, 800) >= FxAtlas.FIREBALL_FRAMES - 3, "остывший — у сажи");
    }

    /** Средняя яркость не ловит ровный диск: складки должны различаться внутри, без прозрачной каймы. */
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

    /** Клуб: 16 кадров по три карты 128, рост края — с 1 и растёт, предел векторов движения — тот, что ждёт шейдер. */
    @Test
    void puffFramesMatchTheBake() throws IOException {
        JsonObject o = json("puff6.json");
        int tile = o.get("tile").getAsInt();
        assertEquals(FxAtlas.PUFF6_FRAMES, o.get("frames").getAsInt());
        assertEquals("abv", o.get("maps").getAsString());
        assertEquals(FxAtlas.PUFF6_FLOW, o.get("flow").getAsFloat(), 1e-6);
        assertEquals((float) tile / 1024, FxAtlas.PUFF6_U, 1e-6, "место кадра на листе — размер кадра запекания");
        assertEquals((float) tile / 2304, FxAtlas.PUFF6_V, 1e-6);
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
