package ua.zentix.incoming.warhead;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import ua.zentix.incoming.registry.ModDataMaps;
import net.minecraft.core.BlockPos;
import ua.zentix.incoming.util.Terrain;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.entity.DebrisEntity;

import java.util.List;
import java.util.Comparator;
import java.util.ArrayList;

/**
 * Explosion debris: crater soil, charred pieces, burning brands, casing parts. Shahed throws 55–75 blocks
 * (horizontal up to 1.5 blocks/tick), missile 90–120 (up to 2.1).
 */
public final class DebrisSpawner {
    private static List<BlockState> burnStates;

    private DebrisSpawner() {}

    /** Resolve current data on each burst so reloads and server changes cannot leave stale palettes. */
    public static List<BlockState> palette(ServerLevel level, DataMapType<Block, List<Integer>> type) {
        var blocks = level.registryAccess().registryOrThrow(Registries.BLOCK);
        record Piece(int slot, String block, BlockState state) {}
        List<Piece> pieces = new ArrayList<>();
        blocks.getDataMap(type).forEach((key, slots) -> {
            BlockState state = blocks.get(key).defaultBlockState();
            for (int slot : slots) pieces.add(new Piece(slot, key.location().toString(), state));
        });
        // The block ID breaks ties for datapacks that add a block to an existing slot.
        pieces.sort(Comparator.comparingInt(Piece::slot).thenComparing(Piece::block));
        return pieces.stream().map(Piece::state).toList();
    }

    private static List<BlockState> burning() {
        if (burnStates == null) {
            BlockState campfire = Blocks.CAMPFIRE.defaultBlockState();
            if (campfire.hasProperty(BlockStateProperties.LIT)) campfire = campfire.setValue(BlockStateProperties.LIT, true);
            burnStates = List.of(campfire, Blocks.FIRE.defaultBlockState(), Blocks.FIRE.defaultBlockState());
        }
        return burnStates;
    }

    /** Shahed debris; returns count. */
    public static int drone(ServerLevel level, Vec3 at, GroundMaterial mat) {
        return spawnGround(level, at, mat.debris(), false, 1.5, 0.5, 1.6)
                + spawnGround(level, at, mat.debris(), false, 1.5, 0.5, 1.6)
                + spawnCharred(level, at, 1.5, 0.5, 1.6)
                + spawn(level, at, palette(level, ModDataMaps.DEBRIS_DRONE), false, 1.5, 0.5, 1.6)
                + spawnBurning(level, at, 1.5, 0.5, 1.6);
    }

    /** Missile debris; returns count. */
    public static int missile(ServerLevel level, Vec3 at, GroundMaterial mat) {
        int n = 0;
        for (int i = 0; i < 4; i++) n += spawnGround(level, at, mat.debris(), false, 2.1, 0.6, 2.2);
        return n + spawnCharred(level, at, 2.1, 0.6, 2.2)
                + spawnCharred(level, at, 2.1, 0.6, 2.2)
                + spawn(level, at, palette(level, ModDataMaps.DEBRIS_MISSILE), false, 2.1, 0.6, 2.2)
                + spawnBurning(level, at, 2.1, 0.6, 2.2)
                + spawnBurning(level, at, 2.1, 0.6, 2.2);
    }

    /**
     * Debris of projectile shot down in air by interceptor: casing parts, burning/charred pieces continue along
     * target heading at its speed (pieces retain body momentum; burst gives small random-direction addition),
     * lose speed to air, and fall in a heading-aligned strip, longer for faster targets. Count follows body size
     * ({@code noseLength}): Lancet/Shahed handful, missile more, B-2 rain of parts. No ground: burst airborne.
     * Returns debris count.
     */
    public static int wreck(ServerLevel level, Vec3 at, Vec3 velocity, double noseLength) {
        RandomSource r = level.random;
        List<BlockState> parts = noseLength > 2.5 ? palette(level, ModDataMaps.DEBRIS_MISSILE) : palette(level, ModDataMaps.DEBRIS_DRONE);
        int rounds = noseLength > 5 ? 3 : 1;
        Vec3 drift = velocity;
        double spread = Math.min(2.5, 0.4 + noseLength * 0.3);
        int n = 0;
        for (int k = 0; k < rounds; k++) {
            for (BlockState s : parts) n += wreckPiece(level, at, s, false, true, drift, spread, r);
            for (BlockState s : palette(level, ModDataMaps.DEBRIS_EMBERS)) n += wreckPiece(level, at, s, true, true, drift, spread, r);
            for (BlockState s : burning()) n += wreckPiece(level, at, s, true, false, drift, spread, r);
        }
        return n;
    }

    private static int wreckPiece(ServerLevel level, Vec3 at, BlockState s, boolean hot, boolean hurts, Vec3 drift, double spread, RandomSource r) {
        Vec3 p = at.add((r.nextDouble() - 0.5) * spread * 2, (r.nextDouble() - 0.5) * spread, (r.nextDouble() - 0.5) * spread * 2);
        level.addFreshEntity(DebrisEntity.create(level, p, s, hot, hurts, drift.add(velocity(r, 0.45, -0.15, 0.35))));
        return 1;
    }

    private static int spawn(ServerLevel level, Vec3 at, List<BlockState> states, boolean hot, double horizontal, double upMin, double upMax) {
        for (BlockState s : states) {
            level.addFreshEntity(DebrisEntity.create(level, start(level.random, at), s, hot, true, velocity(level.random, horizontal, upMin, upMax)));
        }
        return states.size();
    }

    /** Material from a cohesive face that remains intact is chips; ejected loose ground retains its blocks. */
    private static int spawnGround(ServerLevel level, Vec3 at, List<BlockState> states, boolean hot, double horizontal, double upMin, double upMax) {
        BlockPos face = BlockPos.containing(at.add(0, -1e-5, 0));
        boolean small = Terrain.ready(level, face) && SurfaceBlocks.solid(level, face, level.getBlockState(face))
                && SurfaceBlocks.cohesive(level.getBlockState(face));
        for (BlockState state : states) {
            level.addFreshEntity(DebrisEntity.ground(level, start(level.random, at), state, hot, true,
                    velocity(level.random, horizontal, upMin, upMax), small));
        }
        return states.size();
    }

    private static int spawnCharred(ServerLevel level, Vec3 at, double horizontal, double upMin, double upMax) {
        return spawnGround(level, at, palette(level, ModDataMaps.DEBRIS_EMBERS), true, horizontal, upMin, upMax) + spawnGround(level, at, palette(level, ModDataMaps.DEBRIS_ASH), false, horizontal, upMin, upMax);
    }

    private static int spawnBurning(ServerLevel level, Vec3 at, double horizontal, double upMin, double upMax) {
        for (BlockState s : burning()) {
            level.addFreshEntity(DebrisEntity.create(level, start(level.random, at), s, true, false, velocity(level.random, horizontal, upMin, upMax)));
        }
        return burning().size();
    }

    private static Vec3 start(RandomSource r, Vec3 at) {
        return at.add((r.nextDouble() - 0.5) * 2.4, 1.5 + r.nextDouble() * 1.5, (r.nextDouble() - 0.5) * 2.4);
    }

    private static Vec3 velocity(RandomSource r, double horizontal, double upMin, double upMax) {
        return new Vec3((r.nextDouble() * 2 - 1) * horizontal, upMin + r.nextDouble() * (upMax - upMin), (r.nextDouble() * 2 - 1) * horizontal);
    }
}
