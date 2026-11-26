package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.ClientTicks;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A sound anchor counts the sound's wall-clock playing time in client ticks of the current length. */
class OwnSourcesAnchorTest {
    /**
     * A sound playing for one real second: at 20 ticks a second it started 20 clock ticks ago; at {@code /tick rate 10}
     * (100 ms ticks) 10; in a 2× replay (25 ms) 40. Never before the anchor's own tick.
     */
    @Test
    void playedTimeCountsInTicksOfTheCurrentLength() {
        long second = 1_000_000_000L;
        assertEquals(80, OwnSources.zero(0, 100, second, ClientTicks.NOMINAL_NANOS), 1e-9);
        assertEquals(90, OwnSources.zero(0, 100, second, 100_000_000L), 1e-9);
        assertEquals(60, OwnSources.zero(0, 100, second, 25_000_000L), 1e-9);
        assertEquals(95, OwnSources.zero(95, 100, second, ClientTicks.NOMINAL_NANOS), 1e-9);
    }
}
