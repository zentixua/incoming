package ua.zentix.incoming.client.sound;

/**
 * Как далёкий взрыв доходит до уха под открытым небом — ISO 9613-2 в двух полосах, без Minecraft (проверяется
 * юнит-тестами). Низы (63 Гц) несут сам раскат и решают, слышно ли его; верха (2 кГц) — насколько он глухой.
 * <ul>
 * <li>Преграда (холм, дома): дифракция через одну кромку, Dz = 10·lg(3 + 20·z/λ) (ф. 14), не больше 20 дБ; z — разность
 * хода через кромку ({@code Sightline}). Кромка заменяет эффект земли: Abar = Dz − Agr, не меньше нуля (ф. 12).</li>
 * <li>Земля (табл. 3): у источника и у слушателя на 63 Гц As = Ar = −1,5 дБ, на 2 кГц −1,5·(1 − G); посередине
 * Am = −3q и −3q·(1 − Gm). Твёрдая земля отражает — громче; рыхлая гасит верха.</li>
 * <li>Днём земля греет воздух, звук загибает вверх: лучи от источника на высоте hs к уху на высоте hr касаются земли
 * на r = √(2R)·(√hs + √hr) (R — радиус кривизны луча), дальше — зона тени, к 4r до 20 дБ верхов и 10 дБ низов. R
 * такой, что у взрыва на 2 м и уха на 1,7 м r = 1 км (у земли воздух холодает почти как в сухоадиабатическом
 * градиенте, ~0,9 °C на 100 м); подрыв на высоте 600 м уводит тень за 9 км. Ночью инверсия — тени нет.</li>
 * <li>Дождь шумит и прячет далёкое: +3 дБ, гроза — ещё +3.</li>
 * </ul>
 * Запас слышимости M = 20·lg(R0/d) − потери низов; R0 — докуда взрыв слышно в обычных условиях, отражение от земли
 * на низах в нём уже есть. Громкость в игре — от громкости ближней модели на её краю ({@link #NEAR}) вниз на четверть
 * децибел запаса (10^(ΔM/40): слух сжимает громкость), к нулю — плавно за последние {@link #FADE} дБ. На краю при
 * открытом пути ночью без дождя громкость та же, что у ближней модели: ступеньки нет.
 */
public final class Outdoor {
    /** Край ближней модели звука и картинки взрыва, блоков (= метров): дальше считается здесь. */
    public static final double NEAR = 640;
    /** Длины волн полос, м: низы 63 Гц и верха 2 кГц при 343 м/с. */
    public static final double LOW = 343.0 / 63, HIGH = 343.0 / 2000;
    /** Дифракция через одну кромку — не больше, дБ (ISO 9613-2, п. 7.4). */
    static final double BARRIER_CAP = 20;
    /**
     * Зона тени днём: √(2R) — r = √(2R)·(√hs + √hr) у источника на 2 м и уха на 1,7 м ровно 1 км, м^½; глубину
     * набирает к {@link #SHADOW_DEEP} r; глубина у верхов и низов, дБ.
     */
    static final double SHADOW_ROOT = 1000 / (Math.sqrt(2) + Math.sqrt(1.7)), SHADOW_DEEP = 4, SHADOW_HIGH = 20, SHADOW_LOW = 10;
    /** Дождь и гроза прячут далёкое, дБ на уровень 0..1. */
    static final double RAIN = 3, THUNDER = 3;
    /** Последние децибелы запаса — громкость плавно к нулю. */
    static final double FADE = 6;

    private Outdoor() {}

    /**
     * Путь звука от взрыва до уха.
     *
     * @param d       расстояние, блоков (= метров)
     * @param z       разность хода через кромку, м; 0 — прямая видимость
     * @param hs      высота источника над землёй, м
     * @param hr      высота уха над землёй, м
     * @param gs      пористость земли у источника, 0..1 ({@code GroundMaterial.porosity})
     * @param gr      пористость земли у слушателя
     * @param day     0 — ночь (инверсия), 1 — солнце высоко ({@link #day})
     * @param rain    дождь 0..1
     * @param thunder гроза 0..1
     */
    public record Path(double d, double z, double hs, double hr, double gs, double gr, double day, double rain, double thunder) {}

    /**
     * Что дошло: громкость в игре и верха (доля, без воздуха) и слагаемые для лога, дБ: преграда и тень — на низах,
     * верха — на сколько они глуше низов, из них от земли — {@code ground}.
     */
    public record Heard(float volume, float highs, double margin, double barrier, double shadow, double masking, double ground) {}

    /** Дифракция через одну кромку в полосе с длиной волны lambda, дБ; путь открыт (z ≤ 0) — 0. */
    static double barrier(double z, double lambda) {
        return z <= 0 ? 0 : Math.min(BARRIER_CAP, 10 * Math.log10(3 + 20 * z / lambda));
    }

