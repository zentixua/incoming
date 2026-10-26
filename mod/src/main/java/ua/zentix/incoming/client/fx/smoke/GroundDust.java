package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.nuclear.model.MushroomModel;

/**
 * Пыль, которую поднимает ветер за фронтом волны обычного взрыва, — тот же закон, что у ядерки ({@link DustModel}), без
 * Minecraft. Метры, секунды, килограммы; высоты — от точки взрыва.
 * <ul>
 * <li><b>Откуда.</b> С каждой поверхности вокруг — там, где она есть (по секторам {@link #SECTORS} и радиусам
 * {@link #BANDS}: верхний блок, {@code FarBlasts.ground}), без «земля это или крыша»: в каждом кольце — слой
 * {@link DustModel#chemical} (давление, фаза и доля напора вдоль поверхности — {@link HobBlast} по высоте взрыва над
 * этой поверхностью, мощность — ядерная того же давления, {@link CloudModel#equivalentKt}): грунт уходит в воздух с
 * прихода фронта ({@link HobBlast#arrivalSeconds}) долей {@link DustModel#lifted} за положительную фазу на месте. До
 * фронта пыли нет, у разрыва высоко над поверхностью (давление ниже порога срыва) — нет совсем.</li>
 * <li><b>Поверхность.</b> Сколько пыли на ней лежит и как легко её сорвать — по материалу: голый рыхлый грунт (земля,
 * песок, гравий, снег) срывается с u* в 0,34 м/с, грунт под дёрном и листвой — от 3 м/с (Gaj &amp; Small, §2.2), у
 * твёрдого (камень, кирпич, доски, металл, лёд) рыхлого — только осевшая на нём пыль ({@code DustModel.Surface.HARD}:
 * 0,6 г/м², AP-42 §13.2.1). Крыша из камня и скала — одно и то же; дробит перекрытие сам заряд у места удара — это
 * пыль воронки в облаке ({@link CloudModel}). Вода не пылит.</li>
 * <li><b>Под шаром.</b> Пыль, сорванная под огненным шаром продуктов (он касается поверхности), — в его горячем газе и
 * поднимается с облаком, а не стоит у земли (как у ядерки: в шаре на земле пыли волны нет, {@code BlastWall.underBall}):
 * её отдаёт {@link #underBall}.</li>
 * <li><b>Высота.</b> В фазе волны — пыльный слой Kuhl (у фронта ноль, к концу фазы — {@link DustModel.Column#first},
 * десятки сантиметров у зарядов в десятки кг); дальше вспышки, что греет землю, у ВВ нет, и слой растит турбулентность
 * воздуха: σ по вертикали — Бриггс по классу Паскуилла на пути ветра, в квадратуре с верхом Kuhl (σ = верх / 2,5 —
 * ⚠ форма слоя). Слой лежит на своей поверхности — гаусс с отражением от неё: середина массы — на 0,8σ над ней.</li>
 * <li><b>Тяжесть.</b> Холодный воздух с пылью тяжелее чистого (плотность смеси растёт с массой пыли в объёме): слой —
 * гравитационное течение. Он растекается по поверхности (сток пласта постоянного объёма, Huppert &amp; Simpson 1980:
 * dR/dt = Fr·√(g′h), Fr = {@value #FR}, g′V = g·M/ρ — R² = R₀² + 2Fr·√(g·M/(πρ))·t; M — пыль, что ещё в воздухе), а
 * над перепадом вниз (край крыши, обрыв, склон) опускается к поверхности под собой — тяжёлый термик MTT той же массы
 * (Boussinesq: тот же закон, что у тёплого, с обратным знаком; {@link #fall}) или ветром за кромкой, что быстрее; по
 * склону до 45° слой идёт по поверхности вверх и вниз, на стену круче не взбирается ({@code level}).</li>
 * <li><b>Оседание.</b> Корзины {@link DustModel} выпадают из слоя со скоростью Стокса по размеру: у гаусса с отражением
 * концентрация у поверхности — масса / (1,25σ), поток вниз — v·масса / (1,25σ); по ∫dt/(1,25σ) — доля ослабления, что
 * ещё в воздухе ({@link DustModel#extinctionAloft}).</li>
 * <li><b>Ножка.</b> Подсос у оси — тот же член, что у ядерки ({@link DustModel.Column#stem}, {@link DustModel#STEM_S}):
 * из колец ближе {@code STEM_M}·∛(Y/15) он забирает stem/STEM_S их пыли в секунду, пока слой над ними — ветер уносит
 * слой из-под облака за полрадиуса подсоса. Шар обычного взрыва касается земли, поэтому пыль идёт по всей ножке до низа
 * облака сразу ({@code StemDust}, случай следа шара, ⚠ оценка), с воздухом ножки — долей
 * {@link MushroomModel#STEM_INFLOW} подъёма облака; дошедшая до низа облака — уже в облаке.</li>
 * <li><b>Снос.</b> Слой уходит с ветром на высоте своей середины (не ниже 2 м) и расплывается по Бриггсу; поток волны
 * наружу пыль не сносит — она стоит там, где её сорвало.</li>
 * </ul>
 * Горячие продукты взрыва и топлива всплывают и уносят то, что в них, — это облако ({@link CloudModel}), а не слой.
 * Картинке слой отдаёт кольца показа ({@link #ANNULI}: по радиусу поверхности) по секторам: у каждого — своя масса,
 * средний по массе радиус и поверхность, с которой пыль подняло; видимый край — не дальше фронта в этот миг.
 */
public final class GroundDust {
    /** Секторов по направлению от точки удара: у каждого — своя поверхность ({@link Ground}). */
    public static final int SECTORS = 8;

    /** Радиусы, на которых известна поверхность ({@link Ground}), м: кольцу — ближайший по логарифму. */
    public static final double[] BANDS = {3, 6, 12, 24, 48};

    /**
     * Рельеф вокруг: высота поверхности (первый воздух над верхним блоком) над точкой взрыва в месте (x, z) от неё, м;
     * {@link Double#NaN} — не знаем.
     */
    @FunctionalInterface
    public interface Relief {
        Relief UNKNOWN = (x, z) -> Double.NaN;

        double at(double x, double z);
    }

    /**
     * Что лежит вокруг удара по секторам и радиусам ({@code [сектор][радиус BANDS]}; направление k — угол
     * 2π(k + ½)/{@link #SECTORS} от оси x к оси z): доли голого рыхлого грунта, грунта под дёрном или листвой и твёрдого;
     * остальное — вода (с неё пыли нет). {@code height} — высота этой поверхности над точкой взрыва, м ({@link Double#NaN}
     * — не знаем: ровная поверхность на высоте подрыва ниже); {@code relief} — рельеф в любом месте (куда слой стекает).
     */
    public record Ground(double[][] loose, double[][] covered, double[][] hard, double[][] height, Relief relief) {
        /** Всюду голый грунт — как у ядерки ({@code Surface.REFERENCE}); так и там, где клиент ничего не знает. */
        public static final Ground BARE = uniform(1, 0);

        /** Рыхлое и дёрн по секторам, ровная поверхность на высоте подрыва ниже, рельефа нет. */
        public Ground(double[][] loose, double[][] covered) {
            this(loose, covered, filled(0), filled(Double.NaN), Relief.UNKNOWN);
        }

        public static Ground uniform(double loose, double covered) {
            return new Ground(filled(loose), filled(covered));
        }

        /** Таблица [сектор][радиус] с одним числом. */
        public static double[][] filled(double v) {
            double[][] a = new double[SECTORS][BANDS.length];
            for (double[] row : a) java.util.Arrays.fill(row, v);
            return a;
        }
    }

    /** Радиус поверхности для кольца радиуса r: ближайший по логарифму из {@link #BANDS}. */
    static int band(double r) {
        int best = 0;
        double d = Double.MAX_VALUE, lr = Math.log(Math.max(r, 1e-3));
        for (int b = 0; b < BANDS.length; b++) {
            double e = Math.abs(Math.log(BANDS[b]) - lr);
            if (e < d) {
                d = e;
                best = b;
            }
        }
        return best;
    }

    /**
     * Колец счёта (по логарифму радиуса) и колец показа: кольцо показа — кольца счёта у одного радиуса поверхности
     * ({@link #band}), у его клуба в секторе одна поверхность — та, с которой его подняло.
     */
    static final int RINGS = 24, ANNULI = BANDS.length;
    /** Сетка времени таблиц: t_k = T0·(GROW^k − 1), с. */
    static final int STEPS = 112;
    static final double T0 = 0.01, GROW = 1.12;
    /** Внутренний край счёта и дальний — давление у поверхности, ниже которого грунт уже не срывает (psi; вынос ~0). */
    static final double INNER = 0.1, OUTER_PSI = 0.3;
    /**
     * Число Фруда головы гравитационного течения, мелкого против толщи воздуха (Huppert &amp; Simpson 1980, «The slumping
     * of gravity currents», JFM 99: 785, по опытам — 1,19).
     */
    static final double FR = 1.19;
    /**
     * Перепад поверхности на метр пути, с которого это край (вниз — слой над пустотой, падает) или стена (вверх — слой на
     * неё не взбирается), м: склон до 45° из ступеней в блок слой проходит по поверхности.
     */
    static final double DROP = 1;

