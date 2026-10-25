package ua.zentix.incoming.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Свои эффекты ударов выключены ({@code blast_effects} = false, #452) — ни один пакет удара их не показывает и не озвучивает:
 * у каждого обработчика пакета удара в {@code IncomingClient.Hooks} — тот же фильтр {@code ownBlasts()} (ревью FV: новый
 * пакет топлива в воронке его обошёл). Новый пакет удара — в этот список.
 */
class StrikeEffectsFilterTest {
    private static final String[] STRIKE_HANDLERS = {"blast", "bunkerImpact", "vent", "fuelFire", "collapse", "quake"};

    @Test
    void everyStrikeHandlerAsksOwnBlasts() throws IOException {
        String src = Files.readString(Path.of("src/main/java/ua/zentix/incoming/client/IncomingClient.java"));
        for (String handler : STRIKE_HANDLERS) {
            Matcher m = Pattern.compile("public void " + handler + "\\(S2C\\.\\w+ p\\) \\{(.*?)\\n        }", Pattern.DOTALL).matcher(src);
            assertTrue(m.find(), "нет обработчика " + handler);
            assertTrue(m.group(1).contains("ownBlasts()"), handler + " показывает удар и при blast_effects = false");
        }
    }
}
