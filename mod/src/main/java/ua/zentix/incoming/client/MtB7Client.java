package ua.zentix.incoming.client;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.compat.MtB7Columns;
import ua.zentix.incoming.compat.MtB7Log;

/** TEST ONLY. Natural LSS receipt hashes, not authoritative revisions or renderer receipts. */
public final class MtB7Client {
    private record Scope(ClientLevel level, long id) {}
    private static volatile Scope scope;
    private static MtB7Columns.Log anchors, columns;
    private static final AtomicLong omitted = new AtomicLong(), received = new AtomicLong(), hashNanos = new AtomicLong();
    private static long frame, nextAnchor, epoch;
    private MtB7Client() {}
    public static void start() {
        if (!MtB7Columns.ENABLED && !MtB7Capture.ENABLED) return;
        try {
            anchors = new MtB7Columns.Log(MtB7Log.ROOT.resolve("client-anchors.csv"),
                    "wall_ms,mono_ns,scope,tick,frame,x,y,z,lss,hash_ns,omitted,log_drops,capture_drops");
            if (MtB7Columns.ENABLED) {
                columns = new MtB7Columns.Log(MtB7Log.ROOT.resolve("lss.csv"),
                        "wall_ms,mono_ns,scope,tick,dimension,x,z,hash_ns,hashes");
                // A diagnostic consumer must not cause downloads in a client without Voxy.
                if (ModList.get().isLoaded("lss") && ModList.get().isLoaded("voxy")) MtB7Lss.subscribe();
                else Incoming.LOG.info("MT-B7 natural LSS hashes inactive: LSS and Voxy are required");
            }
            if (MtB7Capture.ENABLED) MtB7Capture.start();
            MtB7Log.finalReport(() -> anchors.add("# natural_lss_received="+received.get()+" omitted="+omitted.get()
                    +" hash_ns="+hashNanos.get()+" capture_drops="+MtB7Capture.drops()+" authoritative_expectations=none"));
            NeoForge.EVENT_BUS.addListener(MtB7Client::frame);
        } catch (IOException error) { Incoming.LOG.error("MT-B7 client initialization failed", error); }
    }
    static long scope(ClientLevel level) {
        Scope current = scope;
        return current != null && current.level() == level ? current.id() : -1;
    }
    static void omitted() { omitted.incrementAndGet(); }
    static void receipt(ClientLevel level, long scopeId, int x, int z, long wall, long began, long cost, long[] hashes) {
        if (columns == null || scope(level) != scopeId) { omitted(); return; }
        received.incrementAndGet(); hashNanos.addAndGet(cost);
        columns.add(wall+","+began+","+scopeId+","+level.getGameTime()+","+level.dimension().location()
                +","+x+","+z+","+cost+","+MtB7Columns.encode(hashes));
    }
    private static void frame(RenderFrameEvent.Post event) {
        if (!MtB7Log.beginObservation()) return;
        try { frameObserved(); } finally { MtB7Log.endObservation(); }
    }
    private static void frameObserved() {
        var mc = Minecraft.getInstance(); Scope current = scope;
        if (current == null ? mc.level != null : current.level() != mc.level) {
            scope = current = mc.level == null ? null : new Scope(mc.level, ++epoch);
            nextAnchor = 0;
        }
        frame++;
        if (current == null || mc.player == null) return;
        long now = System.nanoTime(), wall = System.currentTimeMillis();
        if (MtB7Capture.ENABLED) MtB7Capture.frame(frame, wall, now, current.level().getGameTime(), current.id());
        if (now < nextAnchor || anchors == null) return;
        nextAnchor = now + 1_000_000_000L;
        var player = mc.player;
        anchors.add(wall+","+now+","+current.id()+","+current.level().getGameTime()+","+frame+","+player.getX()
                +","+player.getY()+","+player.getZ()+","+received.get()+","+hashNanos.get()+","+omitted.get()
                +","+(anchors.dropped.get()+(columns == null ? 0 : columns.dropped.get()))+","+MtB7Capture.drops());
    }
}
