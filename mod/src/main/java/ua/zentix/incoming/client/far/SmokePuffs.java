package ua.zentix.incoming.client.far;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.plume.Smoke;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;

/**
 * Облака клиента квадратами слоя эффектов — на любой дальности, вблизи и вдали одинаково: клуб модели (гаусс массы
 * вещества от источника {@link SmokeGrid.Source}: облако взрыва, облако старта, дым пожара) — кучка запечённых клубов
 * ({@link FxAtlas#puff6}: симуляция газа в Blender, шестисторонний свет, векторы движения между кадрами).
 * <ul>
 * <li><b>Размер.</b> Видимый край гаусса — там, где толща падает до {@link #EDGE_TAU}: r = σ·√(2·ln(τ₀/{@value #EDGE_TAU}))
 * (τ₀ — толща поперёк середины, Σκ'm/(2πσhσv)); у густого облака это ~3,7σ, у тонкого — ближе к середине. Квадрат клуба
 * — этот край (карта клуба кадрирована по краю вещества, {@code tools/bake_fx/pack.py}).</li>
 * <li><b>Плотность.</b> Непрозрачность середины — 1 − e^(−τ₀); крупный на экране гаусс — кучка до {@value #CLUSTER}
 * клубов вокруг середины (чем крупнее, тем их больше и они мельче), в середине их перекрывается несколько: каждый берёт
 * свою долю, чтобы вместе вышло то же пропускание.</li>
 * <li><b>Цвет</b> — отражение толстого слоя смеси сажи, пыли и капель (два потока, {@link #thick}) при свете неба, каким
 * карта освещения светит блоки под открытым небом ({@link FarView#sky});
 * свет по сторонам (солнце, луна, небо, огненные шары кадра) — по картам кадра в шейдере ({@code fx.vsh}, {@code fx.fsh}).</li>
 * <li><b>Кадр</b> — тот, где симуляция выросла во столько же раз, во сколько вещество источника с выброса
 * ({@link SmokeGrid.Source#puffGrowth}, {@link FxAtlas#puffFrame}), у каждого клуба кучки со своим сдвигом.</li>
 * </ul>
 * Светящийся газ (горячее точки Дрейпера) у источника, что рисует тело огня ({@link SmokeGrid.Source#drawsGlow}: облако
 * взрыва — огненным шаром), здесь не рисуется: дым проступает той же долей, что тело тает. Отрезок
 * ({@link SmokeGrid.Sink#segment}: след, шлейф пожара) — клубы вдоль него через ~σ.
 */
final class SmokePuffs {
    /** Толща видимого края гаусса (порог контраста — 0,02; край квадрата чуть внутри). */
    static final double EDGE_TAU = 0.05;
    /**
     * Клубов на гаусс — не больше; по одному на столько пикселей видимого радиуса на экране (кадр клуба — 128 пикселей:
     * крупнее он мылится), мельче — один клуб.
     */
    static final int CLUSTER = 12;
    static final double CLUSTER_PIXELS = 40;
    /** Клуб кучки — не больше этой доли видимого радиуса гаусса. */
    static final double MAX_SHARE = 0.8;
    /** Клубы отрезка — через столько σ (меньшей из двух). */
    static final double SEGMENT_STEP = 1.2;
    /** Первые кадры симуляции — плотный круглый выброс: на квадрате вышел бы шар. Кадры — с этого. */
    static final int FIRST_FRAME = 3;

    private static final Draw DRAW = new Draw();

    private SmokePuffs() {}

    /** Облака, которых объём не рисует, — в кадр. */
    static void collect(FarView view, FarSprites out) {
        double seconds = SmokeGrid.seconds(view.partial());
        SmokeGrid.quads(s -> {
            DRAW.begin(view, out, s, seconds);
            return s.emit(seconds, DRAW);
        });
    }

    /** Приёмник клубов одного источника: клубы — квадратами. */
    private static final class Draw implements SmokeGrid.Sink {
        private final double[] point = new double[2];
        FarView view;
        FarSprites out;
        private SmokeGrid.Source source;
        /** Кадр клуба по росту вещества (с дробью): дыма и брызг. */
        private double age, sprayAge;
        private long seed;
        private boolean glow, sprays;
        private int index;

