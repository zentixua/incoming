package ua.zentix.incoming.client.sound;

import java.util.ArrayList;
import java.util.List;

/**
 * Смесь взрывов у уха: громкость в игре — половина децибел <b>суммарного</b> уровня, потолок — у суммы, а доля каждого
 * взрыва в ней — его доля мощности в жизни (без Minecraft, проверяется юнит-тестом).
 * <p>
 * Каждый взрыв звучит громкостью по своему давлению у уха — половина децибел давления, до потолка с 1 psi
 * ({@link BlastMix#loudness}, {@link Loudness#CEILING_LUFS}). Это перенос в колонки, а не закон: снаружи от тихой ночи до
 * ближнего взрыва ~110 дБ, у колонок дома ~60. Но перенос был у каждого звука отдельно, а звуки в колонках складываются
 * по мощности: два взрыва разом — +3 дБ в игре, хотя в жизни их уровень вырос на 3 дБ, а в переносе — на 1,5; залп
 * «Града» из 40 разрывов в 30 блоках шёл на 4–5 дБ выше потолка (−8,6 LUFS при потолке −13,5) и упирался в полную шкалу
 * (срезы на 0 dBFS в прогоне RV1, у 3.4.1 — 0–1). Поэтому перенос — у суммы, а внутри суммы — раздел:
 * <ul>
 * <li>взрыв — одна волна: ракурсы одной записи складываются по амплитуде, свои записи (удар в грудь, удар у уха из
 * модели, эхо от рельефа) — по мощности; p_b — мощность в игре против потолка, до раздела;</li>
 * <li>сумма в игре — половина децибел суммы: G = min(1, (Σ p_b²)^½), не громче потолка;</li>
 * <li>доля взрыва b в ней — Q_b = G·p_b² / Σ p², его множитель мощности k_b² = Q_b / p_b = G·p_b / Σ p². Один взрыв не
 * громче потолка звучит как прежде (k = 1); N равных — k² = 1/√N, громче потолка — 1/(N·p); ближний с p = 1 и сто
 * дальних по 0,01 — ближний на 0,04 дБ тише, чем один, каждый дальний на 40 дБ, а все вместе — на 20 дБ ниже его (у
 * общего множителя k² = G/Σp было бы −3 дБ и вровень); ΣQ_b = G.</li>
 * </ul>
 * Так держат громкость в играх с «окном громкости» (HDR-звук: Wwise, Frostbite — Clerwall, GDC 2009): сумма слышимого
 * задаёт верх окна, отношения между звуками внутри сохраняются. Это подача, а не восстановление жизни: p_b — мощность
 * после переноса половиной децибел по наибольшему окну 400 мс ({@link Envelope#of}), и квадрат её — физическая мощность
 * лишь приближённо (в жизни — средний квадрат давления, без окна и без максимума по вариантам); маскировка — только
 * громкостью, без полос и без временной маскировки.
 * <p>
 * Что звучит сейчас — по огибающей самой записи ({@link Envelope}: мгновенная громкость на 400 мс вперёд с каждого тика,
 * с тоном — быстрее или медленнее), так хвост раннего взрыва убавляет поздний ровно настолько, насколько он ещё звучит,
 * а дальние возвращаются, когда ближний стих. Взрыв без голоса движка (уступил место, {@link BlastVoices}) остаётся в
 * смеси с той же мощностью — его играет гул ({@link Rumble}); гул сам в смесь не идёт, иначе сумма зависела бы от числа
 * каналов.
 */
final class Mix {
    /** Средний квадрат мгновенной громкости потолка ({@link Loudness#CEILING_LUFS}). */
    static final double CEILING = Envelope.meanSquare(Loudness.CEILING_LUFS);

    /** Чем звучит слой. */
    enum Kind {
        /** Ракурс общей записи взрыва — голос движка; с другими ракурсами — по амплитуде. */
        VIEW,
        /** Своя запись голосом движка (удар в грудь, звуки бомбы): по мощности. */
        APART,
        /** Своя дорожка своим источником OpenAL (удар у уха, эхо): по мощности; в гул не уходит — не голос движка. */
        OWN
    }

    /**
     * Один слой: огибающая записи для потолка ({@link Envelope#of}, средний квадрат по тикам при громкости 1), энергия той
     * записи, что звучит ({@link Envelope#energy}: по ней гул), тон, громкость, чем звучит и через сколько тиков после
     * начала взрыва начинается (эхо — после прямого удара).
     */
    record Layer(float[] envelope, float[] energy, double pitch, double volume, Kind kind, double delay) {
        Layer(float[] envelope, double pitch, double volume) {
            this(envelope, envelope, pitch, volume, Kind.VIEW, 0);
        }

        /** {@code apart} — своя запись, не ракурс общей: с ракурсами она не согласована и складывается по мощности. */
        Layer(float[] envelope, double pitch, double volume, boolean apart) {
            this(envelope, envelope, pitch, volume, apart ? Kind.APART : Kind.VIEW, 0);
        }

        /** Своя дорожка своим источником ({@link OwnSources}) через {@code delay} тиков после начала взрыва. */
        static Layer own(float[] envelope, float[] energy, double volume, double delay) {
            return new Layer(envelope, energy, 1, volume, Kind.OWN, delay);
        }

        boolean apart() {
            return kind != Kind.VIEW;
        }

        /** Мощность слоя через {@code age} тиков после начала взрыва, против потолка (огибающая — верхняя оценка). */
        double power(double age) {
            return volume * volume * at(envelope, age) / CEILING;
        }

