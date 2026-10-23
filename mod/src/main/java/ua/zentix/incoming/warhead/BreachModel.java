package ua.zentix.incoming.warhead;

/**
 * Пробой преграды зарядом, приложенным к ней, — без Minecraft. Закон — FM 5-250 «Explosives and Demolitions» (1992),
 * прил. B, ф-ла B-7 (метрическая форма ф-лы 3-6): K = R³·M·C, K — кг ТНТ, R — радиус пробоя, м, M — множитель материала
 * (табл. B-3), C — множитель забивки (рис. 3-15). Радиус пробоя — «расстояние, на которое заряд должен проникнуть,
 * чтобы сместить или разрушить преграду» (B-5a): у наружного заряда — толщина преграды (3-12b), всё в нём разрушено.
 * Действует, только если за преградой свободная грань (3-15b(1): «free reflection surface … allows spalling»):
 * плиту над пустотой (настил моста, крышу над помещением, свод над пещерой) заряд пробивает насквозь, а полупространство
 * грунта — нет, там воронка ({@link ua.zentix.incoming.nuclear.model.CraterModel#chargeRadius}).
 * <p>
 * Где закон приближён: формула — правило подрывника для расчёта заряда, который наверняка пробьёт (FM округляет R
 * вверх до ¼ м и советует брать материал прочнее, если сомневаешься), поэтому настоящий пробой того же заряда скорее
 * шире, чем здесь, — насколько, FM не говорит. Обращение формулы (какую толщину пробьёт данный заряд) — наше: таблица
 * M ступенчатая по R, и берётся наибольший R, для которого заряда по FM хватает ({@link Material#radius}). Слои разных
 * материалов FM не складывает (велит считать по самому прочному); здесь путь в каждом слое делится на радиус его
 * материала, и пробой — пока сумма не больше 1 (оценка: у однородной плиты — ровно формула FM).
 */
public final class BreachModel {
    /**
     * Наружный заряд без забивки, приложенный к грани, — «elevated untamped» рис. 3-15 (C = 1,8): боевая часть,
     * взорвавшаяся на поверхности, в которую ударила. Заряд на земле у основания стены — 3,6 (часть энергии уходит в
     * землю), но у плиты, по которой ударили сверху, грань под зарядом и есть преграда.
     */
    public static final double UNTAMPED = 1.8;
    /** Заряд в толще, в центре массы («placed in center of mass», рис. 3-15, C = 1,0): бомба, вошедшая в грунт. */
    public static final double TAMPED = 1.0;
    /** Плотность литого ТНТ, кг/м³ (справочные 1,65 г/см³): размер заряда. */
    static final double TNT_DENSITY = 1650;

    private BreachModel() {}

    /**
     * Группы материалов табл. B-3 (FM 5-250, прил. B): множитель M по ступеням радиуса пробоя. Границы ступеней — м
     * (ступень «0,3 м и меньше» — до 0,3 включительно, остальные — «от … до меньше …»).
     */
    public enum Material {
        /** Earth — земля, песок, гравий, глина, снег и прочее рыхлое. */
        EARTH(false, new double[]{}, 1.12),
        /** Poor masonry, shale, hardpan, good timber, earth construction — дерево, сланец, ссохшаяся глина (терракота). */
        POOR(false, new double[]{1.5}, 5.13, 4.64),
        /** Good masonry, concrete block, rock — камень, кирпич и обычная кладка, бетон без арматуры. */
        ROCK(true, new double[]{0.3, 1, 1.5, 2}, 14.09, 7.69, 6.41, 5.13, 4.32),
        /** Dense concrete, first-class masonry — плотная кладка тёсаным камнем. */
        DENSE(true, new double[]{0.3, 1, 1.5, 2}, 18.26, 9.93, 8.33, 6.57, 5.61),
        /**
         * Reinforced concrete (бетон, арматура не режется) — самая прочная группа таблицы: ею FM велит считать то, что
         * не опознано (3-12c), — твёрдые блоки без записи.
         */
        REINFORCED(true, new double[]{0.3, 1, 1.5, 2}, 28.19, 15.38, 12.81, 10.09, 8.65);

        /** Границы ступеней по R, м (ступеней на одну больше). */
        private final double[] bounds;
        /**
         * Первая ступень включает свою границу: у бетона и скалы — «0,3 м и меньше»; у слабой кладки и дерева —
         * «меньше 1,5 м», и 1,5 — уже вторая.
         */
        private final boolean firstInclusive;
        /** M на каждой ступени. */
        private final double[] factor;

        Material(boolean firstInclusive, double[] bounds, double... factor) {
            if (factor.length != bounds.length + 1) throw new IllegalArgumentException("ступеней не столько, сколько множителей");
            this.bounds = bounds;
            this.factor = factor;
            this.firstInclusive = firstInclusive;
        }

        /** M при радиусе пробоя {@code r}, м (табл. B-3). */
        public double factor(double r) {
            int i = 0;
            // «0,3 м и меньше» — первая ступень включает границу, дальше — «от … до меньше …»
            while (i < bounds.length && (i == 0 && firstInclusive ? r > bounds[i] : r >= bounds[i])) i++;
            return factor[i];
        }

        /** Заряд, который по FM пробивает преграду толщиной {@code r} м при забивке {@code c}, кг ТНТ (ф-ла B-7). */
        public double charge(double r, double c) {
            return r * r * r * factor(r) * c;
        }

        /**
         * Радиус пробоя заряда {@code tnt} кг ТНТ при забивке {@code c}, м: наибольший R, для которого заряда по FM
         * хватает. На каждой ступени R = ∛(K/(M·C)), если он в ней, иначе её верхняя граница, если заряда хватает и
         * на неё; из ступеней — наибольший (M по ступеням падает, так что R растёт с зарядом без скачков назад).
         */
        public double radius(double tnt, double c) {
            if (!(tnt > 0) || !(c > 0)) return 0;
            double best = 0;
            for (int i = 0; i < factor.length; i++) {
                double lo = i == 0 ? 0 : bounds[i - 1], hi = i < bounds.length ? bounds[i] : Double.POSITIVE_INFINITY;
                double r = Math.cbrt(tnt / (factor[i] * c));
                if (r < lo) continue;
                best = Math.max(best, Math.min(r, hi));
            }
            return best;
        }
    }

    /**
     * Радиус шара заряда {@code tnt} кг ТНТ, м: дальше этого от грани заряд к ней не приложен — закон пробоя для
     * отнесённого заряда FM не даёт (там бьёт волна по воздуху, {@link StagedExplosion}).
     */
    public static double chargeRadius(double tnt) {
        return Math.cbrt(3 * Math.max(tnt, 0) / (4 * Math.PI * TNT_DENSITY));
    }
}