        void begin(FarView view, FarSprites out, SmokeGrid.Source source, double seconds) {
            this.view = view;
            this.out = out;
            this.source = source;
            double growth = source.puffGrowth(seconds);
            this.age = FxAtlas.puffFrame(growth);
            this.sprayAge = FxAtlas.sprayFrame(growth);
            this.seed = source.seed();
            this.glow = source.drawsGlow();
            this.sprays = source.sprayClubs();
            this.index = 0;
        }

        @Override
        public void segment(double x, double y, double z, double ax, double ay, double az, double sh, double sv, double soot, double dust,
                            double water, float dustR, float dustG, float dustB, double kelvin, double rise) {
            int at = index++;
            // светящийся газ — тело огня у источника; остывая, он проступает дымом той же долей, что тело тает
            double smoke = glow ? 1 - CloudModel.glowShare(kelvin) : 1;
            if (smoke <= 0 || soot + dust + water <= 0 || !(sh > 0) || !(sv > 0)) return;
            soot *= smoke;
            dust *= smoke;
            water *= smoke;
            double len = 2 * Math.sqrt(ax * ax + ay * ay + az * az);
            int n = len <= 0 ? 1 : (int) Math.min(12, Math.max(1, Math.ceil(len / (SEGMENT_STEP * Math.min(sh, sv)))));
            for (int k = 0; k < n; k++) {
                double f = n == 1 ? 0 : (k + 0.5) / n * 2 - 1;
                blob(x + ax * f, y + ay * f, z + az * f, sh, sv, soot / n, dust / n, water / n, dustR, dustG, dustB, at * 16 + k);
            }
        }

