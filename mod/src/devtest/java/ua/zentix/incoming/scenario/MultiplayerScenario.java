package ua.zentix.incoming.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.hud.ClientFlights;

import java.util.Locale;

/**
 * Multiplayer (tools/mp_scenario.sh): dedicated server and two clients. {@code mp-a} (Alpha, operator) hits the player
 * Bravo and the point while the shells are flying - leaves and enters again; {@code mp-b} (Bravo, target) comes out in the middle approaching
 *  and returning. It is checked that the server and clients are experiencing this: the shells on the departed target go to the last
 *  point, the owner, after returning, sees his flights again, there are no errors in the logs.
 *  Time - client tick from the start (also goes to the menu), steps - according to the connection state.
 */
final class MultiplayerScenario {
    private static final String ADDRESS = "127.0.0.1:" + System.getProperty("incoming.mp.port", "25599");

    private final boolean attacker;
    private int tick;
    private int joinedAt = -1;
    private int rejoins;
    private int leftAt = -1;
    private int lastConnect = -1000;
    private boolean fired;
    /** Bravo: tick (from the input) when Alpha appears in the list of players. */
    private int alphaSeen = -1;

    MultiplayerScenario(boolean attacker) {
        this.attacker = attacker;
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    private void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        tick++;
        boolean inGame = mc.player != null && mc.level != null;
        if (!inGame) {
            joinedAt = -1;
            // the server might not be up yet, and after logging out, wait for a pause and log in again
            boolean away = leftAt >= 0 && tick - leftAt < 200;
            if (!away && mc.screen != null && !(mc.screen instanceof ConnectScreen) && tick - lastConnect > 200) connect(mc);
            if (tick > 12_000) finish(mc, "didn't login to the server");
            return;
        }
        if (joinedAt < 0) {
            joinedAt = tick;
            alphaSeen = -1;
            Incoming.LOG.info("SCENARIO mp joined as {} (rejoins {})", mc.player.getGameProfile().getName(), rejoins);
        }
        int t = tick - joinedAt;
        if (t % 20 == 0 && !ClientFlights.all().isEmpty()) logFlights();
        if (attacker) attackerStep(mc, t);
        else targetStep(mc, t);
    }

    /** Alpha: waits for Bravo, hits him and a point nearby, leaves in the middle of the approach, returns and survives to the end. */
    private void attackerStep(Minecraft mc, int t) {
        if (!fired && t >= 100 && mc.getConnection().getPlayerInfo("Bravo") != null) {
            fired = true;
            cmd(mc, "gamemode creative");
            cmd(mc, "incoming salvo drone 3 0 Bravo");
            cmd(mc, "incoming salvo missile 3 0 Bravo");
            cmd(mc, String.format(Locale.ROOT, "incoming salvo rocket 6 10 at %.1f %.1f %.1f", mc.player.getX() + 60, mc.player.getY(), mc.player.getZ()));
            leftAt = -1;
        }
        if (fired && rejoins == 0 && t == 260) leave(mc);
        if (rejoins == 1 && t == 1200) finish(mc, "done");
    }

    /** Bravo: enters, waits for Alpha, leaves while shells are flying towards him (Alpha hits through 5 s after entering), returns. */
    private void targetStep(Minecraft mc, int t) {
        if (alphaSeen < 0 && mc.getConnection().getPlayerInfo("Alpha") != null) alphaSeen = t;
        if (rejoins == 0 && alphaSeen >= 0 && t == alphaSeen + 240) leave(mc);
        if (rejoins == 1 && t == 1000) finish(mc, "done");
    }

    private void connect(Minecraft mc) {
        lastConnect = tick;
        if (leftAt >= 0) {
            rejoins++;
            leftAt = -1;
        }
        Incoming.LOG.info("SCENARIO mp connect {}", ADDRESS);
        ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(ADDRESS),
                new ServerData("incoming-mp", ADDRESS, ServerData.Type.OTHER), false, null);
    }

    private void leave(Minecraft mc) {
        Incoming.LOG.info("SCENARIO mp leave, flights {}", ClientFlights.all().size());
        leftAt = tick;
        mc.level.disconnect();
        mc.disconnect(new TitleScreen());
    }

    private void finish(Minecraft mc, String why) {
        Incoming.LOG.info("SCENARIO mp {}", why);
        Incoming.LOG.info("SCENARIO done");
        mc.stop();
    }

    private static void cmd(Minecraft mc, String c) {
        mc.player.connection.sendCommand(c);
        Incoming.LOG.info("SCENARIO /{}", c);
    }

    private static void logFlights() {
        StringBuilder sb = new StringBuilder();
        for (var f : ClientFlights.all()) {
            sb.append(' ').append(f.weapon().name()).append('#').append(f.number).append(' ').append(f.phase().name())
                    .append(f.targetLost() ? " lost" : "").append(" →").append(f.targetLabel() == null ? "point" : f.targetLabel().getString());
        }
        Incoming.LOG.info("SCENARIO mp flights{}", sb);
    }
}
