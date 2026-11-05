package ua.zentix.incoming.client;

import dev.vox.lss.api.VoxelColumnData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.compat.LodSections;
import ua.zentix.incoming.compat.MtB7Columns;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** TEST ONLY: every natural arrival is recorded, including arrivals before Incoming. Never injects data. */
public final class MtB7Client {
    private static final Map<Long,long[]> BASE=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Long,Expected> EXPECTED=new java.util.concurrent.ConcurrentHashMap<>();
    private record Expected(long[] hashes,int event,long due) {}
    private static MtB7Columns.Log frames,columns,incoming;
    private static final AtomicLong received=new AtomicLong(), conflicts=new AtomicLong(), replay=new AtomicLong(), hashNanos=new AtomicLong(), omitted=new AtomicLong();
    private static long last,frame;
    private static final Set<Integer> seen=new HashSet<>();
    private static volatile boolean initialized;
    private MtB7Client() {}
    public static void start() {
        if(!MtB7Columns.ENABLED)return;
        try {
            Path dir=Minecraft.getInstance().gameDirectory.toPath().resolve("logs");
            frames=new MtB7Columns.Log(dir.resolve("mt-b7-frames.csv"),"wall_ms,mono_ns,tick,frame,interval_ns,x,y,z,yaw,pitch,lss,conflicts,baseline_replays,hash_ns,history_omissions,log_drops,capture_ns,capture_drops");
            columns=new MtB7Columns.Log(dir.resolve("mt-b7-lss.csv"),"wall_ms,mono_ns,tick,dimension,x,z,compared_sections,mismatch_sections,baseline_matches,event,due,hash_ns,hashes");
            incoming=new MtB7Columns.Log(dir.resolve("mt-b7-incoming.csv"),"wall_ms,mono_ns,tick,dimension,x,z,event,due,ingest_elapsed_ns,sections,retained_bytes,observation_ns,hashes");
            MtB7Capture.start(dir.getParent());
            ClientHorizon.observer=MtB7Client::submitted;
            NeoForge.EVENT_BUS.addListener(MtB7Client::frame);
            initialized=true;
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    private static void submitted(ClientHorizon.Submission s) {
        var mc=Minecraft.getInstance();if(mc.level==null)return;
        long start=System.nanoTime(),wall=System.currentTimeMillis();var p=s.packet();long key=ChunkPos.asLong(p.x(),p.z());
        Expected prev=EXPECTED.get(key);long[] union=prev==null?MtB7Columns.empty():prev.hashes().clone(),hashes=MtB7Columns.empty();
        for(var sec:LodSections.read(p.sections(),mc.level.registryAccess().registryOrThrow(Registries.BIOME))) {
            int i=sec.y()+8;if(i>=0&&i<64)union[i]=hashes[i]=MtB7Columns.hash(sec.blocks());
        }
        if(EXPECTED.size()<32768||prev!=null)EXPECTED.put(key,new Expected(union,p.detonation(),p.notBefore()));else omitted.incrementAndGet();
        long cost=System.nanoTime()-start;hashNanos.addAndGet(cost);
        incoming.add(wall+","+start+","+s.tick()+","+p.dimension()+","+p.x()+","+p.z()+","+p.detonation()+","+p.notBefore()+","+s.elapsedNanos()+","+s.sections()+","+s.retainedBytes()+","+cost+","+MtB7Columns.encode(hashes));
    }
    static void lss(ClientLevel level,int x,int z,VoxelColumnData data) {
        if(!initialized)return;
        long start=System.nanoTime(),wall=System.currentTimeMillis(),key=ChunkPos.asLong(x,z);Expected expected=EXPECTED.get(key);
        long[] baseline=BASE.get(key),hashes=MtB7Columns.empty();int compared=0,mismatch=0,old=0;
        for(var sec:data.sections()){
            int i=sec.sectionY()+8;if(i<0||i>=64)continue;
            hashes[i]=MtB7Columns.hash(sec.section());
            if(expected!=null&&expected.hashes()[i]!=Long.MIN_VALUE){compared++;
                if(hashes[i]!=expected.hashes()[i]){mismatch++;if(baseline!=null&&hashes[i]==baseline[i]&&baseline[i]!=Long.MIN_VALUE)old++;}
            }
        }
        if(expected==null&&baseline==null){if(BASE.size()<32768)BASE.put(key,hashes);else omitted.incrementAndGet();}
        received.incrementAndGet();if(mismatch>0){conflicts.incrementAndGet();MtB7Capture.trigger();}if(old>0)replay.incrementAndGet();
        long cost=System.nanoTime()-start;hashNanos.addAndGet(cost);
        columns.add(wall+","+start+","+level.getGameTime()+","+level.dimension().location()+","+x+","+z+","+compared+","+mismatch+","+old+","+(expected==null?-1:expected.event())+","+(expected==null?-1:expected.due())+","+cost+","+MtB7Columns.encode(hashes));
    }
    private static void frame(RenderFrameEvent.Post event) {
        var mc=Minecraft.getInstance();long now=System.nanoTime(),wall=System.currentTimeMillis();
        if(mc.level!=null&&mc.player!=null){
            long captureNs=MtB7Capture.frame(frame,wall,now,mc.level.getGameTime());
            var p=mc.player;
            frames.add(wall+","+now+","+mc.level.getGameTime()+","+(frame++)+","+(last==0?0:now-last)+","+p.getX()+","+p.getY()+","+p.getZ()+","+p.getYRot()+","+p.getXRot()+","+received.get()+","+conflicts.get()+","+replay.get()+","+hashNanos.get()+","+omitted.get()+","+(frames.dropped.get()+columns.dropped.get()+incoming.dropped.get())+","+captureNs+","+MtB7Capture.drops());
            for(var a:ua.zentix.incoming.client.nuclear.ClientNuclear.detonations())if(seen.add(a.d.id()))Incoming.LOG.info("MT-B7 detonation wall={} tick={} id={} kt={} burst={}",wall,mc.level.getGameTime(),a.d.id(),a.d.yieldKt(),a.d.burst());
        }
        last=now;
    }
}
