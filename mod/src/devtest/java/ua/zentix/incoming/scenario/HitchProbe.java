package ua.zentix.incoming.scenario;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;
import ua.zentix.incoming.Incoming;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Render-thread stall probe for client scenarios: {@code -Dincoming.hitch=true}.
 * <ul>
 * <li>{@code logs/hitch-frames.txt}: every frame — epoch ms, game time, frame interval ms (post→post), render part ms
 * (pre→post), client tick ms inside the frame, GPU ms of the frame (GL timestamps, read 4 frames later; -1 if not
 * ready), the part before the first client tick ms (swap of the previous frame, then queued tasks and packets), and
 * client chunks loaded from packets in the frame ({@code ChunkEvent.Load}: whole chunks, new or replacing).</li>
 * <li>{@code logs/hitch-stacks.txt}: render-thread stack every 10 ms while no frame/tick progress for over 100 ms (no
 * heap-walking thread dumps: they freeze the JVM 115-250 ms each).</li>
 * <li>{@code logs/hitch-net.txt}: per client tick, packets received (netty side) by class; section-update block
 * counts and their new states (light emitters, fire, air, unlit lamp twins); bundle contents; custom payload ids; wire
 * sizes with {@code -Dincoming.hitch.bytes=true}.</li>
 * <li>{@code logs/hitch-samples.txt}: the render-thread stack every {@code incoming.hitch.sampleMs} (5) ms while a
 * detonation is live.</li>
 * <li>{@code logs/hitch-sched.txt}: CPU and run-queue wait per thread group every 250 ms (/proc schedstat).</li>
 * <li>{@code logs/hitch-state.txt}: per client tick from the detonation (else every 100 ticks): Sodium debug
 * strings, client light queue, block-entity tickers (all, removed and waiting for removal, pending, fresh block entities
 * waiting for {@code onLoad}), chunks loaded from packets since the last tick, entities, particles, Incoming fragments,
 * integrated server last tick ms.</li>
 * <li>{@code logs/hitch-be.txt}: ticking block entities by type at the first live tick and every 200 live ticks.</li>
 * </ul>
 */
final class HitchProbe {
    private static final long STALL_NS = 100_000_000L;
    private static volatile long progress = System.nanoTime();
    private static volatile String phase = "start";
    private static volatile Thread render;
    private static BufferedWriter frames, stacks, netOut, state, samples, sched, beTypes;
    /** Client chunks loaded from packets in this frame and since the last client tick. */
    private static int chunksInFrame, chunksInTick;
    /** Start of the frame's first client tick (0: none yet). */
    private static long firstTick;
    private static int liveTicks;
    /** Client ticks run inside the current frame. */
    private static int ticksInFrame;
    private static final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();
    private static long lastPost, pre, tickNs, tickPre;
    private static final int RING = 8;
    private static int[] qs;
    private static final long[] qFrameEpoch = new long[RING];
    private static final long[] qGame = new long[RING];
    private static int frameNo;
    private static volatile int clientTick;
    /** A detonation is live on the client (set on the render thread, read by the sampler). */
    private static volatile boolean live;
    private static Channel hooked;

    private HitchProbe() {}

