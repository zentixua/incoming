package ua.zentix.incoming.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.strike.StrikeWorld;

import java.util.concurrent.locks.LockSupport;

/**
 * Finish generation retained by Incoming's released area tickets before vanilla's shutdown unload loop.
 * A canceled generation task can retain an unload-queued holder until server-thread chunk tasks run;
 * vanilla's unlimited unload loop would otherwise keep requeuing that holder without polling those tasks.
 * AreaLoader remembers only busy holders in its own ticket footprints, including canceled loads. No other
 * holders or idle dimensions are drained. The world attachment owns those references until generation ends.
 */
public final class StopDrain {
    /** Wait limit for the whole server. */
    public static final long LIMIT_NANOS = 30_000_000_000L;
    /** How often to log ongoing waiting. */
    private static final long REPORT_NANOS = 5_000_000_000L;

    private StopDrain() {}

    /** {@code ServerStoppingEvent}, {@code LOWEST} priority: after the mod releases its tickets and work. */
    public static void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        if (!server.isSameThread()) return;
        if (drain(server.getAllLevels(), System.nanoTime() + LIMIT_NANOS)) return;
        StringBuilder s = new StringBuilder();
        for (ServerLevel level : server.getAllLevels()) {
            int n = inFlight(level);
            if (n > 0) s.append(' ').append(level.dimension().location()).append(" — ").append(n);
        }
        Incoming.LOG.warn("Shutdown: Incoming chunk generation not finished within {} s, holders busy:{} — exit will probably hang at \"Saving worlds\"",
                LIMIT_NANOS / 1_000_000_000L, s);
    }

    /**
     * Run chunk tasks only while Incoming generation or loading is active.
     *
     * @param deadline {@code System.nanoTime()} after which to stop
     * @return no Incoming generation remains in any world
     */
    public static boolean drain(Iterable<ServerLevel> levels, long deadline) {
        long report = System.nanoTime() + REPORT_NANOS;
        while (true) {
            boolean busy = false;
            for (ServerLevel level : levels) {
                ServerChunkCache cache = level.getChunkSource();
                if (inFlight(level) == 0) continue;
                cache.runDistanceManagerUpdates();
                while (inFlight(level) > 0 && cache.pollTask()) {
                    if (System.nanoTime() > deadline) return false;
                }
                if (inFlight(level) > 0) busy = true;
            }
            if (!busy) return true;
            long now = System.nanoTime();
            if (now > deadline) return false;
            if (now >= report) {
                report = now + REPORT_NANOS;
                int n = 0;
                for (ServerLevel level : levels) n += inFlight(level);
                Incoming.LOG.info("Shutdown: waiting for Incoming chunk generation started before exit (holders busy {})", n);
            }
            // generation stages run on generation, I/O and light threads; they submit server-thread tasks themselves
            LockSupport.parkNanos(1_000_000L);
        }
    }

    /** Busy generation retained by Incoming areas; never creates state or polls unrelated loads. */
    public static int inFlight(ServerLevel level) {
        return level.hasData(ModAttachments.STRIKE_WORLD) ? StrikeWorld.get(level).areas().inFlight(level) : 0;
    }
}
