package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Air;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Review probe (resend, round 1, minor): the list reaches the client before the clock packet of the new world
 * ({@code StrikeClouds} listeners are registered before {@code NuclearStrikes}'), and {@code ClientClock.counted} is a
 * static kept across worlds and dimensions. One ledger tick with the previous world's larger counted jumps drops every
 * listed cloud for good, though the right value comes a tick later.
 */
class ClientStrikeCloudsStaleClockProbeTest {
    @Test
    void aStaleCountedValueDoesNotDropTheList() {
        ClientStrikeClouds.Ledger ledger = new ClientStrikeClouds.Ledger();
        List<S2C.StrikeClouds.Cloud> built = new ArrayList<>();
        S2C.Blast b = new S2C.Blast(S2C.Blast.DRONE, new Vec3(100, 64, -40), 3, 64, 1, Optional.empty(), WeaponType.DRONE.id());
        // this world: no jumps; the strike ten minutes ago
        ledger.sync(List.of(new S2C.StrikeClouds.Cloud(S2C.StrikeClouds.BLAST, 1000, 0, Air.CALM, Float.NaN, b)), 13_000);
        // first tick: the client still holds the previous world's counted jumps (two nights slept there)
        ledger.tick(13_000, 2 * 12_000, c -> false, c -> 0, (c, t) -> built.add(c), () -> 0L);
        // the new world's clock arrives
        ledger.tick(13_001, 0, c -> true, c -> 0, (c, t) -> built.add(c), () -> 0L);
        assertEquals(1, built.size(), "the cloud was dropped by a stale clock");
    }
}
