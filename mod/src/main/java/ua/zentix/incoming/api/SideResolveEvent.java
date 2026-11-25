package ua.zentix.incoming.api;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.target.Sides;

import java.util.Objects;
import java.util.UUID;

/**
 * Which side a player is on. Spotting, the SAM's friend-or-foe, alerts, targeting, launcher links, the overlay's private
 * lines and interception credit all ask one question: are these two players the same side? A player's side is one key;
 * two players are friendly when they are the same player or their keys are equal, so friendliness is symmetric by
 * construction.
 * <p>
 * Posted on {@code NeoForge.EVENT_BUS} from the server thread when Incoming needs a player's side and has none cached:
 * at most once per player until the cache is invalidated ({@link #invalidate()}, a vanilla scoreboard change, login or
 * logout of that player) and at least once per second while the side is in use. The player may be offline: answer from
 * memory, never from storage.
 * <p>
 * The first answer wins, so precedence is listener priority (NeoForge runs {@code HIGHEST} first). Incoming answers the
 * vanilla team at {@code HIGH} ({@code minecraft:team/<name>}); its optional bridges answer FTB Teams parties at
 * {@code NORMAL} ({@code ftbteams:<team id>}) and Open Parties and Claims parties at {@code LOW}
 * ({@code openpartiesandclaims:<party id>}). Unanswered, the player is their own side ({@code incoming:player/<uuid>}).
 * A provider abstains for a personal or absent membership, so a lower provider can still answer.
 */
public final class SideResolveEvent extends Event {
    private final UUID player;
    @Nullable
    private final String name;
    @Nullable
    private String side;

    public SideResolveEvent(UUID player, @Nullable String name) {
        this.player = Objects.requireNonNull(player, "player");
        this.name = name;
    }

    /** The player whose side is asked (online or not). */
    public UUID getPlayerId() {
        return player;
    }

    /** The player's name if the server knows it without storage (online, or its profile cache); otherwise null. */
    @Nullable
    public String getPlayerName() {
        return name;
    }

    /** The answer so far; null while nobody has answered. */
    @Nullable
    public String getSide() {
        return side;
    }

    public boolean isAnswered() {
        return side != null;
    }

    /**
     * Answer the player's side: a key {@code <namespace>:<value>} in the provider's own namespace (its mod id), equal for
     * every player of one side. The first answer wins.
     *
     * @return whether this answer was taken (false: a higher-priority listener already answered)
     * @throws IllegalArgumentException a key without a valid namespace or with an empty value
     */
    public boolean answer(String side) {
        int colon = side.indexOf(':');
        if (colon <= 0 || colon == side.length() - 1 || !ResourceLocation.isValidNamespace(side.substring(0, colon))) {
            throw new IllegalArgumentException("side key must be <namespace>:<value>: " + side);
        }
        if (this.side != null) return false;
        this.side = side;
        return true;
    }

    /**
     * Forget every cached side: call when your provider's membership changed so the next question asks again. Any thread;
     * vanilla teams and FTB Teams invalidate by themselves.
     */
    public static void invalidate() {
        Sides.invalidate();
    }
}
