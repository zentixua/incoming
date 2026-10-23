package ua.zentix.incoming.warhead;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.Blocks;
import com.mojang.math.Transformation;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.List;
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

    private SurfaceDamage() {}

    /** Maximum excavation from this surface, not summed energy from independent hits. No structural weakening model. */
    public static void mark(ServerLevel level, BlockPos p, BlockState state, double depth) {
        if (!(depth > 0) || state.isAir() || state.getCollisionShape(level, p).isEmpty() || state.getBlock().defaultDestroyTime() < 0) return;
        LevelChunk chunk = level.getChunkAt(p);
        List<Mark> marks = new ArrayList<>(chunk.getData(ModAttachments.SURFACE_DAMAGE));
        double old = depth(level, p);
        for (Mark m : marks) if (m.pos().equals(p)) {
            if (m.state().equals(state) && m.depth() >= depth && !m.rubble().isEmpty()) return;
            discardRubble(level, m);
        }
        marks.removeIf(m -> m.pos().equals(p));
        double damaged = Math.min(1, Math.max(old, depth));
        Mark mark = new Mark(p.immutable(), state, damaged, rubble(level, p, state, damaged));
        marks.add(mark);
        chunk.setData(ModAttachments.SURFACE_DAMAGE, List.copyOf(marks));
        chunk.setUnsaved(true);
        for (ServerPlayer player : level.players()) send(player, mark, stage(mark.depth()));
    }

    /** Vanilla block displays supply saved, shader-lit chips and exposed aggregate, tracked with the terrain.
     * This is a visual approximation of a fractured surface, not removal of the remaining metre of collision. */
    private static List<UUID> rubble(ServerLevel level, BlockPos p, BlockState original, double depth) {
        List<UUID> ids = new ArrayList<>();
        if (!level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()) return List.of();
        chip(level, p, Blocks.BLACKSTONE.defaultBlockState(), 0.01f, 0.006f, 0.03f, 0.97f, 0.018f, 0.92f, -0.16f, 0, ids);
        chip(level, p, Blocks.GRAVEL.defaultBlockState(), 0.17f, 0.026f, 0.18f, 0.66f, 0.025f, 0.62f, 0.29f, 0, ids);
        chip(level, p, original, 0.58f, 0.07f, 0.26f, 0.36f, (float) Math.min(0.15, depth * 0.3), 0.34f, -0.41f, 0.22f, ids);
        chip(level, p, original, 0.04f, 0.045f, 0.61f, 0.28f, (float) Math.min(0.1, depth * 0.2), 0.27f, 0.48f, -0.17f, ids);
        return List.copyOf(ids);
    }

    private static void chip(ServerLevel level, BlockPos p, BlockState state, float x, float y, float z,
                             float sx, float sy, float sz, float yaw, float tilt, List<UUID> ids) {
        Display.BlockDisplay entity = EntityType.BLOCK_DISPLAY.create(level);
        if (entity == null) return;
        CompoundTag nbt = new CompoundTag();
        nbt.put("block_state", NbtUtils.writeBlockState(state));
        var transform = new Transformation(new Vector3f(x, y, z), new Quaternionf().rotateY(yaw).rotateX(tilt),
                new Vector3f(sx, sy, sz), new Quaternionf());
        nbt.put("transformation", Transformation.EXTENDED_CODEC.encodeStart(NbtOps.INSTANCE, transform).getOrThrow());
        nbt.putFloat("view_range", 4);
        nbt.putFloat("width", 3);
        nbt.putFloat("height", 1);
        nbt.putBoolean("NoGravity", true);
        entity.load(nbt);
        entity.setPos(p.getX(), p.getY() + 1, p.getZ());
        entity.getPersistentData().putLong("incoming_surface_pos", p.asLong());
        entity.getPersistentData().put("incoming_surface_state", NbtUtils.writeBlockState(level.getBlockState(p)));
        if (level.addFreshEntity(entity)) ids.add(entity.getUUID());
    }

    private static void discardRubble(ServerLevel level, Mark m) {
        for (UUID id : m.rubble()) {
            var entity = level.getEntity(id);
            if (entity != null) entity.discard();
        }
    }

    /** Displays also clean themselves after non-player terrain changes, including after unload/reload. */
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Display.BlockDisplay display) || !(display.level() instanceof ServerLevel level)
                || display.tickCount % 20 != 0 || !display.getPersistentData().contains("incoming_surface_pos")) return;
        BlockPos p = BlockPos.of(display.getPersistentData().getLong("incoming_surface_pos"));
        if (!Terrain.ready(level, p)) return;
        BlockState expected = NbtUtils.readBlockState(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                display.getPersistentData().getCompound("incoming_surface_state"));
        if (!level.getBlockState(p).equals(expected)) display.discard();
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
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % 100 != 0) return;
        for (ServerPlayer player : level.players()) {
            int cx = player.chunkPosition().x, cz = player.chunkPosition().z;
            for (int x = cx - 2; x <= cx + 2; x++) for (int z = cz - 2; z <= cz + 2; z++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null || !chunk.hasData(ModAttachments.SURFACE_DAMAGE)) continue;
                List<Mark> marks = chunk.getData(ModAttachments.SURFACE_DAMAGE);
                List<Mark> kept = new ArrayList<>();
                for (Mark m : marks) {
                    boolean current = level.getBlockState(m.pos()).equals(m.state());
                    send(player, m, current ? stage(m.depth()) : -1);
                    if (current) kept.add(m);
                    else discardRubble(level, m);
                }
                if (kept.size() != marks.size()) {
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
