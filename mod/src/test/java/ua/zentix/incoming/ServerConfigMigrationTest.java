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
}
