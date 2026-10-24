package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;

import java.util.Locale;

/**
 * Свечение (bloom) огня, огненного шара, вспышки и факела — своё, после шейдерпака: слой эффектов идёт после его
 * композита ({@code AFTER_LEVEL}), и законного пути в буфер пака на 1.21.1 нет (Iris без API, «unknown shaders»
 * выключены по умолчанию). Тем же способом, что у Complementary Reimagined/Unbound r5.9.3 ({@code composite4/5}:
 * HDR кадра → пирамида размытий {@code lod} 2–8 → {@code mix(цвет, пирамида, 0,12)} без порога → кривая), только
 * HDR — не весь кадр, а излучение нашего слоя. Настройка игрока {@code bloom}: {@code OFF}, {@code FAST} (по умолчанию),
 * {@code FULL} — производительность, не следствие.
 * <ul>
 * <li>Излучение. После квадратов слоя — ещё один проход тем же шейдером ({@code fx.fsh}, {@code FxGlowPass}) на уровне
 * пирамиды {@link #glowLevel}: {@code FULL} — на октаву мельче первого уровня ореола (у 1080 — половина кадра, у 1600 —
 * четверть; дальше — спуск в 13 выборок), {@code FAST} — сам первый уровень, вчетверо меньше фрагментов. Только в рамке
 * светящихся на экране (вне её излучения нет, и дым там ничего не гасит) и квадраты от самого дальнего светящегося до
 * ближнего (что дальше всех огней, их излучения не закрывает) — из того же буфера вершин: излучение (R11F_G11F_B10F) и
 * долю пикселя, которую занимает само светящееся (R8), тем же смешиванием, что и цвет ({@code ONE, ONE_MINUS_SRC_ALPHA}):
 * дым перед огнём гасит его излучение той же долей, мягкий край у мира тот же, а что за стеной и рельефом, отсекает
 * расстояние до мира ({@link SceneDepth}; аппаратной глубины в проходе нет). Дым там — одна непрозрачность, без света.
 * Вторая цель в самом слое (MRT) вышла дороже: каждый фрагмент дыма во весь кадр смешивал ещё две цели.
 * Свет — линейный, белый экран — 1: показанный свет, умноженный на пересвет, который экран срезал
 * ({@link FxQuads#emission}: у ядер, вспышек и факела — яркость против белого до {@code Sight.onScreen}, у кадра огня —
 * яркость шара модели против его кадра на экране), не ярче белой точки {@link #WHITE} плёночной кривой огня: ярче неё
 * кадр различий не показывает.</li>
 * <li>Мелкое. Проход берёт излучение в серединах своих пикселей: квадрат уже пикселя (искра, дальний факел, ядро вдали)
 * то попадает в середину, то нет — свечение пропадало бы и мерцало. Уже {@link #SMALL} пикселей прохода поперёк
 * светящийся квадрат идёт в проход пятнами ({@link #spots}: ореол {@code far/glow} формулой, полуразмер
 * {@link #SPOT_HALF} пикселя — выборка в серединах несёт ту же энергию при любом сдвиге, в пределах 1 %) с его энергией:
 * средний свет его места на листе ({@link FxAtlas#means}) × цвет × пересвет × телесный угол; сам он в проход не идёт.
 * Свет пятна — в 16 битах {@code UV2} логарифмом ({@link #magnitude}, шаг 0,07 %), оттенок — в цвете: в байте цвета
 * (1/255) слабое пятно обнулялось, а чуть сильнее — получало почти вдвое больше.</li>
 * <li>Пирамида — спуск двойного фильтра Jimenez 2014 (Call of Duty: Advanced Warfare; так же — Unity URP, Blender EEVEE):
 * 13 выборок, свёртка сохраняет энергию, и каждый уровень несёт всю энергию излучения; сбор уровней одним проходом —
 * B-сплайн, 4 выборки на уровень, равные веса: равная энергия на октаву угла, то есть спад 1/θ², как у функции рассеяния
 * глаза CIE (Vos 1984, CIE 135/1999: L/E = 10/θ², θ в градусах). Уровни — как у пака: 2–8 при высоте кадра 1080
 * (2^k пикселей — ~0,2°…17° при поле зрения 70°), выше — на октаву глубже ({@link #first}). Уменьшенные копии 2×2
 * ({@code glGenerateMipmap}) вместо спуска проверены и отвергнуты: у движущегося источника дальний ореол скачет в разы.</li>
 * <li>Доля {@link #SHARE} без порога — как у пака по умолчанию; у глаза по CIE на углах 0,1°–50° выходит 0,12
 * (0,0191·ln(θ₂/θ₁)). Нормировка одна: сбор делит сумму уровней на их число, вывод множит на долю
 * ({@link #gatherUniforms}, {@link #outputUniforms}; второе деление на число уровней делало долю 0,017). Ореол прибавляется вокруг светящегося: кривая 1 − e^(−x) и смешивание «экраном»
 * ({@code ONE, ONE_MINUS_SRC_COLOR}) — ровно сложение света до этой кривой. На самом светящемся его нет (по доле
 * светящегося): кадр огня показан своей экспозицией, в десятки раз ниже пересвета модели, и ореол, посчитанный с
 * пересветом, высветлил бы его складки и сажу в «мыло». У пака источник отдаёт ореолу свою долю; здесь он её не
 * теряет — его кадр уже показан своей кривой.</li>
 * </ul>
 * Подача, не физика: яркость и энергия огня в модели не меняются, свет на мир и дым — свой ({@code FxLights},
 * {@code HeatPass}). Блик и вуаль Стайлза–Холладея у глаза ({@code FarSprites.glare}) остаются: они видят источник
 * и за кадром, и вне его. Работает с любым паком и без шейдеров; кадр, в который рисует слой, — привязанный (у
 * Flashback при выводе видео — его размера). Ядерный шар (объём пелены) сюда не пишет.
 */
