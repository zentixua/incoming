package ua.zentix.incoming.client;

import dev.vox.lss.api.VoxelColumnData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.compat.LodSections;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** TEST ONLY: observation, never dispatches LSS or alters a column. Never ship. */
public final class MtB7Client {
    private static final boolean ENABLED=Boolean.getBoolean("incoming.mtB7");
    private static final Map<Long,long[]> BASE=new ConcurrentHashMap<>();
    private static final Map<Long,Expected> EXPECTED=new ConcurrentHashMap<>();
    private record Expected(long[] hashes,int event,long due) {}
    private static BufferedWriter frames, columns, incoming;
    private static final AtomicLong received=new AtomicLong(), conflicts=new AtomicLong(), stale=new AtomicLong(), hashNanos=new AtomicLong();
    private static long last, frame, nextShot, nextFlush;
    private static final Set<Integer> seen=new HashSet<>();
    private static boolean initialized;
    private MtB7Client() {}
    public static void start() {
        if (!ENABLED) return;
        try {
            Path dir=Minecraft.getInstance().gameDirectory.toPath().resolve("logs"); Files.createDirectories(dir);
            frames=Files.newBufferedWriter(dir.resolve("mt-b7-frames.csv"));
            columns=Files.newBufferedWriter(dir.resolve("mt-b7-lss.csv"));
            incoming=Files.newBufferedWriter(dir.resolve("mt-b7-incoming.csv"));
            frames.write("wall_ms,tick,frame,interval_ns,x,y,z,yaw,pitch,lss,conflicts,confirmed_stale,hash_ns\n");
            columns.write("wall_ms,tick,x,z,compared_sections,mismatch_sections,baseline_matches,event,due,hash_ns\n");
            incoming.write("wall_ms,tick,x,z,event,due,ingest_elapsed_ns,sections,retained_bytes,observation_ns\n");
            ClientHorizon.observer=MtB7Client::submitted;
            NeoForge.EVENT_BUS.addListener(MtB7Client::frame);
            initialized=true;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { frames.close(); synchronized(columns){columns.close();} synchronized(incoming){incoming.close();} } catch(IOException ignored) {} }, "MT-B7 client flush"));
        } catch(IOException e) { throw new UncheckedIOException(e); }
    }
    private static long[] empty() { long[] h=new long[64]; Arrays.fill(h,Long.MIN_VALUE); return h; }
    private static long hash(LevelChunkSection s) {
        if(s==null || s.hasOnlyAir()) return 0;
        long h=0xcbf29ce484222325L;
        for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) h=(h^Block.getId(s.getBlockState(x,y,z)))*0x100000001b3L;
        return h;
    }
    private static void submitted(ClientHorizon.Submission s) {
        var mc=Minecraft.getInstance(); if(mc.level==null) return;
        long start=System.nanoTime(); var p=s.packet(); long key=ChunkPos.asLong(p.x(),p.z());
        Expected prev=EXPECTED.get(key); long[] hs=prev==null?empty():prev.hashes().clone();
        for(var sec:LodSections.read(p.sections(),mc.level.registryAccess().registryOrThrow(Registries.BIOME))) {
            int i=sec.y()+8; if(i>=0 && i<64) hs[i]=hash(sec.blocks());
        }
        if(EXPECTED.size()<131072 || prev!=null) EXPECTED.put(key,new Expected(hs,p.detonation(),p.notBefore()));
        long cost=System.nanoTime()-start; hashNanos.addAndGet(cost);
        try { synchronized(incoming){ incoming.write(System.currentTimeMillis()+","+s.tick()+","+p.x()+","+p.z()+","+p.detonation()+","+p.notBefore()+","+s.elapsedNanos()+","+s.sections()+","+s.retainedBytes()+","+cost+"\n"); } }
        catch(IOException e) { Incoming.LOG.error("MT-B7 incoming log failed",e); }
    }
    static void lss(ClientLevel level,int x,int z,VoxelColumnData data) {
        if(!ENABLED || !initialized) return;
        long start=System.nanoTime(),key=ChunkPos.asLong(x,z); Expected expected=EXPECTED.get(key);
        long[] baseline=BASE.get(key), hashes=empty(); int compared=0,mismatch=0,old=0;
        for(var sec:data.sections()) {
            int i=sec.sectionY()+8; if(i<0||i>=64)continue;
            hashes[i]=hash(sec.section());
            if(expected!=null && expected.hashes()[i]!=Long.MIN_VALUE) {
                compared++;
                if(hashes[i]!=expected.hashes()[i]) { mismatch++; if(baseline!=null && hashes[i]==baseline[i] && baseline[i]!=Long.MIN_VALUE)old++; }
            }
        }
        if(expected==null && baseline==null && BASE.size()<131072)BASE.put(key,hashes);
        received.incrementAndGet(); if(mismatch>0)conflicts.incrementAndGet(); if(old>0)stale.incrementAndGet();
        long cost=System.nanoTime()-start;hashNanos.addAndGet(cost);
        try { synchronized(columns){columns.write(System.currentTimeMillis()+","+level.getGameTime()+","+x+","+z+","+compared+","+mismatch+","+old+","+(expected==null?-1:expected.event())+","+(expected==null?-1:expected.due())+","+cost+"\n");} }
        catch(IOException e){Incoming.LOG.error("MT-B7 LSS log failed",e);}
    }
    private static void frame(RenderFrameEvent.Post e) {
        var mc=Minecraft.getInstance();long now=System.nanoTime(),wall=System.currentTimeMillis();
        if(mc.level!=null && mc.player!=null) {
            if(last!=0)try{var p=mc.player;frames.write(wall+","+mc.level.getGameTime()+","+(frame++)+","+(now-last)+","+p.getX()+","+p.getY()+","+p.getZ()+","+p.getYRot()+","+p.getXRot()+","+received.get()+","+conflicts.get()+","+stale.get()+","+hashNanos.get()+"\n");}catch(IOException ex){Incoming.LOG.error("MT-B7 frame log failed",ex);}
            for(var a:ua.zentix.incoming.client.nuclear.ClientNuclear.detonations()) if(seen.add(a.d.id())) Incoming.LOG.info("MT-B7 detonation wall={} tick={} id={} kt={} burst={}",wall,mc.level.getGameTime(),a.d.id(),a.d.yieldKt(),a.d.burst());
            if(wall>=nextShot){ nextShot=wall+10000;Screenshot.grab(mc.gameDirectory,"mt-b7-"+wall+".png",mc.getMainRenderTarget(),c->{}); }
            if(wall>=nextFlush){ nextFlush=wall+1000;try{frames.flush();synchronized(columns){columns.flush();}synchronized(incoming){incoming.flush();}}catch(IOException ex){Incoming.LOG.error("MT-B7 flush failed",ex);} }
        }
        last=now;
    }
}
