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
        assertTrue(f.fire()[0] >= 0.5, "вспышка — ярко на экране: свет вокруг почти не ограничен");
        for (int i = 1; i < FxAtlas.FIREBALL_FRAMES; i++) {
            assertTrue(f.burn()[i] > f.burn()[i - 1], "время кадров растёт: " + i);
            assertTrue(f.fire()[i] <= f.fire()[i - 1] + 0.02, "огонь гаснет: кадр " + i);
            if (f.burn()[i - 1] >= 1) assertTrue(f.kelvin()[i] <= f.kelvin()[i - 1], "остывая — холоднее: кадр " + i);
        }
        assertTrue(f.fire()[FxAtlas.FIREBALL_FRAMES - 1] < 0.01, "последний кадр — сажа");
        // кадр по состоянию шара: горит — по времени, остывает — по температуре, к точке Дрейпера — последние
        assertEquals(0, FxAtlas.fireballFrameOf(f, 0, 1484), 1e-9);
        assertTrue(FxAtlas.fireballFrameOf(f, 0.5, 1484) > 2, "середина горения — не вспышка");
        assertTrue(FxAtlas.fireballFrameOf(f, 2, 800) >= FxAtlas.FIREBALL_FRAMES - 3, "остывший — у сажи");
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
