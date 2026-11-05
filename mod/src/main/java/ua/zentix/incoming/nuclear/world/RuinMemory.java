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

    /** New source I/O must leave one real decoder restart, not every configured future worker. */
    static long sourceReadRoom(ServerLevel level) {
        int height = level.getHeight();
        for (ServerLevel other : level.getServer().getAllLevels()) height = Math.max(height, other.getHeight());
        return NuclearPrep.PLANS_CAP - NuclearPrep.residentMemory(level) - RuinWorkers.startupBytes(height);
    }

    static final class Reservation implements AutoCloseable {
        private NuclearWorld owner;
        private long bytes;
        private Reservation(NuclearWorld owner, long bytes) { this.owner = owner; this.bytes = bytes; }
        boolean grow(ServerLevel level, long extra) {
            if (extra < 0) throw new IllegalArgumentException("Negative material reservation");
            if (owner == null) throw new IllegalStateException("Closed material reservation");
            // Ordinary non-cargo pieces already own their complete capture credit. This must
            // neither repeat global admission nor fail because another owner grew meanwhile.
            if (extra == 0) return true;
            if (extra > NuclearPrep.PLANS_CAP - (NuclearPrep.memory(level, false))) return false;
            owner.materialReservation += extra;
            bytes += extra;
            return true;
        }
        /** Published payload has a counted owner now; keep only the still-private capture credit. */
        void transfer(long published) {
            if (published < 0 || published > bytes) throw new IllegalArgumentException("Unfunded material publication");
            owner.materialReservation -= published;
            bytes -= published;
        }
        @Override public void close() {
            if (owner == null) return;
            owner.materialReservation -= bytes;
            owner = null;
        }
    }
}
