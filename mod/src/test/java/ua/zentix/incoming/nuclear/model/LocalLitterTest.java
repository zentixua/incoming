package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocalLitterTest {
    @Test
    void oneReleaseStartsAtArrivalAndStopsGrowingAfterTheMillisecondPhase() {
        var shock = ShockAtBody.chemical(50, true, 30);
        var surface = DustModel.Surface.FIELD;
        double terminal = BlastLitter.COVER_DUST_SETTLE;
        var before = LocalLitter.at(shock, surface, 1, terminal, Math.nextDown(shock.arrivalSeconds()));
        assertEquals(0, before.massKgPerM2());
        assertEquals(0, before.releaseShare());
        assertEquals(0, before.horizontalMps());
        assertEquals(0, before.verticalMps());
        assertEquals(0, before.airDisplacementM());
        var full = LocalLitter.lift(shock, surface, 1, terminal);
        assertTrue(full.massKgPerM2() > 0 && full.massKgPerM2() <= surface.supply());
        assertTrue(full.releaseShare() > 0 && full.releaseShare() <= 1);
        assertTrue(full.horizontalMps() > 0 && full.horizontalMps() < shock.windMetresPerSecond());
        assertEquals(shock.maxAirDisplacementM() / Math.E, full.airDisplacementM(), 1e-12);
        assertTrue(full.airDisplacementM() < 1, "local wind is centimetres of travel, not a long gale");
        var end = LocalLitter.at(shock, surface, 1, terminal, shock.arrivalSeconds() + shock.positiveSeconds());
        assertEquals(full.massKgPerM2(), end.massKgPerM2(), 1e-12);
        assertEquals(full.airDisplacementM(), end.airDisplacementM(), 1e-12);
        assertEquals(full.airDisplacementM(), LocalLitter.at(shock, surface, 1, terminal, 60).airDisplacementM(), 1e-12);
        double previous = 0;
        for (int i = 0; i <= 100; i++) {
            var part = LocalLitter.at(shock, surface, 1, terminal, shock.arrivalSeconds() + shock.positiveSeconds() * i / 100);
            assertTrue(part.massKgPerM2() >= previous);
            assertTrue(part.airDisplacementM() <= shock.maxAirDisplacementM());
            previous = part.massKgPerM2();
        }
    }

    @Test
    void noAdditionalDustMassIsReleasedAfterThePositivePhase() {
        for (double[] c : new double[][]{{50, 30}, {150, 50}, {3, 10}}) {
            var shock = ShockAtBody.chemical(c[0], true, c[1]);
            double terminal = BlastLitter.COVER_DUST_SETTLE;
            double fullMass = LocalLitter.lift(shock, DustModel.Surface.FIELD, 1, terminal).massKgPerM2();
            assertTrue(fullMass > 1e-6, "fixture must release measurable dust mass");
            double previousMass = LocalLitter.at(shock, DustModel.Surface.FIELD, 1, terminal,
                    shock.arrivalSeconds() + shock.positiveSeconds()).massKgPerM2();
            for (double phases : new double[]{1.01, 1.25, 2, 4, 10, 60}) {
                double mass = LocalLitter.at(shock, DustModel.Surface.FIELD, 1, terminal,
                        shock.arrivalSeconds() + phases * shock.positiveSeconds()).massKgPerM2();
                assertEquals(fullMass, mass, fullMass * 1e-10, "dust mass still grows after t+ at " + phases + " phases");
                assertEquals(0, mass - previousMass, fullMass * 1e-10, "a later frame must not emit more dust mass");
                previousMass = mass;
            }
        }
    }

    @Test
    void materialThresholdIsSharedAndHasNoJumpInCountOrVelocity() {
        for (var surface : new DustModel.Surface[]{DustModel.Surface.FIELD, DustModel.Surface.COVERED, DustModel.Surface.HARD}) {
            double lo = 0, hi = 1000;
            for (int i = 0; i < 80; i++) {
                double p = (lo + hi) / 2;
                if (BlastLitter.friction(p, 1) > surface.threshold()) hi = p;
                else lo = p;
            }
            double previousTravel = -1;
            for (double p : new double[]{lo * (1 - 1e-8), hi * (1 + 1e-8)}) {
                var shock = new ShockAtBody(0, 1000 * p, 0.02, 100, 1000 * BlastModel.dynamicPressureKpa(p), BlastModel.windSpeed(p), 0);
                var lift = LocalLitter.lift(shock, surface, 1, 1.7);
                assertTrue(lift.releaseShare() < 1e-6);
                assertTrue(lift.massKgPerM2() < 1e-6);
                assertTrue(lift.horizontalMps() < 1e-6);
                assertTrue(lift.verticalMps() < 1e-6);
                assertEquals(shock.maxAirDisplacementM() / Math.E, lift.airDisplacementM(), 1e-12);
                if (previousTravel >= 0) assertEquals(previousTravel, lift.airDisplacementM(), 1e-7);
                previousTravel = lift.airDisplacementM();
            }
        }
    }

    @Test
    void releaseWeakensWithRangeAndNoTangentialWindLiftsNothing() {
        var surface = DustModel.Surface.COVERED;
        var previous = LocalLitter.lift(ShockAtBody.chemical(50, true, 20), surface, 1, 1.7);
        for (double r = 21; r < 1000; r += 1) {
            var lift = LocalLitter.lift(ShockAtBody.chemical(50, true, r), surface, 1, 1.7);
            assertTrue(lift.massKgPerM2() <= previous.massKgPerM2());
            assertTrue(lift.releaseShare() <= previous.releaseShare());
            assertTrue(lift.horizontalMps() <= previous.horizontalMps());
            previous = lift;
        }
        var none = LocalLitter.lift(ShockAtBody.chemical(50, true, 30), surface, 0, 1.7);
        assertEquals(0, none.releaseShare());
        assertEquals(0, none.horizontalMps());
        assertEquals(0, none.massKgPerM2());
    }
}
