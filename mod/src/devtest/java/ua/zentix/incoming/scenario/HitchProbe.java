package ua.zentix.incoming.scenario;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
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
 * Measurement only (wave-hitch, not for merge): {@code -Dincoming.hitch=true}.
 * <ul>
 * <li>{@code logs/hitch-frames.txt}: every frame — epoch ms, game time, frame interval ms (post→post), render part ms
 * (pre→post), client tick ms inside the frame, GPU ms of the frame (GL timestamps, read 4 frames later; -1 if not
 * ready).</li>
 * <li>{@code logs/hitch-stacks.txt}: render-thread stack every 10 ms while no frame/tick progress for over 100 ms,
 * full thread dump (locks) at stall start and every 500 ms.</li>
 * <li>{@code logs/hitch-net.txt}: per client tick, packets received (netty side) by class; section-update block
 * counts; bundle contents; custom payload ids.</li>
 * <li>{@code logs/hitch-state.txt}: per client tick from the detonation (else every 100 ticks): Sodium debug
 * strings, client light queue, entities, particles, Incoming fragments, integrated server last tick ms.</li>
 * </ul>
 */
final class HitchProbe {
    private static final long STALL_NS = 100_000_000L;
    private static volatile long progress = System.nanoTime();
    private static volatile String phase = "start";
    private static volatile Thread render;
    private static BufferedWriter frames, stacks, net, state;
    private static final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();
    private static long lastPost, pre, tickNs, tickPre;
    private static final int RING = 8;
    private static int[] qs;
    private static final long[] qFrameEpoch = new long[RING];
    private static final long[] qGame = new long[RING];
    private static int frameNo;
    private static int clientTick;
    private static Channel hooked;

    private HitchProbe() {}

    static void startIfRequested() {
        if (!Boolean.getBoolean("incoming.hitch")) return;
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("logs");
            Files.createDirectories(dir);
            frames = Files.newBufferedWriter(dir.resolve("hitch-frames.txt"));
            stacks = Files.newBufferedWriter(dir.resolve("hitch-stacks.txt"));
            net = Files.newBufferedWriter(dir.resolve("hitch-net.txt"));
            state = Files.newBufferedWriter(dir.resolve("hitch-state.txt"));
            frames.write("epoch_ms game frame_ms render_ms tick_ms gpu_ms_of(epoch_ms)\n");
        } catch (IOException e) {
            Incoming.LOG.warn("HITCH probe files", e);
            return;
        }
        NeoForge.EVENT_BUS.addListener(HitchProbe::framePre);
        NeoForge.EVENT_BUS.addListener(HitchProbe::framePost);
        NeoForge.EVENT_BUS.addListener(HitchProbe::tickPre);
        NeoForge.EVENT_BUS.addListener(HitchProbe::tickPost);
        NeoForge.EVENT_BUS.addListener(HitchProbe::login);
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

    private static void framePre(RenderFrameEvent.Pre e) {
        if (render == null) render = Thread.currentThread();
        pre = System.nanoTime();
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
                        + fmt(tickNs / 1e6) + " " + fmt(gpu) + "(" + gpuEpoch + ")\n");
            } catch (IOException ignored) {
                // measurement file
            }
        }
        tickNs = 0;
        lastPost = now;
        mark("between frames (swap, tasks/packets, ticks)");
    }

    private static void tickPre(ClientTickEvent.Pre e) {
        tickPre = System.nanoTime();
        mark("client tick");
    }

    private static void tickPost(ClientTickEvent.Post e) {
        tickNs += System.nanoTime() - tickPre;
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
            if (!snap.isEmpty()) net.write(epoch + " " + game + " " + snap + "\n");
            boolean det = !ua.zentix.incoming.client.nuclear.ClientNuclear.detonations().isEmpty();
            if (det || clientTick % 100 == 0) state.write(epoch + " " + game + " " + state(mc) + "\n");
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
            s.runUpdates((pos, st) -> n[0]++);
            add("SectionBlocksUpdate", 1);
            add("SectionBlocksUpdate.blocks", n[0]);
            return;
        }
        add(msg.getClass().getSimpleName(), 1);
    }

    private static void watch() {
        long stallStart = 0;
        while (true) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                return;
            }
            Thread r = render;
            if (r == null) continue;
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

    private static void flush() {
        for (BufferedWriter w : new BufferedWriter[]{frames, stacks, net, state}) {
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
