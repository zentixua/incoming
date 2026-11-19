package ua.zentix.incoming;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Run the loader's real spec-correction path before acceptance, including TOML round trips. */
class ServerConfigMigrationTest {
    private final ServerConfigMigration migration = new ServerConfigMigration(IncomingConfig.SERVER_SPEC);

    @Test
    void oldFalseSurvivesCorrectionAndSaveReload() {
        CommentedConfig config = new TomlParser().parse("[world]\nsiren = false\n");
        assertFalse(migration.isCorrect(config));
        migration.correct(config);
        assertFalse(config.<Boolean>get("world.early_warning"));
        assertFalse(config.contains("world.siren"));
        assertTrue(migration.isCorrect(config));
        CommentedConfig reloaded = new TomlParser().parse(new TomlWriter().writeToString(config));
        migration.correct(reloaded);
        assertFalse(reloaded.<Boolean>get("world.early_warning"));
        assertTrue(migration.isCorrect(reloaded));
    }

    @Test
    void oldTrueAndFreshDefaultsKeepNetwork() {
        for (String text : new String[]{"", "[world]\nsiren = true\n"}) {
            CommentedConfig config = new TomlParser().parse(text);
            migration.correct(config);
            assertTrue(config.<Boolean>get("world.early_warning"));
            assertFalse(config.contains("world.siren"));
        }
    }

    @Test
    void explicitNewSettingWinsBothWays() {
        for (boolean enabled : new boolean[]{true, false}) {
            CommentedConfig config = new TomlParser().parse("[world]\nsiren = " + !enabled + "\nearly_warning = " + enabled + "\n");
            migration.correct(config);
            assertEquals(enabled, config.<Boolean>get("world.early_warning"));
            assertFalse(config.contains("world.siren"));
        }
    }

    /** Power restore was real minutes; it is now hours of the world's day: the old value keeps its meaning under a vanilla day. */
    @Test
    void restoreMinutesBecomeGameHours() {
        CommentedConfig config = new TomlParser().parse("[grid]\nrestore_minutes = 15.0\n");
        assertFalse(migration.isCorrect(config));
        migration.correct(config);
        assertEquals(18.0, config.<Number>get("grid.restore_hours").doubleValue(), 1e-9);
        assertFalse(config.contains("grid.restore_minutes"));
        assertTrue(migration.isCorrect(config));
        CommentedConfig fresh = new TomlParser().parse("");
        migration.correct(fresh);
        assertEquals(18.0, fresh.<Number>get("grid.restore_hours").doubleValue(), 1e-9, "default: a day's repairs");
        CommentedConfig both = new TomlParser().parse("[grid]\nrestore_minutes = 5.0\nrestore_hours = 30.0\n");
        migration.correct(both);
        assertEquals(30.0, both.<Number>get("grid.restore_hours").doubleValue(), 1e-9, "explicit new setting wins");
    }

    /**
     * The GAME wave default rose from 0.25 to 0.35: a file an older jar wrote with the old default takes the new one
     * once; a value set by hand stays, and so does 0.25 set after this jar rewrote the key's comment.
     */
    @Test
    void oldWaveDefaultMovesOnceHandValuesStay() {
        String old = "[nuclear]\n\t#Works with scale.scale_preset = CUSTOM. Speed of the blast wave (0.25 is the GAME preset).\n\twave_speed = 0.25\n";
        CommentedConfig config = new TomlParser().parse(old);
        assertFalse(migration.isCorrect(config));
        migration.correct(config);
        assertEquals(IncomingConfig.GAME_WAVE_SPEED, config.<Number>get("nuclear.wave_speed").doubleValue(), 1e-12);
        assertEquals(0.35, IncomingConfig.GAME_WAVE_SPEED, 1e-12, "Artem's GAME wave, 19.11");
        assertEquals(0.35, IncomingConfig.ScalePreset.GAME.wave(0.25), 1e-12);

        CommentedConfig fresh = new TomlParser().parse("");
        migration.correct(fresh);
        assertEquals(0.35, fresh.<Number>get("nuclear.wave_speed").doubleValue(), 1e-12, "new default");

        CommentedConfig hand = new TomlParser().parse(old.replace("0.25\n", "0.2\n"));
        migration.correct(hand);
        assertEquals(0.2, hand.<Number>get("nuclear.wave_speed").doubleValue(), 1e-12, "a hand value stays");

        // the player sets 0.25 under this jar: the saved file carries this jar's comment, and the value survives reloads
        config.set("nuclear.wave_speed", 0.25);
        CommentedConfig reloaded = new TomlParser().parse(new TomlWriter().writeToString(config));
        assertTrue(migration.isCorrect(reloaded));
        migration.correct(reloaded);
        assertEquals(0.25, reloaded.<Number>get("nuclear.wave_speed").doubleValue(), 1e-12);
    }
}
