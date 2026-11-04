package ua.zentix.incoming.nuclear.world;

import java.util.concurrent.atomic.AtomicLongArray;

/** TEST ONLY: method completion counts and elapsed worker time, never a scheduling input. */
final class MtB7Stages {
    private static final boolean ENABLED=Boolean.getBoolean("incoming.mtB7");
    static final AtomicLongArray started=new AtomicLongArray(8),finished=new AtomicLongArray(8),failed=new AtomicLongArray(8),nanos=new AtomicLongArray(8);
    private static final AtomicLongArray work = new AtomicLongArray(2);
    static void count(int kind) { if (ENABLED) work.incrementAndGet(kind); }
    static boolean idle() {
        for (int i = 0; i <= 5; i++) if (started.get(i) != finished.get(i)) return false;
        return true;
    }
    private MtB7Stages() {}
    static long begin(int stage){if(!ENABLED)return 0;started.incrementAndGet(stage);return System.nanoTime();}
    static void end(int stage,long start){if(start==0)return;nanos.addAndGet(stage,System.nanoTime()-start);finished.incrementAndGet(stage);}
    static void fail(int stage){if(ENABLED)failed.incrementAndGet(stage);}
    static void chunkSave(net.neoforged.neoforge.event.level.ChunkDataEvent.Save event){if(ENABLED)finished.incrementAndGet(7);}
    static String snapshot(){return "settle_confirmed_loads="+work+" stage_started="+started+" stage_finished="+finished+" stage_failed="+failed+" stage_elapsed_ns="+nanos;}
}
