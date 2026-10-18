package ua.zentix.incoming.nuclear.model;

/**
 * Пресеты мощности (килотонны) с ключом перевода и оптимальная высота воздушного подрыва. Мощнее 15 кт в моде нет
 * ({@code Loadout.Nuke.MAX_YIELD}): тяжёлая зона (от 5 psi у земли) растёт как Y^⅔, у 15 кт на 592 м она до 1,6 км —
 * около 32 тыс. чанков.
 */
public enum Yield {
    TACTICAL(1, "tactical"),
    HIROSHIMA(15, "hiroshima");

    private final double kt;
    private final String key;

    Yield(double kt, String name) {
        this.kt = kt;
        this.key = "incoming.yield." + name;
    }

    public double kt() {
        return kt;
    }

    /** Ключ перевода, например {@code incoming.yield.hiroshima}. */
    public String key() {
        return key;
    }

    /** Высота подрыва с наибольшей площадью 5 psi, м: 240·Y^(1/3) (15 кт → ~590 м, 1 Мт → 2.4 км). */
    public static double optimalBurstHeight(double yieldKt) {
        return 240 * Math.cbrt(yieldKt);
    }
}
