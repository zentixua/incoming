package ua.zentix.incoming;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NuclearYieldConfigTest {
    @Test
    void standardConfigCorrectionDefaultsAndPreservesExistingWorldCaps() {
        var pair = new ModConfigSpec.Builder().configure(IncomingConfig.Server::new);
        var spec = pair.getRight();
        var config = CommentedConfig.inMemory();
        spec.correct(config);
        assertEquals(100, config.<Integer>get("nuclear.max_yield"));
        assertEquals(15, config.<Integer>get("nuclear.default_yield"));
        for (int cap : new int[]{1, 15, 100, 1000}) {
            config.set("nuclear.max_yield", cap);
            spec.correct(config);
            assertTrue(spec.isCorrect(config));
            assertEquals(cap, config.<Integer>get("nuclear.max_yield"));
            assertEquals(15, config.<Integer>get("nuclear.default_yield"));
        }
        for (int invalid : new int[]{-1, 0, 1001}) {
            config.set("nuclear.max_yield", invalid);
            spec.correct(config);
            assertTrue(spec.isCorrect(config));
            assertEquals(Math.clamp(invalid, 1, 1000), config.<Integer>get("nuclear.max_yield"));
        }
    }
}
