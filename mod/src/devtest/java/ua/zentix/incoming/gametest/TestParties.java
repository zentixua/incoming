package ua.zentix.incoming.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.incoming.api.SideResolveEvent;
import ua.zentix.incoming.target.Sides;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A party provider for tests, answering {@link SideResolveEvent} the way the FTB Teams and OPAC bridges do (priority
 * {@code NORMAL}, a namespaced key, abstaining for players without a party): neither mod is in the GameTest runtime. A
 * change with {@code signal} invalidates the cache as an FTB Teams event does; without it, it reaches the cache only
 * through the age bound, as an OPAC party change does. Members are removed after the test.
 */
final class TestParties {
    static final String NAMESPACE = "testparty";
    private static final Map<UUID, String> MEMBERS = new HashMap<>();
    private static boolean installed;

    private TestParties() {}

    static void join(GameTestHelper h, UUID player, String party, boolean signal) {
        install();
        MEMBERS.put(player, party);
        StrikeGameTests.afterTest(h, () -> {
            MEMBERS.remove(player);
            Sides.invalidate();
        });
        if (signal) SideResolveEvent.invalidate();
    }

    static void leave(UUID player, boolean signal) {
        MEMBERS.remove(player);
        if (signal) SideResolveEvent.invalidate();
    }

    static String key(String party) {
        return NAMESPACE + ":" + party;
    }

    private static void install() {
        if (installed) return;
        installed = true;
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, (SideResolveEvent e) -> {
            String party = MEMBERS.get(e.getPlayerId());
            if (party != null) e.answer(key(party));
        });
    }
}
