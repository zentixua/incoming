package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ground hum ({@link BlastSounds#quake(double)}): pressure is what is emitted by the ground moving at the speed of the ground. */
class GroundRumbleTest {
    /**
     * p = ρ₀c₀·v: soil in 1 mm/s - ~0,41 Pa (~86 dB), 10 mm/s - ~4 Pa; without ground movement there is no hum, louder - with
     *  faster moving ground, without a ceiling in law.
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
