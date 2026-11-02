package ua.zentix.incoming.nuclear.world;

import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.bus.api.EventPriority;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;

@Mod(Incoming.MOD_ID)
public final class SettleBench {
    private net.minecraft.server.level.ServerLevel level;
    private Detonation d;
    private long start, startTick, peakHeap, sumNuclear, maxNuclear;
    private final List<Long> ticks = new ArrayList<>();
    private static Object field(Object owner, String name) {
        try { var f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
        catch (ReflectiveOperationException ex) { throw new RuntimeException(ex); }
    }
    public SettleBench() {
        if (!Boolean.getBoolean("incoming.settleBench")) return;
        NeoForge.EVENT_BUS.addListener(this::start);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::tick);
    }
    private void start(ServerStartedEvent e) {
        level = e.getServer().overworld();
        var events = NuclearEvents.get(level);
        // Entire unchanged city lies inside this 7 kt zone. Retained event, no live prep.
        d = new Detonation(events.nextId(), new Vec3(-520, 65, -264), 65, 7, true,
                level.getGameTime()-100000, 0, 0, 20000, 42, 1, false, 1, 0, false);
        events.past().add(d); events.setDirty();
        start = System.nanoTime(); startTick = e.getServer().getTickCount();
        Incoming.LOG.info("CITYBENCH start id={} cores={} maxHeapMiB={} radius={}", d.id(), Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() >> 20, d.ruinRadius());
    }
    private void tick(ServerTickEvent.Post e) {
        if (d == null) return;
        long n = Arrays.stream(NuclearWorld.get(level).lastNanos()).sum();
        sumNuclear += n; maxNuclear = Math.max(maxNuclear,n);
        int tick=e.getServer().getTickCount();
        // Vanilla sample from preceding complete tick, includes Post work from prior tick start bookkeeping.
        long[] times=e.getServer().getTickTimesNanos();
        ticks.add(times[Math.floorMod(tick-1,times.length)]);
        long heap=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        peakHeap=Math.max(heap,peakHeap);
        var p=RuinProgress.get(level).pass(d.id());
        if ((tick-startTick)%200==0 || p.complete) {
            double sec=(System.nanoTime()-start)/1e9;
            var sorted=new ArrayList<>(ticks); Collections.sort(sorted);
            Object pass=field(NuclearWorld.get(level),"settle");
            RuinContext ctx=NuclearWorld.get(level).ruinsIfKept(d.id());
            if(ctx!=null) Incoming.LOG.info("CITYBENCH memory shotsMiB={} appliedMiB={} liveHeapMiB={}",ctx.shotBytes()>>20,((Long)field(ctx,"appliedBytes"))>>20,HeapWatch.get().live()>>20);
            Incoming.LOG.info("CITYBENCH progress saved={} loaded={} limit={} held={}", field(pass,"settled"),field(pass,"loaded"),field(pass,"loadingLimit"),((RuinSettle)pass).held());
            Incoming.LOG.info("CITYBENCH seconds={} cursor={} pending={} complete={} msptMean={} msptP95={} msptMax={} nuclearMean={} nuclearMax={} heapMiB={} peakHeapMiB={} retainedMiB={}",
                    sec,p.cursor,p.pending.size(),p.complete, ticks.stream().mapToLong(Long::longValue).average().orElse(0)/1e6,
                    sorted.get((int)((sorted.size()-1)*.95))/1e6,sorted.get(sorted.size()-1)/1e6,
                    sumNuclear/1e6/ticks.size(),maxNuclear/1e6,heap>>20,peakHeap>>20,(NuclearPrep.PLANS_CAP/2-NuclearWorld.get(level).settleMemoryRoom(level.getHeight()))>>20);
        }
        // Bounded first diagnostic, then full run via property. Always orderly save/stop.
        if (p.complete || tick-startTick>=Integer.getInteger("incoming.settleBench.ticks",36000)) {
            Incoming.LOG.info("CITYBENCH stop complete={}",p.complete);
            d=null; e.getServer().halt(false);
        }
    }
}
