package ua.zentix.incoming.nuclear.world;

import java.util.concurrent.atomic.AtomicLongArray;

/** TEST ONLY: method completion counts and elapsed worker time, never a scheduling input. */
final class MtB7Stages {
    private static final boolean ENABLED=Boolean.getBoolean("incoming.mtB7");
    static final AtomicLongArray started=new AtomicLongArray(6),finished=new AtomicLongArray(6),failed=new AtomicLongArray(6),nanos=new AtomicLongArray(6);
    private MtB7Stages() {}
    static long begin(int stage){if(!ENABLED)return 0;started.incrementAndGet(stage);return System.nanoTime();}
    static void end(int stage,long start){if(start==0)return;nanos.addAndGet(stage,System.nanoTime()-start);finished.incrementAndGet(stage);}
    static void fail(int stage){if(ENABLED)failed.incrementAndGet(stage);}
    static void chunkSave(net.neoforged.neoforge.event.level.ChunkDataEvent.Save event){if(ENABLED)finished.incrementAndGet(5);}
    static String snapshot(){return "stage_started="+started+" stage_finished="+finished+" stage_failed="+failed+" stage_elapsed_ns="+nanos;}
}
