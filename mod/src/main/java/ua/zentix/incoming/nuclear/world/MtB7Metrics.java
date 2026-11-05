package ua.zentix.incoming.nuclear.world;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.NuclearEvents;

/** TEST ONLY: passive timing for the disposable MT-B7 trial. Never ship. */
public final class MtB7Metrics {
    private static BufferedWriter ticks, progress;
    private static long began;
    private MtB7Metrics() {}
    public static void start() {
        if (!Boolean.getBoolean("incoming.mtB7")) return;
        try {
            Files.createDirectories(Path.of("logs"));
            ticks=Files.newBufferedWriter(Path.of("logs/mt-b7-ticks.csv"));
            progress=Files.newBufferedWriter(Path.of("logs/mt-b7-progress.log"));
            ticks.write("wall_ms,tick,work_ns,heap_used,heap_committed,heap_max\n");
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, MtB7Metrics::before);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, MtB7Metrics::after);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { ticks.close(); progress.close(); } catch(IOException ignored) {} }, "MT-B7 flush"));
        } catch(IOException e) { throw new UncheckedIOException(e); }
    }
    private static void before(ServerTickEvent.Pre event) { began=System.nanoTime(); }
    private static void after(ServerTickEvent.Post event) {
        long took=System.nanoTime()-began; var s=event.getServer(); var rt=Runtime.getRuntime();
        try {
            ticks.write(System.currentTimeMillis()+","+s.getTickCount()+","+took+","+(rt.totalMemory()-rt.freeMemory())+","+rt.totalMemory()+","+rt.maxMemory()+"\n");
            if (s.getTickCount()%20!=0) return;
            var level=s.overworld(); var w=NuclearWorld.get(level); var events=NuclearEvents.get(level);
            progress.write(System.currentTimeMillis()+" tick="+level.getGameTime()+" average_ms="+s.getAverageTickTimeNanos()/1e6
                +" scheduled="+events.scheduled()+" detonations="+events.detonations().stream().map(d->d.id()+":"+d.yieldKt()).toList()
                +" lanes_ns="+Arrays.toString(w.lastNanos())+" ruins="+Arrays.toString(w.ruinStats())
                +" prep="+w.prep().mtB7Progress()+"\n");
            ticks.flush(); progress.flush();
        } catch(IOException e) { Incoming.LOG.error("MT-B7 metrics failed",e); }
    }
}
