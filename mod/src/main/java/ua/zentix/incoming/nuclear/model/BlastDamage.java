package ua.zentix.incoming.nuclear.model;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Что волна обычного заряда ломает: кривая равного разрушения в осях «давление — импульс» по закону Джаррета и давление
 * газов взрыва в замкнутом помещении. Заряд — кг ТНТ, давление — кПа, импульс — Па·с; взрыв на земле
 * ({@link BlastModel#surfaceOverpressureKpa}, {@link BlastModel#chemicalImpulse}).
 *
 * <p><b>Джаррет</b> (D. E. Jarrett, «Derivation of the British explosives safety distances», Annals of the New York Academy
 * of Sciences 152, 1968): радиус уровня разрушения кирпичных домов от бомб R = K·∛W / (1 + (3175/W)²)^⅙ (W — кг ТНТ,
 * R — м; K: A — почти полный снос 3,8, B — снос 50–75 % наружных стен 5,6, Cb — непригоден для жилья 9,6, Ca — умеренные
 * 28, D — окна и черепица 56). У большого заряда разрушает давление: R = K·∛W, и K — приведённая дальность, где давление
 * волны падает до порога. У малого — импульс: волна короче, чем стена успевает ответить, и радиус растёт как W^⅔.
 *
 * <p><b>Кривая блока.</b> Порог давления блока P_c — тот же, что у ядерной волны ({@code BlockResponse}): K = приведённая
 * дальность, где давление большого заряда на земле равно P_c (у кладки 12 psi — 3,6, у дерева 5 psi — 5,6, как A и B у
 * Джаррета). Точки (P, I) волны на радиусе Джаррета этого K для зарядов от 1 кг до 10⁷ кг и есть кривая равного
 * разрушения: блок ломается, если его точка (P, I) выше и правее кривой. Гипербола (P − P_c)(I − I_c) = k ту же
 * зависимость не держит: на зарядах 3–2400 кг её ошибка против Джаррета 30–60 %, поэтому кривая — таблица по самой
 * формуле. Приближения: Джаррет выведен по кирпичным домам и бомбам 50–1000 кг, тот же переход 3175 кг взят для всех
 * блоков, ниже 1 кг кривая не продолжается (нужен импульс не меньше, чем у 1 кг, — оценка); у ядерной волны импульс на
 * порядки выше кривой, и ответ — по одному давлению, как у руин ({@code BlockResponseTest}).
 *
 * <p><b>Газы в помещении</b> (UFC 3-340-02, 2008, рис. 2-152; степенная подгонка — оценка, ±30 % в середине графика):
 * P_qs ≈ 2,25·(W/V)^0,72 МПа, V — свободный объём, м³. Давление держится, пока газы не вытекут через проёмы: спад за
 * τ = V / (C_d·A·c), C_d 0,6, c — 340 м/с (оценка: истечение газа через отверстие при скорости звука); импульс газов —
 * P_qs·τ. Без проёмов τ бесконечно — стены держат одно давление.
 */
public final class BlastDamage {
    /** Переход от импульса к давлению у Джаррета, кг ТНТ. */
    public static final double JARRETT_W = 3175;
    /** Уровни Джаррета: K, м/кг^⅓. */
    public static final double JARRETT_A = 3.8, JARRETT_B = 5.6, JARRETT_CB = 9.6, JARRETT_CA = 28, JARRETT_D = 56;
    /** Сетка зарядов кривой: от 1 кг до 10⁷ кг, 20 узлов на порядок. */
    private static final int PER_DECADE = 20, NODES = 7 * PER_DECADE + 1;
    /** Заряд «бесконечно большой» — по нему K порога. */
    private static final double HUGE = 1e12;
    /** Коэффициент истечения через проём и скорость газа в нём, м/с (оценка). */
    private static final double VENT_CD = 0.6, VENT_SPEED = 340;

    private static final ConcurrentHashMap<Double, Curve> CURVES = new ConcurrentHashMap<>();

    private BlastDamage() {}

    /** Радиус уровня разрушения Джаррета, м. */
    public static double jarrett(double k, double tntKg) {
        if (tntKg <= 0) return 0;
        double x = JARRETT_W / tntKg;
        return k * Math.cbrt(tntKg) / Math.pow(1 + x * x, 1 / 6.0);
    }

    /** Приведённая дальность, м/кг^⅓, где давление большого наземного заряда падает до {@code pcKpa}. */
    public static double scaledRange(double pcKpa) {
        return BlastModel.rangeForSurfaceOverpressure(pcKpa, HUGE) / Math.cbrt(HUGE);
    }

    /** Радиус, где блок с порогом {@code pcKpa} ломается от заряда {@code tntKg} в открытом поле, м (Джаррет). */
    public static double breakRange(double pcKpa, double tntKg) {
        return jarrett(scaledRange(pcKpa), tntKg);
    }

    /**
     * Импульс, Па·с, при котором волна давления {@code pKpa} ломает блок с порогом {@code pcKpa}; бесконечность — давление
     * ниже порога (сколько ни держи — не сломает).
     */
    public static double impulseToBreak(double pKpa, double pcKpa) {
        if (pKpa <= pcKpa) return Double.POSITIVE_INFINITY;
        Curve c = CURVES.computeIfAbsent(pcKpa, Curve::new);
        double[] p = c.p, i = c.i;
        if (pKpa >= p[0]) return i[0];
        int last = p.length - 1;
        if (pKpa <= p[last]) return i[last] * (p[last] - pcKpa) / (pKpa - pcKpa);
        int lo = 0, hi = last;
        while (hi - lo > 1) {
            int m = (lo + hi) >>> 1;
            if (p[m] >= pKpa) lo = m;
            else hi = m;
        }
        double u = (Math.log(p[lo]) - Math.log(pKpa)) / (Math.log(p[lo]) - Math.log(p[hi]));
        return Math.exp(Math.log(i[lo]) + u * (Math.log(i[hi]) - Math.log(i[lo])));
    }

    /** Ломает ли волна (давление {@code pKpa}, импульс {@code iPas}) блок с порогом {@code pcKpa}. */
    public static boolean breaks(double pKpa, double iPas, double pcKpa) {
        return iPas >= impulseToBreak(pKpa, pcKpa);
    }

    /**
     * Какая доля волны проходит дальше через сломанный блок: импульс, ушедший на разрушение (тот, что лежит на кривой при
     * этом давлении), волна теряет, остальное идёт дальше. Блок на самом пороге гасит её целиком, блок глубоко в зоне —
     * почти не мешает. Оценка: дифракции и отражения от обломков нет.
     */
    public static double passes(double pKpa, double iPas, double pcKpa) {
        double need = impulseToBreak(pKpa, pcKpa);
        return need >= iPas ? 0 : 1 - need / iPas;
    }

    /** Давление газов взрыва {@code tntKg} в замкнутом объёме {@code volumeM3}, кПа (UFC 3-340-02, подгонка — оценка). */
    public static double quasiStaticKpa(double tntKg, double volumeM3) {
        if (tntKg <= 0 || volumeM3 <= 0) return 0;
        return 2250 * Math.pow(tntKg / volumeM3, 0.72);
    }

    /** Сколько держится давление газов в объёме {@code volumeM3} с проёмами {@code ventM2}, с (бесконечность — без проёмов). */
    public static double ventSeconds(double volumeM3, double ventM2) {
        return ventM2 <= 0 ? Double.POSITIVE_INFINITY : volumeM3 / (VENT_CD * ventM2 * VENT_SPEED);
    }

    /** Кривая равного разрушения порога P_c: давление убывает, импульс растёт с зарядом. */
    private static final class Curve {
        final double[] p = new double[NODES], i = new double[NODES];

        Curve(double pcKpa) {
            double k = scaledRange(pcKpa);
            for (int j = 0; j < NODES; j++) {
                double w = Math.pow(10, (double) j / PER_DECADE);
                double r = jarrett(k, w);
                p[j] = BlastModel.surfaceOverpressureKpa(r, w);
                i[j] = BlastModel.chemicalImpulse(r, w, true);
            }
        }
    }
}
