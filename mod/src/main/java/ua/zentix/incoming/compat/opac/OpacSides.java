package ua.zentix.incoming.compat.opac;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.api.SideResolveEvent;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.parties.party.api.IPartyManagerAPI;
import xaero.pac.common.server.parties.party.api.IServerPartyAPI;

/**
 * Open Parties and Claims parties as a side ({@link SideResolveEvent}, priority {@code LOW}: after the vanilla team and
 * FTB Teams): a party answers {@code openpartiesandclaims:<party id>}, no party abstains (OPAC has no personal party; a
 * party of one is a party its owner made). Allied parties are not one side. Membership comes from
 * {@link IPartyManagerAPI#getPartyByMember}, OPAC's in-memory member map, also for offline owners. OPAC's API publishes
 * no party-change event, so a change reaches Incoming through the cache's age bound ({@code Sides.RECHECK}, one second).
 * Installed from {@code Incoming} only when OPAC is loaded: no other class names its API.
 */
public final class OpacSides {
    static final String NAMESPACE = "openpartiesandclaims";

    private OpacSides() {}

    public static void install() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, OpacSides::onResolve);
    }

    static void onResolve(SideResolveEvent event) {
        if (event.isAnswered()) return;
        answer(event, manager(ServerLifecycleHooks.getCurrentServer()));
    }

    static void answer(SideResolveEvent event, @Nullable IPartyManagerAPI parties) {
        String key = parties == null ? null : key(parties.getPartyByMember(event.getPlayerId()));
        if (key != null) event.answer(key);
    }

    @Nullable
    static String key(@Nullable IServerPartyAPI party) {
        return party == null ? null : NAMESPACE + ":" + party.getId();
    }

    /** OPAC's party manager; null before OPAC's server data exists (its API dereferences that data without a check). */
    @Nullable
    private static IPartyManagerAPI manager(@Nullable MinecraftServer server) {
        if (server == null) return null;
        try {
            return OpenPACServerAPI.get(server).getPartyManager();
        } catch (NullPointerException beforeServerData) {
            return null;
        }
    }
}
