package ua.zentix.incoming.client.far;

import net.minecraft.client.renderer.LightTexture;
import org.joml.Vector3f;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.layer.FxQuads;
import ua.zentix.incoming.client.fx.layer.SourceProbes;
import ua.zentix.incoming.client.render.FarDraw;
import ua.zentix.incoming.client.render.FarModels;
import ua.zentix.incoming.client.render.ProjectilePose;
import ua.zentix.incoming.client.render.WeaponModels;

import java.util.Arrays;

/**
 * Всё дальнее — квадратами в общий кадр слоя эффектов ({@link FxQuads}): шлейфы (лентой), клубы дыма, модели снарядов
 * (плитки атласа {@link FarModels}), огненные шары и пожары — кадрами анимации и клубами пламени, тела мельче
 * нескольких пикселей — круги (корпуса снарядов мельче модели, далёкие шары и факелы), свет — ореолы (блик и вуаль
 * вокруг вспышек, шаров и факелов, зарево). Слой сортирует их вместе с ближними частицами от
 * дальних к ближним по настоящему расстоянию, поэтому огненный шар и модель за ближним столбом дыма закрыты дымом,
 * а пусковая в десятке блоков — впереди дальних столбов.
 * Координаты — относительно камеры; дальше дальней плоскости точка переносится ближе с тем же угловым размером,
 * а слою передаётся, во сколько раз (по настоящему расстоянию он прячет дальнее за рельефом и LOD Distant Horizons).
 * <p>
 * Цвет вершины — свет, уже умноженный на непрозрачность, альфа — сколько закрыто того, что за спрайтом. Дым и корпус
 * закрывают и светят своим цветом, раскалённое закрывает и светит, ореол только светит (альфа 0 — сложение). Дымка
 * воздуха — в непрозрачности ({@link Sight}: L = L₀·t + небо·(1 − t)).
 * <p>
 * Текстуры ({@code tools/gen_particles.py}, на листе слоя): круг {@code far/disc} — сплошной, край сглажен; ореол
 * {@code far/glow} — гауссов, без ядра; ленты — его средняя строка; огненный шар — кадры {@code fx/particle/fireball},
 * пламя — {@code fx/particle/fire}. Сколько квадрата закрывает текстура, столько света она и несёт — множители
 * {@link #DISC}, {@link #GLOW}, {@link #FIREBALL}, {@link #FIRE} переводят радиус круга того же светового потока
 * в полуразмер квадрата ({@code FarSpritesTest}).
 */
public final class FarSprites implements SmokePuffs.Out {
    /**
     * Полуразмер квадрата на радиус круга того же потока: круг {@code far/disc} закрывает 0,632 квадрата (√(π/(4·0,632))),
     * ореол {@code far/glow} — 0,133 (пик в середине — 1).
     */
    public static final double DISC = 1.115, GLOW = 2.43;
    /**
     * Полуразмер кадра огненного шара на его радиус: светящийся шар в кадре — 0,72–0,79 полуразмера (к концу бугры
     * дыма шире); вблизи частицы берут тот же размер ({@code Explosions#fireball}). Клуб пламени закрывает 0,27 квадрата.
     */
    public static final double FIREBALL = 1.3, FIRE = 1.7;
    /**
     * Огненный шар — объём: его кадр стоит на столько радиусов ближе середины, у передней половины ({@link #front}).
     * Плоскостью через середину он сверху уходил бы ближней к глазу половиной в землю под собой (наземный шар — полусфера,
     * середина на 0,45 радиуса над землёй: с 54° срезало бы треть шара), а настоящий купол сверху виден целиком.
     */
    public static final double BALL_FRONT = 0.5;
    /** Тело мельче стольких пикселей (радиус) — круг: формы не видно, а свет точки верен; крупнее — кадр анимации. */
    private static final double SHAPE_FROM = 2, SHAPE_FULL = 4;
    /**
     * Блик и вуаль — свет, рассеянный в самом глазу: их квадрат переносится к глазу на столько блоков с тем же угловым
     * размером, и ближние рельеф и постройки его не режут (иначе у края загруженного мира ореол обрывался бы прямой
     * линией).
     * Закрыто ли само тело: в кадре — по тому, что на экране ({@link SourceProbes}: глубина Minecraft и LOD Distant
     * Horizons у самого источника), вне кадра — по лучам вызывающего (дальше прорисовки — по рельефу, в ней — по блокам).
     */
    static final double EYE = 0.25;
    /**
     * Ореол у глаза — в плоскости экрана на глубине {@link #EYE}·cos θ; сбоку (θ больше ~78°) это ближе ближней плоскости
     * Minecraft (0,05), и квадрат срезало бы целиком. Глубина — не меньше этой.
     */
    static final double EYE_DEPTH = 0.1;
    /** Ореол в воздухе ({@link #glow}) — не ближе стольких блоков по оси взгляда. */
    static final double AIR_NEAR = 1.5;
    /** Полуширина ленты на половину следа: середина {@code far/glow} поперёк закрывает 0,365 ширины. */
    public static final double RIBBON = 2.74;
    /** Мягкость края у рельефа: клуб — {@link FxQuads#SOFT} полуразмера, тело — четверть радиуса. */
    private static final float BODY_SOFT = 0.25f;

