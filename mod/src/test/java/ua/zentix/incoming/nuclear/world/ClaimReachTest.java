package ua.zentix.incoming.nuclear.world;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Claim protection's volume at 15 kt (roadmap B2): the light trace and the ruin zone (which settles on disk after the
 * wave) each reach hundreds of thousands of chunks; claim handlers are asked only for chunks in memory that a write
 * changes ({@code claim/Claims}), in the same work unit as the write, so the work follows the positions written, never
 * the reach.
 */
class ClaimReachTest {
    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.Map.of());
        Bootstrap.bootStrap();
    }

    private static final Detonation KT15 = new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, 0, 0, 8, 20_000, 7, 1f, false, 0.35f);

    @Test
    void fifteenKilotonReach() {
        double scorch = RuinPlanner.scorchReach(KT15), ruins = KT15.ruinRadius();
        long scorchChunks = chunksWithin(scorch), ruinChunks = chunksWithin(ruins);
        System.out.printf(java.util.Locale.ROOT, "CLAIMREACH 15 kt: light trace %.0f blocks, %d chunks; ruins %.0f blocks, %d chunks%n", scorch, scorchChunks, ruins, ruinChunks);
        assertTrue(scorchChunks > 100_000, "light trace reach " + scorchChunks + " chunks");
        assertTrue(ruinChunks >= scorchChunks, "ruin reach " + ruinChunks + " chunks inside the trace's " + scorchChunks);
    }

    /** Chunks whose nearest point lies within {@code r} blocks of the origin. */
    private static long chunksWithin(double r) {
        int n = (int) Math.ceil(r / 16) + 1;
        long count = 0;
        for (int x = -n; x <= n; x++) {
            for (int z = -n; z <= n; z++) {
                double dx = Math.max(0, Math.max(x * 16 - 0.0, -(x * 16 + 16.0))), dz = Math.max(0, Math.max(z * 16 - 0.0, -(z * 16 + 16.0)));
                if (dx * dx + dz * dz <= r * r) count++;
            }
        }
        return count;
    }
}