        private void blob(double x, double y, double z, double sh, double sv, double soot, double dust, double water, float dr, float dg, float db,
                          int id) {
            Vec3 eye = view.camera();
            // на экране — проекция гаусса по углу взгляда (толща по лучу и высота квадрата); в мире — его σ (разброс клубов
            // кучки, край для рельефа и для глаза внутри)
            double across = projected(sh, sv, x - eye.x, y - eye.y, z - eye.z);
            double ks = SmokeGrid.SOOT_K * soot, kd = SmokeGrid.dustK(dr, dg, db) * dust, kw = SmokeGrid.DROP_K * water;
            double tau = (ks + kd + kw) / (2 * Math.PI * sh * across);
            double a = 1 - Math.exp(-tau);
            if (a < 0.004) return;
            double edge = edge(tau);
            double rh = edge * sh, rv = edge * across, rw = edge * sv, r = Math.max(rh, rw);
            double vis = source.visible(y, r);
            if (vis <= 0) return;
            Vec3 cam = view.camera();
            double px = x - cam.x, py = y - cam.y, pz = z - cam.z;
            double d = Math.sqrt(px * px + py * py + pz * pz);
            double air = view.air(d, py);
            if (air < Sight.THRESHOLD) return;
            // глаз входит в клуб — квадраты клуба тают (квадрат в десятки блоков у самой камеры закрыл бы экран краем), а
            // вещество вокруг глаза — вуалью по экрану: толща по лучу взгляда из глаза через гаусс. «Внутри» — по эллипсоиду
            // края (у плоского слоя пыли в сотню метров шириной и метр толщиной глаз над ним снаружи; по наибольшему
            // радиусу он был «внутри», и вуаль слоя закрывала весь кадр)
            double q = Math.sqrt((px * px + pz * pz) / (rh * rh) + py * py / (rw * rw));
            double outside = smoothstep(0.5, 1, q);
            if (outside < 1) veil(px, py, pz, sh, sv, ks + kd + kw, ks, kd, kw, dr, dg, db, (1 - outside) * vis * air);
            if (outside <= 0) return;
            Sight.point(rh, 1, 1, d, view.pixel(), point);
            double grow = point[0] / rh;
            long h = mix(seed * 0x9E3779B97F4A7C15L + id);
            // свет неба — яркость того, каким карта освещения светит блоки и частицы под открытым небом (FarView.sky), с
            // разбросом клуба; его оттенок (ночью — сиреневый) — в шейдере только у света неба, не у огня (FxSkyTint)
            float lit = (float) ((0.88 + 0.24 * unit(h)) * view.skyLight());
            float cr = thick(ks, kd, kw, dr) * lit, cg = thick(ks, kd, kw, dg) * lit, cb = thick(ks, kd, kw, db) * lit;
            double light = shade(ks, kd, kw, dg);
            float shade = (float) (light / BAKED_K), sprayShade = (float) (light / SPRAY_K);
            float shown = (float) (a * vis * point[1] * air * outside);
            // кучка: чем крупнее гаусс на экране, тем больше клубов и тем они мельче (кадр 128 пикселей не растягивается на
            // пол-экрана); дробная часть числа — доля последнего, без скачка
            double pixels = point[0] / Math.max(d * view.pixel(), 1e-12);
            // крупнее экрана — подробность за его краем не видна, а заливка дорога: клубов меньше во столько раз, во сколько
            // гаусс по площади больше экрана (полудиагональ кадра, пикселей)
            double screen = view.edge() / Math.max(view.pixel(), 1e-12);
            double many = Math.clamp(Math.min(pixels / CLUSTER_PIXELS, CLUSTER * Math.min(1, screen * screen / (pixels * pixels))), 1, CLUSTER);
            int count = (int) Math.ceil(many - 1e-9);
            double size = count == 1 ? 1 : Math.min(MAX_SHARE, 1.6 / Math.sqrt(many));
            // в середине перекрывается столько клубов: каждый берёт свою долю, вместе — то же пропускание
            double overlap = Math.max(1, many * size * size);
            float sub = (float) (1 - Math.pow(1 - Math.min(shown, 0.999), 1 / overlap));
            double spread = count == 1 ? 0 : 1 - size;
            // у источника с брызгами (взрыв на воде) вещество — больше капли, чем дым и пыль: клубы брызг
            boolean spray = sprays && kw > ks + kd;
            double clubAge = spray ? sprayAge : age;
            // плоский клуб, на который смотрят сверху (проекция диска шире его толщи), лежит на горизонтали: квадрат лицом к
            // камере у слоя пыли у земли стоял бы стеной над ней (кадры F2a: «плита» под шаром в 0,1 с)
            boolean lying = lies(sh, sv, py, d);
            for (int j = 0; j < count; j++) {
                long hj = mix(h + j * 0x632BE59BD9B4E019L);
                // лежащий клуб — на высоте середины слоя: ниже он ушёл бы под рельеф
                double ox = gauss(hj) * spread * rh * 0.5, oy = lying ? 0 : gauss(hj >>> 7) * spread * rw * 0.5, oz = gauss(hj >>> 14) * spread * rh * 0.5;
                double k = size * (0.8 + 0.4 * unit(hj >>> 21));
                double frame = Math.min(FxAtlas.PUFF6_FRAMES - 1.001, Math.max(FIRST_FRAME, clubAge) + (count > 1 ? 2 * unit(hj >>> 28) : 0));
                int f0 = (int) frame;
                float last = j == count - 1 ? (float) (many - (count - 1)) : 1;
                FxAtlas.Sprite sprite = spray ? FxAtlas.spray6(f0, (j & 1) == 1) : FxAtlas.puff6(f0, (j & 1) == 1);
                // лежащий клуб сверху — освещённый верх слоя: тени карт (они запечены для клуба лицом к камере, свет сбоку и
                // сзади) нет, свет солнца, неба и огня — весь (степень тени 0; свет шаров — в каждом пикселе, fx.fsh); снизу —
                // как обычно. Мягкость у рельефа — путь луча в слое до земли (rw / sin e), не полуразмер
                if (lying) out.puff6Flat(px + ox, py + oy, pz + oz, rh * grow * k, rh * grow * k,
                        Math.min(rh, rw * d / Math.max(Math.abs(py), 1e-3)) * ua.zentix.incoming.client.fx.layer.FxQuads.SOFT, sprite,
                        (float) (frame - f0), py < 0 ? 0 : (spray ? sprayShade : shade), cr, cg, cb, sub * last);
                else out.puff6(px + ox, py + oy, pz + oz, rh * grow * k, rv * grow * k, sprite, (float) (frame - f0), spray ? sprayShade : shade,
                        cr, cg, cb, sub * last);
            }
        }
    }

    /**
     * Лежит ли клуб с осями (σh, σh, σv) на горизонтали, когда глаз выше или ниже его середины на dy при расстоянии d:
     * плоский (σh &gt; 3σv) и видный сверху — проекция диска (σh·sin e) шире его толщи (σv·cos e ≈ σv).
     */
    static boolean lies(double sh, double sv, double dy, double d) {
        return sh > 3 * sv && sh * Math.abs(dy) > sv * d;
    }

