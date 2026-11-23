package ua.zentix.incoming.client.nuclear;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.material.DustGrid;
import ua.zentix.incoming.nuclear.material.DustMaterial;
import ua.zentix.incoming.nuclear.material.MaterialInbox;

import java.util.Optional;
import java.util.function.BiFunction;

/** Read-only renderer view. An empty modern result waits for the server. Legacy terrain freezes on first use. */
public final class SourceMaterial {
    public record Answer(Optional<DustMaterial> value, boolean committed) {
        public boolean frozenFallback() { return !committed && value.isPresent(); }
    }
    private static final Answer PENDING = new Answer(Optional.empty(), false);
    private static final Answer[] COMMITTED = java.util.Arrays.stream(DustMaterial.values())
            .map(m -> new Answer(Optional.of(m), true)).toArray(Answer[]::new);
    private final MaterialInbox server;
    private final Detonation d;
    private final BiFunction<Integer, Integer, DustMaterial> terrain;
    private final int seed;
    private DustGrid grid;
    private final Int2ObjectOpenHashMap<Answer> frozen = new Int2ObjectOpenHashMap<>();
    private Boolean oldWet;

    SourceMaterial(Detonation d, MaterialInbox server, BiFunction<Integer, Integer, DustMaterial> terrain) {
        this(d, server, -1, terrain);
    }
    SourceMaterial(Detonation d, MaterialInbox server, int seed, BiFunction<Integer, Integer, DustMaterial> terrain) {
        this.seed = seed;
        this.d = d;
        this.server = server;
        this.terrain = terrain;
    }
    public Answer material(int x, int z) {
        if (server != null) {
            var map = server.map();
            if (map != null && !map.contains(x, z)) return PENDING;
            if (map != null && map.committed(x, z)) return COMMITTED[map.material(x, z).ordinal()];
            if (seed < 0) return PENDING;
            if (grid == null) grid = new DustGrid(DustGrid.Bounds.of(d));
            return grid.index(x, z) < 0 ? PENDING : new Answer(Optional.of(DustMaterial.of(seed)), false);
        }
        if (grid == null) grid = new DustGrid(DustGrid.Bounds.of(d));
        int i = grid.index(x, z);
        if (i < 0) return PENDING;
        return frozen.computeIfAbsent(i, unused -> new Answer(Optional.of(terrain.apply(x, z)), false));
    }
    public Optional<Boolean> wet() {
        if (server != null) {
            if (seed >= 0) return Optional.of(seed == DustMaterial.WATER.ordinal());
            return server.map() == null ? Optional.empty() : Optional.of(server.map().wet());
        }
        if (oldWet == null) oldWet = material((int) Math.floor(d.burst().x), (int) Math.floor(d.burst().z))
                .value().orElse(DustMaterial.SOIL) == DustMaterial.WATER;
        return Optional.of(oldWet);
    }
}
