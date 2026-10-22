package ua.zentix.incoming.warhead;

/**
 * Удар корпуса бетонобойной бомбы о землю — без Minecraft: взрыва нет (взрыватель замедленный), есть энергия удара.
 */
public final class BunkerImpact {
    /** Тротиловый эквивалент энергии: 4,184 МДж на кг (определение). */
    static final double TNT_J = 4.184e6;
    /**
     * Какая доля энергии удара уходит в воздух — хлопком и выбросом из входа (оценка): у проникающего удара почти вся
     * энергия уходит на дробление среды по пути (закон Янга), в воздух — проценты; взят 1 %.
     */
    static final double AIR_SHARE = 0.01;
    /** Воронка входа: конус радиусом и глубиной в столько калибров (оценка по воронкам входа проникающих тел). */
    static final double ENTRY_CALIBRES = 2;

    private BunkerImpact() {}

    /** Энергия удара тела массой {@code mass} кг на {@code speed} м/с, кг ТНТ. */
    public static double equivalentTnt(double mass, double speed) {
        return 0.5 * mass * speed * speed / TNT_J;
    }

    /** Доля энергии удара {@code tnt} кг ТНТ, что ушла в воздух, кг ТНТ. */
    public static double airTnt(double tnt) {
        return tnt * AIR_SHARE;
    }

    /** Объём грунта, выброшенный из воронки входа тела диаметром {@code diameter} м, м³ (конус π/3·r²·h). */
    public static double entryVolume(double diameter) {
        double r = ENTRY_CALIBRES * diameter;
        return Math.PI / 3 * r * r * r;
    }
}
