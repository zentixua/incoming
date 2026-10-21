package ua.zentix.incoming.client.sound;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.entity.FlightPhase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Мотор зенитной ракеты: закон источника по паспорту и запаздывание звука по её пути ({@link InterceptorSound}). */
class InterceptorSoundTest {
    /** Разгон громче маршевого режима на 10·lg отношения тяг (истечение одно): мощность струи ∝ тяге. */
    @Test
    void boostIsLouderBySevenDecibels() {
        double boost = InterceptorSound.BOOST.power(FlightPhase.BOOST, 20), sustain = InterceptorSound.SUSTAIN.power(FlightPhase.BOOST, 20);
        assertEquals(10 * Math.log10(InterceptorSound.BOOST.thrust() / InterceptorSound.SUSTAIN.thrust()), boost - sustain, 1e-9);
    }

    /**
     * Ракета летит на 14 блоках/тик (почти скорость звука) мимо слушателя: слышно то, что она издала в момент te, когда
     * звук оттуда как раз дошёл, — |p(te) − ухо| = c·(сейчас − te).
     */
    @Test
    void heardFromRetardedPoint() {
        InterceptorSound.Track track = new InterceptorSound.Track();
        for (int t = 0; t <= 40; t++) track.add(t, new Vec3(14 * t, 100, 0), t);
        Vec3 ear = new Vec3(400, 64, 50);
        double now = 40, te = Acoustics.emissionTime(track, now, ear.x, ear.y, ear.z);
        assertTrue(te > 0 && te < now, "момент излучения " + te);
        double[] p = new double[3];
        track.at(te, p);
        assertEquals(Acoustics.SPEED * (now - te), new Vec3(p[0], p[1], p[2]).distanceTo(ear), 1e-3);
        assertEquals(14, track.velocity(te).length(), 1e-9);
        assertEquals(te, track.age(te), 1e-9);
    }
}
