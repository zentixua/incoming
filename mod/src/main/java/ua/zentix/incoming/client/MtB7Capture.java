package ua.zentix.incoming.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import ua.zentix.incoming.compat.MtB7Columns;
import ua.zentix.incoming.Incoming;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** TEST ONLY. Native presented-frame PNGs. Capture is observation, not a Voxy worker or renderer fence. */
final class MtB7Capture {
    private record Shot(NativeImage image,long frame,long wall,long nano,long tick) {}
    private static final ArrayDeque<Shot> RING=new ArrayDeque<>();
    private static final AtomicLong SIGNAL=new AtomicLong(),DROPS=new AtomicLong();
    private static final ThreadPoolExecutor WRITER=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"MT-B7 native PNG");t.setDaemon(true);return t;});
    private static MtB7Columns.Log index;
    private static Path dir;
    private static long signal,nextRing,nextPeriodic,burstUntil;
    private static int burstFrames,total;
    private MtB7Capture() {}
    static void start(Path root)throws IOException{
        dir=root.resolve("screenshots/mt-b7-native");java.nio.file.Files.createDirectories(dir);
        index=new MtB7Columns.Log(dir.resolve("index.csv"),"wall_ms,mono_ns,tick,frame,trigger,kind,file,status");
        Runtime.getRuntime().addShutdownHook(new Thread(()->{WRITER.shutdown();try{WRITER.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}));
    }
    static void trigger(){SIGNAL.incrementAndGet();}
    static long drops(){return DROPS.get()+index.dropped.get();}
    static long frame(long frame,long wall,long nano,long tick){
        long begin=System.nanoTime(),current=SIGNAL.get();
        if(current!=signal){
            signal=current;
            while(!RING.isEmpty())save(RING.removeFirst(),"pre");
            if(nano>=burstUntil){burstUntil=nano+2_000_000_000L;burstFrames=0;}
        }
        boolean burst=nano<burstUntil&&burstFrames<120,periodic=nano>=nextPeriodic;
        if(!burst&&!periodic&&nano<nextRing)return System.nanoTime()-begin;
        if(total>=10000){DROPS.incrementAndGet();return System.nanoTime()-begin;}
        var mc=Minecraft.getInstance();
        // These test copies are 1280x720. Refuse oversized readbacks instead of unbounded native memory.
        var target=mc.getMainRenderTarget();
        if((long)target.width*target.height>1920L*1080){DROPS.incrementAndGet();return System.nanoTime()-begin;}
        NativeImage image=Screenshot.takeScreenshot(target);
        Shot shot=new Shot(image,frame,wall,nano,tick);nextRing=nano+100_000_000L;
        if(burst||periodic){save(shot,burst?"burst":"periodic");if(burst)burstFrames++;nextPeriodic=nano+1_000_000_000L;}
        else{RING.addLast(shot);while(RING.size()>10)RING.removeFirst().image().close();}
        return System.nanoTime()-begin;
    }
    private static void save(Shot shot,String kind){
        if(total>=10000){shot.image().close();DROPS.incrementAndGet();return;}
        String name=shot.wall()+"-f"+shot.frame()+"-"+kind+".png";
        String row=shot.wall()+","+shot.nano()+","+shot.tick()+","+shot.frame()+","+signal+","+kind+","+name+",";
        try{WRITER.execute(()->{try(var image=shot.image()){image.writeToFile(dir.resolve(name));index.add(row+"saved");}catch(IOException e){DROPS.incrementAndGet();index.add(row+"error");Incoming.LOG.error("MT-B7 native image failed",e);}});total++;}
        catch(RejectedExecutionException e){shot.image().close();DROPS.incrementAndGet();index.add(row+"queue_drop");}
    }
}
