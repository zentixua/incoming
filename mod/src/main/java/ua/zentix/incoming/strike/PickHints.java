package ua.zentix.incoming.strike;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.item.DesignatorItem;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.Comparator;
import java.util.UUID;

/**
 * Designator map hint: site clicked, no order yet; area starts loading now (background, by chunks, {@link
 * AreaLoader}, like projectile target area), usually ready by launch. One per player: next click replaces it; new
 * area at most once per second (faster click remembered and acquired when allowed); lives {@link #LIFESPAN}
 * ticks, releases itself even if player logs out.
 */
public final class PickHints {
    /** Hint area releases automatically after 30 s. */
    public static final int LIFESPAN = 600;
    /** New area at most once per second per player. */
    public static final int MIN_INTERVAL = 20;
    private static final TicketType<UUID> TYPE = TicketType.create("incoming_pick", Comparator.<UUID>naturalOrder());

    private PickHints() {}

    /** Player hint area (non-persistent attachment): acquired and deferred click. */
    public static final class Slot {
        @Nullable
        ResourceKey<Level> dimension;
        @Nullable
        ChunkPos held;
        long takenAt = Long.MIN_VALUE / 2;
        @Nullable
        ResourceKey<Level> pendingDimension;
        @Nullable
        ChunkPos pending;

        @Nullable
        public ChunkPos held() {
            return held;
        }
    }

    /**
     * Map click. Same checks as map order ({@link ServerActions#groundInRange}), plus designator in hand
     * (required to open map), not spectator.
     */
    public static void pick(C2S.Pick p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player) || player.isSpectator() || !ServerActions.mayUse(player)) return;
        if (!(player.getItemInHand(InteractionHand.MAIN_HAND).getItem() instanceof DesignatorItem)
                && !(player.getItemInHand(InteractionHand.OFF_HAND).getItem() instanceof DesignatorItem)) return;
        if (!Double.isFinite(p.x()) || !Double.isFinite(p.z()) || !ServerActions.groundInRange(player, p.x(), p.z())) return;
        pick(player.serverLevel(), player.getUUID(), player.getData(ModAttachments.PICK_HINT.get()), p.x(), p.z());
    }

    /**
     * Acquire area around (x, z), replacing previous. Same area (adjacent chunk) only extends expiry; new before
     * {@link #MIN_INTERVAL} since last is remembered for {@link #onPlayerTick}.
     */
    public static void pick(ServerLevel level, UUID who, Slot slot, double x, double z) {
        ChunkPos pos = new ChunkPos(BlockPos.containing(x, 0, z));
        long now = level.getGameTime();
        boolean same = slot.held != null && level.dimension().equals(slot.dimension) && slot.held.getChessboardDistance(pos) < 2;
        if (!same && now - slot.takenAt < MIN_INTERVAL) {
            slot.pendingDimension = level.dimension();
            slot.pending = pos;
            return;
        }
        take(level, who, slot, same ? slot.held : pos);
    }

    private static void take(ServerLevel level, UUID who, Slot slot, ChunkPos pos) {
        release(level.getServer(), who, slot);
        StrikeWorld.get(level).areas().hold(level, area(pos, who), level.getGameTime() + LIFESPAN);
        slot.dimension = level.dimension();
        slot.held = pos;
        slot.takenAt = level.getGameTime();
        slot.pending = null;
        slot.pendingDimension = null;
    }

    private static void release(MinecraftServer server, UUID who, Slot slot) {
        if (slot.held == null || slot.dimension == null) return;
        ServerLevel level = server.getLevel(slot.dimension);
        if (level != null) StrikeWorld.get(level).areas().release(level, area(slot.held, who));
        slot.held = null;
        slot.dimension = null;
    }

    /** Deferred click after {@link #MIN_INTERVAL}; also tests without player. */
    public static void tick(ServerLevel level, UUID who, Slot slot) {
        if (slot.pending == null || level.getGameTime() - slot.takenAt < MIN_INTERVAL) return;
        ServerLevel at = level.getServer().getLevel(slot.pendingDimension);
        if (at != null) take(at, who, slot, slot.pending);
        else slot.pending = null;
    }

    public static void onPlayerTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer player) || !player.hasData(ModAttachments.PICK_HINT.get())) return;
        tick(player.serverLevel(), player.getUUID(), player.getData(ModAttachments.PICK_HINT.get()));
    }

    private static AreaLoader.Area area(ChunkPos pos, UUID who) {
        return new AreaLoader.Area(TYPE, pos, FlightTickets.DISTANCE, who);
    }

    /** Player hint areas acquired or loading (tests). */
    public static int areas(ServerLevel level, UUID who) {
        return StrikeWorld.get(level).areas().count(TYPE, who);
    }

    /** Map hint ticket (tests and scene bench count hint areas). */
    public static boolean isPickTicket(TicketType<?> type) {
        return type == TYPE;
    }
}
