package ua.zentix.incoming.warhead;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ColoredFallingBlock;
import net.minecraft.world.level.block.ConcretePowderBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Crater falls. Blast leaves sand/gravel unsupported (bomb also places rubble over cavity); vanilla turns each
 * into a falling entity ({@code FallingBlock.tick} → {@code FallingBlockEntity.fall}), hundreds within seconds in
 * sand/under cavity roof, all carried by server. Limit live falling blocks to {@link #LIVE_CAP} per blast area
 * (each overlapping neighboring area counts it) and {@link #TOTAL_CAP} per world (salvos create many areas):
 * visible nearby falling. Place others immediately where they would land: {@code fall} already removed source,
 * cancel entity entry ({@link EntityJoinLevelEvent}), place on support below. Neighbor updates like landing make
 * higher column collapse via same path.
 * <p>
 * Immediate placement only if landing is just “place block” ({@link #restingPlace}); others fall live under
 * vanilla rules. Area is a horizontal blast-reach square across full world height (slopes above crater fall too),
 * open while blast holds {@link BlastArea} plus {@link #GRACE} ticks. Unsaved world attachment {@link
 * ModAttachments#CRATER_FALLS}.
 */
public final class CraterFalls {
    /** Live falling blocks starting within blast area. */
    public static final int LIVE_CAP = 48;
    /** Live falling blocks across all world areas: widely dispersed salvo areas do not overlap. */
    public static final int TOTAL_CAP = 96;
    /** Ticks after blast end for remaining falls (chain collapse, late neighbor updates). */
    static final int GRACE = 200;
    /**
     * Maximum area lifetime even if blast end never arrives (timeline exception): longest blast is deferred for
     * readiness ({@link Warheads#DEFERRED_GIVE_UP_TICKS}), then falling.
     */
    static final int OPEN_LIMIT = Warheads.DEFERRED_GIVE_UP_TICKS + GRACE;

    private final Map<UUID, Zone> zones = new HashMap<>();
    /** Falling blocks admitted live by areas. */
    private final List<Live> live = new ArrayList<>();
    /** Last cleanup tick for {@link #live} and areas. */
    private long pruned = Long.MIN_VALUE;
    /** Blocks placed immediately without entities since world load. */
    private long settled;

    /** For {@link ModAttachments#CRATER_FALLS}: per-world, unsaved. */
    public CraterFalls() {}

    public static CraterFalls get(ServerLevel level) {
        return level.getData(ModAttachments.CRATER_FALLS);
    }

    /** Blast {@code key} reaches {@code reach} from {@code centre}: open until {@link #close}. */
    void open(ServerLevel level, UUID key, Vec3 centre, double reach) {
        zones.put(key, new Zone(Mth.floor(centre.x - reach), Mth.floor(centre.z - reach),
                Mth.floor(centre.x + reach), Mth.floor(centre.z + reach), level.getGameTime() + OPEN_LIMIT));
    }

    /** Blast ended: falls continue for {@link #GRACE} ticks. */
    void close(ServerLevel level, UUID key) {
        Zone z = zones.get(key);
        if (z != null) z.until = Math.min(z.until, level.getGameTime() + GRACE);
    }

    /** Blocks placed immediately without entities since world load. */
    public long settled() {
        return settled;
    }

    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || !(event.getLevel() instanceof ServerLevel level)
                || event.getEntity().getType() != EntityType.FALLING_BLOCK) return;
        FallingBlockEntity falling = (FallingBlockEntity) event.getEntity();
        // Only block just starting to fall from its own site (FallingBlockEntity.fall): /summon, dimension-transfer copies,
        // and other mods' entities have no own start (unsaved, defaults 0 0 0).
        BlockPos from = falling.getStartPos();
        if (falling.time != 0 || !from.equals(falling.blockPosition()) || !level.getBlockState(from).isAir()) return;
        if (get(level).settle(level, falling, from)) event.setCanceled(true);
    }

    /**
     * {@code falling} began at {@code from} (already empty). Above area limit, place immediately on support and
     * return {@code true} (no entity entry), otherwise admit live. Where blocks tick but entities do not (level
     * 32: edge, not-yet-grown blast area as {@code AreaLoader} grows entity-ticking rings with ready neighbors,
     * or already released), also place immediately: block tick started fall but entity would hang until ticking
     * returns (CI 22.10: rubble roof collapsed by bomb fracture zone at area edge hung through test end).
     */
    private boolean settle(ServerLevel level, FallingBlockEntity falling, BlockPos from) {
        prune(level);
        // Neighbor blast areas overlap (salvo, crater by old crater): position counts toward every square
        // containing it, otherwise live count would exceed area limit by those counted for neighbor.
        List<Zone> in = new ArrayList<>(2);
        boolean room = live.size() < TOTAL_CAP;
        for (Zone z : zones.values()) {
            if (!z.contains(from)) continue;
            in.add(z);
            room &= z.live < LIVE_CAP;
        }
        if (in.isEmpty()) return false;
        if (room && level.isPositionEntityTicking(from)) {
            admit(falling, in);
            return false;
        }
        BlockPos rest = restingPlace(level, falling, from);
        if (rest == null || !level.setBlock(rest, Block.updateFromNeighbourShapes(falling.getBlockState(), level, rest), Block.UPDATE_ALL)) {
            admit(falling, in);
            return false;
        }
        settled++;
        return true;
    }

    private void admit(FallingBlockEntity falling, List<Zone> in) {
        live.add(new Live(falling, in));
        for (Zone z : in) z.live++;
    }

    /**
     * Once per tick, close expired areas and forget departed falling blocks (landed, chunk-unloaded, entry
     * canceled by other mod). No areas means no counting needed.
     */
    private void prune(ServerLevel level) {
        long now = level.getGameTime();
        if (pruned == now) return;
        pruned = now;
        zones.values().removeIf(z -> z.until < now);
        if (zones.isEmpty()) {
            live.clear();
            return;
        }
        live.removeIf(l -> {
            boolean gone = l.entity.isRemoved() || level.getEntity(l.entity.getId()) != l.entity;
            if (gone) for (Zone z : l.zones) z.live--;
            return gone;
        });
    }

    /**
     * Landing position for block falling from {@code from}, or {@code null} if vanilla landing does more than
     * “place block” and needs a live entity. Immediate placement only for sand, red sand, gravel ({@link
     * ColoredFallingBlock}), concrete powder; anvils damage/play sound, modded blocks have custom {@code
     * onLand}/{@code onBrokenAfterFall}. Cell is first above support ({@link FallingBlock#isFree}: air, fire,
     * fluid, replaceable); as vanilla, replace contents ({@code canBeReplaced} with same context) if block
     * survives. Support needs full top face: torch/slab/carpet/snow layer puts vanilla entity in support cell and
     * drops item. Concrete powder reaching water hardens, so falls live. Column is in the already block-ticking
     * chunk (neighbors ready), so reads load nothing.
     */
    @Nullable
    private static BlockPos restingPlace(ServerLevel level, FallingBlockEntity falling, BlockPos from) {
        BlockState state = falling.getBlockState();
        Class<?> kind = state.getBlock().getClass();
        boolean powder = kind == ConcretePowderBlock.class;
        if (falling.blockData != null || kind != ColoredFallingBlock.class && !powder) return null;
        BlockPos.MutableBlockPos p = from.mutable();
        boolean fluid = false;
        while (p.getY() > level.getMinBuildHeight()) {
            BlockState below = level.getBlockState(p.below());
            if (!FallingBlock.isFree(below)) break;
            fluid |= !below.getFluidState().isEmpty();
            p.move(Direction.DOWN);
        }
        if (p.getY() <= level.getMinBuildHeight() || powder && fluid) return null;
        BlockPos support = p.below();
        if (!level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)) return null;
        BlockState replaced = level.getBlockState(p);
        if (!replaced.canBeReplaced(new DirectionalPlaceContext(level, p, Direction.DOWN, ItemStack.EMPTY, Direction.UP))
                || !state.canSurvive(level, p)) return null;
        return p.immutable();
    }

    private record Live(FallingBlockEntity entity, List<Zone> zones) {}

    private static final class Zone {
        final int minX, minZ, maxX, maxZ;
        long until;
        /** Live falling blocks starting in this area's square. */
        int live;

        Zone(int minX, int minZ, int maxX, int maxZ, long until) {
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.until = until;
        }

        boolean contains(BlockPos p) {
            return p.getX() >= minX && p.getX() <= maxX && p.getZ() >= minZ && p.getZ() <= maxZ;
        }
    }
}
