package ua.zentix.incoming.client.flight;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import ua.zentix.incoming.entity.BomberEntity;
import ua.zentix.incoming.entity.InterceptorEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.strike.Hearing;
import ua.zentix.incoming.strike.WeaponType;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client projectile paths by UUID ({@link FlightTrack}): entity while client has it, otherwise server packets
 * {@link S2C.FarFlights}; one path switches seamlessly between them. Used by projectile sound
 * ({@code ClientSounds}) and far view ({@code client.far.FarFlightRenderer}). Time is own client ticks
 * ({@link #now}), stopped while paused.
 */
public final class FlightTracks {
    private static final Map<UUID, FlightTrack> TRACKS = new HashMap<>();
    /** SAM missile paths from client view, separate: no weapon assigned ({@link FlightTrack#interceptor}). */
    private static final Map<UUID, FlightTrack> INTERCEPTORS = new HashMap<>();
    /**
     * No new data for this many ticks (entity gone, no packets) ends flight. Sound falls silent and distant
     * projectile vanishes sooner, as soon as required time lacks data ({@link FlightTrack#covers}); this timeout
     * prevents a server pausing a second from cutting distant sound.
     */
    private static final int STALE = 40;
    /**
     * Ended flight retained this long: sound travels no longer to ear ({@link Hearing#MAX_DELAY}), distant trail
     * fades in that time.
     */
    public static final int RINGOUT = Hearing.MAX_DELAY;
    private static long tick;
    /** World where paths were recorded: dimension change gives new world; old paths do not fly there. */
    private static ClientLevel world;

    private FlightTracks() {}

    /** Current path time, client ticks. */
    public static long now() {
        return tick;
    }

    /** Once per client tick (unpaused): entity samples, end flights with no recent data. */
    public static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            reset();
            world = null;
            return;
        }
        if (level != world) {
            reset();
            world = level;
        }
        tick++;
        for (Entity e : level.entitiesForRendering()) {
            if (e instanceof StrikeProjectile p && p.isActive()) track(p.getUUID(), p.weapon(), p instanceof BomberEntity).record(tick, p);
            else if (e instanceof InterceptorEntity i && !i.isRemoved()) {
                FlightTrack t = INTERCEPTORS.get(i.getUUID());
                if (t != null && t.confirmedEnd()) continue;
                if (t == null || t.isDead()) INTERCEPTORS.put(i.getUUID(), t = FlightTrack.interceptor(i.getUUID()));
                t.record(tick, i);
            }
        }
        expire(TRACKS, tick);
        expire(INTERCEPTORS, tick);
    }

    /** No data beyond {@link #STALE} ends flight; ended flights forgotten after {@link #RINGOUT}. */
    static void expire(Map<UUID, FlightTrack> tracks, long tick) {
        tracks.values().removeIf(t -> {
            // Entity gone: distant path may arrive with next packet (every 4 ticks); not ended yet.
            if (!t.isDead() && tick - t.lastTick() > STALE) t.die(tick);
            return t.isDead() && tick - t.deathTick() > RINGOUT;
        });
    }

    /** Projectiles absent on client but already audible or visible: path from server data. */
    public static void received(S2C.FarFlights packet) {
        if (Minecraft.getInstance().level == null) return;
        for (S2C.FarFlight f : packet.flights()) receive(f, tick);
    }

    /** One packet path sample; separate from world to test entity → distant path transition. */
    static void receive(S2C.FarFlight f, long tick) {
        FlightTrack t;
        if (f.interceptor()) {
            t = INTERCEPTORS.get(f.id());
            if (t != null && t.confirmedEnd()) return;
            if (t == null || t.isDead()) INTERCEPTORS.put(f.id(), t = FlightTrack.interceptor(f.id()));
        } else t = track(f.id(), WeaponType.byId(f.weapon()), f.bomber());
        t.record(tick, f);
        if (f.ended()) t.impact(tick);
    }

    /** Projectile path: ongoing or new (previous flight with same UUID ended). */
    private static FlightTrack track(UUID id, WeaponType weapon, boolean bomber) {
        FlightTrack t = TRACKS.get(id);
        if (t == null || t.isDead()) {
            t = new FlightTrack(id, weapon, bomber);
            TRACKS.put(id, t);
        }
        return t;
    }

    /** Current projectile path or {@code null}. */
    public static FlightTrack get(UUID id) {
        return TRACKS.get(id);
    }

    public static Collection<FlightTrack> all() {
        return Collections.unmodifiableCollection(TRACKS.values());
    }

    /** SAM missile paths (engine sound, trail and launch cloud). */
    public static Collection<FlightTrack> interceptors() {
        return Collections.unmodifiableCollection(INTERCEPTORS.values());
    }

    /** Current SAM missile path or {@code null}. */
    public static FlightTrack interceptor(UUID id) {
        return INTERCEPTORS.get(id);
    }

    /** Projectile exploded (blast packet): flight ended this tick ({@link FlightTrack#impact}). */
    public static void impact(UUID id) {
        FlightTrack t = TRACKS.get(id);
        if (t != null) t.impact(tick);
    }

    /** Conventional all-clear: these flights ended immediately (no explosion, no fading trail). */
    public static void cancelled(Collection<UUID> projectiles) {
        projectiles.forEach(TRACKS::remove);
    }

    /** All-clear including nuclear. */
    public static void reset() {
        TRACKS.clear();
        INTERCEPTORS.clear();
    }

    /**
     * World exit: clear client-world reference too; without world {@link #tick} does not run, so it would retain
     * old world with chunks and entities in menu until next world entry.
     */
    public static void logout() {
        reset();
        world = null;
    }
}
