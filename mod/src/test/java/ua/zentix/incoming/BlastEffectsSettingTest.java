package ua.zentix.incoming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Its explosion effects ({@code blast_effects}) are enabled by default: without another mod that shows explosions, the player
 *  sees and hears the impact as before; Only he himself turns them off.
 */
class BlastEffectsSettingTest {
    @Test
    void onByDefault() {
        assertTrue(IncomingConfig.CLIENT.blastEffects.getDefault());
    }
}
