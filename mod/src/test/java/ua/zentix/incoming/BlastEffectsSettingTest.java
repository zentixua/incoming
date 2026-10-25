package ua.zentix.incoming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Свои эффекты взрыва ({@code blast_effects}) по умолчанию включены: без другого мода, который показывает взрывы, игрок
 * видит и слышит удар как прежде; выключает их только он сам.
 */
class BlastEffectsSettingTest {
    @Test
    void onByDefault() {
        assertTrue(IncomingConfig.CLIENT.blastEffects.getDefault());
    }
}
