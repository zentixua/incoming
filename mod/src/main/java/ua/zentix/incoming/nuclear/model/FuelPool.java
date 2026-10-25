package ua.zentix.incoming.nuclear.model;

/**
 * Топливо, что осталось в воронке после огненного шара ({@link FuelFireball#POOL_SHARE}) и догорает там тонким слоем на
 * грунте. Один закон у сервера (где и сколько горит огонь на блоках, {@code warhead/FuelFires}) и у дыма и звука клиента.
 * Без Minecraft.
 * <p>
 * Площадь — круг воронки заряда ({@link CraterModel#chargeRadius} сухого грунта): топливо пропитывает выброшенный и
 * перемешанный грунт чаши. Выгорание с единицы площади — у большой лужи ({@link Fuel#poolBurn}) × {@link #THIN_LAYER}:
 * слой в миллиметры на грунте горит медленнее глубокой лужи. Горит {@link #seconds}, тепловыделение {@link #watts}: у
 * шахеда ~2,5 мин по ~2 МВт, у ракеты ~16 мин по ~3 МВт. К концу (последняя пятая) слой выгорает пятнами — горящая
 * площадь и тепло сходят на нет ({@link #share}).
 *
 * @param kg      топлива в воронке, кг
 * @param radius  радиус круга, по которому оно лежит, м
 * @param seconds сколько горит, с (0 — топлива нет)
 * @param watts   тепловыделение, пока горит в полную силу, Вт
 * @param fuel    что горит
 */
public record FuelPool(double kg, double radius, double seconds, double watts, Fuel fuel) {
    /**
     * Выгорание топлива тонким слоем на грунте против глубокой лужи: у разлива толщиной в миллиметры наибольшая скорость
     * горения — порядка пятой доли глубокой (Mealy, Benfer, Gottuk, «Liquid fuel spill fire dynamics», Fire Safety Science
     * 10, 2011: тепло уходит в подложку); у топлива в пористом слое песка она тоже ниже, чем у лужи того же размера. В
     * воронке шахеда — ~2 мм бензина, ракеты — ~8 мм JP-10 в перемешанном грунте (⚠ одна доля на оба: зависимость от
     * толщины и грунта по их корреляциям не взята).
     */
    public static final double THIN_LAYER = 0.2;
    /** Доля жизни слоя, за которую он выгорает пятнами до нуля (⚠ форма спада — оценка). */
    static final double FADE = 0.2;
    public static final FuelPool NONE = new FuelPool(0, 0, 0, 0, Fuel.NONE);

    /**
     * Топливо: теплота сгорания (низшая, справочная), выход сажи в пламени — оценка: у жидких углеводородов 0,01–0,06 г/г
     * (SFPE Handbook, гл. 36, Khan, Tewarson, Chaos; таблица не сверена), взята середина, — и выгорание большой лужи с
     * единицы площади (Babrauskas, SFPE Handbook, гл. «Heat Release Rates», табл. для бассейнов диаметром больше 1 м).
     */
    public enum Fuel {
        NONE(0, 0, 0),
        /** Бензин двухтактного двигателя шахеда (MD-550): 43,4 МДж/кг; выгорание лужи — 0,055 кг/(м²·с). */
        PETROL(43.4e6, 0.04, 0.055),
        /**
         * JP-10 крылатой ракеты (экзо-тетрагидродициклопентадиен): 42,1 МДж/кг; выгорание лужи — как у керосина,
         * 0,039 кг/(м²·с) (⚠ замеров JP-10 нет — близкий по составу и летучести).
         */
        JP10(42.1e6, 0.04, 0.039);

        /** Теплота сгорания, Дж/кг; выход сажи, кг/кг; выгорание большой лужи с единицы площади, кг/(м²·с). */
        public final double heatJPerKg, sootYield, poolBurn;

        Fuel(double heatJPerKg, double sootYield, double poolBurn) {
            this.heatJPerKg = heatJPerKg;
            this.sootYield = sootYield;
            this.poolBurn = poolBurn;
        }
    }

    /**
     * Топливо {@code fuel} в баке к удару, {@code fuelKg} кг, у заряда {@code chargeKg} кг ТНТ, на земле: в воронке — доля
     * {@link FuelFireball#POOL_SHARE}, по кругу воронки заряда. Без топлива или заряда — {@link #NONE}.
     */
    public static FuelPool of(Fuel fuel, double fuelKg, double chargeKg) {
        double kg = FuelFireball.POOL_SHARE * Math.max(0, fuelKg);
        double radius = CraterModel.chargeRadius(chargeKg, CraterModel.Soil.DRY);
        double burn = THIN_LAYER * fuel.poolBurn * Math.PI * radius * radius;
        if (kg <= 0 || burn <= 0) return NONE;
        return new FuelPool(kg, radius, kg / burn, burn * fuel.heatJPerKg, fuel);
    }

    /** Площадь, по которой лежит топливо, м². */
    public double area() {
        return Math.PI * radius * radius;
    }

    /**
     * Доля тепловыделения и горящей площади в возрасте t, с: полное, пока горит, к концу (последняя {@link #FADE} жизни)
     * гаснет пятнами (гладкий спад 3u² − 2u³); 0 — топлива нет или выгорело.
     */
    public double share(double t) {
        if (seconds <= 0 || t < 0 || t >= seconds) return 0;
        double u = (seconds - t) / (FADE * seconds);
        return u >= 1 ? 1 : u * u * (3 - 2 * u);
    }

    /** Высота пламени по Хескестаду, м: L = 0,235·Q^⅖ − 1,02·D (Q — кВт), не меньше полуметра. */
    public double flame() {
        return watts <= 0 ? 0 : Math.max(0.5, 0.235 * Math.pow(watts / 1e3, 0.4) - 1.02 * 2 * radius);
    }

    /**
     * Сколько блоков (клеток по 1 м²) накрывает топливо: площадь, округлённая до целых, не меньше одной клетки, если
     * топливо есть. Блок — метр: шахед — 5 клеток, ракета — 9.
     */
    public int cells() {
        return seconds <= 0 ? 0 : (int) Math.max(1, Math.round(area()));
    }

    /**
     * То же топливо на {@code m} клетках из {@code n} по закону ({@link #cells}): легло на меньшую площадь (узкая яма,
     * шахта, остальное некуда) — слой толще, горит дольше во столько же раз и слабее; при m ≥ n — как есть.
     */
    public FuelPool spread(int m, int n) {
        if (seconds <= 0 || m <= 0 || m >= n) return this;
        double share = (double) m / n;
        return new FuelPool(kg, radius * Math.sqrt(share), seconds / share, watts * share, fuel);
    }

    /**
     * Когда гаснет клетка {@code k} из {@code n} (0 — та, что горит дольше всех: самая низкая, куда топливо стекло), с:
     * горит, пока доля горящей площади ({@link #share}) больше k/n. Клетка 0 горит всю жизнь слоя, край — гаснет первым.
     */
    public double cellSeconds(int k, int n) {
        if (seconds <= 0 || n <= 0 || k >= n) return 0;
        double s = Math.max(0, (double) k / n);
        if (s <= 0) return seconds;
        // обращение 3u² − 2u³ = s на [0, 1]
        double u = 0.5 - Math.sin(Math.asin(1 - 2 * s) / 3);
        return seconds - FADE * seconds * u;
    }
}
