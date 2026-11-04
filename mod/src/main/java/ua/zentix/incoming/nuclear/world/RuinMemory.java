package ua.zentix.incoming.nuclear.world;

import net.minecraft.server.level.ServerLevel;

/** Server-thread ownership reservations, shared with preparation in every dimension. Estimates
 * include collection slack and frozen-save ownership; they are not a claim about total JVM heap. */
final class RuinMemory {
    /** Preparation must leave room to turn one ready plan into authoritative moving material. */
    static final long TRANSITION_ROOM = 64L << 20;

    private RuinMemory() {}

    static Reservation reserve(ServerLevel level, long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative material reservation");
        NuclearWorld owner = NuclearWorld.get(level);
        if (!RuinMatter.get(level).writable() || !CraterMatter.get(level).writable()) return null;
        long retained = NuclearPrep.memory(level, false);
        if (bytes > NuclearPrep.PLANS_CAP - retained) return null;
        owner.materialReservation += bytes;
        return new Reservation(owner, bytes);
    }

    /** Existing support continuations may free ownership needed by a cold import. Their
     * bounded growth uses actual resident charges, while new strike removals stay closed. */
    static Reservation reserveProgress(ServerLevel level, long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative material reservation");
        // Resolve admission-capable getters before checking/charging the remaining room.
        NuclearWorld owner = NuclearWorld.get(level);
        if (!RuinMatter.get(level).writable()) return null;
        long retained = NuclearPrep.residentMemory(level);
        if (bytes > NuclearPrep.PLANS_CAP - retained) return null;
        owner.materialReservation += bytes;
        return new Reservation(owner, bytes);
    }

    /** Pure producer admission: never opens a ledger or changes worker configuration. Server thread only. */
    static Reservation reserveInputs(ServerLevel level, long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative input reservation");
        NuclearWorld owner = NuclearWorld.get(level);
        if (bytes > NuclearPrep.PLANS_CAP - NuclearPrep.residentMemory(level)) return null;
        owner.materialReservation += bytes;
        return new Reservation(owner, bytes);
    }

    static final class Reservation implements AutoCloseable {
        private NuclearWorld owner;
        private long bytes;
        private Reservation(NuclearWorld owner, long bytes) { this.owner = owner; this.bytes = bytes; }
        boolean grow(ServerLevel level, long extra) {
            if (extra < 0) throw new IllegalArgumentException("Negative material reservation");
            if (extra > NuclearPrep.PLANS_CAP - (NuclearPrep.memory(level, false))) return false;
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
