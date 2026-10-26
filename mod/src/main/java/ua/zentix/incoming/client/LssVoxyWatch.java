package ua.zentix.incoming.client;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnConsumer;
import dev.vox.lss.api.VoxelColumnData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import ua.zentix.incoming.Incoming;

/**
 * Chunks LOD Server Support writes to Voxy: it would overwrite one sent by the mod with the server's disk chunk
 * (no distant ruins there), {@link VoxyIngest#overwritten}. Only the public LSS API
 * ({@code LSSApi.registerColumnConsumer}, MIT); class loads only with LSS installed and subscribes only when the
 * mod writes chunks to Voxy (a subscriber without Voxy would make LSS download LODs just for us).
 */
final class LssVoxyWatch {
    private LssVoxyWatch() {}

    static void subscribe() {
        try {
            LSSApi.registerColumnConsumer(new VoxelColumnConsumer() {
                @Override
                public void onVoxelColumnReceived(ClientLevel level, ResourceKey<Level> dimension, int chunkX, int chunkZ, VoxelColumnData columnData) {
                    VoxyIngest.overwritten(level, chunkX, chunkZ);
                }
            });
        } catch (LinkageError e) {
            Incoming.LOG.warn("LOD Server Support: its API differs ({}) — a far chunk it overwrites in Voxy stays as on the server's disk", e.toString());
        }
    }
}