    final CloudModel.Air air;
    final double yieldKt, hob;
    /**
     * Ветер у земли (на 2 м), м/с: путь ветра — каждому клубу каждый кадр, а воздух у слоя один на всё время (корень из
     * суммы квадратов — дорогой {@code Math.hypot}: у налёта из 100 ударов он брал восьмую часть процессора дыма).
     */
    private final double groundWind;
    /** Время, для которого посчитан узел сетки {@link #nodeAt} (один на кадр у всех таблиц слоя). */
    private double nodeT = Double.NaN, nodeAt;
    /** Кольца счёта: середина и площадь (одни на все секторы). */
    private final double[] r, area;
    /**
     * По секторам и кольцам: приход фронта, фаза, поднятое (кг, доля сектора), верх к концу фазы, подсос, его начало; под
     * шаром ли кольцо (его пыль — в облаке).
     */
    private final double[][] arrival, positive, mass, first, stem, stemFrom;
    private final boolean[][] ball;
    /** Высота поверхности над точкой взрыва по секторам и радиусам, м (неизвестна — ровная на {@link #hob} ниже). */
    private final double[][] surface;
    private final Relief relief;
    /** Юбка разрушенного: рождается на высоте точки удара и за краем стекает, а не появляется внизу готовой. */
    private final boolean skirt;
    /** Радиус юбки к {@link #SKIRT_S}, м (0 — не юбка). */
    private final double skirtR;
    private final int[] annulus;
    /** Куда клуб сектора идёт в кольце показа (угол, рад) и какая доля дуги сектора там открыта: юбка — по улицам. */
    private final double[][] heading = new double[SECTORS][ANNULI], openArc = new double[SECTORS][ANNULI];
    /** Во сколько раз дальше поля юбка сектора уходит по открытому (улица), 1 — поле. */
    private final double[] reach = new double[SECTORS];
    /** Стены повернули юбку ({@link #channel}); от какой высоты (м от точки взрыва) — стена. */
    private boolean walled;
    private double wallFrom;
    /** Высота стен над точкой удара, м: ниже — ветер вдоль улицы ({@link #canyon}). */
    private double wallTop;
    /** Путь клуба сектора по открытому ({@link #open}): {угол, радиус, открыт ли} на каждом полуметре радиуса. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private final java.util.List<double[]>[][] chain = new java.util.List[SECTORS][ANNULI];
    /** Таблицы по сетке времени: σ по вертикали и ∫dt/(1,25σ) у каждого кольца показа; подсос — доля его времени. */
    private final double[][] sv = new double[ANNULI][STEPS], settled = new double[ANNULI][STEPS];
    private final double[] stemTime = new double[STEPS];
    /** Пыль, ушедшая в ножку (ослабление в кг смеси {@link DustModel#K_MIX}), нарастающим итогом по сетке. */
    private final double[] drawn = new double[STEPS];
    /** Сток слоя: R² − R₀² по сетке, м²; R₀² — средний по массе квадрат радиуса, откуда пыль подняло. */
    private final double[] slump = new double[STEPS];
    final double slumpFrom;
    /** Поднятая масса у поверхности (без той, что под шаром), кг; радиус подсоса, м; сколько слой виден, с. */
    public final double total, stemReach;
    public final double life;
    /** С какого возраста подсос забирает пыль в ножку, с (бесконечность — не забирает). */
    public final double drawnFrom;
    private double boundInitialV;
    private final double[] boundRadius = new double[STEPS], boundWidth = new double[STEPS],
            windForward = new double[STEPS], windBackward = new double[STEPS];

    /**
     * Слой пыли заряда {@code tntKg} кг ТНТ на высоте {@code hobM} над голым грунтом в воздухе {@code air};
     * {@code ballM} — радиус огненного шара (касается ли он земли).
     */
    GroundDust(double tntKg, double hobM, double ballM, CloudModel.Air air) {
        this(tntKg, hobM, ballM, Ground.BARE, air);
    }

    /**
     * То же над поверхностью {@code ground} по секторам: у каждой — свой материал (рыхлый грунт {@code Surface.FIELD},
     * дёрн {@code COVERED}, твёрдое {@code HARD}, вода — без пыли) и своя высота; срыв по закону над каждой — по высоте
     * взрыва над ней (там, где высота неизвестна, — {@code hobM}).
     */
    GroundDust(double tntKg, double hobM, double ballM, Ground ground, CloudModel.Air air) {
        this(tntKg, hobM, ballM, ground, air, 0);
    }

    /**
     * Юбка пыли у земли (base surge): мелочь разрушенного {@code kg} кг, что поток газа взрыва выносит из пролома или
     * воронки вдоль поверхностей вокруг, — не готовым слоем у точки удара. Ebersole, Vaglio-Laurin, Dvore, ASL-CR-80-32
     * (1980): через {@value #SKIRT_S} с юбка — десятки метров (3×6,8 кг — 44×17 м, 3×55 кг — 54×30 м); радиус —
     * {@value #SKIRT_R0}·R₀, R₀ = 2·W^⅓ м (приближения DIRTRAN там же). Кольцо получает массу по площади и встаёт, когда до
     * него дошла юбка; дальше — тот же закон, что у пыли волны (ветер, оседание, стекание с краёв). ⚠ Оценка: верх юбки —
     * R₀ (у замеров — облако целиком, с поднимающейся частью), раздача по площади ровная.
     */
    static GroundDust skirt(double tntKg, double hobM, double kg, Ground ground, CloudModel.Air air) {
        // юбка идёт от точки удара: каждое кольцо рождается на её высоте, а с края (крыша, обрыв) стекает законом слоя
        Ground source = new Ground(ground.loose(), ground.covered(), ground.hard(), Ground.filled(-Math.max(0, hobM)), ground.relief());
        return new GroundDust(kg > 0 ? tntKg : 0, hobM, 0, source, air, kg);
    }

    /** Когда юбка доходит до своего радиуса, с, и радиус в R₀ (Ebersole и др. 1980, DIRTRAN). */
    static final double SKIRT_S = 0.5, SKIRT_R0 = 2;

    private GroundDust(double tntKg, double hobM, double ballM, Ground ground, CloudModel.Air air, double skirtKg) {
        this.air = air;
        this.groundWind = Math.sqrt(air.windX() * air.windX() + air.windZ() * air.windZ()) * air.wind(2);
        this.yieldKt = CloudModel.equivalentKt(tntKg);
        this.hob = Math.max(0, hobM);
        this.relief = ground.relief();
        this.skirt = skirtKg > 0;
        // высота взрыва над каждой поверхностью: снизу (крыша, улица) или сверху (стена, склон выше точки) — по наклонной
        // дальности то же давление (⚠ отражение от поверхности над точкой — как от поверхности под ней)
        surface = new double[SECTORS][BANDS.length];
        double[][] over = new double[SECTORS][BANDS.length];
        double outer = 0;
        for (int k = 0; k < SECTORS; k++) {
            for (int b = 0; b < BANDS.length; b++) {
                double h = ground.height()[k][b];
                surface[k][b] = Double.isNaN(h) ? -hob : h;
                over[k][b] = rounded(Math.abs(surface[k][b]));
                if (tntKg > 0) outer = Math.max(outer, HobBlast.rangeFor(OUTER_PSI, over[k][b], yieldKt));
            }
        }
        double[] rr = new double[RINGS], aa = new double[RINGS];
        double r0 = 2 * Math.cbrt(Math.max(tntKg, 0)), skirtR = skirtKg > 0 ? SKIRT_R0 * r0 : 0;
        this.skirtR = skirtR;
        if (outer > INNER) {
            for (int i = 0; i < RINGS; i++) {
                double lo = i == 0 ? 0 : INNER * Math.pow(outer / INNER, (double) i / RINGS), r1 = INNER * Math.pow(outer / INNER, (i + 1.0) / RINGS);
                rr[i] = i == 0 ? 0.5 * r1 : Math.sqrt(lo * r1);
                // кольцо юбки — в середине по площади той его части, что внутри юбки: масса там ровная по площади, а кольцо
                // на краю юбки иначе стояло бы в середине всего кольца, за её краем
                double hi = Math.min(r1, skirtR);
                if (lo < skirtR) rr[i] = 2.0 / 3 * (hi * hi * hi - lo * lo * lo) / (hi * hi - lo * lo);
                aa[i] = Math.PI * (r1 * r1 - lo * lo);
            }
        }
        r = rr;
        area = aa;
        arrival = new double[SECTORS][RINGS];
        positive = new double[SECTORS][RINGS];
        mass = new double[SECTORS][RINGS];
        first = new double[SECTORS][RINGS];
        stem = new double[SECTORS][RINGS];
        stemFrom = new double[SECTORS][RINGS];
        ball = new boolean[SECTORS][RINGS];
        double hardSupply = DustModel.Surface.HARD.supply();
        for (int k = 0; k < SECTORS && outer > INNER; k++) {
            for (int i = 0; i < RINGS; i++) {
                int b = band(r[i]);
                Rings f = rings(tntKg, over[k][b], ballM, DustModel.Surface.FIELD, outer), c = rings(tntKg, over[k][b], ballM, DustModel.Surface.COVERED, outer);
                // на твёрдом рыхлого — только осевшая пыль: вынос того же закона, не больше её запаса (порог — как у грунта);
                // на дёрне — тоже только то, что лежит на траве: грунт под ним держат корни; мелочь грунта даёт воронка (её
                // юбка), крошку сорванной травы — FrontDust (крупная, выпадает за секунды). ⚠ Оценка: пыль на траве — как на
                // тихой дороге (запас HARD); прежде дёрн отдавал сантиметр грунта, как голая земля, и у удара у газона в
                // 40 м на 6 с стояла бурая стена
                double hard = Math.min(hardSupply * area[i], f.mass[i]), turf = Math.min(hardSupply * area[i], c.mass[i]);
                mass[k][i] = (ground.loose()[k][b] * f.mass[i] + ground.covered()[k][b] * turf + ground.hard()[k][b] * hard) / SECTORS;
                arrival[k][i] = f.arrival[i];
                positive[k][i] = f.positive[i];
                first[k][i] = f.first[i];
                stem[k][i] = f.stem[i];
                stemFrom[k][i] = f.stemFrom[i];
                ball[k][i] = f.ball[i];
                if (skirtKg > 0) {
                    // края кольца (кольца счёта — логарифмические): доля площади диска юбки в кольце
                    double lo = i == 0 ? 0 : INNER * Math.pow(outer / INNER, (double) i / RINGS), hi = INNER * Math.pow(outer / INNER, (i + 1.0) / RINGS);
                    double in = Math.max(0, Math.min(hi, skirtR) * Math.min(hi, skirtR) - Math.min(lo, skirtR) * Math.min(lo, skirtR));
                    mass[k][i] = skirtR > 0 ? skirtKg * in / (skirtR * skirtR) / SECTORS : 0;
                    // юбка доходит до кольца с той же скоростью и за своим краем: фронт за краем не прыгает к концу колец
                    arrival[k][i] = SKIRT_S * lo / Math.max(skirtR, 1e-9);
                    positive[k][i] = Math.max(SKIRT_S * (Math.min(hi, skirtR) - Math.min(lo, skirtR)) / Math.max(skirtR, 1e-9), 0.05);
                    first[k][i] = r0;
                    // мелочь пролома — не вовлечённая в шар и не втянутая ножкой: её не считать второй раз
                    stem[k][i] = 0;
                    ball[k][i] = false;
                }
            }
        }
        for (int k = 0; k < SECTORS; k++) {
            java.util.Arrays.fill(heading[k], 2 * Math.PI * (k + 0.5) / SECTORS);
            java.util.Arrays.fill(openArc[k], 1);
            reach[k] = 1;
        }
        // юбку несёт газ вдоль поверхностей: в сторону стены выше удара (дом у улицы) он не идёт, а уходит в открытые
        // стороны того же кольца — в улицы; иначе на перекрёстке квартала большая часть пыли лежала бы внутри домов, где её
        // не видно (⚠ оценка: раздача по открытой дуге ровная, стены выше удара на WALL — не пускают)
        if (skirtKg > 0 && outer > INNER) channel(-hob);
        double sum = 0;
        for (int k = 0; k < SECTORS; k++) for (int i = 0; i < RINGS; i++) if (!ball[k][i]) sum += mass[k][i];
        total = sum;
        stemReach = DustModel.STEM_M * Math.cbrt(yieldKt / 15);
        // кольца показа — по радиусам поверхности
        annulus = new int[RINGS];
        for (int i = 0; i < RINGS; i++) annulus[i] = band(r[i]);
        // таблицы: σ по вертикали (верх Kuhl к концу фазы и турбулентность воздуха), оседание, подсос
        double[] top = new double[ANNULI], weight = new double[ANNULI];
        double from = Double.MAX_VALUE, r2 = 0;
        for (int k = 0; k < SECTORS; k++) {
            if (RINGS > 0) from = Math.min(from, stemFrom[k][0]);
            for (int i = 0; i < RINGS; i++) {
                if (ball[k][i]) continue;
                top[annulus[i]] += mass[k][i] * first[k][i];
                weight[annulus[i]] += mass[k][i];
                r2 += mass[k][i] * r[i] * r[i];
            }
        }
        for (int q = 0; q < ANNULI; q++) top[q] = weight[q] > 0 ? top[q] / weight[q] : 0.1;
        slumpFrom = sum > 0 ? Math.max(r2 / sum, 1) : 1;
        for (int s = 1; s < STEPS; s++) {
            double t0 = time(s - 1), t1 = time(s), dt = t1 - t0, mid = 0.5 * (t0 + t1);
            for (int q = 0; q < ANNULI; q++) {
                sv[q][s] = sigmaV(top[q], t1);
                settled[q][s] = settled[q][s - 1] + dt / (1.25 * sigmaV(top[q], mid));
            }
            stemTime[s] = stemTime[s - 1] + (mid > from ? dt * under(mid) : 0);
        }
        for (int q = 0; q < ANNULI; q++) sv[q][0] = sigmaV(top[q], 0);
        // ножка: ушедшее в подсос — нарастающим итогом (ослабление в кг смеси); сток слоя — по пыли, что ещё в воздухе
        for (int s = 1; s < STEPS; s++) {
            double t = 0.5 * (time(s - 1) + time(s)), flux = 0, aloft = 0;
            for (int k = 0; k < SECTORS; k++) {
                for (int i = 0; i < RINGS; i++) {
                    if (ball[k][i] || t <= arrival[k][i]) continue;
                    if (t > stemFrom[k][i]) flux += stem[k][i] / DustModel.STEM_S * under(t) * ringNow(k, i, t);
                    aloft += massNow(k, i, t);
                }
            }
            double dt = time(s) - time(s - 1);
            drawn[s] = drawn[s - 1] + flux * dt;
            slump[s] = slump[s - 1] + 2 * FR * Math.sqrt(CloudModel.G * aloft / (Math.PI * CloudModel.RHO)) * dt;
        }
        int s0 = 1;
        while (s0 < STEPS && drawn[s0] <= 0) s0++;
        drawnFrom = s0 < STEPS ? time(s0 - 1) : Double.POSITIVE_INFINITY;
        life = lifeSeconds();
        for (int k = 0; k < SECTORS; k++) for (int i = 0; i < RINGS; i++) if (!ball[k][i] && mass[k][i] > 0) {
            boundInitialV = Math.max(boundInitialV, first[k][i] / 2.5);
        }
        support();
    }

