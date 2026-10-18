package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Пыль ударной волны — законы обзора «Blast-lofted dust» (§8) и числа, по которым её проверяют: высота у фронта и слоя
 * (GRABLE, MET, Rosenblatt), вынос над рыхлым песком (Gaj &amp; Small), темнота в Хиросиме (Hersey, свидетельства).
 * Хиросима здесь — 15 кт на 592 м (DS02: 16 кт на 600 м), город — {@link DustModel.Surface#REFERENCE}.
 */
class DustModelTest {
    private static final double YIELD = 15, HOB = 592, CLEAR = ThermalModel.VISIBILITY_CLEAR;

    private static DustModel.Column hiroshima(double groundM) {
        return DustModel.column(groundM, HOB, YIELD, false, CLEAR, DustModel.Surface.REFERENCE);
    }

    /**
     * Толща над землёй, при которой днём под слоем — ночь (свет на земле меньше тысячной открытого дня), сумерки (меньше
     * трёх сотых): два потока Эддингтона в слое пыли с альбедо цвета стены, солнце на 40° ({@code PallLight}; клиентский
     * тест пелены сверяет сам свет).
     */
    private static final double DARK = 11, DUSK = 6;

    /** Ослабление смеси корзин — k = 3Q/(2ρD) по долям: ~0,11 м²/г (у распределения MET в обзоре — 0,13). */
    @Test
    void mixExtinctionIsGeometric() {
        assertEquals(108, DustModel.K_MIX, 1);
        assertEquals(3 * 2 / (2 * 2650 * 40e-6), DustModel.K[2], 1e-9);
    }

    /**
     * Над рыхлым сухим песком при 43 м/с вынос — около килограмма на м² в секунду, в пределах двух раз (обзор §8, по Gaj
     * &amp; Small, ур. 8); воздух — за фронтом, ветер которого 43 м/с (2,8 psi).
     */
    @Test
    void fluxOverLooseSand() {
        double rho = DustModel.shockedAir(Solve.decreasingRoot(kpa -> -BlastModel.windSpeed(kpa), -43, 0.01, 1000));
        for (double behind : new double[]{10, 30, 100}) {
            double f = DustModel.flux(rho, 43, behind, 0.28);
            assertTrue(f > 0.5 && f < 2, behind + " м за фронтом: " + f);
        }
        // ниже порога срыва — ничего; вынос непрерывен там, где сальтация переходит во взвесь
        assertEquals(0, DustModel.flux(rho, 3, 10, 0.34));
        double b = DustModel.plate(20), edge = 10 * 0.34 * Math.sqrt(2) * Math.pow(b, 1.25);
        assertEquals(DustModel.flux(rho, edge * 1.0001, 20, 0.34), DustModel.flux(rho, edge * 0.9999, 20, 0.34), 1e-3 * DustModel.flux(rho, edge, 20, 0.34));
        double prev = 0;
        for (double u = 1; u < 150; u += 0.5) {
            double f = DustModel.flux(rho, u, 20, 0.34);
            assertTrue(f >= prev, u + " м/с: вынос убывает");
            prev = f;
        }
    }

    /**
     * Поднятое за фазу ({@link DustModel#soil}: закон выноса с тем, что от шага не зависит, вне цикла, и без шагов после
     * того, как ветер упал ниже порога) — та же сумма {@link DustModel#flux} по серединам шагов, что по самому закону.
     */
    @Test
    void soilIsTheFluxIntegral() {
        int checked = 0;
        for (double psi = 0.3; psi < 200; psi *= 1.3) {
            double kpa = BlastModel.kpa(psi), rho = DustModel.shockedAir(kpa), front = BlastModel.frontSpeed(kpa);
            for (double along : new double[]{0.2, 1}) {
                for (double threshold : new double[]{0.28, 0.34, 0.78, 3}) {
                    double positive = 0.3 + psi / 40, u0 = BlastModel.windSpeed(kpa) * Math.sqrt(along), want = 0, dt = positive / 200;
                    for (int i = 0; i < 200; i++) {
                        double t = (i + 0.5) * dt, x = t / positive;
                        want += DustModel.flux(rho, u0 * (1 - x) * Math.exp(-x), front * t, threshold) * dt;
                    }
                    assertEquals(want, DustModel.soil(kpa, along, positive, threshold), 1e-12 * want, psi + " psi, " + along + ", " + threshold);
                    if (want > 0) checked++;
                }
            }
        }
        assertTrue(checked > 50, "с выносом — " + checked);
    }

    /**
     * Трава и посевы (u*ₜₕ от 3 м/с, Gaj &amp; Small §2.2): в 1,4 км (6 psi) ветер волны их почти не трогает — меньше 2 %
     * того, что отдаёт рыхлый песок (сотни грамм).
     */
    @Test
    void grassHoldsTheSoil() {
        DustModel.Surface grass = new DustModel.Surface(3, 15, 0), sand = new DustModel.Surface(0.28, 15, 0);
        DustModel.Column g = DustModel.column(1400, HOB, YIELD, false, CLEAR, grass), s = DustModel.column(1400, HOB, YIELD, false, CLEAR, sand);
        assertTrue(g.soil() < 0.02 * s.soil(), "трава отдала " + g.soil() + " кг/м² при песке " + s.soil());
        assertTrue(s.soil() > 0.1, "рыхлый песок отдал " + s.soil() + " кг/м²");
        assertTrue(hiroshima(1400).soil() > 0.1 && hiroshima(1400).soil() < s.soil());
    }

    /**
     * Голова — слой у фронта: в первые десятые секунды — десятки метров (GRABLE: 6–25 м в 0,3–0,66 с), к концу положительной
     * фазы — ~45 м; у переднего края — ноль, назад толще (Kuhl: ξ^(5/6)).
     */
    @Test
    void headIsTensOfMetres() {
        for (double r : new double[]{600, 1000, 1400, 2000}) {
            DustModel.Column c = hiroshima(r);
            double quarter = c.height(0.25), end = c.height(c.positive());
            assertTrue(quarter > 6 && quarter < 30, r + " м: в 0,25 с верх " + quarter);
            assertTrue(end > 30 && end < 70, r + " м: к концу фазы верх " + end);
            assertEquals(0, c.height(0));
            assertTrue(c.height(0.05) < quarter);
        }
    }

    /**
     * Через секунды слой — «несколько сотен футов» (Rosenblatt: 60–150 м у NTS к 4–5 с; здесь 58 м в 1,4 км, где фаза
     * волны — 0,83 с, и 105 м в 600 м), над нагретой землёй растёт за минуту (обзор §5: 120–350 м в 1 км, 80–230 м
     * в 1,4 км) и не опускается.
     */
    @Test
    void layerRisesOverHeatedGround() {
        for (double r : new double[]{600, 1000, 1400}) {
            double h = hiroshima(r).height(5);
            assertTrue(h > 50 && h < 150, r + " м: через 5 с верх " + h);
        }
        double h1 = hiroshima(1000).ceiling(), h14 = hiroshima(1400).ceiling();
        assertTrue(h1 > 120 && h1 < 350, "1 км: верх " + h1);
        assertTrue(h14 > 80 && h14 < 230, "1,4 км: верх " + h14);
        DustModel.Column c = hiroshima(1400);
        double prev = 0;
        for (double t = 0.01; t < 3600; t += 0.5) {
            assertTrue(c.height(t) >= prev - 1e-9, t + " с: верх опустился");
            prev = c.height(t);
        }
    }

    /**
     * Хиросима, Фудзии в 1,42 км: «около двадцати минут в потемневшем утре» — ночь под слоем с секунд до 20-й минуты, к
     * 30-й светлее (Hersey 1946).
     */
    @Test
    void darkForTwentyMinutesAtFujii() {
        DustModel.Column c = hiroshima(1420);
        for (double t = 3; t <= 20 * 60; t += 10) assertTrue(c.depth(t) > DARK, t + " с: в 1,42 км уже не ночь, толща " + c.depth(t));
        assertTrue(c.depth(30 * 60) < DARK, "к 30-й минуте не светлее: " + c.depth(30 * 60));
        assertTrue(c.depth(60 * 60) < 3, "через час всё ещё темно: " + c.depth(60 * 60));
    }

    /** Танимото в 3,2 км: «сумерки», день темнел — не ночь и не ясно первые минуты. */
    @Test
    void twilightAtTanimoto() {
        DustModel.Column c = hiroshima(3200);
        for (double t = 5; t <= 180; t += 5) {
            double tau = c.depth(t);
            assertTrue(tau > 4 && tau < DARK, t + " с: в 3,2 км толща " + tau);
        }
    }

    /** Такакура в 300 м: темно, но «небо стало светлеть» через минуты — пыль у эпицентра уходит в ножку. */
    @Test
    void lighterWithinMinutesAtTakakura() {
        DustModel.Column c = hiroshima(300);
        assertTrue(c.depth(30) > DARK, "в 300 м сразу не темно");
        assertTrue(c.depth(10 * 60) < DUSK, "в 300 м через 10 минут всё ещё темно: " + c.depth(600));
    }

    /** Крупное (400 мкм) выпадает за 1–3 минуты — тёмная голова оседает (обзор §8.4); мелкое (3 мкм) висит часы. */
    @Test
    void coarseFallsOutInMinutes() {
        DustModel.Column c = hiroshima(1400);
        assertTrue(c.airborne(4, 180) < 0.05, "400 мкм через 3 минуты: " + c.airborne(4, 180));
        assertTrue(c.airborne(0, 3600) > 0.9, "3 мкм через час: " + c.airborne(0, 3600));
        // оседает то, что выпадает: поток на землю — первые минуты больше, чем через полчаса
        assertTrue(c.fallout(60) > 3 * c.fallout(1800));
    }

    /** Ничего не поднято до фронта; за фазу волны — всё; вся пыль — грунт и обломки, обломков нет там, где дома стоят. */
    @Test
    void liftFollowsThePositivePhase() {
        assertEquals(0, DustModel.lifted(0), 1e-12);
        assertEquals(1, DustModel.lifted(1), 1e-12);
        assertEquals(1, DustModel.lifted(5), 1e-12);
        double prev = 0;
        for (double s = 0.01; s <= 1; s += 0.01) {
            assertTrue(DustModel.lifted(s) > prev);
            prev = DustModel.lifted(s);
        }
        assertEquals(4.62, DustModel.lifted(1e-4) / 1e-4, 0.01, "у фронта растёт как импульс напора");
        DustModel.Column c = hiroshima(1400);
        assertEquals(0, c.depth(0));
        assertEquals(c.lifted(), c.depth(c.positive()), 1e-9 * c.lifted());
        assertEquals(0, DustModel.destroyed(0.5));
        assertEquals(1, DustModel.destroyed(6), 1e-12);
        // обломков нет, где давление на земле ниже 0,6 psi (у 15 кт на 592 м — дальше 6,3 км)
        assertEquals(0, hiroshima(HobBlast.rangeFor(DustModel.DEBRIS_FROM_PSI, HOB, YIELD) + 1).debris(), 1e-12);
    }

    /** Больше мощность — шире и выше слой: та же форма по приведённой дальности, верх — как ∛Y. */
    @Test
    void largerYieldWiderDust() {
        double ys = Math.cbrt(1000 / YIELD);
        DustModel.Column small = hiroshima(1400), big = DustModel.column(1400 * ys, HOB * ys, 1000, false, CLEAR, DustModel.Surface.REFERENCE);
        assertTrue(big.mass() > 0.5 * small.mass() && big.mass() < 3 * small.mass(), small.mass() + " → " + big.mass());
        assertTrue(big.first() > 1.5 * small.first(), "верх в фазе волны " + small.first() + " → " + big.first());
        double r15 = DustModel.reach(HOB, YIELD, false, CLEAR, DustModel.Surface.REFERENCE, 0.05);
        double r1 = DustModel.reach(HOB * Math.cbrt(1 / YIELD), 1, false, CLEAR, DustModel.Surface.REFERENCE, 0.05);
        assertTrue(r15 > 4000 && r15 < 8000, "пыль 15 кт до " + r15 + " м");
        assertTrue(r1 < 0.6 * r15);
        assertTrue(hiroshima(r15 * 0.99).lifted() >= 0.05 && hiroshima(r15 * 1.01).lifted() <= 0.05);
    }
}
