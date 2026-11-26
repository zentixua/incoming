package ua.zentix.incoming.compat.opac;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.api.SideResolveEvent;
import xaero.pac.common.server.parties.party.api.IPartyManagerAPI;
import xaero.pac.common.server.parties.party.api.IServerPartyAPI;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The Open Parties and Claims adapter against OPAC's API with stand-in parties (OPAC is not in the GameTest runtime): a
 * party member answers the party id, a player without a party and a missing manager abstain, an earlier answer stands.
 */
class OpacSidesTest {
    private static final UUID PARTY = UUID.randomUUID();

    @Test
    void memberAnswersParty() {
        UUID member = UUID.randomUUID();
        SideResolveEvent e = new SideResolveEvent(member, null);
        OpacSides.answer(e, manager(Map.of(member, party(PARTY))));
        assertEquals("openpartiesandclaims:" + PARTY, e.getSide());
    }

    @Test
    void noPartyAbstains() {
        SideResolveEvent e = new SideResolveEvent(UUID.randomUUID(), "alone");
        OpacSides.answer(e, manager(Map.of()));
        assertFalse(e.isAnswered(), "a player without a party answered");
        SideResolveEvent early = new SideResolveEvent(UUID.randomUUID(), "early");
        OpacSides.answer(early, null);
        assertFalse(early.isAnswered(), "answered before OPAC's server data exists");
        assertNull(OpacSides.key(null));
    }

    @Test
    void earlierAnswerStands() {
        UUID member = UUID.randomUUID();
        SideResolveEvent e = new SideResolveEvent(member, null);
        e.answer("ftbteams:" + UUID.randomUUID());
        String first = e.getSide();
        OpacSides.answer(e, manager(Map.of(member, party(PARTY))));
        assertEquals(first, e.getSide(), "OPAC replaced a higher-priority answer");
    }

    private static IServerPartyAPI party(UUID id) {
        return (IServerPartyAPI) Proxy.newProxyInstance(IServerPartyAPI.class.getClassLoader(), new Class<?>[] {IServerPartyAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "party " + id;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static IPartyManagerAPI manager(Map<UUID, IServerPartyAPI> parties) {
        return (IPartyManagerAPI) Proxy.newProxyInstance(IPartyManagerAPI.class.getClassLoader(), new Class<?>[] {IPartyManagerAPI.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getPartyByMember")) return parties.get((UUID) args[0]);
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
