package ua.zentix.incoming.client.sound;

import java.util.ArrayList;
import java.util.List;

/**
 * Смесь взрывов у уха: громкость в игре — половина децибел <b>суммарного</b> уровня, потолок — у суммы (без Minecraft,
 * проверяется юнит-тестом).
 * <p>
 * Каждый взрыв звучит громкостью по своему давлению у уха — половина децибел давления, до потолка с 1 psi
 * ({@link BlastMix#loudness}, {@link Loudness#CEILING_LUFS}). Это перенос в колонки, а не закон: снаружи от тихой ночи до
 * ближнего взрыва ~110 дБ, у колонок дома ~60. Но перенос был у каждого звука отдельно, а звуки в колонках складываются
 * по мощности: два взрыва разом — +3 дБ в игре, хотя в жизни их уровень вырос на 3 дБ, а в переносе — на 1,5; залп
 * «Града» из 40 разрывов в 30 блоках шёл на 4–5 дБ выше потолка (−8,6 LUFS при потолке −13,5) и упирался в полную шкалу
 * (срезы на 0 dBFS в прогоне RV1, у 3.4.1 — 0–1). Поэтому перенос — у суммы:
 * <ul>
 * <li>взрыв — одна волна: его слои (ракурсы одной записи, удар в грудь — её низ) складываются по амплитуде, P_b — против
 * потолка;</li>
 * <li>уровни разных взрывов в жизни складываются по мощности, в игре — половина децибел суммы: (Σ P_b²)^½,
 * не громче потолка;</li>
 * <li>все слои всех взрывов — с одним множителем k² = min(1, (Σ P_b²)^½) / Σ P_b: один взрыв не громче потолка звучит
 * как прежде (k = 1), два равных разом — на 1,5 дБ громче одного, сколько угодно — не громче потолка.</li>
 * </ul>
 * Что звучит сейчас — по огибающей самой записи ({@link Envelope}: мгновенная громкость на 400 мс вперёд с каждого тика,
 * с тоном — быстрее или медленнее), так хвост раннего взрыва убавляет поздний ровно настолько, насколько он ещё звучит.
 * Так сводят звук в играх с «окном громкости» (HDR-звук: Frostbite, Wwise) — громкость всего слышимого держится в окне;
 * у нас окно — тот же перенос, что у каждого звука.
 */
final class Mix {
    /** Средний квадрат мгновенной громкости потолка ({@link Loudness#CEILING_LUFS}). */
    static final double CEILING = Envelope.meanSquare(Loudness.CEILING_LUFS);

    /** Один слой: огибающая записи (средний квадрат по тикам при громкости 1), тон, громкость. */
    record Layer(float[] envelope, double pitch, double volume) {
        /** Мощность слоя через {@code age} тиков после начала, против потолка. */
        double power(double age) {
            double at = age * pitch;
            if (at < 0 || envelope.length == 0) return 0;
            int i = (int) Math.floor(at);
            if (i >= envelope.length) return 0;
            double f = at - i, e = i + 1 < envelope.length ? envelope[i] + (envelope[i + 1] - envelope[i]) * f : envelope[i] * (1 - f);
            return volume * volume * e / CEILING;
        }

        double ticks() {
            return envelope.length / Math.max(pitch, 1e-6) + 1;
        }
    }

    /** Взрыв: тик начала и его слои. */
    static final class Blast {
        final double start;
        final List<Layer> layers = new ArrayList<>(5);
        double end;

        Blast(double start) {
            this.start = start;
            this.end = start;
        }

        void add(Layer l) {
            layers.add(l);
            end = Math.max(end, start + l.ticks());
        }

        /**
         * Мощность взрыва в тик {@code tick}, против потолка: слои — по амплитуде (ракурсы — одна запись, сведены по
         * амплитуде, {@link BlastMix}; удар в грудь — её же низ), сумма мощностей занизила бы её до 3 дБ.
         */
        double power(double tick) {
            double a = 0;
            for (Layer l : layers) a += Math.sqrt(l.power(tick - start));
            return a * a;
        }
    }

    private final List<Blast> blasts = new ArrayList<>();

    /** Взрыв, который зазвучит в тик {@code tick}: слои — {@link Blast#add}. */
    Blast blast(double tick) {
        blasts.removeIf(b -> b.end < tick);
        Blast b = new Blast(tick);
        blasts.add(b);
        return b;
    }

    /** Множитель громкости всех слоёв всех взрывов в тик {@code tick} (0..1). */
    double factor(double tick) {
        double sum = 0, sq = 0;
        for (Blast b : blasts) {
            double p = b.power(tick);
            sum += p;
            sq += p * p;
        }
        if (!(sum > 0)) return 1;
        return Math.sqrt(Math.min(1, Math.min(1, Math.sqrt(sq)) / sum));
    }

    /** Мощность смеси в игре в тик {@code tick} с множителем {@link #factor}, против потолка (сверка в тестах). */
    double heard(double tick) {
        double k = factor(tick), sum = 0;
        for (Blast b : blasts) sum += b.power(tick);
        return k * k * sum;
    }

    void reset() {
        blasts.clear();
    }

    int size() {
        return blasts.size();
    }
}
