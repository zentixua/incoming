package ua.zentix.incoming.client.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.target.Sightings;

import java.util.ArrayList;
import java.util.List;

/**
 * Targeting map markers: friendly players and friendly sightings (hostile players, craft, projectiles). Only the
 * server knows sightings, so the map and remote player list request them every half second while open
 * ({@link C2S.MapPlayers}). For friendly and currently seen hostile entities known to the client, map follows the
 * entity smoothly between replies; older sightings stay at their last seen position.
 */
public final class MapPlayers {
    /**
     * Marker: name (player username, also the remote target), position, type, friendly flag and seconds since seen (0 now).
     */
    public record Mark(String name, double x, double z, Sightings.Kind kind, boolean friendly, int age) {
        /** Marker is a valid strike target: hostile player (by username). */
        public boolean target() {
            return kind == Sightings.Kind.PLAYER && !friendly;
        }
    }

    private static List<S2C.MapPlayer> players = List.of();
    /** Map draws markers ({@code map_players}); otherwise they are only remote targets. */
    private static boolean shown;
    /** Dimension where the list arrived: after dimension change the old list's coordinates belong elsewhere. */
    @Nullable
    private static ResourceKey<Level> dimension;

    private MapPlayers() {}

    public static void request() {
        PacketDistributor.sendToServer(new C2S.MapPlayers());
    }

    public static void received(S2C.MapPlayers p) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        players = List.copyOf(p.players());
        shown = p.shown();
        dimension = level.dimension();
    }

    /** Map markers in dimension {@code level} at frame time {@code partialTick} (empty if world settings disable them). */
    public static List<Mark> marks(@Nullable ClientLevel level, float partialTick) {
        if (level == null || !shown || !level.dimension().equals(dimension)) return List.of();
        List<Mark> marks = new ArrayList<>(players.size());
        for (S2C.MapPlayer p : players) {
            Player seen = p.kind() == Sightings.Kind.PLAYER && p.age() == 0 ? level.getPlayerByUUID(p.id()) : null;
            marks.add(seen != null
                    ? new Mark(p.name(), seen.getPosition(partialTick).x, seen.getPosition(partialTick).z, p.kind(), p.friendly(), 0)
                    : new Mark(p.name(), p.x(), p.z(), p.kind(), p.friendly(), p.age()));
        }
        return marks;
    }

    /** Alphabetical usernames of hostile players eligible for a strike (sighted by friendlies). */
    public static List<String> targets(@Nullable ClientLevel level) {
        if (level == null || !level.dimension().equals(dimension)) return List.of();
        List<String> names = new ArrayList<>();
        for (S2C.MapPlayer p : players) {
            if (p.kind() == Sightings.Kind.PLAYER && !p.friendly()) names.add(p.name());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public static void reset() {
        players = List.of();
        shown = false;
        dimension = null;
    }
}