    /**
     * Interval bounds for the moments used by emit. Settling is common to a display annulus and cancels
     * from its normalized moments. Lift only increases and stem survival only decreases: their endpoint
     * products bound each ring's weight throughout an interval, including between table samples.
     * This is built once, without terrain. Using the outermost ring as every puff's width made the old
     * envelope several kilometres wider than the cloud after a raid.
     */
    private void support() {
        double[] upper = new double[RINGS];
        for (int s = 0; s < STEPS; s++) {
            double t0 = time(s), t1 = s + 1 < STEPS ? time(s + 1) : Math.max(t0, CloudModel.LIFE_MAX);
            double stem0 = at(stemTime, t0), stem1 = at(stemTime, t1);
            boundWidth[s] = -1;
            for (int k = 0; k < SECTORS; k++) for (int a = 0; a < ANNULI; a++) {
                double lower = 0, all = 0, moment = 0, lo = Double.POSITIVE_INFINITY, hi = 0;
                for (int i = 0; i < RINGS; i++) {
                    upper[i] = 0;
                    if (annulus[i] != a || ball[k][i] || mass[k][i] <= 0) continue;
                    double m = mass[k][i];
                    lower += m * liftedShare(k, i, t0) * Math.exp(-stem[k][i] * stem1 / DustModel.STEM_S);
                    double u = m * liftedShare(k, i, t1) * Math.exp(-stem[k][i] * stem0 / DustModel.STEM_S);
                    if (u <= 0) continue;
                    upper[i] = u; all += u; moment += u * r[i];
                    lo = Math.min(lo, r[i]); hi = Math.max(hi, r[i]);
                }
                // Same cutoff as emit, with an upper bound on mass over the entire interval.
                if (all * DustModel.extinctionAloft(at(settled[a], t0)) <= 1e-9) continue;
                double mean = lower > 0 ? Math.min(hi, moment / lower) : hi;
                double anchor = moment / all, variance = 0;
                for (int i = 0; i < RINGS; i++) variance += upper[i] * (r[i] - anchor) * (r[i] - anchor);
                double limit = 0.25 * (hi - lo) * (hi - lo);
                variance = lower > 0 ? Math.min(limit, variance / lower) : limit;
                double own = Math.max(Math.sqrt(variance) * reach[k], Math.PI * mean / SECTORS / 1.25 * openArc[k][a]);
                boundRadius[s] = Math.max(boundRadius[s], mean * reach[k]);
                boundWidth[s] = Math.max(boundWidth[s], own);
            }
            // A cached terrain path can have been prepared with an earlier distribution of mass.
            // Its accumulated radial delay is bounded by the largest earlier mean times total growth.
            if (s > 0) boundRadius[s] = Math.max(boundRadius[s], boundRadius[s - 1]);
        }
        // The terrain integrator can retain any fraction of a wind increment. On an open surface it
        // cannot rotate that increment: bound its scalar forward/backward travel on the very same PATH.
        // Include the next path node, because the displayed state interpolates towards that node.
        for (int a = 0; a < ANNULI; a++) {
            int p = 1; double previous = 0, forward = 0, backward = 0;
            for (int s = 0; s < STEPS; s++) {
                double end = s + 1 < STEPS ? time(s + 1) : CloudModel.LIFE_MAX;
                while (p < PATH.length && PATH[p - 1] <= end) {
                    double t = PATH[p++], value = air.wind(Math.max(2, 0.8 * at(sv[a], t))) * t, change = value - previous;
                    forward += Math.max(0, change); backward += Math.max(0, -change); previous = value;
                }
                windForward[s] = Math.max(windForward[s], forward);
                windBackward[s] = Math.max(windBackward[s], backward);
            }
        }
    }

    /** Середина кольца счёта i, м (проверки). */
    double ring(int i) {
        return r[i];
    }

    /** Поднятое кольца i сектора k, кг (проверки). */
    double mass(int k, int i) {
        return mass[k][i];
    }

    /** Стена выше точки удара на столько, м, юбку в свою сторону не пускает (ниже — бордюр, ступень, газ идёт поверх). */
    static final double WALL = 2;

    /**
     * Уровень, от которого стоят стены у удара на высоте {@code source}, м от точки взрыва: нижняя четверть рельефа на
     * кольце от края юбки до двух её радиусов (там улицы, а не яма удара), но не ниже самого удара. Удар в воронку прежнего,
     * в канаву, в яму — стены считаются от поля вокруг, и края ямы стеной не держат (иначе пыль стояла бы в яме, пока клуб
     * не перерос её край); удар на улице — уровень улицы (дома квартала занимают меньше трёх четвертей кольца); удар на
     * крыше — крыша. Рельеф неизвестен — сам удар.
     */
    private double floor(double source) {
        if (skirtR <= 0) return source;
        double[] h = new double[4 * SECTORS * ARC];
        int n = 0;
        for (int i = 0; i < 4; i++) {
            double rho = skirtR * (1 + i / 3.0);
            for (int j = 0; j < SECTORS * ARC; j++) {
                double phi = 2 * Math.PI * (j + 0.5) / (SECTORS * ARC), v = relief.at(rho * Math.cos(phi), rho * Math.sin(phi));
                if (!Double.isNaN(v)) h[n++] = v;
            }
        }
        if (n == 0) return source;
        java.util.Arrays.sort(h, 0, n);
        return Math.max(source, h[n / 4]);
    }

    /** Выборок рельефа на дуге сектора в кольце: улица в 6 м на 10 м от удара — дуга в 35°, 4 выборки из 45° сектора. */
    static final int ARC = 9;

