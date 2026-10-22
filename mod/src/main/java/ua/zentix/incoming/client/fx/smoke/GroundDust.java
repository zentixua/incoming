package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.nuclear.model.MushroomModel;

/**
 * Пыль, которую поднимает ветер за фронтом волны обычного взрыва, — тот же закон, что у ядерки ({@link DustModel}), без
 * Minecraft. Метры, секунды, килограммы.
 * <ul>
 * <li><b>Вынос и время.</b> Кольца по земле от точки под взрывом: в каждом — слой {@link DustModel#chemical} (давление,
 * фаза и доля напора вдоль земли — {@link HobBlast} по высоте подрыва, мощность — ядерная того же давления,
 * {@link CloudModel#equivalentKt}): грунт уходит в воздух с прихода фронта ({@link HobBlast#arrivalSeconds}) долей
 * {@link DustModel#lifted} за положительную фазу на месте. До фронта пыли нет, у разрыва высоко над землёй (давление у
 * земли ниже порога срыва) — нет совсем.</li>
 * <li><b>Под шаром.</b> Пыль, сорванная под огненным шаром продуктов (он касается земли), — в его горячем газе и
 * поднимается с облаком, а не стоит у земли (как у ядерки: в шаре на земле пыли волны нет, {@code BlastWall.underBall}):
 * её отдаёт {@link #underBall}.</li>
 * <li><b>Высота.</b> В фазе волны — пыльный слой Kuhl (у фронта ноль, к концу фазы — {@link DustModel.Column#first},
 * десятки сантиметров у зарядов в десятки кг); дальше вспышки, что греет землю, у ВВ нет, и слой растит турбулентность
 * воздуха: σ по вертикали — Бриггс по классу Паскуилла на пути ветра, в квадратуре с верхом Kuhl (σ = верх / 2,5 —
 * ⚠ форма слоя). Слой у земли — гаусс с отражением от неё: середина массы — на 0,8σ.</li>
 * <li><b>Оседание.</b> Корзины {@link DustModel} выпадают из слоя: у гаусса с отражением концентрация у земли — масса /
 * (1,25σ), поток вниз — v·масса / (1,25σ); по ∫dt/(1,25σ) — доля ослабления, что ещё в воздухе
 * ({@link DustModel#extinctionAloft}).</li>
 * <li><b>Ножка.</b> Подсос у оси — тот же член, что у ядерки ({@link DustModel.Column#stem}, {@link DustModel#STEM_S}):
 * из колец ближе {@code STEM_M}·∛(Y/15) он забирает stem/STEM_S их пыли в секунду, пока слой над ними — ветер уносит
 * слой из-под облака за полрадиуса подсоса. Шар обычного взрыва касается земли, поэтому пыль идёт по всей ножке до низа
 * облака сразу ({@code StemDust}, случай следа шара, ⚠ оценка), с воздухом ножки — долей
 * {@link MushroomModel#STEM_INFLOW} подъёма облака; дошедшая до низа облака — уже в облаке.</li>
 * <li><b>Снос.</b> Слой уходит с ветром на высоте своей середины (не ниже 2 м) и расплывается по Бриггсу; поток волны
 * наружу пыль не сносит — она стоит там, где её сорвало.</li>
 * </ul>
 * Картинке слой отдаёт кольца показа ({@link #ANNULI}): у каждого — своя доля массы, диск того же второго момента по
 * радиусу (сплошной ковёр пыли; кучки клубов по кругу читались отдельными тёмными комками), видимый край — не дальше
 * фронта в этот миг.
 */
public final class GroundDust {
    /** Колец счёта (по логарифму радиуса), колец показа и их края — доли всей поднятой массы. */
    static final int RINGS = 24, ANNULI = 4;
    static final double[] QUANTILE = {0.35, 0.65, 0.88};
    /** Сетка времени таблиц: t_k = T0·(GROW^k − 1), с. */
    static final int STEPS = 112;
    static final double T0 = 0.01, GROW = 1.12;
    /** Внутренний край счёта и дальний — давление у земли, ниже которого грунт уже не срывает (psi; вынос ~0). */
    static final double INNER = 0.1, OUTER_PSI = 0.3;

