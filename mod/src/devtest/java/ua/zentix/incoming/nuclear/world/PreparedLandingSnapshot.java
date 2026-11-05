package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.world.landing.PreparedLandingModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Devtest adapter. Requires published snapshot properties; neither it nor the solver accepts a live world. */
final class PreparedLandingSnapshot {
    record Removal(BlockPos source, double departure, boolean structuralDecisionKnown) {}
    record Input(PreparedLandingModel.Snapshot collision, List<PreparedLandingModel.Source> sources,
                 List<BlockState> states, int removalCells, int nonMaterialCells) {
        Input { sources = List.copyOf(sources); states = List.copyOf(states); }
    }

    static Input from(RuinWindow window, List<Removal> removals, Detonation detonation, PreparedLandingPalette facts) {
        if (window.view == Blast.SERVER) throw new IllegalArgumentException("Publish properties before worker conversion");
        // RuinWindow.get supplies MASS for absent data. Do not inherit that planner approximation.
        return from(window.x0, window.z0, window.minY, window.maxY, RuinWindow.SIDE,
                (x, y, z) -> window.has(x, z) ? window.get(x, y, z) : null, removals, detonation, facts);
    }

    /** Bounded cohort collision input. Complete structural decisions must be supplied by the caller;
     * this 5x5 collision window is not a complete fracture stencil for every inner chunk. */
    static Input from(ChunkShot[] snapshots, ChunkPos centre, int minY, int maxY, Blast.PropsView view,
                      List<Removal> removals, Detonation detonation, PreparedLandingPalette facts) {
        if (view == Blast.SERVER) throw new IllegalArgumentException("Publish properties before worker conversion");
        if (snapshots.length != 25) throw new IllegalArgumentException("Cohort collision input requires a 5x5 snapshot grid");
        ChunkShot[] shots = snapshots.clone();
        for (int k = 0; k < shots.length; k++) {
            ChunkShot shot = shots[k];
            if (shot != null && (shot.x != centre.x + k % 5 - 2 || shot.z != centre.z + k / 5 - 2
                    || shot.minY != minY || shot.maxY() != maxY))
                throw new IllegalArgumentException("Cohort snapshot coordinates or height do not match the grid");
        }
        return from(centre.getMinBlockX() - 32, centre.getMinBlockZ() - 32, minY, maxY, 80, (x, y, z) -> {
            ChunkShot shot = shots[(z >> 4) * 5 + (x >> 4)];
            return shot == null ? null : shot.get(x & 15, y, z & 15);
        }, removals, detonation, facts);
    }

    @FunctionalInterface
    private interface Cells { BlockState get(int x, int y, int z); }

    private static Input from(int x0, int z0, int minY, int maxY, int side, Cells geometry,
                              List<Removal> removals, Detonation detonation, PreparedLandingPalette facts) {
        int height = maxY - minY;
        if (height < 1 || height > 4096) throw new IllegalArgumentException("Invalid bounded collision height");
        byte[] cells = new byte[Math.multiplyExact(side * side, height)];
        for (int y = minY; y < maxY; y++) for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) {
            BlockState state = geometry.get(x, y, z);
            cells[((y - minY) * side + z) * side + x] = state == null
                    ? PreparedLandingModel.UNKNOWN : facts.get(state).collision();
        }
        var palette = new LinkedHashMap<BlockState, Integer>();
        var sources = new ArrayList<PreparedLandingModel.Source>();
        int nonMaterial = 0;
        // Palette identities must not depend on which source chunk's completed task arrived first.
        // Duplicate sources are still rejected by the solver, never silently merged here.
        for (Removal removal : removals.stream().sorted(Comparator.comparingLong(r -> r.source.asLong())).toList()) {
            BlockPos at = removal.source;
            int x = at.getX() - x0, z = at.getZ() - z0;
            if (x < 0 || x >= side || z < 0 || z >= side || at.getY() < minY || at.getY() >= maxY)
                throw new IllegalArgumentException("Removal lies outside the collision snapshot");
            BlockState state = geometry.get(x, at.getY(), z);
            if (state == null) throw new IllegalArgumentException("Removal has no source snapshot");
            PreparedLandingPalette.Facts source = facts.get(state);
            if (!source.material()) {
                nonMaterial++;
                continue;
            }
            Vec3 position = Vec3.atCenterOf(at);
            Vec3 away = new Vec3(position.x - detonation.burst().x, 0, position.z - detonation.burst().z).normalize();
            var material = source.oriented(Math.abs(away.x) > Math.abs(away.z));
            int stateId = palette.computeIfAbsent(material.state(), unused -> palette.size());
            var kind = !removal.structuralDecisionKnown ? PreparedLandingModel.Kind.UNRESOLVED_STRUCTURE
                    : source.fine() ? PreparedLandingModel.Kind.FINE
                    : source.ordinary() ? PreparedLandingModel.Kind.ORDINARY : PreparedLandingModel.Kind.UNSUPPORTED_SOURCE;
            sources.add(new PreparedLandingModel.Source(at.asLong(), detonation.id(), at.getX(), at.getY(), at.getZ(), stateId,
                    material.volume(), material.mass(), removal.departure, away.x * detonation.blastWind(position), away.z * detonation.blastWind(position),
                    detonation.dynamicPressureKpa(position) * 1000 / source.dragMass(), detonation.positivePhaseSeconds(position), kind, material.collision()));
        }
        return new Input(new PreparedLandingModel.Snapshot(x0, minY, z0, side, height, side, cells),
                sources, new ArrayList<>(palette.keySet()), removals.size(), nonMaterial);
    }

    private PreparedLandingSnapshot() {}
}
