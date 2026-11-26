package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.util.Terrain;

/**
 * Every player gets the level's sea level again every {@code Terrain.SEA_RESEND} ticks, not only on login: a Flashback
 * recording started mid-session holds it within that time. The value is the level's ({@code Terrain.sea}).
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SeaLevelGameTests {
    private SeaLevelGameTests() {}

    @GameTest(template = "range", timeoutTicks = Terrain.SEA_RESEND + 100, batch = "sea_level_resend", skyAccess = true)
    public static void seaLevelIsResentToPlayers(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FuelFireGameTests.Watcher<S2C.SeaLevel> watcher = FuelFireGameTests.Watcher.near(h, Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 12, 32))), S2C.SeaLevel.class);
        int sea = Terrain.sea(level);
        h.succeedWhen(() -> {
            h.assertFalse(watcher.got.isEmpty(), "no sea level packet yet");
            h.assertTrue(watcher.got.getLast().y() == sea, "sent " + watcher.got.getLast() + ", the level's is " + sea);
        });
    }
}
