package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.world.landing.PreparedLandingModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Devtest adapter. Requires published snapshot properties; neither it nor the solver accepts a live world. */
final class PreparedLandingSnapshot {
    record Removal(BlockPos source, double departure, boolean structuralDecisionKnown) {}
    record Input(PreparedLandingModel.Snapshot collision, List<PreparedLandingModel.Source> sources,
                 List<BlockState> states, int removalCells, int nonMaterialCells) {
        Input { sources = List.copyOf(sources); states = List.copyOf(states); }
    }

    static Input from(RuinWindow window, List<Removal> removals, Detonation detonation) {
        if (window.view == Blast.SERVER) throw new IllegalArgumentException("Publish properties before worker conversion");
        int height = window.maxY - window.minY, side = RuinWindow.SIDE;
        byte[] cells = new byte[side * side * height];
        for (int y = window.minY; y < window.maxY; y++) for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) {
            byte collision;
            // RuinWindow.get supplies MASS for absent data. Do not inherit that planner approximation.
            if (!window.has(x, z)) collision = PreparedLandingModel.UNKNOWN;
            else {
                var state = window.get(x, y, z);
                var props = window.props(x, y, z);
                collision = props.air() ? PreparedLandingModel.AIR
                        : props.full() && !state.hasBlockEntity() ? PreparedLandingModel.SOLID : PreparedLandingModel.UNSUPPORTED;
            }
            cells[((y - window.minY) * side + z) * side + x] = collision;
        }
        var palette = new LinkedHashMap<BlockState, Integer>();
        var sources = new ArrayList<PreparedLandingModel.Source>();
        int nonMaterial = 0;
        for (Removal removal : removals) {
            BlockPos at = removal.source;
            int x = at.getX() - window.x0, z = at.getZ() - window.z0;
            if (!window.has(x, z)) throw new IllegalArgumentException("Removal has no source snapshot");
            BlockState state = window.get(x, at.getY(), z);
            Blast.Props props = window.props(x, at.getY(), z);
            double volume = props.volume(), mass = volume * props.density();
            if (props.air() || props.fluid() || props.fixed() || !props.bearing() && !props.leaves() || !(mass > 0)) {
                nonMaterial++;
                continue;
            }
            Vec3 position = Vec3.atCenterOf(at);
            Vec3 away = new Vec3(position.x - detonation.burst().x, 0, position.z - detonation.burst().z).normalize();
            boolean fine = props.leaves() || state.is(net.neoforged.neoforge.common.Tags.Blocks.GLASS_BLOCKS)
                    || state.is(net.neoforged.neoforge.common.Tags.Blocks.GLASS_PANES);
            boolean eligible = props.full() && !state.hasBlockEntity() && !fine && !props.waterlogged();
            if (state.hasProperty(BlockStateProperties.WATERLOGGED)) state = state.setValue(BlockStateProperties.WATERLOGGED, false);
            if (props.log() && state.hasProperty(BlockStateProperties.AXIS))
                state = state.setValue(BlockStateProperties.AXIS, Math.abs(away.x) > Math.abs(away.z) ? Direction.Axis.X : Direction.Axis.Z);
            int stateId = palette.computeIfAbsent(state, unused -> palette.size());
            var kind = !removal.structuralDecisionKnown ? PreparedLandingModel.Kind.UNRESOLVED_STRUCTURE
                    : eligible ? PreparedLandingModel.Kind.ORDINARY : PreparedLandingModel.Kind.UNSUPPORTED_SOURCE;
            sources.add(new PreparedLandingModel.Source(at.asLong(), detonation.id(), at.getX(), at.getY(), at.getZ(), stateId,
                    volume, mass, removal.departure, away.x * detonation.blastWind(position), away.z * detonation.blastWind(position),
                    detonation.dynamicPressureKpa(position) * 1000 / mass, detonation.positivePhaseSeconds(position), kind));
        }
        return new Input(new PreparedLandingModel.Snapshot(window.x0, window.minY, window.z0, side, height, side, cells),
                sources, new ArrayList<>(palette.keySet()), removals.size(), nonMaterial);
    }

    private PreparedLandingSnapshot() {}
}