public final class FxBloom {
    /** Доля излучения в ореоле: {@code BLOOM_STRENGTH} Complementary по умолчанию; у глаза по CIE на 0,1°–50° — 0,12. */
    public static final double SHARE = 0.12;
    /** Уровней пирамиды в ореоле (у пака — lod 2–8). */
    public static final int LEVELS = 7;
    /** Первый уровень при высоте кадра {@link #REFERENCE} (у пака — lod 2). */
    static final int FIRST = 2;
    /** Высота кадра, к которой привязаны уровни (у пака — 1080: размер ореола на экране не зависит от разрешения). */
    static final int REFERENCE = 1080;
    /** Плёночная кривая запекания огня ({@code tools/bake_fx/pack.py film}: ACES, приближение Narkowicz 2016). */
    static final double FA = 2.51, FB = 0.03, FC = 2.43, FD = 0.59, FE = 0.14;
    /** Белая точка плёночной кривой огня: свет, который она показывает белым (≈ 7,24); свечение берёт свет не ярче. */
    public static final double WHITE = filmInverse(1);
    /**
     * Байт {@code Emit} вершины: 0 — не светится; {@value #SKIP} — светится через свои пятна ({@link #spots}: в проходе
     * свечения его нет); {@value #EMITTER} + код — светится с пересветом {@link #gain}(код), код — 0…{@value #STEPS};
     * {@value #SPOT} — пятно мелкого светящегося (только в проходе свечения; свет — {@link #magnitude}).
     */
    static final int SKIP = 1, EMITTER = 2, SPOT = 130, STEPS = 125;
    /**
     * Свет пятна — в 16 битах {@code UV2} вершины (у пятна карта освещения не нужна): величина 2^((код − {@value #MAG_ZERO})
     * / {@value #MAG_STEP}), шаг 0,07 %, от 2⁻¹⁶ до 2¹⁶; код 0 — нуль. В байте цвета (1/255) свет слабого пятна обнулялся или
     * рос вдвое — энергия мелкого источника скакала с его размером.
     */
    static final int MAG_ZERO = 16384, MAG_STEP = 1024;

    /** Код величины v ≥ 0 ({@link #MAG_ZERO}): 0 — нуль, 1…32767. */
    static int magnitude(double v) {
        if (!(v > 0)) return 0;
        double c = MAG_ZERO + MAG_STEP * Math.log(v) / Math.log(2);
        return c < 0.5 ? 0 : (int) Math.min(Short.MAX_VALUE, Math.round(c));
    }

    /** Величина по коду ({@link #magnitude}). */
    static double magnitudeOf(int code) {
        return code <= 0 ? 0 : Math.pow(2, (code - MAG_ZERO) / (double) MAG_STEP);
    }

    private FxBloom() {}

    // ---------------------------------------------------------------- законы (общие с шейдерами: FxBloomTest)

    /** Плёночная кривая запекания: показанный линейный свет 0…1 по свету x. */
    public static double film(double x) {
        x = Math.max(0, x);
        return Math.min(1, x * (FA * x + FB) / (x * (FC * x + FD) + FE));
    }

    /** Обратная плёночная кривая: свет, который она показывает как y (0…1); y = 1 — {@link #WHITE}. */
    public static double filmInverse(double y) {
        double t = Math.max(0, Math.min(1, y));
        double a = FA - FC * t, b = FB - FD * t, c = -FE * t;
        return (-b + Math.sqrt(b * b - 4 * a * c)) / (2 * a);
    }

