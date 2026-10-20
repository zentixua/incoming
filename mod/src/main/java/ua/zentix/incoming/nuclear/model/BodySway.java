package ua.zentix.incoming.nuclear.model;

import java.util.Arrays;

/**
 * Стоящий человек под ветром ударной волны — без Minecraft (проверяется юнит-тестом). Тело — перевёрнутый маятник на
 * щиколотках, который держит равновесие: собственные жёсткость и вязкость мышц и обратная связь по наклону и его
 * скорости с задержкой, которую мышцы отдают моментом через свою активацию — звено второго порядка (модель независимых
 * каналов: Peterka 2002, J Neurophysiol 88; с активацией — van der Kooij &amp; Peterka 2011; все числа — Pasma et al.
 * 2017, Front. Comput. Neurosci. 11:99, табл. 1 и (A4); затухание активации — относительное, член 2βω₀s: в (A4)
 * напечатано sβω₀², у безразмерного β это не та размерность). Положительной обратной связи по моменту той модели
 * (K_F, τ_F 17,4 с) нет: за секунды качания она меняет наклон меньше чем на процент. Толкает ветер за фронтом: лобовое
 * сопротивление q·Cd·A на высоте центра масс (Cd стоящего — 1,0–1,3, Hoerner 1965; площадь спереди — около 0,6 м²);
 * напор — квадрат ветра, а ветер у слабой волны идёт за давлением (u = p/ρc): по фазе Фридлендера, в разрежении —
 * слабый обратный.
 * <p>
 * Первым тело бьёт сам фронт (нагрузка при обтекании — diffraction loading, Glasstone &amp; Dolan 1977, гл. IV): давление за фронтом
 * встаёт меньше чем за миллисекунду, на грудь — отражённое (G&amp;D, ур. 3.56.1) и спадает к давлению торможения за 3S/U
 * (S — половина ширины тела, U — скорость фронта), а спину оно обходит за L/U (L — толщина тела) и нарастает там ещё
 * 4S/U. Пока волна обтекает тело, разница давлений толкает его — ~2 мс, против секунды ветра: это удар, тело получает
 * скорость наклона сразу ({@link #jolt}). У 1 psi — 11 Н·с (8°/с), у 3,5 psi (15 кт в 2 км) — 38 Н·с (27°/с), против
 * 20–40 Н·с ветра за всю фазу. Размеры тела — ANSUR II (Gordon et al. 2014): толщина груди ~0,25 м, ширина плеч ~0,4 м;
 * та же нагрузка у зданий (идеализация G&amp;D для прямоугольника), у тела она — оценка. Голова идёт с телом: своя
 * нагрузка на голову (площадь ~0,03 м², масса ~4,5 кг) даёт ей скорость вдвое меньше той, что даёт ей на её высоте
 * наклон тела (~0,13 м/с против ~0,23 у 1 psi); шеи и её качания модель не считает.
 * <p>
 * Земля под ногами тело не наклоняет. Воздушный толчок (airslap) приходит вместе с волной и ведёт грунт резко вниз,
 * потом отчасти обратно (Glasstone &amp; Dolan 1977, §6.12–6.16); скорость грунта — p/ρc (плоская волна в грунте,
 * ρc — оценка): у 1 psi 0,2–0,9 см/с и сдвиг 1–4 мм, у ~6 psi — 1,5–5 см/с и до 1,6 см. Тело толчок чувствует, но
 * опускается вместе с землёй и всем вокруг, а наклон поверхности под фронтом — скорость грунта на его скорость по земле,
 * тысячные доли градуса. Движение грунта впереди волны посчитать нечем (§6.84: простого способа нет); его нет.
 * <p>
 * Наклон дальше границы устойчивости (центр масс над краем стопы: вперёд 8°, назад 4,5°, вбок 8° — McCollum & Leen
 * 1989, J Motor Behav 21) человек не удерживает: шагает или падает. Лицом к волне это (удар фронта и ветер) — от ~0,77 psi у
 * 15 кт (фаза 1,3 с) и ~0,88 psi у 2 кт (0,6 с), спиной — от ~1,06 и ~1,35 psi: у подрыва больше ветер дует дольше. Что дальше,
 * маятник с равновесием не описывает (линейная обратная связь тянула бы «упавшее» тело назад и перебрасывала через
 * вертикаль): шаг держит центр масс над краем новой опоры — наклон стоит на границе, пока ветер сильнее равновесия, и
 * тело выпрямляется, когда равновесие снова его перебарывает (отступление: шаг и падение не считаются). Порог шага —
 * по наклону, статические границы; под ветром центр давления доходит до края стопы раньше: по моменту стопы
 * (mgh·sin границы) от одного ветра шаг был бы с ~0,6 psi лицом к волне у 15 кт и ~0,8 у 2 кт, спиной — с ~0,8 и ~1,1 psi; без
 * модели шага (два звена) это не посчитать — отступление. За фазой назад к взрыву тело клонит обратный ветер разрежения
 * (по Фридлендеру — до 13,5 % давления фронта, напор — 1,8 %): у 2–3 psi — на 1–2°; до края — спиной к волне (назад
 * 4,5°) с ~4,6 psi, лицом (вперёд 8°) — с 6–8 psi.
 */
