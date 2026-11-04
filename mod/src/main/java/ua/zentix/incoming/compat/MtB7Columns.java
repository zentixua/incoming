package ua.zentix.incoming.compat;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.net.S2C;

/** TEST ONLY. Passive geometry records, not revision/freshness tokens. Never ship. */
public final class MtB7Columns {
    public static final boolean ENABLED = Boolean.getBoolean("incoming.mtB7.hashes");
    private static final Map<BlockState, Long> STATES = new ConcurrentHashMap<>();
    private static final long OFFSET = 0xcbf29ce484222325L, PRIME = 0x100000001b3L;
    public static final AtomicLong serverBytes = new AtomicLong(), serverDrops = new AtomicLong();
    private static final ExecutorService SERVER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), r -> {
        Thread t = new Thread(r, "MT-B7 passive columns"); t.setDaemon(true); return t;
    });
    private static final AtomicLong connections = new AtomicLong(), offers = new AtomicLong();
    public static long nextConnection() { return connections.incrementAndGet(); }
    static String offerIdentity(long offer, UUID recipient, long connection, boolean accepted) {
        return offer+","+recipient+","+connection+",queue_offer,"+accepted;
    }
    private static Log serverLog;
    private static final java.util.concurrent.atomic.AtomicBoolean errorLogged = new java.util.concurrent.atomic.AtomicBoolean();
    static {
        if (ENABLED) MtB7Log.producer(SERVER, () -> {
            if (serverLog != null) serverLog.add("# hash_drops=" + serverDrops.get() + " queued_bytes=" + serverBytes.get());
        });
    }
    private MtB7Columns() {}

    public static long hash(LevelChunkSection section) {
        if (section == null || section.hasOnlyAir()) return 0;
        long h = OFFSET;
        for (int y=0;y<16;y++) for (int z=0;z<16;z++) for (int x=0;x<16;x++) {
            BlockState block = section.getBlockState(x,y,z);
            Long cached = STATES.get(block);
            long state = cached == null ? stateHash(block) : cached;
            if (cached == null) {
                synchronized (STATES) { if (STATES.size() < 65536) STATES.putIfAbsent(block, state); }
            }
            h = (h ^ state)*PRIME;
        }
        return h;
    }
    private static long stateHash(BlockState s) {
                long v = OFFSET;
                for (byte b : s.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)) v = (v ^ (b & 255))*PRIME;
                return v;
    }
    public static long[] empty() { long[] result=new long[64]; Arrays.fill(result,Long.MIN_VALUE); return result; }
    public static String encode(long[] hashes) {
        StringBuilder out=new StringBuilder();
        for(int i=0;i<hashes.length;i++) if(hashes[i]!=Long.MIN_VALUE) {
            if(!out.isEmpty())out.append('|');out.append(i-8).append(':').append(Long.toUnsignedString(hashes[i],16));
        }
        return out.toString();
    }
    /** Observe each production queue offer, including refusals; never claim send/delivery. */
    public static void offered(ServerLevel level, S2C.VoxyChunk packet, UUID recipient, long connection, boolean accepted) {
        if(!ENABLED || level == null)return;
        synchronized (SERVER) {
        if (MtB7Log.stopping()) return;
        long charge=packet.sections().length+256L;
        if(serverBytes.addAndGet(charge)>(8L<<20)){serverBytes.addAndGet(-charge);serverDrops.incrementAndGet();return;}
        long wall=System.currentTimeMillis(),nano=System.nanoTime(),tick=level.getGameTime();
        String identity = offerIdentity(offers.incrementAndGet(), recipient, connection, accepted);
        var biomes=level.registryAccess().registryOrThrow(Registries.BIOME);
        try { SERVER.execute(() -> {
            long start=System.nanoTime();
            try {
                if(serverLog==null)serverLog=new Log(MtB7Log.ROOT.resolve("server-columns.csv"),"wall_ms,mono_ns,tick,offer_id,recipient_uuid,connection,phase,accepted,dimension,x,z,event,due,hashes,observe_ns,decode_delay_ns");
                long[] hashes=empty();
                for(var s:LodSections.read(packet.sections(),biomes))if(s.y()>=-8&&s.y()<56)hashes[s.y()+8]=hash(s.blocks());
                serverLog.add(wall+","+nano+","+tick+","+identity+","+packet.dimension()+","+packet.x()+","+packet.z()+","+packet.detonation()+","+packet.notBefore()+","+encode(hashes)+","+(System.nanoTime()-start)+","+(start-nano));
            }catch(Exception e){serverDrops.incrementAndGet();if (errorLogged.compareAndSet(false, true)) Incoming.LOG.error("MT-B7 passive server record failed (subsequent errors counted)",e);}
            finally{serverBytes.addAndGet(-charge);}
        }); } catch (RejectedExecutionException rejected) { serverBytes.addAndGet(-charge); serverDrops.incrementAndGet(); }
        }
    }
    /** Compatibility with the prior MT-B7 instrumenter. */
    public static final class Log extends MtB7Log {
        public Log(Path path, String header) throws IOException { super(path, header); }
    }
}