    /**
     * Юбка уходит с дуги кольца, где поверхность (рельеф на радиусе кольца, м от точки взрыва; неизвестна — открыто) выше
     * {@code source} на {@link #WALL}, на открытую дугу того же кольца — поровну по длине; клуб сектора идёт по середине
     * его открытой дуги и не шире её. Всё кольцо закрыто (двор, яма) — пыль остаётся, как поднята. Стена считается от
     * земли вокруг ({@link #floor}), если удар ниже неё: края воронки и поле вокруг ямы — не стены, юбка выходит из ямы как в
     * поле.
     */
    private void channel(double source) {
        source = floor(source);
        double[][] open = new double[SECTORS][RINGS], angle = new double[SECTORS][RINGS];
        for (int i = 0; i < RINGS; i++) {
            double ring = 0, all = 0;
            for (int k = 0; k < SECTORS; k++) {
                ring += mass[k][i];
                int n = 0;
                double sum = 0;
                for (int j = 0; j < ARC; j++) {
                    double phi = 2 * Math.PI * (k + (j + 0.5) / ARC) / SECTORS, h = relief.at(r[i] * Math.cos(phi), r[i] * Math.sin(phi));
                    if (h > source + WALL) continue;
                    n++;
                    sum += phi;
                }
                open[k][i] = (double) n / ARC;
                angle[k][i] = n == ARC || n == 0 ? 2 * Math.PI * (k + 0.5) / SECTORS : sum / n;
                all += open[k][i];
            }
            if (all <= 0 || all >= SECTORS || ring <= 0) {
                for (int k = 0; k < SECTORS; k++) {
                    open[k][i] = 1;
                    angle[k][i] = 2 * Math.PI * (k + 0.5) / SECTORS;
                }
                continue;
            }
            for (int k = 0; k < SECTORS; k++) mass[k][i] = ring * open[k][i] / all;
            walled = true;
        }
        if (!walled) return;
        wallFrom = source + WALL;
        // высота стен — средняя по закрытым выборкам дуг
        double top = 0;
        int tops = 0;
        for (int i = 0; i < RINGS; i++) {
            for (int k = 0; k < SECTORS; k++) {
                for (int j = 0; j < ARC; j++) {
                    double phi = 2 * Math.PI * (k + (j + 0.5) / ARC) / SECTORS, h = relief.at(r[i] * Math.cos(phi), r[i] * Math.sin(phi));
                    if (!(h > wallFrom)) continue;
                    top += h - source;
                    tops++;
                }
            }
        }
        wallTop = tops > 0 ? top / tops : 0;
        for (java.util.List<double[]>[] row : chain) java.util.Arrays.fill(row, null);
        axes.clear();
        // клуб сектора в кольце показа — середина открытой дуги его колец по массе
        for (int k = 0; k < SECTORS; k++) {
            double[] m = new double[ANNULI], a = new double[ANNULI], o = new double[ANNULI];
            for (int i = 0; i < RINGS; i++) {
                int q = band(r[i]);
                m[q] += mass[k][i];
                a[q] += mass[k][i] * angle[k][i];
                o[q] += mass[k][i] * open[k][i];
            }
            for (int q = 0; q < ANNULI; q++) {
                if (m[q] <= 0) continue;
                heading[k][q] = a[q] / m[q];
                openArc[k][q] = o[q] / m[q];
            }
        }
        // газ юбки занимает ту же площадь, что в поле: где расходиться некуда (улица меж домов), он уходит дальше —
        // как плотный поток в канале. Сектор с долей массы m/M занимает по открытому площадь (m/M)·πR²: дальность —
        // радиус, до которого открытая площадь сектора её набирает (⚠ оценка: глубина потока та же, что в поле; не дальше
        // REACH_MAX·R)
        double whole = 0;
        double[] sector = new double[SECTORS];
        for (int k = 0; k < SECTORS; k++) for (int i = 0; i < RINGS; i++) sector[k] += mass[k][i];
        for (double v : sector) whole += v;
        if (whole <= 0 || skirtR <= 0) return;
        for (int k = 0; k < SECTORS; k++) {
            double need = sector[k] / whole * Math.PI * skirtR * skirtR, got = 0, rho = 0, dr = Math.max(0.5, skirtR / 40);
            while (got < need && rho < REACH_MAX * skirtR) {
                double mid = rho + dr / 2;
                int n = 0;
                for (int j = 0; j < ARC; j++) {
                    double phi = 2 * Math.PI * (k + (j + 0.5) / ARC) / SECTORS;
                    if (!(relief.at(mid * Math.cos(phi), mid * Math.sin(phi)) > source + WALL)) n++;
                }
                double add = 2 * Math.PI / SECTORS * mid * dr * n / ARC;
                if (got + add >= need && add > 0) {
                    rho += dr * (need - got) / add;
                    got = need;
                } else {
                    got += add;
                    rho += dr;
                }
            }
            reach[k] = Math.max(1, Math.min(REACH_MAX, rho / skirtR));
        }
    }

    /**
     * Место клуба юбки сектора k в кольце показа q, растянутого до радиуса rad (м от точки взрыва): клуб идёт от удара
     * по открытому — на каждом полуметре радиуса угол на дуге сектора, открытый там и ближайший к углу на прошлом
     * полуметре (в начале — к середине открытой дуги колец); открытого на дуге нет — клуб стоит на прошлом полуметре:
     * дальше стены юбка в этой стороне не идёт. Клуб, растянутый дальностью и стоком, так остаётся в улице, а не в доме,
     * и у удара не в середине перекрёстка, а идёт по той улице, в которую вошёл (ближайший к середине дуги угол на
     * каждом радиусе заново перескакивал бы в соседнюю улицу: клуб прыгал на десяток метров); шаг цепочки — не дальше
     * {@value #CHAIN_STEP} м от прошлого места, поэтому за стеной клуб не появляется. Цепочка — одна для всех
     * кадров (не зависит от их частоты); {угол, радиус}. Снос ветром ниже крыш держит путь клуба ({@link #step}).
     */
    private double[] open(int k, int q, double rad) {
        int at = (int) Math.round(2 * rad);
        java.util.List<double[]> c = chain[k][q];
        if (c == null) {
            c = chain[k][q] = new java.util.ArrayList<>();
            c.add(new double[]{heading[k][q], 0, 1});
        }
        double from = 2 * Math.PI * k / SECTORS;
        while (c.size() <= at) {
            SmokePreparation.checkpoint();
            double[] last = c.get(c.size() - 1);
            double r1 = 0.5 * c.size(), gap = Double.MAX_VALUE;
            double[] next = {last[0], last[1], 0};
            for (int j = 0; j < 2 * ARC; j++) {
                double phi = from + 2 * Math.PI / SECTORS * (j + 0.5) / (2 * ARC);
                double x = r1 * Math.cos(phi), z = r1 * Math.sin(phi);
                // шаг пути — по открытому и рядом с прошлым местом: через стену в соседнюю улицу клуб не перескакивает
                double far = Math.hypot(x - last[1] * Math.cos(last[0]), z - last[1] * Math.sin(last[0]));
                if (far > CHAIN_STEP || relief.at(x, z) > wallFrom) continue;
                if (Math.abs(phi - last[0]) < gap) {
                    gap = Math.abs(phi - last[0]);
                    next = new double[]{phi, r1, 1};
                }
            }
            c.add(next);
        }
        double[] here = c.get(at);
        // открыто на этом полуметре — на самом радиусе, иначе — где клуб встал
        return new double[]{here[0], here[2] > 0 ? rad : here[1]};
    }

    /** Шаг пути клуба по открытому ({@link #open}) не длиннее, м. */
    static final double CHAIN_STEP = 1.5;

    /** Угол между осью m (шаг π/8) и направлением a, рад, без знака направления оси. */
    private static double off(int m, double a) {
        return Math.abs(Math.IEEEremainder(a - Math.PI * m / 8, Math.PI));
    }

    /**
     * Сколько сноса ветром (sx, sz) за выборку пути клуба юбки с места (x, z) в возрасте tau, лежащего на уровне
     * {@code level}, держит улица (x, z). Ниже крыш ветер в каньоне идёт вдоль улицы — поперёк его держат стены (Dobre et
     * al. 2005, Atmos. Environ. 39:4647: составляющая вдоль каньона — проекция ветра над крышами на его ось): поперечная
     * доля у оси улицы там ({@link #axis}) — до половины высоты стен целиком (вихрь каньона), к их верху — всё меньше.
     * Остаток сноса упирается в стену выше и стен ({@link #wallFrom}), и середины клуба (ниже её клуб идёт над крышей) —
     * держится весь: клуб стоит у стены. Задержка копится выборками, как у запруды, поэтому смена оси на пути место не
     * сдвигает.
     */
    private double[] canyon(int show, double tau, double x, double z, double sx, double sz, double level) {
        double zc = 0.8 * at(sv[show], tau), w = wallTop > 0 ? Math.clamp(2 - 2 * zc / wallTop, 0, 1) : 0, hx = 0, hz = 0;
        if (w > 0) {
            double a = axis(x, z, Math.atan2(sz, sx));
            if (!Double.isNaN(a)) {
                double ex = Math.cos(a), ez = Math.sin(a), par = sx * ex + sz * ez;
                hx = w * (sx - par * ex);
                hz = w * (sz - par * ez);
            }
        }
        if (relief.at(x + sx - hx, z + sz - hz) > Math.max(wallFrom, level + zc)) return new double[]{sx, sz};
        return new double[]{hx, hz};
    }

    /**
     * Клуб юбки в (x, z), середина на высоте {@code middle}, в стене выше и стен ({@link #wallFrom}), и середины:
     * ближайшее открытое место (кольцами через {@value #OUT_STEP} м до {@value #OUT_MAX} м, 16 направлений). Путь клуба
     * держит снос и рост в стену сам ({@link #step}), но по выборкам: между узлами пути задержка идёт по прямой, а снос —
     * нет, и у края улицы середина клуба бывает в стене на метр с небольшим (замер на городе теста) — здесь она выходит к
     * ней. Дальше открытого нет — как есть.
     */
    private double[] out(double x, double z, double middle) {
        double top = Math.max(wallFrom, middle);
        if (!(relief.at(x, z) > top)) return new double[]{x, z};
        for (double r = OUT_STEP; r <= OUT_MAX + 1e-9; r += OUT_STEP) {
            for (int q = 0; q < 16; q++) {
                double a = Math.PI * q / 8, px = x + r * Math.cos(a), pz = z + r * Math.sin(a);
                if (!(relief.at(px, pz) > top)) return new double[]{px, pz};
            }
        }
        return new double[]{x, z};
    }

