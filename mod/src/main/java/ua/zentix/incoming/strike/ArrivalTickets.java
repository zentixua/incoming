package ua.zentix.incoming.strike;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.SortedArraySet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.Comparator;
import java.util.UUID;

/**
 * Player priority is active only while Incoming holds other areas in the destination dimension. Releasing the last
 * such area also removes arrival tickets, including queued releases; idle worlds do not track players.
 * <p>
 * A player joining the world, changing dimension, or awaiting respawn has their location loaded before mod areas.
 * <p>
 * On their first tick, a player synchronously loads their chunk ({@code ServerPlayer.doTick →
 * Entity.updateFluidOnEyes → Level.getChunk}, level-33 {@code UNKNOWN} ticket, {@code
 * ServerChunkCache.getChunk}). Loading and generation tasks follow holder level ({@code
 * ChunkMap.runGenerationTask}, {@code ChunkTaskPriorityQueue}: lower first, ties in order), while mod areas use
 * levels 27–33 (target-area center {@link FlightTickets#DISTANCE} is 29, Lancet {@link
 * FlightTickets#LOITER_DISTANCE} is 27), player tickets 31. During a salvo, entry-site loading queues behind all
 * areas; without a dedicated ticket the server waits 0.55–0.8 s within the tick.
 * <p>
 * On login ({@code PlayerLoggedInEvent}: end of {@code PlayerList.placeNewPlayer}, before first player tick) and
 * after dimension change ({@code PlayerChangedDimensionEvent}), place a loading ticket at level {@code 33 −
 * }{@link #DISTANCE} = 26 on the player's chunk, below any mod area center. Full disk chunk loading waits for
 * neighbors (radius 1, {@code ChunkPyramid.LOADING_PYRAMID}: {@code LIGHT}), at level 27: only previously
 * acquired Lancet area centers remain ahead, not their rings. A level-28 ticket would wait for the entire Lancet
 * salvo (GameTest {@code arrivalLoadsBeforeLoiterAreas}). Loading only ({@link AreaLoader} without ticking): adds
 * no tick tracking; blocks and entities tick as without it, from player tickets. Gives levels up to 32 within
 * radius 6, chunks player tickets will also cover in view distance a few ticks later; vanilla decides ticking by
 * its own rules (see {@link AreaLoader} on loading tickets). Radius 7 is fully loaded; below view distance 7 this
 * temporarily exceeds the player's own coverage. Entry chunks usually exist on disk: loading, not generation.
 * <p>
 * Remove arrival ticket once radius {@code DISTANCE − 2} = 5 (no farther than view distance) has player tickets
 * ({@code TicketType.PLAYER}, any player; {@code DistanceManager} places them via queue): their status levels are
 * then no higher than arrival-ticket levels, so removal demotes nothing. Remove no later than {@link #LIFESPAN}
 * ticks (player moved far away or left; during {@code /tick freeze} expiry stops with world time). Salvo areas
 * acquired in the first seconds after login become ready later; projectiles wait for them themselves.
 * <p>
 * No ticket for spectators without chunk generation ({@code spectatorsGenerateChunks} = false): vanilla gives
 * them no tickets and does not tick them over unloaded chunks.
 * <p>
 * Respawn: {@code PlayerList.respawn} synchronously reads bed or anchor ({@code
 * ServerPlayer.findRespawnAndUseSpawnBlock → Level.getBlockState}) before {@code PlayerRespawnPositionEvent} and
 * {@code PlayerRespawnEvent}; thus place the same ticket already at death ({@code LivingDeathEvent}) on the
 * player's respawn point ({@code getRespawnPosition}/{@code getRespawnDimension}). Without one, player appears at
 * world spawn held by vanilla {@code START} ({@code spawnChunkRadius}); mod ticket there only if {@code
 * spawnChunkRadius} = 0. While death screen is open, the site loads first. Respawn ticket is a {@link
 * #LEASE}-tick lease renewed by dead-player ticks (dead players tick on death screen). Respawn (arrival ticket
 * already at actual spawn), logout, or living-player tick (another mod canceled death) removes it; expiry
 * releases a player who no longer ticks (unloaded, another mod's fake player). Slot transfers to new player
 * object in {@code PlayerEvent.Clone}: an attachment without serializer is not copied automatically. While held,
 * radius 5 around bed entity-ticks without a nearby player: {@code checkDespawn} runs as at world spawn, so
 * random mobs near bed disappear if another player in this dimension is farther than 128 blocks.
 * <p>
 * Bed or anchor on Sable craft: respawn point is in plot-grid coordinates; Sable respawns at its own world point
 * (Sable {@code ServerPlayerMixin}). No loading tickets in plot grid (Sable chunk holders there), so no ticket.
 * Hardcore respawn makes the player a spectator without generation: no arrival ticket.
 * <p>
 * In-dimension teleport ({@code /tp}, pearl, mod commands/code): NeoForge synchronously loads destination chunk
 * ({@code Entity.setPosRaw → Level.getChunk}) with a level-33 {@code UNKNOWN} ticket removed a tick later ({@code
 * purgeStaleTickets}); new player tickets queue a few per tick. The synchronously loaded ring (up to 11 chunks
 * from site) falls below loading then rises again with player tickets while neighbors still generate. Vanilla
 * unload task ({@code ChunkMap.scheduleUnload}) sees {@code getGenerationRefCount() > 0}, requeues itself, and
 * {@code processUnloads} processes it until tick time ends: exactly 50 ms/tick, no time for chunk tasks, so the
 * generation holding it barely progresses (scene bench: 100 Shaheds, camera teleported with salvo, 50 ms/tick for
 * up to a minute; projectiles enter the world at 52–55 s instead of 3–5). Thus a player jump ({@link
 * #onLevelTick}: at world tick end their chunk moved at least {@link #JUMP} chunks and player tickets do not hold
 * the site) places the same arrival ticket on the new chunk before the second stale-ticket purge removes {@code
 * UNKNOWN}: arrival holds radius 18 loaded, preserving the synchronous ring and loading the site before mod
 * areas. This does not remove the synchronous destination-chunk load itself.
 * <p>
 * Does not cover portal search ({@code PortalForcer}), which loads before the mod learns of it; respawn positions
 * replaced by another mod in {@code PlayerRespawnPositionEvent}, craft respawn, or world spawn when the bed is
 * gone. A player logging out from death screen and back in gets only an arrival ticket on the death chunk (their
 * object is there), with no respawn-site ticket: login has no death event.
 */
