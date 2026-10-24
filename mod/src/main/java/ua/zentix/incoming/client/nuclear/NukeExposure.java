package ua.zentix.incoming.client.nuclear;

/**
 * Экспозиция огненного шара ядерки и кадра во вспышку — подача, а не физика: яркость шара (чёрное тело его температуры),
 * его размер, время, световой импульс и его энергия — модели ({@code FireballModel}, {@code MushroomModel}); здесь —
 * только то, как кадр их показывает.
 *
 * <p><b>Шар — как обычный</b> ({@code tools/bake_fx/pack.py}: {@code film_exposure}, {@code film}; #426): экспозиция по
 * огню и плёночная кривая по каналам. В мере белой стены шар в сотни и тысячи раз ярче белого (у 15 кт через 3 с ядро
 * ~300, через секунду ~5 000), и кадр срезал его в ровный диск без складок и узора. У обычного шара огонь — один кадр
 * экспозиции на всё горение; у ядерки яркость ядра за 0,5–10 с падает в ~4 000 раз, и одна экспозиция дала бы белый диск
 * в начале или чёрный в конце. Поэтому камера выставлена по огню и следует за ним не вполне ({@link #gain}): видимая
 * поверхность шара (температура края шапки) яркостью L (полуденных белых стен, без привыкания глаза) — на кривой K·L^β
 * ({@link #KEY}, {@link #FOLLOW}): у 15 кт ~1,4 во втором максимуме, ~1 через 0,5 с, ~0,8 через секунду, ~0,55 через
 * 3 с, ~0,4 через 5 с — горячее светлее и белее, остывая — оранжевее и темнее, а перепад температуры по поверхности
 * (узор шара, языки клубов) виден; днём и ночью — одинаково: камера выставлена по огню, а не по небу. Свечение
 * тусклее этого — в мере кадра с привыканием глаза к небу, как было; между ними — плавно (x = E·(1 + (E/(K·L^β))²)^−½,
 * E = L × привыкание глаза). Мерило — поверхность, а не ядро внутри: свернувшись в кольцо (у 15 кт с ~3 с), шапка
 * снаружи остывает смешением с воздухом, кадр возвращается к экспозиции сцены, и горячее нутро в просветах клубов горит,
 * как прежде (ночью красное свечение шапки держится дольше). Кривая — та же, что у обычного шара: ACES по каналам
 * (приближение Narkowicz 2016, CC0), выше белого — связь каналов ({@link #WHITE}). K и β подобраны по кадрам песочницы,
 * не выведены.
 *
 * <p><b>Свет шара вокруг</b> (ножка, пыль, пелена, мир, частицы) — по-прежнему чёрное тело модели в мере кадра: мгла и
 * стена пыли в его свете, ночью — во всю силу, как были. Не ярче шара на экране ({@link #cap}, как у обычного шара:
 * {@code FxAtlas.fireLightAt}) — только то, что его обнимает и без этого сливалось бы с ним в один белый диск: оболочка
 * облака Вильсона ({@code wilsonLight} в {@code fx_pall}) и блик с вуалью ({@code NukeRenderer.fireballs}).
 *
 * <p><b>Кадр во вспышку</b> ({@link #overexposure}): камера выставлена на то, что видела до вспышки, и догоняет свет
 * вспышки у себя постоянной {@link #ADAPT_S} (в логарифме яркости, как автоэкспозиция). Пересвет кадра против
 * выставленного — X = K^(e^(−u/τ)), K — во сколько раз свет вспышки у камеры во втором максимуме ярче того, на что
 * выставлен кадр, u — время после максимума: до максимума и в нём кадр белый, через ~0,25 с у 15 кт в 1,4 км — пересвет
 * ~50 (проступают тёмные силуэты), через 0,5 с — ~3, через 0,75 с — ~1,4, через секунду — как был. Прежде белый экран стоял весь импульс (1,2 с у
 * 15 кт) и гас ещё 2–4 с. Засветка глаз игрока ({@code NukeFlash}: вуаль, ожоги сетчатки, послеобраз) — физика глаза и
 * от этого не меняется.
 */
