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
    private static final AtomicLong SAVE_IDS = new AtomicLong(), activeSaves = new AtomicLong(), activeEnqueues = new AtomicLong();
    private static long began, wall;
    private static volatile long serverSession;
    private MtB7Metrics() {}
    public static void start() {
        if (!Boolean.getBoolean("incoming.mtB7")) return;
        try {
            ticks = new MtB7Log(MtB7Log.ROOT.resolve("ticks.csv"),
                    "server_session,start_wall_ms,start_mono_ns,end_wall_ms,end_mono_ns,tick,work_ns,heap_used,heap_committed,heap_max,log_drops");
            progress = new MtB7Log(MtB7Log.ROOT.resolve("progress.log"), "# MT-B7 v2: cumulative elapsed times overlap; chunk_save_events are NOT disk completions");
            saves = new MtB7Log(MtB7Log.ROOT.resolve("ledger.csv"),
                    "wall_ms,mono_ns,server_session,id,ledger_file,ledger,phase,elapsed_ns,success");
            MtB7Log.completeness(() -> {
                synchronized (SAVE_IDS) {
                    progress.add("server_session_end="+serverSession+" scope=process_global active_saves="+activeSaves.get()+" active_enqueue_returns="+activeEnqueues.get()+" "+MtB7Stages.snapshot());
                    return activeSaves.get() == 0 && activeEnqueues.get() == 0 && MtB7Stages.idle();
                }
            });
            RuinMatter.class.getSimpleName(); CraterMatter.class.getSimpleName(); RuinProgress.class.getSimpleName();
            // Link first-use formatting/encoding outside the measured save caller and its allocation check.
            saves.add(saveRow(0, 0, fileKey(new java.io.File("startup")), "schema", "startup", 0, true));
            NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerAboutToStartEvent event) -> {
                serverSession++;
                progress.add("server_session_start=" + serverSession + " mono_ns=" + System.nanoTime() + " scope=process_global " + MtB7Stages.snapshot());
            });
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, MtB7Metrics::before);
            NeoForge.EVENT_BUS.addListener(MtB7Stages::chunkSave);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, MtB7Metrics::after);
        } catch (IOException error) { Incoming.LOG.error("MT-B7 metrics initialization failed", error); }
    }
    static long saveId() {
        synchronized (SAVE_IDS) {
            if (saves == null || MtB7Log.stopping()) return 0;
            activeSaves.incrementAndGet(); activeEnqueues.incrementAndGet(); return SAVE_IDS.incrementAndGet();
        }
    }
    static long session() { return serverSession; }
    static String fileKey(java.io.File file) {
        return java.net.URLEncoder.encode(file.toPath().toAbsolutePath().normalize().toString(), java.nio.charset.StandardCharsets.UTF_8);
    }
    static String saveRow(long id, long session, String file, String ledger, String phase, long elapsed, boolean success) {
        return System.currentTimeMillis()+","+System.nanoTime()+","+session+","+id+","+file+","+ledger+","+phase+","+elapsed+","+success;
    }
    static void save(long id, long session, String file, LedgerData ledger, String phase, long elapsed, boolean success) {
        if (id != 0) {
            saves.add(saveRow(id, session, file, ledger.getClass().getSimpleName(), phase, elapsed, success));
            if (phase.equals("freeze_complete")) activeSaves.decrementAndGet();
            if (phase.equals("enqueue_return")) activeEnqueues.decrementAndGet();
        }
    }
    private static void before(ServerTickEvent.Pre event) { began = System.nanoTime(); wall = System.currentTimeMillis(); }
    private static void after(ServerTickEvent.Post event) {
        if (ticks == null || progress == null || MtB7Log.stopping()) return;
        long now = System.nanoTime(); var server = event.getServer(); var rt = Runtime.getRuntime();
        ticks.add(serverSession+","+wall+","+began+","+System.currentTimeMillis()+","+now+","+server.getTickCount()+","+(now-began)+","+(rt.totalMemory()-rt.freeMemory())+","+rt.totalMemory()+","+rt.maxMemory()+","+(ticks.dropped.get()+progress.dropped.get()+saves.dropped.get()));
        if (server.getTickCount()%20 != 0) return;
        progress.add("server_session="+serverSession+" mono_ns="+System.nanoTime()+" tick="+server.getTickCount()+" scope=process_global "+MtB7Stages.snapshot());
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
                    +" server_session="+serverSession);
        }
    }
}