public final class ArrivalTickets {
    /** Ticket level 33 − 7 = 26: below Lancet area center (27). */
    public static final int DISTANCE = FlightTickets.LOITER_DISTANCE + 1;
    /** Arrival ticket expires automatically after 10 s. */
    public static final int LIFESPAN = 200;
    /** Respawn ticket lease: 5 s after last dead-player tick. */
    public static final int LEASE = 100;
    /**
     * Jump: player chunk moved at least this many chunks in one world tick (maximum axis). Walking or flight
     * cannot do that (2 chunks/tick is 640 blocks/s); a teleport beyond view distance can.
     */
    public static final int JUMP = 2;
    static final TicketType<UUID> TYPE = TicketType.create("incoming_arrival", Comparator.<UUID>naturalOrder());

    private ArrivalTickets() {}

    /** Only active Incoming areas compete with player loading; arrival tickets cannot keep themselves alive. */
    private static boolean needed(ServerLevel level) {
        return level.hasData(ModAttachments.STRIKE_WORLD) && StrikeWorld.get(level).areas().hasOtherThan(TYPE);
    }

    private static void forget(ServerPlayer player) {
        if (!player.hasData(ModAttachments.ARRIVAL.get())) return;
        release(player.server, player.getUUID(), player.getData(ModAttachments.ARRIVAL.get()));
        player.removeData(ModAttachments.ARRIVAL.get());
    }

