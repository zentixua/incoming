package ua.zentix.incoming.client.sound;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Гул взрывов без голоса движка (без Minecraft, проверяется юнит-тестом): что уступило место ({@link BlastVoices}) или не
 * получило его, звучит не своим разовым звуком, а суммой в {@link #QUADRANTS} петлях вокруг уха — как гул пожаров
 * ({@link FireRoar}) и разрушения ядерки ({@link Clatter}). Взрывы разных мест некогерентны, поэтому мощности
 * складываются; петля — шум со спектром хвостов дальнего ракурса ({@code blast.rumble}, {@code build_sounds.py rumble}),
 * громкость — каждый тик корень суммы членов:
 * <ul>
 * <li>член — взрыв в смеси ({@link Mix}): его доля в слышимом k_b² × энергия его голосов по профилю звучащих записей
 * ({@link Mix.Blast#voiced}, {@link Envelope#energy}: мгновенная громкость той записи, что выбрало зерно, с её настоящим
 * сроком, без заглядывания вперёд и без максимума по вариантам — огибающая потолка подняла бы гул до удара члена и дала
 * бы ему хвост самого долгого варианта); в смесь гул не идёт снова — мощность взрыва там уже есть;</li>
 * <li>направление — cos²/sin² между двумя соседними петлями (сумма весов 1), петли — на востоке, юге, западе и севере;</li>
 * <li>голос, который уступил, стихает за {@link BlastVoices#FADE} тика, а его член растёт так же: мощность голоса f²,
 * гула — 1 − f², сумма — та же;</li>
 * <li>тон петли — средний по мощности тон её членов (Сакс: у «Града» запись быстрее, у ракеты медленнее), верха — средние
 * по мощности верха их пути.</li>
 * </ul>
 * Приближение: тембр всех членов — один спектр хвостов раската; атаки в гуле не слышны (огибающая — окна 400 мс), редкие
 * члены гул оставляют неровным — ровна только петля.
 */
final class Rumble {
    static final int QUADRANTS = 4;

    /**
     * Взрыв в гуле: откуда, тон, доля мощности за преградой у уха и верха его голосов, и с какого тика он в гуле (за
     * {@code fade} тиков — весь).
     */
    static final class Member {
        final Mix.Blast blast;
        final double azimuth;
        final float pitch, gain, highs;
        double from;
        int fade;

        Member(Mix.Blast blast, double azimuth, float pitch, float gain, float highs, double from, int fade) {
            this.blast = blast;
            this.azimuth = azimuth;
            this.pitch = pitch;
            this.gain = gain;
            this.highs = highs;
            this.from = from;
            this.fade = fade;
        }

        /** Доля мощности взрыва в гуле в тик {@code now}: 1 − f², f — громкость стихающего голоса. */
        double weight(double now) {
            double f = voice(now, from, fade);
            return 1 - f * f;
        }

        /** Голос замолчал сейчас: вся мощность — в гуле. */
        void settle(double now) {
            from = now;
            fade = 0;
        }
    }

    /** Громкость (амплитуда) голоса, стихающего с тика {@code from} за {@code fade} тиков, в тик {@code now}. */
    static double voice(double now, double from, int fade) {
        if (fade <= 0) return 0;
        return Math.max(0, Math.min(1, 1 - (now - from) / fade));
    }

    private final List<Member> members = new ArrayList<>();
    private final double[] power = new double[QUADRANTS], pitch = new double[QUADRANTS], highs = new double[QUADRANTS];

    /** Взрыв {@code blast} (в смеси) — в гул: с азимута {@code azimuth} от уха (atan2(dz, dx)), с тика {@code now}. */
    Member add(Mix.Blast blast, double azimuth, float pitch, float gain, float highs, double now, int fade) {
        Member m = new Member(blast, azimuth, pitch, gain, highs, now, fade);
        members.add(m);
        return m;
    }

    /** Тик {@code now} (после тика смеси, {@link Mix#at}): кончившиеся — прочь, мощность, тон и верха петель. */
    void tick(double now) {
        members.removeIf(m -> m.blast.ended(now));
        Arrays.fill(power, 0);
        Arrays.fill(pitch, 0);
        Arrays.fill(highs, 0);
        double sector = 2 * Math.PI / QUADRANTS;
        for (Member m : members) {
            double k = m.blast.factor(), e = m.weight(now) * m.gain * k * k * m.blast.voiced(now);
            if (!(e > 0)) continue;
            double u = (m.azimuth < 0 ? m.azimuth + 2 * Math.PI : m.azimuth) / sector;
            int q0 = (int) Math.floor(u) % QUADRANTS, q1 = (q0 + 1) % QUADRANTS;
            double frac = u - Math.floor(u), c = Math.cos(frac * Math.PI / 2), s = Math.sin(frac * Math.PI / 2);
            put(q0, e * c * c, m);
            put(q1, e * s * s, m);
        }
        for (int q = 0; q < QUADRANTS; q++) {
            if (power[q] > 0) {
                pitch[q] /= power[q];
                highs[q] /= power[q];
            } else {
                pitch[q] = 1;
                highs[q] = 1;
            }
        }
    }

    private void put(int q, double e, Member m) {
        power[q] += e;
        pitch[q] += e * m.pitch;
        highs[q] += e * m.highs;
    }

    /** Мощность петли {@code q} в игре после {@link #tick}, против потолка (как {@link Mix.Blast#heard}). */
    double power(int q) {
        return power[q];
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
    }
}
