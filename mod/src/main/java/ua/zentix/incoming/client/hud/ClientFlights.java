package ua.zentix.incoming.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.client.far.FarFlightView;
import ua.zentix.incoming.client.flight.FlightTrack;
import ua.zentix.incoming.client.flight.FlightTracks;
import ua.zentix.incoming.entity.BomberEntity;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.strike.WeaponType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Own projectiles in flight as sent by the server ({@link S2C.Flights}, every 5 ticks): impact ETA decreases by client
 * ticks between packets; position is where the client draws the body: at the entity if visible, otherwise on the
 * distant visual track, or, without it, between the last two packets with one packet of delay (snapshot
 * interpolation, no prediction). Projectile number (No. 3) is constant throughout flight.
 */
public final class ClientFlights {
    /** Track points retained per projectile (packet every 5 ticks: ~2 minutes of flight). */
    private static final int TRAIL = 480;
    private static final List<Tracked> FLIGHTS = new ArrayList<>();
    /** Body position on the distant visual track (frame drawn in one thread). */
    private static final double[] AT = new double[3];
    /**
     * Projectiles currently visible to the client, by UUID. HUD markers, map and camera request each flight's entity
     * every frame; scanning all world entities (thousands in a pack with Create) for 30–90 flights would be noticeable.
     */
    private static final Map<UUID, StrikeProjectile> VISIBLE = new HashMap<>();
    private static long tick;
    private static int nextNumber = 1;

    private ClientFlights() {}

    /** Projectile: server data and persistent number. */
    public static final class Tracked {
        public final UUID id;
        public final int number;
        S2C.Flight data;
        /** Target entity network ID ({@link S2C.Flights#NO_ENTITY} if absent). */
        int targetEntity = S2C.Flights.NO_ENTITY;
        /** Position from the previous packet and client ticks when the previous and latest packets arrived. */
        private Vec3 prevPos;
        private long prevAt, at;
        /** Travelled track from server packets (for the map), at most {@link #TRAIL} points. */
        private final ArrayDeque<Vec3> trail = new ArrayDeque<>();

        Tracked(S2C.Flight data, int number) {
            this.id = data.id();
            this.data = data;
            this.number = number;
            this.prevPos = data.pos();
            this.at = tick;
            this.prevAt = tick - 1;
            trail.add(data.pos());
        }

        void accept(S2C.Flight next) {
            prevPos = data.pos();
            prevAt = at;
            at = tick;
            data = next;
            if (trail.getLast().distanceToSqr(next.pos()) > 1) trail.addLast(next.pos());
            while (trail.size() > TRAIL) trail.removeFirst();
        }

        public WeaponType weapon() {
            WeaponType[] w = WeaponType.values();
            return w[Math.max(0, Math.min(w.length - 1, data.weapon()))];
        }

        public FlightPhase phase() {
            return FlightPhase.byId(data.phase());
        }

        public boolean nuclear() {
            return data.nuclear();
        }

        /** Seconds to impact, accounting for ticks elapsed since the packet. */
        public double etaSeconds(float partialTick) {
            return Math.max(0, data.eta() - (tick - at) - partialTick) / 20.0;
        }

        public Vec3 target() {
            return data.target();
        }

        /**
         * Target entity if visible to the client (network ID from the server packet): put the marker where it is drawn,
         * rather than at the point sent every 5 ticks (at ×4–×64 zoom the point's lag on screen grows by the same factor).
         */
        @Nullable
        public net.minecraft.world.entity.Entity targetEntity() {
            if (targetEntity == S2C.Flights.NO_ENTITY || targetLost()) return null;
            ClientLevel level = Minecraft.getInstance().level;
            return level == null ? null : level.getEntity(targetEntity);
        }

        /** Target disappeared: projectile flies to its last known point. */
        public boolean targetLost() {
            return data.targetLost();
        }

        /** Target identity: username, entity type, craft; null for a point. */
        @Nullable
        public Component targetLabel() {
            String n = data.targetName();
            return switch (data.targetKind()) {
                case S2C.Flight.TARGET_NAMED -> Component.literal(n);
                case S2C.Flight.TARGET_TYPE -> Component.translatable(n);
                case S2C.Flight.TARGET_AIRCRAFT -> n.isBlank()
                        ? Component.translatable("incoming.target.aircraft")
                        : Component.translatable("incoming.target.aircraft.named", n);
                default -> null;
            };
        }

