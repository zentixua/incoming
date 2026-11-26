package ua.zentix.incoming.grid;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.util.WorldClock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code grid.restore_spread_seconds} runs on the world's day: its tooltip, config comment and blackout guide page say
 * what one unit lasts (72 s of the world's day, 90 = 1 h 48 min) instead of plain seconds, which hold only under a
 * vanilla day.
 */
class RestoreSpreadTest {
    private static final Path ROOT = Path.of("src/main");

    @Test
    void oneUnitIs72SecondsOfTheWorldsDay() {
        assertEquals(72, Blackouts.SPREAD_TICKS_PER_UNIT * WorldClock.SECONDS_PER_TICK, 1e-9);
        // the default 90 units: 6480 s of the world's day = 1 h 48 min
        assertEquals(1 * 3600 + 48 * 60, 90 * Blackouts.SPREAD_TICKS_PER_UNIT * WorldClock.SECONDS_PER_TICK, 1e-9);
    }

    @Test
    void textsCountTheSpreadOnTheWorldsDay() throws Exception {
        String en = tooltip("en_us"), ru = tooltip("ru_ru");
        assertTrue(en.contains("world's day") && en.contains("72 s") && en.contains("1 h 48 min"), en);
        assertTrue(ru.contains("игровых суток") && ru.contains("72 с") && ru.contains("1 ч 48 мин"), ru);
        assertFalse(en.startsWith("Seconds"), en);
        String config = Files.readString(ROOT.resolve("java/ua/zentix/incoming/IncomingConfig.java"), StandardCharsets.UTF_8);
        int at = config.indexOf("gridRestoreSpread = b.comment(");
        String comment = config.substring(at, config.indexOf(".translation(", at));
        assertTrue(comment.contains("world's day") && comment.contains("72 seconds") && comment.contains("1 h 48 min"), comment);
        String guide = Files.readString(Path.of("../docs/guide/blackout.md"), StandardCharsets.UTF_8);
        String guideRu = Files.readString(Path.of("../docs/guide/ru/blackout.md"), StandardCharsets.UTF_8);
        assertTrue(guide.contains("1 h 48 min"), "docs/guide/blackout.md states the spread on the world's day");
        assertTrue(guideRu.contains("1 ч 48 мин"), "docs/guide/ru/blackout.md states the spread on the world's day");
    }

    private static String tooltip(String lang) throws Exception {
        JsonObject o = JsonParser.parseString(Files.readString(ROOT.resolve("resources/assets/incoming/lang/" + lang + ".json"),
                StandardCharsets.UTF_8)).getAsJsonObject();
        return o.get("incoming.config.grid_restore_spread.tooltip").getAsString();
    }
}
