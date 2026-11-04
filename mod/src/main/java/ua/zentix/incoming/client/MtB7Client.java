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
import ua.zentix.incoming.compat.MtB7Log;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** TEST ONLY. Diagnostic geometry, never renderer fences or authoritative revisions. */
public final class MtB7Client {
    private record Expected(long[] hashes, int event, long due) {}
    private static final class Scope {
        final ClientLevel level;
        final long id;
        final ConcurrentHashMap<Long,long[]> base = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Long,Expected> expected = new ConcurrentHashMap<>();
        Scope(ClientLevel level, long id) { this.level = level; this.id = id; }
        synchronized void baseline(long key, long[] value) {
            if (base.containsKey(key)) return;
            if (base.size() < 32768) base.put(key, value); else omitted.incrementAndGet();
        }
        synchronized void expect(long key, Expected value) {
            if (expected.size() < 32768 || expected.containsKey(key)) expected.put(key, value);
            else omitted.incrementAndGet();
        }
    }
    private static volatile Scope scope;
    private static MtB7Columns.Log anchors, columns, incoming;
    private static final AtomicLong omitted = new AtomicLong(), received = new AtomicLong(), conflicts = new AtomicLong(), hashNanos = new AtomicLong();
    private static long frame, nextAnchor, epoch;
    private MtB7Client() {}
    public static void start() {
        if (!MtB7Columns.ENABLED && !MtB7Capture.ENABLED) return;
        try {
            anchors = new MtB7Columns.Log(MtB7Log.ROOT.resolve("client-anchors.csv"), "wall_ms,mono_ns,scope,tick,frame,x,y,z,lss,conflicts,hash_ns,history_omissions,log_drops,capture_drops");
            if (MtB7Columns.ENABLED) {
                columns = new MtB7Columns.Log(MtB7Log.ROOT.resolve("lss.csv"), "wall_ms,mono_ns,scope,tick,dimension,x,z,compared_sections,mismatch_sections,baseline_matches,event,due,hash_ns,hashes");
                incoming = new MtB7Columns.Log(MtB7Log.ROOT.resolve("incoming.csv"), "wall_ms,mono_ns,scope,tick,dimension,x,z,event,due,ingest_elapsed_ns,sections,retained_bytes,observation_ns,hashes");
                ClientHorizon.observer = MtB7Client::submitted;
            }
            if (MtB7Capture.ENABLED) MtB7Capture.start();
            NeoForge.EVENT_BUS.addListener(MtB7Client::frame);
        } catch (IOException error) { Incoming.LOG.error("MT-B7 client initialization failed", error); }
    }
    private static void submitted(ClientHorizon.Submission submission) {
        Scope state = scope;
        // Explicit producer level prevents late old-world work from being attributed to a new connection.
        if (state == null || state.level != submission.level() || incoming == null) { omitted.incrementAndGet(); return; }
        long start = System.nanoTime(), wall = System.currentTimeMillis(); var packet = submission.packet();
        long key = ChunkPos.asLong(packet.x(), packet.z()); Expected prior = state.expected.get(key);
        long[] union = prior == null ? MtB7Columns.empty() : prior.hashes().clone(), hashes = MtB7Columns.empty();
        try {
            for (var section : LodSections.read(packet.sections(), state.level.registryAccess().registryOrThrow(Registries.BIOME))) {
                int i = section.y()+8;
                if (i >= 0 && i < 64) union[i] = hashes[i] = MtB7Columns.hash(section.blocks());
            }
            state.expect(key, new Expected(union, packet.detonation(), packet.notBefore()));
            long cost = System.nanoTime()-start; hashNanos.addAndGet(cost);
            incoming.add(wall+","+start+","+state.id+","+submission.tick()+","+packet.dimension()+","+packet.x()+","+packet.z()+","+packet.detonation()+","+packet.notBefore()+","+submission.elapsedNanos()+","+submission.sections()+","+submission.retainedBytes()+","+cost+","+MtB7Columns.encode(hashes));
        } catch (RuntimeException error) { omitted.incrementAndGet(); }
    }
    static void lss(ClientLevel level, int x, int z, VoxelColumnData data) {
        if (!MtB7Columns.ENABLED || columns == null) return;
        Scope state = scope;
        if (state == null || state.level != level) { omitted.incrementAndGet(); return; }
        long start = System.nanoTime(), wall = System.currentTimeMillis(), key = ChunkPos.asLong(x,z);
        Expected expected = state.expected.get(key); long[] base = state.base.get(key), hashes = MtB7Columns.empty();
        int compared = 0, mismatch = 0, old = 0;
        try {
            for (var section : data.sections()) {
                int i = section.sectionY()+8; if (i < 0 || i >= 64) continue;
                hashes[i] = MtB7Columns.hash(section.section());
                if (expected != null && expected.hashes()[i] != Long.MIN_VALUE) {
                    compared++;
                    if (hashes[i] != expected.hashes()[i]) {
                        mismatch++; if (base != null && hashes[i] == base[i] && base[i] != Long.MIN_VALUE) old++;
                    }
                }
            }
            if (expected == null && base == null) state.baseline(key, hashes);
            received.incrementAndGet(); if (mismatch > 0) conflicts.incrementAndGet();
            long cost = System.nanoTime()-start; hashNanos.addAndGet(cost);
            columns.add(wall+","+start+","+state.id+","+level.getGameTime()+","+level.dimension().location()+","+x+","+z+","+compared+","+mismatch+","+old+","+(expected == null ? -1 : expected.event())+","+(expected == null ? -1 : expected.due())+","+cost+","+MtB7Columns.encode(hashes));
        } catch (RuntimeException error) { omitted.incrementAndGet(); }
    }
    private static void frame(RenderFrameEvent.Post event) {
        var mc = Minecraft.getInstance(); Scope state = scope;
        if (state == null ? mc.level != null : state.level != mc.level) {
            scope = state = mc.level == null ? null : new Scope(mc.level, ++epoch);
            nextAnchor = 0;
        }
        frame++;
        if (state == null || mc.player == null) return;
        long now = System.nanoTime(), wall = System.currentTimeMillis();
        if (MtB7Capture.ENABLED) MtB7Capture.frame(frame, wall, now, state.level.getGameTime(), state.id);
        if (now < nextAnchor || anchors == null) return;
        nextAnchor = now + 1_000_000_000L;
        var p = mc.player;
        anchors.add(wall+","+now+","+state.id+","+state.level.getGameTime()+","+frame+","+p.getX()+","+p.getY()+","+p.getZ()+","+received.get()+","+conflicts.get()+","+hashNanos.get()+","+omitted.get()+","+(anchors.dropped.get()+(columns == null ? 0 : columns.dropped.get()+incoming.dropped.get()))+","+MtB7Capture.drops());
    }
}
