package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.nuclear.model.BlastLitter;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.ChemicalFront;
import static org.junit.jupiter.api.Assertions.*;

class FrontDustTest {
    @Test
    void pressurePhaseEndsTheSupplyButDustSettlesOverSeconds() {
        for (double kg : new double[]{3, 50, 150, 450}) {
            for (double r : new double[]{20, 50, 100}) {
                double phase = BlastModel.chemicalPhaseSeconds(r, kg, true);
                assertEquals(0, FrontDust.envelope(-0.01, phase));
                assertEquals(0, FrontDust.envelope(0, phase));
                assertEquals(0, FrontDust.envelope(1e-10, phase), 1e-6);
                assertTrue(FrontDust.envelope(8 * phase, phase) > 0.8, "no eight-phase deletion");
                assertTrue(FrontDust.envelope(1, phase) > 0.5, "coarse dust remains at one second");
                assertTrue(FrontDust.envelope(3, phase) > 0.15);
                double before = FrontDust.envelope(0.12, phase), after = FrontDust.envelope(0.16, phase);
                assertTrue(after / before > 0.97, "no 35 ms disappearance behind the ring");
                double dt = 1e-5, age = phase + 2;
                double derivative = (FrontDust.envelope(age + dt, phase) - FrontDust.envelope(age - dt, phase)) / (2 * dt);
                assertEquals(-BlastLitter.COVER_DUST_SETTLE / FrontDust.HEIGHT * FrontDust.envelope(age, phase), derivative, 1e-8);
            }
        }
    }

    private record Blob(double x, double y, double z, double h, double v, double dust) {}

    private static Blob emit(FrontDust.Patch p, double age, double radius) {
        Blob[] got = new Blob[1];
        FrontDust.emitPatch(p, p.arrival() + age, radius,
                (x, y, z, ax, ay, az, h, v, soot, dust, water, red, green, blue, kelvin, rise) -> got[0] = new Blob(x, y, z, h, v, dust));
        return got[0];
    }

    @Test
    void expandingAndDriftingPuffsDoNotCreateDustOrEvaporateIt() {
        FrontDust.Patch p = new FrontDust.Patch(30, 64, 0, 30, 0.06, 0.016, 2.4, 6, 0.8, 3, -2, 3);
        assertEquals(0, emit(p, -0.001, 30).dust);
        Blob a = emit(p, 0.2, 100), b = emit(p, 1.2, 500);
        assertEquals(3, b.x - a.x, 1e-12);
        assertEquals(-2, b.z - a.z, 1e-12);
        assertTrue(b.h > a.h);
        // Integrated extinction changes only by deposition, not by the expanding drawn area or a display fade.
        assertEquals(Math.exp(-BlastLitter.COVER_DUST_SETTLE / FrontDust.HEIGHT), b.dust / a.dust, 1e-12);
        assertEquals(FrontDust.COLUMN_TAU * p.area() * p.contrast() * FrontDust.envelope(0.2, p.phase()),
                a.dust * SmokeGrid.dustK(FrontDust.ALBEDO_R, FrontDust.ALBEDO_G, FrontDust.ALBEDO_B), 1e-12);
    }

    /**
     * Клуб у бегущего края, ещё сжатый фронтом, — не непрозрачная точка: толща одного клуба не больше EDGE_TAU, запас
     * площадки он отдаёт по мере роста и весь — когда вырос; середина клуба — не ниже земли и не выше своего σ над ней.
     */
    @Test
    void edgePuffsAreBoundedAndGrowFromTheGround() {
        ChemicalFront front = ChemicalFront.cached(50, true);
        double k = SmokeGrid.dustK(FrontDust.ALBEDO_R, FrontDust.ALBEDO_G, FrontDust.ALBEDO_B);
        for (double r : new double[]{20, 35, 50}) {
            double arrival = front.arrivalSeconds(r), phase = BlastModel.chemicalPhaseSeconds(r, 50, true);
            FrontDust.Patch p = new FrontDust.Patch(r, 64, 0, r, arrival, phase, 3.4, 31, 1, 0, 0, 3);
            double full = 0;
            for (double age = 1e-5; age < 0.5; age *= 1.1) {
                Blob b = emit(p, age, front.radiusAt(arrival + age));
                if (b.dust <= 0) continue;
                double tau = k * b.dust / (2 * Math.PI * b.h * b.v), most = Math.max(FrontDust.EDGE_TAU, FrontDust.fullTau(p.area(), p.contrast(), p.sigma()));
                assertTrue(tau <= most + 1e-9, r + " м, " + age + " с: толща клуба " + tau);
                assertTrue(b.y - 64 >= 0 && b.y - 64 <= b.v + 1e-12, "клуб стоит на земле");
                full = Math.max(full, k * b.dust / (FrontDust.COLUMN_TAU * p.area() * p.contrast() * FrontDust.envelope(age, phase)));
            }
            assertEquals(1, full, 1e-9, r + " м: выросший клуб несёт весь запас площадки");
        }
    }

