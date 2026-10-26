package ua.zentix.incoming.client.flight;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.net.S2C;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InterceptorTrackTest {
    private static S2C.FarFlight sample(UUID id, int age, boolean ended) {
        return new S2C.FarFlight(id, S2C.FarFlight.INTERCEPTOR, false, false, false, new Vec3(0, 150, -age * 14),
                new Vec3(0, 0, -14), 180, 0, 0, FlightPhase.CRUISE.ordinal(), age, new Vec3(0, 150, -10000), Float.NaN, ended);
    }

    @Test
    void farSamKeepsItsIdentityAcrossPacketGapsAndEndsAtTheBurst() {
        UUID id = UUID.randomUUID();
        try {
            FlightTracks.receive(sample(id, 31, false), 100);
            FlightTrack t = FlightTracks.interceptor(id);
            assertNotNull(t);
            assertNull(t.weapon, "this is SAM, not Shahed with an unknown id weapon");
            Map<UUID, FlightTrack> tracks = new HashMap<>();
            tracks.put(id, t);
            FlightTracks.expire(tracks, 103);
            assertFalse(t.isDead(), "between packets every four ticks the path does not break");
            FlightTracks.receive(sample(id, 35, false), 104);
            assertSame(t, FlightTracks.interceptor(id), "one chain of smoke after the entity leaves");
            assertTrue(t.covers(102), "the path between the packages is restored");
            FlightTracks.receive(sample(id, 37, true), 106);
            assertTrue(t.isDead());
            assertEquals(106, t.deathTick(), "end by break message, not after timeout");
            FlightTracks.receive(sample(id, 36, false), 107);
            assertSame(t, FlightTracks.interceptor(id), "delayed sample does not create new smoke after breaking");
            assertTrue(t.isDead());
        } finally {
            FlightTracks.reset();
        }
    }
}