    /**
     * σ по вертикали экрана у гаусса с осями (σh, σh, σv), на который смотрят из (dx, dy, dz) от глаза: проекция гаусса —
     * гаусс с осями σh (поперёк, по горизонтали) и √(σv²·cos²e + σh²·sin²e) (e — угол взгляда к горизонту), толща по лучу
     * через середину — масса / (2π·σ₁·σ₂). Плоский клуб (слой пыли у земли: σh в десятки раз больше σv) сверху — круг на
     * земле толщей слоя по лучу, сбоку — полоса; квадрат к камере высотой σv лёг бы тонкой чертой поперёк кадра (кадры
     * шлагбаума F2a: тёмные полосы над землёй вокруг взрыва).
     */
    static double projected(double sh, double sv, double dx, double dy, double dz) {
        double d2 = dx * dx + dy * dy + dz * dz;
        if (!(d2 > 0)) return sv;
        double sin2 = dy * dy / d2;
        return Math.sqrt(sv * sv * (1 - sin2) + sh * sh * sin2);
    }

    /**
     * Гаусс вещества с центром (px, py, pz) от глаза, σ по горизонтали и вертикали, толща поперёк середины tau — кучкой
     * запечённых клубов: цвет толстого слоя (cr, cg, cb; до света неба у глаза), степень тени карт shade, кадр клуба age
     * (с дробью), зерно h, видимая доля vis, клубов в кучке — не больше most; vary — и одиночный клуб берёт свой кадр и
     * отражение по зерну (клубы трубки идут рядом друг с другом: одинаковые лежали бы стопкой монет). Глаз в гауссе —
     * вуаль ({@link #veil}).
     */
    static int club(FarView view, FarSprites out, double px, double py, double pz, double sh, double sv, double tau, float cr, float cg, float cb,
                    double shade, boolean spray, double age, long h, double vis, int most, boolean vary) {
        double a = 1 - Math.exp(-tau);
        if (a < 0.004) return 0;
        double edge = edge(tau);
        double rh = edge * sh, rv = edge * sv, r = Math.max(rh, rv);
        double d = Math.sqrt(px * px + py * py + pz * pz);
        double air = view.air(d, py);
        if (air < Sight.THRESHOLD) return 0;
        // глаз входит в клуб — квадраты клуба тают (квадрат в десятки блоков у самой камеры закрыл бы экран краем), а
        // вещество вокруг глаза — вуалью по экрану: толща по лучу взгляда из глаза через гаусс
        double outside = smoothstep(0.5 * r, r, d);
        int veils = outside < 1 && veilAround(view, out, px, py, pz, sh, sv, tau * 2 * Math.PI * sh * sv, cr, cg, cb, (1 - outside) * vis * air) ? 1 : 0;
        if (outside <= 0) return veils;
        // вне кадра — не нужен: шар всего квадрата кучки (клуб — до 1,2 радиуса, его место — до ~0,9 от середины)
        if (!FarFlightView.inFrame(view, px, py, pz, d, 2.2 * r)) return veils;
        double[] point = POINT;
        Sight.point(rh, 1, 1, d, view.pixel(), point);
        double grow = point[0] / rh;
        // свет неба — яркость FarView.sky с разбросом клуба; оттенок — в шейдере у света неба (FxSkyTint)
        float lit = (float) ((0.88 + 0.24 * unit(h)) * view.skyLight());
        float sr = cr * lit, sg = cg * lit, sb = cb * lit;
        float shown = (float) (a * vis * point[1] * air * outside);
        // кучка: чем крупнее гаусс на экране, тем больше клубов и тем они мельче (кадр 128 пикселей не растягивается на
        // пол-экрана); дробная часть числа — доля последнего, без скачка
        double pixels = point[0] / Math.max(d * view.pixel(), 1e-12);
        // крупнее экрана — подробность за его краем не видна, а заливка дорога: клубов меньше во столько раз, во сколько
        // гаусс по площади больше экрана (полудиагональ кадра, пикселей)
        double screen = view.edge() / Math.max(view.pixel(), 1e-12);
        double many = Math.clamp(Math.min(pixels / CLUSTER_PIXELS, most * Math.min(1, screen * screen / (pixels * pixels))), 1, most);
        int count = (int) Math.ceil(many - 1e-9);
        double size = count == 1 ? 1 : Math.min(MAX_SHARE, 1.6 / Math.sqrt(many));
        // в середине перекрывается столько клубов: каждый берёт свою долю, вместе — то же пропускание
        double overlap = Math.max(1, many * size * size);
        float sub = (float) (1 - Math.pow(1 - Math.min(shown, 0.999), 1 / overlap));
        double spread = count == 1 ? 0 : 1 - size;
        for (int j = 0; j < count; j++) {
            long hj = mix(h + j * 0x632BE59BD9B4E019L);
            double ox = gauss(hj) * spread * rh * 0.5, oy = gauss(hj >>> 7) * spread * rv * 0.5, oz = gauss(hj >>> 14) * spread * rh * 0.5;
            double k = size * (0.8 + 0.4 * unit(hj >>> 21));
            double frame = Math.min(FxAtlas.PUFF6_FRAMES - 1.001, Math.max(FIRST_FRAME, age) + (count > 1 || vary ? 2 * unit(hj >>> 28) : 0));
            int f0 = (int) frame;
            float last = j == count - 1 ? (float) (many - (count - 1)) : 1;
            boolean mirror = vary ? (hj >>> 40 & 1) == 1 : (j & 1) == 1;
            out.puff6(px + ox, py + oy, pz + oz, rh * grow * k, rv * grow * k, spray ? FxAtlas.spray6(f0, mirror) : FxAtlas.puff6(f0, mirror),
                    (float) (frame - f0), (float) shade, sr, sg, sb, sub * last);
        }
        return count + veils;
    }

