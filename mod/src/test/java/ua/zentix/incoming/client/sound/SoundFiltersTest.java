package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Direct filter at the start of sound: our path is with our sounds and with Sound Physics Remastered, his path is with strangers. */
class SoundFiltersTest {
    /** Filter Sound Physics: an obstacle at the ear (our sound is 3 blocks away from it), −4 dB and deaf. */
    private static final float[] SPR = {0.63f, 0.2f};

    @Test
    void ownSoundKeepsOurPathAndReplacesSoundPhysics() {
        // long-range strike: the top was eaten by air - our filter, without multiplying by the filter Sound Physics
        assertArrayEquals(new float[]{1f, 0.3f}, SoundFilters.direct(true, 1f, 0.3f, true, SPR), 1e-6f);
        // open close: our filter 1 is still installed - remove its filter, otherwise the obstacle would be counted twice
        assertArrayEquals(new float[]{1f, 1f}, SoundFilters.direct(true, 1f, 1f, true, SPR), 1e-6f);
        // Sound Physics is installed, but its filter cannot be read - ours is installed anyway
        assertArrayEquals(new float[]{1f, 1f}, SoundFilters.direct(true, 1f, 1f, true, null), 1e-6f);
        // without Sound Physics open sound does not need a filter
        assertNull(SoundFilters.direct(true, 1f, 1f, false, null));
        assertArrayEquals(new float[]{0.8f, 0.5f}, SoundFilters.direct(true, 0.8f, 0.5f, false, null), 1e-6f);
    }

    @Test
    void foreignSoundKeepsSoundPhysicsPathUnderOurDeafness() {
        // stun over his barriers
        assertArrayEquals(new float[]{0.5f * 0.63f, 0.1f * 0.2f}, SoundFilters.direct(false, 0.5f, 0.1f, true, SPR), 1e-6f);
        // without stunning, we don’t touch its filter
        assertNull(SoundFilters.direct(false, 1f, 1f, true, SPR));
        assertArrayEquals(new float[]{0.5f, 0.1f}, SoundFilters.direct(false, 0.5f, 0.1f, false, null), 1e-6f);
    }
}
