package ua.zentix.incoming.sound;

import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * Как звук мотора доходит до уха под открытым небом и слышно ли его — ISO 9613-2:1996 по октавам 63 Гц – 8 кГц, без
 * Minecraft, кроме погоды ({@link Weather#of}); общий для сервера (кому слать путь снаряда, {@code strike.Hearing}) и
 * клиента (громкость слоёв), поэтому дальности у них одни и те же.
 * <ul>
 * <li>Уровень у уха в полосе: L = L<sub>W</sub> − A<sub>div</sub> − A<sub>atm</sub> − A<sub>gr</sub> − тень (ф. 3, 4):
 * разлёт 20·lg d + 11 (ф. 7), воздух α·d ({@link Air}, по влажности воздуха мира у земли), земля (табл. 3), днём —
 * зона тени (рефракция вверх).</li>
 * <li>Земля — луг ({@link #MEADOW}, G = 1: трава, лес, пашня — почти весь мир Minecraft); у источника, у слушателя
 * и посередине — формулы a′–d′ табл. 3 по высотам.</li>
 * <li>Зона тени днём — тот же закон, что у далёких взрывов ({@code client.sound.Outdoor}): лучи касаются земли на
 * r = √(2R)·(√hs + √hr), к 4r глубина 10 дБ на 63 Гц и 20 дБ с 2 кГц (между — по октавам ровно).</li>
 * <li>Слышно, пока звук у уха хоть в одной октаве громче фона в ней же ({@link #margin}): слух маскирует звук
 * шумом только в своей полосе (критические полосы Флетчера, 1940; уже октавы выше 500 Гц — по октаве порог
 * осторожный), поэтому тональный мотор слышно и тогда, когда его A-уровень ниже A-уровня фона. Фон тихой загородной
 * местности ночью 34 дБА, днём 40 дБА (ANSI/ASA S12.9-2013 ч. 3, табл. C.1, кат. 6 «очень тихая загородная», по памяти —
 * ⚠ сверить; загородный дом ночью 30–35 дБА — SANS 10103 по отчёту Eskom, тихая местность ночью 32–35 дБА — Alaska DNR),
 * по октавам — поровну A-энергии от 125 Гц до 4 кГц, на 63 Гц и 8 кГц на 10 дБ меньше (оценка: листва, насекомые,
 * далёкая дорога); дождь и гроза — +3 дБ каждый, как у далёких взрывов.</li>
 * </ul>
 * Преграды (холм, дом) здесь не считаются: сервер их не знает, а клиент глушит путь сам (заслон, {@code SoundFilters}),
 * — так слышимое клиентом всегда внутри того, что ему шлёт сервер.
 */
public final class Propagation {
    /** Середины октав, Гц. */
    public static final double[] OCTAVES = {63, 125, 250, 500, 1000, 2000, 4000, 8000};
    public static final int BANDS = OCTAVES.length;
    /** Поправка A по октавам, дБ (IEC 61672-1, табл. 3). */
    static final double[] A_WEIGHT = {-26.2, -16.1, -8.6, -3.2, 0, 1.2, 1.0, -1.1};
    /**
     * Поглощение воздухом по октавам, дБ/м, по влажности с шагом {@link #HUMIDITY_STEPS} (1 %): строка — влажность
     * 0..1, у пути — ближайшая ({@link #alpha}); во влажности мира у земли (70–100 %) — в пределах 1 % формулы.
     */
    private static final int HUMIDITY_STEPS = 100;
    private static final double[][] ALPHA = new double[HUMIDITY_STEPS + 1][BANDS];
    /** Фон по октавам против всего фона, дБ (A): 125 Гц – 4 кГц поровну, края на 10 дБ тише. */
    static final double[] BACKGROUND = new double[BANDS];
    /** Земля по ISO 9613-2: G = 1 — пористая (трава, лес, пашня). */
    public static final double MEADOW = 1;
    /** Ухо над землёй, м (как у далёких взрывов). */
    public static final double EAR = 1.7;
    /** Фон ночью и днём, дБА; дождь и гроза поднимают его, дБ на уровень 0..1 (⚠ оценка: замеров фона под эти условия нет). */
    public static final double NIGHT = 34, DAY = 40, RAIN = 3, THUNDER = 3;
    /** Последние децибелы над фоном звук плавно тонет в нём (частичная маскировка, подача; как у далёких взрывов). */
    public static final double FADE = 6;
    /**
     * Зона тени днём: √(2R), м^½ — касание луча у источника на 2 м и уха на 1,7 м ровно в 1 км (у земли воздух
     * холодает почти как в сухоадиабатическом градиенте, ~0,9 °C на 100 м); глубину набирает к {@link #SHADOW_DEEP}
     * от касания; глубина у низов (63 Гц) и верхов (с 2 кГц), дБ. ⚠ Оценка: профиля c(z) и замеров нет, ISO 9613-2 этого
     * не даёт.
     */
    public static final double SHADOW_ROOT = 1000 / (Math.sqrt(2) + Math.sqrt(1.7)), SHADOW_DEEP = 4, SHADOW_LOW = 10, SHADOW_HIGH = 20;

    static {
        double sum = 0;
        for (int h = 0; h <= HUMIDITY_STEPS; h++) {
            // влажность 0 у ISO 9613-1 вне области формулы (частоты релаксации кислорода — к нулю): от 1 %
            for (int b = 0; b < BANDS; b++) ALPHA[h][b] = Air.alphaAt(OCTAVES[b], Math.max(1, h) / (double) HUMIDITY_STEPS);
        }
        for (int b = 0; b < BANDS; b++) {
            BACKGROUND[b] = b == 0 || b == BANDS - 1 ? -10 : 0;
            sum += Math.pow(10, BACKGROUND[b] / 10);
        }
        for (int b = 0; b < BANDS; b++) BACKGROUND[b] -= 10 * Math.log10(sum);
    }

    private Propagation() {}

    /**
     * Погода для рефракции, фона и поглощения воздухом.
     *
     * @param day      0 — ночь, 1 — солнце высоко ({@link #day}): фон днём громче
     * @param rain     дождь 0..1
     * @param thunder  гроза 0..1
     * @param shadow   0..1 — насколько воздух преломляет звук вверх, в зону тени ({@link #refraction})
     * @param humidity относительная влажность у земли 0..1 ({@code util.Air#surfaceHumidity}): воздух ест верха
     */
    public record Weather(double day, double rain, double thunder, double shadow, double humidity) {
        public static final Weather NIGHT = new Weather(0, 0, 0, 0), NOON = new Weather(1, 0, 0, 1);

        /** Погода с влажностью ясного дня у земли ({@code util.Air#SURFACE_CLEAR}). */
        public Weather(double day, double rain, double thunder, double shadow) {
            this(day, rain, thunder, shadow, ua.zentix.incoming.util.Air.SURFACE_CLEAR);
        }

        /**
         * Погода мира сейчас: день — по высоте солнца (без неба или с застывшим временем — ночь), тень — по классу
         * устойчивости, влажность — у земли, всё — из воздуха мира ({@link ua.zentix.incoming.util.Air#of(Level)}, тот
         * же у дыма и полёта).
         */
        public static Weather of(Level level) {
            ua.zentix.incoming.util.Air air = ua.zentix.incoming.util.Air.of(level);
            // без неба (Незер, Энд) воздух не греется солнцем и не остывает ночью: тени нет, как прежде
            return new Weather(Propagation.day(ua.zentix.incoming.util.Air.sun(level)), level.getRainLevel(1), level.getThunderLevel(1),
                    level.dimensionType().hasSkyLight() ? refraction(air.stability()) : 0, air.surfaceHumidity());
        }

        /** Та же погода в условиях записей ({@link Air#HUMIDITY}): сколько воздуха уже в дальней записи. */
        public Weather recorded() {
            return new Weather(day, rain, thunder, shadow, Air.HUMIDITY / 100);
        }
    }

    /** Поглощение воздухом в октаве b при влажности {@code humidity} (0..1), дБ/м. */
    public static double alpha(int b, double humidity) {
        return ALPHA[(int) Math.round(Math.max(0, Math.min(1, humidity)) * HUMIDITY_STEPS)][b];
    }

    /**
     * Насколько звук уходит вверх, в зону тени, по классу устойчивости Паскуилла: пока температура падает с высотой
     * (неустойчиво и нейтрально, A–D: стандартная атмосфера — 6,5 K/км), скорость звука наверху меньше, и лучи
     * загибаются вверх — тень полная; при инверсии (E–F, ясная ночь) лучи загибаются вниз — тени нет; между D и E —
     * плавно. Ветер (тень против ветра, её нет по ветру) не учтён.
     */
    public static double refraction(double stability) {
        return Math.max(0, Math.min(1, ua.zentix.incoming.util.Stability.E - stability));
    }

    /**
     * Путь от источника до уха.
     *
     * @param d  расстояние, м (= блоков)
     * @param hs высота источника над землёй, м
     * @param hr высота уха над землёй, м
     * @param g  пористость земли 0..1
     */
    public record Path(double d, double hs, double hr, double g, Weather weather) {
        /** Путь над лугом: источник на высоте {@code hs} над землёй под слушателем, ухо на {@link #EAR}. */
        public static Path over(double d, double hs, Weather weather) {
            return new Path(d, Math.max(0, hs), EAR, MEADOW, weather);
        }
    }

    /** Насколько сейчас день для фона: по синусу высоты солнца — у горизонта и ниже 0, к 30° — 1. */
    public static double day(double sunSine) {
        return smoothstep(0, 0.5, sunSine);
    }

    /** Фон, дБА. */
    public static double background(Weather w) {
        return NIGHT + (DAY - NIGHT) * w.day + RAIN * w.rain + THUNDER * w.thunder;
    }

    /** Фон в октаве b, дБ (A). */
    public static double background(int b, Weather w) {
        return background(w) + BACKGROUND[b];
    }

    /** Где днём начинается зона тени, м: там луч от источника к уху касается земли. */
    public static double shadowFrom(double hs, double hr) {
        return SHADOW_ROOT * (Math.sqrt(Math.max(0, hs)) + Math.sqrt(Math.max(0, hr)));
    }

    /** Глубина зоны тени на частоте f, дБ: 10 дБ на 63 Гц, 20 с 2 кГц, между — ровно по октавам. */
    public static double shadow(double f, double d, double hs, double hr, double day) {
        double from = shadowFrom(hs, hr);
        double k = Math.max(0, Math.min(1, Math.log(f / 63) / Math.log(2000.0 / 63)));
        return day * smoothstep(from, SHADOW_DEEP * from, d) * (SHADOW_LOW + (SHADOW_HIGH - SHADOW_LOW) * k);
    }

    /**
     * Эффект земли в октаве b, дБ (ISO 9613-2, табл. 3): отрицательный — отражение добавляет.
     *
     * @param dp расстояние по земле, м
     */
    static double ground(int b, double dp, double hs, double hr, double g) {
        double q = dp <= 30 * (hs + hr) ? 0 : 1 - 30 * (hs + hr) / dp;
        if (b == 0) return -1.5 - 1.5 - 3 * q;
        return region(b, hs, dp, g) + region(b, hr, dp, g) - 3 * q * (1 - g);
    }

    /** Область у источника или у слушателя на высоте h: As или Ar (табл. 3). */
    private static double region(int b, double h, double dp, double g) {
        double near = 1 - Math.exp(-dp / 50);
        double k = switch (b) {
            case 1 -> 1.5 + 3.0 * Math.exp(-0.12 * sq(h - 5)) * near + 5.7 * Math.exp(-0.09 * h * h) * (1 - Math.exp(-2.8e-6 * dp * dp));
            case 2 -> 1.5 + 8.6 * Math.exp(-0.09 * h * h) * near;
            case 3 -> 1.5 + 14.0 * Math.exp(-0.46 * h * h) * near;
            case 4 -> 1.5 + 5.0 * Math.exp(-0.9 * h * h) * near;
            default -> 0;
        };
        return b <= 4 ? -1.5 + g * k : -1.5 * (1 - g);
    }

    /** Больше этого путь звук не прибавляет, дБ: земля на 63 Гц (−6 дБ ослабления) и поправка A (+1,2 дБ на 2 кГц). */
    static final double MAX_GAIN = 6 + 1.2;

    /** Разлёт сферой, дБ (ф. 7). */
    public static double divergence(double d) {
        return 20 * Math.log10(Math.max(1, d)) + 11;
    }

    /** Фон в самой тихой октаве, дБ (A). */
    static double quietest(Weather w) {
        double min = Double.POSITIVE_INFINITY;
        for (double b : BACKGROUND) min = Math.min(min, b);
        return background(w) + min;
    }

    /** Ослабление в октаве b по пути (разлёт, воздух, земля, тень), дБ. */
    public static double attenuation(int b, Path p) {
        double d = Math.max(1, p.d);
        double dp = Math.sqrt(Math.max(1, d * d - sq(p.hs - p.hr)));
        return divergence(d) + alpha(b, p.weather.humidity) * d + ground(b, dp, p.hs, p.hr, p.g)
                + shadow(OCTAVES[b], d, p.hs, p.hr, p.weather.shadow);
    }

    /**
     * Уровень у уха от источника мощностью {@code lw} с октавным спектром {@code shape}: всего (A) и в каждой октаве
     * (A, в {@code out}, может быть {@code null}), дБ.
     */
    public static double level(double lw, Spectrum shape, Path p, double[] out) {
        double sum = 0;
        for (int b = 0; b < BANDS; b++) {
            double l = lw + shape.z(b) + A_WEIGHT[b] - attenuation(b, p);
            if (out != null) out[b] = l;
            sum += Math.pow(10, l / 10);
        }
        return 10 * Math.log10(Math.max(sum, 1e-30));
    }

    /**
     * Насколько звук с октавами {@code bands} (дБ, A — как пишет {@link #level}) громче фона в самой выгодной октаве, дБ
     * (меньше нуля — не слышно).
     */
    public static double margin(double[] bands, Weather w) {
        double best = Double.NEGATIVE_INFINITY;
        for (int b = 0; b < BANDS; b++) best = Math.max(best, bands[b] - background(b, w));
        return best;
    }

    /**
     * Докуда слышно источник, м: где он у уха во всех октавах тонет в фоне (не дальше {@code limit}). Уровень с
     * расстоянием только падает (земля и тень меняются медленнее разлёта), поэтому — делением пополам по логарифму
     * расстояния.
     */
    public static double range(double lw, Spectrum shape, double hs, Weather w, double limit) {
        double[] bands = new double[BANDS];
        if (audible(lw, shape, Path.over(limit, hs, w), bands)) return limit;
        double lo = 1, hi = limit;
        if (!audible(lw, shape, Path.over(lo, hs, w), bands)) return 0;
        for (int i = 0; i < 40; i++) {
            double mid = Math.sqrt(lo * hi);
            if (audible(lw, shape, Path.over(mid, hs, w), bands)) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    private static boolean audible(double lw, Spectrum shape, Path p, double[] bands) {
        level(lw, shape, p, bands);
        return margin(bands, p.weather) > 0;
    }

    /** Поправка A октавы b, дБ. */
    public static double aWeight(int b) {
        return A_WEIGHT[b];
    }

    /** Плавная ступенька 0→1 между a и b. */
    public static double smoothstep(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    private static double sq(double x) {
        return x * x;
    }
}
