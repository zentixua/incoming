package ua.zentix.incoming.nuclear.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Пыль в ножке гриба — та, что подсос у эпицентра забрал из слоя пыли волны ({@link DustModel}: доля
 * {@link DustModel.Column#stem}, с {@link DustModel.Column#stemFrom}, e-кратно за {@link DustModel#STEM_S} с). Ножку
 * питает только пыль, поднятая с земли ветром за фронтом и нагретым слоем (NRC 1985, «The Effects on the Atmosphere of
 * a Major Nuclear Exchange», гл. 4, с. 22: «sweep-up and thermal dust»); нисходящий поток шара у оси первые секунды
 * подсос глушит (Rosenblatt 1981, рис. 13e) — отсюда начало подсоса не раньше {@code stemFrom}. Сколько пыли ушло из
 * слоя, столько вошло в ножку: одна масса на оба места, без своей кривой у ножки.
 * <p>
 * В ножке пыль идёт вверх с её воздухом ({@link MushroomModel#stemRise}): выброс, вошедший в t', в момент t — на высоте
 * stemRise(t) − stemRise(t'); выше низа шапки — уже в шапке. Поэтому у воздушного подрыва, где подсос начинается
 * позже, а облако уходит быстро, пыльная ножка может не догнать шапку (NRC, с. 22: у 10 кт на 450 м «dusty stems that
 * did not merge with the ring vortex»), а у наземного шар касается земли и пыль уходит вверх за ним сразу. Толща
 * ножки — ослабление пыли в ней на метр высоты, Λ = Σk·m (м²/м), поперёк круга радиуса r через середину — 2Λ/(πr).
 * Шар, коснувшийся земли (наземный и низкий подрыв), — свой след: пыль из-под него идёт по всей ножке до шапки сразу
 * (поток в следе шара не измерен — ⚠ оценка), и Λ — вся вошедшая пыль на высоту до низа шапки.
 * Оседание крупной пыли в ножке не считается: её несёт поток вверх.
 * <p>
 * Таблица — шаг {@link #STEP} с до {@link #SPAN} стабилизаций облака; кольца подсоса — {@link #RINGS} до края подсоса
 * ({@code STEM_M}·∛(Y/15)). Одна на подрыв (кэш по мощности, высоте, виду подрыва и видимости).
 */
public final class StemDust {
    /** Шаг таблицы, с; колец подсоса; на сколько стабилизаций облака таблица. */
    static final double STEP = 1;
    static final int RINGS = 24;
    static final double SPAN = 1.5;
    /** Видимость по умолчанию (ясно), м — для проверок без подрыва. */
    public static final double CLEAR = 20_000;

    private static final Map<String, StemDust> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, StemDust> e) {
            return size() > 8;
        }
    };

    private final double yieldKt, hob;
    private final boolean surface;
    /** Ослабление пыли в ножке на метр высоты (м²/м) и верх пыли в ножке над землёй (м), по шагам. */
    private final double[] lambda, top;

    private StemDust(double yieldKt, double hobM, boolean surface, double visibilityM) {
        this.yieldKt = yieldKt;
        this.hob = Math.max(0, hobM);
        this.surface = surface;
        double ys = Math.cbrt(yieldKt / 15), reach = DustModel.STEM_M * ys;
        DustModel.Column[] col = new DustModel.Column[RINGS];
        double[] area = new double[RINGS], arrival = new double[RINGS];
        for (int i = 0; i < RINGS; i++) {
            double r0 = reach * i / RINGS, r1 = reach * (i + 1) / RINGS, r = 0.5 * (r0 + r1);
            col[i] = DustModel.column(r, hob, yieldKt, surface, visibilityM, DustModel.Surface.REFERENCE);
            area[i] = Math.PI * (r1 * r1 - r0 * r0);
            arrival[i] = HobBlast.arrivalSeconds(r, hob, yieldKt);
        }
        int n = (int) Math.ceil(SPAN * CloudModel.stabilizationSeconds(yieldKt) / STEP) + 1;
        boolean wake = hob <= FireballModel.maxRadius(yieldKt, surface);
        double[] inflow = new double[n], rise = new double[n];
        lambda = new double[n];
        top = new double[n];
        for (int k = 0; k < n; k++) {
            double t = k * STEP, flux = 0;
            for (int i = 0; i < RINGS; i++) {
                DustModel.Column c = col[i];
                double age = t - arrival[i];
                // подсос берёт из слоя долю stem/STEM_S его толщи в секунду — тот же член, что убывает в DustModel.Column.left
                if (age > c.stemFrom()) flux += area[i] * c.stem() / DustModel.STEM_S * c.depth(age);
            }
            inflow[k] = flux * STEP;
            rise[k] = MushroomModel.stemRise(t, hob, yieldKt, surface);
        }
        for (int k = 0; k < n; k++) {
            double t = k * STEP, capBot = Math.max(hob, CloudModel.capBottom(t, hob, yieldKt, surface)), held = 0, oldest = 0;
            for (int j = 0; j <= k; j++) {
                if (inflow[j] <= 0) continue;
                double z = rise[k] - rise[j];
                if (z >= capBot) continue;
                held += inflow[j];
                oldest = Math.max(oldest, z);
            }
            if (wake) {
                // шар на земле: ножка — его след, пыль из-под него идёт по всей ножке до шапки
                top[k] = capBot;
                lambda[k] = held / Math.max(capBot, 1);
            } else {
                top[k] = oldest;
                lambda[k] = held / Math.max(oldest, 1);
            }
        }
    }

    /** Пыль ножки подрыва (из кэша; нет — считается сейчас). */
    public static StemDust of(double yieldKt, double hobM, boolean surface, double visibilityM) {
        String key = key(yieldKt, hobM, surface, visibilityM);
        synchronized (CACHE) {
            StemDust d = CACHE.get(key);
            if (d != null) return d;
        }
        StemDust d = new StemDust(yieldKt, hobM, surface, visibilityM);
        synchronized (CACHE) {
            CACHE.put(key, d);
        }
        return d;
    }

    /** Ключи, что уже считаются в фоне. */
    private static final java.util.Set<String> PENDING = new java.util.HashSet<>();

    /**
     * Пыль ножки, если таблица готова; нет — начинает считать её в фоне (десятки мс — не в кадре) и отдаёт null: ножка
     * первые кадры подрыва без пыли (подсос всё равно начинается через секунды).
     */
    @org.jetbrains.annotations.Nullable
    public static StemDust ready(double yieldKt, double hobM, boolean surface, double visibilityM) {
        String key = key(yieldKt, hobM, surface, visibilityM);
        synchronized (CACHE) {
            StemDust d = CACHE.get(key);
            if (d != null || !PENDING.add(key)) return d;
        }
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                of(yieldKt, hobM, surface, visibilityM);
            } finally {
                synchronized (CACHE) {
                    PENDING.remove(key);
                }
            }
        });
        return null;
    }

    private static String key(double yieldKt, double hobM, boolean surface, double visibilityM) {
        return yieldKt + "/" + Math.max(0, hobM) + "/" + surface + "/" + visibilityM;
    }

    private double at(double[] a, double t) {
        double s = Math.max(0, t / STEP);
        int i = (int) Math.min(a.length - 2, Math.floor(s));
        if (i < 0) return a[0];
        double f = Math.min(1, s - i);
        return a[i] + (a[i + 1] - a[i]) * f;
    }

    /** Ослабление пыли в ножке на метр высоты в момент t, м²/м (за таблицей — последнее). */
    public double lambda(double t) {
        return at(lambda, t);
    }

    /** Докуда поднялась пыль в ножке в момент t, м над землёй (не выше низа шапки). */
    public double top(double t) {
        return at(top, t);
    }

    /** Толща ножки поперёк через середину в момент t при радиусе r, м: 2Λ/(πr). */
    public double tau(double t, double r) {
        return 2 * lambda(t) / (Math.PI * Math.max(r, 1));
    }

    double yieldKt() {
        return yieldKt;
    }

    double hob() {
        return hob;
    }

    boolean surface() {
        return surface;
    }
}
