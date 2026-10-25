package ua.zentix.incoming.client.sound;

import net.minecraft.util.RandomSource;

import java.util.Arrays;

/**
 * Сумма некогерентных источников одного звука по {@link #N} петлям вокруг уха (без Minecraft, проверяется юнит-тестом):
 * мощности складываются, направление — cos²/sin² между двумя соседними петлями (сумма весов 1, как у {@link Rumble} и
 * {@link FireRoar}), верха — средние по мощности. Так звучат пожары в воронках ({@link CraterFire}): их в налёте сотни, а
 * каналов у движка — 247 на всё; по петле на пожар их не хватало (налёт из 300 шахедов: ~150 каналов пожаров и отказы
 * движка каждые полсекунды, пока те петли пытались встать заново).
 * <ul>
 * <li>Мощности петель складываются в ухе, только если их звуки некогерентны при любой разнице стартов: у каждой четверти
 * свой вариант {@code blast.fire} — свой кусок длинной записи пожара ({@code fire()} в {@code build_sounds.py}), а не сдвиг
 * одной записи. Одна запись в двух петлях, начатых в один тик, складывалась по амплитуде: пожар между ними был на 2 дБ
 * громче (ревью R8).</li>
 * <li>Слышно ли — решает сумма ({@link #heard}), а не каждая петля: пожар, слышный сам ({@link CraterFire}), между двумя
 * петлями делится пополам, и порог на каждой убрал бы его совсем — смотря по азимуту (ревью R9). Играют все петли с долей
 * слышной суммы ({@link #plays}). Слышимость самого пожара — у уха, в калиброванной мере, не в громкости файла (ревью
 * R11).</li>
 * </ul>
 */
final class Quarters {
    static final int N = 4;

    private final double[] power = new double[N], highs = new double[N];

    void clear() {
        Arrays.fill(power, 0);
        Arrays.fill(highs, 0);
    }

    /**
     * Источник с азимута {@code azimuth} от уха (atan2(dz, dx)) громкостью {@code volume} (как у звука Minecraft, до
     * фильтра), что проходит преграду долей громкости {@code gain} и верхов {@code highs}.
     */
    void add(double azimuth, double volume, double gain, double highs) {
        double p = volume * volume * gain * gain;
        if (!(p > 0)) return;
        double u = (azimuth < 0 ? azimuth + 2 * Math.PI : azimuth) / (2 * Math.PI / N);
        int q0 = (int) Math.floor(u) % N, q1 = (q0 + 1) % N;
        double frac = u - Math.floor(u), c = Math.cos(frac * Math.PI / 2), s = Math.sin(frac * Math.PI / 2);
        put(q0, p * c * c, highs);
        put(q1, p * s * s, highs);
    }

    private void put(int q, double p, double h) {
        power[q] += p;
        highs[q] += p * h;
    }

    /**
     * Громкость петли {@code q} (0..1): корень суммы мощностей. Петли пожара собраны на потолке игры ({@code FIRE_LUFS} в
     * {@code build_sounds.py}; громче файла Minecraft не играет): мощность 1 — потолок. Потолок — у суммы всех четвертей,
     * как у смеси взрывов: громче него — все четверти тише поровну, направления те же; потолок у каждой петли отдельно
     * давал бы полю пожаров посередине между двумя петлями на 3 дБ больше, чем на оси одной.
     */
    float volume(int q) {
        double sum = 0;
        for (double p : power) sum += p;
        return (float) Math.sqrt(power[q] / Math.max(1, sum));
    }

    /** Доля верхов петли {@code q}: средняя по мощности. */
    float highs(int q) {
        return power[q] > 0 ? (float) (highs[q] / power[q]) : 1;
    }

    /**
     * Слышна ли сумма: в ней есть слышимый источник. Слышно ли каждый — решено у уха, в калиброванной мере, до суммы
     * ({@link CraterFire#volume}); порога в громкости файла здесь нет: она меняется с пересведением файла, и с ним менялась
     * бы дальность слышимости (ревью R11).
     */
    boolean heard() {
        double sum = 0;
        for (double p : power) sum += p;
        return sum > 0;
    }

    /** Играть ли петлю {@code q}: сумма слышна и в этой четверти есть её доля, пусть и тише порога. */
    boolean plays(int q) {
        return power[q] > 0 && heard();
    }

    /**
     * Зерно звука, с которым петля четверти {@code q} берёт вариант {@code q} записи из {@code variants} равных по весу
     * (Minecraft выбирает вариант первым {@code nextInt} по весам у {@code RandomSource} звука): у четвертей — разные
     * варианты, пока их хватает. Зёрна — вразброс по всему {@code long}: у {@code LegacyRandomSource} первое число малых
     * зёрен почти одно, и среди зёрен 0–4095 {@code nextInt(4)} не даёт ни 0, ни 1 — три петли из четырёх играли один файл.
     */
    static long seed(int variants, int q) {
        int n = Math.max(1, variants);
        for (long i = 0; i < 4096; i++) {
            long seed = i * 0x9E3779B97F4A7C15L;
            if (RandomSource.create(seed).nextInt(n) == q % n) return seed;
        }
        throw new IllegalStateException("no seed picks variant " + q % n + " of " + n);
    }

    /** Направление петли {@code q} от уха: азимут atan2(dz, dx). */
    static double azimuth(int q) {
        return 2 * Math.PI * q / N;
    }
}
