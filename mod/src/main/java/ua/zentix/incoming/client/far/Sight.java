package ua.zentix.incoming.client.far;

import ua.zentix.incoming.util.Visibility;

/**
 * Видимость вдали без Minecraft: дымка по Кошмидеру и яркие точки с сохранением светового потока.
 * <ul>
 * <li>Воздух ослабляет свет как exp(−σ·d), σ = 3,912 / V, где V — дальность видимости: на ней контраст тёмного
 * против неба падает до порога глаза 0,02 (Кошмидер, 1924). Коэффициенты дымки, дождя и грозы складываются.</li>
 * <li>Тёмное и яркое вдали: L = L₀·t + Lг·(1 − t), где Lг — дымка у горизонта, а она и есть небо за ним. Поэтому всё
 * рисуется своим светом с непрозрачностью × t поверх того, что за ним ({@link FarSprites}), а не смешивается с цветом
 * тумана: ванильный туман в дождь синеет, и дым вдали был бы синим.</li>
 * <li>Всё — настоящего углового размера, без наименьшего: мельче пикселя тело рисуется пятном {@link #MIN_PIXELS} с той же
 * долей закрытого неба и тем же светом ({@link #body}, {@link #light}); ярче белого экрана — бликом и вуалью.</li>
 * </ul>
 */
public final class Sight {
    /** −ln 0,02: порог контраста глаза, по которому определена дальность видимости. */
    public static final double KOSCHMIEDER = 3.912;
    /** Дальность видимости, блоков (= метров): ясно, дождь, гроза — та же, что у света вспышки ({@link Visibility}). */
    public static final double CLEAR = Visibility.CLEAR, RAIN = Visibility.RAIN, THUNDER = Visibility.THUNDER;
    /**
     * Мельче этого пятно не рисуется: полтора пикселя в поперечнике — свет и заслон те же, что у настоящего угла, только
     * бледнее во столько же раз (как звёзды в движках); пятно в пиксель мерцало бы, переходя между пикселями.
     */
    public static final double MIN_PIXELS = 1.5;
    /** Доля света меньше этой — не видно (порог Кошмидера). */
    public static final double THRESHOLD = 0.02;
    /** Привыкание глаза к свету неба — степень ({@link #adapted}). */
    public static final double ADAPT = 4;
    /**
     * Блик ({@link #light}): ширина ореола растёт с логарифмом пересвета — как на снимке: пересвет 10 — 2,4 ядра. Пик —
     * доля SCATTER яркости тела, не больше HALO от белого: у края диска рассеянный свет — доли процента яркости источника
     * (функция рассеяния CIE: L/Lист ≈ 10·π·α²/θ², углы в градусах), на снимке — до процента. Поэтому днём шар в 2–8 раз
     * ярче неба остаётся оранжевым телом без ореола, вспышка чуть светится, а ночью блик упирается в HALO.
     */
    public static final double GLARE = 0.6, HALO = 0.6, SCATTER = 0.01;
    /**
     * Вуаль ({@link #light}): постоянная Стайлза–Холладея с порогом «заметно над фоном», подобрана по оценкам: ночью
     * за 2 км у шахеда ~10°, за 8 км ~1,5°, у ракеты вдвое шире; днём вуаль не шире блика (её нет). Не шире VEIL_MAX
     * рад (10°); пик — VEIL_PEAK от белого: у края вуаль — вдвое ярче ночного фона, как и задумано порогом.
     */
    public static final double VEIL = 0.7, VEIL_MAX = 0.17, VEIL_PEAK = 0.22;
    /** Ядро с таким пересветом — белое: насыщенный цвет яркого уходит в белый, цвет остаётся в блике (как в кино и AgX). */
    public static final double WHITE = 32;

    private Sight() {}

    /** Дальность видимости по погоде мира: дождь и гроза 0..1, как у {@code Level.getRainLevel}/{@code getThunderLevel}. */
    public static double range(float rain, float thunder) {
        double sigma = 1 / CLEAR + rain * (1 / RAIN - 1 / CLEAR) + thunder * (1 / THUNDER - 1 / RAIN);
        return 1 / sigma;
    }

    /** Доля света (и собственного контраста тёмного), дошедшая через d блоков воздуха у уровня моря вдоль земли. */
    public static double transmittance(double d, double range) {
        return Math.exp(-KOSCHMIEDER * d / range);
    }

