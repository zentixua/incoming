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
                assertTrue(FrontDust.envelope(phase + FrontDust.SETTLE_SECONDS, phase) < 1.1e-5, "removal only after settling");
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
        FrontDust.Patch p = new FrontDust.Patch(30, 64, 0, 30, 0.06, 0.016, 2, 6, 0.8, 3, -2, 3);
        assertEquals(0, emit(p, -0.001, 30).dust);
        Blob a = emit(p, 0.2, 100), b = emit(p, 1.2, 500);
        assertEquals(3, b.x - a.x, 1e-12);
        assertEquals(-2, b.z - a.z, 1e-12);
        assertTrue(b.h > a.h);
        // Integrated extinction changes only by deposition, not by the expanding drawn area or a display fade.
        assertEquals(Math.exp(-BlastLitter.COVER_DUST_SETTLE / FrontDust.HEIGHT), b.dust / a.dust, 1e-12);
        assertEquals(FrontDust.COLUMN_TAU * p.area() * p.contrast() * FrontDust.envelope(0.2, p.phase()),
                a.dust * SmokeGrid.dustK(0.82f, 0.76f, 0.62f), 1e-12);
    }

    @Test
    void puffsStayBehindTheFrontIncludingTheirLiftAndDrift() {
        ChemicalFront front = ChemicalFront.cached(50, true);
        for (double r : new double[]{20, 50, 100}) {
            double arrival = front.arrivalSeconds(r), phase = BlastModel.chemicalPhaseSeconds(r, 50, true);
            FrontDust.Patch p = new FrontDust.Patch(r, 0, 0, r, arrival, phase, 2.4, 5, 1, 5, 0, 3);
            for (double age = 1e-5; age < 0.1; age *= 1.13) {
                double radius = front.radiusAt(arrival + age);
                Blob b = emit(p, age, radius);
                if (b.dust <= 0) continue;
                assertTrue(Math.sqrt(b.x * b.x + b.y * b.y + b.z * b.z) + 4 * Math.max(b.h, b.v) <= radius + 1e-9);
            }
        }
    }
}
