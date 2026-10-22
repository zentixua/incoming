package ua.zentix.incoming.warhead;

import ua.zentix.incoming.nuclear.model.CraterModel;

/**
 * Взрыв заряда под землёй — без Minecraft, общий у сервера (полость, дробление, обрушения, воронка) и клиента (облако,
 * звук). Всё — по приведённой глубине λ = d/W^⅓ (м/кг^⅓) и радиусам, кратным W^⅓ (TM 5-855-1; Westine); W — кг ТНТ.
 * Законы запертого взрыва известны грубо: каждое число ниже — оценка с диапазоном, и это сказано прямо.
 *
 * @param tnt   заряд, кг ТНТ
 * @param depth глубина заряда под верхом над ним, м
 */
public record BuriedBlast(double tnt, double depth) {
    /**
     * Радиус полости, ·W^⅓ м: 0,12–0,5 (между данными подземных ядерных и химических взрывов, TM 5-855-1 — широкая
     * оценка); взята середина по логарифму, 0,25: у 2,4 т — 3,4 м.
     */
    static final double CAVITY = 0.25;
    /** Зона дробления — столько радиусов полости (по обзору «несколько радиусов»; оценка). */
    static final double CRUSH = 3;
    /** Трещины и обрушения ходов и пещер — до столько ·W^⅓ м (по обзору «десятки метров»; оценка): у 2,4 т — 27 м. */
    static final double FRACTURE = 2;
    /** Мельче этой приведённой глубины взрыв выбрасывает грунт наружу — воронка (TM 5-855-1: ~1 м/кг^⅓). */
    static final double BREACH = 1;
    /**
     * Обрушение свода полости поднимается трубой на столько радиусов полости (оценка по трубам обрушения подземных ядерных
     * испытаний — 4–6 радиусов полости): ближе к верху — провал на поверхности.
     */
    static final double CHIMNEY = 5;

    /** Плотность породы над полостью, кг/м³ (оценка: грунт 1600–2000, камень 2600–2700). */
    static final double ROCK_DENSITY = 2400;

    /**
     * Энергия обрушения свода над полостью объёмом {@code volume} м³, кг ТНТ: порода объёма полости падает на высоту
     * трубы обрушения ({@link #CHIMNEY} радиусов полости) — m·g·h.
     */
    public static double collapseTnt(double volume) {
        double r = Math.cbrt(3 * volume / (4 * Math.PI));
        return volume * ROCK_DENSITY * 9.80665 * CHIMNEY * r / BunkerImpact.TNT_J;
    }

    /** W^⅓, кг^⅓. */
    public double cube() {
        return Math.cbrt(tnt);
    }

    /** Приведённая глубина λ, м/кг^⅓. */
    public double scaledDepth() {
        return depth / cube();
    }

    /** Взрыв выбрасывает грунт наружу (λ < {@value #BREACH}): воронка выброса и облако, как у наземного. */
    public boolean breaches() {
        return scaledDepth() < BREACH;
    }

    /** Радиус полости, м. */
    public double cavity() {
        return CAVITY * cube();
    }

    /** Радиус зоны дробления, м. */
    public double crush() {
        return CRUSH * cavity();
    }

    /** Докуда рушатся своды ходов и пещер, м. */
    public double fracture() {
        return FRACTURE * cube();
    }

    /** Высота трубы обрушения над центром полости, м. */
    public double chimney() {
        return CHIMNEY * cavity();
    }

    /** Свод рушится до верха: на поверхности провал (взрыв не прорвался наружу, но труба обрушения достаёт до верха). */
    public boolean subsides() {
        return !breaches() && depth <= chimney();
    }

    /**
     * Радиус видимой воронки выброса в грунте {@code soil}, м: у самой поверхности — воронка наземного заряда
     * ({@link CraterModel#chargeRadius}), с глубиной сперва шире (заряд выбрасывает больше грунта), к границе прорыва
     * (λ = {@value #BREACH}) сходит на нет — дальше полость без воронки (камуфлет). Форма — R₀·(1 + 2λ)·(1 − λ²): наибольшая,
     * ~1,5 R₀, около λ ≈ 0,5 (оценка формы по обзорам глубины подрыва — кривые воронок по приведённой глубине; числа с
     * первоисточником не сверены). 0 — воронки нет.
     */
    public double craterRadius(CraterModel.Soil soil) {
        if (!breaches()) return 0;
        double l = Math.max(0, scaledDepth()) / BREACH;
        return CraterModel.chargeRadius(tnt, soil) * (1 + 2 * l) * (1 - l * l);
    }

    /** Глубина видимой воронки выброса, м: четверть диаметра, как у воронки наземного заряда. */
    public double craterDepth(CraterModel.Soil soil) {
        return 0.5 * craterRadius(soil);
    }

    /** Объём чаши воронки выброса (параболоид π/2·r²·h), м³: столько грунта выброшено. */
    public double craterVolume(CraterModel.Soil soil) {
        double r = craterRadius(soil);
        return Math.PI / 2 * r * r * craterDepth(soil);
    }

    /**
     * Какая доля энергии заряда уходит в воздух волной ({@code hole} — сечение хода в грунте, м²; 0 — хода нет). Глубокий —
     * только газы через ход: доля сечения хода в стенке полости, hole / (4π·R²) (оценка: у 2,4 т и хода 0,8 м — 0,35 %).
     * Прорыв наружу — 1 − λ (у самой поверхности — весь заряд, на границе прорыва — доля хода; оценка).
     */
    public double airShare(double hole) {
        double r = cavity(), through = Math.min(1, hole / (4 * Math.PI * r * r));
        return breaches() ? Math.max(through, 1 - Math.max(0, scaledDepth()) / BREACH) : through;
    }

    /** Заряд, который слышно и видно над землёй, кг ТНТ: {@link #airShare} от заряда. */
    public double airCharge(double hole) {
        return tnt * airShare(hole);
    }
}
