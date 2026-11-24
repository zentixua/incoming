package ua.zentix.incoming.compat;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.net.S2C;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;

/**
 * Flashback replay server: integrated server reading a recording and sending its packets to viewers. Mod packets
 * are sent as read ({@code ReplayServer.handleGamePacket}); while seeking, one server tick applies a recording-chunk
 * snapshot (Flashback NeoForge Fixed stores the last packet of each type) and reads packets from its start to the
 * destination, the last 20 ticks one at a time with {@code fastForwarding} set. Each read ends with a server tick
 * ({@code runUpdates} → {@code tickServer}), so at its end the mod sends viewers a {@link S2C.ReplayTick} marker: recording
 * position and whether the server is seeking, from {@code doClientRendering}: snapshot applied or tick-by-tick catch-up.
 * During video export Flashback retains the snapshot flag until export ends — seeking there uses only {@code fastForwarding},
 * and the client detects position jumps itself. The client processes one connection's packets in order, so everything
 * arriving before the marker was read no later than that server tick; Flashback NeoForge Fixed accumulates packets from
 * snapshot start and sends them at replay-server tick end, after all read markers — so the client awaits another marker
 * (deciding whether an event is live, {@code client.replay.Replay}).
 * <p>
 * Flashback has no mod API and is optional and client-only: access public replay-server methods and field through
 * reflection; replay server and client share a JVM and see the same fields. Without Flashback or those
 * members there are no markers, and the client accepts events immediately.
 */
public final class FlashbackReplay {
    private static boolean looked;
    @Nullable
    private static volatile Class<?> serverClass;
    @Nullable
    private static Method place, rendering, viewers;
    @Nullable
    private static Field fastForwarding, exportJob;

    private static Method partialPosition, exportPosition, settings, startTick, getConfig, metadata;
    private static Field internal, recent, identifier;

    public record Recording(java.nio.file.Path path, java.util.UUID id) {}

    /** openReplayWorld puts the opened archive first, including recordings outside the replay folder. */
    @Nullable
    public static Recording recording(MinecraftServer server) {
        if (!isReplay(server) || recent == null) return null;
        try {
            Object config = internal.get(getConfig.invoke(null));
            var paths = (java.util.List<?>) recent.get(config);
            if (paths.isEmpty()) return null;
            return new Recording(java.nio.file.Path.of((String) paths.getFirst()),
                    (java.util.UUID) identifier.get(metadata.invoke(server)));
        } catch (ReflectiveOperationException | RuntimeException e) {
            Incoming.LOG.warn("Cannot locate the current Flashback recording", e);
            return null;
        }
    }

    /** The editor's fractional playback tick, or the exact video frame's position during export. */
    public static double position(MinecraftServer server, int marker) {
        if (!isReplay(server)) return marker;
        try {
            Object job = exportJob.get(null);
            if (job != null && startTick != null) return (int) startTick.invoke(settings.invoke(job)) + ((Number) exportPosition.invoke(job)).doubleValue();
            if (partialPosition == null) return marker;
            return ((Number) partialPosition.invoke(server)).doubleValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return marker;
        }
    }

    private FlashbackReplay() {}

    /** {@code s} is a Flashback replay server understood by the mod. */
    public static boolean isReplay(@Nullable MinecraftServer s) {
        if (s == null) return false;
        if (!looked) find();
        Class<?> c = serverClass;
        return c != null && c.isInstance(s);
    }

    /** End of replay-server tick (and each seek read): send viewers a recording-position marker. */
    public static void onServerTick(ServerTickEvent.Post e) {
        MinecraftServer s = e.getServer();
        if (!isReplay(s)) return;
        try {
            boolean seeking = exportJob.get(null) != null ? fastForwarding.getBoolean(s) : !(boolean) rendering.invoke(s);
            S2C.ReplayTick tick = new S2C.ReplayTick((int) place.invoke(s), seeking);
            for (Object v : (Collection<?>) viewers.invoke(s)) {
                if (v instanceof ServerPlayer player) PacketDistributor.sendToPlayer(player, tick);
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            off(ex);
        }
    }

    /**
     * Flashback exports video ({@code Flashback.EXPORT_JOB}): frames are not real-time, so frame-budget controls
     * (nuclear pall detail) must not reduce quality. Without Flashback: false.
     */
    public static boolean exporting() {
        if (!looked) find();
        Field job = exportJob;
        if (serverClass == null || job == null) return false;
        try {
            return job.get(null) != null;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    private static synchronized void find() {
        if (looked) return;
        try {
            if (!ModList.get().isLoaded("flashback")) return;
            // Without class initialization: ordinary gameplay has no replay server.
            Class<?> c = Class.forName("com.moulberry.flashback.playback.ReplayServer", false, FlashbackReplay.class.getClassLoader());
            place = c.getMethod("getReplayTick");
            rendering = c.getMethod("doClientRendering");
            viewers = c.getMethod("getReplayViewers");
            fastForwarding = c.getField("fastForwarding");
            exportJob = Class.forName("com.moulberry.flashback.Flashback", false, FlashbackReplay.class.getClassLoader()).getField("EXPORT_JOB");
            try {
                partialPosition = c.getMethod("getPartialReplayTick");
                metadata = c.getMethod("getMetadata");
                identifier = metadata.getReturnType().getField("replayIdentifier");
                getConfig = exportJob.getDeclaringClass().getMethod("getConfig");
                internal = getConfig.getReturnType().getField("internal");
                recent = internal.getType().getField("recentReplays");
                exportPosition = exportJob.getType().getMethod("getCurrentTickDouble");
                settings = exportJob.getType().getMethod("getSettings");
                startTick = settings.getReturnType().getMethod("startTick");
            } catch (ReflectiveOperationException ex) {
                Incoming.LOG.warn("Flashback recording clock integration is incomplete: {}", ex.toString());
            }
            serverClass = c;
        } catch (ReflectiveOperationException | LinkageError ex) {
            off(ex);
        } finally {
            looked = true;
        }
    }

    private static void off(Throwable ex) {
        if (serverClass == null && looked) return;
        serverClass = null;
        Incoming.LOG.warn("Flashback: cannot read replay server members ({}) — the mod will not detect replay seeking, replay events play as live", ex.toString());
    }
}