    final CloudModel.Air air;
    final double yieldKt, hob;
    /**
     * Кольца счёта: середина, площадь, приход фронта, фаза, поднятое (кг), верх к концу фазы, подсос, его начало; под
     * шаром ли кольцо (его пыль — в облаке).
     */
    private final double[] r, area, arrival, positive, mass, first, stem, stemFrom;
    private final boolean[] ball;
    private final int[] annulus;
    /** Кольца показа: края по радиусу. */
    private final double[] edge = new double[ANNULI + 1];
    /** Таблицы по сетке времени: σ по вертикали и ∫dt/(1,25σ) у каждого кольца показа; подсос — доля его времени. */
    private final double[][] sv = new double[ANNULI][STEPS], settled = new double[ANNULI][STEPS];
    private final double[] stemTime = new double[STEPS];
    /** Пыль, ушедшая в ножку (ослабление в кг смеси {@link DustModel#K_MIX}), нарастающим итогом по сетке. */
    private final double[] drawn = new double[STEPS];
    /** Поднятая масса у земли (без той, что под шаром), кг; радиус подсоса, м; сколько слой виден, с. */
    public final double total, stemReach;
    public final double life;

    /**
     * Слой пыли заряда {@code tntKg} кг ТНТ на высоте {@code hobM} над землёй в воздухе {@code air}; {@code ballM} —
     * радиус огненного шара (касается ли он земли).
     */
    GroundDust(double tntKg, double hobM, double ballM, CloudModel.Air air) {
        this.air = air;
        this.yieldKt = CloudModel.equivalentKt(tntKg);
        this.hob = Math.max(0, hobM);
        Rings k0 = rings(tntKg, hob, ballM);
        r = k0.r;
        area = k0.area;
        arrival = k0.arrival;
        positive = k0.positive;
        mass = k0.mass;
        first = k0.first;
        stem = k0.stem;
        stemFrom = k0.stemFrom;
        ball = k0.ball;
        annulus = new int[RINGS];
        double outer = k0.outer, sum = 0;
        for (int i = 0; i < RINGS; i++) if (!ball[i]) sum += mass[i];
        total = sum;
        stemReach = DustModel.STEM_M * Math.cbrt(yieldKt / 15);
        // кольца показа — по долям массы
        double acc = 0;
        int a = 0;
        for (int i = 0; i < RINGS; i++) {
            if (ball[i]) continue;
            acc += mass[i];
            annulus[i] = a;
            while (a < ANNULI - 1 && sum > 0 && acc >= QUANTILE[a] * sum) edge[++a] = r[i];
        }
        for (int k = a + 1; k <= ANNULI; k++) edge[k] = Math.max(edge[k - 1], outer);
        edge[ANNULI] = outer;
        // таблицы: σ по вертикали (верх Kuhl к концу фазы и турбулентность воздуха), оседание, подсос
        double[] top = new double[ANNULI], weight = new double[ANNULI];
        for (int i = 0; i < RINGS; i++) {
            if (ball[i]) continue;
            top[annulus[i]] += mass[i] * first[i];
            weight[annulus[i]] += mass[i];
        }
        for (int k = 0; k < ANNULI; k++) top[k] = weight[k] > 0 ? top[k] / weight[k] : 0.1;
        double from = RINGS > 0 ? stemFrom[0] : 0;
        for (int s = 1; s < STEPS; s++) {
            double t0 = time(s - 1), t1 = time(s), dt = t1 - t0, mid = 0.5 * (t0 + t1);
            for (int k = 0; k < ANNULI; k++) {
                sv[k][s] = sigmaV(top[k], t1);
                settled[k][s] = settled[k][s - 1] + dt / (1.25 * sigmaV(top[k], mid));
            }
            stemTime[s] = stemTime[s - 1] + (mid > from ? dt * under(mid) : 0);
        }
        for (int k = 0; k < ANNULI; k++) sv[k][0] = sigmaV(top[k], 0);
        // ножка: ушедшее в подсос — нарастающим итогом (ослабление в кг смеси)
        for (int s = 1; s < STEPS; s++) {
            double t = 0.5 * (time(s - 1) + time(s)), flux = 0;
            for (int i = 0; i < RINGS; i++) if (!ball[i] && t > stemFrom[i]) flux += stem[i] / DustModel.STEM_S * under(t) * ringNow(i, t);
            drawn[s] = drawn[s - 1] + flux * (time(s) - time(s - 1));
        }
        life = lifeSeconds();
    }

    /** Кольца счёта одного заряда и высоты: от воздуха не зависят (залп «Града» считает их один раз). */
    private record Rings(double outer, double[] r, double[] area, double[] arrival, double[] positive, double[] mass, double[] first,
                         double[] stem, double[] stemFrom, boolean[] ball) {
    }