public final class NukeExposure {
    /** Поверхность шара яркостью в одну полуденную белую стену — на кривой K (камера выставлена по огню). */
    public static final double KEY = 0.233;
    /** Насколько вход кривой у яркого шара следует за его яркостью (в логарифме): 0 — шар всегда одной яркости, 1 — по белой стене. */
    public static final double FOLLOW = 0.15;
    /** Пересвет выше белого: доля связи каналов, как у обычного шара ({@code pack.py}: white). */
    public static final double WHITE = 0.35;
    /** Постоянная, с которой экспозиция кадра догоняет вспышку, с (подача: автоэкспозиция камеры). */
    public static final double ADAPT_S = 0.2;
    /**
     * Доли кольца ({@code MushroomModel.rollUp}), на которых свечение уходит с плёночной кривой на прежний путь ({@link #filmShare};
     * у 15 кт — с ~5 до ~7 с, у 1 кт — с ~2,5 до ~3,7 с).
     */
    public static final double ROLL_FROM = 0.15, ROLL_TO = 0.45;

    private NukeExposure() {}

    /**
     * Во сколько раз экспозиция шара темнее кадра, когда его поверхность светит {@code key} (линейный, белая стена — 1, с
     * привыканием глаза к небу {@code adapt}: днём 1, ночью ~1 200).
     */
    public static double gain(double key, double adapt) {
        double r = Math.pow(Math.max(key, 0), 1 - FOLLOW) * Math.pow(Math.max(adapt, 1e-9), FOLLOW) / KEY;
        return 1 / Math.sqrt(1 + r * r);
    }

    /**
     * Доля свечения гриба на плёночной кривой при доле кольца {@code roll} ({@code mushFilmShare}): пока шар — шар, вся;
     * свернувшаяся шапка — прежним путём, с пеленой (пересвет — в долю пикселя одним оттенком, {@link #pixel}): кривая по
     * каналам белила её оранжевое нутро ночью и меняла его сквозь мглу (ревью #441), а гриб на 7–10 с — прежний.
     */
    public static double filmShare(double roll) {
        double u = Math.max(0, Math.min(1, (roll - ROLL_FROM) / (ROLL_TO - ROLL_FROM)));
        return 1 - u * u * (3 - 2 * u);
    }

    /** Плёночная кривая по каналу (ACES, приближение Narkowicz 2016): вход — свет с экспозицией, выход — линейный 0..1. */
    public static double film(double x) {
        double v = Math.max(x, 0);
        return Math.min(1, v * (2.51 * v + 0.03) / (v * (2.43 * v + 0.59) + 0.14));
    }

    /** Связь каналов выше белого: доля к белому у пикселя, чей ярчайший канал с экспозицией — {@code top}. */
    public static double white(double top) {
        return WHITE * (1 - Math.exp(-Math.max(top - 1, 0)));
    }

    /**
     * Свет {@code rgb} (линейный, белая стена — 1, с привыканием глаза) на экране при экспозиции шара {@code gain}:
     * линейный 0..1 по каналам — в {@code out}.
     */
    public static void screen(double r, double g, double b, double gain, double[] out) {
        double xr = r * gain, xg = g * gain, xb = b * gain, w = white(Math.max(xr, Math.max(xg, xb)));
        out[0] = film(xr) + (1 - film(xr)) * w;
        out[1] = film(xg) + (1 - film(xg)) * w;
        out[2] = film(xb) + (1 - film(xb)) * w;
    }

    /**
     * Шар на экране и предел его света на том, что его обнимает ({@code mushBallCap} в {@code mushroom.glsl}): поверхность
     * шара светом {@code rgb} (с привыканием глаза {@code adapt}) при экспозиции по ней же — линейный, по каналам, в
     * {@code out}.
     */
    public static void cap(double r, double g, double b, double adapt, double[] out) {
        screen(r, g, b, gain(0.2126 * r + 0.7152 * g + 0.0722 * b, adapt), out);
    }

