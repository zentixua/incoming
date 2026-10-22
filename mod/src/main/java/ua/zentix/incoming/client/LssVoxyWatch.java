package ua.zentix.incoming.client;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnConsumer;
import dev.vox.lss.api.VoxelColumnData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import ua.zentix.incoming.Incoming;

/**
 * Какие чанки LOD Server Support кладёт в Voxy: тот, что прислал мод, он переписал бы чанком с диска сервера (руин
 * вдали там нет) — {@link VoxyIngest#overwritten}. Только открытое API LSS ({@code LSSApi.registerColumnConsumer}, MIT);
 * класс грузится, только если LSS стоит, и подписывается, только если мод кладёт чанки в Voxy (подписчик без Voxy
 * заставил бы LSS качать LOD для одного нас).
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
