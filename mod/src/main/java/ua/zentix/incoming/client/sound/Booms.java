package ua.zentix.incoming.client.sound;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.flight.FlightTrack;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.registry.ModSounds;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.sound.SonicBoom;
import ua.zentix.incoming.strike.Hearing;

/**
 * Хлопок сверхзвукового снаряда: до уха дошёл его конус Маха. Снаружи конуса звука нет вовсе (у запаздывания нет
 * корней, {@link Acoustics#emissions}); в миг, когда конус проходит слушателя, приходит N-волна — скачок давления
 * и такой же обратный через {@code Δt} (у снаряда РСЗО — миллисекунды, сухой треск; у большого корпуса — десятки
 * миллисекунд, двойной удар), — и только потом звук самого снаряда, изданный, когда он уже был ближе.
 */
final class Booms {
    /** Длительность N-волны в записях {@code sonic.crack} и {@code sonic.boom}, с ({@code tools/build_sounds.py}). */
    static final double CRACK = 0.004, BOOM = 0.032;
    /** Громкость 1 — с этого давления (1 psi), тише — на половину децибел давления, как у взрывов ({@code Outdoor}). */
    static final double FULL_PA = 1000 * BlastModel.kpa(1);

    private Booms() {}

    /**
     * Звук снаряда впервые дошёл до уха (момент излучения {@code te}): если тогда снаряд шёл быстрее звука, это пришёл
     * конус Маха — играть N-волну. Начало известного пути не в счёт: звук с первой точки истории — не конус, а край
     * того, что о снаряде знает клиент.
     */
    static void reached(FlightTrack track, double te, Vec3 ear, Propagation.Weather weather) {
        Crack c = crack(track, te, ear, weather);
        if (c == null || c.volume < 0.005f) return;
        ClientSounds.atEar((c.longWave ? ModSounds.SONIC_BOOM : ModSounds.SONIC_CRACK).get(), c.at, c.volume, c.pitch);
    }

    /**
     * Хлопок, который приходит, если звук впервые дошёл из момента {@code te}: N-волна у уха, громкость, запись (короткая
     * или долгая) и тон, растягивающий её до длительности волны; {@code null} — снаряд тогда шёл не быстрее звука.
     */
    static Crack crack(FlightTrack track, double te, Vec3 ear, Propagation.Weather weather) {
        if (te < track.start() + 2) return null;
        Noise.Body body = EngineSound.noise(track).body();
        Vec3 v = track.velocity(te);
        double mach = v.length() / Acoustics.SPEED;
        if (body == null || mach <= 1) return null;
        double[] p = new double[3];
        track.at(te, p);
        Vec3 at = new Vec3(p[0], p[1], p[2]);
        Vec3 n = ear.subtract(at);
        double d = n.length();
        // расстояние от линии полёта до уха
        double cos = d < 1.0e-6 ? 1 : n.dot(v) / (d * v.length());
        SonicBoom.Wave w = SonicBoom.at(mach, d * Math.sqrt(Math.max(0, 1 - cos * cos)), body);
        Outdoor.Path path = new Outdoor.Path(d, 0, Hearing.height(at, ear), Propagation.EAR, 1, 1, weather.day(), weather.rain(), weather.thunder());
        boolean longWave = w.seconds() >= Math.sqrt(CRACK * BOOM);
        float pitch = (float) Math.max(0.5, Math.min(2, (longWave ? BOOM : CRACK) / w.seconds()));
        return new Crack(at, w, Outdoor.pressureVolume(w.pressure(), FULL_PA, path), longWave, pitch);
    }

    /** Хлопок у уха: откуда, волна, громкость, долгая ли запись и её тон. */
    record Crack(Vec3 at, SonicBoom.Wave wave, float volume, boolean longWave, float pitch) {}
}
