package ua.zentix.incoming.client.fx.layer;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import ua.zentix.incoming.Incoming;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Все текстуры слоя эффектов на одном листе 1024×3328 с уменьшенными копиями (mip-уровнями): клубы дыма и пламени,
 * искра, вспышка, кольцо, кадры огненного шара (256×256), запечённый клуб дыма ({@link #puff6}: 16 кадров по три карты),
 * дальние круг, ореол и клубы. Один лист — одна текстура на весь отсортированный проход слоя
 * (частицы и дальнее вперемешку); уменьшенные копии — чтобы клуб в десяток пикселей вдали не рябил. Цвет на листе
 * умножен на непрозрачность, как и смешивание слоя.
 * <p>
 * Каждая картинка — в своём размере (клубы 64×64): частицы вблизи рисуются ближайшим пикселем, как из атласа Minecraft
 * ({@link FxQuads#nearestTexels}), и пиксель листа должен быть пикселем картинки. Копии — средние четырёх пикселей
 * с умноженной альфой: край тает без тёмной каймы. Не атлас Minecraft и не его {@code MipmapGenerator}: тот обнуляет
 * в копиях прозрачность ниже 96 из 255 (для листвы), а у клуба дыма она почти вся такая — с ним дым за стеной
 * в сотне блоков пропадал бы целиком. Места на листе постоянные и кратны 16 — копии соседних мест не смешиваются;
 * картинку другого размера (пакет ресурсов) лист подгоняет под место.
 */
public final class FxAtlas extends SimplePreparableReloadListener<NativeImage[]> {
    public static final FxAtlas INSTANCE = new FxAtlas();

    private static final int CELL = 64, COLS = 16, ROWS = 52, WIDTH = COLS * CELL, HEIGHT = ROWS * CELL;
    /** Кадров огненного шара ({@code fx/particle/fireball_00…15}, 256×256): от вспышки до чёрного дыма. */
    public static final int FIREBALL_FRAMES = 16;
    public static final int FIREBALL_SIZE = 4 * CELL;
    private static final int PUFF_SIZE = 2 * CELL, FIREBALL_ROW = 36;
    public static final int FIREBALL_COLUMNS = 4;
    public static final float FIREBALL_U = (float) FIREBALL_SIZE / WIDTH, FIREBALL_V = (float) FIREBALL_SIZE / HEIGHT,
            FIREBALL_TOP = (float) (FIREBALL_ROW * CELL) / HEIGHT;
    /** Уменьшенных копий: клуб 64 → 4 пикселя (все места кратны 16 — копии соседних мест не смешивают). */
    private static final int MIPS = 4;

    /** Место текстуры на листе (доли листа). */
    public record Sprite(float u0, float v0, float u1, float v1) {
        /** Середина по вертикали (лента — средняя строка ореола). */
        public float vMid() {
            return (v0 + v1) * 0.5f;
        }
    }

    private record Slot(ResourceLocation texture, int x, int y, int w, int h) {}

    private static final List<Slot> SLOTS = new ArrayList<>();
    private static final Sprite[] SMOKE = new Sprite[16], FIRE = new Sprite[8], PUFFS = new Sprite[8], FIREBALL = new Sprite[FIREBALL_FRAMES],
            FIREBALL_MIRRORED = new Sprite[FIREBALL_FRAMES];
    private static final Sprite RING, FLASH, GLOW, DISC, SPARK, FLAKE, PAPER;
    /**
     * Запечённый клуб дыма ({@code tools/bake_fx}: газ Mantaflow в Blender), {@value #PUFF6_FRAMES} кадров раскрытия, у кадра
     * три карты 128×128 одна под другой на {@link #PUFF6_STEP} листа: A — свет солнца справа, сверху, сзади (r, g, b) и
     * непрозрачность; B — слева, снизу, спереди и та же непрозрачность; V — смещение кадра к следующему (векторы движения,
     * r и g; ±{@link #PUFF6_FLOW} пикселей кадра). Кадр i — в ряду i / 8, столбце i % 8, ряды — с {@link #PUFF6_TOP}.
     */
    public static final int PUFF6_FRAMES = 16;
    /** Кадров в ряду, начало кадров на листе (доля высоты), шаг карт (доля высоты), размер кадра (доли листа). */
    public static final int PUFF6_COLUMNS = 8;
    public static final float PUFF6_TOP = (float) (8 * CELL) / HEIGHT, PUFF6_STEP = (float) (4 * CELL) / HEIGHT,
            PUFF6_U = (float) (2 * CELL) / WIDTH, PUFF6_V = (float) (2 * CELL) / HEIGHT;
    /** Наибольшее смещение карты V, пикселей кадра 128 (так её пишет {@code tools/bake_fx/pack.py}). */
    public static final float PUFF6_FLOW = 32;
    private static final Sprite[] PUFF6 = new Sprite[PUFF6_FRAMES], PUFF6_MIRRORED = new Sprite[PUFF6_FRAMES];
    /**
     * Прежний процедурный огненный шар ({@code fx/particle/ball_00…15}, {@code tools/gen_particles.py}: растёт и остывает в
     * своих кадрах по доле жизни) — у частиц шара ({@code Fx.fireball}: разрыв зенитной ракеты).
     */
    private static final Sprite[] BALL = new Sprite[FIREBALL_FRAMES];
    private static final ResourceLocation BALL_LIGHT = Incoming.id("textures/fx/particle/ball.json");
    private static volatile float[] ballShown = full();
    private static final ResourceLocation FIREBALL_LIGHT = Incoming.id("textures/fx/particle/fireball.json"),
            PUFF6_DATA = Incoming.id("textures/fx/particle/puff6.json");
    /** Кадры шара: огонь на экране ({@link #fireLightAt}), время в долях горения и температура; пока не прочитаны — запасные. */
    private static volatile Fireball fireball = Fireball.FALLBACK;
    /** Рост клуба в кадрах (во сколько раз край вещества больше, чем в первом); пока не прочитан — запасной. */
    private static volatile float[] puffGrowth = linear(1, 3);
    /**
     * Свет карт клуба ({@code puff6.json}): наибольшее смещение векторов движения (доля кадра), свет густого места
     * спереди и средний по шести сторонам (линейный) — к ним шейдер нормирует карты.
     */
    private static volatile float[] puffLight = {PUFF6_FLOW / 128f, 0.79f, 0.5f};

    /**
     * Кадры огненного шара ({@code tools/bake_fx/pack.py} → {@code fireball.json}): огонь кадра на экране (доля белого),
     * время кадра в долях горения симуляции и цветовая температура его пламени, K (у потухшего — 0).
     */
    record Fireball(float[] fire, float[] burn, float[] kelvin) {
        static final Fireball FALLBACK = new Fireball(full(), linear(0, 2), descending());
    }

    static {
        for (int i = 0; i < SMOKE.length; i++) SMOKE[i] = slot(String.format(Locale.ROOT, "fx/particle/smoke_%02d", i), i * CELL, 0, CELL, CELL);
        for (int i = 0; i < FIRE.length; i++) FIRE[i] = slot(String.format(Locale.ROOT, "fx/particle/fire_%02d", i), i * CELL, CELL, CELL, CELL);
        int x = FIRE.length * CELL;
        FLASH = slot("fx/particle/flash", x, CELL, CELL, CELL);
        GLOW = slot("far/glow", x + CELL, CELL, CELL, CELL);
        DISC = slot("far/disc", x + 2 * CELL, CELL, 32, 32);
        SPARK = slot("fx/particle/spark", x + 2 * CELL + 32, CELL, 16, 16);
        // пепел и клочок бумаги (ядерная пелена)
        FLAKE = slot("fx/particle/flake", x + 3 * CELL, CELL, 16, 16);
        PAPER = slot("fx/particle/paper", x + 3 * CELL + 16, CELL, 16, 16);
        // клубы дальнего дыма: 4×2 кадра на одной картинке
        Sprite puffs = slot("nuke/puffs", 0, 2 * CELL, 4 * CELL, 2 * CELL);
        float du = (puffs.u1 - puffs.u0) / 4, dv = (puffs.v1 - puffs.v0) / 2;
        for (int i = 0; i < PUFFS.length; i++) {
            float u = puffs.u0 + i % 4 * du, v = puffs.v0 + i / 4 * dv;
            PUFFS[i] = new Sprite(u, v, u + du, v + dv);
        }
        RING = slot("fx/particle/ring", 4 * CELL, 2 * CELL, 2 * CELL, 2 * CELL);
        // Огненный шар: четыре ряда по четыре кадра 256² ниже остальных карт; их размер и места не меняются.
        for (int i = 0; i < FIREBALL_FRAMES; i++) {
            FIREBALL[i] = slot(String.format(Locale.ROOT, "fx/particle/fireball_%02d", i), i % FIREBALL_COLUMNS * FIREBALL_SIZE,
                    FIREBALL_ROW * CELL + i / FIREBALL_COLUMNS * FIREBALL_SIZE, FIREBALL_SIZE, FIREBALL_SIZE);
            Sprite s = FIREBALL[i];
            FIREBALL_MIRRORED[i] = new Sprite(s.u1, s.v0, s.u0, s.v1);
        }
    }

    static {
        for (int i = 0; i < PUFF6_FRAMES; i++) {
            int x = i % PUFF6_COLUMNS * PUFF_SIZE, y = 8 * CELL + i / PUFF6_COLUMNS * PUFF_SIZE;
            PUFF6[i] = slot(String.format(Locale.ROOT, "fx/particle/puff6a_%02d", i), x, y, PUFF_SIZE, PUFF_SIZE);
            PUFF6_MIRRORED[i] = new Sprite(PUFF6[i].u1, PUFF6[i].v0, PUFF6[i].u0, PUFF6[i].v1);
            slot(String.format(Locale.ROOT, "fx/particle/puff6b_%02d", i), x, y + 4 * CELL, PUFF_SIZE, PUFF_SIZE);
            slot(String.format(Locale.ROOT, "fx/particle/puff6v_%02d", i), x, y + 8 * CELL, PUFF_SIZE, PUFF_SIZE);
        }
    }

    /**
     * Кадр i запечённого клуба (карта A, {@link #PUFF6_FRAMES}); {@code mirror} — отражён по горизонтали: правая и левая
     * карты меняются местами в шейдере ({@code fx.fsh} — по знаку производной u).
     */
    public static Sprite puff6(int i, boolean mirror) {
        return (mirror ? PUFF6_MIRRORED : PUFF6)[Math.clamp(i, 0, PUFF6_FRAMES - 1)];
    }

    /**
     * Запечённый клуб брызг ({@code tools/bake_fx}: тяжёлый газ капель, брошенный вверх, падает обратно; альбедо 0,99,
     * асимметрия капель 0,85) — те же 16 кадров по три карты и те же шаги листа, что у дыма, с ряда {@link #SPRAY6_ROW}:
     * шейдер шестистороннего света ({@code fx.fsh}: {@code sixFrames}) берёт их без своих чисел, карты — в мере дыма
     * ({@code pack.py}: «diffuse» дыма).
     */
    public static final int SPRAY6_ROW = 24;
    private static final Sprite[] SPRAY6 = new Sprite[PUFF6_FRAMES], SPRAY6_MIRRORED = new Sprite[PUFF6_FRAMES];
    private static final ResourceLocation SPRAY6_DATA = Incoming.id("textures/fx/particle/spray6.json");
    private static volatile float[] sprayGrowth = linear(1, 3);

    static {
        for (int i = 0; i < PUFF6_FRAMES; i++) {
            int x = i % PUFF6_COLUMNS * PUFF_SIZE, y = SPRAY6_ROW * CELL + i / PUFF6_COLUMNS * PUFF_SIZE;
            SPRAY6[i] = slot(String.format(Locale.ROOT, "fx/particle/spray6a_%02d", i), x, y, PUFF_SIZE, PUFF_SIZE);
            SPRAY6_MIRRORED[i] = new Sprite(SPRAY6[i].u1, SPRAY6[i].v0, SPRAY6[i].u0, SPRAY6[i].v1);
            slot(String.format(Locale.ROOT, "fx/particle/spray6b_%02d", i), x, y + 4 * CELL, PUFF_SIZE, PUFF_SIZE);
            slot(String.format(Locale.ROOT, "fx/particle/spray6v_%02d", i), x, y + 8 * CELL, PUFF_SIZE, PUFF_SIZE);
        }
    }

    /** Кадр i запечённого клуба брызг ({@link #puff6}). */
    public static Sprite spray6(int i, boolean mirror) {
        return (mirror ? SPRAY6_MIRRORED : SPRAY6)[Math.clamp(i, 0, PUFF6_FRAMES - 1)];
    }

    /** Кадр клуба брызг (с дробью), где край вещества вырос во столько раз с первого кадра, — до последнего клубящегося. */
    public static double sprayFrame(double growth) {
        return Math.min(at(sprayGrowth, growth, 0, PUFF6_FRAMES - 1), sprayLast);
    }

    /** Последний клубящийся кадр брызг ({@link #turbulentLast}). */
    private static volatile int sprayLast = PUFF6_FRAMES - 1;

    static {
        for (int i = 0; i < FIREBALL_FRAMES; i++) {
            BALL[i] = slot(String.format(Locale.ROOT, "fx/particle/ball_%02d", i), i % 8 * PUFF_SIZE, 20 * CELL + i / 8 * PUFF_SIZE,
                    PUFF_SIZE, PUFF_SIZE);
        }
    }

    /** Кадр прежнего шара ({@link #BALL}) на доле жизни f (0..1): шар растёт в кадре от 0,3 до 0,7 полуразмера. */
    public static Sprite ball(float f) {
        return BALL[Math.clamp((int) (f * FIREBALL_FRAMES), 0, FIREBALL_FRAMES - 1)];
    }

    /** Огонь прежнего шара на экране в кадре доли жизни f ({@code ball.json}). */
    public static float ballLight(float f) {
        return ballShown[Math.clamp((int) (f * FIREBALL_FRAMES), 0, FIREBALL_FRAMES - 1)];
    }

    private int id = -1;

    private FxAtlas() {}

    private static Sprite slot(String path, int x, int y, int w, int h) {
        SLOTS.add(new Slot(Incoming.id("textures/" + path + ".png"), x, y, w, h));
        return new Sprite((float) x / WIDTH, (float) y / HEIGHT, (float) (x + w) / WIDTH, (float) (y + h) / HEIGHT);
    }

    /** Клуб дыма: 4 клуба × 4 стадии рассеивания. */
    public static Sprite smoke(int i) {
        return SMOKE[i];
    }

    public static Sprite fire(int i) {
        return FIRE[i];
    }

    public static Sprite spark() {
        return SPARK;
    }

    /** Хлопья пепла. */
    public static Sprite flake() {
        return FLAKE;
    }

    /** Клочок обгоревшей бумаги. */
    public static Sprite paper() {
        return PAPER;
    }

    public static Sprite flash() {
        return FLASH;
    }

    public static Sprite ring() {
        return RING;
    }

    /**
     * Кадр огненного шара (с дробью) по состоянию шара модели: пока горит (burn — доля времени горения меньше 1) — кадр
     * того же времени горения симуляции; остывая — кадр той же цветовой температуры пламени (по убыванию после горения).
     */
    public static double fireballFrame(double burn, double kelvin) {
        return fireballFrameOf(fireball, burn, kelvin);
    }

    /** То же для своих кадров (юнит-тест). */
    static double fireballFrameOf(Fireball f, double burn, double kelvin) {
        int n = FIREBALL_FRAMES;
        // конец горения — кадр (с дробью), где время симуляции — её конец горения, и температура пламени там: остывая, кадр
        // идёт от него к первому кадру остывания той же температуры, без скачка
        double end = at(f.burn, 1, 0, n - 1);
        if (burn < 1) return Math.min(at(f.burn, burn, 0, n - 1), end);
        int from = (int) Math.floor(end);
        double k0 = at(f.kelvin, end);
        if (kelvin >= k0) return end;
        int next = from + 1;
        if (next > n - 1) return n - 1;
        while (next < n - 1 && f.kelvin[next] > kelvin) next++;
        if (next == from + 1) return end + (next - end) * (k0 - kelvin) / Math.max(k0 - f.kelvin[next], 1e-6);
        return next - 1 + (f.kelvin[next - 1] - kelvin) / Math.max(f.kelvin[next - 1] - f.kelvin[next], 1e-6);
    }

    /** Нижний кадр смеси: верх всегда вверх; разнообразие — только отражением по горизонтали. */
    public static Sprite fireballAt(double frame, boolean mirror) {
        return (mirror ? FIREBALL_MIRRORED : FIREBALL)[Math.clamp((int) frame, 0, FIREBALL_FRAMES - 1)];
    }

    /** Вес следующего кадра; последний кадр не смешивается с началом или соседним слотом листа. */
    public static float fireballMix(double frame) {
        double f = Math.clamp(frame, 0, FIREBALL_FRAMES - 1);
        return (float) (f - Math.floor(f));
    }

    /** Огонь шара на экране в кадре {@code frame} ({@link #fireLightAt}), между кадрами — плавно. */
    public static float fireLightAt(double frame) {
        return (float) at(fireball.fire, frame);
    }

    /**
     * Кадр запечённого клуба (с дробью), где край вещества вырос во столько раз с первого кадра, — не дальше последнего
     * клубящегося ({@link #turbulentLast}): дальше облако растёт, а кадр стоит.
     */
    public static double puffFrame(double growth) {
        return Math.min(at(puffGrowth, growth, 0, PUFF6_FRAMES - 1), puffLast);
    }

    /**
     * Мелкой структуры кадра (puff6.json «detail», {@code tools/bake_fx/pack.py}) у клубящегося — не меньше этой доли
     * её наибольшей (⚠ оценка: дальше кадры глазом — ровный размытый шар).
     */
    static final double TURBULENT = 2.0 / 3;

    /**
     * Последний клубящийся кадр: симуляция — конечный клуб, к концу он рассеивается и теряет мелкую структуру (у puff6 к
     * 15-му кадру — 0,42 наибольшей), а настоящее облако, пока растёт, остаётся турбулентным — термик подобен себе, клубы
     * растут вместе с ним (Scorer 1957; Morton, Taylor, Turner 1956). Облака на 150–300 м в 10–30 с брали поздние кадры по
     * росту — ровные размытые шары без клубов (шлагбаум RV2). Последний кадр после наибольшей структуры, где её ещё не
     * меньше {@link #TURBULENT}; нет данных — все кадры.
     */
    static int turbulentLast(float[] detail) {
        if (detail == null || detail.length == 0) return PUFF6_FRAMES - 1;
        int peak = 0;
        for (int i = 1; i < detail.length; i++) if (detail[i] > detail[peak]) peak = i;
        for (int i = peak + 1; i < detail.length; i++) if (detail[i] < TURBULENT * detail[peak]) return i - 1;
        return detail.length - 1;
    }

    /** Последний кадр клуба, что берёт {@link #puffFrame}. */
    private static volatile int puffLast = PUFF6_FRAMES - 1;

    /** Значение таблицы a в номере x (с дробью, за краями — край). */
    private static double at(float[] a, double x) {
        double c = Math.max(0, Math.min(a.length - 1, x));
        int i = Math.min(a.length - 2, (int) c);
        return a[i] + (a[i + 1] - a[i]) * (c - i);
    }

    /** Номер (с дробью) по возрастающей таблице a, где она равна v (за краями — lo или hi). */
    private static double at(float[] a, double v, int lo, int hi) {
        if (v <= a[0]) return lo;
        for (int i = 0; i < a.length - 1; i++) {
            if (v <= a[i + 1]) return i + (v - a[i]) / Math.max(a[i + 1] - a[i], 1e-9);
        }
        return hi;
    }

    /** Кадры шара из {@code fireball.json}: {@code {"fire": […], "burn": […], "kelvin": […]}} по 16 чисел. */
    static Fireball parseFire(Reader in) {
        var o = JsonParser.parseReader(in).getAsJsonObject();
        return new Fireball(numbers(o.getAsJsonArray("fire"), FIREBALL_FRAMES), numbers(o.getAsJsonArray("burn"), FIREBALL_FRAMES),
                numbers(o.getAsJsonArray("kelvin"), FIREBALL_FRAMES));
    }

    /** Мелкая структура кадров из {@code puff6.json}: {@code "detail"}; нет — null. */
    static float[] parseDetail(Reader in) {
        var o = JsonParser.parseReader(in).getAsJsonObject();
        return o.has("detail") ? numbers(o.getAsJsonArray("detail"), PUFF6_FRAMES) : null;
    }

    /** Рост клуба из {@code puff6.json}: {@code {"growth": [16 чисел]}}. */
    static float[] parseGrowth(Reader in) {
        return numbers(JsonParser.parseReader(in).getAsJsonObject().getAsJsonArray("growth"), PUFF6_FRAMES);
    }

    /** Свет карт клуба из {@code puff6.json}: {@code "flow"} (пикселей кадра {@code "tile"}), {@code "front"}, {@code "diffuse"}. */
    static float[] parseLight(Reader in) {
        var o = JsonParser.parseReader(in).getAsJsonObject();
        return new float[]{o.get("flow").getAsFloat() / o.get("tile").getAsFloat(), o.get("front").getAsFloat(), o.get("diffuse").getAsFloat()};
    }

    /** Свет карт клуба: смещение векторов движения (доля кадра), свет густого спереди и средний по сторонам (линейные). */
    public static float[] puff6Light() {
        return puffLight;
    }

    private static float[] numbers(JsonArray a, int n) {
        if (a == null || a.size() != n) throw new IllegalArgumentException("frames " + (a == null ? 0 : a.size()) + ", expected " + n);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = a.get(i).getAsFloat();
        return out;
    }

    private static float[] full() {
        float[] out = new float[FIREBALL_FRAMES];
        Arrays.fill(out, 1);
        return out;
    }

    /** 16 чисел от a до b. */
    private static float[] linear(float a, float b) {
        float[] out = new float[FIREBALL_FRAMES];
        for (int i = 0; i < out.length; i++) out[i] = a + (b - a) * i / (out.length - 1);
        return out;
    }

    /** Запасные температуры кадров: от шара топлива к точке Дрейпера. */
    private static float[] descending() {
        float[] out = linear(1500, 600);
        return out;
    }

    /** Кадр дальнего клуба (4×2, {@code nuke/puffs}). */
    public static Sprite puff(int i) {
        return PUFFS[i];
    }

    /** Сплошной круг с мягким краем (корпус, огненный шар, ядро вспышки вдали). */
    public static Sprite disc() {
        return DISC;
    }

    /** Ровная белая середина круга {@code far/disc} (вуаль облака вокруг глаза): два пикселя вокруг его середины. */
    public static Sprite white() {
        return WHITE;
    }

    private static final Sprite WHITE = new Sprite((DISC.u0 + DISC.u1) / 2 - 1f / WIDTH, (DISC.v0 + DISC.v1) / 2 - 1f / HEIGHT,
            (DISC.u0 + DISC.u1) / 2 + 1f / WIDTH, (DISC.v0 + DISC.v1) / 2 + 1f / HEIGHT);

    /** Гауссов ореол без ядра (блик, вуаль, зарево; лентой — шлейф). */
    public static Sprite glow() {
        return GLOW;
    }

    /** Текстура OpenGL листа; −1 — ещё не загружен. */
    public int id() {
        return id;
    }

    /** Фоновый поток: картинки на места со своими уменьшенными копиями, умноженная альфа. */
    @Override
    protected NativeImage[] prepare(ResourceManager resources, ProfilerFiller profiler) {
        int[][] sheets = new int[MIPS + 1][];
        for (int l = 0; l <= MIPS; l++) sheets[l] = new int[(WIDTH >> l) * (HEIGHT >> l)];
        for (Slot s : SLOTS) place(resources, s, sheets);
        fireball = read(resources, FIREBALL_LIGHT, FxAtlas::parseFire, Fireball.FALLBACK);
        puffGrowth = read(resources, PUFF6_DATA, FxAtlas::parseGrowth, linear(1, 3));
        puffLast = turbulentLast(read(resources, PUFF6_DATA, FxAtlas::parseDetail, null));
        sprayGrowth = read(resources, SPRAY6_DATA, FxAtlas::parseGrowth, linear(1, 3));
        sprayLast = turbulentLast(read(resources, SPRAY6_DATA, FxAtlas::parseDetail, null));
        ballShown = read(resources, BALL_LIGHT, r -> numbers(JsonParser.parseReader(r).getAsJsonObject().getAsJsonArray("fire"), FIREBALL_FRAMES),
                full());
        puffLight = read(resources, PUFF6_DATA, FxAtlas::parseLight, new float[]{PUFF6_FLOW / 128f, 0.79f, 0.5f});
        NativeImage[] levels = new NativeImage[MIPS + 1];
        for (int l = 0; l <= MIPS; l++) levels[l] = image(sheets[l], WIDTH >> l, HEIGHT >> l);
        return levels;
    }

    /** Поток отрисовки: в видеопамять (та же текстура при перезагрузке ресурсов). */
    @Override
    protected void apply(NativeImage[] levels, ResourceManager resources, ProfilerFiller profiler) {
        if (id < 0) id = TextureUtil.generateTextureId();
        TextureUtil.prepareImage(id, MIPS, WIDTH, HEIGHT);
        for (int l = 0; l < levels.length; l++) {
            NativeImage img = levels[l];
            img.upload(l, 0, 0, 0, 0, img.getWidth(), img.getHeight(), true, true, true, true);
        }
    }

    private static <T> T read(ResourceManager resources, ResourceLocation at, java.util.function.Function<Reader, T> parse, T fallback) {
        Optional<Resource> res = resources.getResource(at);
        if (res.isPresent()) {
            try (Reader in = new InputStreamReader(res.get().open(), StandardCharsets.UTF_8)) {
                return parse.apply(in);
            } catch (IOException | RuntimeException e) {
                Incoming.LOG.warn("{} could not be read: flipbook timing falls back to defaults", at, e);
                return fallback;
            }
        }
        Incoming.LOG.warn("{} not found: flipbook timing falls back to defaults", at);
        return fallback;
    }

    /** Картинка и её копии на свои места листа (цвет, умноженный на непрозрачность); другой размер — подгоняется. */
    private static void place(ResourceManager resources, Slot s, int[][] sheets) {
        Optional<Resource> res = resources.getResource(s.texture);
        if (res.isEmpty()) {
            Incoming.LOG.warn("Effect texture {} not found: its slot stays empty", s.texture);
            return;
        }
        int[] px;
        try (InputStream in = res.get().open(); NativeImage img = NativeImage.read(in)) {
            px = fit(img, s.w, s.h);
        } catch (IOException | RuntimeException e) {
            Incoming.LOG.warn("Effect texture {} could not be read: its slot stays empty", s.texture, e);
            return;
        }
        int w = s.w, h = s.h;
        for (int l = 0; ; l++) {
            put(sheets[l], WIDTH >> l, s, l, px);
            if (l == MIPS) break;
            px = half(px, w, h);
            w >>= 1;
            h >>= 1;
        }
    }

    /** Пиксели картинки (ABGR, цвет умножен на непрозрачность) в размере места: другой размер — билинейно. */
    private static int[] fit(NativeImage img, int w, int h) {
        int sw = img.getWidth(), sh = img.getHeight();
        int[] src = new int[sw * sh];
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) src[y * sw + x] = premultiply(img.getPixelRGBA(x, y));
        }
        if (sw == w && sh == h) return src;
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) out[y * w + x] = sample(src, sw, sh, (x + 0.5f) * sw / w - 0.5f, (y + 0.5f) * sh / h - 0.5f);
        }
        return out;
    }

    /** Копия уровня l места s на лист этого уровня (ширина листа — sheetW). */
    private static void put(int[] sheet, int sheetW, Slot s, int l, int[] px) {
        int w = Math.max(1, s.w >> l), h = Math.max(1, s.h >> l), x0 = s.x >> l, y0 = s.y >> l;
        for (int y = 0; y < h; y++) System.arraycopy(px, y * w, sheet, (y0 + y) * sheetW + x0, w);
    }

    /** ABGR → ABGR с цветом, умноженным на непрозрачность. */
    private static int premultiply(int abgr) {
        int a = abgr >>> 24;
        int r = (abgr & 0xFF) * a / 255, g = (abgr >> 8 & 0xFF) * a / 255, b = (abgr >> 16 & 0xFF) * a / 255;
        return a << 24 | b << 16 | g << 8 | r;
    }

    /** Значение между пикселями (билинейно, по каналам с умноженной альфой), за краем — край. */
    private static int sample(int[] src, int w, int h, float fx, float fy) {
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        float tx = fx - x0, ty = fy - y0;
        int c00 = at(src, w, h, x0, y0), c10 = at(src, w, h, x0 + 1, y0), c01 = at(src, w, h, x0, y0 + 1), c11 = at(src, w, h, x0 + 1, y0 + 1);
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            float v0 = (c00 >>> shift & 0xFF) * (1 - tx) + (c10 >>> shift & 0xFF) * tx;
            float v1 = (c01 >>> shift & 0xFF) * (1 - tx) + (c11 >>> shift & 0xFF) * tx;
            out |= Math.round(v0 * (1 - ty) + v1 * ty) << shift;
        }
        return out;
    }

    private static int at(int[] src, int w, int h, int x, int y) {
        return src[Math.clamp(y, 0, h - 1) * w + Math.clamp(x, 0, w - 1)];
    }

    /** Копия вдвое меньше: среднее четырёх пикселей по каждому каналу. */
    private static int[] half(int[] src, int w, int h) {
        int hw = w >> 1, hh = h >> 1;
        int[] out = new int[hw * hh];
        for (int y = 0; y < hh; y++) {
            for (int x = 0; x < hw; x++) {
                int i = 2 * y * w + 2 * x;
                int a = src[i], b = src[i + 1], c = src[i + w], d = src[i + w + 1], v = 0;
                for (int shift = 0; shift < 32; shift += 8) {
                    int sum = (a >>> shift & 0xFF) + (b >>> shift & 0xFF) + (c >>> shift & 0xFF) + (d >>> shift & 0xFF);
                    v |= (sum + 2) / 4 << shift;
                }
                out[y * hw + x] = v;
            }
        }
        return out;
    }

    private static NativeImage image(int[] px, int w, int h) {
        NativeImage img = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) img.setPixelRGBA(x, y, px[y * w + x]);
        }
        return img;
    }
}
