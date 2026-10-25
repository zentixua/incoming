package ua.zentix.incoming.client.sound;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Гул взрывов без голоса движка (без Minecraft, проверяется юнит-тестом): что уступило место ({@link BlastVoices}) или не
 * получило его, звучит не своим разовым звуком, а суммой в {@link #QUADRANTS} петлях вокруг уха — как гул пожаров
 * ({@link FireRoar}) и разрушения ядерки ({@link Clatter}). Взрывы разных мест некогерентны, поэтому мощности
 * складываются; петля — шум со спектром хвостов дальнего ракурса ({@code blast.rumble}, {@code build_sounds.py rumble}),
 * громкость — корень суммы членов в каждом тике звукового движка:
 * <ul>
 * <li>член — взрыв в смеси ({@link Mix}): его доля в слышимом k_b² × энергия его голосов по профилю звучащих записей
 * ({@link Mix.Blast#voiced}, {@link Envelope#energy}: мгновенная громкость той записи, что выбрало зерно, с её настоящим
 * сроком, без заглядывания вперёд и без максимума по вариантам — огибающая потолка подняла бы гул до удара члена и дала
 * бы ему хвост самого долгого варианта); в смесь гул не идёт снова — мощность взрыва там уже есть;</li>
 * <li>голоса, что ещё звучат и стихают, свою долю несут сами: член — всё, что взрыв дал бы голосами, минус f² × то, что
 * играют они (f — их громкость, {@link #voice}); слой, которого движок не взял, — в гуле сразу и весь;</li>
 * <li>направление — cos²/sin² между двумя соседними петлями (сумма весов 1), петли — на востоке, юге, западе и севере;</li>
 * <li>тон петли — средний по мощности в ней тон её членов (Сакс: у «Града» запись быстрее, у ракеты медленнее), верха —
 * средние так же верха их пути: голос, который ещё звучит, тембр гула не трогает; гула ещё нет (петля встаёт заранее) —
 * тембр тех, кто в него придёт.</li>
 * </ul>
 * Часы стихания — тики звукового движка: в них же движок берёт громкость голоса ({@code SoundEngine.tickNonPaused}), и
 * петля считает свою громкость в своём тике, а не в тике клиента после него (тогда гул отставал бы от голоса на тик и
 * переход терял бы до 3,5 дБ).
 * <p>
 * Приближение: тембр всех членов — один спектр хвостов раската; атаки в гуле не слышны (огибающая — окна 400 мс), редкие
 * члены гул оставляют неровным — ровна только петля.
 */
final class Rumble {
    static final int QUADRANTS = 4;
    /** Петля без членов столько тиков — отпустить канал. */
    static final int HOLD = 20;

    /**
     * Взрыв в гуле: откуда, тон, доля мощности за преградой у уха и верха его голосов; {@code voiced} — какие из его
     * голосов движка (по порядку слоёв, {@link Mix.Blast#voiced}) ещё звучат и стихают с тика {@code from} за
     * {@code fade} тиков (null — голосов нет: весь взрыв в гуле).
     */
    static final class Member {
        final Mix.Blast blast;
        final double azimuth;
        final float pitch, gain, highs;
        final boolean @Nullable [] voiced;
        final double from;
        final int fade;

        Member(Mix.Blast blast, double azimuth, float pitch, float gain, float highs, boolean @Nullable [] voiced, double from, int fade) {
            this.blast = blast;
            this.azimuth = azimuth;
            this.pitch = pitch;
            this.gain = gain;
            this.highs = highs;
            this.voiced = voiced;
            this.from = from;
            this.fade = fade;
        }

        /**
         * Мощность члена в гуле в тик движка {@code sound} при смеси на её тике, против потолка: всё, что взрыв дал бы
         * голосами, минус f² × что ещё играют его стихающие голоса.
         */
        double power(double sound) {
            double k = blast.factor(), all = blast.voiced(blast.now(), null);
            if (voiced != null) {
                double f = voice(sound, from, fade);
                all -= f * f * blast.voiced(blast.now(), voiced);
            }
            return gain * k * k * Math.max(0, all);
        }

        /** Мощность, когда голоса стихнут совсем: по ней петля встаёт заранее и уходит. */
        double potential() {
            double k = blast.factor();
            return gain * k * k * blast.voiced(blast.now(), null);
        }
    }

    /**
     * Громкость (амплитуда) голоса, уступившего в тик движка {@code from}, в тик {@code now}: первый тик после уступки — ещё
     * полная (петля, которой ещё нет, встаёт в следующем тике клиента), потом к нулю за {@code fade} тиков.
     */
    static double voice(double now, double from, int fade) {
        if (fade <= 0) return 0;
        return Math.max(0, Math.min(1, 1 - (now - from - 1) / fade));
    }

    private final List<Member> members = new ArrayList<>();
    private final double[] power = new double[QUADRANTS], potential = new double[QUADRANTS], pitch = new double[QUADRANTS],
            highs = new double[QUADRANTS], soonPitch = new double[QUADRANTS], soonHighs = new double[QUADRANTS];
    private final int[] quiet = new int[QUADRANTS];

    /** Взрыв {@code blast} (в смеси) — в гул целиком: с азимута {@code azimuth} от уха (atan2(dz, dx)). */
    Member add(Mix.Blast blast, double azimuth, float pitch, float gain, float highs, double now, int fade) {
        return add(blast, azimuth, pitch, gain, highs, null, now, fade);
    }

    /** Взрыв — в гул, его голоса {@code voiced} стихают с тика движка {@code now} за {@code fade} тиков. */
    Member add(Mix.Blast blast, double azimuth, float pitch, float gain, float highs, boolean @Nullable [] voiced, double now, int fade) {
        Member m = new Member(blast, azimuth, pitch, gain, highs, voiced, now, fade);
        members.add(m);
        return m;
    }

    /**
     * Тик движка {@code sound} (смесь — на своём тике, {@link Mix#at}): кончившиеся — прочь, мощность, тон и верха петель.
     */
    void tick(double sound) {
        members.removeIf(m -> m.blast.ended(m.blast.now()));
        Arrays.fill(power, 0);
        Arrays.fill(potential, 0);
        Arrays.fill(pitch, 0);
        Arrays.fill(highs, 0);
        Arrays.fill(soonPitch, 0);
        Arrays.fill(soonHighs, 0);
        double sector = 2 * Math.PI / QUADRANTS;
        for (Member m : members) {
            double e = m.power(sound), full = m.potential();
            if (!(full > 0)) continue;
            double u = (m.azimuth < 0 ? m.azimuth + 2 * Math.PI : m.azimuth) / sector;
            int q0 = (int) Math.floor(u) % QUADRANTS, q1 = (q0 + 1) % QUADRANTS;
            double frac = u - Math.floor(u), c = Math.cos(frac * Math.PI / 2), s = Math.sin(frac * Math.PI / 2);
            put(q0, e * c * c, full * c * c, m);
            put(q1, e * s * s, full * s * s, m);
        }
        // тембр звучащего гула — по тому, что в нём звучит; голос, который только уступил и ещё полный, его не трогает.
        // Гула ещё нет, а члены есть (петля встаёт заранее) — тембр тех, кто в него придёт
        for (int q = 0; q < QUADRANTS; q++) {
            if (power[q] > 0) {
                pitch[q] /= power[q];
                highs[q] /= power[q];
            } else if (potential[q] > 0) {
                pitch[q] = soonPitch[q] / potential[q];
                highs[q] = soonHighs[q] / potential[q];
            } else {
                pitch[q] = 1;
                highs[q] = 1;
            }
        }
    }

    private void put(int q, double e, double full, Member m) {
        power[q] += e;
        potential[q] += full;
        pitch[q] += e * m.pitch;
        highs[q] += e * m.highs;
        soonPitch[q] += full * m.pitch;
        soonHighs[q] += full * m.highs;
    }

    /** Мощность петли {@code q} в игре после {@link #tick}, против потолка (как {@link Mix.Blast#heard}). */
    double power(int q) {
        return power[q];
    }

    /** Мощность петли {@code q}, когда стихнут голоса её членов. */
    double potential(int q) {
        return potential[q];
    }

    /**
     * Играть ли петлю {@code q}: в гуле есть что играть — громкость всей суммы {@code total} (по {@link #potential} всех
     * четвертей, {@link #potentialTotal}) больше нуля — и в четверти есть его доля. Члены гула — взрывы, слышные у уха
     * (это решает их звук, {@link BlastSounds}); порога в громкости файла петли здесь нет: она меняется с пересведением
     * файла (ревью R11). Порог у каждой петли отдельно убрал бы член между двумя петлями — смотря по азимуту (ревью R9).
     */
    boolean plays(int q, float total) {
        return potential[q] > 0 && total > 0;
    }

    /**
     * Держать ли петлю {@code q} (раз в тик клиента): она играет ({@link #plays}: гул слышен целиком, когда стихнут голоса,
     * и в четверти есть его доля) — или тишина ещё не дольше {@link #HOLD} тиков.
     */
    boolean keep(int q, float total) {
        quiet[q] = plays(q, total) ? 0 : quiet[q] + 1;
        return quiet[q] <= HOLD;
    }

    /** Направление петли {@code q} от уха: азимут atan2(dz, dx). */
    static double azimuth(int q) {
        return 2 * Math.PI * q / QUADRANTS;
    }

    float pitch(int q) {
        return (float) Math.max(BlastMix.PITCH_MIN, Math.min(BlastMix.PITCH_MAX, pitch[q]));
    }

    float highs(int q) {
        return (float) highs[q];
    }

    /** Громкость петли (0..1) у файла громкостью {@code fileLufs}, чтобы она звучала мощностью {@code power} против потолка. */
    static float volume(double power, double fileLufs) {
        return (float) Math.min(1, Math.sqrt(Math.max(0, power) * Mix.CEILING / Envelope.meanSquare(fileLufs)));
    }

    /** Мощность всего гула, когда стихнут голоса его членов. */
    double potentialTotal() {
        double s = 0;
        for (double p : potential) s += p;
        return s;
    }

    /** Мощность всего гула, против потолка. */
    double total() {
        double s = 0;
        for (double p : power) s += p;
        return s;
    }

    int size() {
        return members.size();
    }

    void clear() {
        members.clear();
        Arrays.fill(power, 0);
        Arrays.fill(potential, 0);
        Arrays.fill(quiet, 0);
    }
}
