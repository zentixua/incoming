package ua.zentix.incoming.nuclear.world;

import net.minecraft.server.level.ServerLevel;

/** Server-thread ownership reservations, shared with preparation in every dimension. Estimates
 * include collection slack and serialization copies; they are not a claim about total JVM heap. */
final class RuinMemory {
    /** Preparation must leave room to turn one ready plan into authoritative moving material. */
    static final long TRANSITION_ROOM = 64L << 20;

    private RuinMemory() {}

    static Reservation reserve(ServerLevel level, long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative material reservation");
        NuclearWorld owner = NuclearWorld.get(level);
        long retained = NuclearPrep.memory(level) - TRANSITION_ROOM;
        if (bytes > NuclearPrep.PLANS_CAP - retained) return null;
        owner.materialReservation += bytes;
        return new Reservation(owner, bytes);
    }

    static final class Reservation implements AutoCloseable {
        private NuclearWorld owner;
        private long bytes;
        private Reservation(NuclearWorld owner, long bytes) { this.owner = owner; this.bytes = bytes; }
        boolean grow(ServerLevel level, long extra) {
            if (extra < 0) throw new IllegalArgumentException("Negative material reservation");
            if (extra > NuclearPrep.PLANS_CAP - (NuclearPrep.memory(level) - TRANSITION_ROOM)) return false;
            owner.materialReservation += extra;
            bytes += extra;
            return true;
        }
        @Override public void close() {
            if (owner == null) return;
            owner.materialReservation -= bytes;
            owner = null;
        }
    }
}
