package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.strike.StrikeWorld;

import java.util.List;

@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class Review723DustProbeGameTests {
    private Review723DustProbeGameTests() {}

    @GameTest(template = "range", batch = "review723_dust", timeoutTicks = 6000, skyAccess = true)
    public static void everyCollapsedHouseReceivesDustOverItsFootprint(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // The town is wider than the template. Keep it beyond the GameTest grid in a normal shard run.
        BlockPos origin = h.absolutePos(BlockPos.ZERO).west(1600);
        List<CollapseGameTests.SalvoHouse> town = CollapseGameTests.salvoTown(h, origin, true);
        var watcher = FuelFireGameTests.Watcher.near(h, town.get(town.size() / 2).target(), S2C.Collapse.class);
        CollapseGameTests.strikeSalvo(level, town);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(StrikeWorld.get(level).idle(), "salvo unfinished"))
                .thenExecute(() -> verify(h, town, watcher.got))
                .thenSucceed();
    }

    private static void verify(GameTestHelper h, List<CollapseGameTests.SalvoHouse> town, List<S2C.Collapse> packets) {
        int collapsed = 0, missing = 0, received = 0, offFootprint = 0;
        double volume = 0;
        boolean[] covered = new boolean[town.size()];
        ChunkPos first = new ChunkPos(town.getFirst().corner());
        ChunkPos last = new ChunkPos(town.getLast().corner());
        for (S2C.Collapse packet : packets) {
            // Keep the town and its neighboring chunks, excluding residual effects from other fixtures.
            ChunkPos chunk = new ChunkPos(BlockPos.containing(packet.pos()));
            if (chunk.x < first.x - 1 || chunk.x > last.x + 1 || chunk.z < first.z - 1 || chunk.z > last.z + 1) continue;
            h.assertTrue(packet.volume() > 0, "dust volume must be positive");
            received++;
            volume += packet.volume();
            int house = 0;
            while (house < town.size() && !town.get(house).contains(packet.pos())) house++;
            if (house == town.size()) offFootprint++;
            else covered[house] = true;
        }
        for (int i = 0; i < town.size(); i++) {
            var house = town.get(i);
            if (house.fallenUpperBlocks(h.getLevel()) * 2 <= house.upper().size()) continue;
            collapsed++;
            if (!covered[i]) missing++;
        }
        Incoming.LOG.info("COLLAPSE DUST salvo: {} houses collapsed, {} missing dust, {} of {} packets outside house footprints, {} m³ total",
                collapsed, missing, offFootprint, received, volume);
        h.assertTrue(collapsed == town.size(), "expected all " + town.size() + " houses to collapse, got " + collapsed);
        h.assertTrue(missing == 0 && offFootprint == 0,
                missing + "/" + collapsed + " collapsed houses missed dust over their footprint, with " + offFootprint + " packets outside");
    }
}
