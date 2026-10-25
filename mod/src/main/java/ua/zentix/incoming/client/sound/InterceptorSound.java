package ua.zentix.incoming.client.sound;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.defense.InterceptorSpec;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.registry.ModSounds;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.sound.Spectra;
import ua.zentix.incoming.strike.Article;

/**
 * Мотор зенитной ракеты — те же законы, что у моторов снарядов ({@link EngineSound}): мощность звука РДТТ — доля
 * механической мощности струи по его тяге и истечению из паспорта ({@link Noise.Rocket}: на разгоне — двигатель
 * разгона, дальше — маршевый режим), путь до уха — {@link Propagation} (разлёт, воздух по октавам, земля, тень днём,
 * фон), громкость в игре — {@link Loudness}; докуда слышно, выходит само: пока хоть одна октава громче фона. Запись —
 * петля ускорителя ({@code booster.engine}, одна на все РДТТ), голос — по величине двигателя.
 * <p>
 * Звучит слоем {@link EngineSound.Layer#SAM} в общем хозяйстве моторов ({@link ClientSounds}): путь — из путей ПВО
 * ({@link ua.zentix.incoming.client.flight.FlightTracks#interceptors}: по сущности и по пакетам сервера), момент
 * излучения, каналы ({@link VoiceBudget}), перезапуск после F3+T и смены устройства, преграды — как у снарядов.
 */
public final class InterceptorSound {
    private static final InterceptorSpec SPEC = InterceptorSpec.SAM;
    /** Двигатель на разгоне и в маршевом режиме (игровом, не по физике — {@link InterceptorSpec.Passport#sustain}). */
    static final Noise.Rocket BOOST = rocket(SPEC.passport().boost()), SUSTAIN = rocket(SPEC.passport().sustain());
    /**
     * Голос записи: одна запись на все РДТТ, тон — по величине двигателя (как у {@link EngineSound.Layer#BOOSTER}:
     * ускоритель шахеда и ракеты 1, снаряд «Града» 1,3); 9М96 (Ø 0,24 м) — между ними. Сдвиг записи в пределах ±25 %.
     */
    static final double VOICE = 1.2;
    /** Звук схода слышен, только если мотор зазвучал у уха с первых тиков разгона. */
    static final int IGNITION_HEARD = 10;
    /**
     * Корпус по паспорту — для N-волны, когда ракета быстрее звука ({@link Booms}: шкала полёта до настоящей скорости
     * 9М96). Слоя обтекания у ЗРК нет: тон записи ({@code reference}) не нужен.
     */
    static final Noise.Body BODY = new Noise.Body(SPEC.passport().diameter(), SPEC.passport().length(), Double.NaN, Spectra.BOMB_FALL);

    private InterceptorSound() {}

    private static Noise.Rocket rocket(Article.SolidMotor m) {
        return new Noise.Rocket(m.thrust(), m.exhaust(), Spectra.BOOSTER_ENGINE);
    }

    /** Мощность звука мотора в момент излучения, дБ: на разгоне — двигатель разгона, дальше — маршевый режим. */
    static double power(Emission e) {
        Noise.Rocket motor = FlightPhase.byId(e.phase()).boosterLit() ? BOOST : SUSTAIN;
        return motor.power(FlightPhase.BOOST, e.phaseAge());
    }

    /** Громкость, тон и доля верхов мотора у уха из того, что слушатель слышит сейчас. */
    static EngineSound.Tone tone(Emission e) {
        double lw = power(e);
        if (lw == Noise.SILENT) return EngineSound.Tone.SILENT;
        Propagation.Path path = e.path(e.distance());
        double[] bands = new double[Propagation.BANDS];
        double level = Propagation.level(lw, Spectra.BOOSTER_ENGINE, path, bands);
        double gain = Loudness.gain(level, Propagation.margin(bands, e.weather()));
        // предел тона у Minecraft — 0,5…2 (SoundEngine)
        double pitch = Math.max(0.5, Math.min(2, VOICE * e.doppler()));
        return new EngineSound.Tone(gain, pitch, Emission.highs(lw, Spectra.BOOSTER_ENGINE, path));
    }

    /**
     * Мотор впервые слышен у уха: если это звук самого схода (а не середины полёта — ракета появилась у клиента уже в
     * пути), удар поджига — как у ускорителей снарядов ({@code EngineSound.launchEvents}): запись старта ускорителя
     * ({@code launch.booster}) громкостью по мощности звука двигателя разгона на полной тяге и пути до уха, голосом этого
     * двигателя.
     */
    static void heard(Emission e) {
        if (!FlightPhase.byId(e.phase()).boosterLit() || e.phaseAge() >= IGNITION_HEARD) return;
        double[] bands = new double[Propagation.BANDS];
        double level = Propagation.level(BOOST.power(FlightPhase.BOOST, 0), Spectra.LAUNCH_BOOSTER, e.path(e.distance()), bands);
        double v = Loudness.gain(level, Propagation.margin(bands, e.weather()));
        if (v > 0.01) ClientSounds.atEar(ModSounds.LAUNCH_BOOSTER.get(), new Vec3(e.x(), e.y(), e.z()), Loudness.motor(v), (float) VOICE);
    }
}