    /** {@link #cap} на экране — доля белого (мера экрана, степень 1/2,2), как огонь обычного шара ({@code FxAtlas.fireLightAt}). */
    public static double capShown(double r, double g, double b, double adapt) {
        double[] c = new double[3];
        cap(r, g, b, adapt, c);
        return Math.pow(Math.min(1, 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]), 1 / 2.2);
    }

    /**
     * Пиксель пелены на экране — копия конца {@code main} в {@code fx_pall}: {@code light} — рассеянный свет (линейный, сумма
     * по лучу с ослаблением), {@code heat} — свечение грибов как есть, {@code emitted} — оно же с экспозицией шара (с тем же
     * ослаблением), {@code film} — доля плёночной кривой ({@link #filmShare}), {@code cover} — доля пикселя, закрытая
     * веществом (не дымкой); в {@code out} — цвет на экране с умноженной долей (rgb). Прежний путь — свечение со светом
     * ({@link #shown}); кривая — свечение с экспозицией плёночной кривой поверх света, в линейном; между ними — смесь
     * готовых картинок.
     */
    static void pixel(double[] light, double[] heat, double[] emitted, double film, double cover, double[] out) {
        double[] sum = new double[3];
        for (int c = 0; c < 3; c++) sum[c] = light[c] + heat[c];
        shown(sum, cover, out);
        if (film <= 0) return;
        double[] base = new double[3], f = new double[3];
        shown(light, cover, base);
        screen(emitted[0], emitted[1], emitted[2], 1, f);
        for (int c = 0; c < 3; c++) {
            double curve = Math.min(PallLight.display(PallLight.linear(base[c]) + f[c]), 1);
            out[c] += (curve - out[c]) * film;
        }
    }

    /**
     * Прежний путь ({@code pallShown} в {@code fx_pall}): свет как вещество (свет / cover), ярче белого — в долю пикселя тем
     * же оттенком, к белому долей {@code CROSS}, экран, плечо выше 0,6 по ярчайшему каналу; × доля пикселя.
     */
    static void shown(double[] light, double cover, double[] out) {
        double shownCover = cover;
        double[] medium = new double[3];
        for (int c = 0; c < 3; c++) medium[c] = light[c] / Math.max(cover, 1e-4);
        double lum = 0.2126 * medium[0] + 0.7152 * medium[1] + 0.0722 * medium[2];
        if (lum > 1 && cover > 1e-4) {
            shownCover = Math.min(cover * lum, 1);
            for (int c = 0; c < 3; c++) medium[c] *= cover / shownCover;
        }
        double white = PallLight.CROSS * Math.max(0.2126 * medium[0] + 0.7152 * medium[1] + 0.0722 * medium[2] - 1, 0);
        double[] d = new double[3];
        for (int c = 0; c < 3; c++) d[c] = PallLight.display(medium[c] + white);
        double m = Math.max(d[0], Math.max(d[1], d[2])), k = m <= 0.6 ? 1 : (0.6 + 0.4 * (1 - Math.exp(-(m - 0.6) / 0.4))) / m;
        for (int c = 0; c < 3; c++) out[c] = d[c] * k * shownCover;
    }

    /**
     * Пересвет кадра против выставленного (≥ 1): {@code peak} — во сколько раз свет вспышки у камеры во втором максимуме
     * ярче того, на что выставлен кадр (≥ 1), {@code after} — секунды игры после максимума (до него — меньше нуля).
     */
    public static double overexposure(double peak, double after) {
        double k = Math.max(peak, 1);
        if (after <= 0) return k;
        return Math.pow(k, Math.exp(-after / ADAPT_S));
    }

    /** Через сколько секунд после максимума пересвет кадра {@link #overexposure} опускается до {@code x} (> 1). */
    public static double settled(double peak, double x) {
        if (peak <= x) return 0;
        return ADAPT_S * Math.log(Math.log(peak) / Math.log(x));
    }
}
