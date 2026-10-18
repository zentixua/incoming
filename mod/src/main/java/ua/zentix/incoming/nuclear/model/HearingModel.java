package ua.zentix.incoming.nuclear.model;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Слух после ударной волны: временный сдвиг порога слышимости (TTS), его восстановление, разрыв барабанных перепонок.
 * <ul>
 * <li>Вред уху — не пик давления, а энергия в полосе слуха. У далёкой волны почти вся энергия — инфразвук, а слышимое
 * несёт один фронт: скачок давления p (нарастание доли миллисекунды, {@code client.sound.BlastPulse}). Энергия
 * скачка с A-коррекцией (МЭК 61672) — пик минус {@link #STEP_A} дБ, от длины фазы (дольше 50 мс) не зависит:
 * LAE = Lпик − 38,7 дБ. Звуковые удары самолётов до 144 фунт/фут² (~1 psi) людям слуха не портили (DTIC AD0680800,
 * обзоры NASA); там же и порог: суточная доза шума — 85 дБА за 8 ч (ISO 1999, NIOSH), LAE 129,6 дБ, — у фронта ~0,75
 * psi. Предел пика 140 дБ (CHABA, NIOSH) — для выстрелов, у которых вся энергия в слышимом, — к долгой волне не
 * подходит.</li>
 * <li>Сдвиг через 2 минуты у верхов (4 кГц): от нуля на пороге до {@link #TRAUMA_SHIFT} дБ у порога разрыва перепонок
 * (5 psi — акустическая травма), между — прямая в децибелах энергии (~3 дБ сдвига на дБ). Приближение: данных о TTS
 * от одной долгой волны мало, опоры — порог и травма. На низах (до 1 кГц) импульс сдвигает порог меньше —
 * {@link #LOW_SHARE} сдвига верхов (приближение по форме «выемки» у 4 кГц).</li>
 * <li>Восстановление — прямая по логарифму времени от 2 минут (Ward 1960): сдвиг до 40 дБ проходит за 16 часов, больше —
 * дольше, вдвое на каждые 10 дБ сверх (приближение: такие сдвиги у людей часто оставляют постоянную потерю, её в игре
 * нет). Раньше 2 минут сдвиг больше — той же прямой, от 12 с.</li>
 * <li>Перепонки рвутся по логнормальному закону: 1 % при 5 psi, 50 % при 15 psi (DTIC; Glasstone &amp; Dolan, табл.
 * 12.38), 99 % — при 45 psi. Разрыв — кондуктивная потеря {@link #RUPTURE_LOW}/{@link #RUPTURE_HIGH} дБ на низах и
 * верхах (перфорация средней величины, Mehta et al. 2006); перфорация зарастает за 4–8 недель.</li>
 * <li>Глуше, чем порог: сдвиг порога в улитке у громких звуков почти не слышен (рекрутмент — громкость догоняет
 * обычную к {@link #DISCOMFORT} дБ), поэтому звук игры (~{@link #LEVEL} дБ) тише на {@link #loudnessLoss}, а не на весь
 * сдвиг; кондуктивная потеря глушит всё — ровно на себя.</li>
 * </ul>
 */
public final class HearingModel {
    /** Опора звукового давления, Па. */
    public static final double REFERENCE_PA = 2e-5;
    /** Энергия скачка с A-коррекцией против пика, дБ: 10·lg(2∫A²(f)/(2πf)² df), с. */
    public static final double STEP_A = -38.74;
    /** С какой энергии слышимого фронта начинается сдвиг порога, дБ·с (85 дБА за 8 часов). */
    public static final double ONSET_EXPOSURE = 85 + 10 * Math.log10(8 * 3600);
    /** Сдвиг у верхов при 5 psi, дБ. */
    static final double TRAUMA_SHIFT = 50;
    /** Давление порога разрыва перепонок, кПа (5 psi). */
    static final double RUPTURE_ONSET_KPA = BlastModel.kpa(5);
    /** Доля сдвига верхов на низах. */
    static final double LOW_SHARE = 1 / 3.0;
    /** С какого часа идёт прямая восстановления (2 минуты); волны в пределах стольких часов — одна доза ({@link Ear}). */
    public static final double FIRST_HOURS = 2 / 60.0;
    /** Восстановление: за сколько часов проходит сдвиг до {@code SLOW_FROM} дБ. */
    static final double FULL_HOURS = 16, SLOW_FROM = 40;
    /** Перепонки: медиана, psi; 1 % — при 5 psi (z = −2,326). */
    static final double RUPTURE_MEDIAN_PSI = 15, RUPTURE_SIGMA = Math.log(3) / 2.326;
    /** Кондуктивная потеря при разрыве, дБ: низы и верха. */
    static final double RUPTURE_LOW = 25, RUPTURE_HIGH = 15;
    /** Перфорация зарастает: с какого дня потеря уходит и к какому её нет. */
    static final double HEAL_FROM_DAYS = 28, HEAL_DAYS = 56;
    /** Громкость звуков игры и порог дискомфорта, дБ: между ними — рекрутмент. */
    static final double LEVEL = 70, DISCOMFORT = 100;

    private HearingModel() {
    }

    /** Энергия слышимого фронта волны с пиком {@code pa} Па, дБ·с (LAE). */
    public static double exposure(double pa) {
        return 20 * Math.log10(Math.max(pa, 1e-9) / REFERENCE_PA) + STEP_A;
    }

    /** Сдвиг порога у верхов через 2 минуты, дБ, по энергии слышимого фронта {@code exposure}, дБ·с. */
    public static double shift(double exposure) {
        double trauma = exposure(1000 * RUPTURE_ONSET_KPA);
        return Math.max(0, TRAUMA_SHIFT * (exposure - ONSET_EXPOSURE) / (trauma - ONSET_EXPOSURE));
    }

    /** Сдвиг на низах при сдвиге верхов {@code high}, дБ. */
    public static double low(double high) {
        return LOW_SHARE * high;
    }

    /** За сколько часов проходит сдвиг {@code first} (через 2 минуты), дБ. */
    public static double recoveryHours(double first) {
        return FULL_HOURS * Math.pow(2, Math.max(0, first - SLOW_FROM) / 10);
    }

    /** Сдвиг через {@code hours} часов после волны, дБ; {@code first} — через 2 минуты. */
    public static double shiftAfter(double first, double hours) {
        if (first <= 0) return 0;
        double end = recoveryHours(first), t = Math.max(hours, 0.1 * FIRST_HOURS);
        if (t >= end) return 0;
        return first * (1 - Math.log10(t / FIRST_HOURS) / Math.log10(end / FIRST_HOURS));
    }

    /** Вероятность разрыва перепонок при давлении {@code kpa} кПа. */
    public static double ruptureProbability(double kpa) {
        double psi = BlastModel.psi(kpa);
        if (psi <= 0) return 0;
        return normal(Math.log(psi / RUPTURE_MEDIAN_PSI) / RUPTURE_SIGMA);
    }

    /** Кондуктивная потеря от разорванных перепонок через {@code days} дней, дБ: верха или низы. */
    public static double rupturedLoss(boolean high, double days) {
        double t = Math.max(0, Math.min(1, (days - HEAL_FROM_DAYS) / (HEAL_DAYS - HEAL_FROM_DAYS)));
        return (high ? RUPTURE_HIGH : RUPTURE_LOW) * (1 - t * t * (3 - 2 * t));
    }

    /**
     * На сколько тише звук игры при сдвиге порога {@code shift} в улитке, дБ: громкость растёт от сдвинутого порога и к
     * {@link #DISCOMFORT} дБ догоняет обычную (рекрутмент, прямая в дБ — приближение).
     */
    public static double loudnessLoss(double shift) {
        double s = Math.max(0, Math.min(shift, LEVEL));
        return (DISCOMFORT - LEVEL) * s / (DISCOMFORT - s);
    }

    /** Функция распределения стандартного нормального (Абрамовиц–Стиган 26.2.17, ошибка до 7,5·10⁻⁸). */
    static double normal(double z) {
        double t = 1 / (1 + 0.2316419 * Math.abs(z));
        double poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        double tail = Math.exp(-z * z / 2) / Math.sqrt(2 * Math.PI) * poly;
        return z >= 0 ? 1 - tail : tail;
    }

    /**
     * Волны у одного уха. Волны в пределах 2 минут (часов {@link #FIRST_HOURS}) — одна доза: энергии складываются (равная
     * энергия, ISO 1999), сдвиг — от суммы и с последней. Дальше у каждой дозы своя прямая восстановления, а сдвиг —
     * наибольший из них: вторая доза поверх ещё не прошедшего сдвига его не сокращает и не складывается с ним
     * (приближение: данных о сложении сдвигов от разных доз нет).
     */
    public static final class Ear {
        /** Больше стольких доз не держим: уходит та, у которой сдвиг сейчас меньше. */
        private static final int MOST = 16;
        /** Доза: энергия, дБ·с, и час её последней волны. */
        private final List<double[]> doses = new ArrayList<>();

        /** Волна с энергией слышимого фронта {@code exposure}, дБ·с, в час {@code hour} часов слуха. */
        public void blast(double exposure, double hour) {
            double e = exposure;
            for (Iterator<double[]> it = doses.iterator(); it.hasNext(); ) {
                double[] d = it.next();
                if (hour - d[1] > FIRST_HOURS) continue;
                e = 10 * Math.log10(Math.pow(10, e / 10) + Math.pow(10, d[0] / 10));
                it.remove();
            }
            doses.add(new double[]{e, hour});
            if (doses.size() > MOST) {
                double[] least = doses.get(0);
                for (double[] d : doses) if (shiftAfter(HearingModel.shift(d[0]), hour - d[1]) < shiftAfter(HearingModel.shift(least[0]), hour - least[1])) least = d;
                doses.remove(least);
            }
        }

        /** Сдвиг верхов в час {@code hour}, дБ; прошедшие дозы уходят (кроме тех, с которыми ещё сложится новая волна). */
        public double shift(double hour) {
            double most = 0;
            for (Iterator<double[]> it = doses.iterator(); it.hasNext(); ) {
                double[] d = it.next();
                double s = shiftAfter(HearingModel.shift(d[0]), hour - d[1]);
                if (s <= 0 && hour - d[1] > FIRST_HOURS) it.remove();
                else most = Math.max(most, s);
            }
            return most;
        }

        /** Есть ли ещё дозы. */
        public boolean any() {
            return !doses.isEmpty();
        }

        public void clear() {
            doses.clear();
        }
    }
}