        /** Энергия звучащей записи через {@code age} тиков, против потолка. */
        double energy(double age) {
            return volume * volume * at(energy, age) / CEILING;
        }

        private double at(float[] e, double age) {
            double at = (age - delay) * pitch;
            // за тик до начала — уже первый: голос, который ведёт кадр или поток звука, начинается с долей тика раньше
            // счёта тиков смеси, а громкость его первого кадра — уже по этому слою
            if (at < 0 && at >= -pitch) at = 0;
            if (at < 0 || e.length == 0) return 0;
            int i = (int) Math.floor(at);
            if (i >= e.length) return 0;
            double f = at - i;
            return i + 1 < e.length ? e[i] + (e[i + 1] - e[i]) * f : e[i] * (1 - f);
        }

        /** Тиков от начала взрыва до конца слоя: и огибающей потолка, и энергии (её окно хвоста — после записи). */
        double ticks() {
            return delay + Math.max(envelope.length, energy.length) / Math.max(pitch, 1e-6) + 1;
        }
    }

    /** Взрыв: тик начала и его слои; множитель — у смеси ({@link #factor}). */
    final class Blast {
        double start;
        final List<Layer> layers = new ArrayList<>(5);
        double end;
        /** Мощность до раздела и множитель громкости в тик {@link Mix#now}. */
        private double p, k = 1;
        /** В смеси (не кончился). */
        private boolean live = true;

        Blast(double start) {
            this.start = start;
            this.end = start;
        }

        /** Слой — в смесь; взрыв, у которого прежние слои кончились (эхо готово позже прямого удара), — снова в ней. */
        void add(Layer l) {
            layers.add(l);
            end = Math.max(end, start + l.ticks());
            if (!live) {
                live = true;
                blasts.add(this);
            }
            dirty = true;
        }

        /**
         * Звук на деле начался на {@code ticks} позже тика, в который его попросили ({@link OwnSources.Anchor}): все слои
         * взрыва — от настоящего начала, как их голоса.
         */
        void late(double ticks) {
            if (!(ticks > 0)) return;
            start += ticks;
            end += ticks;
            dirty = true;
        }

        /**
         * Мощность взрыва в тик {@code tick}, против потолка: ракурсы — по амплитуде (одна запись, сведены по амплитуде,
         * {@link BlastMix}; сумма мощностей занизила бы их до 3 дБ), свои записи ({@link Layer#apart}: удар в грудь, удар
         * у уха, эхо) — по мощности (по амплитуде они завышали бы взрыв от 1 psi вдвое, и он звучал бы на 3 дБ ниже потолка).
         */
        double power(double tick) {
            double a = 0, apart = 0;
            for (Layer l : layers) {
                double p = l.power(tick - start);
                if (l.apart()) apart += p;
                else a += Math.sqrt(p);
            }
            return a * a + apart;
        }

        /**
         * Энергия голосов движка этого взрыва в тик {@code tick} (ракурсы и свои записи, без своих источников), против
         * потолка, до множителя — так же сложенная, как {@link #power}, но по энергии звучащих записей: её и берёт гул,
         * когда голосов нет.
         */
        double voiced(double tick) {
            double a = 0, apart = 0;
            for (Layer l : layers) {
                if (l.kind() == Kind.OWN) continue;
                double e = l.energy(tick - start);
                if (l.kind() == Kind.APART) apart += e;
                else a += Math.sqrt(e);
            }
            return a * a + apart;
        }

        /** Множитель громкости звуков этого взрыва сейчас (0..1): k_b = (G·p_b / Σ p²)^½. */
        double factor() {
            update();
            return k;
        }

        /** Доля взрыва в слышимом сейчас, против потолка: Q_b = k_b²·p_b. */
        double heard() {
            update();
            return k * k * p;
        }

        boolean ended(double tick) {
            return end < tick;
        }
    }

    private final List<Blast> blasts = new ArrayList<>();
    /** Тик, на который посчитаны множители. */
    private double now = Double.NaN;
    private boolean dirty = true;

    /** Взрыв, который зазвучит в тик {@code tick}: слои — {@link Blast#add}. */
    Blast blast(double tick) {
        at(tick);
        Blast b = new Blast(tick);
        blasts.add(b);
        dirty = true;
        return b;
    }

    /** Тик смеси: кончившиеся взрывы — прочь, множители — на этот тик. */
    void at(double tick) {
        if (tick != now) {
            now = tick;
            dirty = true;
        }
        blasts.removeIf(b -> {
            if (!b.ended(tick)) return false;
            b.live = false;
            return true;
        });
    }

    private void update() {
        if (!dirty) return;
        dirty = false;
        double sq = 0;
        for (Blast b : blasts) {
            b.p = b.power(now);
            sq += b.p * b.p;
        }
        double g = Math.min(1, Math.sqrt(sq));
        // тишина — множитель 1: звук, которого огибающая ещё не знает, не глушится
        for (Blast b : blasts) b.k = sq > 0 ? Math.sqrt(Math.min(1, g * b.p / sq)) : 1;
    }

    /** Мощность смеси в игре в тик {@code tick}, против потолка: Σ Q_b = G (сверка в тестах). */
    double heard(double tick) {
        at(tick);
        return heard();
    }

    /** Мощность смеси в игре сейчас, против потолка. */
    double heard() {
        update();
        double sum = 0;
        for (Blast b : blasts) sum += b.k * b.k * b.p;
        return sum;
    }

    void reset() {
        blasts.forEach(b -> b.live = false);
        blasts.clear();
        dirty = true;
    }

    int size() {
        return blasts.size();
    }
}
