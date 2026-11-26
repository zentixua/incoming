package ua.zentix.incoming.client.far;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.fx.BlastEffects;
import ua.zentix.incoming.client.nuclear.ClientClock;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.strike.StrikeClouds;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ObjLongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Conventional strikes the client did not see happen (rule 10 of the time note): the server's list
 * ({@link S2C.StrikeClouds}) is built here at each strike's motion age, without flash or sound, in the air of its moment
 * ({@link FarBlasts.Resent}). What arrived live is not built again; a crater fire is its state and is applied each time
 * ({@link BlastEffects#fuelFire}, one source per crater).
 * <p>
 * A late joiner gets the list right after login, before its chunks: a cloud waits for the chunk under it (its dust reads
 * the ground around) up to {@link Ledger#CHUNK_WAIT}, or is built at once beyond the client's view. A Flashback seek clears
 * every cloud ({@code IncomingClient.rewound}); the last list read (the snapshot keeps the last packet of a type) is then
 * built again at the replay position's ages. Strikes after that position are left to their live packets.
 */
public final class ClientStrikeClouds {
    static final Ledger LEDGER = new Ledger();
    private static ClientLevel world;

    private ClientStrikeClouds() {}

    /** A cloud event arrived live and was built by its own handler. */
    public static void live(CustomPacketPayload p) {
        ClientLevel level = level();
        if (level != null) LEDGER.live(p, level.getGameTime());
    }

    /** The server's list. */
    public static void sync(S2C.StrikeClouds p) {
        ClientLevel level = level();
        if (level != null) LEDGER.sync(p.clouds(), level.getGameTime());
    }

    /** Replay seek: every cloud and crater smoke is gone; the last list is built again at its ages. */
    public static void rewound() {
        ClientLevel level = level();
        if (level != null) LEDGER.rewound(level.getGameTime());
    }

    /** World exit. */
    public static void reset() {
        LEDGER.clear();
        world = null;
    }

    /** Client tick (under the level's tick gate, as the clouds' own clock): builds what is due. */
    public static void tick() {
        ClientLevel level = level();
        if (level == null) return;
        Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        LEDGER.tick(level.getGameTime(), ClientClock.counted(), c -> ready(level, c), c -> position(c.packet()).distanceToSqr(eye),
                ClientStrikeClouds::build, System::nanoTime);
    }

    private static ClientLevel level() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc == null ? null : mc.level;
        if (level != world) {
            // another world (dimension change, a new world): its clouds start from its own list
            LEDGER.clear();
            world = level;
        }
        return level;
    }

    /** The chunk under the event is here, or will not come (beyond the client's view distance). */
    private static boolean ready(ClientLevel level, S2C.StrikeClouds.Cloud c) {
        Vec3 at = position(c.packet());
        int cx = (int) Math.floor(at.x) >> 4, cz = (int) Math.floor(at.z) >> 4;
        if (level.getChunkSource().getChunk(cx, cz, false) != null) return true;
        Minecraft mc = Minecraft.getInstance();
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        int view = mc.options.getEffectiveRenderDistance() + 1;
        return Math.max(Math.abs(cx - ((int) Math.floor(eye.x) >> 4)), Math.abs(cz - ((int) Math.floor(eye.z) >> 4))) > view;
    }

    static Vec3 position(CustomPacketPayload p) {
        return switch (p) {
            case S2C.Blast b -> b.pos();
            case S2C.BunkerImpact b -> b.pos();
            case S2C.Vent v -> v.pos();
            case S2C.Collapse c -> c.pos();
            case S2C.Intercept i -> i.pos();
            case S2C.FuelFire f -> f.cells().isEmpty() ? Vec3.ZERO : Vec3.atBottomCenterOf(f.cells().getFirst());
            default -> Vec3.ZERO;
        };
    }

    /** Builds {@code c} at {@code ticks} of motion age (a crater fire at its own clock). */
    private static void build(S2C.StrikeClouds.Cloud c, long ticks) {
        // with the mod's blast effects off another mod shows impacts: neither live nor resent clouds are ours to draw
        if (!IncomingConfig.CLIENT.blastEffects.get()) return;
        FarBlasts.Resent resent = new FarBlasts.Resent(ticks, c.air(), c.surface());
        switch (c.packet()) {
            case S2C.Blast b -> FarBlasts.add(b, resent);
            case S2C.BunkerImpact b -> BlastEffects.bunkerImpactCloud(b, resent);
            case S2C.Vent v -> BlastEffects.ventCloud(v, resent);
            case S2C.Collapse col -> BlastEffects.collapseCloud(col, resent);
            case S2C.Intercept i -> BlastEffects.interceptCloud(i, resent);
            case S2C.FuelFire f -> BlastEffects.fuelFire(f);
            default -> {}
        }
    }

    /** Which listed clouds to build and when; Minecraft-free for tests. */
    static final class Ledger {
        /**
         * Building time per client tick, ns: a cloud model costs ~3 ms to build, live or resent ({@code StrikeCloudsTest}), so
         * a join with a full list spreads over ticks, the nearest first.
         */
        static final long BUILD_NANOS = 3_000_000;
        /** Longest wait for the chunk under a cloud, ticks: a joiner's chunks arrive after the list. */
        static final int CHUNK_WAIT = 100;

        private record Waiting(S2C.StrikeClouds.Cloud cloud, long since) {}

        private List<S2C.StrikeClouds.Cloud> known = List.of();
        /** Clouds built or seen live, by their packet, with the game time they began (forgotten after their life). */
        private final Map<CustomPacketPayload, Long> shown = new HashMap<>();
        private final ArrayDeque<Waiting> waiting = new ArrayDeque<>();

        void live(CustomPacketPayload p, long now) {
            shown.put(p, now);
        }

        void sync(List<S2C.StrikeClouds.Cloud> clouds, long now) {
            known = clouds;
            queue(now);
        }

        void rewound(long now) {
            shown.clear();
            queue(now);
        }

        void clear() {
            known = List.of();
            shown.clear();
            waiting.clear();
        }

        /**
         * Listed clouds to build: not seen live, and already begun. One born after {@code now} (a replay seek back past
         * it, the list read at the old position) comes live when playback reaches it; built here as well, it would be
         * drawn twice. The next list follows its live packet within a second.
         */
        private void queue(long now) {
            waiting.clear();
            for (S2C.StrikeClouds.Cloud c : known) {
                if (c.born() > now) continue;
                if (c.kind() == S2C.StrikeClouds.FUEL || !shown.containsKey(c.packet())) waiting.add(new Waiting(c, now));
            }
        }

        /**
         * Builds the due clouds, nearest first, within {@link #BUILD_NANOS} of this tick (at least one): begun by
         * {@code now}, still alive by their motion age, their ground here or not coming.
         */
        void tick(long now, double counted, Predicate<S2C.StrikeClouds.Cloud> ready, ToDoubleFunction<S2C.StrikeClouds.Cloud> distance,
                  ObjLongConsumer<S2C.StrikeClouds.Cloud> build, LongSupplier nanos) {
            shown.values().removeIf(born -> (now - born) / 20.0 >= StrikeClouds.LIFE_S);
            // past its life by real age, or seen live: gone for good. Aged past it only by counted jumps: held, since this
            // world's clock may not have arrived yet (the server prunes by the same motion age, so a real one leaves the list)
            waiting.removeIf(w -> w.cloud.born() > now || w.cloud.kind() != S2C.StrikeClouds.FUEL
                    && ((now - w.cloud.born()) / 20.0 >= StrikeClouds.LIFE_S || shown.containsKey(w.cloud.packet())));
            long start = nanos.getAsLong();
            for (boolean first = true; first || nanos.getAsLong() - start < BUILD_NANOS; first = false) {
                Waiting best = null;
                double nearest = Double.POSITIVE_INFINITY;
                for (Waiting w : waiting) {
                    S2C.StrikeClouds.Cloud c = w.cloud;
                    if (c.kind() != S2C.StrikeClouds.FUEL && StrikeClouds.motion(c, now, counted) >= StrikeClouds.LIFE_S) continue;
                    if (c.kind() != S2C.StrikeClouds.FUEL && now - w.since < CHUNK_WAIT && !ready.test(c)) continue;
                    double d = distance.applyAsDouble(c);
                    if (best == null || d < nearest) {
                        best = w;
                        nearest = d;
                    }
                }
                if (best == null) return;
                waiting.remove(best);
                S2C.StrikeClouds.Cloud c = best.cloud;
                build.accept(c, Math.round(StrikeClouds.motion(c, now, counted) * 20));
                if (c.kind() != S2C.StrikeClouds.FUEL) shown.put(c.packet(), c.born());
            }
        }

        /** Tests: clouds still waiting. */
        int waiting() {
            return waiting.size();
        }
    }
}
