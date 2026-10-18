package ua.zentix.incoming.util;

/**
 * Дальность видимости по погоде, метры (= блоки), одна на всё: дымка картинки вдали ({@code client.far.Sight}) и свет
 * ядерной вспышки сквозь воздух ({@code nuclear.model.ThermalModel}). Ясно — 20 км. Дождь Minecraft — умеренный
 * (3–8 мм/ч): 5 км; гроза — ливень, 1 км (ITU-R P.1814, табл. 2: ослабление в дожде 1,076·R^0,67 дБ/км, V = 3,91/σ;
 * у грозы наблюдений нет — оценка по ливню 25–100 мм/ч, 0,6–1,8 км).
 */
public final class Visibility {
    public static final double CLEAR = 20_000, RAIN = 5_000, THUNDER = 1_000;

    private Visibility() {}
}
