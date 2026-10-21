package ua.zentix.incoming.client.fx;

import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;

/**
 * Что взорвалось — то, что картинке и звуку нужно от взрыва, из пакета {@link S2C.Blast} и паспорта оружия
 * ({@link WeaponSpec}): заряд и топливо к удару берутся только отсюда, а не из вида пакета (по виду «Ланцет» был шахедом).
 * Без Minecraft.
 *
 * @param weapon      оружие; null — взрыв не оружия из паспорта (разрыв зенитной ракеты)
 * @param chargeKg    заряд в тротиловом эквиваленте, кг ({@link WeaponSpec#charge}); 0 — наземного взрыва нет (бомба
 *                    рвётся под землёй, у неё свои звуки)
 * @param fuelKg      топливо, что сгорает огненным шаром, кг ({@link WeaponSpec#fuelAtImpact})
 * @param surface     взрыв на земле (волна отражается от неё — давление как у заряда в 1,8 раза больше) или в воздухе
 * @param underground бомба взорвалась под землёй ({@link S2C.Blast#BUNKER})
 */
public record BlastSource(@Nullable WeaponType weapon, double chargeKg, double fuelKg, boolean surface, boolean underground) {
    /**
     * Топливо: теплота сгорания (низшая, справочная) и выход сажи при горении в огненном шаре — оценка: у жидких
     * углеводородов в пламени 0,01–0,06 г/г (SFPE Handbook, гл. 36, Khan, Tewarson, Chaos; таблица не сверена), взята
     * середина. Огненный шар топлива горит быстрее и с большим подсосом воздуха, чем пожар разлития, — вероятно, сажи меньше.
     *
     * @param heatJPerKg теплота сгорания, Дж/кг
     * @param sootYield  сажи на кг сгоревшего, кг
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

        /**
         * Теплота сгорания, Дж/кг; выход сажи, кг/кг; выгорание большой лужи с единицы площади, кг/(м²·с) (Babrauskas,
         * SFPE Handbook, гл. «Heat Release Rates», табл. для бассейнов диаметром больше 1 м).
         */
        public final double heatJPerKg, sootYield, poolBurn;

        Fuel(double heatJPerKg, double sootYield, double poolBurn) {
            this.heatJPerKg = heatJPerKg;
            this.sootYield = sootYield;
            this.poolBurn = poolBurn;
        }
    }

    /**
     * Взрывчатка — брутто-формула CHNO. Свободный углерод продуктов детонации — по правилам Кистяковского–Вильсона
     * (Akhavan, «The Chemistry of Explosives», гл. 4): при кислородном балансе ниже −40 % (модифицированные, Springall
     * Roberts) кислород сперва уходит в воду, остаток — в CO; выше — сперва CO, потом вода, потом CO₂. Что не стало CO —
     * сажа: тротил беден кислородом (баланс −74 %) — в продуктах 18,5 % массы углерода, дым чёрный; гексоген (−21,6 %) —
     * углерода нет, дым серый — грунт, вода и то, что сгорело рядом.
     */
    public enum Explosive {
        /** Тротил C₇H₅N₃O₆. */
        TNT(7, 5, 3, 6),
        /** Гексоген C₃H₆N₆O₆ (основа PBXN-107 «Томагавка»: 86 % гексогена, остальное — связка, её углерод не в счёт). */
        RDX(3, 6, 6, 6);

        final int c, h, n, o;

        Explosive(int c, int h, int n, int o) {
            this.c = c;
            this.h = h;
            this.n = n;
            this.o = o;
        }

        /** Молярная масса, г/моль. */
        double molarMass() {
            return 12.011 * c + 1.008 * h + 14.007 * n + 15.999 * o;
        }

        /** Кислородный баланс до CO₂ и H₂O, доля массы (тротил −0,74, гексоген −0,216). */
        public double oxygenBalance() {
            return -1600 * (2 * c + h / 2.0 - o) / molarMass() / 100;
        }

        /** Свободного углерода в продуктах детонации на кг взрывчатки, кг (правила Кистяковского–Вильсона). */
        public double freeCarbon() {
            double oxygen = o, carbon;
            if (oxygenBalance() < -0.40) {
                oxygen -= Math.min(oxygen, h / 2.0);
                carbon = c - Math.min(c, oxygen);
            } else {
                carbon = c - Math.min(c, oxygen);
            }
            return carbon * 12.011 / molarMass();
        }
    }


    /**
     * Топливо по двигателю: шахед — бензин, ракета — JP-10; у остальных к удару его нет.
     */
    public Fuel fuel() {
        return fuelKg <= 0 ? Fuel.NONE : weapon == WeaponType.MISSILE ? Fuel.JP10 : Fuel.PETROL;
    }

    /**
     * Состав взрывчатки: «Томагавк» — PBXN-107 на гексогене (ref/passport.md); у шахеда, «Ланцета», «Града» и разрыва
     * зенитной ракеты состав не публикуется — тротиловый, как у боевых частей их семейств (оценка; дым на съёмках ударов —
     * чёрный).
     */
    public Explosive explosive() {
        return weapon == WeaponType.MISSILE ? Explosive.RDX : Explosive.TNT;
    }

    /** Взрыв из пакета: оружие из пакета (в старом повторе — по виду), числа — из его паспорта. */
    public static BlastSource of(S2C.Blast p) {
        return of(p.weaponType(), p.kind() == S2C.Blast.BUNKER);
    }

    /** Взрыв оружия {@code w} на земле ({@code underground} — бомба под землёй). */
    public static BlastSource of(WeaponType w, boolean underground) {
        WeaponSpec s = w.spec();
        return new BlastSource(w, s.charge(), s.fuelAtImpact(), true, underground);
    }

    /** Разрыв в воздухе заряда {@code chargeKg} кг ТНТ без топлива. */
    public static BlastSource air(double chargeKg) {
        return new BlastSource(null, chargeKg, 0, false, false);
    }
}
