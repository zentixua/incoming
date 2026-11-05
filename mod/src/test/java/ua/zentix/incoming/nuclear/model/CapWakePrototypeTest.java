package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Semantic checks for an isolated prototype, not acceptance of nuclear cloud opacity or motion. */
class CapWakePrototypeTest {
    private static CapWakePrototype wake(double yield, double beta, double lag, int bins) {
        return new CapWakePrototype(yield, 240 * Math.cbrt(yield), false,
                new CapWakePrototype.Closure(beta, lag), bins);
    }

    @Test
    void capAndWakeConserveOneFiniteInventoryAcrossTheSensitivityEnvelope() {
        for (double yield : new double[]{1, 5, 15, 1000}) {
            for (double beta : new double[]{0, 0.03, 0.1, 0.2}) {
                for (double lag : new double[]{0, 0.25, 0.5, 0.75}) {
                    CapWakePrototype model = wake(yield, beta, lag, 128);
                    double previousCap = 1;
                    for (double t : new double[]{0, 5, 15, 30, 60, 120, 300, 900}) {
                        var snapshot = model.at(t, 3, -4);
                        assertEquals(1, snapshot.capInventory() + snapshot.wakeInventory(), 2e-14,
                                "released material is removed from its source, never duplicated");
                        assertEquals(snapshot.dropletBudget(), snapshot.capDroplets() + snapshot.wakeDroplets(),
                                1e-12 * Math.max(1, snapshot.dropletBudget()),
                                "droplet condensation uses the existing cap budget, not an extra full-density wake");
                        assertTrue(snapshot.capInventory() >= 0 && snapshot.capInventory() <= previousCap);
                        previousCap = snapshot.capInventory();
                        assertTrue(snapshot.slabs().size() <= 128);
                        for (var slab : snapshot.slabs()) {
                            assertTrue(slab.mass() > 0 && slab.mass() <= 1);
                            assertTrue(slab.droplets() >= 0);
                            assertTrue(slab.top() > slab.bottom() && slab.radius() > 0);
                            assertTrue(Double.isFinite(slab.crossDepthPerInventory()));
                        }
                    }
                    assertEquals(model.at(model.stopsAt(), 0, 0).wakeInventory(),
                            model.at(1e6, 0, 0).wakeInventory(), 0, "no indefinite emitter after cap stabilization");
                    assertEquals(model.at(model.stopsAt(), 0, 0).dropletBudget(),
                            model.at(1e6, 0, 0).dropletBudget(), 0, "the condensate source also remains finite");
                }
            }
        }
    }

    @Test
    void releaseIsCausalAndAQueryNeverBorrowsTheRestOfItsTimeBin() {
        CapWakePrototype model = wake(15, 0.1, 0, 128);
        assertEquals(0, model.at(-1, 0, 0).capInventory(), 0);
        assertEquals(0, model.at(-1, 0, 0).wakeInventory(), 0);
        for (double t : new double[]{0, Math.nextDown(model.startsAt()), model.startsAt()})
            assertTrue(model.at(t, 0, 0).slabs().isEmpty());
        double justAfter = model.startsAt() + 1e-6;
        var first = model.at(justAfter, 0, 0);
        assertTrue(first.wakeInventory() > 0 && first.wakeInventory() < 1e-6,
                "a partial first interval must not emit its future mass");
        assertEquals(justAfter, first.slabs().getLast().to(), 0);
        for (double t : new double[]{15, 30, 60, 120}) {
            var snapshot = model.at(t, 0, 0);
            for (var slab : snapshot.slabs()) {
                assertTrue(slab.from() >= model.startsAt() && slab.to() <= t);
                assertTrue(slab.bottom() > 240 * Math.cbrt(15), "born above burst height, never on the ground");
            }
            assertEquals(CloudModel.capBottom(t, 240 * Math.cbrt(15), 15, false), snapshot.top(), 1e-10,
                    "newly released material starts at the current cap; the oldest material sets the lower edge");
        }
    }

