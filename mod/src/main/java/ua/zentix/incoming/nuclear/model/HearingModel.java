package ua.zentix.incoming.nuclear.model;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * <li>Один закон для любой волны: энергия слышимого — A-энергия самой волны у уха, импульса Фридлендера с пиком p и
 * положительной фазой t₊ ({@link #exposure(double, double)}). У далёкой ядерной волны (фаза в секунды) спектр выше
 * 100 Гц — спектр скачка, и это ровно «пик − 38,7 дБ» ({@link #exposure(double)} — её предел при t₊ → ∞; у фазы 0,2 с
 * разница меньше 0,01 дБ). У обычного взрыва фаза — миллисекунды: слышна вся волна, энергия меньше, чем у скачка того
 * же пика (у 50 кг в 100 м — на 0,2 дБ, у 3 кг в 1 км — на 7 дБ). Порог — тот же: равная энергия, 85 дБА за 8 ч. Предел
 * пика 140 дБ (CHABA, NIOSH) — допуск для сотни выстрелов в день, а не порог сдвига от одной волны: у шахеда начало
 * сдвига (LAE 129,6 дБ) — в ~75 м, пик там ~168 дБ.</li>
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

    /** Фон тихой ночью за городом, дБА (оценка: сельская местность ночью — 25–35 дБА). */
    static final double QUIET_NIGHT_A = 30;
    /** Время, за которое слух складывает энергию короткого звука, с (временная суммация, ~200 мс). */
    static final double INTEGRATION_S = 0.2;
    /**
     * Поглощение воздухом по ISO 9613-1 при 20 °C и 70 % влажности, дБ/км, на 63 Гц … 8 кГц — та же таблица, что в
     * {@code tools/build_sounds.py} ({@code AIR_DB_KM}); между — по логарифмам, ниже 63 и выше 8 кГц — как f².
     */
    private static final double[] AIR_HZ = {63, 125, 250, 500, 1000, 2000, 4000, 8000}, AIR_DB_KM = {0.1, 0.3, 1.1, 2.8, 5.0, 9.0, 22.9, 76.6};
    /** Частоты интеграла энергии, Гц: от инфразвука до верха слуха, по логарифму. */
    private static final double[] F = new double[480];
    private static final double[] A2 = new double[F.length], DF = new double[F.length];
    /** Дальность слышимости по заряду: закон дорогой, а зарядов в игре несколько. */
    private static final Map<Double, Double> AUDIBLE = new ConcurrentHashMap<>();

    static {
        double lo = Math.log(1), hi = Math.log(40_000);
        for (int i = 0; i < F.length; i++) F[i] = Math.exp(lo + (hi - lo) * i / (F.length - 1));
        for (int i = 0; i < F.length; i++) {
            double a = aWeight(F[i]);
            A2[i] = a * a;
            // трапеции по логарифмической сетке
            DF[i] = 0.5 * ((i + 1 < F.length ? F[i + 1] : F[i]) - (i > 0 ? F[i - 1] : F[i]));
        }
    }

    private HearingModel() {
    }

    /**
     * A-коррекция (МЭК 61672-1), доля амплитуды: R_A(f) = 12194²·f⁴ / ((f² + 20,6²)·√((f² + 107,7²)(f² + 737,9²))·(f² +
     * 12194²)), +2,00 дБ — 1 на 1 кГц.
     */
    public static double aWeight(double f) {
        double f2 = f * f;
        double ra = 12194.0 * 12194 * f2 * f2 / ((f2 + 20.6 * 20.6) * Math.sqrt((f2 + 107.7 * 107.7) * (f2 + 737.9 * 737.9)) * (f2 + 12194.0 * 12194));
        return ra * Math.pow(10, 2.0 / 20);
    }

    /**
     * A-энергия волны у уха, дБ·с (LAE): импульс Фридлендера (1 − t/t₊)·e^(−t/t₊) с пиком {@code pa} Па (фронт —
     * скачок: нарастание в доли миллисекунды у давлений, где энергия вредит, на неё не влияет), спектр
     * |P(ω)| = p·t₊·ωt₊ / (1 + (ωt₊)²), E = 2∫A²(f)|P(f)|² df.
     *
     * @param tPlus положительная фаза у уха, с
     */
    public static double exposure(double pa, double tPlus) {
        return 20 * Math.log10(Math.max(pa, 1e-9) / REFERENCE_PA) + 10 * Math.log10(shape(tPlus, 0));
    }

    /**
     * Доля энергии волны с единичным пиком и фазой {@code tPlus}, что слух берёт (с A-коррекцией), с·Па²/Па²; путь
     * {@code metres} ест верха по ISO 9613-1 (0 — у источника).
     */
    private static double shape(double tPlus, double metres) {
        double sum = 0, t = Math.max(1e-6, tPlus);
        for (int i = 0; i < F.length; i++) {
            double wt = 2 * Math.PI * F[i] * t, spectrum = t * wt / (1 + wt * wt);
            double air = metres > 0 ? Math.pow(10, -airDbPerKm(F[i]) * metres / 1000 / 10) : 1;
            sum += A2[i] * spectrum * spectrum * air * DF[i];
        }
        return 2 * sum;
    }

    /** Поглощение воздухом на частоте f, дБ/км (ISO 9613-1, {@link #AIR_DB_KM}). */
    static double airDbPerKm(double f) {
        if (f <= AIR_HZ[0]) return AIR_DB_KM[0] * (f / AIR_HZ[0]) * (f / AIR_HZ[0]);
        int n = AIR_HZ.length - 1;
        if (f >= AIR_HZ[n]) return AIR_DB_KM[n] * (f / AIR_HZ[n]) * (f / AIR_HZ[n]);
        int i = 0;
        while (f > AIR_HZ[i + 1]) i++;
        double u = Math.log(f / AIR_HZ[i]) / Math.log(AIR_HZ[i + 1] / AIR_HZ[i]);
        return Math.exp(Math.log(AIR_DB_KM[i]) + u * Math.log(AIR_DB_KM[i + 1] / AIR_DB_KM[i]));
    }

    /**
     * A-энергия волны наземного взрыва заряда {@code tntKg} кг ТНТ на расстоянии {@code metres}, дБ·с: давление и фаза
     * — Кинни–Грэхем ({@link BlastModel}), по дороге воздух ест верха (ISO 9613-1). Земли, рельефа и ветра нет — их
     * считает путь у слушателя ({@code client.sound.Outdoor}).
     */
    public static double blastExposureAt(double tntKg, double metres) {
        double pa = 1000 * BlastModel.surfaceOverpressureKpa(metres, tntKg), tPlus = BlastModel.chemicalPhaseSeconds(metres, tntKg, true);
        return 20 * Math.log10(Math.max(pa, 1e-12) / REFERENCE_PA) + 10 * Math.log10(shape(tPlus, metres));
    }

    /**
     * Докуда слышно наземный взрыв заряда {@code tntKg} кг ТНТ тихой ночью, м: там его A-энергия падает до энергии
     * фона ({@link #QUIET_NIGHT_A}) за время суммации слуха ({@link #INTEGRATION_S}) — 23 дБ·с. Это R0 дальней модели
     * звука (запас слышимости 20·lg(R0/d) минус потери пути) и дальность, до которой сервер шлёт пакет взрыва. Верхняя
     * оценка: мир плоский, без турбулентности и волновода; днём зона тени, холмы, дождь — у слушателя. 0 — заряда нет.
     */
    public static double audibleRangeM(double tntKg) {
        if (!(tntKg > 0)) return 0;
        return AUDIBLE.computeIfAbsent(tntKg, HearingModel::solveAudible);
    }

    private static double solveAudible(double tntKg) {
        double threshold = QUIET_NIGHT_A + 10 * Math.log10(INTEGRATION_S), lo = 1, hi = 1e7;
        if (blastExposureAt(tntKg, hi) >= threshold) return hi;
        for (int i = 0; i < 60; i++) {
            double mid = Math.sqrt(lo * hi);
            if (blastExposureAt(tntKg, mid) > threshold) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /**
     * Энергия слышимого фронта долгой волны с пиком {@code pa} Па, дБ·с (LAE): предел {@link #exposure(double, double)}
     * при положительной фазе много дольше слышимого (t₊ → ∞) — скачок.
     */
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
