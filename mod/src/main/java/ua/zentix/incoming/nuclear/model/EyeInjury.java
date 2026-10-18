package ua.zentix.incoming.nuclear.model;

/**
 * Глаза и вспышка (Glasstone &amp; Dolan 1977, §12.83–12.88): ослепление — засветка всего поля зрения, временная, и ожог
 * сетчатки — постоянное слепое пятно там, где на сетчатке был шар.
 *
 * <p>Опора — рис. 12.88a (ясный день) и 12.88b (ночь): дальности для наблюдателя на земле при подрыве на 10 000 футов, до
 * которых ослепление длится около 10 с (так, что не прочесть приборы при тусклом свете) и до которых обжигается
 * сетчатка смотревшего на шар (моргание за 0,25 с уже учтено: оно спасает только от больших мощностей). Ночью дальности
 * больше: зрачок шире, а глаз, привыкший к темноте, засвечивается сильнее (§12.84). Подрывы мода ниже 10 000 футов, и
 * воздуха на пути к далёкому глазу у них больше: дальности рисунка — их верхняя оценка.
 *
 * <p>Засветка растёт с импульсом у глаза: 10 с при импульсе на дальности рисунка и дольше ближе — по логарифму импульса
 * (как время восстановления после засвета), до времени, за которое глаз приходит в себя после полного выцветания
 * пигмента: днём — около минуты (колбочки; проба на засвет у окулиста), ночью — около 10 минут (колбочки выходят на
 * плато темновой адаптации и снова видят местность под луной; палочкам для звёздного света нужно до получаса). Полное
 * выцветание поля — при освещённости сетчатки {@link #FULL_BLEACH_TD_S} от освещённой вспышкой земли перед глазом
 * (оценка). У G&amp;D засветка — «от нескольких секунд до минут» (§12.84).
 */
public final class EyeInjury {
    /** Мощности опорных точек рис. 12.88, кт. */
    private static final double[] YIELDS = {1, 10, 100, 1000, 10000};
    /** Ослепление на 10 с, мили от эпицентра: день, ночь (подрыв на 10 000 футов, снято с рисунка ±1 миля). */
    private static final double[] FLASH_DAY_MI = {5.7, 12.7, 19.8, 15.1, 27.8};
    private static final double[] FLASH_NIGHT_MI = {40.3, 60, 67, 53.7, 74};
    /** Ожог сетчатки, мили от эпицентра: день, ночь. */
    private static final double[] RETINA_DAY_MI = {11.0, 18.5, 23.9, 32.7, 54.9};
    private static final double[] RETINA_NIGHT_MI = {19.3, 30.5, 42.7, 56.5, 84};
    private static final double MILE_M = 1609.344, FIGURE_HOB_M = 3048;
    /** Видимость, для которой построен рисунок: ясно (та же, что «ясно» у света, {@link ThermalModel#VISIBILITY_CLEAR}). */
    private static final double FIGURE_VISIBILITY = ThermalModel.VISIBILITY_CLEAR;

    /** Ослепление на краю дальности рисунка, с. */
    public static final double EDGE_S = 10;
    /** Глаз приходит в себя после полного выцветания пигмента, с: днём, ночью (плато колбочек темновой адаптации). */
    public static final double FULL_DAY_S = 60, FULL_NIGHT_S = 600;
    /**
     * Освещённость сетчатки, при которой пигмент выцветает почти весь (95 %), тд·с: три постоянные выцветания колбочек
     * (~5·10⁶ тд·с, Раштон) — оценка.
     */
    public static final double FULL_BLEACH_TD_S = 1.5e7;
    /** Альбедо земли перед глазом, световая отдача света шара (~6000 К), лм/Вт. */
    private static final double GROUND_ALBEDO = 0.2, LUMENS_PER_WATT = 93;
    /** Зрачок, мм: днём и у привыкшего к темноте. */
    private static final double PUPIL_DAY_MM = 3, PUPIL_NIGHT_MM = 7;

    private EyeInjury() {}