    /**
     * Ослабление чистого воздуха (рассеяние Рэлея) у уровня моря, 1/м: 550 нм (Bucholtz 1995) — без дымки видно на 340 км.
     * Остальное ослабление по дальности видимости — дымка (аэрозоль и капли).
     */
    public static final double RAYLEIGH = 1.16e-5;
    /**
     * Масштабы высоты, м: дымка ясного дня — в нижних километрах (пограничный слой; Elterman 1968, как у неба в движках —
     * Nishita 1993), чистый воздух — плотность атмосферы. Дальность видимости — у земли: на шапку гриба в 5 км луч идёт
     * почти над всей дымкой, и равная по всей высоте дымка прятала бы её втрое сильнее настоящей.
     */
    public static final double HAZE_HEIGHT = 1200, AIR_HEIGHT = 8000;

    /**
     * Воздух от глаза: ослабление у глаза, 1/м, — дымка ясного дня {@code haze}, чистый воздух {@code clean} и погода
     * {@code weather} — дождь и гроза сверх ясного дня ({@link #at}).
     */
    public record Air(double haze, double clean, double weather) {
        /**
         * У глаза на высоте {@code eye} м над уровнем моря (ниже — как на нём): у моря сумма — 3,912 / range. Погода по
         * высоте не редеет: капли идут от облаков, а выше их низа луч идёт в самих облаках — шапка над ними в дождь не видна.
         */
        public static Air at(double eye, double range) {
            double h = Math.max(0, eye), clear = KOSCHMIEDER / CLEAR, air = Math.min(RAYLEIGH, clear);
            return new Air((clear - air) * Math.exp(-h / HAZE_HEIGHT), air * Math.exp(-h / AIR_HEIGHT),
                    Math.max(0, KOSCHMIEDER / range - clear));
        }

        /**
         * Доля света, дошедшая через d м до точки на {@code rise} м выше глаза (ниже — меньше нуля): дымка и чистый воздух
         * редеют с высотой ({@link Sight#HAZE_HEIGHT}, {@link Sight#AIR_HEIGHT}), погода — нет; вдоль земли у моря — как
         * {@link Sight#transmittance(double, double)}.
         */
        public double transmittance(double d, double rise) {
            return Math.exp(-d * (haze * along(rise / HAZE_HEIGHT) + clean * along(rise / AIR_HEIGHT) + weather));
        }
    }

    /** Средняя по отрезку доля ослабления у его начала, когда отрезок поднимается на x масштабов высоты: (1 − e^−x) / x. */
    static double along(double x) {
        return Math.abs(x) < 1e-4 ? 1 - 0.5 * x : -Math.expm1(-x) / x;
    }

    /**
     * Как рисовать яркую или маленькую точку: свет — яркость × телесный угол — сохраняется.
     * Крупнее {@link #MIN_PIXELS} и не ярче белого — как есть; мельче — точкой этого размера, бледнее во столько же раз;
     * ярче белого экрана — шире, как блик в глазу или на снимке, а не ярче белого.
     *
     * @param radius     настоящий радиус, блоков
     * @param brightness яркость против белого экрана (тёмному корпусу — 1: тогда это его доля в пикселе)
     * @param t          доля света, дошедшая через воздух ({@link #transmittance}); тёмному — 1
     * @param d          дальность, блоков
     * @param pixel      угол одного пикселя экрана, рад
     * @param out        {радиус для рисования на дальности d, блоков; непрозрачность 0..1}
     */
    public static void point(double radius, double brightness, double t, double d, double pixel, double[] out) {
        double r = radius / Math.max(d, 1e-6);
        double flux = brightness * t * r * r;
        double drawn = Math.max(Math.max(r, 0.5 * MIN_PIXELS * pixel), Math.sqrt(flux));
        out[0] = drawn * d;
        out[1] = Math.min(1, flux / (drawn * drawn));
    }

    /**
     * Тёмное тело радиуса radius на дальности d (корпус снаряда): круг настоящего углового размера; мельче пятна
     * {@link #MIN_PIXELS} — пятно, бледнее во столько раз, во сколько оно больше тела (закрыто то же небо).
     *
     * @param out {радиус круга для рисования на дальности d, блоков; непрозрачность 0..1}
     */
    public static void body(double radius, double d, double pixel, double[] out) {
        double angle = radius / Math.max(d, 1e-6), drawn = Math.max(angle, 0.5 * MIN_PIXELS * pixel);
        out[0] = drawn * d;
        out[1] = (angle / drawn) * (angle / drawn);
    }

