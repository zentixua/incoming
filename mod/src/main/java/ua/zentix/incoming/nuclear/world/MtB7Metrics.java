package ua.zentix.incoming.nuclear.world;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.compat.MtB7Log;
import ua.zentix.incoming.registry.ModAttachments;

/** TEST ONLY. Passive timings, no world loads, zone walks or ledger snapshots. Never ship. */
public final class MtB7Metrics {
    private static MtB7Log ticks, progress, saves;
    private static final AtomicLong SAVE_IDS = new AtomicLong();
    private static long began, wall;
    private MtB7Metrics() {}
    public static void start() {
        if (!Boolean.getBoolean("incoming.mtB7")) return;
        try {
            ticks = new MtB7Log(MtB7Log.ROOT.resolve("ticks.csv"),
                    "start_wall_ms,start_mono_ns,end_wall_ms,end_mono_ns,tick,work_ns,heap_used,heap_committed,heap_max,log_drops");
            progress = new MtB7Log(MtB7Log.ROOT.resolve("progress.log"), "# MT-B7 v2: cumulative elapsed times overlap; chunk_save_events are NOT disk completions");
            saves = new MtB7Log(MtB7Log.ROOT.resolve("ledger.csv"),
                    "wall_ms,mono_ns,id,ledger,phase,elapsed_ns,success");
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, MtB7Metrics::before);
            NeoForge.EVENT_BUS.addListener(MtB7Stages::chunkSave);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, MtB7Metrics::after);
        } catch (IOException error) { Incoming.LOG.error("MT-B7 metrics initialization failed", error); }
    }
    static long saveId() { return saves == null ? 0 : SAVE_IDS.incrementAndGet(); }
    static void save(long id, LedgerData ledger, String phase, long elapsed, boolean success) {
        if (id != 0) saves.add(System.currentTimeMillis()+","+System.nanoTime()+","+id+","+ledger.getClass().getSimpleName()+","+phase+","+elapsed+","+success);
    }
    private static void before(ServerTickEvent.Pre event) { began = System.nanoTime(); wall = System.currentTimeMillis(); }
    private static void after(ServerTickEvent.Post event) {
        if (ticks == null || progress == null) return;
        long now = System.nanoTime(); var server = event.getServer(); var rt = Runtime.getRuntime();
        ticks.add(wall+","+began+","+System.currentTimeMillis()+","+now+","+server.getTickCount()+","+(now-began)+","+(rt.totalMemory()-rt.freeMemory())+","+rt.totalMemory()+","+rt.maxMemory()+","+(ticks.dropped.get()+progress.dropped.get()+saves.dropped.get()));
        if (server.getTickCount()%20 != 0) return;
        int dimensions = 0;
        for (var level : server.getAllLevels()) {
            if (++dimensions > 8) { progress.add("dimension_limit tick="+server.getTickCount()); break; }
            // Do not call NuclearWorld.get: a diagnostic must not poll/admit cold ledgers.
            if (!level.hasData(ModAttachments.NUCLEAR_WORLD)) continue;
            var world = level.getData(ModAttachments.NUCLEAR_WORLD);
            progress.add("wall_ms="+System.currentTimeMillis()+" mono_ns="+System.nanoTime()+" tick="+server.getTickCount()
                    +" dimension="+level.dimension().location()+" game_time="+level.getGameTime()
                    +" lanes_ns="+Arrays.toString(world.lastNanos())+" queued="+world.queuedChunks()
                    +" ruins="+Arrays.toString(world.ruinStats())
                    +" hash_queued_bytes="+ua.zentix.incoming.compat.MtB7Columns.serverBytes.get()
                    +" hash_drops="+ua.zentix.incoming.compat.MtB7Columns.serverDrops.get()
                    +" material_reservation="+world.materialReservation+" prep="+world.prep().mtB7Progress()
                    +" "+MtB7Stages.snapshot());
        }
    }
}