    /** Шаг и предел поиска открытого места у клуба в стене ({@link #out}), м. */
    static final double OUT_STEP = 0.25, OUT_MAX = 1.5;

    /** Сколько сноса до начала пути (возраст t, место клуба {@code at}) держит улица ({@link #canyon}, одной выборкой). */
    private double[] young(int show, double t, double[] at, double level) {
        if (!walled) return new double[]{0, 0};
        double[] d = drift(t, 0.8 * at(sv[show], t));
        return canyon(show, t, at[0] - d[0], at[1] - d[1], d[0], d[1], level);
    }

    /** Оси улиц ({@link #axis}) по клеткам {@value #AXIS_CELL} м и направлению (шаг π/8). */
    private final java.util.HashMap<Long, Double> axes = new java.util.HashMap<>();
    /** Клетка кэша оси улицы, м. */
    static final int AXIS_CELL = 2;

    /** Полудлина пробы оси улицы, м. */
    static final int AXIS_RUN = 8;

    /**
     * Ось улицы в точке (x, z) от точки взрыва, рад: из 8 направлений (шаг π/8) — то, вдоль которого в обе стороны (до
     * {@value #AXIS_RUN} м, полосой в 3 м) открыто дальше, чем поперёк, на больше всего (диагональ у перекрёстка открыта вдоль так же
     * далеко, как улица, но и поперёк — тоже); из равных — ближе к направлению {@code toward}. Поперёк открыто почти так же
     * (перекрёсток, площадь) или нигде не открыто — {@link Double#NaN}: ветер там не поворачивает.
     */
    private double axis(double x, double z, double toward) {
        long cx = Math.floorDiv((long) Math.floor(x), AXIS_CELL), cz = Math.floorDiv((long) Math.floor(z), AXIS_CELL);
        int bucket = Math.floorMod(Math.round(toward * 8 / Math.PI), 8);
        long key = ((cx & 0xFFFFF) << 24) | ((cz & 0xFFFFF) << 4) | bucket;
        Double known = axes.get(key);
        if (known != null) return known;
        // ось клетки — по её середине и середине корзины направлений, а не по месту первого вопроса: иначе ответ зависел бы
        // от того, какой кадр спросил первым (частота кадров, порядок клубов)
        x = (cx + 0.5) * AXIS_CELL;
        z = (cz + 0.5) * AXIS_CELL;
        toward = Math.PI * bucket / 8;
        // полосой из трёх линий через ±1,5 м: диагональ через угол перекрёстка одна линия проходит, полоса — нет
        double[] run = new double[8];
        for (int m = 0; m < 8; m++) {
            double c = Math.cos(Math.PI * m / 8), sn = Math.sin(Math.PI * m / 8);
            for (double lane = -1.5; lane <= 1.5; lane += 1.5) {
                double x0 = x - lane * sn, z0 = z + lane * c;
                for (int side = -1; side <= 1; side += 2) {
                    for (int step = 1; step <= AXIS_RUN; step++) {
                        if (relief.at(x0 + side * step * c, z0 + side * step * sn) > wallFrom) break;
                        run[m]++;
                    }
                }
            }
        }
        // улица — вдоль открыто далеко, поперёк — нет: больше всего разница; из равных — ближе к toward
        int best = 0;
        for (int m = 1; m < 8; m++) {
            double gain = run[m] - run[(m + 4) % 8], was = run[best] - run[(best + 4) % 8];
            if (gain > was || gain == was && off(m, toward) < off(best, toward)) best = m;
        }
        double a = run[best] - run[(best + 4) % 8] < 0.3 * run[best] ? Double.NaN : Math.PI * best / 8;
        axes.put(key, a);
        return a;
    }

    /** Дальше скольких радиусов поля юбка по улице не уходит (⚠ оценка: длина квартала и запас газа). */
    static final double REACH_MAX = 3;

    /** Во сколько раз дальше поля юбка сектора k (проверки). */
    double reach(int k) {
        return reach[k];
    }

    /** Угол клуба сектора k в кольце показа q, рад (проверки). */
    double heading(int k, int q) {
        return heading[k][q];
    }

    /**
     * Высота взрыва над поверхностью, округлённая для кэша колец: до 4 м — полметра, выше — шагом в 10 % (давление у
     * поверхности от неё меняется плавно).
     */
    static double rounded(double h) {
        if (h <= 4) return Math.round(2 * h) / 2.0;
        double step = Math.log(1.1);
        return Math.exp(Math.round(Math.log(h) / step) * step);
    }

    /** Кольца счёта одного заряда и высоты: от воздуха не зависят (залп «Града» считает их один раз). */
    private record Rings(double[] mass, double[] arrival, double[] positive, double[] first, double[] stem, double[] stemFrom, boolean[] ball) {
    }

