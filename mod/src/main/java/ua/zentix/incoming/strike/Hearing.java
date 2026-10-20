package ua.zentix.incoming.strike;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;

/**
 * Слышно ли снаряд: его шум сейчас (паспорт звука аппарата {@link WeaponSpec.Airframe#noise}, настоящая скорость
 * сущности) против фона у уха — по октавам — после пути по открытому воздуху ({@link Propagation}). Тот же расчёт, что даёт
 * громкость слоёв его звука на клиенте ({@code client.sound.EngineSound}), поэтому сервер шлёт путь снаряда
 * ({@link FarFlights}) ровно тем, кто его может слышать, а клиент дальше этого молчит сам. Преграды сервер не считает:
 * за холмом клиент глушит звук сам, и слышимое им всегда внутри посланного.
 */
public final class Hearing {
    /**
     * Дольше звук мода к уху не идёт, тиков: столько клиент помнит путь снаряда (60 с — гул B-2 с 12 км, рёв залпа
     * «Града» с 20 км). Дальше {@link #RANGE} звук не слышен — предел модели, а не физики: ночью рёв МБР
     * долетел бы и дальше.
     */
    public static final int MAX_DELAY = 1200;
    /** Дальше этого, блоков (= метров), звук не несётся: путь звука за {@link #MAX_DELAY}. */
    public static final double RANGE = MAX_DELAY * Noise.SOUND / 20;

    private Hearing() {}

    /** Высота источника над землёй под слушателем, м: ухо — на {@link Propagation#EAR} над ней. */
    public static double height(Vec3 source, Vec3 ear) {
        return Math.max(0, source.y - ear.y + Propagation.EAR);
    }

    /** Насколько снаряд сейчас громче фона у уха {@code ear} по открытому пути, дБ (меньше нуля — не слышно). */
    public static double margin(StrikeProjectile p, Vec3 ear, Propagation.Weather weather) {
        Vec3 at = p.position();
        double d = at.distanceTo(ear);
        if (d > RANGE) return Double.NEGATIVE_INFINITY;
        Propagation.Path path = Propagation.Path.over(d, height(at, ear), weather);
        return p.airframe().noise().margin(p.flightPhase(), p.phaseAge(), p.velocity().length(), drilling(p), path);
    }

    /** Слышно ли снаряд сейчас у уха {@code ear}. */
    public static boolean audible(StrikeProjectile p, Vec3 ear, Propagation.Weather weather) {
        return margin(p, ear, weather) > 0;
    }

    /**
     * Докуда снаряд сейчас слышно, блоков, если уши на {@code below} блоков ниже него на ровном лугу (0 — не слышно).
     * Для проверок и описаний: сервер решает по {@link #audible}.
     */
    public static double range(StrikeProjectile p, double below, Propagation.Weather weather) {
        return range(p.airframe().noise(), p.flightPhase(), p.phaseAge(), p.velocity().length(), drilling(p), below, weather);
    }

    /**
     * Докуда слышно шум {@code noise} в фазе {@code ph} на скорости {@code speed} (блоков/тик), блоков (0 — не слышно,
     * не дальше {@link #RANGE}): уши на {@code below} блоков ниже источника на ровном лугу (вблизи источник не выше
     * расстояния до него).
     */
    public static double range(Noise noise, FlightPhase ph, double age, double speed, boolean drilling, double below, Propagation.Weather w) {
        double lo = 1, hi = RANGE;
        if (!heard(noise, ph, age, speed, drilling, lo, below, w)) return 0;
        if (heard(noise, ph, age, speed, drilling, hi, below, w)) return hi;
        for (int i = 0; i < 40; i++) {
            double mid = Math.sqrt(lo * hi);
            if (heard(noise, ph, age, speed, drilling, mid, below, w)) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    private static boolean heard(Noise noise, FlightPhase ph, double age, double v, boolean drilling, double d, double below, Propagation.Weather w) {
        Propagation.Path path = Propagation.Path.over(d, Math.min(d, below) + Propagation.EAR, w);
        return noise.margin(ph, age, v, drilling, path) > 0;
    }

    private static boolean drilling(StrikeProjectile p) {
        return p instanceof BunkerBusterEntity b && b.isDrilling();
    }
}
