package ua.zentix.incoming.nuclear.world;

/** Private bootstrap before GameTest installs its relative deadlines and onEachTick callbacks. */
@net.neoforged.fml.common.EventBusSubscriber(modid = "incoming")
public final class ReplayEpoch {
    private static boolean set;
    @net.neoforged.bus.api.SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void firstTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        var server = event.getServer();
        if (set || !(server instanceof net.minecraft.gametest.framework.GameTestServer)) return;
        set = true;
        ((net.minecraft.world.level.storage.ServerLevelData) server.overworld().getLevelData()).setGameTime(2_040_660_000L);
        server.overworld().random.setSeed(57263017L);
        ua.zentix.incoming.Incoming.LOG.info("DENSE_REPLAY bootstrap epoch 2040660000 and grid seed 57263017 before test registration");
    }
}
