package ua.zentix.incoming.target;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.api.SideResolveEvent;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Friend or foe: every player has one side key ({@link SideResolveEvent}: vanilla {@code /team}, a party mod's team, or
 * the player alone); two players are friendly when they are the same player or their keys are equal, so the relation is
 * symmetric by construction. An unowned strike or launcher (console, command block) is friendly to nobody.
 * <p>
 * Keys are cached per server (an unsaved overworld attachment, {@link Cache}) and asked again after {@link #invalidate}
 * (any vanilla scoreboard change, FTB Teams membership events, a provider's own call), after the player's login or logout,
 * and once an answer is {@link #RECHECK} ticks old: the bound for a provider whose API publishes no change event (Open
 * Parties and Claims). Offline owners resolve the same way, from memory only. Server thread only.
 */
public final class Sides {
    /** Priority of Incoming's vanilla-team answer: before the bridges, after a listener at {@code HIGHEST}. */
    public static final EventPriority PRIORITY = EventPriority.HIGH;
    /** Oldest cached answer reused, server ticks (1 s): an OPAC party change applies within it. */
    public static final int RECHECK = 20;
    /** Prefix of a vanilla team's key ({@code minecraft:team/<name>}). */
    static final String TEAM = "minecraft:team/";
    /** Prefix of the key of a player without any side ({@code incoming:player/<uuid>}). */
    static final String PLAYER = "incoming:player/";
    /** Bumped by every whole-cache invalidation; a cached answer from an older epoch is stale. Any thread. */
    private static final AtomicLong EPOCH = new AtomicLong();

    private Sides() {}

    /** Friendly owners (possibly offline): the same player or the same side. Null is unowned, friendly to nobody. */
    public static boolean friendly(MinecraftServer server, @Nullable UUID a, @Nullable UUID b) {
        if (a == null || b == null) return false;
        return a.equals(b) || side(server, a).equals(side(server, b));
    }

    /** Friendly live players: the same player or the same side. */
    public static boolean friendly(ServerPlayer a, Player b) {
        if (a.getUUID().equals(b.getUUID())) return true;
        return side(a).equals(b instanceof ServerPlayer p ? side(p) : side(a.server, b.getUUID()));
    }

    /** Side key of a player by UUID, online or not. */
    public static String side(MinecraftServer server, UUID player) {
        return cache(server).side(server, player, null);
    }

    /**
     * Side key of a live player. The same as by UUID; the player's own name is used only when the server does not know
     * the UUID at all (a test or automation fake player outside the player list).
     */
    public static String side(ServerPlayer player) {
        return cache(player.server).side(player.server, player.getUUID(), player.getScoreboardName());
    }

    /** Forget every cached side ({@link SideResolveEvent#invalidate()}). Any thread. */
    public static void invalidate() {
        EPOCH.incrementAndGet();
    }

    /** Whole-cache invalidations so far: tests check that a change signal reached the cache. */
    public static long epoch() {
        return EPOCH.get();
    }

    /** The server's side cache. */
    public static Cache cache(MinecraftServer server) {
        return server.overworld().getData(ModAttachments.SIDES);
    }

    /** Login and logout: the name a vanilla team is looked up by may change, and the name's source does. */
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) cache(p.server).forget(p.getUUID());
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) cache(p.server).forget(p.getUUID());
    }

    /** Incoming's own answer at {@code HIGH} priority: the vanilla team of the player's name. */
    public static void answerTeam(SideResolveEvent event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        String name = event.getPlayerName();
        if (event.isAnswered() || server == null || name == null) return;
        PlayerTeam team = server.getScoreboard().getPlayersTeam(name);
        if (team != null) event.answer(TEAM + team.getName());
    }

    /**
     * Scoreboard name by UUID: online player's name, otherwise the server's in-memory profile cache (null if absent:
     * team unknown). Never a storage read.
     */
    public static Function<UUID, @Nullable String> names(MinecraftServer server) {
        return id -> {
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null) return online.getScoreboardName();
            GameProfileCache cache = server.getProfileCache();
            return cache == null ? null : cache.get(id).map(GameProfile::getName).orElse(null);
        };
    }

    /** One cached answer: key, whether a name was known, epoch and server tick of the answer. */
    private record Entry(String side, boolean named, long epoch, int tick) {}

    /**
     * The server's cached sides: player UUID to key ({@link ModAttachments#SIDES}, overworld, not saved: it ends with the
     * server, and a single-player world change starts empty). Registers the vanilla scoreboard's change listener.
     */
    public static final class Cache {
        private final Map<UUID, Entry> entries = new HashMap<>();
        /** Questions posted ({@link SideResolveEvent}): the cache's work counter. */
        private long resolutions;

        public Cache(IAttachmentHolder holder) {
            // Team add, remove, join, leave and change (and every score change: harmless) mark the scoreboard dirty.
            if (holder instanceof ServerLevel level) level.getServer().getScoreboard().addDirtyListener(Sides::invalidate);
        }

        /** Questions posted so far (one per cache miss). */
        public long resolutions() {
            return resolutions;
        }

        void forget(UUID player) {
            entries.remove(player);
        }

        String side(MinecraftServer server, UUID player, @Nullable String hint) {
            int now = server.getTickCount();
            long epoch = EPOCH.get();
            Entry e = entries.get(player);
            if (e != null && e.epoch == epoch && now - e.tick < RECHECK && now >= e.tick && (e.named || hint == null)) return e.side;
            String name = names(server).apply(player);
            if (name == null) name = hint;
            SideResolveEvent event = new SideResolveEvent(player, name);
            resolutions++;
            NeoForge.EVENT_BUS.post(event);
            String side = event.isAnswered() ? event.getSide() : PLAYER + player;
            entries.put(player, new Entry(side, name != null, epoch, now));
            return side;
        }
    }
}
