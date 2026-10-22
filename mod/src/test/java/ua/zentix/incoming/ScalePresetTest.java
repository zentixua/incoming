package ua.zentix.incoming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Пресет масштаба: только ядерная волна и радиусы ядерки; у GAME волна игровая, у REAL всё как в жизни. */
class ScalePresetTest {
    @Test
    void presetsSetWaveAndRadii() {
        assertEquals(0.20, IncomingConfig.ScalePreset.GAME.wave(0.7), 1e-12);
        assertEquals(1, IncomingConfig.ScalePreset.GAME.radii(0.3), 1e-12);
        assertEquals(1, IncomingConfig.ScalePreset.REAL.wave(0.7), 1e-12);
        assertEquals(1, IncomingConfig.ScalePreset.REAL.radii(0.3), 1e-12);
        assertEquals(0.7, IncomingConfig.ScalePreset.CUSTOM.wave(0.7), 1e-12);
        assertEquals(0.3, IncomingConfig.ScalePreset.CUSTOM.radii(0.3), 1e-12);
    }
}