    private static final double[] POINT = new double[2];

    /** Уровень дробления отрезка длиной len с наименьшей σ сечения sigma: 2^уровень клубов кладут их через {@link #SEGMENT_STEP}·σ. */
    static double tubeLevel(double len, double sigma) {
        return Math.clamp(Math.log(Math.max(1, len / (SEGMENT_STEP * sigma))) / Math.log(2), 0, TUBE_LEVELS);
    }

    /** Клубов на отрезке уровня level — 2 в этой степени. */
    static int tubeDepth(double level) {
        return (int) Math.ceil(level - 1e-9);
    }

    /** Доля места j (из 2^{@link #tubeDepth}) на уровне level: чётные — целые, нечётные верхнего уровня проявляются с дробью уровня. */
    static double tubeWeight(int j, double level) {
        int k = tubeDepth(level);
        return k > 0 && (j & 1) == 1 ? level - (k - 1) : 1;
    }

    /** Сумма долей мест уровня level: вещество места — его доля от неё. */
    static double tubeTotal(double level) {
        int k = tubeDepth(level);
        return k == 0 ? 1 : (1 << (k - 1)) * (1 + level - (k - 1));
    }

    /** Клубов трубки следа на отрезок — не больше 2 в этой степени. */
    static final int TUBE_LEVELS = 6;
    /**
     * Середина клуба трубки гуляет на столько σ: крупные вихри следа — порядка его ширины, и сам след извивается на доли
     * ширины (⚠ оценка).
     */
    static final double TUBE_MEANDER = 0.3;
    /** Клубов в кучке у клуба трубки — не больше: подробность вдоль трубки дают соседние клубы. */
    static final int TUBE_CLUSTER = 3;

