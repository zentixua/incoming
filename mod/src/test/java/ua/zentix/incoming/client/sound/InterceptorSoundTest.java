package ua.zentix.incoming.client.sound;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.flight.FlightTrack;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.sound.Propagation;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Anti-aircraft missile motor: source law according to spec ({@link InterceptorSound}) - a layer of general motor management. */
class InterceptorSoundTest {
    /** Acceleration is louder than the marching mode at 10·lg thrust ratio (one expiration): jet power ∝ thrust. */
    @Test
    void boostIsLouderByThrustRatio() {
        double boost = InterceptorSound.BOOST.power(FlightPhase.BOOST, 20), sustain = InterceptorSound.SUSTAIN.power(FlightPhase.BOOST, 20);
        assertEquals(10 * Math.log10(InterceptorSound.BOOST.thrust() / InterceptorSound.SUSTAIN.thrust()), boost - sustain, 1e-9);
    }

    /**
     * The path of an anti-aircraft missile, which the client does not have (only server packets, far from the player), sounds the same layer as
     *  in entity: the sound starts the air defense path, and not the tick of the entity - before, a long-range missile flew with a torch and a trail in silence.
     */
    @Test
    void farSamTrackFromServerPacketsIsHeard() {
        UUID id = UUID.randomUUID();
        FlightTrack t = FlightTrack.interceptor(id);
        // marching section past the ear in 2 km from the side, 10 blocks/tick, height 300 m: packets once every 4 tick
        for (int k = 0; k <= 400; k += 4) {
            t.record(k, new S2C.FarFlight(id, S2C.FarFlight.INTERCEPTOR, false, false, false, new Vec3(-2000 + 10 * k, 300, 2000),
                    new Vec3(10, 0, 0), 90, 0, 0, FlightPhase.CRUISE.ordinal(), 40 + k, new Vec3(0, 300, 2000), Float.NaN, false));
        }
        assertEquals(List.of(EngineSound.Layer.SAM), ClientSounds.layers(t), "near the air defense path - SAM motor layer");
        Vec3 ear = new Vec3(0, 1.7, 0);
        double te = Acoustics.emissionTime(t, 400, ear.x, ear.y, ear.z);
        assertTrue(t.covers(te), "the sound has already arrived");
        Emission e = Emission.at(t, te, ear, false, Propagation.Weather.NIGHT);
        EngineSound.Tone tone = EngineSound.Layer.SAM.tone(t, e);
        assertNotNull(tone);
        assertTrue(tone.gain() >= VoiceBudget.AUDIBLE, "the main engine can be heard at 2 km at night: " + tone.gain());
    }

    /**
     * At the flight scale reaching real 9M96 speed, the missile is supersonic: N-wave follows the same law as projectiles
     * ({@link Booms}); body is from the SAM spec (the path has no weapon).
     */
    @Test
    void supersonicSamCracksByTheSameLaw() {
        UUID id = UUID.randomUUID();
        FlightTrack t = FlightTrack.interceptor(id);
        double v = 2 * Acoustics.SPEED;
        for (int k = 0; k <= 200; k += 4) {
            t.record(k, new S2C.FarFlight(id, S2C.FarFlight.INTERCEPTOR, false, false, false, new Vec3(-2000 + v * k, 300, 300),
                    new Vec3(v, 0, 0), 90, 0, 0, FlightPhase.CRUISE.ordinal(), 40 + k, new Vec3(0, 300, 300), Float.NaN, false));
        }
        Vec3 ear = new Vec3(0, 1.7, 0);
        double[] roots = new double[1];
        int first = -1;
        for (int now = 0; now <= 200 && first < 0; now++) if (Acoustics.emissions(t, now, ear.x, ear.y, ear.z, roots) > 0) first = now;
        assertTrue(first > 0, "the Mach cone reached the ear");
        Acoustics.emissions(t, first, ear.x, ear.y, ear.z, roots);
        assertNotNull(Booms.crack(t, roots[0], ear, Propagation.Weather.NIGHT), "supersonic SAM gives N wave");
    }
}