    /** Доля середины пути, где работает земля (табл. 3, прим.): ближе 30·(hs + hr) середины нет. */
    static double q(double dp, double hs, double hr) {
        double near = 30 * (hs + hr);
        return dp <= near ? 0 : 1 - near / dp;
    }

    /** Эффект земли Agr = As + Ar + Am, дБ: отрицательный — отражение от земли добавляет. */
    static double ground(boolean high, Path p) {
        double q = q(p.d, p.hs, p.hr);
        if (!high) return -1.5 - 1.5 - 3 * q;
        double gm = (p.gs + p.gr) / 2;
        return -1.5 * (1 - p.gs) - 1.5 * (1 - p.gr) - 3 * q * (1 - gm);
    }

    /** Преграда сверх эффекта земли (Abar = Dz − Agr ≥ 0), дБ. */
    static double obstacle(boolean high, Path p) {
        return p.z > 0 ? Math.max(0, barrier(p.z, high ? HIGH : LOW) - ground(high, p)) : 0;
    }

    /** Где днём начинается зона тени, блоков: там луч от источника к уху касается земли. */
    static double shadowFrom(double hs, double hr) {
        return SHADOW_ROOT * (Math.sqrt(Math.max(0, hs)) + Math.sqrt(Math.max(0, hr)));
    }

    /** Зона тени днём, дБ: звук загибает вверх, и дальше касания луча с землёй у земли его всё меньше. */
    static double shadow(boolean high, Path p) {
        double from = shadowFrom(p.hs, p.hr);
        return p.day * smoothstep(from, SHADOW_DEEP * from, p.d) * (high ? SHADOW_HIGH : SHADOW_LOW);
    }

    /** Дождь и гроза, дБ. */
    static double masking(Path p) {
        return RAIN * p.rain + THUNDER * p.thunder;
    }

    /** Насколько сейчас день для рефракции: по синусу высоты солнца — у горизонта и ниже 0, к 30° — 1. */
    public static double day(double sunSine) {
        return smoothstep(0, 0.5, sunSine);
    }

    /** Потери низов по дороге сверх разлёта, дБ: преграда, зона тени, дождь (земля на низах уже в R0 и в давлении). */
    public static double losses(Path p) {
        return pressureLoss(p) + masking(p);
    }

    /** Сколько по дороге потеряло само давление на низах, дБ: преграда и зона тени (дождь прячет звук, давление — нет). */
    public static double pressureLoss(Path p) {
        return obstacle(false, p) + shadow(false, p);
    }

    /** Запас слышимости, дБ: земля на низах уже в R0. */
    public static double margin(double r0, Path p) {
        return 20 * Math.log10(r0 / p.d) - losses(p);
    }

    /**
     * Громкость в игре.
     *
     * @param r0   докуда слышно в обычных условиях, блоков
     * @param v640 громкость ближней модели на её краю ({@link #NEAR})
     */
    public static float volume(double r0, double v640, Path p) {
        double m = margin(r0, p);
        double v = v640 * Math.pow(10, (m - 20 * Math.log10(r0 / NEAR)) / 40) * smoothstep(0, FADE, m);
        return (float) Math.min(1, v);
    }

    /**
     * Громкость в игре по пиковому давлению у уха {@code pa} (на открытом пути, с отражением от земли — как у модели
     * волны): громкость {@code full} давления — 1, тише — на половину децибел давления, как у ближней модели обычных
     * взрывов ({@link BlastMix}: −3 дБ на удвоение) и у {@link #volume}, потери по дороге — так же. Угасания у порога
     * слышимости нет: так считается взрыв, который слышен много дальше, чем идёт мир (ядерный подрыв).
     */
    public static float pressureVolume(double pa, double full, Path p) {
        return (float) Math.min(1, Math.sqrt(Math.max(0, pa) / full) * Math.pow(10, -losses(p) / 40));
    }

    /** Доля верхов против низов (дождь глушит обе полосы одинаково — не в счёт); воздух — отдельно. */
    public static float highs(Path p) {
        double extra = ground(true, p) + obstacle(true, p) + shadow(true, p) - ground(false, p) - obstacle(false, p) - shadow(false, p);
        return (float) Math.min(1, Math.pow(10, -extra / 20));
    }

    /**
     * Доля верхов против низов, которую по дороге потеряло само давление ударной волны: преграда и зона тени, без
     * земли. У волны отражение от земли — уже в её давлении (у земли — одна волна с отражённой, ножка Маха), а не
     * встречная интерференция звука над мягкой землёй, как у {@link #highs}.
     */
    public static float pressureHighs(Path p) {
        double extra = obstacle(true, p) + shadow(true, p) - obstacle(false, p) - shadow(false, p);
        return (float) Math.min(1, Math.pow(10, -extra / 20));
    }

    public static Heard hear(double r0, double v640, Path p) {
        return new Heard(volume(r0, v640, p), highs(p), margin(r0, p), obstacle(false, p), shadow(false, p), masking(p),
                ground(true, p) - ground(false, p));
    }

    private static double smoothstep(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }
}