    /** Модели кадра в атласе. */
    final FarModels models = new FarModels();
    private final float[] tile = new float[FarModels.OUT];
    private FxQuads out;
    private SourceProbes probes;
    private double far;
    /** Куда смотрит камера (для глубины ореола у глаза). */
    private float fx, fy, fz;
    /** Сколько клубов, кругов, света, лент, моделей и вуалей вещества у глаза записано в этом кадре. */
    private final int[] counts = new int[6];

    /** Новый кадр: писать в out, яркие источники — в пробы видимости probes. */
    void begin(FarView view, FxQuads out, SourceProbes probes) {
        this.out = out;
        this.probes = probes;
        far = view.far();
        fx = view.forward().x();
        fy = view.forward().y();
        fz = view.forward().z();
        Arrays.fill(counts, 0);
        models.begin();
    }

    public boolean isEmpty() {
        return counts[0] + counts[1] + counts[2] + counts[3] + counts[4] + counts[5] == 0;
    }

    /** Сколько чего в кадре (клубы, круги, свет, ленты, модели) — в out, без выделения памяти. */
    void counts(int[] into) {
        System.arraycopy(counts, 0, into, 0, counts.length);
    }

    /**
     * Клуб дыма или пыли (кадр tex из 4×2 {@code nuke/puffs.png}) с центром (dx, dy, dz) от камеры: свой цвет
     * (r, g, b) с непрозрачностью a (уже с дымкой воздуха).
     */
    public void puff(double dx, double dy, double dz, double half, float rot, int tex, float r, float g, float b, float a) {
        if (billboard(dx, dy, dz, half, rot, FxAtlas.puff(tex), r * a, g * a, b * a, a, (float) half * FxQuads.SOFT)) counts[0]++;
    }

