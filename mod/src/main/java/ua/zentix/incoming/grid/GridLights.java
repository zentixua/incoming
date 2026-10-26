package ua.zentix.incoming.grid;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.registry.ModBlocks;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Grid lamps and their unpowered counterparts ({@link ModBlocks#UNLIT}). Conversion works both ways, carrying every
 * state property (hanging, waterlogged, facing, axis, level, “lit”, “powered”): after a blackout each lamp returns exactly
 * as it was. Players cannot obtain counterparts (no item), so any counterpart in the world is a lamp we darkened, and
 * restoration needs no record of what stood where.
 */
public final class GridLights {
    /** Lamp state → counterpart state and back (all states of every pair; one lookup, no property copying). */
    private static volatile Map<BlockState, BlockState> toUnlit, toLit;
    /** State pairs that cannot change directly in a section palette (see {@link #inPlace}); grid lamps have none. */
    private static volatile Set<BlockState> notInPlace;

    private GridLights() {}

    private static void init() {
        if (toUnlit != null) return;
        Map<BlockState, BlockState> unlit = new IdentityHashMap<>(), lit = new IdentityHashMap<>();
        Set<BlockState> bad = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ModBlocks.UnlitPair p : ModBlocks.UNLIT) {
            Block on = p.lit().get(), off = p.unlit().get();
            for (BlockState s : on.getStateDefinition().getPossibleStates()) unlit.put(s, copy(s, off.defaultBlockState()));
            for (BlockState s : off.getStateDefinition().getPossibleStates()) lit.put(s, copy(s, on.defaultBlockState()));
        }
        unlit.forEach((from, to) -> {
            if (!sameInWorld(from, to)) bad.add(from);
        });
        lit.forEach((from, to) -> {
            if (!sameInWorld(from, to)) bad.add(from);
        });
        notInPlace = bad;
        toLit = lit;
        toUnlit = unlit;
    }

    /**
     * For the world, a lamp and counterpart are identical except for light: no block entity, different shape (collision,
     * outline, shading), air, opacity, skylight propagation or heightmap position. They can therefore change directly in the
     * section palette without {@code setBlock} (which also updates chunk heightmaps and skylight sources): neighbors,
     * heightmaps, craft physics and collisions need no updates; only light remains.
     */
    private static boolean sameInWorld(BlockState a, BlockState b) {
        var at = EmptyBlockGetter.INSTANCE;
        for (Heightmap.Types type : Heightmap.Types.values()) {
            if (type.isOpaque().test(a) != type.isOpaque().test(b)) return false;
        }
        return !a.hasBlockEntity() && !b.hasBlockEntity() && a.isAir() == b.isAir() && a.canOcclude() == b.canOcclude()
                && a.getFluidState().equals(b.getFluidState())
                && a.propagatesSkylightDown(at, BlockPos.ZERO) == b.propagatesSkylightDown(at, BlockPos.ZERO)
                && a.getLightBlock(at, BlockPos.ZERO) == b.getLightBlock(at, BlockPos.ZERO)
                && a.useShapeForLightOcclusion() == b.useShapeForLightOcclusion()
                && a.getCollisionShape(at, BlockPos.ZERO).toAabbs().equals(b.getCollisionShape(at, BlockPos.ZERO).toAabbs())
                && a.getShape(at, BlockPos.ZERO).toAabbs().equals(b.getShape(at, BlockPos.ZERO).toAabbs())
                && a.getOcclusionShape(at, BlockPos.ZERO).toAabbs().equals(b.getOcclusionShape(at, BlockPos.ZERO).toAabbs());
    }

    /** Conversion of {@code state} (lamp or counterpart) can happen directly in the palette ({@link #sameInWorld}). */
    public static boolean inPlace(BlockState state) {
        init();
        return !notInPlace.contains(state);
    }

    /** Lit grid lamp (goes dark in a blackout). */
    public static boolean isLit(BlockState state) {
        init();
        return toUnlit.containsKey(state);
    }

    /** Unlit lamp (counterpart). */
    public static boolean isUnlit(BlockState state) {
        init();
        return toLit.containsKey(state);
    }

    /** Lamp counterpart with the same properties; null — not a grid lamp. */
    @Nullable
    public static BlockState unlit(BlockState state) {
        init();
        return toUnlit.get(state);
    }

    /** Lamp from counterpart with the same properties; null — not a counterpart. */
    @Nullable
    public static BlockState lit(BlockState state) {
        init();
        return toLit.get(state);
    }

    /** Convert to the desired grid state: {@code dark} — darken, otherwise light; null — nothing to change. */
    @Nullable
    public static BlockState toward(BlockState state, boolean dark) {
        return dark ? unlit(state) : lit(state);
    }

    /** Lamp needing conversion to grid state ({@code dark} — lit, otherwise unlit). */
    public static boolean needs(BlockState state, boolean dark) {
        return dark ? isLit(state) : isUnlit(state);
    }

    private static BlockState copy(BlockState from, BlockState to) {
        for (Property<?> p : from.getProperties()) {
            if (to.hasProperty(p)) to = with(to, p, from);
        }
        return to;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState to, Property<T> p, BlockState from) {
        return to.setValue(p, from.getValue(p));
    }
}