    private static final java.util.Map<String, Rings> CACHE = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Rings> e) {
            return size() > 16;
        }
    };

    /** Кольца счёта заряда {@code tntKg} на высоте {@code hob} с шаром {@code ballM} (из кэша; нет — считаются сейчас). */
    private static Rings rings(double tntKg, double hob, double ballM) {
        String key = tntKg + "/" + hob + "/" + ballM;
        synchronized (CACHE) {
            Rings k = CACHE.get(key);
            if (k != null) return k;
        }
        double y = CloudModel.equivalentKt(tntKg);
        double[] r = new double[RINGS], area = new double[RINGS], arrival = new double[RINGS], positive = new double[RINGS],
                mass = new double[RINGS], first = new double[RINGS], stem = new double[RINGS], stemFrom = new double[RINGS];
        boolean[] ball = new boolean[RINGS];
        double outer = tntKg <= 0 ? 0 : HobBlast.rangeFor(OUTER_PSI, hob, y);
        if (outer > INNER) {
            for (int i = 0; i < RINGS; i++) {
                double r0 = i == 0 ? 0 : INNER * Math.pow(outer / INNER, (double) i / RINGS), r1 = INNER * Math.pow(outer / INNER, (i + 1.0) / RINGS);
                r[i] = i == 0 ? 0.5 * r1 : Math.sqrt(r0 * r1);
                area[i] = Math.PI * (r1 * r1 - r0 * r0);
                DustModel.Column c = DustModel.chemical(r[i], hob, y, ballM);
                arrival[i] = HobBlast.arrivalSeconds(r[i], hob, y);
                positive[i] = c.positive();
                mass[i] = c.mass() * area[i];
                first[i] = c.first();
                stem[i] = c.stem();
                stemFrom[i] = c.stemFrom();
                ball[i] = r[i] * r[i] + hob * hob <= ballM * ballM;
            }
        }
        Rings k = new Rings(outer, r, area, arrival, positive, mass, first, stem, stemFrom, ball);
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

    private static double at(double[] a, double t) {
        double s = node(t);
        int i = (int) Math.min(a.length - 2, Math.floor(s));
        return a[i] + (a[i + 1] - a[i]) * (s - i);
    }

    /** Путь ветра у земли за t, м (не меньше, чем при 1 м/с: в штиль воздух тоже перемешивается). */
    double path(double t) {
        return Math.max(1, Math.hypot(air.windX(), air.windZ()) * air.wind(2)) * t;
    }

    /** σ слоя по вертикали в возрасте t: верх Kuhl к концу фазы / 2,5 и турбулентность воздуха (Бриггс), м. */
    private double sigmaV(double top, double t) {
        double s0 = top / 2.5, s = CloudModel.spreadV(air.spread(), path(t));
        return Math.max(0.05, Math.sqrt(s0 * s0 + s * s));
    }

    /** Доля слоя, что ещё над зоной подсоса (ветер уносит его за полрадиуса подсоса — подсос гаснет). */
    private double under(double t) {
        double reach = Math.max(stemReach, 1e-3), d = Math.hypot(air.windX(), air.windZ()) * air.wind(2) * t;
        double u = Math.max(0, Math.min(1, (d - 0.5 * reach) / (0.5 * reach)));
        return 1 - u * u * (3 - 2 * u);
    }

    /** Поднятая доля кольца i к возрасту t (с взрыва): с прихода фронта — по фазе на месте. */
    private double liftedShare(int i, double t) {
        double age = t - arrival[i];
        return age <= 0 ? 0 : DustModel.lifted(age / Math.max(positive[i], 1e-6));
    }

    /** Кольцо i в возрасте t: ослабление в кг смеси — поднятое, осевшее и ушедшее в подсос. */
    private double ringNow(int i, double t) {
        double m = mass[i] * liftedShare(i, t);
        if (m <= 0) return 0;
        double left = Math.exp(-stem[i] * at(stemTime, t) / DustModel.STEM_S);
        return m * DustModel.extinctionAloft(at(settled[annulus[i]], t)) * left;
    }

    /** Верх слоя кольца i в возрасте t в фазе волны — Kuhl по пути фронта (∝ возраст^⅚ к {@code first}), м. */
    private double waveTop(int i, double t) {
        double age = t - arrival[i];
        if (age <= 0) return 0;
        return first[i] * Math.pow(Math.min(1, age / Math.max(positive[i], 1e-6)), 5 / 6.0);
    }

    /** Пыль, сорванная под огненным шаром к возрасту t, кг (без оседания: она в облаке). */
    public double underBall(double t) {
        double m = 0;
        for (int i = 0; i < RINGS; i++) if (ball[i]) m += mass[i] * liftedShare(i, t);
        return m;
    }

    /** Пыль, ушедшая в подсос к возрасту t (ослабление в кг смеси). */
    public double drawn(double t) {
        return at(drawn, t);
    }

    /**
     * Фронт по земле в возрасте t, м: дальше него пыли нет. Между кольцами — по приходу ({@link HobBlast#arrivalSeconds}),
     * за последним — со скоростью звука.
     */
    public double front(double t) {
        if (total <= 0 && underBall(1e9) <= 0 || t <= arrival[0]) return 0;
        for (int i = 1; i < RINGS; i++) {
            if (arrival[i] > t) return r[i - 1] + (r[i] - r[i - 1]) * (t - arrival[i - 1]) / Math.max(arrival[i] - arrival[i - 1], 1e-9);
        }
        return r[RINGS - 1] + ua.zentix.incoming.nuclear.model.BlastModel.SOUND_SPEED * (t - arrival[RINGS - 1]);
    }

    /** Видимый край клуба — не дальше стольких σ (толща в сотни: край по порогу 0,05 — до ~4σ, {@code SmokePuffs.edge}). */
    static final double EDGE_SIGMAS = 4;

    /** Приёмник клубов слоя (центр от точки под взрывом на земле, м). */
    public interface Blobs {
        void blob(double x, double y, double z, double sh, double sv, double dust);
    }

    /**
     * Кольца показа в возрасте t: диск σ² = ⟨r²⟩/2 (в квадратуре с расплыванием Бриггса), не шире фронта; снос — ветер на
     * высоте середины слоя. {@code fade} — доля, что рисуется.
     */
    public void blobs(double t, double fade, Blobs out) {
        if (total <= 0 || t <= 0) return;
        // один проход по кольцам: у каждого кольца показа — масса, её радиус и второй момент, доля ещё в фазе волны
        double[] m = new double[ANNULI], mr2 = new double[ANNULI], wt = new double[ANNULI], wtop = new double[ANNULI];
        for (int i = 0; i < RINGS; i++) {
            if (ball[i]) continue;
            double e = ringNow(i, t);
            if (e <= 0) continue;
            int a = annulus[i];
            m[a] += e;
            mr2[a] += e * r[i] * r[i];
            if (t - arrival[i] < positive[i]) {
                wt[a] += e;
                wtop[a] += e * waveTop(i, t);
            }
        }
        double spread = CloudModel.spreadH(air.spread(), path(t)), front = front(t);
        for (int a = 0; a < ANNULI; a++) {
            if (m[a] <= 1e-9) continue;
            // σ по вертикали: в фазе волны — верх Kuhl той части, что ещё в фазе; потом — таблица
            double v = at(sv[a], t);
            if (wt[a] > 0) v = wt[a] / m[a] * Math.max(0.05, wtop[a] / wt[a] / 2.5) + (1 - wt[a] / m[a]) * v;
            double zc = 0.8 * v;
            double[] d = drift(t, zc);
            // кольцо показа — диск того же второго момента (σ² = ⟨r²⟩/2): сплошной ковёр пыли, а не кучки по кругу; видимый
            // край (4σ) — не дальше фронта: пыль встаёт там, где фронт уже прошёл
            double disc = Math.sqrt(0.5 * mr2[a] / m[a]);
            double sh = Math.max(Math.min(Math.hypot(disc, spread), front / EDGE_SIGMAS), v);
            out.blob(d[0], zc, d[1], sh, v, m[a] * fade);
        }
    }

    /**
     * Снос слоя ветром за t, м (x и z): ветер на высоте середины слоя {@code zc} над землёй, не ниже 2 м (⚠ высота
     * середины — нынешняя на весь путь: слой растёт, и вначале он шёл чуть медленнее).
     */
    public double[] drift(double t, double zc) {
        double lean = air.wind(Math.max(2, zc)) * Math.max(t, 0);
        return new double[]{air.windX() * lean, air.windZ() * lean};
    }

    /** Толща самого густого клуба слоя в возрасте t (через середину по горизонтали). */
    double tau(double t) {
        double[] max = {0};
        blobs(t, 1, (x, y, z, sh, sv, dust) -> max[0] = Math.max(max[0], DustModel.K_MIX * dust / (2 * Math.PI * sh * sv)));
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