        /** Projectile entity if visible to the client. */
        @Nullable
        public StrikeProjectile entity() {
            StrikeProjectile p = VISIBLE.get(id);
            // After a dimension change the old client world leaves as a whole, without events for each entity.
            return p != null && !p.isRemoved() && p.level() == Minecraft.getInstance().level ? p : null;
        }

        /**
         * Current projectile position, where the client draws its body (screen marker, map, camera): smoothed entity
         * position, or distant visual track ({@link FarFlightView#drawnAt}); without either, between packets.
         */
        public Vec3 position(float partialTick) {
            StrikeProjectile p = entity();
            if (p != null) return p.getPosition(partialTick);
            if (FarFlightView.drawnAt(id, partialTick, AT)) return new Vec3(AT[0], AT[1], AT[2]);
            double k = (tick - at + partialTick) / Math.max(1, at - prevAt);
            return prevPos.lerp(data.pos(), Math.min(1, k));
        }

        /**
         * Largest body dimension (span or length), blocks: its angular size sets the projectile's screen frame;
         * the aircraft for B-2, the bomb for its bomb.
         */
        public double bodySize() {
            StrikeProjectile p = entity();
            FlightTrack track = p == null ? FlightTracks.get(id) : null;
            boolean bomber = p != null ? p instanceof BomberEntity : track != null && track.bomber;
            return ClientWeaponSpec.of(weapon()).airframe(!bomber).far().size();
        }

        /** Speed, blocks per tick: entity's last tick, otherwise between the last two packets. */
        public Vec3 velocity() {
            StrikeProjectile p = entity();
            if (p != null) return p.position().subtract(p.xo, p.yo, p.zo);
            return data.pos().subtract(prevPos).scale(1.0 / Math.max(1, at - prevAt));
        }

        /** Travelled track from server packets, oldest points first. */
        public Collection<Vec3> trail() {
            return Collections.unmodifiableCollection(trail);
        }
    }

    /** Speed in km/h from blocks per tick (block is a metre, 20 ticks per second). */
    public static double kmh(double blocksPerTick) {
        return blocksPerTick * 20 * 3.6;
    }

    public static void update(S2C.Flights packet) {
        List<Tracked> next = new ArrayList<>();
        for (int i = 0; i < packet.flights().size(); i++) {
            S2C.Flight f = packet.flights().get(i);
            Tracked t = find(f.id());
            if (t == null) t = new Tracked(f, nextNumber++);
            else t.accept(f);
            t.targetEntity = packet.target(i);
            next.add(t);
        }
        FLIGHTS.clear();
        FLIGHTS.addAll(next);
        FLIGHTS.sort(Comparator.comparingInt(t -> t.data.eta()));
        if (FLIGHTS.isEmpty()) nextNumber = 1;
    }

    public static void tick() {
        tick++;
    }

    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() && e.getEntity() instanceof StrikeProjectile p) VISIBLE.put(p.getUUID(), p);
    }

    public static void onLeave(EntityLeaveLevelEvent e) {
        // A projectile returning to the world is a new entity with the same UUID: remove only the one that left.
        if (e.getLevel().isClientSide() && e.getEntity() instanceof StrikeProjectile p) VISIBLE.remove(p.getUUID(), p);
    }

    /**
     * Client world leaves (dimension change): its entities leave with it without individual {@link EntityLeaveLevelEvent}s.
     * Forget projectiles of this world, otherwise they would retain the entire old world in memory.
     */
    public static void onLevelUnload(LevelEvent.Unload e) {
        if (e.getLevel() instanceof ClientLevel level) VISIBLE.values().removeIf(p -> p.level() == level);
    }

    public static void reset() {
        FLIGHTS.clear();
        VISIBLE.clear();
        nextNumber = 1;
    }

    /** By impact ETA, soonest first. */
    public static List<Tracked> all() {
        return FLIGHTS;
    }

    @Nullable
    public static Tracked find(UUID id) {
        for (Tracked t : FLIGHTS) {
            if (t.id.equals(id)) return t;
        }
        return null;
    }
}
