package ua.zentix.incoming.client.fx;

import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;

/**
 * Что взорвалось — для картинки и звука взрыва, одним местом (без Minecraft): заряд в тротиловом эквиваленте и остаток
 * топлива — из паспорта оружия, названного пакетом взрыва ({@link S2C.Blast#weaponType}), вид топлива и состав
 * взрывчатки — здесь, по открытым данным (ref/passport.md). Размер взрыва по виду пакета ({@code kind}) не берёт никто:
 * «Ланцет» шёл видом шахеда и выглядел шахедом.
 *
 * @param weapon    оружие
 * @param tntKg     заряд, кг ТНТ ({@link WeaponSpec#charge})
 * @param fuelKg    топливо в баках к удару, кг ({@link WeaponSpec#fuelAtImpact})
 * @param fuel      какое топливо
 * @param explosive состав взрывчатки
 */
public record BlastSource(WeaponType weapon, double tntKg, double fuelKg, Fuel fuel, Explosive explosive) {

    /**
     * Топливо: теплота сгорания (низшая, справочная) и выход сажи при горении в огненном шаре — оценка: у жидких
     * углеводородов в пламени 0,01–0,06 г/г (SFPE Handbook, гл. 36, Khan, Tewarson, Chaos; таблица не сверена), взята
     * середина. Огненный шар топлива горит быстрее и с большим подсосом воздуха, чем пожар разлития, — вероятно, сажи меньше.
     *
     * @param heatJPerKg теплота сгорания, Дж/кг
     * @param sootYield  сажи на кг сгоревшего, кг
     */
    public enum Fuel {
        NONE(0, 0),
        /** Бензин двухтактного двигателя шахеда (MD-550): 43,4 МДж/кг. */
        PETROL(43.4e6, 0.04),
        /** JP-10 крылатой ракеты (экзо-тетрагидродициклопентадиен): 42,1 МДж/кг. */
        JP10(42.1e6, 0.04);

        public final double heatJPerKg, sootYield;

        Fuel(double heatJPerKg, double sootYield) {
            this.heatJPerKg = heatJPerKg;
            this.sootYield = sootYield;
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

    /** Взрыв из пакета: оружие пакета (в старом повторе — по виду). */
    public static BlastSource of(S2C.Blast p) {
        return of(p.weaponType());
    }

    /**
     * Взрыв оружия по паспорту. Состав: «Томагавк» — PBXN-107 на гексогене (ref/passport.md); у шахеда, «Ланцета» и
     * «Града» состав не публикуется — тротиловый, как у боевых частей их семейств (оценка; дым на съёмках ударов —
     * чёрный). Топливо — по двигателю: шахед — бензин, ракета — JP-10; у остальных к удару его нет.
     */
    public static BlastSource of(WeaponType w) {
        WeaponSpec s = w.spec();
        Fuel fuel = s.fuelAtImpact() <= 0 ? Fuel.NONE : w == WeaponType.MISSILE ? Fuel.JP10 : Fuel.PETROL;
        Explosive explosive = w == WeaponType.MISSILE ? Explosive.RDX : Explosive.TNT;
        return new BlastSource(w, s.charge(), s.fuelAtImpact(), fuel, explosive);
    }
}
