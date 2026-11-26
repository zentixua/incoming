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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review probe (resend, round 1): a Flashback seek back to a position before the recording's first list. The snapshot holds
 * no {@code S2C.StrikeClouds}, so the ledger keeps the list read at the old position, and every strike in it lies after the
 * new position. Those strikes come live when playback reaches them (the recorded packets, flushed at the replay server's
 * end-of-tick marker), so the ledger must never build them itself: the client's game time reaches a strike's tick before
 * or after its marker hands the live packet over, and a build at that tick plus the live packet's own build draw the cloud
 * twice (same seed, same place: double density for its whole life).
 */
class ClientStrikeCloudsSeekProbeTest {
    private final ClientStrikeClouds.Ledger ledger = new ClientStrikeClouds.Ledger();
    private final List<S2C.StrikeClouds.Cloud> built = new ArrayList<>();

    private static S2C.Blast blast(int i) {
        return new S2C.Blast(S2C.Blast.DRONE, new Vec3(100 + i, 64, -40), 3, 64, i, Optional.empty(), WeaponType.DRONE.id());
    }

    private static S2C.StrikeClouds.Cloud cloud(long born, S2C.Blast b) {
        return new S2C.StrikeClouds.Cloud(S2C.StrikeClouds.BLAST, born, 0, Air.CALM, Float.NaN, b);
    }

    private void tick(long now) {
        ledger.tick(now, 0, c -> true, c -> 0, (c, ticks) -> built.add(c), () -> 0L);
    }

    @Test
    void aStrikeAfterTheSeekPositionIsLeftToItsLivePacket() {
        S2C.Blast early = blast(1), late = blast(2);
        // live playback: both strikes seen live, then the list that holds them
        ledger.live(early, 1000);
        ledger.live(late, 3000);
        ledger.sync(List.of(cloud(1000, early), cloud(3000, late)), 3020);
        // seek back to 500, before the first strike: no list in the snapshot, only the reset runs
        ledger.rewound(500);
        // playback reaches the first strike's tick; its live packet waits for the replay marker of that tick
        for (long t = 500; t <= 1000; t++) tick(t);
        assertTrue(built.isEmpty(), "built from the stale list before its live packet: " + built);
        ledger.live(early, 1000);
        for (long t = 1001; t <= 3000; t++) tick(t);
        assertEquals(List.of(), built, "a strike after the seek position was built by the ledger as well as live");
    }
}