    /**
     * Отрезок трубки следа ({@code TrailModel}: гауссово сечение σ, ослабление на метр e — у концов a и b, от глаза) — клубами
     * вдоль оси, как облака: трубка из гауссов того же сечения через ≤ {@link #SEGMENT_STEP}·σ (до {@code 2^}{@link #TUBE_LEVELS}
     * на отрезок, дальше шаг шире), у каждого — вещество своего
     * куска оси (сумма гауссов вдоль оси даёт толщу трубки поперёк середины e/(√(2π)·σ)). Клубов на отрезок — 2^k
     * ({@link #TUBE_LEVELS}): места уровня k — часть мест уровня k + 1, и новые (нечётные) проявляются долей, пока трубка
     * сужается на экране, — без скачков, когда σ растёт.
     *
     * @param wa     ширина трубки у конца a для порога клубов ({@code FarFlightView.puffShare}: на половине непрозрачности, с
     *               множителем бюджета), у b — wb: доля вещества, что рисуют клубы, — у каждого клуба по его расстоянию
     *               (остальное — лента, её доля у концов — та же)
     * @param albedo альбедо однократного рассеяния вещества (до дельта-Эддингтона, асимметрия {@link Smoke#ASYMMETRY})
     * @param sigma0 σ при рождении следа: кадр клуба — по росту σ с него ({@link FxAtlas#puffFrame})
     * @param whole  отрезок без ленты (проходит у самого глаза): клубы рисуют всё его вещество
     * @return квадратов клубов и вуалей
     */
    static int tube(FarView view, FarSprites out, double ax, double ay, double az, double sa, double ea, double wa, double bx, double by, double bz,
                    double sb, double eb, double wb, double albedo, double sigma0, long seed, boolean whole) {
        if (ea + eb <= 0 || !(sa > 0) || !(sb > 0)) return 0;
        double tx = bx - ax, ty = by - ay, tz = bz - az, len = Math.sqrt(tx * tx + ty * ty + tz * tz);
        double level = tubeLevel(len, Math.min(sa, sb));
        int k = tubeDepth(level), n = 1 << k;
        double total = tubeTotal(level);
        // цвет и тень — двумя потоками после дельта-Эддингтона (f = g²), как у облаков ({@link SmokeGrid}); толща — полное
        // ослабление, как у ленты и порога видимости следа (Кошмидер): рассеянное вперёд мелкими (~мкм) частицами уходит
        // на десятки градусов и картинку за дымом не несёт
        double g = Smoke.ASYMMETRY, f = g * g, w = (1 - f) * albedo / (1 - f * albedo), gs = g / (1 + g);
        float c = thick(w, gs);
        double shade = twoStream(w, gs) / BAKED_K;
        int drawn = 0;
        for (int j = 0; j < n; j++) {
            double weight = tubeWeight(j, level);
            if (weight <= 0) continue;
            double u = (double) j / n, sigma = sa + (sb - sa) * u, e = ea + (eb - ea) * u;
            double cx = ax + tx * u, cy = ay + ty * u, cz = az + tz * u;
            double share = whole ? 1 : FarFlightView.puffShare(wa + (wb - wa) * u, Math.sqrt(cx * cx + cy * cy + cz * cz), view.pixel());
            if (share <= 0) continue;
            // вещество куска оси len/total (с долей места), гаусс 3D того же σ: толща поперёк середины — K/(2πσ²)
            double tau = share * e * len * weight / total / (2 * Math.PI * sigma * sigma);
            if (!(tau > 0)) continue;
            double age = FxAtlas.puffFrame(sigma / Math.max(sigma0, 1e-6));
            // зерно — по месту на самом мелком уровне: клуб остаётся тем же, когда уровень меняется
            long h = mix(seed * 0x9E3779B97F4A7C15L + ((long) j << (TUBE_LEVELS - k)));
            // вихри следа — порядка σ: середина клуба гуляет на {@link #TUBE_MEANDER}·σ (⚠ оценка), иначе ровный ряд
            double m = TUBE_MEANDER * sigma;
            drawn += club(view, out, cx + m * gauss(h >>> 3), cy + m * gauss(h >>> 11), cz + m * gauss(h >>> 19), sigma, sigma, tau, c, c, c, shade,
                    false, age, h, 1, TUBE_CLUSTER, true);
        }
        return drawn;
    }

    /**
     * Вуаль вещества клуба трубки вокруг глаза (как {@link #veil}, но для {@link #club}): ослабление всей массы ext (м²),
     * цвет толстого слоя (cr, cg, cb; свет — карта освещения под открытым небом и огонь кадра), доля share.
     */
    private static boolean veilAround(FarView view, FarSprites out, double px, double py, double pz, double sh, double sv, double ext, float cr,
                                      float cg, float cb, double share) {
        double fx = view.forward().x(), fy = view.forward().y(), fz = view.forward().z();
        float k = (float) ((1 - Math.exp(-sightDepth(px, py, pz, sh, sv, ext, fx, fy, fz))) * share);
        if (k < 0.004f) return false;
        out.veil(fx * VEIL_AT, fy * VEIL_AT, fz * VEIL_AT, VEIL_AT * 4, cr, cg, cb, k, OPEN_SKY);
        return true;
    }

