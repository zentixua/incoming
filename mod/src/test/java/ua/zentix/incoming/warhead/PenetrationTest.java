package ua.zentix.incoming.warhead;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Пробитие по Янгу (SAND97-2426, прил. A) — числа справочника пакета (ref/passport.md, «Пробитие MOP по Янгу»). */
class PenetrationTest {
    private static final Penetration.Body MOP = Penetration.body(WeaponSpec.BUNKER.payload().article());
    /** Условия удара настоящего сброса с 12 км (паспорт). */
    private static final double REAL = WeaponSpec.BUNKER.payload().article().impact().speed();

    /** Тело MOP по паспорту: m/A 24,5–27,1 т/м², нос — оживало ~6 калибров, N ≈ 0,99. */
    @Test
    void bodyFromPassport() {
        assertTrue(MOP.massPerArea() > 24_500 && MOP.massPerArea() < 27_100, "m/A " + MOP.massPerArea());
        assertEquals(0.99, MOP.nose(), 0.01);
    }

    /** На 511 м/с: камень (S 0,84) и бетон — 8,4–9,3 м; глубинный сланец (0,67) — 6,7–7,4; гравий (4–6) — 40–67. */
    @Test
    void realImpactDepthsMatchTheReference() {
        double rock = Penetration.depth(MOP, 0.84, REAL);
        assertTrue(rock > 8.4 && rock < 9.3, "камень " + rock);
        double deep = Penetration.depth(MOP, 0.67, REAL);
        assertTrue(deep > 6.7 && deep < 7.4, "сланец " + deep);
        double gravel = Penetration.depth(MOP, 5, REAL);
        assertTrue(gravel > 40 && gravel < 67, "гравий " + gravel);
    }

    /** Формула напрямую: D = 0,000018·S·N·(m/A)^0,7·(V − 30,5) — ±5 % к расчёту по справочнику. */
    @Test
    void highVelocityBranchIsYoung() {
        double expect = 0.000018 * 0.84 * MOP.nose() * Math.pow(MOP.massPerArea(), 0.7) * (300 - 30.5);
        assertEquals(expect, Penetration.depth(MOP, 0.84, 300), expect * 0.05);
    }

    /** Ниже 61 м/с — вторая ветвь: D = 0,0008·S·N·(m/A)^0,7·ln(1 + 2,15·10⁻⁴·V²). */
    @Test
    void lowVelocityBranchIsLogarithmic() {
        double k = 12 * MOP.nose() * Math.pow(MOP.massPerArea(), 0.7);
        assertEquals(0.0008 * k * Math.log(1 + 2.15e-4 * 40 * 40), Penetration.depth(MOP, 12, 40), 1e-9);
    }

    /** Однородную среду как ни режь на куски — путь тот же, что одним слоем. */
    @Test
    void uniformMediumIsOneLayerHoweverSliced() {
        double d = Penetration.depth(MOP, 0.84, 250);
        Penetration.Run run = new Penetration.Run(MOP, 250);
        double went = 0;
        for (int i = 0; i < 100 && !run.stopped(); i++) went += run.through(Penetration.Kind.ROCK, 0.84, 0.37);
        assertTrue(run.stopped());
        assertEquals(d, went, 1e-9);
    }

    /** Слой тоньше пути: скорость на выходе V·√(1 − T/D); следующий слой — по ней. */
    @Test
    void layerExitSpeed() {
        double d = Penetration.depth(MOP, 12, 250);
        Penetration.Run run = new Penetration.Run(MOP, 250);
        run.through(Penetration.Kind.SOIL, 12, 10);
        assertEquals(250 * Math.sqrt(1 - 10 / d), run.speed(), 1e-9);
        double v = run.speed();
        double rock = Penetration.depth(MOP, 0.84, v);
        assertEquals(rock, run.through(Penetration.Kind.ROCK, 0.84, 100), 1e-9);
    }

    /** Грунт за бетоном — с S/2 на шесть калибров пути (и через пустоту между плитами): ход в нём короче, чем без плиты. */
    @Test
    void soilUnderConcreteIsHarderForSixCalibres() {
        Penetration.Run slab = new Penetration.Run(MOP, 250);
        slab.through(Penetration.Kind.CONCRETE, 0.84, 1);
        slab.gap();
        double v = slab.speed();
        double soft = Penetration.depth(MOP, 6, v);
        Penetration.Run free = new Penetration.Run(MOP, v);
        double plain = 0;
        while (!free.stopped()) plain += free.through(Penetration.Kind.SOIL, 12, 1);
        double under = 0;
        while (!slab.stopped()) under += slab.through(Penetration.Kind.SOIL, 12, 1);
        assertTrue(under < plain, "за плитой " + under + ", без неё " + plain);
        // первые 4,8 м — среда S/2: скорость там гаснет как в однородной S = 6
        Penetration.Run check = new Penetration.Run(MOP, 250);
        check.through(Penetration.Kind.CONCRETE, 0.84, 1);
        double vIn = check.speed();
        check.through(Penetration.Kind.SOIL, 12, 4.8);
        assertEquals(vIn * Math.sqrt(1 - 4.8 / Penetration.depth(MOP, 6, vIn)), check.speed(), 1e-9);
        assertTrue(soft > 4.8);
    }

    /** Непробиваемое — встаёт сразу; в воде скорость гаснет как e^(−ρ·CdA·x/(2m)). */
    @Test
    void stopAndFluid() {
        Penetration.Run stop = new Penetration.Run(MOP, 250);
        assertEquals(0, stop.through(Penetration.Kind.STOP, 0, 1));
        assertTrue(stop.stopped());
        Penetration.Run water = new Penetration.Run(MOP, 250);
        water.through(Penetration.Kind.FLUID, 1000, 10);
        assertEquals(250 * Math.exp(-1000 * MOP.cda() * 10 / (2 * MOP.mass())), water.speed(), 1e-9);
        assertFalse(water.stopped());
    }
}
