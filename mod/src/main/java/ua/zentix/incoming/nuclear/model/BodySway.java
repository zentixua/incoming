package ua.zentix.incoming.nuclear.model;

/**
 * Стоящий человек под ветром ударной волны — без Minecraft (проверяется юнит-тестом). Тело — перевёрнутый маятник на
 * щиколотках, который держит равновесие: собственные жёсткость и вязкость мышц и обратная связь по наклону и его
 * скорости с задержкой (модель независимых каналов, Peterka 2002, J Neurophysiol 88; числа здоровых взрослых — Pasma
 * et al. 2017, Front. Comput. Neurosci. 11:99, табл. 1). Толкает ветер за фронтом: лобовое сопротивление q·Cd·A на
 * высоте центра масс (Cd стоящего — 1,0–1,3, Hoerner 1965; площадь спереди — около 0,6 м²); напор — квадрат ветра, а ветер
 * у слабой волны идёт за давлением (u = p/ρc): по фазе Фридлендера, в разрежении — слабый обратный.
 * <p>
 * Земля под ногами тело не наклоняет. Воздушный толчок (airslap) ведёт грунт у поверхности вниз, а не вбок (Glasstone &
 * Dolan 1977, §6.13–6.14), его скорость повторяет давление (§6.84) и равна p/ρc грунта: у 1 psi — около сантиметра в
 * секунду, сдвиг вниз — импульс фазы / ρc, миллиметры (у 5 psi — около полутора сантиметров); тело опускается вместе с
 * землёй. Движение грунта впереди волны — прямой толчок от воронки наземного взрыва и обгоняющее волну — слабее толчка
 * волны, и посчитать его нечем (§6.84: простого способа нет); его нет.
 * <p>
 * Наклон дальше границы устойчивости (центр масс над краем стопы: вперёд 8°, назад 4,5°, вбок 8° — McCollum & Leen
 * 1989, J Motor Behav 21) человек не удерживает: шагает или падает. Это — от ~1,5 psi (у 15 кт — фаза около
 * секунды); что дальше, маятник с равновесием не описывает.
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
    /** Лобовое сопротивление стоящего, м²: площадь 0,6 м² × Cd 1,1. */
    static final double DRAG_AREA = 0.66;
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
     * не стих и тело не выпрямилось. Пустой — ветер не наклонил тело и на {@link #STILL}.
     *
     * @param qPa   скоростной напор за фронтом вдоль земли, Па
     * @param tPlus положительная фаза, с
     */
    public static double[] tilt(double qPa, double tPlus) {
        if (!(qPa > 0) || !(tPlus > 0)) return new double[0];
        int steps = (int) Math.ceil(LONGEST / STEP), lag = (int) Math.round(DELAY / STEP), every = (int) Math.round(1.0 / (RATE * STEP));
        double[] th = new double[steps + 1], om = new double[steps + 1];
        double mgh = MASS * G * HEIGHT, peak = 0;
        int end = steps;
        for (int i = 0; i < steps; i++) {
            double t = i * STEP, x = t / tPlus, f = (1 - x) * Math.exp(-x);
            // ветер — за давлением, напор — его квадрат со знаком
            double push = qPa * f * Math.abs(f) * DRAG_AREA * HEIGHT;
            int j = Math.max(0, i - lag);
            double hold = GAIN_P * th[j] + GAIN_D * om[j] + MUSCLE_K * th[i] + MUSCLE_B * om[i];
            double a = (mgh * Math.sin(th[i]) - hold + push) / INERTIA;
            om[i + 1] = om[i] + a * STEP;
            th[i + 1] = th[i] + om[i + 1] * STEP;
            peak = Math.max(peak, Math.abs(th[i + 1]));
            // ветер стих (разрежение за три фазы — меньше 0,3 % напора) и тело стоит
            if (t > 3 * tPlus && Math.abs(th[i + 1]) < STILL && Math.abs(om[i + 1]) < STILL) {
                end = i + 1;
                break;
            }
        }
        if (peak < STILL) return new double[0];
        double[] out = new double[end / every + 1];
        for (int k = 0; k < out.length; k++) out[k] = th[Math.min(end, k * every)];
        return out;
    }

    /** Наибольший наклон, рад, с отсчёта {@code from}. */
    public static double peak(double[] tilt, int from) {
        double m = 0;
        for (int i = Math.max(0, from); i < tilt.length; i++) m = Math.max(m, Math.abs(tilt[i]));
        return m;
    }
}