    /**
     * Яркое тело (вспышка, огненный шар, факел) радиуса radius на дальности d, яркостью seen против белого экрана
     * (с привыканием глаза и воздухом: {@link #adapted} × t).
     * <ul>
     * <li>Ядро — круг настоящего размера, мельче пятна {@link #MIN_PIXELS} — пятно с тем же светом
     * ({@code seen·угол²}), бледнее; ярче белого экрана — насыщено и к белому.</li>
     * <li>Блик — ореол на снимке и в глазу: радиус ядра × (1 + {@link #GLARE}·ln(1 + seen)).</li>
     * <li>Вуаль — свет точки, рассеянный в глазу и дымке (Стайлз–Холладей: яркость вуали ∝ E/θ², E — освещённость
     * у глаза ∝ seen·угол²): над фоном она видна до угла {@code угол·√(VEIL·seen)}. Днём фон — небо, и это меньше двух
     * радиусов ядра (вуали нет); ночью — градусы, не шире {@link #VEIL_MAX}.</li>
     * </ul>
     *
     * @param out {радиус ядра, блоков; доля круга ядра, которую закрывает само тело; яркость ядра на экране против белого
     *            (больше 1 — пересвет); радиус блика; радиус вуали — 0, если она не шире блика} — на дальности d
     */
    public static void light(double radius, double seen, double d, double pixel, double[] out) {
        double angle = radius / Math.max(d, 1e-6), drawn = Math.max(angle, 0.5 * MIN_PIXELS * pixel), cover = (angle / drawn) * (angle / drawn);
        double core = drawn * d, glare = core * (1 + GLARE * Math.log1p(Math.max(0, seen)));
        double veil = Math.min(angle * Math.sqrt(VEIL * Math.max(0, seen)), VEIL_MAX) * d;
        out[0] = core;
        out[1] = cover;
        out[2] = seen * cover;
        out[3] = glare;
        out[4] = veil > glare ? veil : 0;
    }

    /**
     * Во сколько раз ярче кажется свет при свете неба ambient ({@link FarView#ambient}: 1 днём, 0,17 ночью, 0,12 ночью
     * в грозу): глаз и экспозиция привыкают к фону. Настоящая разница неба днём и ночью — 10⁶–10⁷ раз; степень
     * {@link #ADAPT} даёт ночью ~1200: вспышка за километры слепит и светит в небе, но не заливает весь экран.
     */
    public static double adapted(double brightness, double ambient) {
        return brightness / Math.pow(ambient, ADAPT);
    }

    /**
     * Привыкание глаза кадра — одно на всё, что кадр рисует светом обычного оружия (тело огня, блик и вуаль, свет на дыме,
     * частицах и мире): глаз и экспозиция привыкают к свету вокруг себя — к свету неба ({@link #adapted}: ambient^{@value #ADAPT}
     * против дня) и к свету огня у глаза fire (освещённость белой стены у глаза от огненных шаров, факелов и пожаров, белая
     * стена в полдень — 1) вместе. Без огня — {@link #adapted}. Во сколько раз ярче кажется свет яркости 1.
     */
    public static double exposure(double ambient, double fire) {
        return 1 / (Math.pow(ambient, ADAPT) + Math.max(0, fire));
    }

    /**
     * Свет на экране самосветящегося тела цвета rgb (sRGB, наибольший канал — 255) яркостью lum против белого экрана (с
     * привыканием глаза): во всю силу, если ярче белого, и пересвет белит цвет к {@link #WHITE} (как ядро {@link #light});
     * тусклее — темнее в линейном свете, а на экран — в sRGB (тело в 0,1 белого — 0,35 экрана, не 0,1); × share. sRGB 0..1.
     */
    public static float[] onScreen(int rgb, double lum, float share) {
        return onScreen((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, lum, share);
    }

    /** То же для цвета (r, g, b) sRGB 0..1. */
    public static float[] onScreen(float r, float g, float b, double lum, float share) {
        double k = Math.min(1, Math.max(0, lum)), white = lum > 1 ? Math.min(1, Math.log(lum) / Math.log(WHITE)) : 0;
        float[] in = {r, g, b}, out = new float[3];
        for (int i = 0; i < 3; i++) {
            double c = Math.max(0, Math.min(1, in[i])), l = c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
            out[i] = (float) ua.zentix.incoming.client.fx.plume.FlameModel.srgb((l + (1 - l) * white) * k) * share;
        }
        return out;
    }
}
