package ua.zentix.incoming.client;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.compat.LodSections;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.nuclear.model.Yield;
import ua.zentix.incoming.scenario.lod.LodPanorama;
import ua.zentix.incoming.client.lod.TimedLodBuffer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in real Voxy ingestion experiment; synthetic city columns, real clocks, no changes to playable strikes. */
public final class LodBufferScenario {
    private static final boolean SERVER_TRANSPORT = Boolean.getBoolean("incoming.lodProbe.serverTransport");
    private static final boolean PRODUCTION = !Boolean.getBoolean("incoming.lodProbe.control");
    private static final int SECTIONS = 24, CHANGED = 4, WINDOW = 1024, PASS = 32;
    private static final boolean CHANGED_ONLY = Boolean.getBoolean("incoming.lodProbe.changedOnly");
    private static final long BYTES = 48L << 20;
    private static LodBufferScenario active;
    private final TimedLodBuffer<LodProbeWire.Column> buffer = new TimedLodBuffer<>(WINDOW, BYTES);
    private final AtomicInteger receipts = new AtomicInteger();
    private final ConcurrentLinkedQueue<String> lines = new ConcurrentLinkedQueue<>();
    private final List<LodProbeWire.Column> witnesses = new ArrayList<>();
    private final Path output = Minecraft.getInstance().gameDirectory.toPath().resolve("lod-probe");
    private volatile long now, sampled, accepted, lateSum, lateMax, peakBytes, firstSubmit, lastSubmit;
    private volatile int phase = -1, epoch, yield, total;
    private volatile long burstTick, received;
    private volatile boolean done, failed, replayRequested;
    private volatile int replayFrame = -1;
    private volatile Producer producer;
    private Thread worker;
    private boolean opened, started;
    private int ticks, lastLogged, finishedAt = -1;
    private long phaseStarted, lastFrame, frames, frameSum, frameMax;
    private int shots, frontFrames;
    private LodProbeReadback readback;
    private int witnessYield;
    private double visibleBaseline;
    private boolean visibleLogged;
    private final java.nio.FloatBuffer depthPixel = org.lwjgl.BufferUtils.createFloatBuffer(1);

