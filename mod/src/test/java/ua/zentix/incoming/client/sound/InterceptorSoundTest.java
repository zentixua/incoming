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

/** Мотор зенитной ракеты: закон источника по паспорту ({@link InterceptorSound}) — слоем общего хозяйства моторов. */
class InterceptorSoundTest {
    /** Разгон громче маршевого режима на 10·lg отношения тяг (истечение одно): мощность струи ∝ тяге. */
    @Test
    void boostIsLouderByThrustRatio() {
        double boost = InterceptorSound.BOOST.power(FlightPhase.BOOST, 20), sustain = InterceptorSound.SUSTAIN.power(FlightPhase.BOOST, 20);
        assertEquals(10 * Math.log10(InterceptorSound.BOOST.thrust() / InterceptorSound.SUSTAIN.thrust()), boost - sustain, 1e-9);
    }

    /**
     * Путь зенитной ракеты, которой у клиента нет (только пакеты сервера, далеко от игрока), звучит тем же слоем, что и
     * по сущности: звук заводит путь ПВО, а не тик сущности, — раньше дальняя ракета летела с факелом и следом молча.
     */
    @Test
    void farSamTrackFromServerPacketsIsHeard() {
        UUID id = UUID.randomUUID();
        FlightTrack t = FlightTrack.interceptor(id);
        // маршевый участок мимо уха в 2 км сбоку, 10 блоков/тик, высота 300 м: пакеты раз в 4 тика
        for (int k = 0; k <= 400; k += 4) {
            t.record(k, new S2C.FarFlight(id, S2C.FarFlight.INTERCEPTOR, false, false, false, new Vec3(-2000 + 10 * k, 300, 2000),
                    new Vec3(10, 0, 0), 90, 0, 0, FlightPhase.CRUISE.ordinal(), 40 + k, new Vec3(0, 300, 2000), Float.NaN, false));
        }
        assertEquals(List.of(EngineSound.Layer.SAM), ClientSounds.layers(t), "у пути ПВО — слой мотора ЗРК");
        Vec3 ear = new Vec3(0, 1.7, 0);
        double te = Acoustics.emissionTime(t, 400, ear.x, ear.y, ear.z);
        assertTrue(t.covers(te), "звук уже дошёл");
        Emission e = Emission.at(t, te, ear, false, Propagation.Weather.NIGHT);
        EngineSound.Tone tone = EngineSound.Layer.SAM.tone(t, e);
        assertNotNull(tone);
        assertTrue(tone.gain() >= VoiceBudget.AUDIBLE, "маршевый мотор в 2 км ночью слышно: " + tone.gain());
    }

    /**
     * На шкале полёта к настоящей скорости 9М96 ракета быстрее звука: N-волна — тем же законом, что у снарядов
     * ({@link Booms}), корпус — по паспорту ЗРК (оружия у пути нет).
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
        assertTrue(first > 0, "конус Маха дошёл до уха");
        Acoustics.emissions(t, first, ear.x, ear.y, ear.z, roots);
        assertNotNull(Booms.crack(t, roots[0], ear, Propagation.Weather.NIGHT), "сверхзвуковая ЗРК даёт N-волну");
    }
}