    static void startIfRequested() {
        if (!Boolean.getBoolean("incoming.hitch")) return;
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("logs");
            Files.createDirectories(dir);
            frames = Files.newBufferedWriter(dir.resolve("hitch-frames.txt"));
            stacks = Files.newBufferedWriter(dir.resolve("hitch-stacks.txt"));
            netOut = Files.newBufferedWriter(dir.resolve("hitch-net.txt"));
            state = Files.newBufferedWriter(dir.resolve("hitch-state.txt"));
            samples = Files.newBufferedWriter(dir.resolve("hitch-samples.txt"));
            sched = Files.newBufferedWriter(dir.resolve("hitch-sched.txt"));
            beTypes = Files.newBufferedWriter(dir.resolve("hitch-be.txt"));
            frames.write("epoch_ms game frame_ms render_ms tick_ms ticks gpu_ms_of(epoch_ms) pre_ms chunks\n");
            sched.write("epoch_ms then per thread group: name cpu_ms wait_ms (schedstat deltas over the interval); load\n");
        } catch (IOException e) {
            Incoming.LOG.warn("HITCH probe files", e);
            return;
        }
        NeoForge.EVENT_BUS.addListener(HitchProbe::framePre);
        NeoForge.EVENT_BUS.addListener(HitchProbe::framePost);
        NeoForge.EVENT_BUS.addListener(HitchProbe::tickPre);
        NeoForge.EVENT_BUS.addListener(HitchProbe::tickPost);
        NeoForge.EVENT_BUS.addListener(HitchProbe::login);
        NeoForge.EVENT_BUS.addListener(HitchProbe::chunkLoad);
        Thread w = new Thread(HitchProbe::watch, "Incoming hitch watchdog");
        w.setDaemon(true);
        w.start();
        Runtime.getRuntime().addShutdownHook(new Thread(HitchProbe::flush));
        Incoming.LOG.info("HITCH probe on");
    }

    private static void mark(String p) {
        progress = System.nanoTime();
        phase = p;
    }

    private static void chunkLoad(net.neoforged.neoforge.event.level.ChunkEvent.Load e) {
        if (!e.getLevel().isClientSide()) return;
        chunksInFrame++;
        chunksInTick++;
    }

    private static void framePre(RenderFrameEvent.Pre e) {
        if (render == null) render = Thread.currentThread();
        pre = System.nanoTime();
        if (firstTick == 0) firstTick = pre;
        mark("render");
        if (qs == null) {
            qs = new int[RING * 2];
            GL15C.glGenQueries(qs);
        }
        int slot = frameNo % RING;
        GL33C.glQueryCounter(qs[slot * 2], GL33C.GL_TIMESTAMP);
    }

    private static void framePost(RenderFrameEvent.Post e) {
        long now = System.nanoTime();
        int slot = frameNo % RING;
        GL33C.glQueryCounter(qs[slot * 2 + 1], GL33C.GL_TIMESTAMP);
        Minecraft mc = Minecraft.getInstance();
        long epoch = System.currentTimeMillis();
        qFrameEpoch[slot] = epoch;
        qGame[slot] = mc.level == null ? -1 : mc.level.getGameTime();
        // GPU time of the frame RING/2 frames back
        double gpu = -1;
        long gpuEpoch = -1;
        if (frameNo >= RING / 2) {
            int old = (frameNo - RING / 2) % RING;
            if (GL15C.glGetQueryObjecti(qs[old * 2 + 1], GL15C.GL_QUERY_RESULT_AVAILABLE) != 0) {
                long a = GL33C.glGetQueryObjecti64(qs[old * 2], GL15C.GL_QUERY_RESULT), b = GL33C.glGetQueryObjecti64(qs[old * 2 + 1], GL15C.GL_QUERY_RESULT);
                gpu = (b - a) / 1e6;
                gpuEpoch = qFrameEpoch[old];
            }
        }
        frameNo++;
        if (lastPost != 0 && mc.level != null) {
            try {
                frames.write(epoch + " " + mc.level.getGameTime() + " " + fmt((now - lastPost) / 1e6) + " " + fmt((now - pre) / 1e6) + " "
                        + fmt(tickNs / 1e6) + " " + ticksInFrame + " " + fmt(gpu) + "(" + gpuEpoch + ") " + fmt((firstTick - lastPost) / 1e6)
                        + " " + chunksInFrame + "\n");
            } catch (IOException ignored) {
                // measurement file
            }
        }
        tickNs = 0;
        ticksInFrame = 0;
        chunksInFrame = 0;
        firstTick = 0;
        lastPost = now;
        mark("between frames (swap, tasks/packets, ticks)");
    }

    private static void tickPre(ClientTickEvent.Pre e) {
        tickPre = System.nanoTime();
        if (firstTick == 0) firstTick = tickPre;
        mark("client tick");
    }

    private static void tickPost(ClientTickEvent.Post e) {
        tickNs += System.nanoTime() - tickPre;
        ticksInFrame++;
        mark("after client tick");
        clientTick++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        hook(mc);
        long epoch = System.currentTimeMillis(), game = mc.level.getGameTime();
        Map<String, Long> snap = new TreeMap<>();
        for (var en : counts.entrySet()) {
            long v = en.getValue().getAndSet(0);
            if (v != 0) snap.put(en.getKey(), v);
        }
        try {
            if (!snap.isEmpty()) netOut.write(epoch + " " + game + " " + snap + "\n");
            boolean det = !ua.zentix.incoming.client.nuclear.ClientNuclear.detonations().isEmpty();
            live = det;
            if (clientTick % 200 == 0) flushAll();
            if (det || clientTick % 100 == 0) state.write(epoch + " " + game + " " + state(mc) + "\n");
            if (det && liveTicks++ % 200 == 0) beTypes.write(epoch + " " + game + " " + beTypes(mc) + "\n");
        } catch (IOException ignored) {
            // measurement file
        }
    }

    private static String state(Minecraft mc) {
        StringBuilder b = new StringBuilder();
        try {
            Class<?> c = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
            Object r = c.getMethod("instanceNullable").invoke(null);
            if (r != null) {
                b.append("sodium=").append(c.getMethod("getChunksDebugString").invoke(r)).append(" | ");
                for (Object s : (java.util.Collection<?>) c.getMethod("getDebugStrings").invoke(r)) b.append(s).append(" | ");
            }
        } catch (ClassNotFoundException e) {
            b.append("vanilla sections=").append(mc.levelRenderer.getSectionStatistics()).append(" | ");
        } catch (ReflectiveOperationException | RuntimeException e) {
            b.append("sodium? ").append(e).append(" | ");
        }
        try {
            java.lang.reflect.Field f = net.minecraft.client.multiplayer.ClientLevel.class.getDeclaredField("lightUpdateQueue");
            f.setAccessible(true);
            b.append("lightQueue=").append(((java.util.Collection<?>) f.get(mc.level)).size());
        } catch (ReflectiveOperationException | RuntimeException e) {
            b.append("lightQueue? ").append(e);
        }
        b.append(" lightWork=").append(mc.level.getChunkSource().getLightEngine().hasLightWork());
        try {
            java.lang.reflect.Field f = net.minecraft.world.level.Level.class.getDeclaredField("blockEntityTickers");
            f.setAccessible(true);
            b.append(" beTickers=").append(((java.util.Collection<?>) f.get(mc.level)).size());
        } catch (ReflectiveOperationException | RuntimeException e) {
            b.append(" beTickers? ").append(e);
        }
        b.append(blockEntities(mc));
        b.append(" chunksLoadedSinceTick=").append(chunksInTick);
        chunksInTick = 0;
        b.append(" chunks=").append(mc.level.getChunkSource().getLoadedChunksCount());
        b.append(" entities=").append(mc.level.getEntityCount());
        b.append(" particles=").append(mc.particleEngine.countParticles());
        b.append(" fragments=").append(ua.zentix.incoming.client.fx.Fragments.alive());
        var server = mc.getSingleplayerServer();
        if (server != null) {
            long[] t = server.getTickTimesNanos();
            int i = (server.getTickCount() - 1) % t.length;
            b.append(" srvTick=").append(server.getTickCount()).append(" srvLastMs=").append(fmt(t[Math.max(0, i)] / 1e6));
            b.append(" srvGame=").append(server.overworld().getGameTime());
        }
        return b.toString();
    }

    private static java.util.List<?> levelList(Minecraft mc, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field f = net.minecraft.world.level.Level.class.getDeclaredField(name);
        f.setAccessible(true);
        return (java.util.List<?>) f.get(mc.level);
    }

    /** Block-entity ticker list state: the client re-creates every block entity of a chunk it receives whole. */
    private static String blockEntities(Minecraft mc) {
        try {
            int removed = 0;
            for (Object t : levelList(mc, "blockEntityTickers"))
                if (((net.minecraft.world.level.block.entity.TickingBlockEntity) t).isRemoved()) removed++;
            return " beRemoved=" + removed + " bePending=" + levelList(mc, "pendingBlockEntityTickers").size() + " beFresh="
                    + (levelList(mc, "freshBlockEntities").size() + levelList(mc, "pendingFreshBlockEntities").size());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return " beState? " + e;
        }
    }

    /** Live tickers by block-entity type, most first. */
    private static String beTypes(Minecraft mc) {
        Map<String, Integer> n = new java.util.HashMap<>();
        try {
            for (Object t : levelList(mc, "blockEntityTickers")) {
                var ticker = (net.minecraft.world.level.block.entity.TickingBlockEntity) t;
                if (!ticker.isRemoved()) n.merge(ticker.getType(), 1, Integer::sum);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "? " + e;
        }
        StringBuilder b = new StringBuilder();
        n.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(40)
                .forEach(en -> b.append(en.getKey()).append('=').append(en.getValue()).append(' '));
        return b.toString();
    }

    private static void hook(Minecraft mc) {
        var conn = mc.getConnection();
        if (conn == null) return;
        Channel ch = conn.getConnection().channel();
        if (ch == null || ch == hooked) return;
        hooked = ch;
        ch.eventLoop().execute(() -> {
            try {
                if (ch.pipeline().get("incoming_hitch") != null) return;
                ch.pipeline().addBefore("packet_handler", "incoming_hitch", new Counter());
                Incoming.LOG.info("HITCH net counter on, pipeline {}", ch.pipeline().names());
            } catch (RuntimeException e) {
                Incoming.LOG.warn("HITCH net counter not installed, pipeline {}", ch.pipeline().names(), e);
            }
        });
    }

    private static void login(ClientPlayerNetworkEvent.LoggingIn e) {
        hooked = null;
    }

    private static final class Counter extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            try {
                count(msg);
            } catch (RuntimeException ignored) {
                // a probe never breaks the connection
            }
            super.channelRead(ctx, msg);
        }
    }

    private static void add(String k, long n) {
        counts.computeIfAbsent(k, x -> new AtomicLong()).addAndGet(n);
    }

    private static void count(Object msg) {
        if (msg instanceof ClientboundBundlePacket bundle) {
            add("Bundle", 1);
            for (Packet<?> p : bundle.subPackets()) count(p);
            return;
        }
        if (msg instanceof ClientboundCustomPayloadPacket c) {
            add("payload:" + c.payload().type().id(), 1);
            return;
        }
        if (msg instanceof ClientboundSectionBlocksUpdatePacket s) {
            long[] n = {0};
            s.runUpdates((pos, st) -> {
                n[0]++;
                classify("delta", st);
            });
            add("SectionBlocksUpdate", 1);
            add("SectionBlocksUpdate.blocks", n[0]);
            bytes("SectionBlocksUpdate", w -> ClientboundSectionBlocksUpdatePacket.STREAM_CODEC.encode(w, s));
            return;
        }
        if (msg instanceof ClientboundLevelChunkWithLightPacket c) {
            long key = ChunkPos.asLong(c.getX(), c.getZ());
            Integer lit = lightAt.get(key);
            if (lit != null && clientTick - lit <= 3) add("lightUpdateWithin3TicksBeforeChunk", 1);
            bytes("ChunkWithLight", w -> ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(w, c));
        } else if (msg instanceof ClientboundLightUpdatePacket l) {
            if (lightAt.size() > 100_000) lightAt.clear();
            lightAt.put(ChunkPos.asLong(l.getX(), l.getZ()), clientTick);
            bytes("LightUpdate", w -> ClientboundLightUpdatePacket.STREAM_CODEC.encode(w, l));
        } else if (msg instanceof ClientboundBlockUpdatePacket b) {
            classify("single", b.getBlockState());
            bytes("BlockUpdate", w -> ClientboundBlockUpdatePacket.STREAM_CODEC.encode(w, b));
        }
        add(msg.getClass().getSimpleName(), 1);
    }

    /** New states in block-change packets: emitters, fire, air, unlit lamp twins (light removal comes from these). */
    @SuppressWarnings("deprecation") // the plain emission is the light source the client propagates
    private static void classify(String kind, net.minecraft.world.level.block.state.BlockState st) {
        if (st.getLightEmission() > 0) add(kind + ".emits", 1);
        if (st.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock) add(kind + ".fire", 1);
        else if (st.isAir()) add(kind + ".air", 1);
        var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock());
        if (id.getNamespace().equals("incoming") && id.getPath().startsWith("unlit")) add(kind + ".unlit", 1);
    }

    /** Wire sizes of chunk, light and delta packets ({@code -Dincoming.hitch.bytes=true}; one Netty thread reads). */
    private static final boolean BYTES = Boolean.getBoolean("incoming.hitch.bytes");
    private static final Map<Long, Integer> lightAt = new ConcurrentHashMap<>();
    private static final java.util.zip.Deflater DEFLATER = new java.util.zip.Deflater();
    private static final byte[] DEFLATED = new byte[1 << 21];

    /**
     * Wire size of a packet body (raw) and as a dedicated server's compression sends it (zlib default level from 256
     * bytes, vanilla {@code CompressionEncoder}); netty thread.
     */
    private static void bytes(String kind, java.util.function.Consumer<RegistryFriendlyByteBuf> encode) {
        // re-encoding and deflating every packet costs the measured client CPU on its Netty thread: only on request
        if (!BYTES) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), mc.level.registryAccess(), net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
        try {
            encode.accept(buf);
            int raw = buf.readableBytes() + 1;
            long packed = raw;
            if (raw >= 256) {
                byte[] in = new byte[buf.readableBytes()];
                buf.readBytes(in);
                DEFLATER.reset();
                DEFLATER.setInput(in);
                DEFLATER.finish();
                packed = 0;
                while (!DEFLATER.finished()) packed += DEFLATER.deflate(DEFLATED);
            }
            add("bytes." + kind, raw);
            add("zbytes." + kind, packed);
        } catch (RuntimeException e) {
            add("bytesFailed." + kind, 1);
        } finally {
            buf.release();
        }
    }

    /** Sampling period of the render thread while a detonation is live, ms. */
    private static final int SAMPLE_MS = Integer.getInteger("incoming.hitch.sampleMs", 5);
    private static long lastSched;
    private static final Map<String, long[]> schedPrev = new java.util.HashMap<>();

    /** Every sample of the render thread while a detonation is live: epoch ms, then the stack top-down, ';'-joined. */
    private static void sample(Thread r) {
        if (!live) return;
        StackTraceElement[] st = r.getStackTrace();
        StringBuilder b = new StringBuilder(4096).append(System.currentTimeMillis()).append(' ').append(phase).append('|');
        int n = Math.min(st.length, 90);
        for (int i = 0; i < n; i++) {
            StackTraceElement el = st[i];
            if (i > 0) b.append(';');
            b.append(el.getClassName()).append('.').append(el.getMethodName());
        }
        b.append('\n');
        write(samples, b.toString());
    }

    /**
     * CPU and run-queue wait of thread groups from /proc schedstat (truth from the kernel: a render thread waiting for a
     * core shows as wait, not as its own work). Groups by thread name prefix.
     */
    private static void sched() {
        long now = System.currentTimeMillis();
        if (now - lastSched < 250) return;
        lastSched = now;
        Map<String, long[]> sum = new TreeMap<>();
        java.io.File[] tasks = new java.io.File("/proc/self/task").listFiles();
        if (tasks == null) return;
        for (java.io.File t : tasks) {
            try {
                String comm = Files.readString(t.toPath().resolve("comm")).strip();
                String[] ss = Files.readString(t.toPath().resolve("schedstat")).strip().split(" ");
                String g = comm.replaceAll("[-#\\s]*\\d+$", "");
                long[] v = sum.computeIfAbsent(g, k -> new long[3]);
                v[0] += Long.parseLong(ss[0]);
                v[1] += Long.parseLong(ss[1]);
                v[2]++;
            } catch (IOException | RuntimeException ignored) {
                // a thread ended between list and read
            }
        }
        StringBuilder b = new StringBuilder().append(now);
        for (var en : sum.entrySet()) {
            long[] prev = schedPrev.get(en.getKey());
            long[] v = en.getValue();
            if (prev != null) {
                double cpu = (v[0] - prev[0]) / 1e6, wait = (v[1] - prev[1]) / 1e6;
                if (cpu > 0.5 || wait > 0.5) b.append(" [").append(en.getKey()).append("] ").append(fmt(cpu)).append(' ').append(fmt(wait)).append(" n").append(v[2]);
            }
            schedPrev.put(en.getKey(), v);
        }
        try {
            b.append(" load ").append(Files.readString(Path.of("/proc/loadavg")).strip());
        } catch (IOException ignored) {
            // no /proc
        }
        write(sched, b.append('\n').toString());
    }

    private static void watch() {
        long stallStart = 0;
        while (true) {
            try {
                Thread.sleep(SAMPLE_MS);
            } catch (InterruptedException e) {
                return;
            }
            Thread r = render;
            if (r == null) continue;
            try {
                sample(r);
                sched();
            } catch (RuntimeException ignored) {
                // a probe never stops
            }
            long now = System.nanoTime(), since = now - progress;
            if (since < STALL_NS) {
                if (stallStart != 0) {
                    write(stacks, "END " + System.currentTimeMillis() + " stall_ms " + fmt((now - stallStart) / 1e6 + STALL_NS / 1e6) + "\n");
                    stallStart = 0;
                }
                continue;
            }
            StringBuilder b = new StringBuilder();
            if (stallStart == 0) {
                stallStart = now;
                b.append("BEGIN ").append(System.currentTimeMillis()).append(" phase ").append(phase).append('\n');
            }
            b.append("S ").append(System.currentTimeMillis()).append(" since_ms ").append(fmt(since / 1e6)).append(" ").append(r.getState()).append('\n');
            for (StackTraceElement el : r.getStackTrace()) b.append("  ").append(el).append('\n');
            write(stacks, b.toString());
        }
    }

    private static synchronized void write(BufferedWriter w, String s) {
        try {
            w.write(s);
        } catch (IOException ignored) {
            // measurement file
        }
    }

    private static synchronized void flushAll() {
        for (BufferedWriter w : new BufferedWriter[]{frames, stacks, netOut, state, samples, sched, beTypes}) {
            try {
                if (w != null) w.flush();
            } catch (IOException ignored) {
                // measurement file
            }
        }
    }

    private static void flush() {
        for (BufferedWriter w : new BufferedWriter[]{frames, stacks, netOut, state, samples, sched, beTypes}) {
            try {
                if (w != null) w.close();
            } catch (IOException ignored) {
                // exit
            }
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
