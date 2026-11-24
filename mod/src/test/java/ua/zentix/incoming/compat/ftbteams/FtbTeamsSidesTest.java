package ua.zentix.incoming.compat.ftbteams;

import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamManager;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.api.SideResolveEvent;
import ua.zentix.incoming.target.Sides;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The FTB Teams adapter against FTB Teams' API with stand-in teams (FTB Teams is not in the GameTest runtime): a party
 * answers its id, the personal team every player has, a server team (it takes no members) and no team abstain, an earlier answer stands, and
 * every membership event invalidates the cached sides.
 */
class FtbTeamsSidesTest {
    private static final UUID PARTY = UUID.randomUUID(), SERVER = UUID.randomUUID(), PERSONAL = UUID.randomUUID();

    @Test
    void partyAnswers() {
        assertEquals("ftbteams:" + PARTY, FtbTeamsSides.key(team(PARTY, "party")));
        assertNull(FtbTeamsSides.key(team(SERVER, "server")), "a server team, which no player's effective team can be");
    }

    @Test
    void personalOrAbsentAbstains() {
        assertNull(FtbTeamsSides.key(team(PERSONAL, "player")), "the personal team every player has is a side");
        assertNull(FtbTeamsSides.key(null));
        UUID alone = UUID.randomUUID(), member = UUID.randomUUID(), stranger = UUID.randomUUID();
        TeamManager manager = manager(Map.of(alone, team(PERSONAL, "player"), member, team(PARTY, "party")));
        SideResolveEvent e = new SideResolveEvent(alone, "alone");
        FtbTeamsSides.answer(e, manager);
        assertFalse(e.isAnswered(), "a personal team answered");
        SideResolveEvent unknown = new SideResolveEvent(stranger, null);
        FtbTeamsSides.answer(unknown, manager);
        assertFalse(unknown.isAnswered(), "an unknown player answered");
        SideResolveEvent m = new SideResolveEvent(member, "member");
        FtbTeamsSides.answer(m, manager);
        assertEquals("ftbteams:" + PARTY, m.getSide());
    }

    @Test
    void earlierAnswerStands() {
        UUID member = UUID.randomUUID();
        SideResolveEvent e = new SideResolveEvent(member, "member");
        e.answer("minecraft:team/red");
        FtbTeamsSides.answer(e, manager(Map.of(member, team(PARTY, "party"))));
        assertEquals("minecraft:team/red", e.getSide(), "a lower-priority provider replaced the vanilla team");
    }

    @Test
    void membershipEventsInvalidate() {
        FtbTeamsSides.invalidateOnChange();
        long before = Sides.epoch();
        TeamEvent.PLAYER_CHANGED.invoker().accept(null);
        TeamEvent.PLAYER_JOINED_PARTY.invoker().accept(null);
        TeamEvent.PLAYER_LEFT_PARTY.invoker().accept(null);
        TeamEvent.CREATED.invoker().accept(null);
        TeamEvent.DELETED.invoker().accept(null);
        TeamEvent.LOADED.invoker().accept(null);
        assertTrue(Sides.epoch() - before >= 6, "membership events invalidated " + (Sides.epoch() - before) + " times of 6");
    }

    /** A stand-in team: {@code kind} player, party or server. */
    private static Team team(UUID id, String kind) {
        return (Team) Proxy.newProxyInstance(Team.class.getClassLoader(), new Class<?>[] {Team.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getId", "getTeamId" -> id;
            case "isPlayerTeam" -> kind.equals("player");
            case "isPartyTeam" -> kind.equals("party");
            case "isServerTeam" -> kind.equals("server");
            case "isClientTeam" -> false;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> kind + " " + id;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static TeamManager manager(Map<UUID, Team> teams) {
        return (TeamManager) Proxy.newProxyInstance(TeamManager.class.getClassLoader(), new Class<?>[] {TeamManager.class}, (proxy, method, args) -> {
            if (method.getName().equals("getTeamForPlayerID")) return Optional.ofNullable(teams.get((UUID) args[0]));
            throw new UnsupportedOperationException(method.getName());
        });
    }
}