public final class BodySway {
    /** Масса тела, кг. */
    static final double MASS = 75;
    /** Высота центра масс над щиколотками, м: на ней и сила ветра (середина тела спереди). */
    static final double HEIGHT = 0.83;
    /** Момент инерции тела вокруг щиколоток, кг·м². */
    static final double INERTIA = 66;
    /** Жёсткость и вязкость самих мышц: Н·м/рад и Н·м·с/рад. */
    static final double MUSCLE_K = 40.5, MUSCLE_B = 68.8;
    /** Обратная связь равновесия: по наклону, Н·м/рад, по его скорости, Н·м·с/рад, и её задержка, с. */
    static final double GAIN_P = 943.9, GAIN_D = 313.5, DELAY = 0.097;
    /** Активация мышц: собственная частота, рад/с, и относительное затухание. */
    static final double ACTIVATION_W = 16.8, ACTIVATION_DAMPING = 0.99;
    /** Площадь тела спереди, м². */
    static final double AREA = 0.6;
    /** Лобовое сопротивление стоящего, м²: площадь × Cd 1,1. */
    static final double DRAG_AREA = AREA * 1.1;
    /** Толщина тела вдоль волны и половина его ширины, м: путь фронта до спины и путь разгрузки груди (ANSUR II). */
    static final double DEPTH = 0.25, HALF_WIDTH = 0.2;
    static final double G = 9.81;
    /** Отсчётов наклона в секунду. */
    public static final int RATE = 100;
    /** Шаг счёта, с. */
    static final double STEP = 0.001;
    /** Границы устойчивости, рад: вперёд, назад, вбок. */
    public static final double FORWARD = Math.toRadians(8), BACK = Math.toRadians(4.5), SIDE = Math.toRadians(8);
    /** Тише этого наклона, рад (0,01°), тело стоит. */
    static final double STILL = Math.toRadians(0.01);
    /** Дольше стольких секунд не считаем. */
    static final double LONGEST = 20;

    private BodySway() {}

    /**
     * Наклон тела по ветру, рад (больше нуля — по ветру), {@link #RATE} отсчётов в секунду с прихода фронта, пока ветер
     * не стих и тело не выпрямилось. Пустой — ветер не наклонил тело и на {@link #STILL}. Дальше границы устойчивости
     * наклон не уходит: стоит на ней, пока равновесие не перебарывает ветер.
     *
     * @param pPa   давление фронта у тела (падающее), Па
     * @param qPa   скоростной напор за фронтом вдоль земли, Па
     * @param open  доля открытого к взрыву (стена, дом, холм между телом и взрывом — ветровая тень): удар и ветер — её доля
     *              (оценка: фронт огибает преграду, но толкает уже не вдоль земли)
     * @param tPlus положительная фаза, с
     * @param edge  граница устойчивости по ветру, рад
     * @param back  граница против ветра (туда тело возвращается и туда толкает обратный ветер разрежения), рад
     */
    public static double[] tilt(double pPa, double qPa, double open, double tPlus, double edge, double back) {
        if (!(qPa > 0) || !(open > 0) || !(tPlus > 0)) return new double[0];
        int steps = (int) Math.ceil(LONGEST / STEP), lag = (int) Math.round(DELAY / STEP), every = (int) Math.round(1.0 / (RATE * STEP));
        // наклон и его скорость за задержку назад (кольцом; до волны тело стоит — нули)
        double[] thBack = new double[lag], omBack = new double[lag];
        double[] out = new double[steps / every + 2];
        int n = 1;
        // удар фронта — короче шага счёта и задержки равновесия: скорость наклона сразу
        double mgh = MASS * G * HEIGHT, peak = 0, th = 0, om = open * jolt(pPa, qPa, tPlus) * HEIGHT / INERTIA, torque = 0, torqueRate = 0;
        double w2 = ACTIVATION_W * ACTIVATION_W, damp = 2 * ACTIVATION_DAMPING * ACTIVATION_W;
        for (int i = 0; i < steps; i++) {
            double t = i * STEP;
            // ветер — за давлением, напор — его квадрат со знаком; среднее за шаг: у обычного взрыва фаза — несколько шагов
            double push = open * qPa * windShare(t / tPlus, (t + STEP) / tPlus) * DRAG_AREA * HEIGHT;
            int k = i % lag;
            double command = GAIN_P * thBack[k] + GAIN_D * omBack[k];
            thBack[k] = th;
            omBack[k] = om;
            // команда равновесия становится моментом через активацию мышц
            torqueRate += (w2 * (command - torque) - damp * torqueRate) * STEP;
            torque += torqueRate * STEP;
            double a = (mgh * Math.sin(th) - torque - MUSCLE_K * th - MUSCLE_B * om + push) / INERTIA;
            om += a * STEP;
            th += om * STEP;
            if (th > edge || th < -back) {
                // за границей человек шагает: центр масс остаётся над краем новой опоры, и равновесие начинает с неё —
                // тело стоит на краю (задержка помнит край без скорости, мышцы держат его)
                th = th > 0 ? edge : -back;
                om = 0;
                Arrays.fill(thBack, th);
                Arrays.fill(omBack, 0);
                torque = GAIN_P * th;
                torqueRate = 0;
            }
            peak = Math.max(peak, Math.abs(th));
            boolean sample = (i + 1) % every == 0;
            if (sample) out[n++] = th;
            // ветер стих (разрежение за три фазы — меньше 1 % напора) и тело стоит
            if (t > 3 * tPlus && Math.abs(th) < STILL && Math.abs(om) < STILL) {
                if (!sample) out[n++] = th;
                break;
            }
        }
        if (peak < STILL) return new double[0];
        return Arrays.copyOf(out, n);
    }