    /** Код пересвета 0…{@value #STEPS}: 0 — свет как показан, {@value #STEPS} — {@link #WHITE} и ярче. */
    public static int code(double hdr) {
        double g = Math.max(1, Math.min(WHITE, hdr));
        return (int) Math.round(STEPS * Math.log(g) / Math.log(WHITE));
    }

    /** Пересвет по коду ({@link #code}). */
    public static double gain(int code) {
        return Math.pow(WHITE, Math.clamp(code, 0, STEPS) / (double) STEPS);
    }

    /** Кривая ореола: свет x (белый экран — 1) → доля до белого; смешивание «экраном» — сложение света до неё. */
    public static double curve(double x) {
        return -Math.expm1(-Math.max(0, x));
    }

    /**
     * Первый уровень пирамиды у кадра высотой {@code height}: как у пака — {@link #FIRST} при {@link #REFERENCE}, выше —
     * на столько октав глубже (у 1600 — третий), ниже — не мельче.
     */
    static int first(int height) {
        return FIRST + (int) Math.max(0, Math.round(Math.log(height / (double) REFERENCE) / Math.log(2)));
    }

    /** Качество из настроек игрока ({@code bloom}); без настроек (проверки) — {@code FAST}. */
    static IncomingConfig.Client.Bloom quality() {
        try {
            return IncomingConfig.CLIENT.bloom.get();
        } catch (IllegalStateException | NullPointerException e) {
            return IncomingConfig.Client.Bloom.FAST;
        }
    }

    /**
     * Уровень прохода излучения у кадра высотой {@code height}: {@code FULL} — на октаву мельче {@link #first} (дальше он
     * идёт в пирамиду через спуск в 13 выборок: у 1080 — половина кадра, у 1600 — четверть), {@code FAST} — сам первый
     * уровень (фрагментов вчетверо меньше; мелкое — пятнами того же размера в его пикселях, {@link #spots}).
     */
    static int glowLevel(int height, IncomingConfig.Client.Bloom quality) {
        return first(height) - (quality == IncomingConfig.Client.Bloom.FULL ? 1 : 0);
    }

    /** Угол пикселя прохода излучения, рад, при угле пикселя кадра {@code pixel} и высоте {@code height}; 0 — свечения нет. */
    static double texel(double pixel, int height) {
        IncomingConfig.Client.Bloom q = quality();
        if (q == IncomingConfig.Client.Bloom.OFF || !ready()) return 0;
        return pixel * (1 << glowLevel(height, q));
    }

    /** Шейдеры свечения загружены и цели не отказали. */
    static boolean ready() {
        return !failed && down != null && up != null && gather != null && out != null;
    }

    /**
     * Пятно мелкого светящегося — полуразмер, пикселей прохода излучения: гауссов ореол {@code far/glow} (σ ≈ 0,3 полуразмера,
     * ≈ 0,9 пикселя) выборкой в серединах пикселей несёт ту же энергию при любом сдвиге (у квадрата в доли пикселя выборка
     * попадает в середину пикселя или нет — свечение пропадает и мерцает). Мельче {@value #SMALL} пикселей поперёк — пятна.
     */
    static final double SPOT_HALF = 3, SMALL = 3;
    /** Пятен на квадрат вдоль — не больше (длинная тонкая полоса — бусами через полуразмер пятна). */
    static final int SPOTS = 8;
    /** Средняя по квадрату пятна доля его ореола ({@code glowAlpha} в {@code fx.fsh}; середины крайних пикселей — 64/63). */
    static final double SPOT_COVER = spotCover();

