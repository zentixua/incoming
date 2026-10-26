package ua.zentix.incoming.target;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Function;

/**
 * Friend/foe via {@code /team} (vanilla scoreboard): same player or same team is friendly. Teamless player is
 * their own side. Console/command-block ownerless strikes belong to nobody, hostile to all.
 */
public final class Sides {
    private Sides() {}

    /** Friendly: same entity or team ({@link Entity#isAlliedTo(Entity)}). */
    public static boolean friendly(Entity a, Entity b) {
        return a == b || a.isAlliedTo(b);
    }

    /**
     * Friendly player UUIDs (projectile/launcher owners, possibly offline): same player or team. Null is unowned
     * (console), friendly to nobody.
     */
    public static boolean friendly(MinecraftServer server, @Nullable UUID a, @Nullable UUID b) {
        return friendly(server.getScoreboard(), a, b, names(server));
    }

    /** {@link #friendly(MinecraftServer, UUID, UUID)} using {@code board} and player {@code names}. */
    public static boolean friendly(Scoreboard board, @Nullable UUID a, @Nullable UUID b, Function<UUID, @Nullable String> names) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        PlayerTeam team = team(board, a, names);
        return team != null && team.isAlliedTo(team(board, b, names));
    }

    /**
     * Side key shared by friendlies: team name, or UUID for teamless player (distinct prefixes prevent
     * confusion).
     */
    public static String side(MinecraftServer server, UUID player) {
        return side(server.getScoreboard(), player, names(server));
    }

    /** {@link #side(MinecraftServer, UUID)} using {@code board} and player {@code names}. */
    public static String side(Scoreboard board, UUID player, Function<UUID, @Nullable String> names) {
        return key(team(board, player, names), player);
    }

    /** Online player's side key ({@link #side(MinecraftServer, UUID)}): team via scoreboard name. */
    public static String side(ServerPlayer player) {
        return key(player.getTeam(), player.getUUID());
    }

    private static String key(@Nullable PlayerTeam team, UUID player) {
        return team != null ? "team:" + team.getName() : "player:" + player;
    }

    /** Player team by UUID: scoreboard stores team members by name ({@link #names}). */
    @Nullable
    public static PlayerTeam team(Scoreboard board, UUID player, Function<UUID, @Nullable String> names) {
        String name = names.apply(player);
        return name == null ? null : board.getPlayersTeam(name);
    }

    /**
     * Scoreboard name by UUID: online player's name, otherwise server profile cache (null if absent: team
     * unknown).
     */
    public static Function<UUID, @Nullable String> names(MinecraftServer server) {
        return id -> {
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null) return online.getScoreboardName();
            GameProfileCache cache = server.getProfileCache();
            return cache == null ? null : cache.get(id).map(GameProfile::getName).orElse(null);
        };
    }
}
