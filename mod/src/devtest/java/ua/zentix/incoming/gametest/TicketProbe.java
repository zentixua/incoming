package ua.zentix.incoming.gametest;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.SortedArraySet;
import net.minecraft.world.level.ChunkPos;
import ua.zentix.incoming.strike.AreaLoader;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * ticket chunks in the vanilla queue ({@code DistanceManager.tickets}): how many of them there are with this key. ticket mod hold
 * key - UUID projectile (or player); for the download ticket {@link AreaLoader} the value is the area itself, the key is its.
 */
public final class TicketProbe {
    private static final Field TICKETS, KEY;

    static {
        try {
            TICKETS = DistanceManager.class.getDeclaredField("tickets");
            TICKETS.setAccessible(true);
            KEY = Ticket.class.getDeclaredField("key");
            KEY.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private TicketProbe() {}

    /** ticket type with the name {@code type} and the key {@code key} throughout the world. */
    public static int count(ServerLevel level, String type, UUID key) {
        return count(level, t -> t.toString().equals(type), key);
    }

    /** ticket of any type under {@code type} with key {@code key} all over the world. */
    @SuppressWarnings("unchecked")
    public static int count(ServerLevel level, Predicate<TicketType<?>> type, UUID key) {
        try {
            var map = (Long2ObjectMap<SortedArraySet<Ticket<?>>>) TICKETS.get(level.getChunkSource().chunkMap.getDistanceManager());
            int n = 0;
            for (SortedArraySet<Ticket<?>> set : map.values()) {
                for (Ticket<?> t : set) {
                    if (type.test(t.getType()) && key.equals(key(t))) n++;
                }
            }
            return n;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** ticket type with the name {@code type} all over the world, with any value. */
    @SuppressWarnings("unchecked")
    public static int count(ServerLevel level, String type) {
        try {
            var map = (Long2ObjectMap<SortedArraySet<Ticket<?>>>) TICKETS.get(level.getChunkSource().chunkMap.getDistanceManager());
            int n = 0;
            for (SortedArraySet<Ticket<?>> set : map.values()) {
                for (Ticket<?> t : set) if (t.getType().toString().equals(type)) n++;
            }
            return n;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Ticket types with key {@code key} all over the world, one per ticket (for check messages). */
    @SuppressWarnings("unchecked")
    public static List<String> types(ServerLevel level, UUID key) {
        try {
            var map = (Long2ObjectMap<SortedArraySet<Ticket<?>>>) TICKETS.get(level.getChunkSource().chunkMap.getDistanceManager());
            List<String> out = new ArrayList<>();
            for (var e : map.long2ObjectEntrySet()) {
                for (Ticket<?> t : e.getValue()) {
                    if (key.equals(key(t))) out.add(t.getType() + "@" + new ChunkPos(e.getLongKey()));
                }
            }
            return out;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Ticket key (region loading ticket has the district key). */
    public static Object key(Ticket<?> t) throws IllegalAccessException {
        Object k = KEY.get(t);
        return k instanceof AreaLoader.Area a ? a.key() : k;
    }
}
