package ua.zentix.incoming.compat;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.compat.ftbteams.FtbTeamsSides;
import ua.zentix.incoming.compat.opac.OpacSides;
import ua.zentix.incoming.target.Sides;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The documented precedence among side providers (guide "Sides"): the vanilla team before FTB Teams before Open Parties
 * and Claims. NeoForge runs listeners in priority order and the first answer wins, so the order is the priorities' order,
 * each registered with its own constant.
 */
class SidePrecedenceTest {
    @Test
    void vanillaThenFtbTeamsThenOpac() {
        assertTrue(Sides.PRIORITY.ordinal() < FtbTeamsSides.PRIORITY.ordinal(), "FTB Teams answers before the vanilla team");
        assertTrue(FtbTeamsSides.PRIORITY.ordinal() < OpacSides.PRIORITY.ordinal(), "OPAC answers before FTB Teams");
    }

    @Test
    void eachListenerUsesItsConstant() throws Exception {
        String root = "src/main/java/ua/zentix/incoming/";
        assertTrue(Files.readString(Path.of(root + "Incoming.java")).contains("addListener(Sides.PRIORITY, Sides::answerTeam)"));
        assertTrue(Files.readString(Path.of(root + "compat/ftbteams/FtbTeamsSides.java")).contains("addListener(PRIORITY, FtbTeamsSides::onResolve)"));
        assertTrue(Files.readString(Path.of(root + "compat/opac/OpacSides.java")).contains("addListener(PRIORITY, OpacSides::onResolve)"));
    }
}
