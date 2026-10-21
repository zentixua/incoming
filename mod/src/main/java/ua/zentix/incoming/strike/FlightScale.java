package ua.zentix.incoming.strike;

import ua.zentix.incoming.IncomingConfig;

/**
 * Масштаб скоростей и высот полёта — настройка мира {@code scale.flight_speed} через пресет {@code scale.scale_preset}
 * (вид «масштаб под игру», решение Артёма 21.10): x = 0 — игровые скорости и высоты (не по физике: медленнее и ниже
 * настоящих, чтобы подлёт было видно на сотнях блоков), x = 1 — как в жизни, между — плавно, у каждого изделия своим
 * отношением: v = v<sub>игр</sub>·(v<sub>наст</sub>/v<sub>игр</sub>)^x. Отношение игра/жизнь одно на все изделия и все
 * их скорости — иначе сломались бы соотношения между ними. Законы полёта (перегрузка, тяжесть, сопротивление, ветер)
 * одни при любом x; всё у цели настоящее при любом.
 */
public final class FlightScale {
    /** x для проверок без мира: задают сами ({@link #pin}); NaN — из настроек. */
    private static double pinned = Double.NaN;

    private FlightScale() {}

    /** x сейчас: из настроек мира, а без них (юнит-тесты, клиент до входа) — 0, игровые. */
    public static double x() {
        if (!Double.isNaN(pinned)) return pinned;
        return IncomingConfig.SERVER_SPEC.isLoaded() ? IncomingConfig.SERVER.flightSpeed() : 0;
    }

    /** Задать x для юнит-тестов (NaN — снова из настроек). */
    public static void pin(double x) {
        pinned = x;
    }

    /** Величина между игровой {@code game} и настоящей {@code real} при x: game·(real/game)^x (обе больше нуля). */
    public static double blend(double game, double real, double x) {
        if (game <= 0 || real <= 0) return x >= 1 ? real : game;
        return game * Math.pow(real / game, Math.max(0, Math.min(1, x)));
    }

    /** То же при x из настроек. */
    public static double blend(double game, double real) {
        return blend(game, real, x());
    }
}
