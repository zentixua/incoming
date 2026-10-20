package ua.zentix.incoming.client.sound;

/**
 * Удар ударной волны у уха — дорожка из модели, без Minecraft (проверяется юнит-тестом), у любого взрыва: вдали от
 * ядерного подрыва (меньше 1 psi) и у обычного заряда ({@link BlastMix}: давление и фаза — Кинни–Грэхем) волна —
 * ударная: один скачок давления и плавный спад положительной фазы за {@code t+} к нулю,
 * потом разрежение (импульс Фридлендера, сбалансированный: Garcés 2019, гл. 8). Хвостового скачка, как у N-волны, нет:
 * задний фронт отстаёт и становится скачком лишь за сотню километров. Отражение от земли у уха на 1,6 м приходит
 * через миллисекунды и сливается с прямым (за вершиной Маха — одна волна): второго хлопка нет.
 * <p>
 * Скачок не размывает воздух, как обычный звук (ISO 9613-1 съел бы его верха за километр): крутизну держит сама
 * волна, и время нарастания тем больше, чем слабее скачок, — в среднем обратно скачку (Pierce 1989). Число —
 * по замерам звуковых ударов: 30–200 Па нарастают за 1–5 мс (с турбулентностью; теория релаксации даёт в 2–3 раза
 * меньше, Gionfriddo 1991), то есть ~0,17 Па·с: 0,01 psi — 2,5 мс (глухой удар), 0,1 psi — 0,25 мс (хлёсткий). Что
 * съели холм и зона тени — доля верхов на 2 кГц ({@link Outdoor#highs}) — однополюсный срез с той же долей на 2 кГц.
 * Ниже 20 Гц слух не слышит: положительная фаза в секунду — инфразвук, его толчок ощущают, а не слышат, — он снят
 * фильтром Баттерворта второго порядка, чтобы дорожка не тратила запас громкости на неслышимое. У ядерной волны слышен
 * один фронт — щелчок в десятки миллисекунд (через 50 мс после пика остаётся меньше 2 % при любой фазе); у обычного
 * взрыва фаза — миллисекунды (у 3 кг в 50 м — 7 мс, у 450 кг в 1 км — 39 мс), и слышна вся волна со сжатием и
 * разрежением. И то и другое — в первых {@link #TAIL} с после фронта: дальше в дорожке только срезанный инфразвук.
 */
public final class BlastPulse {
    /** Частота дорожки, Гц: фронт в сотые доли миллисекунды. */
    public static final int RATE = 44_100;
    /** Время нарастания (10–90 %) × скачок давления, с·Па. */
    static final double RISE_PA_S = 0.17;
    /** Ниже этого слух не слышит, Гц. */
    static final double HEARING_HZ = 20;
    /** Полоса, по которой {@link Outdoor} считает верха, Гц. */
    static final double HIGHS_HZ = 2000;
    /** Дорожка после фронта, с. */
    static final double TAIL = 0.25;
    /** Пик дорожки: громкость возвращает усиление источника. */
    static final double PEAK = 0.98;

    private BlastPulse() {}

    /** Давление волны (доля пика) через {@code t} с после фронта: (1 − t/t+)·e^(−t/t+), импульс за всё время — ноль. */
    public static double friedlander(double t, double tPlus) {
        double x = Math.max(0, t) / tPlus;
        return (1 - x) * Math.exp(-x);
    }

    /** Время нарастания фронта (10–90 %) со скачком {@code pa} паскалей, с. */
    public static double riseSeconds(double pa) {
        return RISE_PA_S / Math.max(pa, 1e-9);
    }

    /**
     * Дорожка удара: моно, {@link #RATE}, пик {@link #PEAK}, фронт — в первые миллисекунды.
     *
     * @param pa    скачок давления у уха, Па
     * @param tPlus положительная фаза, с
     * @param highs доля верхов на 2 кГц, что дошла ({@link Outdoor#highs}; 1 — путь открыт)
     */
    public static short[] render(double pa, double tPlus, double highs) {
        double rise = riseSeconds(pa);
        // фронт Тейлора ½(1 + th(t/θ)): от 10 до 90 % — 2·arth(0,8)·θ
        double theta = Math.max(0.5 / RATE, rise / (2 * 1.0986));
        double t0 = 4 * theta + 0.002;
        int n = (int) Math.ceil(RATE * (t0 + TAIL));
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE - t0;
            x[i] = 0.5 * (1 + Math.tanh(t / theta)) * friedlander(t, tPlus);
        }
        if (highs < 1) {
            double h = Math.max(1e-3, highs);
            lowpass(x, HIGHS_HZ * h / Math.sqrt(1 - h * h));
        }
        highpass(x, HEARING_HZ);
        // последняя четверть — к тишине: дорожка кончается без щелчка
        int fade = n / 4;
        for (int i = n - fade; i < n; i++) x[i] *= 0.5 * (1 + Math.cos(Math.PI * (i - (n - fade)) / fade));
        double peak = 0;
        for (double v : x) peak = Math.max(peak, Math.abs(v));
        short[] pcm = new short[n];
        if (peak <= 0) return pcm;
        for (int i = 0; i < n; i++) pcm[i] = (short) Math.round(x[i] / peak * PEAK * Short.MAX_VALUE);
        return pcm;
    }

    /** Однополюсный срез: на частоте {@code fc} −3 дБ. */
    static void lowpass(double[] x, double fc) {
        double a = 1 - Math.exp(-2 * Math.PI * fc / RATE), y = 0;
        for (int i = 0; i < x.length; i++) {
            y += a * (x[i] - y);
            x[i] = y;
        }
    }

    /** Баттерворт второго порядка, верхних частот (билинейное преобразование). */
    static void highpass(double[] x, double fc) {
        double k = Math.tan(Math.PI * fc / RATE), q = Math.sqrt(0.5), norm = 1 / (1 + k / q + k * k);
        double b0 = norm, b1 = -2 * norm, b2 = norm, a1 = 2 * (k * k - 1) * norm, a2 = (1 - k / q + k * k) * norm;
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double in = x[i], out = b0 * in + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = in;
            y2 = y1;
            y1 = out;
            x[i] = out;
        }
    }
}
