package ua.zentix.incoming.compat.ftbteams;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamManager;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.api.SideResolveEvent;
import ua.zentix.incoming.target.Sides;

/**
 * FTB Teams as a side ({@link SideResolveEvent}, priority {@code NORMAL}: after the vanilla team, before Open Parties and
 * Claims): a party answers {@code ftbteams:<team id>}; the personal team FTB Teams gives every player, no team, or a
 * manager not loaded yet abstain (a player's effective team is their party or their personal team: server teams take no
 * members). Allies are not one side (one key per player cannot hold a relation between
 * teams). Membership comes from {@link TeamManager#getTeamForPlayerID}, FTB Teams' in-memory map of known players, also for
 * offline owners. Every membership event invalidates the cached sides. Installed from {@code Incoming} only when FTB Teams
 * is loaded: no other class names its API (or Architectury's, whose events it uses).
 */
public final class FtbTeamsSides {
    static final String NAMESPACE = "ftbteams";
    /** After the vanilla team ({@link Sides#PRIORITY}), before Open Parties and Claims. */
    public static final EventPriority PRIORITY = EventPriority.NORMAL;

    private FtbTeamsSides() {}

    public static void install() {
        NeoForge.EVENT_BUS.addListener(PRIORITY, FtbTeamsSides::onResolve);
        invalidateOnChange();
    }

    /** Every membership change of FTB Teams invalidates the cached sides. */
    static void invalidateOnChange() {
        TeamEvent.PLAYER_CHANGED.register(e -> Sides.invalidate());
        TeamEvent.PLAYER_JOINED_PARTY.register(e -> Sides.invalidate());
        TeamEvent.PLAYER_LEFT_PARTY.register(e -> Sides.invalidate());
        TeamEvent.CREATED.register(e -> Sides.invalidate());
        TeamEvent.DELETED.register(e -> Sides.invalidate());
        TeamEvent.LOADED.register(e -> Sides.invalidate());
    }

    static void onResolve(SideResolveEvent event) {
        if (event.isAnswered() || !FTBTeamsAPI.api().isManagerLoaded()) return;
        answer(event, FTBTeamsAPI.api().getManager());
    }

    static void answer(SideResolveEvent event, TeamManager manager) {
        String key = key(manager.getTeamForPlayerID(event.getPlayerId()).orElse(null));
        if (key != null) event.answer(key);
    }

    /** The side key of the player's effective team: parties only. */
    @Nullable
    static String key(@Nullable Team team) {
        if (team == null || !team.isPartyTeam()) return null;
        return NAMESPACE + ":" + team.getId();
    }
}