    /**
     * Запечённый клуб ({@link FxAtlas#puff6}: кадр sprite, смесь со следующим mix) с центром (dx, dy, dz) от камеры,
     * полуразмерами halfA вдоль экрана и halfB вверх: цвет (r, g, b) — вещество в свете неба у глаза, его свет по сторонам —
     * в шейдере по картам кадра, тень в картах — в степени shade ({@link SmokePuffs#shade}); непрозрачность a (уже с дымкой
     * воздуха). Без поворота: оси карт — оси экрана.
     */
    public void puff6(double dx, double dy, double dz, double halfA, double halfB, FxAtlas.Sprite sprite, float mix, float shade, float r, float g,
                      float b, float a) {
        if (a < 0.002f) return;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz), unfold = FarDraw.fold(d, far);
        out.sixWay(mix, shade);
        out.billboard((float) (dx * unfold), (float) (dy * unfold), (float) (dz * unfold), (float) d, (float) (halfA * unfold),
                (float) (halfB * unfold), 0, sprite, Math.min(1, r * a), Math.min(1, g * a), Math.min(1, b * a), Math.min(1, a),
                (float) Math.max(halfA, halfB) * FxQuads.SOFT, (float) unfold, LightTexture.FULL_BRIGHT, 0);
        out.sixWay(-1);
        counts[0]++;
    }

    /**
     * Запечённый клуб плоского гаусса (слой пыли у земли), лежащий на горизонтали ({@link FxQuads#flat}): полуразмеры
     * {@code halfA} и {@code halfB} — по горизонтали в мире; мягкость края у рельефа {@code soft} — путь луча в слое до
     * земли (у лежащего квадрата зазор до мира за ним одинаков по всей площади, и мягкость по полуразмеру гасила бы
     * слой целиком).
     */
    public void puff6Flat(double dx, double dy, double dz, double halfA, double halfB, double soft, FxAtlas.Sprite sprite, float mix, float shade,
                          float r, float g, float b, float a) {
        if (a < 0.002f) return;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz), unfold = FarDraw.fold(d, far);
        out.sixWay(mix, shade);
        out.flat((float) (dx * unfold), (float) (dy * unfold), (float) (dz * unfold), (float) d, (float) (halfA * unfold), (float) (halfB * unfold),
                sprite, Math.min(1, r * a), Math.min(1, g * a), Math.min(1, b * a), Math.min(1, a),
                (float) soft, (float) unfold, LightTexture.FULL_BRIGHT, 0);
        out.sixWay(-1);
        counts[0]++;
    }

    /**
     * Вуаль вещества вокруг глаза (облако, в которое вошла камера, {@link SmokePuffs}): ровный квадрат полуразмера half лицом
     * к камере в (dx, dy, dz) от неё — свой цвет (r, g, b) с непрозрачностью a, без мягкого края у мира (что ближе —
     * ближе квадрата и так).
     */
    public void veil(double dx, double dy, double dz, double half, float r, float g, float b, float a) {
        veil(dx, dy, dz, half, r, g, b, a, LightTexture.FULL_BRIGHT);
    }

    /** То же со светом мира {@code light} (упакованный, для карты освещения): вещество ленты следа освещает мир у глаза. */
    public void veil(double dx, double dy, double dz, double half, float r, float g, float b, float a, int light) {
        if (a < 0.002f) return;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        out.billboard((float) dx, (float) dy, (float) dz, (float) d, (float) half, 0, FxAtlas.white(), Math.min(1, r * a), Math.min(1, g * a),
                Math.min(1, b * a), Math.min(1, a), 0, 1, light, 0);
        counts[5]++;
    }

    /**
     * Модель снаряда в позе pose с центром (dx, dy, dz) от камеры: плитка атласа {@link FarModels} квадратом поперёк луча
     * на неё, закрывает и светит долю a (дымка воздуха, видимость над рельефом, переход от точки).
     *
     * @param size  размер модели ({@code FarLook.size})
     * @param camUp верх экрана: плитка повёрнута, как экран
     * @return false — модели нет (атлас полон, модель вплотную к камере): нужна точка
     */
    public boolean model(WeaponModels.Look look, ProjectilePose pose, double dx, double dy, double dz, double size, double pixel, Vector3f camUp,
                         float a) {
        if (!models.add(look, pose, dx, dy, dz, size, pixel, camUp, tile)) return false;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz), k = FarDraw.fold(d, far);
        float x = (float) (dx * k), y = (float) (dy * k), z = (float) (dz * k), h = (float) (tile[FarModels.HALF] * k);
        float rx = tile[FarModels.RIGHT] * h, ry = tile[FarModels.RIGHT + 1] * h, rz = tile[FarModels.RIGHT + 2] * h;
        float ux = tile[FarModels.UP] * h, uy = tile[FarModels.UP + 1] * h, uz = tile[FarModels.UP + 2] * h;
        float u0 = tile[FarModels.U0], v0 = tile[FarModels.V0], u1 = tile[FarModels.U1], v1 = tile[FarModels.V1];
        float c = Math.min(1, a), soft = tile[FarModels.HALF] * BODY_SOFT, unfold = (float) k;
        int light = LightTexture.FULL_BRIGHT;
        // нижний левый угол — (u0, v0) атласа: низ атласа — низ плитки
        out.modelTiles(true);
        out.quad((float) d);
        out.vertex(x - rx - ux, y - ry - uy, z - rz - uz, u0, v0, c, c, c, c, soft, unfold, light, 0);
        out.vertex(x + rx - ux, y + ry - uy, z + rz - uz, u1, v0, c, c, c, c, soft, unfold, light, 0);
        out.vertex(x + rx + ux, y + ry + uy, z + rz + uz, u1, v1, c, c, c, c, soft, unfold, light, 0);
        out.vertex(x - rx + ux, y - ry + uy, z - rz + uz, u0, v1, c, c, c, c, soft, unfold, light, 0);
        out.modelTiles(false);
        counts[4]++;
        return true;
    }

    /**
     * Круг радиуса radius (того же потока: квадрат — в {@link #DISC} раз больше) своего цвета с непрозрачностью a поверх
     * того, что за ним: корпус снаряда, огненный шар, ядро вспышки.
     */
    public void disc(double dx, double dy, double dz, double radius, float r, float g, float b, float a) {
        if (billboard(dx, dy, dz, radius * DISC, 0, FxAtlas.disc(), r * a, g * a, b * a, a, (float) radius * BODY_SOFT)) counts[1]++;
    }

    /**
     * Ореол в воздухе, который несёт свет круга радиуса radius яркостью a (пик в середине — a): складывается с тем, что
     * за ним — зарево из-за гребня и на облаках. Светится воздух глубиной deep перед (dx, dy, dz): квадрат стоит там, где
     * этот воздух начинается, — что ближе, его закрывает, а что внутри — закрывает плавно (мягкий край по расстоянию до
     * мира, с LOD DH). На месте источника квадрат резал бы прямо по себе постройки на том же расстоянии: ночью зарево
     * из-за гребня шириной в экран ложилось бы на дома пятнами и полосами, а на LOD DH, которых аппаратная глубина не
     * видит, — поверх. Блик и вуаль — в глазу ({@link #EYE}).
     */
    public void glow(double dx, double dy, double dz, double radius, double deep, float r, float g, float b, float a) {
        if (r * a + g * a + b * a < 0.006f) return;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double cos = (dx * fx + dy * fy + dz * fz) / Math.max(d, 1e-9);
        // не ближе, чем у камеры гаснет любой квадрат слоя (fx.fsh: полностью виден с 1,5 блока по оси взгляда)
        double front = Math.max(d - deep, AIR_NEAR / Math.max(cos, 0.1));
        out.glowShape(true);
        if (front >= d) {
            if (billboard(dx, dy, dz, radius * GLOW, 0, FxAtlas.glow(), r * a, g * a, b * a, 0, 0)) counts[2]++;
        } else {
            double unfold = FarDraw.fold(front, far), k = front / d * unfold;
            out.billboard((float) (dx * k), (float) (dy * k), (float) (dz * k), (float) d, (float) (radius * GLOW * k), 0, FxAtlas.glow(), Math.min(1, r * a),
                    Math.min(1, g * a), Math.min(1, b * a), 0, (float) (d - front), (float) unfold, LightTexture.FULL_BRIGHT, 0);
            counts[2]++;
        }
        out.glowShape(false);
    }

    /**
     * Яркое тело с центром (dx, dy, dz) от камеры радиуса radius, яркостью seen против белого экрана (с привыканием глаза
     * и воздухом), цвета (r, g, b) ({@link Sight#light}): ядро — свой свет поверх того, что за ним (пересвет — к белому,
     * тусклее белого — гаснет и тает: остывший шар уже стал дымом), блик и вуаль — ореолами того же цвета.
     *
     * @param t     доля света, дошедшая через воздух: насколько тело закрывает то, что за ним
     * @param w     доля ядра (видимое над рельефом, переход к ближней картинке)
     * @param glare доля блика и вуали: их глубина не режет, поэтому закрытое по лучам вызывающего — здесь (в кадре — ещё
     *              и проба {@link SourceProbes})
     * @param out   числа {@link Sight#light} (5) — для лога
     */
    public void light(double dx, double dy, double dz, double radius, double seen, double t, double pixel, float r, float g, float b, double w,
                      double glare, double[] out) {
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        Sight.light(radius, seen, d, pixel, out);
        core(dx, dy, dz, out, t, r, g, b, w);
        glare(dx, dy, dz, radius, out, seen, r, g, b, glare, 1);
    }

    /**
     * Яркое тело с формой, как {@link #light}: крупнее {@link #SHAPE_FULL} пикселей — картинкой полуразмера half (кадр
     * огненного шара, клуб пламени: цвет — в ней), мельче {@link #SHAPE_FROM} — кругом с тем же светом, между ними —
     * смесь. Тело и свет в глазу — каждый своей долей: вблизи тело рисуют частицы, а блик и вуаль — здесь.
     *
     * @param radius радиус светящегося тела сейчас (свет, блик, вуаль, точка), блоков
     * @param half   полуразмер картинки, блоков (растёт ли тело — в её кадрах)
     * @param body   доля тела (видимое над рельефом, переход к частицам, таяние)
     * @param glare  доля блика и вуали
     * @param shown  тело на экране в картинке (доля белого): блик и вуаль не ярче — остывший в сажу шар не слепит
     */
    public void shaped(double dx, double dy, double dz, double radius, FxAtlas.Sprite sprite, double half, float rot, double seen, double t, double pixel,
                       float r, float g, float b, double body, double glare, double shown, double[] out) {
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        Sight.light(radius, seen, d, pixel, out);
        double shape = smoothstep(SHAPE_FROM, SHAPE_FULL, radius / Math.max(d * pixel, 1e-12));
        if (shape < 1) core(dx, dy, dz, out, t, r, g, b, body * (1 - shape));
        float a = (float) (t * body * shape);
        if (shape > 0 && billboard(dx, dy, dz, half, rot, sprite, a, a, a, a, (float) (radius * BODY_SOFT), BALL_FRONT * radius)) counts[1]++;
        glare(dx, dy, dz, radius, out, seen, r, g, b, glare, shown);
    }

    /**
     * Блик и вуаль яркого тела ({@link #light}) без самого тела: тело рисует объём (огненный шар ядерного удара —
     * в проходе пелены, {@code fx_pall}), свет в глазу — здесь, с пробой видимости тела ({@link SourceProbes}: мир,
     * пелена и стена пыли, грибы перед ним).
     *
     * @param seen  яркость тела против белого экрана (с привыканием глаза и воздухом)
     * @param shown тело на экране (доля белого): блик и вуаль не ярче
     * @param out   числа {@link Sight#light} (5)
     */
    public void eyeLight(double dx, double dy, double dz, double radius, double seen, double pixel, float r, float g, float b, double shown,
                         double[] out) {
        Sight.light(radius, seen, Math.sqrt(dx * dx + dy * dy + dz * dz), pixel, out);
        glare(dx, dy, dz, radius, out, seen, r, g, b, 1, shown);
    }

    /**
     * Ядро {@link #light} кругом: свой свет поверх того, что за ним; пересвет — к белому, тусклее белого — темнее в линейном
     * свете и на экран в sRGB ({@link Sight#onScreen}, как струя и языки пламени вблизи: на 0,8 прорисовки без скачка) и тает.
     */
    private void core(double dx, double dy, double dz, double[] o, double t, float r, float g, float b, double w) {
        double s = o[2];
        float[] c = Sight.onScreen(r, g, b, s, (float) w);
        float a = (float) (o[1] * t * Math.min(1, 4 * s) * w);
        if (billboard(dx, dy, dz, o[0] * DISC, 0, FxAtlas.disc(), c[0], c[1], c[2], a, (float) (o[0] * BODY_SOFT))) counts[1]++;
    }

    /**
     * Блик и вуаль {@link #light} тела радиуса radius ореолами его цвета у самого глаза ({@link #EYE}), не ярче тела на
     * экране shown: доля w — сколько тела открыто по лучам вызывающего, в кадре — ещё и проба тела ({@link SourceProbes}).
     */
    private void glare(double dx, double dy, double dz, double radius, double[] o, double seen, float r, float g, float b, double w, double shown) {
        if (w <= 0) return;
        int probe = probes.add(dx, dy, dz, radius);
        eye(dx, dy, dz, o[3], r, g, b, (float) (Math.min(Math.min(Sight.HALO, Sight.SCATTER * seen), shown) * w), probe);
        if (o[4] > 0) eye(dx, dy, dz, o[4], r, g, b, (float) (Math.min(Sight.VEIL_PEAK, shown) * w), probe);
    }

    /**
     * Ореол {@link #glow}, перенесённый к глазу: те же лучи от глаза, сортировка — по настоящему расстоянию; глубина —
     * не меньше {@link #EYE_DEPTH} (сбоку — дальше от глаза); виден, сколько видно источника с пробой probe.
     */
    private void eye(double dx, double dy, double dz, double radius, float r, float g, float b, float a, int probe) {
        if (r * a + g * a + b * a < 0.006f) return;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double cos = (dx * fx + dy * fy + dz * fz) / Math.max(d, 1e-9);
        double k = Math.max(EYE, EYE_DEPTH / Math.max(cos, 1e-3)) / Math.max(d, 1e-9);
        if (k >= 1) {
            glow(dx, dy, dz, radius, 0, r, g, b, a);
            return;
        }
        out.seenBy(probe);
        out.glowShape(true);
        out.billboard((float) (dx * k), (float) (dy * k), (float) (dz * k), (float) d, (float) (radius * GLOW * k), 0, FxAtlas.glow(), Math.min(1, r * a),
                Math.min(1, g * a), Math.min(1, b * a), 0, 0, (float) k, LightTexture.FULL_BRIGHT, 0);
        out.glowShape(false);
        out.seenBy(0);
        counts[2]++;
    }

    private static double smoothstep(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    /**
     * Отрезок шлейфа лентой лицом к камере: от (ax..) до (bx..), полуширины (уже с {@link #RIBBON}), непрозрачности и
     * свет мира концов (упакованный, для карты освещения), свой цвет. Поперёк ленты — перпендикуляр к отрезку и к лучу
     * зрения (на любую его точку — тот же); joint на входе — поперёк у конца a, общий с прошлым отрезком той же ленты
     * (NaN в [0] — нет: по этому отрезку), на выходе — поперёк у конца b.
     * <p>
     * Смотришь вдоль отрезка — трубка дыма видна с торца: на экране она короче своей ширины, и лента вырождается —
     * перпендикуляр к почти параллельным отрезку и лучу случаен, соседние отрезки ложатся веером полос с прямым краем
     * (у сопла их ещё и освещает факел). Видно же сечение трубки — круг её ширины, а толща вдоль взгляда — длина отрезка
     * против ширины. Короче ширины на экране отрезок рисуется кругом (ореол того же следа) этой толщи, длиннее —
     * лентой, между — плавно ({@link #endOn}).
     *
     * @return отрезок записан лентой (joint — новый)
     */
    public boolean ribbon(double ax, double ay, double az, double halfA, float alphaA, int lightA, double bx, double by, double bz, double halfB,
                          float alphaB, int lightB, float r, float g, float b, float[] joint) {
        if (alphaA < 0.002f && alphaB < 0.002f) return false;
        double da = Math.sqrt(ax * ax + ay * ay + az * az), db = Math.sqrt(bx * bx + by * by + bz * bz);
        double ka = FarDraw.fold(da, far), kb = FarDraw.fold(db, far);
        float x0 = (float) (ax * ka), y0 = (float) (ay * ka), z0 = (float) (az * ka);
        float x1 = (float) (bx * kb), y1 = (float) (by * kb), z1 = (float) (bz * kb);
        float tx = x1 - x0, ty = y1 - y0, tz = z1 - z0;
        float mx = (x0 + x1) * 0.5f, my = (y0 + y1) * 0.5f, mz = (z0 + z1) * 0.5f;
        float nx = ty * mz - tz * my, ny = tz * mx - tx * mz, nz = tx * my - ty * mx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        // длина отрезка на экране (поперёк луча к его середине) против его ширины: торцом — круг
        double seg = Math.sqrt(tx * tx + ty * ty + tz * tz), across = len / Math.max(Math.sqrt(mx * mx + my * my + mz * mz), 1e-9);
        // глаз в самой трубке (ближе полуширины к оси отрезка, с полуширины — плавно): лента — плоскость через глаз; её
        // вещество вокруг глаза — вуалью: толща по взгляду — путь в трубке (длина отрезка или ширина на синус угла к нему)
        // в ширинах
        double hw = 0.5 * (halfA * ka + halfB * kb) / RIBBON, gap = distance(x0, y0, z0, tx, ty, tz);
        double inside = 1 - smoothstep(0.5 * hw, hw, gap);
        if (inside > 0) {
            double sin = Math.sqrt(Math.max(0, 1 - sq((tx * fx + ty * fy + tz * fz) / Math.max(seg, 1e-9))));
            double path = Math.min(seg, 2 * hw / Math.max(sin, 0.1));
            double a = 0.5 * (Math.min(1, alphaA) + Math.min(1, alphaB));
            // толща — путь этого отрезка в ширинах, без пола: у соседних отрезков пути складываются в настоящий путь взгляда
            float k = (float) (inside * (1 - Math.pow(1 - Math.min(a, 0.999), path / (2 * Math.max(hw, 1e-6)))));
            if (k > 0.004f) veil(fx * VEIL_AT, fy * VEIL_AT, fz * VEIL_AT, VEIL_AT * 4, Math.min(1, r), Math.min(1, g), Math.min(1, b), k, lightA);
            alphaA *= (float) (1 - inside);
            alphaB *= (float) (1 - inside);
            if (alphaA < 0.002f && alphaB < 0.002f) return false;
        }
        // торцом — только когда отрезок и луч почти параллельны (sin до 0,35, к 0,5 — плавно к ленте): сбоку короткий отрезок
        // широкого старого следа — лента, иначе диски соседних отрезков ложились бы друг на друга и густили след
        double tilt = Math.max(0, Math.min(1, (across / Math.max(seg, 1e-9) - 0.35) / 0.15));
        double side = Math.max(endOn(across, 0.5 * (halfA * ka + halfB * kb) / RIBBON), tilt * tilt * (3 - 2 * tilt));
        if (side < 1) {
            // торец трубки: круг её ширины, толща вдоль взгляда — длина отрезка в ширинах (непрозрачность ленты — поперёк)
            double halfW = 0.5 * (halfA * ka + halfB * kb) / RIBBON, a = 0.5 * (Math.min(1, alphaA) + Math.min(1, alphaB));
            double along = 1 - Math.pow(1 - Math.min(a, 0.999), seg / (2 * Math.max(halfW, 1e-6)));
            float k = (float) ((1 - side) * along);
            out.glowShape(true);
            // мягкость края у мира — в настоящих блоках, как у всех квадратов (halfW — в перенесённых ближе)
            out.billboard(mx, my, mz, (float) (0.5 * (da + db)), (float) (halfW * GLOW), 0, FxAtlas.glow(), Math.min(1, r) * k, Math.min(1, g) * k,
                    Math.min(1, b) * k, k, (float) (0.5 * (halfA + halfB) / RIBBON), (float) (0.5 * (ka + kb)), lightA, 0);
            out.glowShape(false);
            counts[3]++;
            if (side <= 0 || len < 1e-6f) {
                joint[0] = Float.NaN;
                return false;
            }
            alphaA *= (float) side;
            alphaB *= (float) side;
        }
        if (len < 1e-6f) return false;
        nx /= len;
        ny /= len;
        nz /= len;
        float px = nx, py = ny, pz = nz;
        if (!Float.isNaN(joint[0]) && joint[0] * nx + joint[1] * ny + joint[2] * nz > 0) {
            px = joint[0];
            py = joint[1];
            pz = joint[2];
        }
        float ha = (float) (halfA * ka), hb = (float) (halfB * kb);
        float aa = Math.min(1, alphaA), ab = Math.min(1, alphaB), cr = Math.min(1, r), cg = Math.min(1, g), cb = Math.min(1, b);
        float soft = (float) Math.max(halfA, halfB), ua = (float) ka, ub = (float) kb;
        FxAtlas.Sprite s = FxAtlas.glow();
        float v = s.vMid();
        out.glowShape(true);
        out.quad((float) Math.max(da, db));
        out.vertex(x0 - px * ha, y0 - py * ha, z0 - pz * ha, s.u0(), v, cr * aa, cg * aa, cb * aa, aa, soft, ua, lightA, 0);
        out.vertex(x0 + px * ha, y0 + py * ha, z0 + pz * ha, s.u1(), v, cr * aa, cg * aa, cb * aa, aa, soft, ua, lightA, 0);
        out.vertex(x1 + nx * hb, y1 + ny * hb, z1 + nz * hb, s.u1(), v, cr * ab, cg * ab, cb * ab, ab, soft, ub, lightB, 0);
        out.vertex(x1 - nx * hb, y1 - ny * hb, z1 - nz * hb, s.u0(), v, cr * ab, cg * ab, cb * ab, ab, soft, ub, lightB, 0);
        out.glowShape(false);
        counts[3]++;
        joint[0] = nx;
        joint[1] = ny;
        joint[2] = nz;
        return true;
    }

    /**
     * Светящаяся полоса — струя факела вблизи ({@code PlumeRenderer}): углы a, b — у одного конца, c, d — у другого (от
     * камеры), свет этих концов (на экране, с пересветом к белому, умноженный на долю). Поперёк — профиль ореола
     * {@code far/glow} (мягкий край, как у смешения на кромке струи), квадрат шире в {@link #STRIP_CROSS} раза: свет поперёк
     * тот же, что у ровной полосы этой ширины (с кругом {@code far/disc} край был жёстким, кадры F6). Только светит (альфа 0):
     * что за ней, остаётся видно. Сортируется на ahead блоков ближе своего ближнего угла: клубы своего следа у оси струи
     * (до ~3,7σ₀ от неё) ложатся под огонь, а не между его кусками.
     */
    public void glowStrip(Vector3f a, Vector3f b, Vector3f c, Vector3f d, float r0, float g0, float b0, float r1, float g1, float b1, float soft,
                          float ahead) {
        if (r0 + g0 + b0 < 0.006f && r1 + g1 + b1 < 0.006f) return;
        widen(a, b);
        widen(d, c);
        double da = a.length(), db = b.length(), dc = c.length(), dd = d.length();
        float ka = (float) FarDraw.fold(da, far), kb = (float) FarDraw.fold(db, far), kc = (float) FarDraw.fold(dc, far), kd = (float) FarDraw.fold(dd, far);
        FxAtlas.Sprite s = FxAtlas.glow();
        float v = s.vMid();
        r0 = Math.min(1, r0);
        g0 = Math.min(1, g0);
        b0 = Math.min(1, b0);
        r1 = Math.min(1, r1);
        g1 = Math.min(1, g1);
        b1 = Math.min(1, b1);
        out.glowShape(true);
        out.quad((float) Math.max(1e-3, Math.min(Math.min(da, db), Math.min(dc, dd)) - ahead));
        out.vertex(a.x * ka, a.y * ka, a.z * ka, s.u0(), v, r0, g0, b0, 0, soft, ka, LightTexture.FULL_BRIGHT, 0);
        out.vertex(b.x * kb, b.y * kb, b.z * kb, s.u1(), v, r0, g0, b0, 0, soft, kb, LightTexture.FULL_BRIGHT, 0);
        out.vertex(c.x * kc, c.y * kc, c.z * kc, s.u1(), v, r1, g1, b1, 0, soft, kc, LightTexture.FULL_BRIGHT, 0);
        out.vertex(d.x * kd, d.y * kd, d.z * kd, s.u0(), v, r1, g1, b1, 0, soft, kd, LightTexture.FULL_BRIGHT, 0);
        out.glowShape(false);
        counts[2]++;
    }

    /**
     * Во сколько раз полоса {@code far/glow} шире ровной того же света поперёк: середина круга {@code far/disc} поперёк
     * закрывает ~0,93 ширины, ореола exp(−(u/0,42)²) — 0,42·√π/2 ≈ 0,744.
     */
    static final float STRIP_CROSS = 0.93f / 0.744f;

    /** Отрезок p–q поперёк струи — шире в {@link #STRIP_CROSS} раза от своей середины. */
    private static void widen(Vector3f p, Vector3f q) {
        float mx = 0.5f * (p.x + q.x), my = 0.5f * (p.y + q.y), mz = 0.5f * (p.z + q.z);
        p.set(mx + (p.x - mx) * STRIP_CROSS, my + (p.y - my) * STRIP_CROSS, mz + (p.z - mz) * STRIP_CROSS);
        q.set(mx + (q.x - mx) * STRIP_CROSS, my + (q.y - my) * STRIP_CROSS, mz + (q.z - mz) * STRIP_CROSS);
    }

    /**
     * Светящийся круг радиуса radius лицом к камере (сечение струи факела с торца, {@code PlumeRenderer}): свет (r, g, b)
     * на экране, только светит.
     */
    public void glowDisc(double dx, double dy, double dz, double radius, float r, float g, float b, float soft) {
        if (billboard(dx, dy, dz, radius * DISC, 0, FxAtlas.disc(), r, g, b, 0, soft)) counts[2]++;
    }

    /** Вуаль трубки следа вокруг глаза — на столько блоков перед ним (как у облаков, {@link SmokePuffs#VEIL_AT}). */
    static final double VEIL_AT = SmokePuffs.VEIL_AT;

    /** Расстояние от глаза (начало координат) до отрезка от (x0, y0, z0) длиной (tx, ty, tz). */
    static double distance(double x0, double y0, double z0, double tx, double ty, double tz) {
        double t2 = tx * tx + ty * ty + tz * tz;
        double u = t2 < 1e-12 ? 0 : Math.max(0, Math.min(1, -(x0 * tx + y0 * ty + z0 * tz) / t2));
        double px = x0 + tx * u, py = y0 + ty * u, pz = z0 + tz * u;
        return Math.sqrt(px * px + py * py + pz * pz);
    }

    private static double sq(double v) {
        return v * v;
    }

    /**
     * Доля отрезка шлейфа, что рисуется лентой, при его длине на экране across (поперёк луча зрения, блоки на месте) и
     * полуширине трубки halfWidth: длиннее двух ширин — вся, короче ширины — круг торца ({@link #ribbon}), между — плавно.
     */
    static double endOn(double across, double halfWidth) {
        double u = Math.max(0, Math.min(1, (across / Math.max(2 * halfWidth, 1e-9) - 1)));
        return u * u * (3 - 2 * u);
    }

    /** Квадрат: свет (r, g, b) уже умножен на непрозрачность a; ни света, ни заслона — не пишется. */
    private boolean billboard(double dx, double dy, double dz, double half, float rot, FxAtlas.Sprite sprite, float r, float g, float b, float a,
                              float soft) {
        return billboard(dx, dy, dz, half, rot, sprite, r, g, b, a, soft, 0);
    }

    /** Квадрат с тем же угловым размером, перенесённый на {@code ahead} блоков ближе к глазу ({@link #front}). */
    private boolean billboard(double dx, double dy, double dz, double half, float rot, FxAtlas.Sprite sprite, float r, float g, float b, float a,
                              float soft, double ahead) {
        if (a < 0.002f && r + g + b < 0.006f) return false;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz), shift = front(d, ahead), unfold = FarDraw.fold(d * shift, far), k = shift * unfold;
        // цвет вершины — байт: больше 1 переполнился бы
        out.billboard((float) (dx * k), (float) (dy * k), (float) (dz * k), (float) d, (float) (half * k), rot, sprite, Math.min(1, r), Math.min(1, g),
                Math.min(1, b), Math.min(1, a), soft, (float) unfold, LightTexture.FULL_BRIGHT, 0);
        return true;
    }

    /**
     * Во сколько раз ближе к глазу поставить квадрат на расстоянии d, чтобы он стоял на ahead блоков ближе, с тем же
     * угловым размером (размер × то же число); не ближе {@link #AIR_NEAR}, у самого глаза — на месте.
     */
    public static double front(double d, double ahead) {
        if (ahead <= 0 || d <= AIR_NEAR) return 1;
        return Math.max(d - ahead, AIR_NEAR) / d;
    }
}
