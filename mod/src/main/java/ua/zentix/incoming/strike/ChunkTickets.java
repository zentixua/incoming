package ua.zentix.incoming.strike;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;
import java.util.UUID;

/**
 * Entities outside ticking chunks do not tick; a missile travels 0.7 chunks per tick. Projectile holds its own
 * and ahead chunk along heading while flying. Uses vanilla region tickets through {@link AreaLoader} (like {@link
 * FlightTickets}: ticking only after neighbors ready), unsaved and without synchronous loading, unlike NeoForge
 * {@code TicketController.forceChunk}, which writes every change to {@code ForcedChunksSavedData} and immediately
 * completes loading. Key is projectile UUID: salvo neighbors do not remove each other's tickets.
 */
public final class ChunkTickets {
    private static final TicketType<UUID> TYPE = TicketType.create("incoming_projectile", Comparator.<UUID>naturalOrder());
    /**
     * Ticket level 33 − 2 = 31: chunk entity-ticks. This does not make it force-loaded: playerless world still
     * sleeps after 300 ticks; {@link StrikeWorld} keeps it awake during a strike.
     */
    private static final int DISTANCE = 2;

    private ChunkTickets() {}

    public static void hold(ServerLevel level, UUID owner, long chunk, boolean hold) {
        AreaLoader.Area area = new AreaLoader.Area(TYPE, new ChunkPos(chunk), DISTANCE, owner);
        if (hold) StrikeWorld.get(level).areas().hold(level, area);
        else StrikeWorld.get(level).areas().release(level, area);
    }

    /** Chunks held by projectile {@code owner} (tests). */
    public static int held(ServerLevel level, UUID owner) {
        return StrikeWorld.get(level).areas().count(TYPE, owner);
    }

    /** Whether projectile {@code owner} holds {@code chunk} (tests). */
    public static boolean holds(ServerLevel level, UUID owner, long chunk) {
        return StrikeWorld.get(level).areas().holds(new AreaLoader.Area(TYPE, new ChunkPos(chunk), DISTANCE, owner));
    }
}