    private static final java.util.Map<String, Rings> CACHE = new java.util.LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Rings> e) {
            return size() > 256;
        }
    };

    /**
     * Кольца счёта заряда {@code tntKg} на высоте {@code hob} над поверхностью {@code surface} с шаром {@code ballM} до
     * радиуса {@code outer} (из кэша; нет — считаются сейчас).
     */
    private static Rings rings(double tntKg, double hob, double ballM, DustModel.Surface surface, double outer) {
        String key = tntKg + "/" + hob + "/" + ballM + "/" + surface.threshold() + "/" + outer;
        synchronized (CACHE) {
            Rings k = CACHE.get(key);
            if (k != null) return k;
        }
        double y = CloudModel.equivalentKt(tntKg);
        double[] mass = new double[RINGS], arrival = new double[RINGS], positive = new double[RINGS], first = new double[RINGS],
                stem = new double[RINGS], stemFrom = new double[RINGS];
        boolean[] ball = new boolean[RINGS];
        for (int i = 0; i < RINGS; i++) {
            double r0 = i == 0 ? 0 : INNER * Math.pow(outer / INNER, (double) i / RINGS), r1 = INNER * Math.pow(outer / INNER, (i + 1.0) / RINGS);
            double r = i == 0 ? 0.5 * r1 : Math.sqrt(r0 * r1);
            DustModel.Column c = DustModel.chemical(r, hob, y, ballM, surface);
            arrival[i] = HobBlast.arrivalSeconds(r, hob, y);
            positive[i] = c.positive();
            mass[i] = c.mass() * Math.PI * (r1 * r1 - r0 * r0);
            first[i] = c.first();
            stem[i] = c.stem();
            stemFrom[i] = c.stemFrom();
            ball[i] = r * r + hob * hob <= ballM * ballM;
        }
        Rings k = new Rings(mass, arrival, positive, first, stem, stemFrom, ball);
        synchronized (CACHE) {
            CACHE.put(key, k);
        }
        return k;
    }

    /** Время узла сетки s, с. */
    static double time(int s) {
        return T0 * (Math.pow(GROW, s) - 1);
    }

    /** Узел сетки (с дробью) для времени t. */
    private static double node(double t) {
        return Math.min(STEPS - 1.0, Math.log(1 + Math.max(t, 0) / T0) / Math.log(GROW));
    }

    /** Таблица a по узлам сетки в возрасте t — линейно между узлами; узел — один на возраст (логарифм — раз в кадр). */
    private double at(double[] a, double t) {
        if (t != nodeT) {
            nodeT = t;
            nodeAt = node(t);
        }
        double s = nodeAt;
        int i = (int) Math.min(a.length - 2, Math.floor(s));
        return a[i] + (a[i + 1] - a[i]) * (s - i);
    }

    /** Путь ветра у земли за t, м (не меньше, чем при 1 м/с: в штиль воздух тоже перемешивается). */
    double path(double t) {
        return Math.max(1, groundWind) * t;
    }

    /** σ слоя по вертикали в возрасте t: верх Kuhl к концу фазы / 2,5 и турбулентность воздуха (Бриггс), м. */
    private double sigmaV(double top, double t) {
        double s0 = top / 2.5, s = CloudModel.spreadV(air.spread(), path(t));
        return Math.max(0.05, Math.sqrt(s0 * s0 + s * s));
    }

    /** Доля слоя, что ещё над зоной подсоса (ветер уносит его за полрадиуса подсоса — подсос гаснет). */
    private double under(double t) {
        double reach = Math.max(stemReach, 1e-3), d = groundWind * t;
        double u = Math.max(0, Math.min(1, (d - 0.5 * reach) / (0.5 * reach)));
        return 1 - u * u * (3 - 2 * u);
    }

    /** Поднятая доля кольца i сектора k к возрасту t (с взрыва): с прихода фронта — по фазе на месте. */
    private double liftedShare(int k, int i, double t) {
        double age = t - arrival[k][i];
        return age <= 0 ? 0 : DustModel.lifted(age / Math.max(positive[k][i], 1e-6));
    }

    /** Кольцо i сектора k в возрасте t: ослабление в кг смеси — поднятое, осевшее и ушедшее в подсос. */
    private double ringNow(int k, int i, double t) {
        double m = mass[k][i] * liftedShare(k, i, t);
        if (m <= 0) return 0;
        double left = Math.exp(-stem[k][i] * at(stemTime, t) / DustModel.STEM_S);
        return m * DustModel.extinctionAloft(at(settled[annulus[i]], t)) * left;
    }

    /** То же по массе, кг: тяжесть слоя — масса пыли в воздухе (крупная, что осела, слой уже не тянет). */
    private double massNow(int k, int i, double t) {
        double m = mass[k][i] * liftedShare(k, i, t);
        if (m <= 0) return 0;
        double left = Math.exp(-stem[k][i] * at(stemTime, t) / DustModel.STEM_S);
        return m * DustModel.airborne(at(settled[annulus[i]], t)) * left;
    }

    /** Верх слоя кольца i сектора k в возрасте t в фазе волны — Kuhl по пути фронта (∝ возраст^⅚ к {@code first}), м. */
    private double waveTop(int k, int i, double t) {
        double age = t - arrival[k][i];
        if (age <= 0) return 0;
        return first[k][i] * Math.pow(Math.min(1, age / Math.max(positive[k][i], 1e-6)), 5 / 6.0);
    }

    /** Пыль, сорванная под огненным шаром к возрасту t, кг (без оседания: она в облаке). */
    public double underBall(double t) {
        double m = 0;
        for (int k = 0; k < SECTORS; k++) for (int i = 0; i < RINGS; i++) if (ball[k][i]) m += mass[k][i] * liftedShare(k, i, t);
        return m;
    }

    /** Пыль, ушедшая в подсос к возрасту t (ослабление в кг смеси). */
    public double drawn(double t) {
        return at(drawn, t);
    }

    /** Фронт по поверхностям вокруг в возрасте t, м (у сектора, где он дальше всего): дальше него пыли нет. */
    public double front(double t) {
        double f = 0;
        for (int k = 0; k < SECTORS; k++) f = Math.max(f, front(k, t));
        return f;
    }

    /**
     * Фронт по поверхности сектора k в возрасте t, м. Между кольцами — по приходу ({@link HobBlast#arrivalSeconds}), за
     * последним — со скоростью звука.
     */
    double front(int k, double t) {
        // юбка: край идёт от точки удара ровно, R = 2R₀ к SKIRT_S (Ebersole и др. 1980), за ним — с той же скоростью (пласт
        // дальше растягивает сток, {@link #stretch}); кольца с их серединами по площади фронта не дают
        if (skirt) return skirtR * reach[k] * Math.max(0, t) / SKIRT_S;
        if (RINGS == 0 || r[RINGS - 1] <= 0 || t <= arrival[k][0]) return 0;
        for (int i = 1; i < RINGS; i++) {
            if (arrival[k][i] > t) return r[i - 1] + (r[i] - r[i - 1]) * (t - arrival[k][i - 1]) / Math.max(arrival[k][i] - arrival[k][i - 1], 1e-9);
        }
        return r[RINGS - 1] + ua.zentix.incoming.nuclear.model.BlastModel.SOUND_SPEED * (t - arrival[k][RINGS - 1]);
    }

    /** Сток слоя к возрасту t: во сколько раз вырос радиус пласта, R/R₀ (гравитационное течение, {@link #FR}). */
    double stretch(double t) {
        return Math.sqrt(1 + at(slump, t) / slumpFrom);
    }

    /**
     * На сколько опустился за {@code dt} с над перепадом клуб пыли массы {@code dust} кг в объёме гаусса σh, σv: тяжёлый
     * термик MTT (как у тёплого облака, {@link Thermal}, с обратным знаком; Boussinesq — ⚠ у самого места удара пыли в
     * воздухе по массе больше самого воздуха, и он падает быстрее): R = R₀ + αz, d(R³W)/dt = F, F = 3B/(4π), B = g·M/ρ;
     * из покоя (R₀ + αz)⁴ = R₀⁴ + 2αF·t². Воздух нейтральный (⚠ устойчивость за десятки секунд падения на десятки метров
     * почти не тормозит: N·t ~ 0,2). R₀ — шар объёма слоя 2πσh²·1,25σv.
     */
    static double fall(double dust, double sh, double sv, double dt) {
        return dust <= 0 ? 0 : fall(radius(sh, sv), buoyancy(dust), dt);
    }

    /** R₀ тяжёлого термика клуба σh, σv: шар объёма слоя 2πσh²·1,25σv, м. */
    static double radius(double sh, double sv) {
        return Math.cbrt(3 * (2 * Math.PI * sh * sh * 1.25 * sv) / (4 * Math.PI));
    }

    /** F = 3B/(4π), B = g·M/ρ — у {@code dust} кг пыли, м⁴/с². */
    static double buoyancy(double dust) {
        return 3 * CloudModel.G * Math.max(dust, 0) / CloudModel.RHO / (4 * Math.PI);
    }

    /** Опускание за dt термика R₀, F из покоя: ((R₀⁴ + 2αF·t²)^¼ − R₀)/α, м. */
    static double fall(double r0, double f, double dt) {
        if (f <= 0 || dt <= 0) return 0;
        double a = CloudModel.ALPHA, r4 = r0 * r0 * r0 * r0;
        return (Math.sqrt(Math.sqrt(r4 + 2 * a * f * dt * dt)) - r0) / a;
    }

    /** Видимый край клуба — не дальше стольких σ (толща в сотни: край по порогу 0,05 — до ~4σ, {@code SmokePuffs.edge}). */
    static final double EDGE_SIGMAS = 4;

    /** Приёмник клубов слоя (центр от точки взрыва, м). */
    public interface Blobs {
        void blob(double x, double y, double z, double sh, double sv, double dust);
    }

    /**
     * Кольца показа в возрасте t: клубы по секторам (масса — по поверхности сектора), σ в квадратуре с расплыванием
     * Бриггса, видимый край не дальше фронта; пласт стекает гравитационным течением и уходит с ветром на высоте середины
     * слоя, над перепадом опускается к поверхности под собой. {@code fade} — доля, что рисуется.
     */
    public void blobs(double t, double fade, Blobs out) {
        emit(t, fade, true, (x, y, z, sh, sv, dust, mass, source) -> out.blob(x, y, z, sh, sv, dust));
    }

    /** Envelope of the same radial growth, spread and wind law, without visiting the terrain path. */
    public void bounds(double t, double floor, double ceiling, SmokeBounds out) {
        if (total <= 0 || t <= 0) return;
        int s = (int) node(t);
        if (boundWidth[s] < 0) return;
        double v = boundInitialV, lowWind = Double.POSITIVE_INFINITY, highWind = 0;
        for (double[] row : sv) {
            double sigma = at(row, t), drift = air.wind(Math.max(2, 0.8 * sigma)) * t;
            v = Math.max(v, sigma); lowWind = Math.min(lowWind, drift); highWind = Math.max(highWind, drift);
        }
        double radial = boundRadius[s] * stretch(t);
        double spread = CloudModel.spreadH(air.spread(), path(t));
        double width = Math.max(Math.hypot(boundWidth[s] * stretch(t), spread), v);
        // A street chain advances at most CHAIN_STEP per half-metre radius. Held motion can remove either
        // radial or wind increments. Allow both signs, plus the final correction out of a wall.
        double r = (walled ? 2 * CHAIN_STEP / 0.5 : 2) * radial + OUT_MAX + SmokeBounds.PUFF * width;
        double x0, x1, z0, z1;
        if (walled) {
            double wind = 2 * Math.hypot(air.windX(), air.windZ()) * (windForward[s] + windBackward[s]);
            x0 = z0 = -wind; x1 = z1 = wind;
        } else {
            double lo = lowWind - windForward[s], hi = highWind + windBackward[s];
            x0 = Math.min(air.windX() * lo, air.windX() * hi); x1 = Math.max(air.windX() * lo, air.windX() * hi);
            z0 = Math.min(air.windZ() * lo, air.windZ() * hi); z1 = Math.max(air.windZ() * lo, air.windZ() * hi);
        }
        out.box(x0 - r, floor - SmokeBounds.PUFF * width, z0 - r, x1 + r, ceiling + SmokeBounds.PUFF * width + 0.8 * v, z1 + r);
    }

    /** Клуб слоя для проверок — и масса пыли в нём, кг (по ней — тяжесть слоя), и поверхность, с которой её подняло, м. */
    interface Probe {
        void blob(double x, double y, double z, double sh, double sv, double dust, double mass, double source);
    }

    /** Клубы в возрасте t с массой ({@link Probe}). */
    void probe(double t, Probe out) {
        emit(t, 1, true, out);
    }

    /** Клубы в возрасте t; {@code levels} — где они лежат (без него — на поверхности, с которой подняло: толща слоя). */
    private void emit(double t, double fade, boolean levels, Probe out) {
        if (total <= 0 || t <= 0) return;
        // один проход по кольцам: у каждого кольца показа в секторе — масса, её радиус и второй момент, доля ещё в фазе волны,
        // масса пыли в воздухе (тяжесть слоя, {@link #airborne}: тем же счётом — по кольцу показа она бралась бы заново у
        // каждого клуба, а это всё кольца на каждое кольцо показа — у налёта из 100 ударов пятая часть процессора дыма)
        double[][] m = new double[ANNULI][SECTORS], mr = new double[ANNULI][SECTORS], mr2 = new double[ANNULI][SECTORS],
                wt = new double[ANNULI][SECTORS], wtop = new double[ANNULI][SECTORS], aloft = new double[ANNULI][SECTORS];
        for (int k = 0; k < SECTORS; k++) {
            for (int i = 0; i < RINGS; i++) {
                if (ball[k][i]) continue;
                int a = annulus[i];
                if (levels) aloft[a][k] += massNow(k, i, t);
                double e = ringNow(k, i, t);
                if (e <= 0) continue;
                m[a][k] += e;
                mr[a][k] += e * r[i];
                mr2[a][k] += e * r[i] * r[i];
                if (t - arrival[k][i] < positive[k][i]) {
                    wt[a][k] += e;
                    wtop[a][k] += e * waveTop(k, i, t);
                }
            }
        }
        double spread = CloudModel.spreadH(air.spread(), path(t)), stretch = stretch(t);
        for (int a = 0; a < ANNULI; a++) {
            double table = at(sv[a], t);
            for (int k = 0; k < SECTORS; k++) {
                double mass = m[a][k];
                if (mass <= 1e-9) continue;
                // σ по вертикали: в фазе волны — верх Kuhl той части, что ещё в фазе; потом — таблица
                double v = table;
                if (wt[a][k] > 0) v = wt[a][k] / mass * Math.max(0.05, wtop[a][k] / wt[a][k] / 2.5) + (1 - wt[a][k] / mass) * v;
                double zc = 0.8 * v;
                // кольцо показа в секторе — клуб на среднем по массе радиусе; σ — разброс колец по радиусу, не уже дуги сектора;
                // пласт стекает (радиус и ширина × R/R₀); видимый край (4σ) — не дальше фронта
                double source = mr[a][k] / mass, var = Math.max(0, mr2[a][k] / mass - source * source);
                // юбка по улице — дальше: место и разброс колец по радиусу растянуты на дальность сектора, поперёк — не шире
                // открытой дуги
                double own = Math.max(Math.sqrt(var) * reach[k], Math.PI * source / SECTORS / 1.25 * openArc[k][a]);
                source *= reach[k];
                double mean = source * stretch;
                double[] e = edge(mean, Math.max(Math.hypot(own * stretch, spread), v), own, v, front(k, t));
                mean = e[0];
                double sh = e[1];
                double ang = heading[k][a], path = source;
                if (walled && mean > 0) {
                    double[] o = open(k, a, mean);
                    ang = o[0];
                    source *= o[1] / mean;
                    mean = o[1];
                }
                double ux = Math.cos(ang), uz = Math.sin(ang);
                double[] d = drift(t, zc);
                // путь клуба в улице идёт от того же места по открытому ({@link #place}), что и он сам
                double[] at = levels ? level(k, a, t, walled ? path : source, ux, uz, own) : new double[]{surface[k][a], 0, 0};
                double x = d[0] + mean * ux - at[1], z = d[1] + mean * uz - at[2];
                if (walled) {
                    double[] p = out(x, z, at[0] + zc);
                    x = p[0];
                    z = p[1];
                }
                out.blob(x, at[0] + zc, z, sh, v, mass * fade, aloft[a][k], surface[k][a]);
            }
        }
    }

    /**
     * Середина клуба по радиусу и его σh, когда видимый край (4σ) дошёл бы дальше фронта: не дальше фронта ({@link #emit};
     * путь клуба в улице — {@link #place} — от того же места).
     */
    private static double[] edge(double mean, double sh, double own, double v, double front) {
        if (mean + EDGE_SIGMAS * sh > front) {
            sh = Math.max((front - mean) / EDGE_SIGMAS, Math.min(sh, Math.max(own, v)));
            mean = Math.max(0, Math.min(mean, front - EDGE_SIGMAS * sh));
            // фронт ещё у самой воронки: клубы — у середины, не шире него
            if (mean + EDGE_SIGMAS * sh > front) sh = Math.max(front / EDGE_SIGMAS, v);
        }
        return new double[]{mean, sh};
    }

    /** Масса пыли в воздухе у клуба кольца показа {@code show} сектора k в возрасте t, кг. */
    private double airborne(int k, int show, double t) {
        double m = 0;
        for (int i = 0; i < RINGS; i++) if (annulus[i] == show && !ball[k][i]) m += massNow(k, i, t);
        return m;
    }

    /**
     * Сетка пути клубов, с: шаг 0,25 с вначале (край крыши, приземление), потом 2 % возраста, не больше 2 с — путь
     * клуба вдали от удара, когда он уже шире десятков метров.
     */
    static final double[] PATH;

    static {
        java.util.List<Double> g = new java.util.ArrayList<>();
        for (double t = 0; t <= CloudModel.LIFE_MAX; t += Math.min(2, Math.max(0.25, 0.02 * t))) g.add(t);
        PATH = g.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * Состояние пути клуба в узле сетки {@link #PATH}: уровень поверхности, на которой он лежит (м от точки взрыва), и
     * падение — с какого уровня и с какого мига, R₀ и F тяжёлого термика ({@link #fall}).
     */
    private static final class Track {
        double level, from, since, r0, f;
        /** Последняя известная поверхность под клубом, м ({@link Double#NaN} — ещё не было). */
        double seen = Double.NaN;
        /** Сколько сноса и стока задержала запруда (препятствие шире следа и выше слоя), м по x и z. */
        double heldX, heldZ;
        boolean falling;

        Track copy() {
            Track c = new Track();
            c.heldX = heldX;
            c.heldZ = heldZ;
            c.seen = seen;
            c.level = level;
            c.from = from;
            c.since = since;
            c.r0 = r0;
            c.f = f;
            c.falling = falling;
            return c;
        }
    }

    /**
     * Путь клуба: узел сетки, состояние в нём и в следующем — между узлами уровень идёт по прямой, так что каждый узел
     * считается один раз (выборки рельефа — только при переходе узла).
     */
    private static final class Path {
        int node;
        Track now, next;
    }

    /** Пути клубов [кольцо показа][сектор]: ведутся вперёд с кадрами, назад (перемотка) — заново. */
    private final Path[][] paths = new Path[ANNULI][SECTORS];

    /** Выборок пути на шаг {@link #PATH} не больше стольких (шаг выборки — полширины клуба, не меньше метра). */
    static final int PROBES = 16;
    /** Узлов пути одного клуба за вызов не больше стольких: догонка в большом возрасте идёт кадрами, а не одним. */
    static final int CATCH_UP = 64;
    /** Узел {@link #PATH}, с которого строится путь (0,5 с): к нему фронт прошёл кольца показа, их радиус установился. */
    static final int PATH_FROM = 2;

    /**
     * Уровень поверхности, на которой лежит клуб кольца показа {@code show} сектора {@code sector} в возрасте t, м от
     * точки взрыва. Путь клуба (сток пласта вдоль {@code ux, uz} от радиуса {@code source} и снос ветром на высоте середины
     * слоя в тот миг) идёт по сетке {@link #PATH} от поверхности, с которой пыль подняло. Тяжёлый воздух уходит в самое
     * низкое место, до которого достаёт: поверхность под клубом — нижняя под его следом ({@link #under}: улица между
     * домами, низ склона, улица за краем крыши), выборка — через полширины клуба (не реже метра). Вниз слой идёт по ней,
     * пока она уходит не круче {@link #DROP} на метр пути (склон до 45°: рельеф Minecraft — ступени в блок); за краем
     * круче — опускается быстрее из двух: тяжёлым термиком с мига, когда вышел за край (масса пыли в воздухе и размер
     * клуба в тот миг; дальше плавучесть сохраняется — ⚠ крупная пыль, что оседает из него, её уносит), или ветром за
     * кромкой ({@link #LEE}), — пока не ляжет на неё. Вверх — не круче 45° пройденного пути и только на подъём не выше
     * толщи слоя (2σv: тяжёлое течение глубже препятствия переливается через него, Rottman и Simpson 1983); перед
     * подъёмом выше (препятствие шире следа: терраса, склон, большой дом) — запруда: стоит, пока слой не станет толще (⚠
     * обтекания нет). Где рельеф неизвестен, лежащий клуб держит уровень, падающий падает до самой
     * низкой поверхности, известной под ним (последней, иначе — своего сектора, которую видел взрыв).
     */
    private double[] level(int sector, int show, double t, double source, double ux, double uz, double own) {
        // до PATH_FROM фронт ещё поднимает кольцо, и средний радиус клуба бежит наружу: путь строится потом, а пока клуб
        // лежит, где его подняло
        if (t < PATH[PATH_FROM]) {
            paths[show][sector] = null;
            double[] at = place(sector, show, t, source, ux, uz, own);
            double start = under(at[0], at[1], 0.5 * width(show, t, own)), level = begin(sector, show, start, ux, uz, at);
            double[] held = young(show, t, at, level);
            return new double[]{level, held[0], held[1]};
        }
        Path p = paths[show][sector];
        if (p == null || PATH[p.node] > t) {
            // начало — {@link #begin}
            p = new Path();
            p.node = PATH_FROM;
            p.now = new Track();
            double[] at = place(sector, show, PATH[PATH_FROM], source, ux, uz, own);
            double start = under(at[0], at[1], 0.5 * width(show, PATH[PATH_FROM], own));
            p.now.level = begin(sector, show, start, ux, uz, at);
            p.now.seen = start;
            double[] held = young(show, PATH[PATH_FROM], at, p.now.level);
            p.now.heldX = held[0];
            p.now.heldZ = held[1];
            p.next = p.now.copy();
            step(p.next, sector, show, PATH[PATH_FROM], PATH[PATH_FROM + 1], source, ux, uz, own);
            paths[show][sector] = p;
        }
        // догонка за один вызов — не больше CATCH_UP узлов (первый вызов в большом возрасте): дальше — в следующих кадрах
        for (int k = 0; k < CATCH_UP && p.node + 2 < PATH.length && PATH[p.node + 1] <= t; k++) {
            SmokePreparation.checkpoint();
            Track next = p.next.copy();
            step(next, sector, show, PATH[p.node + 1], PATH[p.node + 2], source, ux, uz, own);
            p.node++;
            p.now = p.next;
            p.next = next;
        }
        double u = Math.max(0, Math.min(1, (t - PATH[p.node]) / (PATH[p.node + 1] - PATH[p.node])));
        return new double[]{p.now.level + u * (p.next.level - p.now.level), p.now.heldX + u * (p.next.heldX - p.now.heldX),
                p.now.heldZ + u * (p.next.heldZ - p.now.heldZ)};
    }

    /**
     * С какой поверхности начинается путь клуба: под его следом в его месте ({@code under}, место {@code at} от точки
     * взрыва), если от точки полосы, где известна поверхность, с которой пыль подняло, до него по радиусу —
     * одна поверхность (нигде не круче 45° на метр: склон, двор); если между ними стена (дом), — не выше поверхности, с
     * которой подняло: тяжёлая пыль сама на крышу не попадает (в первые доли секунды след меньше дома, и середина кольца у
     * улицы бывает на нём). Неизвестно — точка полосы.
     */
    private double begin(int sector, int show, double under, double ux, double uz, double[] at) {
        double source = surface[sector][show];
        if (Double.isNaN(under)) return source;
        if (under <= source) return skirt && under < source - DROP ? source : under;
        // юбка идёт от самой точки удара на её высоте: стена между ней и клубом (дом у улицы) держит её внизу, даже если
        // точка полосы уже на крыше
        double x0 = skirt ? 0 : BANDS[show] * ux, z0 = skirt ? 0 : BANDS[show] * uz, len = Math.hypot(at[0] - x0, at[1] - z0);
        int n = (int) Math.max(1, Math.min(32, Math.ceil(len)));
        double step = Math.max(1, len / n), last = skirt ? source : relief.at(x0, z0);
        for (int i = 1; i <= n; i++) {
            double h = relief.at(x0 + (at[0] - x0) * i / n, z0 + (at[1] - z0) * i / n);
            if (Double.isNaN(h)) continue;
            if (!Double.isNaN(last) && Math.abs(h - last) > DROP * step + DROP) return source;
            last = h;
        }
        return under;
    }

    /** Ширина клуба кольца показа {@code show} шириной {@code own} в возрасте t (σh), м. */
    private double width(int show, double t, double own) {
        return Math.max(Math.hypot(own * stretch(t), CloudModel.spreadH(air.spread(), path(t))), at(sv[show], t));
    }

    /** Место пути клуба в возрасте t (x, z от точки взрыва). */
    private double[] place(int sector, int show, double t, double source, double ux, double uz, double own) {
        double v = at(sv[show], t);
        double[] d = drift(t, 0.8 * v);
        double mean = source * stretch(t);
        // в улице — с того же места, что клуб: не дальше фронта ({@link #edge}; σ — без доли фазы волны, она — доли
        // секунды у самого удара) и по открытому ({@link #open})
        if (walled) mean = edge(mean, width(show, t, own), own, v, front(sector, t))[0];
        if (walled && mean > 0) {
            double[] o = open(sector, show, mean);
            return new double[]{d[0] + o[1] * Math.cos(o[0]), d[1] + o[1] * Math.sin(o[0])};
        }
        return new double[]{d[0] + mean * ux, d[1] + mean * uz};
    }

    /**
     * Поверхность под клубом с серединой в (x, z) и полушириной w, м от точки взрыва: третья снизу из 13 выборок рельефа
     * (нижние ~20 % следа) в середине и
     * на ±w и ±0,62w по осям и на диагоналях ({@link #FOOTPRINT}: несоизмеримые шаги — след, кратный шагу кварталов, не
     * попадает только в крыши) — тяжёлый воздух уходит в низ, до которого достаёт (столб, ствол, крыши по бокам улицы его не
     * поднимают, дом шире клуба держит), но щель, куда попадают одна-две выборки (овраг, канава), весь клуб в себя не забирает;
     * неизвестно всё — {@link Double#NaN}.
     */
    /** Выборки следа клуба в долях полуширины. */
    static final double[][] FOOTPRINT = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {0.62, 0}, {-0.62, 0}, {0, 0.62}, {0, -0.62},
            {0.71, 0.71}, {-0.71, 0.71}, {0.71, -0.71}, {-0.71, -0.71}};

    double under(double x, double z, double w) {
        double[] h = new double[FOOTPRINT.length];
        int n = 0;
        for (double[] d : FOOTPRINT) {
            double v = relief.at(x + w * d[0], z + w * d[1]);
            if (!Double.isNaN(v)) h[n++] = v;
        }
        if (n == 0) return Double.NaN;
        java.util.Arrays.sort(h, 0, n);
        return h[Math.min(n - 1, n / 6)];
    }

    /**
     * За краем крыши или обрыва ветер опускает слой не круче стольких метров на метр сноса: поток за острой кромкой
     * отрывается и прилипает к земле через ~2 высоты препятствия (куб в пограничном слое — 1,5–2,5 H, Castro &amp; Robins
     * 1977, JFM 79; ⚠ у длинного дома и гребня — дальше).
     */
    static final double LEE = 0.5;

    /** Шаг пути клуба от t0 до t1 ({@link #level}). */
    private void step(Track tr, int sector, int show, double t0, double t1, double source, double ux, double uz, double own) {
        double[] p0 = place(sector, show, t0, source, ux, uz, own), p1 = place(sector, show, t1, source, ux, uz, own);
        double dx = p1[0] - p0[0], dz = p1[1] - p0[1], len = Math.hypot(dx, dz), w = 0.5 * width(show, t1, own);
        int n = (int) Math.max(1, Math.min(PROBES, Math.ceil(len / Math.max(1, w))));
        // в улице — не реже метра пути: поперечный снос и стену держит каждая выборка ({@link #canyon})
        if (walled) n = (int) Math.max(n, Math.min(PROBES, Math.ceil(len)));
        double[] w0 = drift(t0, 0.8 * at(sv[show], t0)), w1 = drift(t1, 0.8 * at(sv[show], t1));
        // склон — не круче блока на метр пути и роста полуширины следа (растущий след сам достаёт ниже по склону — это не
        // край); за краем ветер опускает слой на LEE метра на метр сноса
        double grow = Math.max(0, w - 0.5 * width(show, t0, own)) / n;
        double tol = DROP * Math.max(1, len / n + grow), lee = LEE * Math.hypot(w1[0] - w0[0], w1[1] - w0[1]) / n;
        for (int i = 1; i <= n; i++) {
            double u = (double) i / n, tau = t0 + u * (t1 - t0);
            if (walled) {
                double[] h = canyon(show, tau, p0[0] + (u - 1.0 / n) * dx - tr.heldX, p0[1] + (u - 1.0 / n) * dz - tr.heldZ,
                        (w1[0] - w0[0]) / n, (w1[1] - w0[1]) / n, tr.level);
                tr.heldX += h[0];
                tr.heldZ += h[1];
                // рост клуба по открытому ({@link #open}) уносит в стену только снесённого ветром в другую улицу: держится
                double top = Math.max(wallFrom, tr.level + 0.8 * at(sv[show], tau));
                if (relief.at(p0[0] + u * dx - tr.heldX, p0[1] + u * dz - tr.heldZ) > top) {
                    tr.heldX += (dx - (w1[0] - w0[0])) / n;
                    tr.heldZ += (dz - (w1[1] - w0[1])) / n;
                }
            }
            double below = under(p0[0] + u * dx - tr.heldX, p0[1] + u * dz - tr.heldZ, w), before = tr.seen;
            if (Double.isNaN(below)) {
                // рельеф неизвестен: лежащий держит уровень, падающий падает — до самой низкой поверхности, что видел взрыв
                if (tr.falling) fallTo(tr, Math.min(Double.isNaN(tr.seen) ? lowest(sector) : tr.seen, tr.level), tau, lee);
                continue;
            }
            if (below > tr.level) {
                // вверх: подъём выше толщи слоя и круче склона — стоит на своём уровне; ниже — не круче 45° пути;
                // падающий, долетев, лёг
                if (below - tr.level > Math.max(tol, 2 * at(sv[show], tau))) {
                    // запруда: перед препятствием шире следа и выше слоя тяжёлый воздух стоит — остаток сноса и стока шага
                    // не идёт, пока слой не станет толще
                    tr.heldX += (1 - (i - 1.0) / n) * dx;
                    tr.heldZ += (1 - (i - 1.0) / n) * dz;
                    // падающий падает дальше — до поверхности там, где стоит (последней известной под ним)
                    if (tr.falling) fallTo(tr, Math.min(Double.isNaN(tr.seen) ? lowest(sector) : tr.seen, tr.level), t1, lee);
                    return;
                }
                tr.seen = below;
                tr.level = Math.min(below, tr.level + DROP * len / n);
                tr.falling = false;
                continue;
            }
            tr.seen = below;
            if (below >= tr.level - tol - DROP) {
                // склон вниз (с блоком на ступенчатость рельефа), или падающий долетел до поверхности — лежит на ней
                tr.level = below;
                tr.falling = false;
                continue;
            }
            if (tr.falling && !Double.isNaN(before) && before - below <= tol + DROP) {
                // падает над склоном, по которому слой мог бы идти: опускается и вместе с ним (сошёл на склон)
                tr.level -= Math.max(0, before - below);
            }
            if (!tr.falling) {
                // вышел за край: падает с этого мига — масса и размер клуба на краю
                tr.falling = true;
                tr.from = tr.level;
                tr.since = t0 + (u - 0.5 / n) * (t1 - t0);
                tr.r0 = radius(width(show, tr.since, own), at(sv[show], tr.since));
                tr.f = buoyancy(airborne(sector, show, tr.since));
            }
            fallTo(tr, below, tau, lee);
        }
    }

    /**
     * Падающий клуб в миг tau над поверхностью {@code floor}: опустился — быстрее из двух: тяжёлым термиком или ветром
     * за кромкой ({@code lee} — на столько за эту выборку, {@link #LEE}); дошёл до неё — лёг.
     */
    private static void fallTo(Track tr, double floor, double tau, double lee) {
        double level = Math.min(tr.from - fall(tr.r0, tr.f, tau - tr.since), tr.level - lee);
        if (level <= floor) {
            tr.level = floor;
            tr.falling = false;
        } else {
            tr.level = level;
        }
    }

    /** Самая низкая известная поверхность сектора, м от точки взрыва (где рельефа под падающим клубом не знаем). */
    private double lowest(int sector) {
        double m = Double.POSITIVE_INFINITY;
        for (double h : surface[sector]) m = Math.min(m, h);
        return m;
    }

    /**
     * Снос слоя ветром за t, м (x и z): ветер на высоте середины слоя {@code zc} над его поверхностью, не ниже 2 м (⚠
     * высота середины — нынешняя на весь путь: слой растёт, и вначале он шёл чуть медленнее).
     */
    public double[] drift(double t, double zc) {
        double lean = air.wind(Math.max(2, zc)) * Math.max(t, 0);
        return new double[]{air.windX() * lean, air.windZ() * lean};
    }

    /** Толща самого густого клуба слоя в возрасте t (через середину по горизонтали). */
    double tau(double t) {
        double[] max = {0};
        emit(t, 1, false, (x, y, z, sh, sv, dust, mass, source) -> max[0] = Math.max(max[0], DustModel.K_MIX * dust / (2 * Math.PI * sh * sv)));
        return max[0];
    }

    /** Сколько слой виден, с: пока толща самого густого клуба больше {@link CloudModel#SEEN} (по узлам сетки). */
    private double lifeSeconds() {
        if (total <= 0) return 0;
        double last = 0;
        for (int s = 1; s < STEPS && time(s) <= CloudModel.LIFE_MAX; s++) if (tau(time(s)) >= CloudModel.SEEN) last = time(s);
        return last <= 0 ? 0 : Math.min(CloudModel.LIFE_MAX, last * GROW);
    }
}
