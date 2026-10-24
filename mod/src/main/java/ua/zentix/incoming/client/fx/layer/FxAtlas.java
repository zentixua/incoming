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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Все текстуры слоя эффектов на одном листе 1024×6144 с уменьшенными копиями (mip-уровнями): клубы дыма и пламени,
 * искра, вспышка, кольцо, кадры огненного шара (256×256) и их векторы движения (128×128), запечённый клуб дыма
 * ({@link #puff6}: 16 кадров по три карты),
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

    private static final int CELL = 64, COLS = 16, ROWS = 96;
    /** Размер листа, пикселей. */
    static final int WIDTH = COLS * CELL, HEIGHT = ROWS * CELL;
    /**
     * Кадров огненного шара ({@code fx/particle/fireball_00…47}, 256×256): {@value #FIREBALL_BURN} — горение, остальные —
     * остывание в сажу. Между кадрами — смесь вдоль векторов движения ({@code fireballv_00…47}): огонь течёт, а не
     * проступает двойным; 10 кадров на 2–3 с горения стояли силуэтом (ревью #426).
     */
    public static final int FIREBALL_FRAMES = 48, FIREBALL_BURN = 32;
    /** Прежний процедурный шар ({@link #ball}) и его таблица света — 16 кадров. */
    public static final int BALL_FRAMES = 16;
    public static final int FIREBALL_SIZE = 4 * CELL;
    private static final int PUFF_SIZE = 2 * CELL, FIREBALL_ROW = 36, FIREBALL_FLOW_ROW = FIREBALL_ROW + 12 * 4;
    public static final int FIREBALL_COLUMNS = 4;
    public static final float FIREBALL_U = (float) FIREBALL_SIZE / WIDTH, FIREBALL_V = (float) FIREBALL_SIZE / HEIGHT,
            FIREBALL_TOP = (float) (FIREBALL_ROW * CELL) / HEIGHT;
    /**
     * Векторы движения кадра шара к следующему: карта 128×128 ({@value #FIREBALL_FLOW_COLUMNS} в ряду с
     * {@link #FIREBALL_FLOW_TOP}), r и g — смещение ±{@link #FIREBALL_FLOW} пикселей кадра 256 (так пишет
     * {@code tools/bake_fx/pack.py}).
     */
    public static final int FIREBALL_FLOW_COLUMNS = 8, FIREBALL_FLOW_SIZE = 2 * CELL;
    public static final float FIREBALL_FLOW = 16, FIREBALL_FLOW_U = (float) FIREBALL_FLOW_SIZE / WIDTH,
            FIREBALL_FLOW_V = (float) FIREBALL_FLOW_SIZE / HEIGHT, FIREBALL_FLOW_TOP = (float) (FIREBALL_FLOW_ROW * CELL) / HEIGHT;
    /**
     * Уменьшенных копий: клуб 64 → 4 пикселя (все места кратны 16 — копии соседних мест не смешивают). Выборка у края места
     * отступает на полтекселя той копии, что читает (`frameInset` в {@code fx.fsh}, там же {@code FRAME_LEVELS}).
     */
    public static final int MIPS = 4;

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
    private static final Sprite[] BALL = new Sprite[BALL_FRAMES];
    private static final ResourceLocation BALL_LIGHT = Incoming.id("textures/fx/particle/ball.json");
    private static volatile float[] ballShown = linear(1, 1);
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
     * время кадра в долях горения симуляции, цветовая температура его пламени, K (у потухшего — 0), и полуразмер кадра в
     * радиусах шара (кадр вмещает всё вещество: остывающая сажа не обрезается кругом).
     */
    record Fireball(float[] fire, float[] burn, float[] kelvin, float[] half) {
        static final Fireball FALLBACK = new Fireball(linear(1, 1, FIREBALL_FRAMES), linear(0, 2, FIREBALL_FRAMES),
                linear(1500, 600, FIREBALL_FRAMES), linear(1.3f, 1.3f, FIREBALL_FRAMES));
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
        // Огненный шар: 12 рядов по четыре кадра 256² (по 4 клетки в высоту) ниже остальных карт, под ними — их векторы
        // движения 128², по 8 в ряду
        for (int i = 0; i < FIREBALL_FRAMES; i++) {
            FIREBALL[i] = slot(String.format(Locale.ROOT, "fx/particle/fireball_%02d", i), i % FIREBALL_COLUMNS * FIREBALL_SIZE,
                    FIREBALL_ROW * CELL + i / FIREBALL_COLUMNS * FIREBALL_SIZE, FIREBALL_SIZE, FIREBALL_SIZE);
            slot(String.format(Locale.ROOT, "fx/particle/fireballv_%02d", i), i % FIREBALL_FLOW_COLUMNS * FIREBALL_FLOW_SIZE,
                    FIREBALL_FLOW_ROW * CELL + i / FIREBALL_FLOW_COLUMNS * FIREBALL_FLOW_SIZE, FIREBALL_FLOW_SIZE, FIREBALL_FLOW_SIZE);
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
        for (int i = 0; i < BALL_FRAMES; i++) {
            BALL[i] = slot(String.format(Locale.ROOT, "fx/particle/ball_%02d", i), i % 8 * PUFF_SIZE, 20 * CELL + i / 8 * PUFF_SIZE,
                    PUFF_SIZE, PUFF_SIZE);
        }
    }

    /** Кадр прежнего шара ({@link #BALL}) на доле жизни f (0..1): шар растёт в кадре от 0,3 до 0,7 полуразмера. */
    public static Sprite ball(float f) {
        return BALL[Math.clamp((int) (f * BALL_FRAMES), 0, BALL_FRAMES - 1)];
    }

    /** Огонь прежнего шара на экране в кадре доли жизни f ({@code ball.json}). */
    public static float ballLight(float f) {
        return ballShown[Math.clamp((int) (f * BALL_FRAMES), 0, BALL_FRAMES - 1)];
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
     * того же времени горения симуляции; остывая — кадр той же доли остывания: температура шара модели от его конца
     * горения ({@code endKelvin}) к точке Дрейпера — как пламя кадров от конца горения симуляции к ней. По самой
     * температуре кадр стоял бы на конце горения, пока шар модели (1484–1500 K) не остынет до пламени симуляции в её конец
     * (~1360 K): у ракеты и бомбы — ~0,35 с стоп-кадра.
     */
    public static double fireballFrame(double burn, double kelvin, double endKelvin) {
        return fireballFrameOf(fireball, burn, kelvin, endKelvin);
    }

    /** То же для своих кадров (юнит-тест). */
    static double fireballFrameOf(Fireball f, double burn, double kelvin, double endKelvin) {
        int n = FIREBALL_FRAMES;
        // конец горения — кадр (с дробью), где время симуляции — её конец горения, и температура пламени там: остывая, кадр
        // идёт от него к кадрам остывания той же доли остывания, без скачка
        double end = at(f.burn, 1, 0, n - 1);
        if (burn < 1) return Math.min(at(f.burn, burn, 0, n - 1), end);
        int from = (int) Math.floor(end);
        double k0 = at(f.kelvin, end), draper = ua.zentix.incoming.client.fx.smoke.CloudModel.DRAPER_K;
        if (endKelvin > draper && k0 > draper) kelvin = draper + (kelvin - draper) * (k0 - draper) / (endKelvin - draper);
        if (kelvin >= k0) return end;
        int next = from + 1;
        if (next > n - 1) return n - 1;
        while (next < n - 1 && f.kelvin[next] > kelvin) next++;
        if (next == from + 1) return end + (next - end) * (k0 - kelvin) / Math.max(k0 - f.kelvin[next], 1e-6);
        return Math.min(n - 1, next - 1 + (f.kelvin[next - 1] - kelvin) / Math.max(f.kelvin[next - 1] - f.kelvin[next], 1e-6));
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

    /** Полуразмер кадра {@code frame} (с дробью) в радиусах шара: кадр вмещает всё вещество, без круга по краю. */
    public static double fireballHalf(double frame) {
        return at(fireball.half, frame);
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

    /**
     * Кадры шара из {@code fireball.json}: {@code "fire"}, {@code "burn"}, {@code "kelvin"}, {@code "half"} по
     * {@value #FIREBALL_FRAMES} чисел; предел векторов движения {@code "flow"} — тот, что ждёт шейдер.
     */
    static Fireball parseFire(Reader in) {
        var o = JsonParser.parseReader(in).getAsJsonObject();
        if (!o.has("flow") || o.get("flow").getAsFloat() != FIREBALL_FLOW) throw new IllegalArgumentException("fireball flow " + o.get("flow"));
        return new Fireball(numbers(o.getAsJsonArray("fire"), FIREBALL_FRAMES), numbers(o.getAsJsonArray("burn"), FIREBALL_FRAMES),
                numbers(o.getAsJsonArray("kelvin"), FIREBALL_FRAMES), numbers(o.getAsJsonArray("half"), FIREBALL_FRAMES));
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

    /** 16 чисел от a до b. */
    private static float[] linear(float a, float b) {
        return linear(a, b, BALL_FRAMES);
    }

    private static float[] linear(float a, float b, int n) {
        float[] out = new float[n];
        for (int i = 0; i < out.length; i++) out[i] = a + (b - a) * i / (out.length - 1);
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
        ballShown = read(resources, BALL_LIGHT, r -> numbers(JsonParser.parseReader(r).getAsJsonObject().getAsJsonArray("fire"), BALL_FRAMES),
                linear(1, 1));
        puffLight = read(resources, PUFF6_DATA, FxAtlas::parseLight, new float[]{PUFF6_FLOW / 128f, 0.79f, 0.5f});
        emission = emissionTable(sheets[0]);
        NativeImage[] levels = new NativeImage[MIPS + 1];
        for (int l = 0; l <= MIPS; l++) levels[l] = image(sheets[l], WIDTH >> l, HEIGHT >> l);
        return levels;
    }

    /**
     * Средние по месту листа для свечения мелкого светящегося ({@code FxQuads}: пятно вместо квадрата мельче пикселя прохода
     * свечения несёт его энергию): суммы по клеткам {@value #CELL4}×{@value #CELL4} пикселей с накоплением вдоль строки, на клетку
     * {@value #MEANS} чисел — у частиц и кадров огня (цвет с умноженной альфой) a·lin(rgb/a), у остального lin(rgb), и a;
     * lin — степень 2,2, как в {@code fx.fsh}.
     */
    private static volatile float[] emission;
    static final int CELL4 = 4, MEANS = 7;
    private static final int TW = WIDTH / CELL4, TH = HEIGHT / CELL4;

    private static float[] emissionTable(int[] base) {
        float[] lin = new float[256];
        for (int i = 0; i < 256; i++) lin[i] = (float) Math.pow(i / 255.0, 2.2);
        float[] cells = new float[TW * TH * MEANS];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int p = base[y * WIDTH + x], a = p >>> 24 & 255;
                if (a == 0) continue;
                int r = p & 255, g = p >> 8 & 255, b = p >> 16 & 255, c = ((y / CELL4) * TW + x / CELL4) * MEANS;
                float af = a / 255f;
                cells[c] += af * lin[Math.min(255, Math.round(255f * r / a))];
                cells[c + 1] += af * lin[Math.min(255, Math.round(255f * g / a))];
                cells[c + 2] += af * lin[Math.min(255, Math.round(255f * b / a))];
                cells[c + 3] += lin[r];
                cells[c + 4] += lin[g];
                cells[c + 5] += lin[b];
                cells[c + 6] += af;
            }
        }
        // накопление вдоль строки клеток: row[(y·(TW + 1) + x)·MEANS + k] — сумма клеток строки y левее x (по всему листу
        // float потерял бы точность: суммы до миллионов против клетки в единицы)
        float[] row = new float[TH * (TW + 1) * MEANS];
        for (int y = 0; y < TH; y++) {
            for (int x = 0; x < TW; x++) {
                int at = (y * (TW + 1) + x + 1) * MEANS;
                for (int k = 0; k < MEANS; k++) row[at + k] = row[at - MEANS + k] + cells[(y * TW + x) * MEANS + k];
            }
        }
        return row;
    }

    /** Средние для свечения по готовому листу base ({@link #emissionTable}) — без загрузки ресурсов (проверки). */
    static void emissionFrom(int[] base) {
        emission = emissionTable(base);
    }

    /**
     * Средние по месту (u0, v0)–(u1, v1) листа ({@link #emissionTable}): клетками по 4 пикселя, не меньше одной (строка ленты —
     * клеткой вокруг неё); в out — {@value #MEANS} чисел на пиксель места. Лист ещё не прочитан — false.
     */
    public static boolean means(float u0, float v0, float u1, float v1, float[] out) {
        float[] sat = emission;
        if (sat == null) return false;
        int x0 = Math.clamp((int) Math.floor(Math.min(u0, u1) * TW + 1e-3), 0, TW - 1), y0 = Math.clamp((int) Math.floor(Math.min(v0, v1) * TH + 1e-3), 0, TH - 1);
        int x1 = Math.clamp((int) Math.ceil(Math.max(u0, u1) * TW - 1e-3), x0 + 1, TW), y1 = Math.clamp((int) Math.ceil(Math.max(v0, v1) * TH - 1e-3), y0 + 1, TH);
        float area = (x1 - x0) * (y1 - y0) * (float) (CELL4 * CELL4);
        for (int k = 0; k < MEANS; k++) out[k] = 0;
        for (int y = y0; y < y1; y++) {
            for (int k = 0; k < MEANS; k++) out[k] += sat[(y * (TW + 1) + x1) * MEANS + k] - sat[(y * (TW + 1) + x0) * MEANS + k];
        }
        for (int k = 0; k < MEANS; k++) out[k] /= area;
        return true;
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