    /**
     * Player ticket location (non-persistent attachment). After respawn old and new player objects hold the same
     * slot ({@link #onClone}): removal is idempotent; logout between {@code Clone} and end of {@code
     * PlayerList.respawn} removes via the old object. Copying and clearing the old slot would lose it.
     */
    public static final class Slot {
        @Nullable
        ResourceKey<Level> dimension;
        @Nullable
        ChunkPos held;
        long until;
        /** Ticket is at respawn site, player dead: lease renewed by player ticks. */
        boolean respawn;
        /** Player location at previous world tick ({@link #onLevelTick}): dimension and chunk. */
        @Nullable
        ResourceKey<Level> seenIn;
        long seenAt;
    }

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) arrive(p);
    }

    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) arrive(p);
    }

    /**
     * {@code LOWEST} priority: after all listeners except other {@code LOWEST}. Earlier canceled deaths do not
     * arrive ({@code addListener} without {@code receiveCanceled}); later cancellation is cleaned up by a
     * living-player tick.
     */
    public static void onDeath(LivingDeathEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) awaitRespawn(p);
    }

    /** Transfer slot to new player object (respawn, leaving the End): no serializer means no attachment copy. */
    public static void onClone(PlayerEvent.Clone e) {
        if (!(e.getOriginal() instanceof ServerPlayer p) || !p.hasData(ModAttachments.ARRIVAL.get())) return;
        Slot slot = p.getData(ModAttachments.ARRIVAL.get());
        ServerLevel level = slot.dimension == null ? null : p.server.getLevel(slot.dimension);
        if (level == null || !needed(level) || slot.held == null || !StrikeWorld.get(level).areas().holds(area(slot.held, p.getUUID()))) {
            forget(p);
            return;
        }
        e.getEntity().setData(ModAttachments.ARRIVAL.get(), slot);
    }

    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        // Hardcore: player becomes a spectator without generation after the event.
        if (p.server.isHardcore() && !e.isEndConquered()) {
            forget(p);
            return;
        }
        arrive(p);
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) forget(p);
    }

    public static void onPlayerTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !p.hasData(ModAttachments.ARRIVAL.get())) return;
        tick(p.server, p.getUUID(), p.getData(ModAttachments.ARRIVAL.get()), p.server.getPlayerList().getViewDistance(), p.isDeadOrDying());
        if (p.getData(ModAttachments.ARRIVAL.get()).held == null && !needed(p.serverLevel())) forget(p);
    }

    /** World tick end: player jump within this world places an arrival ticket at the new site ({@link #moved}). */
    public static void onLevelTick(LevelTickEvent.Post e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !needed(level)) return;
        int view = level.getServer().getPlayerList().getViewDistance();
        for (ServerPlayer p : level.players()) {
            if (!generates(p) || p.isDeadOrDying()) continue;
            moved(level, p.chunkPosition(), p.getUUID(), p.getData(ModAttachments.ARRIVAL.get()), view);
        }
    }

    /**
     * Player {@code who} is in chunk {@code now} this world tick (also tests without a player). Jump in same
     * world (at least {@link #JUMP} chunks since last tick) to a site not held by player tickets (radius {@code
     * DISTANCE − 2}, capped by {@code viewDistance}) places arrival ticket there, replacing the old one.
     *
     * @return ticket placed
     */
    public static boolean moved(ServerLevel level, ChunkPos now, UUID who, Slot slot, int viewDistance) {
        if (!needed(level)) {
            release(level.getServer(), who, slot);
            slot.seenIn = null;
            return false;
        }
        boolean jumped = level.dimension().equals(slot.seenIn) && new ChunkPos(slot.seenAt).getChessboardDistance(now) >= JUMP;
        slot.seenIn = level.dimension();
        slot.seenAt = now.toLong();
        // No loading tickets in Sable plot grid (Sable chunk holders there), as for respawn sites.
        if (!jumped || slot.respawn || SubLevels.inPlotGrid(level, now) || coveredByPlayers(level, now, Math.min(DISTANCE - 2, viewDistance))) return false;
        arrive(level, now, who, slot);
        return true;
    }

    /**
     * Ticket for dead player {@code p}'s respawn site: personal point (bed, anchor), otherwise overworld spawn as
     * in {@code PlayerList.respawn}. Remove previous ticket; no new one for spectator without generation, Sable
     * plot-grid point, or world spawn held by {@code START}.
     */
    private static void awaitRespawn(ServerPlayer p) {
        forget(p);
        if (!generates(p)) return;
        ServerLevel level = p.server.getLevel(p.getRespawnDimension());
        BlockPos pos = p.getRespawnPosition();
        if (level == null || pos == null) {
            level = p.server.overworld();
            if (level.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS) > 0) return;
            pos = level.getSharedSpawnPos();
        }
        if (!needed(level)) return;
        ChunkPos chunk = new ChunkPos(pos);
        // Bed on craft: Sable respawns at its own world point; plot grid must have no tickets.
        if (SubLevels.inPlotGrid(level, chunk)) return;
        awaitRespawn(level, chunk, p.getUUID(), p.getData(ModAttachments.ARRIVAL.get()));
    }

    /** Replace previous ticket with a {@link #LEASE}-tick respawn-site lease (also tests without a player). */
    public static void awaitRespawn(ServerLevel level, ChunkPos pos, UUID who, Slot slot) {
        release(level.getServer(), who, slot);
        if (!needed(level)) return;
        slot.until = level.getGameTime() + LEASE;
        slot.respawn = true;
        StrikeWorld.get(level).areas().hold(level, area(pos, who), slot.until);
        slot.dimension = level.dimension();
        slot.held = pos;
    }

    private static void arrive(ServerPlayer p) {
        if (!needed(p.serverLevel()) || !generates(p)) {
            forget(p);
            return;
        }
        arrive(p.serverLevel(), p.chunkPosition(), p.getUUID(), p.getData(ModAttachments.ARRIVAL.get()));
    }

    /** Vanilla loads no chunks for a spectator without generation; neither does the mod. */
    private static boolean generates(ServerPlayer p) {
        return !p.isSpectator() || p.serverLevel().getGameRules().getBoolean(GameRules.RULE_SPECTATORSGENERATECHUNKS);
    }

    /** Replace previous ticket with an arrival ticket on chunk {@code pos} (also tests without a player). */
    public static void arrive(ServerLevel level, ChunkPos pos, UUID who, Slot slot) {
        release(level.getServer(), who, slot);
        if (!needed(level)) return;
        slot.until = level.getGameTime() + LIFESPAN;
        StrikeWorld.get(level).areas().hold(level, area(pos, who), slot.until);
        slot.dimension = level.dimension();
        slot.held = pos;
    }

    /**
     * Tick player {@code who} (also tests without a player). Remove arrival ticket once player tickets hold the
     * site (radius {@code DISTANCE − 2}, capped by {@code viewDistance}) or expiry. Renew dead-player respawn
     * lease for {@link #LEASE} ticks; remove for living player.
     */
    public static void tick(MinecraftServer server, UUID who, Slot slot, int viewDistance, boolean dead) {
        if (slot.held == null || slot.dimension == null) return;
        ServerLevel level = server.getLevel(slot.dimension);
        if (level == null || !needed(level) || !StrikeWorld.get(level).areas().holds(area(slot.held, who))) {
            release(server, who, slot);
            return;
        }
        if (slot.respawn) {
            if (!dead) {
                release(server, who, slot);
            } else {
                slot.until = level.getGameTime() + LEASE;
                StrikeWorld.get(level).areas().renew(area(slot.held, who), slot.until);
            }
            return;
        }
        if (level.getGameTime() >= slot.until || coveredByPlayers(level, slot.held, Math.min(DISTANCE - 2, viewDistance))) {
            release(server, who, slot);
        }
    }

    /** Remove ticket; if already expired ({@link AreaLoader}), only clear the slot. */
    public static void release(MinecraftServer server, UUID who, Slot slot) {
        if (slot.held == null || slot.dimension == null) return;
        ServerLevel level = server.getLevel(slot.dimension);
        if (level != null) StrikeWorld.get(level).areas().release(level, area(slot.held, who));
        slot.held = null;
        slot.dimension = null;
        slot.respawn = false;
    }

    /** Count player's arrival tickets (tests). */
    public static int count(ServerLevel level, UUID who) {
        return level.hasData(ModAttachments.STRIKE_WORLD) ? StrikeWorld.get(level).areas().count(TYPE, who) : 0;
    }

    /** Chunk holding the slot's ticket, or null (tests). */
    @Nullable
    public static ChunkPos held(Slot slot) {
        return slot.held;
    }

    private static AreaLoader.Area area(ChunkPos pos, UUID who) {
        return new AreaLoader.Area(TYPE, pos, DISTANCE, who, false);
    }

    /** Every chunk in {@code radius} around {@code centre} has a player ticket (any player). */
    private static boolean coveredByPlayers(ServerLevel level, ChunkPos centre, int radius) {
        var tickets = level.getChunkSource().chunkMap.getDistanceManager().tickets;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                SortedArraySet<Ticket<?>> at = tickets.get(ChunkPos.asLong(centre.x + dx, centre.z + dz));
                if (at == null || at.stream().noneMatch(t -> t.getType() == TicketType.PLAYER)) return false;
            }
        }
        return true;
    }
}
