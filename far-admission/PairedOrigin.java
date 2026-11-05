package ua.zentix.incoming.nuclear.world;

/** Disposable measurement control; never part of the mod or committed test suite. */
@net.neoforged.fml.common.EventBusSubscriber(modid = "incoming")
public final class PairedOrigin {
    private PairedOrigin() {}
    @net.neoforged.bus.api.SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void firstTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        var server = event.getServer();
        if (server instanceof net.minecraft.gametest.framework.GameTestServer && server.getTickCount() == 1) {
            // GameTestServer.startTests is called immediately after super.tickServer returns.
            // Native GameTest WorldOptions already use seed zero. Only the test-grid choice is seeded.
            server.overworld().random.setSeed(65439824719800L);
            ua.zentix.incoming.Incoming.LOG.info("PAIRED_ORIGIN first native tick seed 65439824719800; physical/world rules unchanged");
        }
    }
}
