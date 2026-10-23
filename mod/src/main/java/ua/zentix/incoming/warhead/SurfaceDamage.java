package ua.zentix.incoming.warhead;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
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

/** Sub-block excavation in cohesive material: retain its collision shape and save the damaged depth with the chunk. */
public final class SurfaceDamage {
    public record Mark(BlockPos pos, BlockState state, double depth) {
        static final Codec<Mark> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(Mark::pos),
                BlockState.CODEC.fieldOf("state").forGetter(Mark::state),
                Codec.doubleRange(0, 1).fieldOf("depth").forGetter(Mark::depth)).apply(i, Mark::new));
    }

    public static final Codec<List<Mark>> CODEC = Mark.CODEC.listOf();

    private SurfaceDamage() {}

    /** Maximum excavation from this surface, not summed energy from independent hits. No structural weakening model. */
    public static void mark(ServerLevel level, BlockPos p, BlockState state, double depth) {
        if (!(depth > 0) || state.isAir() || state.getCollisionShape(level, p).isEmpty() || state.getBlock().defaultDestroyTime() < 0) return;
        LevelChunk chunk = level.getChunkAt(p);
        List<Mark> marks = new ArrayList<>(chunk.getData(ModAttachments.SURFACE_DAMAGE));
        double old = depth(level, p);
        marks.removeIf(m -> m.pos().equals(p));
        Mark mark = new Mark(p.immutable(), state, Math.min(1, Math.max(old, depth)));
        marks.add(mark);
        chunk.setData(ModAttachments.SURFACE_DAMAGE, List.copyOf(marks));
        chunk.setUnsaved(true);
        for (ServerPlayer player : level.players()) send(player, mark, stage(mark.depth()));
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
            for (ServerPlayer player : level.players()) send(player, m, -1);
            return true;
        });
        chunk.setData(ModAttachments.SURFACE_DAMAGE, List.copyOf(marks));
        chunk.setUnsaved(true);
    }
}
