package ua.zentix.incoming.compat;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** TEST ONLY. Passive geometry records, not revision/freshness tokens. Never ship. */
public final class MtB7Columns {
    public static final boolean ENABLED = Boolean.getBoolean("incoming.mtB7.hashes");
    private static final Map<BlockState, Long> STATES = new ConcurrentHashMap<>();
    private static final long OFFSET = 0xcbf29ce484222325L, PRIME = 0x100000001b3L;
    /** Legacy progress fields remain zero: the retired Incoming publisher is absent. */
    public static final AtomicLong serverBytes = new AtomicLong(), serverDrops = new AtomicLong();
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
    /** Compatibility with the prior MT-B7 instrumenter. */
    public static final class Log extends MtB7Log {
        public Log(Path path, String header) throws IOException { super(path, header); }
    }
}
