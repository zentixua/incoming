package ua.zentix.incoming.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import ua.zentix.incoming.compat.MtB7Log;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** TEST ONLY. Opt-in 1 Hz diagnostic PNGs. No pre-trigger ring, no readback when disabled. */
final class MtB7Capture {
    static final boolean ENABLED = Boolean.getBoolean("incoming.mtB7.screenshots");
    private static final AtomicLong DROPS = new AtomicLong();
    private static ThreadPoolExecutor writer;
    private static MtB7Log index;
    private static Path directory;
    private static long next;
    private static int total;
    private MtB7Capture() {}
    static void start() throws IOException {
        directory = MtB7Log.ROOT.resolve("native"); Files.createDirectories(directory);
        index = new MtB7Log(directory.resolve("index.csv"), "wall_ms,mono_ns,scope,tick,frame,readback_ns,file,status");
        writer = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2), task -> {
            Thread thread = new Thread(task,"MT-B7 PNG"); thread.setDaemon(true); return thread;
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            writer.shutdown(); try { writer.awaitTermination(3,TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            index.close();
        }));
    }
    static long drops() { return DROPS.get() + (index == null ? 0 : index.dropped.get()); }
    static void frame(long frame,long wall,long nano,long tick,long scope) {
        if (!ENABLED || writer == null || nano < next) return;
        next = nano + 1_000_000_000L;
        if (total >= 1000 || writer.getQueue().remainingCapacity() == 0) { DROPS.incrementAndGet(); return; }
        var target = Minecraft.getInstance().getMainRenderTarget();
        if ((long) target.width*target.height > 1920L*1080) { DROPS.incrementAndGet(); return; }
        long began = System.nanoTime(); var image = Screenshot.takeScreenshot(target);
        String file = wall+"-f"+frame+".png";
        String row = wall+","+nano+","+scope+","+tick+","+frame+","+(System.nanoTime()-began)+","+file+",";
        try {
            writer.execute(() -> {
                try (image) { image.writeToFile(directory.resolve(file)); index.add(row+"saved"); }
                catch (IOException error) { DROPS.incrementAndGet(); index.add(row+"error"); }
            });
            total++;
        } catch (RejectedExecutionException error) { image.close(); DROPS.incrementAndGet(); index.add(row+"queue_drop"); }
    }
}