    /** Насколько глаз привык к темноте (0 — день, 1 — темнота) при свете у глаза 0..15 (больший из неба и блоков). */
    public static double darkness(int light) {
        return 1 - Math.max(0, Math.min(15, light)) / 15.0;
    }

    /**
     * Сколько секунд ослеплён глаз (поле засвечено так, что не разглядеть тусклого) при импульсе {@code q} кал/см² у
     * глаза.
     *
     * @param dark насколько глаз привык к темноте в миг вспышки: 0 — день, 1 — ночь без огней
     */
    public static double dazzleSeconds(double q, double yieldKt, double dark) {
        if (q <= 0) return 0;
        double edge = edgeFluence(yieldKt, dark), full = Math.max(edge * 1.01, fullBleachFluence(dark));
        double longest = mixLog(FULL_DAY_S, FULL_NIGHT_S, dark);
        double t = EDGE_S + (longest - EDGE_S) * Math.log(q / edge) / Math.log(full / edge);
        return Math.max(0, Math.min(longest, t));
    }

    /** Импульс у глаза, при котором ослепление — {@link #EDGE_S}: на дальности рисунка, кал/см². */
    static double edgeFluence(double yieldKt, double dark) {
        double day = fluenceAt(FLASH_DAY_MI, yieldKt), night = fluenceAt(FLASH_NIGHT_MI, yieldKt);
        return Math.exp(Math.log(day) + (Math.log(night) - Math.log(day)) * clamp(dark));
    }

    /** Импульс у глаза, при котором выцветает всё поле (свет освещённой земли перед глазом), кал/см². */
    static double fullBleachFluence(double dark) {
        double pupil = PUPIL_DAY_MM + (PUPIL_NIGHT_MM - PUPIL_DAY_MM) * clamp(dark);
        double area = Math.PI * pupil * pupil / 4;
        // кал/см² → Дж/м² → лм·с/м² → кд·с/м² ламбертовой земли → тд·с
        double tdPerCal = 4.184e4 * LUMENS_PER_WATT * GROUND_ALBEDO / Math.PI * area;
        return FULL_BLEACH_TD_S / tdPerCal;
    }

    /**
     * До какой наклонной дальности, м, обжигается сетчатка смотревшего на шар при видимости {@code visibilityM}: энергия
     * на единицу площади изображения от дальности не зависит, её отнимает только воздух (§12.86), поэтому граница — та же
     * прозрачность воздуха, что на дальности рисунка в ясную погоду.
     */
    public static double retinaRange(double yieldKt, double dark, double visibilityM) {
        double day = slant(at(RETINA_DAY_MI, yieldKt)), night = slant(at(RETINA_NIGHT_MI, yieldKt));
        return (day + (night - day) * clamp(dark)) * visibilityM / FIGURE_VISIBILITY;
    }

    /** Импульс на дальности рисунка {@code miles} в ясную погоду (подрыв в воздухе), кал/см². */
    private static double fluenceAt(double[] miles, double yieldKt) {
        return ThermalModel.fluenceCalPerCm2(slant(at(miles, yieldKt)), yieldKt, false, FIGURE_VISIBILITY);
    }

    /** Наклонная дальность до подрыва на высоте рисунка, м. */
    private static double slant(double groundM) {
        return Math.hypot(groundM, FIGURE_HOB_M);
    }

    /** Дальность по рисунку, м: линейно по логарифму мощности; мощность — в пределах рисунка. */
    private static double at(double[] miles, double yieldKt) {
        double x = Math.log10(Math.max(YIELDS[0], Math.min(YIELDS[YIELDS.length - 1], yieldKt)));
        int i = Math.min(YIELDS.length - 2, (int) Math.floor(x));
        double f = x - i;
        return (miles[i] + (miles[i + 1] - miles[i]) * f) * MILE_M;
    }

    private static double mixLog(double day, double night, double dark) {
        return Math.exp(Math.log(day) + (Math.log(night) - Math.log(day)) * clamp(dark));
    }

    private static double clamp(double dark) {
        return Math.max(0, Math.min(1, dark));
    }
}