    @Test
    void noDetrainmentMeansNoWakeAndRiseChoiceMovesExistingMassRatherThanCreatingIt() {
        assertTrue(wake(15, 0, 0, 128).at(120, 0, 0).slabs().isEmpty());
        double previousBottom = 0, mass = -1;
        for (double lag : new double[]{0, 0.25, 0.5, 0.75}) {
            var snapshot = wake(15, 0.1, lag, 128).at(120, 0, 0);
            if (mass < 0) mass = snapshot.wakeInventory();
            assertEquals(mass, snapshot.wakeInventory(), 0);
            assertTrue(snapshot.bottom() > previousBottom);
            previousBottom = snapshot.bottom();
        }
    }

    @Test
    void windAdvectsReleasedMaterialWithoutChangingItsMassOrVerticalGeometry() {
        CapWakePrototype model = wake(5, 0.1, 0.5, 128);
        var calm = model.at(60, 0, 0);
        var wind = model.at(60, 3, -4);
        assertEquals(calm.wakeInventory(), wind.wakeInventory(), 0);
        for (int i = 0; i < calm.slabs().size(); i++) {
            var a = calm.slabs().get(i); var b = wind.slabs().get(i);
            assertEquals(a.bottom(), b.bottom(), 0);
            assertEquals(a.top(), b.top(), 0);
            assertEquals(a.mass(), b.mass(), 0);
            assertEquals(0, a.x(), 0);
            assertEquals(0, a.z(), 0);
            assertTrue(b.x() > 0 && b.z() < 0);
            assertEquals(-4.0 / 3, b.z() / b.x(), 1e-14);
            // Independent trapezoidal integral of the prescribed wind over the parcel's actual height path.
            double birth = (b.from() + b.to()) / 2, h0 = CloudModel.capBottom(birth, 240 * Math.cbrt(5), 5, false);
            double end = CloudModel.end(240 * Math.cbrt(5), 5, false);
            double expected = birth * h0 / end, dt = (60 - birth) / 1000;
            for (int n = 0; n < 1000; n++) {
                double ta = birth + n * dt, tb = birth + (n + 1) * dt;
                double ha = h0 + 0.5 * (CloudModel.capBottom(ta, 240 * Math.cbrt(5), 5, false) - h0);
                double hb = h0 + 0.5 * (CloudModel.capBottom(tb, 240 * Math.cbrt(5), 5, false) - h0);
                expected += dt * (ha + hb) / (2 * end);
            }
            assertEquals(expected * 3, b.x(), 1e-5);
        }
    }

    @Test
    void replayIsPureAndResolutionChangesDoNotChangeSupplyOrExtent() {
        CapWakePrototype model = wake(15, 0.1, 0.25, 128);
        var at60 = model.at(60, 4, 2);
        model.at(300, 4, 2);
        assertEquals(at60, model.at(60, 4, 2));
        assertThrows(UnsupportedOperationException.class, () -> at60.slabs().clear());
        var coarse = wake(15, 0.1, 0.25, 64).at(120, 4, 2);
        var fine = wake(15, 0.1, 0.25, 256).at(120, 4, 2);
        assertEquals(coarse.wakeInventory(), fine.wakeInventory(), 2e-14);
        assertEquals(coarse.bottom(), fine.bottom(), 1e-10);
        assertEquals(coarse.top(), fine.top(), 1e-10);
        var middle = model.at(120, 4, 2);
        assertTrue(Math.abs(middle.meanHeight() - fine.meanHeight()) < Math.abs(coarse.meanHeight() - fine.meanHeight()),
                "midpoint placement converges as the release intervals shrink");
        assertEquals(fine.meanHeight(), middle.meanHeight(), 0.002 * (fine.top() - fine.bottom()),
                "128 intervals locate the inventory mean within 0.2% of its extent relative to 256 intervals");
    }
}
