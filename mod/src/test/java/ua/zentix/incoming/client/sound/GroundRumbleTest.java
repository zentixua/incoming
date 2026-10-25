package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Гул земли ({@link BlastSounds#quake(double)}): давление — то, что излучает земля, ходящая со скоростью грунта. */
class GroundRumbleTest {
    /**
     * p = ρ₀c₀·v: грунт в 1 мм/с — ~0,41 Па (~86 дБ), 10 мм/с — ~4 Па; без движения грунта гула нет, громче — с
     * быстрее идущим грунтом, без потолка в законе.
     */
    @Test
    void rumbleIsTheAirImpedanceTimesGroundVelocity() {
        assertEquals(0.413, BlastSounds.rumblePa(1e-3), 0.005);
        assertEquals(4.13, BlastSounds.rumblePa(1e-2), 0.05);
        assertEquals(0, BlastSounds.rumblePa(0));
        assertEquals(0, BlastSounds.rumblePa(-1));
        assertTrue(BlastSounds.byPressure(BlastSounds.rumblePa(0.05), BlastSounds.QUAKE_LUFS)
                > BlastSounds.byPressure(BlastSounds.rumblePa(0.005), BlastSounds.QUAKE_LUFS));
    }
}
