package ua.zentix.incoming.client;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnConsumer;
import dev.vox.lss.api.VoxelColumnData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.compat.MtB7Columns;
import ua.zentix.incoming.compat.MtB7Log;

/** TEST ONLY. Loaded only with LSS and Voxy; observes the public callback without changing its data. */
final class MtB7Lss {
    private MtB7Lss() {}
    static void subscribe() {
        try {
            LSSApi.registerColumnConsumer(new VoxelColumnConsumer() {
                @Override public void onVoxelColumnReceived(ClientLevel level, ResourceKey<Level> dimension,
                        int x, int z, VoxelColumnData data) {
                    if (!MtB7Log.beginObservation()) return;
                    try {
                        long scope = MtB7Client.scope(level);
                        if (scope < 0) { MtB7Client.omitted(); return; }
                        long began = System.nanoTime(), wall = System.currentTimeMillis();
                        long[] hashes = MtB7Columns.empty();
                        for (var section : data.sections()) {
                            int index = section.sectionY() + 8;
                            if (index >= 0 && index < hashes.length) hashes[index] = MtB7Columns.hash(section.section());
                        }
                        MtB7Client.receipt(level, scope, x, z, wall, began, System.nanoTime()-began, hashes);
                    } catch (RuntimeException error) { MtB7Client.omitted(); }
                    finally { MtB7Log.endObservation(); }
                }
            });
            Incoming.LOG.info("MT-B7: observing natural LSS receipt hashes through its public consumer API");
        } catch (LinkageError error) {
            Incoming.LOG.warn("MT-B7 natural LSS observer unavailable: {}", error.toString());
        }
    }
}