    /** Свет мира под открытым небом без света блоков (упакованный, для карты освещения). */
    private static final int OPEN_SKY = LightTexture.pack(0, 15);

    /** Вуаль облака вокруг глаза — на столько блоков перед ним (ближе квадраты слоя тают у ближней плоскости). */
    static final double VEIL_AT = 2;

    /**
     * Вещество гаусса вокруг глаза (центр (px, py, pz) от глаза, σ, толща поперёк середины tau): толща по лучу взгляда
     * из глаза — τ₀·e^(−b²/2σ²)·½·(1 + erf(a/(√2σ))) (a — середина гаусса вдоль взгляда, b — поперёк; σ — среднее
     * геометрическое), непрозрачность — 1 − e^(−τ), квадратом во весь экран на {@link #VEIL_AT} блока перед глазом. Тот
     * же цвет вещества, что у клубов. Доля share — сколько клубов растаяло.
     */
    private static void veil(double px, double py, double pz, double sh, double sv, double ext, double ks, double kd, double kw, float dr,
                             float dg, float db, double share) {
        FarView view = DRAW.view;
        double fx = view.forward().x(), fy = view.forward().y(), fz = view.forward().z();
        double along = sightDepth(px, py, pz, sh, sv, ext, fx, fy, fz);
        float k = (float) ((1 - Math.exp(-along)) * share);
        if (k < 0.004f) return;
        float cr = thick(ks, kd, kw, dr), cg = thick(ks, kd, kw, dg), cb = thick(ks, kd, kw, db);
        // во весь экран: полуразмер — с запасом на угол поля зрения до ~120°; свет — как у вещества под открытым небом
        // (карта освещения: небо 15) и огонь кадра у глаза (FxLights), как у клубов этого же вещества снаружи
        double half = VEIL_AT * 4;
        DRAW.out.veil(fx * VEIL_AT, fy * VEIL_AT, fz * VEIL_AT, half, cr, cg, cb, k, OPEN_SKY);
    }

    /**
     * Толща по лучу взгляда из глаза (направление f, единичное) через гаусс с осями (σh, σh, σv), центр — (px, py, pz) от
     * глаза, ослабление всей массы ext (м²): в пространстве, где гаусс — шар единичного σ, луч идёт с шагом |f'| =
     * √((fx² + fz²)/σh² + fy²/σv²), и толща — ext/((2π)^{3/2}σh²σv)·√(π/2)/|f'|·e^(−b'²/2)·(1 + erf(a'/√2)), a' — путь до
     * ближайшей к середине точки луча, b' — её расстояние до середины (обе — в σ). У шара — прежняя формула вуали.
     */
    static double sightDepth(double px, double py, double pz, double sh, double sv, double ext, double fx, double fy, double fz) {
        double ux = fx / sh, uy = fy / sv, uz = fz / sh, len = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (!(len > 0)) return 0;
        ux /= len;
        uy /= len;
        uz /= len;
        // середина от глаза в σ: луч из глаза — t·u, ближайшая к середине точка — t = a'
        double cx = px / sh, cy = py / sv, cz = pz / sh;
        double a = cx * ux + cy * uy + cz * uz, b2 = Math.max(0, cx * cx + cy * cy + cz * cz - a * a);
        return ext / (Math.pow(2 * Math.PI, 1.5) * sh * sh * sv) * Math.sqrt(Math.PI / 2) / len * Math.exp(-0.5 * b2)
                * (1 + SmokeGrid.erf(a / Math.sqrt(2)));
    }

    /** Видимый край гаусса толщи τ₀ поперёк середины, в σ: где толща падает до {@link #EDGE_TAU}. */
    static double edge(double tau) {
        return Math.clamp(Math.sqrt(2 * Math.max(0, Math.log(Math.max(tau, 1e-6) / EDGE_TAU))), 1.2, 4.0);
    }

