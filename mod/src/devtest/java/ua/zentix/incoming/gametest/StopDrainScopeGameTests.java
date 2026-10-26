package ua.zentix.incoming.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.util.StopDrain;

import java.util.Comparator;
import java.util.List;

@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StopDrainScopeGameTests {
    private static final TicketType<ChunkPos> OTHER = TicketType.create("external_test_load", Comparator.comparingLong(ChunkPos::toLong));

    private StopDrainScopeGameTests() {}

    /** A busy vanilla/other-mod load alone must not make Incoming poll tasks or wait at shutdown. */
    @GameTest(template = "range", timeoutTicks = 100, batch = "stop_drain_scope", skyAccess = true)
    public static void unrelatedGenerationIsNotDrained(GameTestHelper h) {
        var level = h.getLevel().getServer().getLevel(Level.END);
        h.assertTrue(level != null, "no End");
        var cache = level.getChunkSource();
        ChunkPos pos = new ChunkPos(40_000, 40_000);
        cache.addRegionTicket(OTHER, pos, 0, pos);
        try {
            cache.tick(() -> false, false);
            var holder = cache.chunkMap.getUpdatingChunkIfPresent(pos.toLong());
            h.assertTrue(holder != null && holder.getGenerationRefCount() > 0, "external load did not start generation");
            h.assertTrue(StopDrain.inFlight(level) == 0, "external generation counted as Incoming work");
            h.assertTrue(StopDrain.drain(List.of(level), Long.MIN_VALUE), "idle Incoming waited for an external load");
            h.assertTrue(holder.getGenerationRefCount() > 0, "Incoming polled unrelated generation");
        } finally {
            // Finish this test's external request so vanilla's own shutdown has no canceled generation to await.
            level.getChunk(pos.x, pos.z);
            cache.removeRegionTicket(OTHER, pos, 0, pos);
        }
        h.succeed();
    }
}