    public LodBufferScenario() {
        active = this;
        if (PRODUCTION) ClientHorizon.observer = this::submitted;
        NeoForge.EVENT_BUS.addListener(this::screen);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::frame);
        // Read before FxLayer marks this frame's LOD depth consumed.
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGH, this::renderWitness);
        NeoForge.EVENT_BUS.addListener(this::serverTick);
    }
    private void screen(ScreenEvent.Init.Post e) {
        if (opened || !(e.getScreen() instanceof TitleScreen)) return;
        opened = true;
        var mc = Minecraft.getInstance();
        String world = "incoming_lod_probe_" + System.currentTimeMillis(); // never delete/reuse a user world
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        LevelSettings settings = new LevelSettings(world, GameType.SPECTATOR, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(20261104L, false, false), registries -> {
            var biomes = registries.lookupOrThrow(Registries.BIOME);
            var flat = FlatLevelGeneratorSettings.getDefault(biomes, registries.lookupOrThrow(Registries.STRUCTURE_SET), registries.lookupOrThrow(Registries.PLACED_FEATURE))
                    .withBiomeAndLayers(List.of(new FlatLayerInfo(1, Blocks.BEDROCK), new FlatLayerInfo(127, Blocks.STONE)), Optional.empty(), biomes.getOrThrow(Biomes.PLAINS));
            WorldDimensions dims = WorldPresets.createNormalWorldDimensions(registries);
            return dims.replaceOverworldGenerator(registries, new FlatLevelSource(flat));
        }, e.getScreen());
    }
    public static void receive(LodProbeWire.Column p) {
        LodBufferScenario s = active;
        if (s == null || s.done) return;
        if (p.column().sections().length == 0) {
            s.buffer.stop();
            if (PRODUCTION) ClientHorizon.reset();
            s.phase = p.phase(); s.epoch = p.epoch(); s.yield = p.yield(); s.total = p.total();
            s.burstTick = p.due(); s.accepted = s.received = s.lateSum = s.lateMax = s.sampled = 0;
            s.firstSubmit = s.lastSubmit = s.peakBytes = 0; s.phaseStarted = System.nanoTime();
            synchronized (s.witnesses) { s.witnesses.clear(); }
            s.shots = s.frontFrames = 0;
            Minecraft.getInstance().execute(() -> { s.frames = s.frameSum = s.frameMax = 0; s.lastFrame = 0; });
            s.log("BEGIN epoch=%d phase=%d kt=%d columns=%d burstTick=%d", p.epoch(), p.phase(), p.yield(), p.total(), p.due());
            Minecraft.getInstance().execute(s::camera);
            return;
        }
        if (p.epoch() != s.epoch) { s.log("OLD_PACKET epoch=%d", p.epoch()); return; }
        if (PRODUCTION) {
            var column = p.column();
            if (!ClientHorizon.offer(Minecraft.getInstance().level, new S2C.VoxyChunk(column.dimension(), column.x(), column.z(), column.sections(), p.due()))) {
                s.fail("production buffer admission exceeded");
            }
            s.received++;
            s.peakBytes = Math.max(s.peakBytes, ClientHorizon.bytes());
            return;
        }
        int sections = p.phase() == 1 && CHANGED_ONLY ? CHANGED : SECTIONS;
        var t = s.buffer.reserve(net.minecraft.world.level.ChunkPos.asLong(p.column().x(), p.column().z()), p.due(), p.column().sections().length, sections);
        if (t == null) { s.fail("buffer admission exceeded"); return; }
        s.buffer.complete(t, p);
        s.received++;
        s.peakBytes = Math.max(s.peakBytes, s.buffer.bytes());
    }
    private void submitted(ClientHorizon.Submission s) {
        long end = System.nanoTime(), begin = end - s.elapsedNanos();
        if (firstSubmit == 0) firstSubmit = begin;
        lastSubmit = end;
        long late = phase == 1 ? Math.max(0, s.tick() - s.packet().notBefore()) : 0;
        lateSum += late;
        lateMax = Math.max(lateMax, late);
        accepted++;
        peakBytes = Math.max(peakBytes, s.retainedBytes());
        if (accepted == 1 || accepted % Math.max(1, total / 32) == 0) {
            synchronized (witnesses) { witnesses.add(new LodProbeWire.Column(epoch, phase, s.packet().notBefore(), total, yield, s.packet())); }
        }
        receipts.incrementAndGet();
        log("SUBMIT epoch=%d phase=%d x=%d z=%d due=%d now=%d elapsedNs=%d sections=%d changed=%d bytes=%d production=true",
                epoch, phase, s.packet().x(), s.packet().z(), s.packet().notBefore(), s.tick(), s.elapsedNanos(), s.sections(),
                phase == 1 ? CHANGED : SECTIONS, s.packet().sections().length);
    }
    public static void ack(LodProbeWire.Ack p) {
        if (active != null && active.producer != null) active.producer.ack(p);
    }
    private void serverTick(ServerTickEvent.Post e) {
        Producer p = producer;
        if (p != null) p.tick();
    }
    private void tick(ClientTickEvent.Post e) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        now = mc.level.getGameTime(); ticks++;
        if (!started && ticks > 100) {
            started = true;
            if (!VoxyIngest.available()) { fail("Voxy ingestion unavailable"); return; }
            mc.options.hideGui = true; mc.options.fov().set(90); mc.options.renderDistance().set(3);
            mc.options.pauseOnLostFocus = false;
            worker = new Thread(this::work, "Incoming LOD buffer probe"); worker.setDaemon(true); worker.start();
            mc.getSingleplayerServer().execute(() -> {
                mc.getSingleplayerServer().overworld().setDayTime(6000);
                producer = new Producer(mc.getSingleplayerServer().overworld(), mc.player.getUUID());
            });
        }
        int count = receipts.getAndSet(0);
        if (count > 0) PacketDistributor.sendToServer(new LodProbeWire.Ack(epoch, count));
        if (ticks - lastLogged >= 20 && phase >= 0) {
            lastLogged = ticks;
            log("PROGRESS epoch=%d phase=%d kt=%d now=%d received=%d submitted=%d held=%d bytes=%d backlog=%s lateMaxTicks=%d heapBytes=%d",
                    epoch, phase, yield, now, received, accepted, buffer.size(), buffer.bytes(), VoxyIngest.backlogged(), lateMax,
                    Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
            flush();
        }
        if (done && finishedAt < 0) { finishedAt = ticks; flush(); }
        if (finishedAt >= 0 && ticks - finishedAt > 40) mc.stop();
        if (ticks > 24000) fail("scenario exceeded 20 minutes without completing");
    }
    private void camera() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        double r = radius(yield);
        // One fixed 90-degree panorama per yield, outside vanilla loaded chunks and within ~8 km of witnesses.
        double y = 180, z = 8 + r + 600;
        mc.player.setPos(8, y, z);
        mc.player.setYRot(180);
        mc.player.setXRot((float) Math.toDegrees(Math.atan2(y - 80, z - (8 + r * .95))));
        mc.player.yRotO = mc.player.getYRot(); mc.player.xRotO = mc.player.getXRot();
        var server = mc.getSingleplayerServer();
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(mc.player.getUUID());
            if (p != null) p.teleportTo(server.overworld(), 8, y, z, 180, mc.player.getXRot());
        });
    }
    private void frame(RenderFrameEvent.Post e) {
        long t = System.nanoTime();
        if (lastFrame != 0) { long d = t - lastFrame; frameSum += d; frameMax = Math.max(frameMax, d); frames++; }
        lastFrame = t;
        if (phase == 2 && replayFrame >= 0 && replayFrame < 90) {
            var mc = Minecraft.getInstance();
            String name = String.format(Locale.ROOT, "replay_%03d_tick%d.png", replayFrame++, now);
            Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), c -> {});
            log("REPLAY_FRAME file=%s now=%d", name, now);
        }
        if (phase != 1 || failed) return;
        // Before and across the measured front. Frame timestamps are recorded, not inferred from send logs.
        double r = radius(yield), h = Yield.optimalBurstHeight(yield);
        Detonation d = detonation(yield, burstTick);
        long first = burstTick + (long) Math.ceil(d.arrivalTicks(Math.hypot(r * .8, h)));
        long last = burstTick + (long) Math.ceil(d.arrivalTicks(Math.hypot(r, h)));
        if (now >= (first + last) / 2 - 10 && frontFrames < 90) {
            var mc = Minecraft.getInstance();
            String name = String.format(Locale.ROOT, "front_%d_%03d_tick%d.png", yield, frontFrames++, now);
            Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), c -> {});
            log("FRONT_FRAME file=%s now=%d", name, now);
        }
        long[] at = {first - 20, first, first + 2, first + 10, (first + last) / 2, last, last + 20, last + 100};
        if (shots < at.length && now >= at[shots]) {
            var mc = Minecraft.getInstance();
            String name = String.format(Locale.ROOT, "lod_%d_%d_tick%d.png", yield, shots++, now);
            Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), c -> {});
            log("FRAME file=%s now=%d first=%d last=%d submitted=%d fps=%d", name, now, first, last, accepted, mc.getFps());
        }
    }
    /** Read only the existing Voxy depth texture: database readiness alone is not rendered visibility. */
    private void renderWitness(net.neoforged.neoforge.client.event.RenderLevelStageEvent e) {
        if (e.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_LEVEL || phase < 0 || phase > 1) return;
        if (witnessYield != yield) { witnessYield = yield; visibleBaseline = 0; visibleLogged = false; }
        if (phase == 1 && visibleLogged) return;
        var view = new ua.zentix.incoming.client.render.LodDepth.View();
        if (!ua.zentix.incoming.client.render.LodDepth.current(view)) return;
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        int z = (int) Math.floor(radius(yield) / 16);
        Vec3 delta = new Vec3(8, 120.5, z * 16 + 8).subtract(camera.getPosition());
        var look = camera.getLookVector(); var left = camera.getLeftVector(); var up = camera.getUpVector();
        var projected = new org.joml.Vector4f((float) -(delta.x * left.x + delta.y * left.y + delta.z * left.z),
                (float) (delta.x * up.x + delta.y * up.y + delta.z * up.z),
                (float) -(delta.x * look.x + delta.y * look.y + delta.z * look.z), 1);
        new org.joml.Matrix4f(view.inverseProjection).invert().transform(projected);
        if (projected.w <= 0) return;
        float nx = projected.x / projected.w, ny = projected.y / projected.w;
        if (Math.abs(nx) >= .98 || Math.abs(ny) >= .98) return;
        int width = org.lwjgl.opengl.GL45.glGetTextureLevelParameteri(view.texture, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH);
        int height = org.lwjgl.opengl.GL45.glGetTextureLevelParameteri(view.texture, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT);
        int px = (int) ((nx * .5 + .5) * width), py = (int) ((ny * .5 + .5) * height);
        double nearest = Double.POSITIVE_INFINITY;
        long before = System.nanoTime();
        for (int dx = -1; dx <= 1; dx++) {
            depthPixel.clear();
            org.lwjgl.opengl.GL45.glGetTextureSubImage(view.texture, 0, px + dx, py, 0, 1, 1, 1,
                    org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT, org.lwjgl.opengl.GL11.GL_FLOAT, depthPixel);
            float depth = depthPixel.get(0);
            if (depth == view.empty || depth == view.world) continue;
            var hit = new org.joml.Vector4f((float) ((px + dx + .5) / width * 2 - 1), (float) ((py + .5) / height * 2 - 1),
                    view.negativeOneToOne ? depth * 2 - 1 : depth, 1);
            view.inverseProjection.transform(hit);
            nearest = Math.min(nearest, Math.sqrt(hit.x * hit.x + hit.y * hit.y + hit.z * hit.z) / Math.abs(hit.w));
        }
        if (phase == 0 && visibleBaseline == 0 && Math.abs(nearest - delta.length()) < 40) {
            visibleBaseline = nearest;
            log("VISIBLE_BASELINE kt=%d tick=%d pixelX=%d pixelY=%d depthMeters=%.4f targetMeters=%.4f readNs=%d", yield, now, px, py, nearest, delta.length(), System.nanoTime() - before);
        }
        if (phase == 1 && visibleBaseline > 0 && nearest > visibleBaseline + 20 && nearest < visibleBaseline + 800) {
            visibleLogged = true;
            long due = burstTick + (long) Math.ceil(detonation(yield, burstTick).arrivalTicks(Math.hypot(z * 16.0, Yield.optimalBurstHeight(yield))));
            double seen = now + e.getPartialTick().getGameTimeDeltaPartialTick(false);
            log("VISIBLE_RUIN kt=%d due=%d seen=%.4f latenessTicks=%.4f depthMeters=%.4f baseline=%.4f readNs=%d footprintPixels=3", yield, due, seen, seen - due, nearest, visibleBaseline, System.nanoTime() - before);
            shot("visible_ruin_" + yield);
        }
    }
    private void work() {
        try {
            while (!done) {
                if (replayRequested) { replay(); break; }
                if (!PRODUCTION && !VoxyIngest.backlogged()) {
                    for (int n = 0; n < PASS; n++) {
                        var r = buffer.take(now);
                        if (r == null) break;
                        if (buffer.current(r.ticket())) {
                            long begin = System.nanoTime();
                            VoxyIngest.ingest(Minecraft.getInstance().level, r.value().column()); // direct-ingestion control
                            long end = System.nanoTime();
                            if (firstSubmit == 0) firstSubmit = begin;
                            lastSubmit = end;
                            long late = phase == 1 ? Math.max(0, now - r.ticket().due()) : 0;
                            lateSum += late; lateMax = Math.max(lateMax, late); accepted++;
                            if (accepted == 1 || accepted % Math.max(1, total / 32) == 0) {
                                synchronized (witnesses) { witnesses.add(r.value()); }
                            }
                            log("SUBMIT epoch=%d phase=%d x=%d z=%d due=%d now=%d elapsedNs=%d sections=%d changed=%d bytes=%d",
                                    epoch, phase, r.value().column().x(), r.value().column().z(), r.ticket().due(), now, end - begin, r.ticket().sections(), phase == 1 ? CHANGED : SECTIONS, r.ticket().bytes());
                        }
                        buffer.finish(r); receipts.incrementAndGet();
                    }
                }
                if (ticks % 2 == 0 && phase >= 0 && phase <= 1) witness();
                Thread.sleep(2);
            }
        } catch (Exception e) { fail("worker: " + e); }
    }
    private void witness() throws ReflectiveOperationException {
        if (readback == null) readback = new LodProbeReadback(Minecraft.getInstance().level);
        List<LodProbeWire.Column> checking;
        synchronized (witnesses) { checking = List.copyOf(witnesses); }
        for (var p : checking) {
            // acquireIfExists may block on disk. Never hold the monitor used by Producer.tick here.
            boolean matches = readback.air(p.column().x() * 16 + 8, 120, p.column().z() * 16 + 8) == (p.phase() == 1);
            synchronized (witnesses) {
                if (matches && p.epoch() == epoch && witnesses.remove(p)) {
                    log("DB_READY epoch=%d x=%d z=%d due=%d seen=%d lateTicks=%d", epoch, p.column().x(), p.column().z(), p.due(), now, Math.max(0, now - p.due()));
                    sampled++;
                }
            }
        }
    }

    /** Public LSS dispatch is observational: consumers cannot veto another consumer. Repair only after ingest drains. */
    private void replay() throws Exception {
        phase = 2;
        ClientHorizon.observer = null;
        var mc = Minecraft.getInstance();
        int x = 0, z = (int) (radius(1000) * .9 / 16);
        var packet = new S2C.VoxyChunk(mc.level.dimension().location(), x, z, producer.ruined);
        mc.execute(() -> {
            mc.player.setPos(8, 160, z * 16 + 608);
            mc.player.setYRot(180); mc.player.setXRot(4);
            mc.player.yRotO = 180; mc.player.xRotO = 4;
            var server = mc.getSingleplayerServer();
            server.execute(() -> server.getPlayerList().getPlayer(producer.player).teleportTo(server.overworld(), 8, 160, z * 16 + 608, 180, 4));
        });
        waitTicks(100);
        shot("replay_before");
        var sections = LodSections.read(producer.intact, mc.level.registryAccess().registryOrThrow(Registries.BIOME));
        var data = sections.stream().map(v -> new dev.vox.lss.api.VoxelColumnData.SectionData(v.y(), v.blocks(), v.blockLight(), v.skyLight()))
                .toArray(dev.vox.lss.api.VoxelColumnData.SectionData[]::new);
        if (PRODUCTION) {
            // Prime the production repair cache for this coordinate even if the panorama evicted it.
            ClientHorizon.offer(mc.level, packet);
            waitTicks(20);
            replayFrame = 0; // recording starts BEFORE the stale producer is dispatched
            waitTicks(2);
            for (int round = 0; round < 2; round++) {
                long began = System.nanoTime();
                dev.vox.lss.api.LSSApi.dispatchColumn(mc.level, mc.level.dimension(), x, z, new dev.vox.lss.api.VoxelColumnData(data, 1));
                log("REPLAY_DISPATCH round=%d tick=%d production=true", round, now);
                waitTicks(40);
                if (!readback.air(x * 16 + 8, 120, z * 16 + 8)) { fail("production replay repair did not converge"); return; }
                log("REPLAY_REPAIRED round=%d tick=%d elapsedMs=%.4f production=true", round, now, (System.nanoTime() - began) / 1e6);
            }
            waitTicks(80);
            log("COMPLETE production panorama and replay probe");
            done = true;
            return;
        }
        for (int round = 0; round < 2; round++) {
            long sentAt = now, began = System.nanoTime();
            dev.vox.lss.api.LSSApi.dispatchColumn(mc.level, mc.level.dimension(), x, z, new dev.vox.lss.api.VoxelColumnData(data, 1));
            log("REPLAY_DISPATCH round=%d tick=%d oldTimestamp=1 publicConsumerCanVeto=false", round, sentAt);
            long limit = System.nanoTime() + 10_000_000_000L;
            while (readback.air(x * 16 + 8, 120, z * 16 + 8) && System.nanoTime() < limit) Thread.sleep(2);
            if (readback.air(x * 16 + 8, 120, z * 16 + 8)) { fail("LSS replay did not reach the Voxy database"); return; }
            log("REPLAY_OVERWROTE round=%d tick=%d elapsedMs=%.4f", round, now, (System.nanoTime() - began) / 1e6);
            if (round == 0) { waitTicks(40); shot("replay_stale_unrepaired"); waitTicks(2); }
            else replayFrame = 0;
            while (VoxyIngest.backlogged()) Thread.sleep(2);
            long repairAt = now, repairNs = System.nanoTime();
            VoxyIngest.ingest(mc.level, packet);
            limit = System.nanoTime() + 10_000_000_000L;
            while (!readback.air(x * 16 + 8, 120, z * 16 + 8) && System.nanoTime() < limit) Thread.sleep(2);
            if (!readback.air(x * 16 + 8, 120, z * 16 + 8)) { fail("retained column repair did not converge"); return; }
            log("REPLAY_REPAIRED round=%d callTick=%d seenTick=%d repairMs=%.4f totalMs=%.4f", round, repairAt, now,
                    (System.nanoTime() - repairNs) / 1e6, (System.nanoTime() - began) / 1e6);
            waitTicks(80); shot("replay_repaired_" + round);
        }
        log("COMPLETE panorama and stale replay probe"); done = true;
    }
    private void waitTicks(int count) throws InterruptedException {
        long target = now + count;
        while (!done && now < target) Thread.sleep(5);
    }
    private void shot(String name) {
        Minecraft.getInstance().execute(() -> {
            var mc = Minecraft.getInstance();
            Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), c -> {});
            log("REPLAY_SHOT file=%s.png tick=%d", name, now);
        });
    }
    private void log(String format, Object... args) { lines.add(String.format(Locale.ROOT, format, args)); }
    private synchronized void flush() {
        try {
            Files.createDirectories(output);
            List<String> batch = new ArrayList<>();
            for (String line; (line = lines.poll()) != null;) batch.add(line);
            Files.write(output.resolve("events.tsv"), batch, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) { Incoming.LOG.error("LODPROBE log failed", e); failed = done = true; }
    }
    private void fail(String reason) { Incoming.LOG.error("LODPROBE FAILED {}", reason); log("FAILED %s", reason); failed = done = true; }
    private static double radius(int kt) { return HobBlast.rangeFor(5, Yield.optimalBurstHeight(kt), kt); }
    private static Detonation detonation(int kt, long at) {
        return new Detonation(1, new Vec3(8, 64 + Yield.optimalBurstHeight(kt), 8), 64, kt, false, at, 0, 0, 20000, 1, 1, false, .25f);
    }

    private final class Producer {
        final ServerLevel level;
        final java.util.UUID player;
        final int[] yields = Boolean.getBoolean("incoming.lodProbe.short") ? new int[]{15} : new int[]{15, 100, 1000};
        final byte[] intact, ruined;
        LodPanorama envelope;
        int which, stage, generation = 1, credits, sent, acked, count;
        boolean prepared, exhausted;
        long start, completed = -1;
        LodPanorama.Column pending;
        Detonation event;
        Producer(ServerLevel level, java.util.UUID player) {
            this.level = level; this.player = player;
            intact = template(level, false); ruined = template(level, true);
            reset();
        }
        void reset() {
            envelope = new LodPanorama(radius(yields[which]));
            prepared = exhausted = false; pending = null; sent = acked = count = 0; completed = -1;
            credits = Math.min(WINDOW, (int) (BYTES / Math.max(intact.length, ruined.length)));
            event = detonation(yields[which], level.getGameTime() + 200);
        }
        void ack(LodProbeWire.Ack p) { if (p.epoch() == generation) { credits += p.count(); acked += p.count(); } }
        void tick() {
            if (done || replayRequested) return;
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(player);
            if (p == null) return;
            if (!prepared) {
                if (!envelope.prepare(16)) return;
                prepared = true; count = Math.toIntExact(envelope.columns()); start = level.getGameTime();
                event = detonation(yields[which], start + 200);
                PacketDistributor.sendToPlayer(p, new LodProbeWire.Column(generation, stage, event.gameTime(), count, yields[which], new S2C.VoxyChunk(level.dimension().location(), 0, 0, new byte[0])));
                log("ENVELOPE kt=%d radius=%.3f columns=%d headers=%d intactBytes=%d ruinBytes=%d window=%d sectionBudget=%d ruinSections=%d",
                        yields[which], radius(yields[which]), count, envelope.headers(), intact.length, ruined.length, credits, PASS * SECTIONS, CHANGED_ONLY ? CHANGED : SECTIONS);
            }
            for (int n = 0; n < 512 && credits > 0 && !exhausted; n++) {
                var c = pending != null ? pending : envelope.next();
                if (c == null) { exhausted = true; break; }
                long due = stage == 0 ? Long.MIN_VALUE : event.gameTime() + (long) Math.ceil(event.arrivalTicks(Math.hypot(c.radius(), Yield.optimalBurstHeight(yields[which]))));
                var packet = new LodProbeWire.Column(generation, stage, due, count, yields[which], new S2C.VoxyChunk(level.dimension().location(), c.x(), c.z(), stage == 0 ? intact : ruined));
                // Integrated connections may bypass byte encoding. Explicit round-trip measures the actual codec too.
                var bytes = Unpooled.buffer();
                try { LodProbeWire.Column.CODEC.encode(bytes, packet); packet = LodProbeWire.Column.CODEC.decode(bytes); }
                finally { bytes.release(); }
                if (SERVER_TRANSPORT) {
                    var column = packet.column();
                    if (!ua.zentix.incoming.compat.VoxyLods.offerPrepared(p,
                            new S2C.VoxyChunk(column.dimension(), column.x(), column.z(), column.sections(), due))) {
                        pending = c;
                        break;
                    }
                    received++; // admitted to the production server queue, separately from observer-confirmed submission
                } else PacketDistributor.sendToPlayer(p, packet);
                pending = null;
                credits--; sent++;
            }
            if (exhausted && acked >= count && !VoxyIngest.backlogged()) {
                if (completed < 0) {
                    completed = level.getGameTime();
                    shot("panorama_" + yields[which] + "_" + stage + "_complete");
                    log("SUMMARY kt=%d phase=%d columns=%d ticks=%d submitSeconds=%.6f lateMeanTicks=%.4f lateMaxTicks=%d peakBytes=%d dbWitnesses=%d frameMeanMs=%.4f frameMaxMs=%.4f",
                            yields[which], stage, count, completed - start, (lastSubmit - firstSubmit) / 1e9,
                            count == 0 ? 0 : lateSum / (double) count, lateMax, peakBytes, sampled, frames == 0 ? 0 : frameSum / (double) frames / 1e6, frameMax / 1e6);
                }
                if (level.getGameTime() - completed < 120) return;
                synchronized (witnesses) {
                    if (!witnesses.isEmpty()) {
                        if (level.getGameTime() - completed > 1200) fail("database witnesses never converged");
                        return;
                    }
                }
                if (stage == 0) stage = 1;
                else { stage = 0; which++; }
                if (which == yields.length) { log("COMPLETE all panorama envelopes"); if (Boolean.getBoolean("incoming.lodProbe.short")) done = true; else replayRequested = true; return; }
                generation++; reset();
            }
        }
    }
    private static byte[] template(ServerLevel level, boolean ruin) {
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
        try {
            // Existing wire format supports a section span. The fixture's verified baseline has changes only
            // in sections 4..7, including the three now-empty sections. Unknown baselines still need full columns.
            int first = ruin && CHANGED_ONLY ? 4 : -4;
            int count = ruin && CHANGED_ONLY ? CHANGED : SECTIONS;
            out.writeVarInt(first); out.writeVarInt(count);
            for (int sy = first; sy < first + count; sy++) {
                LevelChunkSection s = new LevelChunkSection(biomes);
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    int h = sy * 16 + y;
                    if (sy == 3) s.setBlockState(x, y, z, Blocks.STONE.defaultBlockState(), false);
                    else if (ruin && h >= 64 && h < 67 + ((x * 7 + z * 13) & 3)) s.setBlockState(x, y, z, Blocks.COBBLESTONE.defaultBlockState(), false);
                    else if (!ruin && h >= 64 && h < 128 && x >= 2 && x <= 13 && z >= 2 && z <= 13) {
                        boolean wall = x == 2 || x == 13 || z == 2 || z == 13;
                        if (h % 8 == 0 || wall) s.setBlockState(x, y, z, wall && h % 8 > 1 ? Blocks.GLASS.defaultBlockState() : Blocks.WHITE_CONCRETE.defaultBlockState(), false);
                    }
                }
                out.writeByte(s.hasOnlyAir() ? 8 : 9); // same LodSections wire flags: blocks + full sky
                if (!s.hasOnlyAir()) s.write(out);
            }
            byte[] data = new byte[out.readableBytes()]; out.readBytes(data); return data;
        } finally { out.release(); }
    }
}
