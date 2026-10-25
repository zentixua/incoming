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
        assertEquals((float) tile / FxAtlas.HEIGHT, FxAtlas.PUFF6_V, 1e-6);
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
        // клуб дыма — шапка облака взрыва EmberGen (tools/bake_fx, puff-vdb): её кадры клубятся до конца, и облако берёт все
        assertEquals(FxAtlas.PUFF6_FRAMES - 1, FxAtlas.turbulentLast(smoke), "у шапки облака клубятся все кадры");
        assertEquals(FxAtlas.PUFF6_FRAMES - 1, FxAtlas.turbulentLast(null), "нет данных — все кадры");
    }

    /**
     * Клуб дыма — клубы, а не «вата»: в непрозрачном ядре каждого кадра (непрозрачность больше 0,6) свет сверху (карта A,
     * зелёный) отходит от своего сглаживания по 9×9 пикселей в среднем на 12 % и больше. Прежний клуб своей Mantaflow —
     * пушистый шар с ровными картами (0,01–0,08 в кадрах, что брали облака): облака в игре были ровными тёмными шарами
     * без светлых верхов клубов; шапка облака EmberGen сверху — 0,13–0,19.
     */
    @Test
    void puffFramesHaveBillowsInTheirLight() throws IOException {
        for (int f = 0; f < FxAtlas.PUFF6_FRAMES; f++) {
            String name = String.format(Locale.ROOT, "puff6a_%02d.png", f);
            try (InputStream in = FxAtlasTest.class.getResourceAsStream(DIR + name)) {
                assertNotNull(in, name);
                BufferedImage img = ImageIO.read(in);
                int n = img.getWidth();
                double[] top = new double[n * n];
                boolean[] core = new boolean[n * n];
                for (int y = 0; y < n; y++) {
                    for (int x = 0; x < n; x++) {
                        int argb = img.getRGB(x, y);
                        top[y * n + x] = Math.pow((argb >> 8 & 255) / 255.0, 2.2);
                        core[y * n + x] = (argb >>> 24) > 0.6 * 255;
                    }
                }
                double dev = 0, sum = 0;
                int count = 0;
                for (int y = 0; y < n; y++) {
                    for (int x = 0; x < n; x++) {
                        if (!core[y * n + x]) continue;
                        double blur = 0;
                        for (int dy = -4; dy <= 4; dy++) {
                            for (int dx = -4; dx <= 4; dx++) blur += top[Math.clamp(y + dy, 0, n - 1) * n + Math.clamp(x + dx, 0, n - 1)];
                        }
                        dev += Math.abs(top[y * n + x] - blur / 81);
                        sum += top[y * n + x];
                        count++;
                    }
                }
                assertTrue(count > 0.1 * n * n, name + ": у клуба есть непрозрачное ядро");
                double fine = dev / sum;
                assertTrue(fine >= 0.12, name + ": клубы в свете сверху " + fine);
            }
        }
    }

    /**
     * У кадров клубов облаков (дым и брызги) нет прямого края альфы: клуб кладётся в любом повороте — стоя, отражённым и
     * лёжа на земле (пыль у земли), и прямой край любого кадра где-нибудь встаёт стенкой (ревью #443: срез шапки облака
     * плоскостью при запекании дал прямой низ у кадров, и пыль налёта на 55 с легла отвесной стенкой). Мера — по настоящей
     * двумерной альфе ({@link #straightEdge}): силуэт по лучам из середины, и самый длинный его кусок, что лежит на прямой
     * (в пределах {@link #STRAIGHT_TOL} поперечника), — не больше {@link #STRAIGHT} поперечника. У клубящегося края — 0,11–0,29
     * (прежний клуб, брызги, шапка сверху), у срезанного плоскостью низа прежних кадров — до 0,46, у кадра с обрезанной
     * стороной — от 0,66.
     * Среднее по кольцам (как у профилей непрозрачности) направленную границу теряет. Огненный шар сюда не входит: низ
     * его кадра срезан у земли нарочно ({@code pack.py}: DOWN), шар стоит на ней и не ложится.
     */
    @Test
    void cloudFramesHaveNoStraightEdge() throws IOException {
        for (String prefix : new String[]{"puff6a", "spray6a", "eject6a", "crush6a", "air6a"}) {
            for (int f = 0; f < FxAtlas.PUFF6_FRAMES; f++) {
                String name = String.format(Locale.ROOT, "%s_%02d.png", prefix, f);
                double edge = straightEdge(alpha(name));
                assertTrue(edge <= STRAIGHT, name + ": прямой кусок силуэта на " + edge + " поперечника");
            }
        }
        // кадр с обрезанной стороной (треть справа — пусто) и со срезанным углом тест ловит
        double[][] side = alpha("puff6a_05.png"), corner = alpha("puff6a_09.png");
        int n = side.length;
        for (double[] row : side) Arrays.fill(row, n - n * 35 / 100, n, 0);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) if (x + y > 5 * n / 4) corner[y][x] = 0;
        }
        assertTrue(straightEdge(side) > STRAIGHT, "срезанная сторона: " + straightEdge(side));
        assertTrue(straightEdge(corner) > STRAIGHT, "срезанный угол: " + straightEdge(corner));
    }

    /**
     * Кадр клуба дыма — округлый: клуб — гаусс модели с осями квадрата (σh поперёк, σv вверх), и форму облака задаёт квадрат,
     * а не картинка. Шапка облака взрыва сверху вытянута (оси инерции альфы 1,4–1,7 : 1), и кадр, отражённый и лёжа на
     * земле, клал гаусс косой полосой с длинными ровными боками — у крупного клуба пыли у глаза налёт вставал стенкой
     * (ревью #443). Отношение осей инерции альфы каждого кадра — не больше {@link #PUFF_AXES} (прежний клуб — 1,0–1,2,
     * шапка сверху, сжатая при запекании вдоль большой оси, — 1,05–1,15).
     */
    @Test
    void puffFramesAreRound() throws IOException {
        for (int k = 0; k < 4 * FxAtlas.PUFF6_FRAMES; k++) {
            String name = String.format(Locale.ROOT, "%s6a_%02d.png", CLUB_SETS[k / FxAtlas.PUFF6_FRAMES], k % FxAtlas.PUFF6_FRAMES);
            double[][] a = alpha(name);
            double w = 0, cx = 0, cy = 0;
            for (int y = 0; y < a.length; y++) {
                for (int x = 0; x < a.length; x++) {
                    w += a[y][x];
                    cx += x * a[y][x];
                    cy += y * a[y][x];
                }
            }
            cx /= w;
            cy /= w;
            double xx = 0, yy = 0, xy = 0;
            for (int y = 0; y < a.length; y++) {
                for (int x = 0; x < a.length; x++) {
                    xx += (x - cx) * (x - cx) * a[y][x] / w;
                    yy += (y - cy) * (y - cy) * a[y][x] / w;
                    xy += (x - cx) * (y - cy) * a[y][x] / w;
                }
            }
            double mid = (xx + yy) / 2, diff = Math.sqrt((xx - yy) * (xx - yy) / 4 + xy * xy);
            double axes = Math.sqrt((mid + diff) / (mid - diff));
            assertTrue(axes <= PUFF_AXES, name + ": оси альфы " + axes);
        }
    }

    /** Наборы клубов, что кладутся гауссом модели: дым и клубы других готовых симуляций (выброс, дробление, воздух). */
    static final String[] CLUB_SETS = {"puff", "eject", "crush", "air"};

    /**
     * Клубы других готовых симуляций ({@code bake.py clubs-vdb}): те же 16 кадров по три карты 128, что у дыма, в мере дыма
     * (одна нормировка карт на все клубы слоя); рост с 1 не убывает; доля жизни — от 0 до 1 не убывает (кадр явления —
     * {@link FxAtlas#lifeFrame}); мелкая структура есть; места на листе — в шаге кадров шейдера от начала кадров дыма и не
     * налезают на соседей.
     */
    @Test
    void clubSetsMatchTheBake() throws IOException {
        JsonObject smoke = json("puff6.json");
        for (FxAtlas.Clubs c : new FxAtlas.Clubs[]{FxAtlas.EJECT, FxAtlas.CRUSH, FxAtlas.AIR}) {
            JsonObject o = json(c.prefix + "6.json");
            int tile = o.get("tile").getAsInt();
            assertEquals(FxAtlas.PUFF6_FRAMES, o.get("frames").getAsInt(), c.prefix);
            assertEquals("abv", o.get("maps").getAsString());
            assertEquals(FxAtlas.PUFF6_FLOW, o.get("flow").getAsFloat(), 1e-6, c.prefix + ": предел векторов движения — тот, что ждёт шейдер");
            assertEquals(smoke.get("diffuse").getAsFloat(), o.get("diffuse").getAsFloat(), 1e-6, c.prefix + ": карты — в мере дыма");
            for (int i = 0; i < FxAtlas.PUFF6_FRAMES; i++) {
                for (char m : "abv".toCharArray()) image(String.format(Locale.ROOT, "%s6%c_%02d.png", c.prefix, m, i), tile);
            }
            float[] growth, life, detail;
            try (Reader r = open(c.prefix + "6.json")) {
                growth = FxAtlas.parseGrowth(r);
            }
            try (Reader r = open(c.prefix + "6.json")) {
                life = FxAtlas.parseLife(r);
            }
            try (Reader r = open(c.prefix + "6.json")) {
                detail = FxAtlas.parseDetail(r);
            }
            assertEquals(1, growth[0], 1e-6);
            assertEquals(0, life[0], 1e-6);
            assertEquals(1, life[FxAtlas.PUFF6_FRAMES - 1], 1e-6);
            for (int i = 1; i < FxAtlas.PUFF6_FRAMES; i++) {
                assertTrue(growth[i] >= growth[i - 1], c.prefix + ": рост, кадр " + i);
                assertTrue(life[i] > life[i - 1], c.prefix + ": доля жизни, кадр " + i);
            }
            assertNotNull(detail, c.prefix);
            // место кадров: в шаге кадров шейдера (FxSix: начало кадров дыма + целое число кадров) и после прежних наборов
            float rows = (c.frames[0].v0() - FxAtlas.PUFF6_TOP) / FxAtlas.PUFF6_V;
            assertEquals(Math.round(rows), rows, 1e-3, c.prefix + ": кадр — в шаге кадров шейдера");
            assertTrue(c.row >= 96 && c.row + 12 <= FxAtlas.HEIGHT / 64, c.prefix + ": ниже прежних мест, внутри листа");
        }
        assertEquals(12, FxAtlas.CRUSH.row - FxAtlas.EJECT.row);
        assertEquals(12, FxAtlas.AIR.row - FxAtlas.CRUSH.row);
        // кадр явления по доле жизни — от первого кадра до последнего, без возврата
        double prev = -1;
        for (double f = 0; f <= 1.0001; f += 0.05) {
            double k = FxAtlas.lifeFrame(ua.zentix.incoming.client.fx.smoke.SmokeGrid.Look.EJECTA, f);
            assertTrue(k >= prev, "кадр по доле жизни не идёт назад");
            prev = k;
        }
    }

    /** Отношение осей инерции альфы кадра клуба — не больше ({@link #puffFramesAreRound}). */
    static final double PUFF_AXES = 1.25;

    /** Самый длинный прямой кусок силуэта — не больше этой доли поперечника ({@link #straightEdge}). */
    static final double STRAIGHT = 0.32;
    /** Прямой — в пределах этой доли поперечника от хорды. */
    static final double STRAIGHT_TOL = 0.012;
    /** Силуэт — тексели с непрозрачностью не меньше (у густого дыма видно и бледное). */
    static final double SILHOUETTE = 0.2;

    private static double[][] alpha(String name) throws IOException {
        try (InputStream in = FxAtlasTest.class.getResourceAsStream(DIR + name)) {
            assertNotNull(in, name);
            BufferedImage img = ImageIO.read(in);
            double[][] a = new double[img.getHeight()][img.getWidth()];
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) a[y][x] = (img.getRGB(x, y) >>> 24) / 255.0;
            }
            return a;
        }
    }

    /**
     * Самый длинный прямой кусок силуэта альфы, в долях его поперечника: 360 лучей из середины (центр масс альфы), на каждом —
     * дальний тексель не прозрачнее {@link #SILHOUETTE} (шаг — полтекселя); кусок — подряд идущие точки лучей, что все в
     * пределах {@link #STRAIGHT_TOL} поперечника от хорды между его концами; поперечник — два средних радиуса силуэта.
     */
    static double straightEdge(double[][] a) {
        int n = a.length, rays = 360;
        double w = 0, cx = 0, cy = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                w += a[y][x];
                cx += x * a[y][x];
                cy += y * a[y][x];
            }
        }
        cx /= w;
        cy /= w;
        double[] px = new double[rays], py = new double[rays];
        double mean = 0;
        for (int i = 0; i < rays; i++) {
            double t = 2 * Math.PI * i / rays, dx = Math.cos(t), dy = Math.sin(t), r = 0;
            for (double s = 0; s < n; s += 0.5) {
                double x = cx + s * dx, y = cy + s * dy;
                if (x < 0 || y < 0 || x > n - 1 || y > n - 1) break;
                if (a[(int) Math.round(y)][(int) Math.round(x)] >= SILHOUETTE) r = s;
            }
            px[i] = cx + r * dx;
            py[i] = cy + r * dy;
            mean += r / rays;
        }
        double diameter = 2 * mean, best = 0;
        for (int i = 0; i < rays; i++) {
            for (int len = 3; len < rays / 2; len++) {
                int j = (i + len) % rays;
                double ax = px[i], ay = py[i], bx = px[j] - ax, by = py[j] - ay, chord = Math.hypot(bx, by);
                if (chord < 1e-6) continue;
                boolean straight = true;
                for (int k = 1; k < len && straight; k++) {
                    int m = (i + k) % rays;
                    straight = Math.abs((px[m] - ax) * by - (py[m] - ay) * bx) / chord <= STRAIGHT_TOL * diameter;
                }
                if (!straight) break;
                best = Math.max(best, chord / diameter);
            }
        }
        return best;
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