    /**
     * Цена кадра: площадок не больше {@code PATCHES} (с округлением по кольцам) у любого заряда, шаг поперёк кольца не
     * мельче шага вдоль; площадка, чей клуб уже прозрачнее того, что рисуется, отдаёт пустой клуб, и источник кончается
     * вместе с последней — а не через 20 с оседания до 10⁻⁵.
     */
    @Test
    void patchesAreFewAndStopWhenInvisible() {
        for (double kg : new double[]{3, 18, 50, 150, 450}) {
            ChemicalFront front = ChemicalFront.cached(kg, true);
            double[] ring = FrontLitter.tearRing(front, 1, 0);
            double inner = ring[0], outer = ring[1];
            for (int budget : new int[]{FrontDust.PATCHES, FrontDust.PATCHES / 4}) {
                double arc = FrontDust.arc(inner, outer, budget);
                int rings = FrontDust.rings(inner, outer, budget), count = 0;
                double dr = (outer - inner) / rings;
                for (int j = 0; j < rings; j++) count += FrontDust.spokes(inner + dr * (j + 0.5), arc);
                assertTrue(count <= budget + 6 * rings, kg + " кг: " + count + " площадок при " + budget);
                if (budget == FrontDust.PATCHES) assertTrue(count <= budget, kg + " кг: " + count + " площадок");
                assertTrue(dr <= arc + 1e-9 && arc >= FrontDust.STEP, kg + " кг: шаг поперёк " + dr + ", вдоль " + arc);
            }
        }
        // залп: свободное из общего предела, но не меньше четверти своих
        assertEquals(FrontDust.PATCHES, FrontDust.budget(0));
        assertEquals(FrontDust.ALL_PATCHES - 700, FrontDust.budget(700));
        assertEquals(FrontDust.PATCHES / 4, FrontDust.budget(5 * FrontDust.PATCHES));
        ChemicalFront front = ChemicalFront.cached(50, true);
        double r = 30, arrival = front.arrivalSeconds(r), phase = BlastModel.chemicalPhaseSeconds(r, 50, true);
        FrontDust.Patch p = new FrontDust.Patch(r, 64, 0, r, arrival, phase, 3.4, 31, 1, 0, 0, 3);
        double k = SmokeGrid.dustK(FrontDust.ALBEDO_R, FrontDust.ALBEDO_G, FrontDust.ALBEDO_B);
        Blob before = emit(p, p.until() - arrival - 1e-3, 1e3), after = emit(p, p.until() - arrival + 1e-3, 1e3);
        double tau = k * before.dust / (2 * Math.PI * before.h * before.v);
        assertTrue(tau > 0.5 * FrontDust.FAINTEST && tau <= FrontDust.FAINTEST * 1.01, "у конца клуб уже не рисуется: " + tau);
        assertEquals(0, after.dust, "после — пустой клуб");
        assertTrue(p.until() < arrival + 10, "площадка видна " + (p.until() - arrival) + " с");
    }

    @Test
    void puffsStayBehindTheFrontIncludingTheirLiftAndDrift() {
        ChemicalFront front = ChemicalFront.cached(50, true);
        for (double r : new double[]{20, 50, 100}) {
            double arrival = front.arrivalSeconds(r), phase = BlastModel.chemicalPhaseSeconds(r, 50, true);
            FrontDust.Patch p = new FrontDust.Patch(r, 0, 0, r, arrival, phase, 3.4, 31, 1, 5, 0, 3);
            for (double age = 1e-5; age < 0.1; age *= 1.13) {
                double radius = front.radiusAt(arrival + age);
                Blob b = emit(p, age, radius);
                if (b.dust <= 0) continue;
                assertTrue(Math.sqrt(b.x * b.x + b.y * b.y + b.z * b.z) + 4 * Math.max(b.h, b.v) <= radius + 1e-9);
            }
        }
    }
}
