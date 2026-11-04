package ua.zentix.incoming.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.nuclear.IcbmApproaches;
import ua.zentix.incoming.strike.WeaponSpec;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class IcbmArrivalTest {
    private static IcbmArrival path(int id, long end) {
        var d = new Detonation(-1, new Vec3(29_000_000.25, 656, -29_000_000.75), 64, 15, false,
                0, 0, 0, 20_000, 0, 1, false, 0.25f);
        return new IcbmArrival(id, end - 240, end, d, IcbmArrival.velocity(Vec3.ZERO, d.burst(), 25));
    }

    @Test void endpointAndSpeedAreIndependentOfCameraYieldAndEffectsScale() {
        var p = path(1, 1000);
        assertEquals(p.geometry().burst(), p.at(p.end()));
        assertEquals(25, p.at(999).distanceTo(p.at(998)), 1e-7);
        assertEquals(WeaponSpec.NUKE.airframe().gameDive(), WeaponSpec.NUKE.airframe().diveSpeed(0));
        assertEquals(WeaponSpec.NUKE.airframe().article().real().dive() / 20, WeaponSpec.NUKE.airframe().diveSpeed(1), 1e-9);
        assertEquals(0, p.light(p.start()));
        assertTrue(p.light(p.start() + 0.001) < 1e-6);
    }

    @Test void everySupportedDurationRetainsBoostAndTheConfiguredDeadline() {
        for (int duration : new int[]{0, 200, 1800, 72000}) {
            long end = 12345 + duration, start = IcbmArrival.start(12345, end);
            assertTrue(start >= 12345);
            assertTrue(start <= end);
            assertTrue(end - start <= IcbmArrival.WINDOW);
            if (duration >= 200) assertTrue(start >= 12345 + IcbmArrival.BOOST);
        }
    }

    @Test void packetsNeverRewindOrExtrapolateAHeadAndLatePacketsSeek() {
        var p = path(1, 1000);
        var tracks = new IcbmApproaches();
        assertFalse(tracks.supported()); // old recordings keep their original countdown graphic
        tracks.snapshot(990, List.of(p));
        assertTrue(tracks.supported());
        assertEquals(989.5, tracks.time(0.5f));
        tracks.tick();
        assertEquals(990, tracks.time(0));
        for (int i = 0; i < 30; i++) tracks.tick();
        assertEquals(990, tracks.time(0.5f));
        tracks.snapshot(995, List.of(p));
        assertEquals(994.25, tracks.time(0.25f));
        tracks.snapshot(998, List.of(p));
        assertEquals(997.5, tracks.time(0.5f));
        assertEquals(997.5, tracks.time(0));
        tracks.snapshot(800, List.of(p)); // replay seek is explicitly a new timeline
        assertEquals(799.5, tracks.time(0.5f));
        assertTrue(tracks.supported());
    }

    @Test void burstConsumesOnlyItsOwnHeadAndRetainsAFiniteWake() {
        var p = path(1, 1000);
        var tracks = new IcbmApproaches();
        tracks.snapshot(999, List.of(p, path(2, 1001)));
        tracks.finish(1);
        tracks.snapshot(1000, List.of(path(2, 1001)));
        assertEquals(2, tracks.entries().size());
        var first = tracks.entries().iterator().next();
        assertFalse(first.head());
        assertEquals(1000.5, first.time(0, tracks.local(0.5f)));
        for (int i = 0; i < 5; i++) tracks.tick();
        assertEquals(1, tracks.entries().size());
        tracks.snapshot(1000, List.of()); // cancellation at the same epoch still replaces the snapshot
        assertTrue(tracks.entries().isEmpty());
        tracks.reset();
        assertTrue(tracks.entries().isEmpty());
        assertFalse(tracks.supported());
        tracks.snapshot(1001, List.of()); // capability also arrives before a target has ready ground
        assertTrue(tracks.supported());
    }
    @Test void everyBurstGeometryEndsAtItsOwnPointAndOnsetHasNoPrebuiltWake() {
        for (double yield : new double[]{15, 100, 1000}) for (boolean air : new boolean[]{false, true}) {
            double height = air ? ua.zentix.incoming.nuclear.model.Yield.optimalBurstHeight(yield) : 0;
            var g = new Detonation(-1, new Vec3(-29_000_000, 64 + height, 29_000_000), 64, yield, !air,
                    0, 0, 0, 20_000, 0, 1, false, 0.25f);
            var p = new IcbmArrival(1, 760, 1000, g, new Vec3(0, -25, 0));
            assertEquals(g.burst(), p.at(1000));
            assertTrue(p.at(999).y > g.burst().y);
            assertEquals(0, p.light(759));
            assertEquals(0, p.light(760));
            assertEquals(1, p.light(780));
        }
    }
}