    /**
     * Среднее f·|f| (напор по фазе Фридлендера f = (1 − x)·e^(−x), со знаком) на доле фазы от {@code x0} до {@code x1}:
     * ∫(1 − x)²e^(−2x)dx = −e^(−2x)·((1 − x)²/2 − (1 − x)/2 + 1/4), за x = 1 — с обратным знаком.
     */
    static double windShare(double x0, double x1) {
        return (windImpulse(x1) - windImpulse(x0)) / (x1 - x0);
    }

    /** ∫₀ˣ f·|f| dx. */
    private static double windImpulse(double x) {
        double one = antiderivative(1) - antiderivative(0);
        return x <= 1 ? antiderivative(x) - antiderivative(0) : 2 * one - (antiderivative(x) - antiderivative(0));
    }

    private static double antiderivative(double x) {
        double u = 1 - x;
        return -Math.exp(-2 * x) * (u * u / 2 - u / 2 + 0.25);
    }

    /** Удар фронта у долгой волны (фаза много дольше обтекания тела, ядерный подрыв): {@link #jolt(double, double, double)} при t₊ → ∞. */
    public static double jolt(double pPa, double qPa) {
        return jolt(pPa, qPa, Double.POSITIVE_INFINITY);
    }

    /**
     * Удар фронта по телу вдоль земли, Н·с: импульс разницы давлений на грудь и спину, пока волна обтекает тело, сверх
     * ветра ({@link #tilt} считает напор с прихода фронта). Грудь: отражённое p_r спадает к p + q за 3S/U — сверх ветра
     * (p_r − p − q)·3S/2U; спина: давления нет L/U и оно нарастает к p за 4S/U — p·(L + 2S)/U. Фаза давления (секунды)
     * за эти миллисекунды не спадает. Под воздушным подрывом фронт падает на землю наклонно: вдоль земли — доля cos α,
     * а {@code qPa} уже несёт cos²α (напор вдоль земли против напора Ренкина–Гюгонио у этого давления). Фаза
     * обычного взрыва — миллисекунды, сравнимые с обтеканием: давление за эти миллисекунды спадает по Фридлендеру, и
     * обе разницы берутся с его долей в каждый миг (у ядерной фазы в секунды — меньше 0,5 %).
     *
     * @param pPa   давление фронта у тела (падающее), Па
     * @param qPa   скоростной напор за фронтом вдоль земли, Па
     * @param tPlus положительная фаза, с
     */
    public static double jolt(double pPa, double qPa, double tPlus) {
        if (!(pPa > 0) || !(qPa > 0) || !(tPlus > 0)) return 0;
        double kpa = pPa / 1000, full = 1000 * BlastModel.dynamicPressureKpa(kpa), u = BlastModel.frontSpeed(kpa);
        double along = Math.sqrt(Math.min(1, qPa / full)), reflected = 1000 * BlastInjury.reflectedKpa(kpa);
        double clear = 3 * HALF_WIDTH / u, behind = DEPTH / u, rise = 4 * HALF_WIDTH / u;
        // грудь: избыток отражённого спадает к нулю за 3S/U; спина: давления нет L/U, потом оно нарастает к p за 4S/U
        double front = Math.max(0, reflected - pPa - full) * weighted(clear, 0, tPlus, 1), rear = pPa * (weighted(behind, 0, tPlus, 0) + weighted(rise, behind, tPlus, 1));
        return AREA * along * (front + rear);
    }

    /**
     * ∫ доли давления по Фридлендеру на отрезке длиной {@code span} с мига {@code from}, с весом, спадающим к концу
     * отрезка ({@code fall} = 1: 1 − s/span) или ровным (0), с.
     */
    private static double weighted(double span, double from, double tPlus, double fall) {
        int n = 64;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double s = (i + 0.5) / n * span, x = (from + s) / tPlus;
            sum += (1 - fall * s / span) * (1 - x) * Math.exp(-x);
        }
        return sum * span / n;
    }

    /** Наибольший наклон, рад, с отсчёта {@code from}. */
    public static double peak(double[] tilt, int from) {
        double m = 0;
        for (int i = Math.max(0, from); i < tilt.length; i++) m = Math.max(m, Math.abs(tilt[i]));
        return m;
    }
}