    private static double spotCover() {
        int n = 1024;
        double sum = 0, edge = 64.0 / 63;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double x = ((i + 0.5) / n * 2 - 1) * edge, y = ((j + 0.5) / n * 2 - 1) * edge;
                sum += glowAlpha(Math.sqrt(x * x + y * y));
            }
        }
        return sum / ((double) n * n);
    }

    /** Ореол {@code far/glow} ({@code fx.fsh: glowAlpha}) на расстоянии r в долях полуразмера. */
    static double glowAlpha(double r) {
        double t = Math.max(0, Math.min(1, (1 - r) / 0.25));
        return Math.exp(-(r / 0.42) * (r / 0.42)) * t * t * (3 - 2 * t) / 0.997147;
    }

    /**
     * Пятна квадрата v (четыре вершины от камеры, x y z подряд) с пикселем прохода излучения в texel рад: мельче
     * {@link #SMALL} пикселей поперёк — пятна по его длине (не больше {@link #SPOTS}), иначе 0 (квадрат рисуется сам). В out —
     * середины пятен (x y z подряд), за ними — телесный угол квадрата (его свет делится между пятнами поровну).
     */
    static int spots(float[] v, double texel, double[] out) {
        double cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < 4; i++) {
            cx += v[3 * i] / 4;
            cy += v[3 * i + 1] / 4;
            cz += v[3 * i + 2] / 4;
        }
        double d = Math.sqrt(cx * cx + cy * cy + cz * cz);
        if (d < 1e-4 || texel <= 0) return 0;
        double nx = cx / d, ny = cy / d, nz = cz / d;
        // площадь четырёхугольника — половина векторного произведения диагоналей, телесный угол — её доля поперёк луча
        double ax = v[6] - v[0], ay = v[7] - v[1], az = v[8] - v[2], bx = v[9] - v[3], by = v[10] - v[4], bz = v[11] - v[5];
        double sx = 0.5 * (ay * bz - az * by), sy = 0.5 * (az * bx - ax * bz), sz = 0.5 * (ax * by - ay * bx);
        double solid = Math.abs(sx * nx + sy * ny + sz * nz) / (d * d);
        // полудлина поперёк луча — до самой дальней вершины, ширина — площадь на длину
        double reach = 0, ux = 0, uy = 0, uz = 0;
        for (int i = 0; i < 4; i++) {
            double px = v[3 * i] - cx, py = v[3 * i + 1] - cy, pz = v[3 * i + 2] - cz, along = px * nx + py * ny + pz * nz;
            px -= along * nx;
            py -= along * ny;
            pz -= along * nz;
            double len = Math.sqrt(px * px + py * py + pz * pz) / d;
            if (len > reach) {
                reach = len;
                ux = px;
                uy = py;
                uz = pz;
            }
        }
        double width = reach > 0 ? solid / (2 * reach) : 0;
        if (width >= SMALL * texel) return 0;
        int n = (int) Math.clamp(Math.ceil(2 * reach / (SPOT_HALF * texel)), 1, SPOTS);
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
        for (int k = 0; k < n; k++) {
            double t = n == 1 || ul <= 0 ? 0 : ((k + 0.5) / n * 2 - 1) * reach * d / ul;
            out[3 * k] = cx + t * ux;
            out[3 * k + 1] = cy + t * uy;
            out[3 * k + 2] = cz + t * uz;
        }
        out[3 * n] = solid;
        return n;
    }

    /** Последний уровень: {@link #LEVELS} от первого, но не мельче двух пикселей по меньшей стороне. */
    static int last(int width, int height) {
        int f = first(height), k = f;
        while (k < f + LEVELS - 1 && size(Math.min(width, height), k + 1) >= 2) k += 1;
        return k;
    }

    /** Размер уровня k по стороне n: вдвое мельче на уровень, с округлением вверх. */
    static int size(int n, int k) {
        for (int i = 0; i < k; i++) n = (n + 1) / 2;
        return Math.max(1, n);
    }

    // ---------------------------------------------------------------- пирамида на процессоре (те же выборки, что шейдеры)

    /** Пиксели уровня: свет одного канала по строкам, ширина и высота. */
    record Image(float[] v, int w, int h) {
        static Image of(int w, int h) {
            return new Image(new float[w * h], w, h);
        }

        float at(int x, int y) {
            return v[Math.max(0, Math.min(h - 1, y)) * w + Math.max(0, Math.min(w - 1, x))];
        }

        /** Выборка с линейным фильтром и краем «по краю» в точке (x, y) в пикселях (середина пикселя i — i + ½). */
        float sample(double x, double y) {
            double u = x - 0.5, t = y - 0.5;
            int i = (int) Math.floor(u), j = (int) Math.floor(t);
            double fu = u - i, ft = t - j;
            return (float) ((at(i, j) * (1 - fu) + at(i + 1, j) * fu) * (1 - ft) + (at(i, j + 1) * (1 - fu) + at(i + 1, j + 1) * fu) * ft);
        }

        double sum() {
            double s = 0;
            for (float f : v) s += f;
            return s;
        }
    }

    /** Вниз ({@code fx_bloom_down}): пиксель (x, y) уровня — точка (2x + 1, 2y + 1) прошлого, 13 выборок. */
    static Image down(Image s) {
        Image o = Image.of((s.w + 1) / 2, (s.h + 1) / 2);
        for (int y = 0; y < o.h; y++) {
            for (int x = 0; x < o.w; x++) {
                double cx = 2 * x + 1, cy = 2 * y + 1;
                double e = s.sample(cx, cy);
                double corners = s.sample(cx - 2, cy + 2) + s.sample(cx + 2, cy + 2) + s.sample(cx - 2, cy - 2) + s.sample(cx + 2, cy - 2);
                double sides = s.sample(cx, cy + 2) + s.sample(cx - 2, cy) + s.sample(cx + 2, cy) + s.sample(cx, cy - 2);
                double inner = s.sample(cx - 1, cy + 1) + s.sample(cx + 1, cy + 1) + s.sample(cx - 1, cy - 1) + s.sample(cx + 1, cy - 1);
                o.v[y * o.w + x] = (float) (e * 0.125 + corners * 0.03125 + sides * 0.0625 + inner * 0.125);
            }
        }
        return o;
    }

    /** Вверх ({@code fx_bloom_up}): шатёр 3×3 по пикселям мельчайшего уровня s в уровень размера w×h. */
    static Image tent(Image s, int w, int h) {
        Image o = Image.of(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double cx = (x + 0.5) / 2, cy = (y + 0.5) / 2;
                double corners = s.sample(cx - 1, cy - 1) + s.sample(cx + 1, cy - 1) + s.sample(cx - 1, cy + 1) + s.sample(cx + 1, cy + 1);
                double sides = s.sample(cx, cy - 1) + s.sample(cx - 1, cy) + s.sample(cx + 1, cy) + s.sample(cx, cy + 1);
                o.v[y * w + x] = (float) ((corners + 2 * sides + 4 * s.sample(cx, cy)) / 16);
            }
        }
        return o;
    }

    /**
     * Свет ореола на кадре до кривой ({@code fx_bloom.fsh}: {@code light}) по ореолу halo ({@link #halo}) — с числами вывода,
     * которые ставит проход ({@link #outputUniforms}); доли светящегося нет.
     */
    static Image light(Image halo, int w, int h, int g) {
        float[] share = new float[1];
        outputUniforms((name, v) -> {
            if (name.equals("BloomStep")) share[0] = v[2];
        }, size(w, 1), size(h, 1), g, size(w, g), size(h, g), 0, 0);
        Image out = Image.of(w, h);
        for (int i = 0; i < out.v.length; i++) out.v[i] = halo.v[i] * share[0];
        return out;
    }

    /** Кубический B-сплайн по пикселям m в точке (x, y) в его пикселях (середина пикселя i — i + ½), край — «по краю». */
    static double bspline(Image m, double x, double y) {
        double tx = x - 0.5, ty = y - 0.5;
        int ix = (int) Math.floor(tx), iy = (int) Math.floor(ty);
        double[] wx = basis(tx - ix), wy = basis(ty - iy);
        double sum = 0;
        for (int j = 0; j < 4; j++) {
            for (int i = 0; i < 4; i++) sum += wx[i] * wy[j] * m.at(ix - 1 + i, iy - 1 + j);
        }
        return sum;
    }

    /** Веса кубического B-сплайна четырёх соседей при доле f. */
    static double[] basis(double f) {
        double f2 = f * f, f3 = f2 * f;
        return new double[]{(-f3 + 3 * f2 - 3 * f + 1) / 6, (3 * f3 - 6 * f2 + 4) / 6, (-3 * f3 + 3 * f2 + 3 * f + 1) / 6, f3 / 6};
    }

    /**
     * Ореол во весь кадр w×h по излучению прохода свечения e на уровне g — свет до кривой (без доли {@link #SHARE}): спуск
     * в 13 выборок до {@link #last}, сбор уровней {@link #first} … {@link #last} с равными весами одним проходом B-сплайном
     * (у каждого — 4 выборки), шатром до половины кадра, на кадр — линейно; те же выборки, что у проходов. Сумма по кадру —
     * сумма излучения в пикселях кадра (энергия сохраняется).
     */
    static Image halo(Image e, int w, int h, int g) {
        int first = first(h), last = last(w, h), n = last - first + 1;
        float[] count = new float[1];
        gatherUniforms((name, v) -> count[0] = v[0], n);
        Image[] level = new Image[last + 1];
        level[g] = e;
        for (int k = g + 1; k <= last; k++) level[k] = down(level[k - 1]);
        Image f = level[first];
        Image gather = Image.of(f.w, f.h);
        for (int y = 0; y < f.h; y++) {
            for (int x = 0; x < f.w; x++) {
                double sum = 0;
                for (int k = first; k <= last; k++) {
                    double s2 = 1 << (k - first);
                    sum += bspline(level[k], (x + 0.5) / s2, (y + 0.5) / s2);
                }
                gather.v[y * f.w + x] = (float) (sum / count[0]);
            }
        }
        Image up = gather;
        for (int k = first - 1; k >= 1; k--) up = tent(up, size(w, k), size(h, k));
        Image out = Image.of(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) out.v[y * w + x] = up.sample((x + 0.5) / 2, (y + 0.5) / 2);
        }
        return out;
    }

    /** Куда ставятся числа шейдеров проходов: у видеокарты — uniform программы, у проверки — таблица. */
    interface Uniforms {
        void set(String name, float... v);
    }

    /** Числа прохода сбора ({@code fx_bloom_gather}): уровней в сборе — на столько он делит сумму (равные веса). */
    static void gatherUniforms(Uniforms u, int levels) {
        u.set("BloomCount", levels);
    }

    /**
     * Числа вывода на кадр ({@code fx_bloom}): шаг половины кадра w1×h1, доля ореола {@link #SHARE} — сбор уже поделил сумму
     * уровней на их число (второе деление делало долю 0,12/7), начало окна вывода, шаг доли светящегося уровня g размера
     * wg×hg.
     */
    static void outputUniforms(Uniforms u, int w1, int h1, int g, int wg, int hg, int fromX, int fromY) {
        u.set("BloomStep", 1f / w1, 1f / h1, (float) SHARE, 0f);
        u.set("BloomFrom", fromX, fromY);
        u.set("BloomCover", 1f / ((1 << g) * wg), 1f / ((1 << g) * hg));
    }

    /** Числа шейдера s по таблице {@link Uniforms}. */
    private static Uniforms of(ShaderInstance s) {
        return (name, v) -> {
            com.mojang.blaze3d.shaders.AbstractUniform at = s.safeGetUniform(name);
            switch (v.length) {
                case 1 -> at.set(v[0]);
                case 2 -> at.set(v[0], v[1]);
                case 3 -> at.set(v[0], v[1], v[2]);
                default -> at.set(v[0], v[1], v[2], v[3]);
            }
        };
    }

    // ---------------------------------------------------------------- проходы

    @Nullable
    static ShaderInstance down, up, gather, out;
    /**
     * Рамка прохода свечения: излучение (уровень {@link #glowLevel} пирамиды) и доля светящегося того же размера; сбор
     * уровней — своя текстура размера первого уровня.
     */
    private static int fbo = -1, cover = -1, gathered = -1, gatheredFbo = -1, frameW, frameH, frameG = -1;
    private static boolean failed;
    private static final int MAX = 12;
    private static final int[] LEVEL = new int[MAX + 1], LEVEL_FBO = new int[MAX + 1], LEVEL_W = new int[MAX + 1], LEVEL_H = new int[MAX + 1];
    private static final int[] VIEWPORT = new int[4];
    private static final float[] ZERO = new float[4];
    /** Время проходов свечения на видеокарте (с проходом излучения) и уровни — для строки сценария. */
    private static final GpuTimer GPU = new GpuTimer();
    /** Части: проход излучения, пирамида, вывод на кадр. */
    private static final GpuTimer PASS = new GpuTimer(), PYRAMID = new GpuTimer(), OUT = new GpuTimer();
    private static int lastFirst, lastLast;

    static {
        java.util.Arrays.fill(LEVEL, -1);
        java.util.Arrays.fill(LEVEL_FBO, -1);
    }

    /**
     * После квадратов слоя (шейдер слоя {@code fx} — текущий, состояние смешивания — его): квадраты от самого дальнего
     * светящегося — ещё раз, в половину кадра, проходом свечения ({@code FxGlowPass}: излучение и доля светящегося), потом
     * пирамида и ореол на кадр. Ничего не светится, шейдеры не загрузились, рамку не завести — ничего.
     */
    static void draw(FxQuads quads, ShaderInstance fx, Matrix4f viewProjection) {
        IncomingConfig.Client.Bloom q = quality();
        if (q == IncomingConfig.Client.Bloom.OFF || !quads.glows() || !ready()) return;
        int frame = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        int w = VIEWPORT[0] + VIEWPORT[2], h = VIEWPORT[1] + VIEWPORT[3];
        int g = glowLevel(h, q);
        if (w <= 0 || h <= 0 || !target(w, h, g)) return;
        // излучение и доля светящегося — только в рамке светящихся на экране: вне её дым ничего не гасит (там нуль)
        if (!scissor(quads.glowBox(), viewProjection, LEVEL_W[g], LEVEL_H[g], RECT)) return;
        int timed = GPU.begin(), pass = PASS.begin();
        // излучение и доля светящегося — тем же шейдером и смешиванием, на уровне g, без аппаратной глубины (что за
        // миром — по расстоянию до мира в шейдере)
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
        GlStateManager._viewport(0, 0, LEVEL_W[g], LEVEL_H[g]);
        GL30.glClearBufferfv(GL11.GL_COLOR, 0, ZERO);
        GL30.glClearBufferfv(GL11.GL_COLOR, 1, ZERO);
        fx.safeGetUniform("FxGlowPass").set(1f, (float) w / LEVEL_W[g]);
        RenderSystem.enableScissor(RECT[0], RECT[1], RECT[2], RECT[3]);
        boolean any = quads.drawGlow(fx);
        RenderSystem.disableScissor();
        fx.safeGetUniform("FxGlowPass").set(0f, 1f);
        PASS.end(pass);
        if (any) halo(frame, w, h, g);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, frame);
        GlStateManager._viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        // как у слоя после квадратов
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.enableDepthTest();
        GPU.end(timed);
    }

    private static final int[] RECT = new int[4];
    private static final Vector4f CORNER = new Vector4f();

    /**
     * Прямоугольник в пикселях цели w×h, куда ложится рамка box (от камеры) в матрице viewProjection, с запасом в два
     * пикселя: x, y, ширина, высота — в rect. Угол рамки у глаза или за ним — весь кадр. false — рамка вне кадра.
     */
    static boolean scissor(float[] box, Matrix4f viewProjection, int w, int h, int[] rect) {
        double x0 = 1, y0 = 1, x1 = -1, y1 = -1;
        for (int i = 0; i < 8; i++) {
            viewProjection.transform(CORNER.set(box[(i & 1) == 0 ? 0 : 3], box[(i & 2) == 0 ? 1 : 4], box[(i & 4) == 0 ? 2 : 5], 1));
            if (CORNER.w < 1e-3f) {
                x0 = y0 = -1;
                x1 = y1 = 1;
                break;
            }
            x0 = Math.min(x0, CORNER.x / CORNER.w);
            y0 = Math.min(y0, CORNER.y / CORNER.w);
            x1 = Math.max(x1, CORNER.x / CORNER.w);
            y1 = Math.max(y1, CORNER.y / CORNER.w);
        }
        int px0 = (int) Math.max(0, Math.floor((x0 * 0.5 + 0.5) * w) - 2), py0 = (int) Math.max(0, Math.floor((y0 * 0.5 + 0.5) * h) - 2);
        int px1 = (int) Math.min(w, Math.ceil((x1 * 0.5 + 0.5) * w) + 2), py1 = (int) Math.min(h, Math.ceil((y1 * 0.5 + 0.5) * h) + 2);
        rect[0] = px0;
        rect[1] = py0;
        rect[2] = px1 - px0;
        rect[3] = py1 - py0;
        return rect[2] > 0 && rect[3] > 0;
    }

    /**
     * Пирамида из излучения (уровень g) и ореол на кадр frame размера w×h: спуск в 13 выборок до последнего уровня, сбор
     * всех уровней ореола одним проходом (B-сплайн, 4 выборки на уровень), шатром до половины кадра, вывод. Цена здесь — не
     * пиксели, а цепочка зависимых проходов: шейдер применяется раз на серию, между проходами меняются только цель,
     * текстура и шаг, четырёхугольник — постоянный треугольник во весь кадр. Сбор вместо подъёма по уровням с прибавлением —
     * на 5 проходов меньше; уменьшенные копии ({@code glGenerateMipmap}, 2×2) вместо спуска — ещё меньше, но они
     * привязывают глубокие уровни к своей сетке: у движущегося источника дальний ореол скачет в разы (FxBloomTest).
     */
    private static void halo(int frame, int w, int h, int g) {
        int first = first(h), last = last(w, h), n = last - first + 1;
        lastFirst = first;
        lastLast = last;
        int timed = PYRAMID.begin();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        triangle();
        // вниз: уровень k из k − 1
        begin(down, LEVEL[g]);
        for (int k = g + 1; k <= last; k++) pass(down, k, LEVEL[k - 1], LEVEL_W[k - 1], LEVEL_H[k - 1]);
        // сбор уровней first … last в свою текстуру размера первого
        ShaderInstance c = gather;
        for (int i = 0; i < GATHER; i++) c.setSampler("Sampler" + i, LEVEL[first + Math.min(i, n - 1)]);
        gatherUniforms(of(c), n);
        c.apply();
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, gatheredFbo);
        GlStateManager._viewport(0, 0, LEVEL_W[first], LEVEL_H[first]);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        // мельче первого уровня — шатром до половины кадра: растянутый вчетверо линейно уровень у источника некруглый (~10 %)
        begin(up, gathered);
        for (int k = first - 1; k >= 1; k--) {
            int src = k == first - 1 ? gathered : LEVEL[k + 1];
            pass(up, k, src, LEVEL_W[k + 1], LEVEL_H[k + 1]);
        }
        PYRAMID.end(timed);
        // на кадр: вокруг светящегося, кривая и «экран»
        int shown = OUT.begin();
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, frame);
        GlStateManager._viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR, GlStateManager.SourceFactor.ZERO,
                GlStateManager.DestFactor.ONE);
        ShaderInstance s = out;
        s.setSampler("Sampler1", cover);
        outputUniforms(of(s), LEVEL_W[1], LEVEL_H[1], g, LEVEL_W[g], LEVEL_H[g], VIEWPORT[0], VIEWPORT[1]);
        begin(s, LEVEL[1]);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        s.clear();
        // свой массив вершин был привязан мимо BufferUploader: его привязку он помнит и не повторил бы
        GlStateManager._glBindVertexArray(0);
        BufferUploader.invalidate();
        OUT.end(shown);
    }

    /** Уровней в проходе сбора — не больше (по выборке на каждый, {@code fx_bloom_gather}). */
    static final int GATHER = 7;

    /** Применить шейдер s с текстурой src в Sampler0 (серия проходов дальше меняет только цель, текстуру и шаг). */
    private static void begin(ShaderInstance s, int src) {
        s.setSampler("Sampler0", src);
        s.apply();
    }

    /** Проход в уровень k применённым шейдером s из текстуры src размера sw×sh. */
    private static void pass(ShaderInstance s, int k, int src, int sw, int sh) {
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, LEVEL_FBO[k]);
        GlStateManager._viewport(0, 0, LEVEL_W[k], LEVEL_H[k]);
        com.mojang.blaze3d.shaders.Uniform step = s.getUniform("BloomStep");
        if (step != null) {
            step.set(1f / sw, 1f / sh, 0f, 0f);
            step.upload();
        }
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(src);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    /** Свой массив вершин: треугольник во весь кадр (−1, −1), (3, −1), (−1, 3) — без шва по диагонали. */
    private static int triangle = -1;

    private static void triangle() {
        if (triangle >= 0) {
            GlStateManager._glBindVertexArray(triangle);
            return;
        }
        triangle = GlStateManager._glGenVertexArrays();
        GlStateManager._glBindVertexArray(triangle);
        int buffer = GlStateManager._glGenBuffers();
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, new float[]{-1, -1, 0, 3, -1, 0, -1, 3, 0}, GL15.GL_STATIC_DRAW);
        // место Position — первое: шейдеры проходов собраны с форматом POSITION
        GlStateManager._enableVertexAttribArray(0);
        GlStateManager._vertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0);
    }

    /**
     * Уровни пирамиды кадра w×h (R11F_G11F_B10F, линейная выборка, край — «по краю»; уровень {@link #glowLevel} —
     * излучение прохода свечения) и доля светящегося (R8, того же размера); false — не завести.
     */
    private static boolean target(int w, int h, int g) {
        if (fbo >= 0 && frameW == w && frameH == h && frameG == g) return true;
        int draw = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean ok = true;
        for (int k = 1; k <= MAX; k++) {
            LEVEL_W[k] = size(w, k);
            LEVEL_H[k] = size(h, k);
            LEVEL[k] = texture(LEVEL[k], LEVEL_W[k], LEVEL_H[k], GL30.GL_R11F_G11F_B10F);
            if (LEVEL_FBO[k] < 0) LEVEL_FBO[k] = GlStateManager.glGenFramebuffers();
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, LEVEL_FBO[k]);
            GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, LEVEL[k], 0);
            ok &= GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
        }
        int first = first(h);
        gathered = texture(gathered, LEVEL_W[first], LEVEL_H[first], GL30.GL_R11F_G11F_B10F);
        if (gatheredFbo < 0) gatheredFbo = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, gatheredFbo);
        GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, gathered, 0);
        ok &= GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
        cover = texture(cover, LEVEL_W[g], LEVEL_H[g], GL30.GL_R8);
        if (fbo < 0) fbo = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
        GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, LEVEL[g], 0);
        GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GlConst.GL_TEXTURE_2D, cover, 0);
        GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
        ok &= GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        frameW = w;
        frameH = h;
        frameG = g;
        if (!ok) {
            Incoming.LOG.error("Effects bloom: targets for {}×{} not created, no bloom", w, h);
            failed = true;
        }
        return ok;
    }

    private static int texture(int id, int w, int h, int format) {
        if (id < 0) {
            id = GlStateManager._genTexture();
            GlStateManager._bindTexture(id);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
        }
        GlStateManager._bindTexture(id);
        GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, format, w, h, 0, GL11.GL_RGB, GL11.GL_FLOAT, null);
        return id;
    }

    /** Раз в секунду (тик слоя). */
    static void second() {
        GPU.second();
        PASS.second();
        PYRAMID.second();
        OUT.second();
    }

    /** Для строки сценария: время проходов свечения и уровни. */
    static String describe() {
        return String.format(Locale.ROOT, "bloom %s %.3f ms (longest %.3f, %d frames; glow pass %.3f, pyramid %.3f, out %.3f; %dx%d, glow level %d, levels %d-%d)",
                quality(), GPU.lastMean, GPU.lastMax, GPU.lastFrames, PASS.lastMean, PYRAMID.lastMean, OUT.lastMean, frameW, frameH, frameG, lastFirst, lastLast);
    }
}
