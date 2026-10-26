package ua.zentix.incoming.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Their impact effects are turned off ({@code blast_effects} = false, #452) - not a single impact package shows or sounds them:
 *  Each impact package handler in {@code IncomingClient.Hooks} has the same filter {@code ownBlasts()} (review FV: the new
 *  fuel package in the crater bypassed it). The new shock pack is on this list.
 */
class StrikeEffectsFilterTest {
    private static final String[] STRIKE_HANDLERS = {"blast", "bunkerImpact", "vent", "fuelFire", "collapse", "quake"};

    @Test
    void everyStrikeHandlerAsksOwnBlasts() throws IOException {
        String src = Files.readString(Path.of("src/main/java/ua/zentix/incoming/client/IncomingClient.java"));
        for (String handler : STRIKE_HANDLERS) {
            Matcher m = Pattern.compile("public void " + handler + "\\(S2C\\.\\w+ p\\) \\{(.*?)\\n        }", Pattern.DOTALL).matcher(src);
            assertTrue(m.find(), "no handler " + handler);
            assertTrue(m.group(1).contains("ownBlasts()"), handler + " shows impact also at blast_effects = false");
        }
    }
}