    /**
     * Цвет толстого слоя смеси на экране (один канал): альбедо ω' и асимметрия g' — по долям ослабления сажи, пыли
     * (альбедо {@code dustW}) и капель; отражение полубесконечного слоя двух потоков R∞ = (1 − s)/(1 + s),
     * s = √((1 − ω')/(1 − ω'g')) (замыкание по средним полусфер; у Эддингтона s больше в 2/√3 раза, и у сажи — ω' ≈ 0,2 —
     * отражение выходило бы отрицательным, сажа — чёрной дырой); на экран — степень 1/2,2. Обращение — {@link SmokeGrid#dustAlbedo}.
     */
    static float thick(double soot, double dust, double water, float dustW) {
        double sum = soot + dust + water;
        double w = (soot * SmokeGrid.SOOT_W + dust * dustW + water * SmokeGrid.DROP_W) / sum;
        double g = (soot * SmokeGrid.SOOT_G + dust * SmokeGrid.DUST_G + water * SmokeGrid.DROP_G) / sum;
        return thick(w, g);
    }

    /** Цвет толстого слоя вещества с альбедо ω' и асимметрией g' (после дельта-Эддингтона), один канал. */
    static float thick(double w, double g) {
        double u = Math.sqrt(Math.max((1 - w) / (1 - w * g), 0));
        return (float) Math.pow(Math.max(0, (1 - u) / (1 + u)), 1 / 2.2);
    }

    /**
     * Альбедо и асимметрия вещества, с которыми запечены карты клуба дыма и клуба брызг ({@code tools/bake_fx/bake.py}:
     * {@code PUFF_ALBEDO}, {@code PUFF_G}, {@code SPRAY_ALBEDO}, {@code SPRAY_G}).
     */
    static final double BAKED_W = 0.55, BAKED_G = 0.55, SPRAY_W = 0.99, SPRAY_G = 0.85;
    /** Затухание света в толще k двух потоков у вещества запекания дыма и брызг (после дельта-Эддингтона, как у {@link SmokeGrid}). */
    static final double BAKED_K = deltaK(BAKED_W, BAKED_G), SPRAY_K = deltaK(SPRAY_W, SPRAY_G);

    /** k двух потоков вещества с альбедо w и асимметрией g после дельта-Эддингтона (f = g²). */
    static double deltaK(double w, double g) {
        double f = g * g;
        return twoStream((1 - f) * w / (1 - f * w), g / (1 + g));
    }

    /** Затухание рассеянного света в толще, на единицу толщи: k = √(3(1 − ω')(1 − ω'g')) (два потока Эддингтона). */
    static double twoStream(double w, double g) {
        return Math.sqrt(Math.max(3 * (1 - w) * (1 - w * g), 0));
    }

    /**
     * Затухание света в толще смеси, k двух потоков; степень тени карт — k смеси / k вещества запекания этого клуба
     * ({@link #BAKED_K} у дыма, {@link #SPRAY_K} у брызг). Карты клуба — свет, прошедший его толщу τ (≈ e^(−kτ) против
     * среднего по клубу), поэтому у смеси с другим альбедо карта берётся в этой степени ({@code fx.fsh}): белый дым окиси алюминия и капли (ω' ≈ 0,999) почти не дают тени — светлые насквозь,
     * как облако; сажа (ω' ≈ 0,2) — темнее запечённого клуба. ⚠ Приближение: путь света в клубе один и тот же, меняется
     * только затухание на нём. Доли — по ослаблению, как у {@link #thick}.
     */
    static double shade(double soot, double dust, double water, float dustW) {
        double sum = soot + dust + water;
        if (!(sum > 0)) return BAKED_K;
        double w = (soot * SmokeGrid.SOOT_W + dust * dustW + water * SmokeGrid.DROP_W) / sum;
        double g = (soot * SmokeGrid.SOOT_G + dust * SmokeGrid.DUST_G + water * SmokeGrid.DROP_G) / sum;
        return twoStream(w, g);
    }

    /** Перемешивание битов (splitmix64): своё случайное у каждого клуба без объектов на кадр. */
    private static long mix(long z) {
        z = (z ^ z >>> 30) * 0xBF58476D1CE4E5B9L;
        z = (z ^ z >>> 27) * 0x94D049BB133111EBL;
        return z ^ z >>> 31;
    }

    /** Доля 0..1 из младших бит. */
    private static double unit(long h) {
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }

    /** Колокол без хвостов из трёх долей (клуб не улетает от середины). */
    private static double gauss(long h) {
        return (unit(h) + unit(h >>> 24) + unit(h >>> 40) - 1.5) * 1.15;
    }

    private static double smoothstep(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }
}
