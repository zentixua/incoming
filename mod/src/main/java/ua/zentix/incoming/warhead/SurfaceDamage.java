package ua.zentix.incoming.warhead;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Display;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.registry.ModEntities;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Sub-block excavation in cohesive material: retain its collision shape and save the damaged depth with the chunk. */
public final class SurfaceDamage {
    public record Mark(BlockPos pos, BlockState state, double depth, List<UUID> rubble) {
        public Mark(BlockPos pos, BlockState state, double depth) { this(pos, state, depth, List.of()); }
        static final Codec<Mark> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(Mark::pos),
                BlockState.CODEC.fieldOf("state").forGetter(Mark::state),
                Codec.doubleRange(0, 1).fieldOf("depth").forGetter(Mark::depth),
                UUIDUtil.CODEC.listOf().optionalFieldOf("rubble", List.of()).forGetter(Mark::rubble)).apply(i, Mark::new));
    }

    public static final Codec<List<Mark>> CODEC = Mark.CODEC.listOf();

    /** Presentation budget, not a damage limit: depth in the chunk is retained even without a display. */
    public static final int DISPLAY_CAP = 256;
    private static final int DISPLAY_RANGE = 64;

    /** Per-level non-serializable attachment. One composite display per damaged column. */
    public static final class Displays {
        private final Map<BlockPos, Visible> live = new LinkedHashMap<>();
    }

    private record Visible(BlockState state, Display.BlockDisplay entity) {}

    private SurfaceDamage() {}

    /** Maximum excavation from this surface, not summed energy from independent hits. No structural weakening model. */
    public static void mark(ServerLevel level, BlockPos p, BlockState state, double depth) {
        if (!Terrain.ready(level, p) || !(depth > 0) || state.isAir() || state.getCollisionShape(level, p).isEmpty() || state.getBlock().defaultDestroyTime() < 0) return;
        LevelChunk chunk = level.getChunkAt(p);
        List<Mark> marks = new ArrayList<>(chunk.getData(ModAttachments.SURFACE_DAMAGE));
        double old = depth(level, p);
        for (Mark m : marks) if (m.pos().equals(p)) {
            if (m.state().equals(state) && m.depth() >= depth && m.rubble().isEmpty()) {
                show(level, m);
                return;
            }
            discardRubble(level, m);
        }
        marks.removeIf(m -> m.pos().equals(p));
        double damaged = Math.min(1, Math.max(old, depth));
        Mark mark = new Mark(p.immutable(), state, damaged);
        marks.add(mark);
        chunk.setData(ModAttachments.SURFACE_DAMAGE, List.copyOf(marks));
        chunk.setUnsaved(true);
        show(level, mark);
        for (ServerPlayer player : level.players()) send(player, mark, stage(mark.depth()));
    }

    /** Vanilla entity tracking/lighting, one composite model; only the chunk mark survives unload or restart. */
    private static void show(ServerLevel level, Mark mark) {
        BlockPos p = mark.pos();
        if (!Terrain.ready(level, p.above()) || !level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()) return;
        Map<BlockPos, Visible> live = level.getData(ModAttachments.SURFACE_DISPLAYS).live;
        Visible old = live.get(p);
        if (old != null && !old.entity().isRemoved()) return;
        live.remove(p);
        // Removed/inaccessible displays must not reserve slots until the next maintenance tick.
        if (live.size() >= DISPLAY_CAP) live.values().removeIf(v -> v.entity().isRemoved());
        if (live.size() >= DISPLAY_CAP) return;
        Display.BlockDisplay entity = ModEntities.SURFACE_DAMAGE.get().create(level);
        if (entity == null) return;
        CompoundTag nbt = new CompoundTag();
        nbt.put("block_state", NbtUtils.writeBlockState(mark.state()));
        nbt.putFloat("view_range", 4);
        nbt.putFloat("width", 2);
        nbt.putFloat("height", 1);
        entity.load(nbt);
        entity.setPos(p.getX(), p.getY() + 1, p.getZ());
        if (level.addFreshEntity(entity)) live.put(p, new Visible(mark.state(), entity));
    }

    /** Migrate #437's saved vanilla displays on load, including chunks that never start entity ticking. */
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel && event.getEntity().getType() == EntityType.BLOCK_DISPLAY
                && event.getEntity().getPersistentData().contains("incoming_surface_pos")) event.setCanceled(true);
    }

    private static void discardRubble(ServerLevel level, Mark m) {
        Visible current = level.getData(ModAttachments.SURFACE_DISPLAYS).live.remove(m.pos());
        if (current != null) current.entity().discard();
        for (UUID id : m.rubble()) {
            var entity = level.getEntity(id);
            if (entity != null) entity.discard();
        }
    }

    public static double depth(ServerLevel level, BlockPos p) {
        if (!Terrain.ready(level, p)) return 0;
        for (Mark m : level.getChunkAt(p).getData(ModAttachments.SURFACE_DAMAGE)) {
            if (m.pos().equals(p) && level.getBlockState(p).equals(m.state())) return m.depth();
        }
        return 0;
    }

    /** Presentation only: use vanilla cracks and its packet, without a new block or network format. */
    private static int stage(double depth) {
        return Mth.clamp((int) Math.ceil(depth * 10) - 1, 0, 8);
    }

    private static void send(ServerPlayer player, Mark m, int stage) {
        if (player.distanceToSqr(m.pos().getX() + 0.5, m.pos().getY() + 0.5, m.pos().getZ() + 0.5) > 32 * 32) return;
        // Entity ids are positive; use a separate negative id for each damaged location.
        int id = Long.hashCode(m.pos().asLong()) | Integer.MIN_VALUE;
        player.connection.send(new ClientboundBlockDestructionPacket(id, m.pos(), stage));
    }

    /** Vanilla expires its crack overlay after 400 ticks. Refresh nearby saved marks, including after reconnecting. */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % 20 != 0) return;
        Map<BlockPos, Visible> live = level.getData(ModAttachments.SURFACE_DISPLAYS).live;
        live.entrySet().removeIf(e -> {
            Display.BlockDisplay entity = e.getValue().entity();
            if (entity.isRemoved()) return true;
            // Keep the object until discarded: an inaccessible entity can survive in a chunk without getEntity(id).
            if (!Terrain.ready(level, e.getKey()) || !level.getBlockState(e.getKey()).equals(e.getValue().state())
                    || Terrain.ready(level, e.getKey().above()) && !level.getBlockState(e.getKey().above())
                            .getCollisionShape(level, e.getKey().above()).isEmpty()) {
                entity.discard();
                return true;
            }
            if (level.getGameTime() % 100 == 0 && level.players().stream().noneMatch(p -> p.distanceToSqr(entity) <= DISPLAY_RANGE * DISPLAY_RANGE)) {
                entity.discard();
                return true;
            }
            return false;
        });
        if (level.getGameTime() % 100 != 0) return;
        for (ServerPlayer player : level.players()) {
            int cx = player.chunkPosition().x, cz = player.chunkPosition().z;
            for (int x = cx - 4; x <= cx + 4; x++) for (int z = cz - 4; z <= cz + 4; z++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null || !chunk.hasData(ModAttachments.SURFACE_DAMAGE)) continue;
                List<Mark> marks = chunk.getData(ModAttachments.SURFACE_DAMAGE);
                List<Mark> kept = new ArrayList<>();
                for (Mark m : marks) {
                    boolean current = level.getBlockState(m.pos()).equals(m.state());
                    send(player, m, current ? stage(m.depth()) : -1);
                    if (current) {
                        if (!m.rubble().isEmpty()) discardRubble(level, m);
                        Mark clean = m.rubble().isEmpty() ? m : new Mark(m.pos(), m.state(), m.depth());
                        kept.add(clean);
                        if (player.distanceToSqr(m.pos().getCenter()) <= DISPLAY_RANGE * DISPLAY_RANGE) show(level, clean);
                    }
                    else discardRubble(level, m);
                }
                if (!kept.equals(marks)) {
                    chunk.setData(ModAttachments.SURFACE_DAMAGE, List.copyOf(kept));
                    chunk.setUnsaved(true);
                }
            }
        }
    }

    /** Replacing a block by the same material must not inherit the old surface damage. */
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        clear(event);
    }

    public static void onBreak(BlockEvent.BreakEvent event) {
        clear(event);
    }

    private static void clear(BlockEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Terrain.ready(level, event.getPos())) return;
        LevelChunk chunk = level.getChunkAt(event.getPos());
        if (!chunk.hasData(ModAttachments.SURFACE_DAMAGE)) return;
        List<Mark> marks = new ArrayList<>(chunk.getData(ModAttachments.SURFACE_DAMAGE));
        marks.removeIf(m -> {
            if (!m.pos().equals(event.getPos())) return false;
            discardRubble(level, m);
            for (ServerPlayer player : level.players()) send(player, m, -1);
            return true;
        });
        chunk.setData(ModAttachments.SURFACE_DAMAGE, List.copyOf(marks));
        chunk.setUnsaved(true);
    }
}
