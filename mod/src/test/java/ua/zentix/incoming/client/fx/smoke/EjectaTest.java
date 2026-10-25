package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.CraterModel;
import ua.zentix.incoming.warhead.GroundMaterial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Завеса выброса воронки ({@link Ejecta}): масса сохраняется, падает назад за секунды, грунта под землёй нет. */
class EjectaTest {
    private static final double[] CHARGES = {6.4, 50, 150};

    static Ejecta of(double tnt) {
        BlastTarget t = BlastTarget.of(GroundMaterial.DIRT, tnt, 0);
        return new Ejecta(CraterModel.chargeRadius(tnt, CraterModel.Soil.DRY), t.mass(tnt) * (1 - CloudModel.DUST_LOFTED), t.matter().clods);
    }

    /**
     * Зёрна в воздухе плюс упавшие — все зёрна выброса; струи кладут ровно то, что в воздухе; выброс плюс пыль в воздухе —
     * масса воронки.
     */
    @Test
    void massIsConserved() {
        for (double tnt : CHARGES) {
            BlastTarget t = BlastTarget.of(GroundMaterial.DIRT, tnt, 0);
            Ejecta e = of(tnt);
            assertEquals(t.mass(tnt), e.mass + t.mass(tnt) * CloudModel.DUST_LOFTED, 1e-6, "выброс + пыль — воронка");
            assertEquals(e.mass * (1 - BlastTarget.Matter.DIRT.clods), e.grains, 1e-9);
            double prev = e.grains + 1e-9;
            for (double s : new double[]{0.02, 0.1, 0.3, 1, 2, 3, 6, 12}) {
                double[] sum = {0};
                e.jets(s, 77, (x, y, z, ax, ay, az, sigma, kg) -> sum[0] += kg);
                double aloft = e.grainsAloft(s, 77);
                assertEquals(aloft, sum[0], 1e-6 * e.grains, tnt + " кг, " + s + " с: струи — то, что в воздухе");
                assertTrue(aloft <= prev, "в воздухе не прибывает: " + s + " с");
                assertTrue(aloft >= 0 && aloft <= e.grains + 1e-9);
                prev = aloft;
            }
            assertEquals(0, e.grainsAloft(e.life + 0.1, 77), 1e-9, "к концу жизни всё на земле");
        }
    }

    /**
     * Ожидание записки: в 0,1 с у шахеда весь выброс ещё в воздухе (конус растёт из воронки); к 1 с основная масса упала,
     * в воздухе — меньше трети; девять десятых на земле за 1–3 с у всех трёх зарядов.
     */
    @Test
    void curtainFallsBackInSeconds() {
        Ejecta shahed = of(50);
        assertTrue(shahed.grainsAloft(0.1, 1) > 0.9 * shahed.grains, "0,1 с: " + shahed.grainsAloft(0.1, 1) / shahed.grains);
        assertTrue(shahed.grainsAloft(1, 1) < 0.35 * shahed.grains, "1 с: " + shahed.grainsAloft(1, 1) / shahed.grains);
        for (double tnt : CHARGES) {
            Ejecta e = of(tnt);
            assertTrue(e.settled > 0.6 && e.settled < 3, tnt + " кг: 90 % на земле за " + e.settled + " с");
        }
    }

    /** Струи — над землёй (упавшее не рисуется под ней) и не дальше места, куда долетел самый быстрый элемент. */
    @Test
    void jetsStayAboveGroundAndWithinReach() {
        for (double tnt : CHARGES) {
            Ejecta e = of(tnt);
            double reach = e.radius + e.vMax * e.vMax / Ejecta.G;
            for (double s : new double[]{0.05, 0.2, 1, 2.5}) {
                e.jets(s, 5, (x, y, z, ax, ay, az, sigma, kg) -> {
                    assertTrue(y - Math.abs(ay) > -1e-6, "конец струи под землёй: " + (y - Math.abs(ay)));
                    assertTrue(Math.hypot(x, z) + Math.hypot(ax, az) <= reach + 1e-6, "дальше наибольшей дальности");
                    assertTrue(sigma >= 0.25 * e.radius - 1e-9);
                });
            }
        }
    }

    /** Комья вблизи и завеса — один закон: скорость у края воронки — C₂√(gR), быстрее у середины. */
    @Test
    void particlesAndCurtainShareTheLaw() {
        Ejecta e = of(50);
        assertEquals(Ejecta.C2 * Math.sqrt(Ejecta.G * e.radius), e.speed(1), 1e-9);
        assertTrue(e.speed(0.01) > e.speed(0.5));
        assertEquals(e.radius, e.from(1), 1e-9);
    }

    /**
     * Весь баланс разрушенного у каждой цели: куски вблизи ({@link BlastTarget#clods}, частицы) + зёрна завесы (у грунта,
     * {@link Ejecta#grains}) + пыль в воздух ({@link BlastTarget#dust}) = масса чаши или пролома; кусками — не весь объём, а
     * своя доля (ревью фронта F: комья брали весь объём чаши поверх завесы и пыли).
     */
    @Test
    void wholeMassIsSharedOnce() {
        for (double tnt : CHARGES) {
            for (GroundMaterial m : new GroundMaterial[]{GroundMaterial.DIRT, GroundMaterial.SAND, GroundMaterial.GRAVEL, GroundMaterial.STONE,
                    GroundMaterial.BRICK, GroundMaterial.WOOD}) {
                BlastTarget t = BlastTarget.of(m, tnt, 0);
                double grains = t.family() == BlastTarget.Family.SOIL ? new Ejecta(1, t.ballistic(tnt), t.matter().clods).grains : 0;
                assertEquals(t.mass(tnt), t.clods(tnt) + grains + t.dust(tnt), 1e-9 * t.mass(tnt), m + ", " + tnt + " кг");
                assertEquals(t.clods(tnt), t.clodVolume(tnt) * t.matter().density, 1e-9 * t.mass(tnt));
                assertTrue(t.clodVolume(tnt) < t.volume(tnt), m + ": кусками — меньше всей чаши");
            }
            // плита над пустотой: весь пролом без пыли — кусками (завесы нет)
            double r = ua.zentix.incoming.warhead.BreachModel.Material.POOR.radius(tnt, ua.zentix.incoming.warhead.BreachModel.UNTAMPED);
            BlastTarget slab = BlastTarget.of(GroundMaterial.WOOD, false, 0.5, r);
            assertEquals(BlastTarget.Family.SLAB, slab.family());
            assertEquals(slab.mass(tnt), slab.clods(tnt) + slab.dust(tnt), 1e-9 * slab.mass(tnt));
        }
    }
}
