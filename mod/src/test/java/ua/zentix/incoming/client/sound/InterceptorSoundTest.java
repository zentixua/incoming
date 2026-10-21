package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.entity.FlightPhase;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Мотор зенитной ракеты: закон источника по паспорту ({@link InterceptorSound}). */
class InterceptorSoundTest {
    /** Разгон громче маршевого режима на 10·lg отношения тяг (истечение одно): мощность струи ∝ тяге. */
    @Test
    void boostIsLouderByThrustRatio() {
        double boost = InterceptorSound.BOOST.power(FlightPhase.BOOST, 20), sustain = InterceptorSound.SUSTAIN.power(FlightPhase.BOOST, 20);
        assertEquals(10 * Math.log10(InterceptorSound.BOOST.thrust() / InterceptorSound.SUSTAIN.thrust()), boost - sustain, 1e-9);
    }
}
