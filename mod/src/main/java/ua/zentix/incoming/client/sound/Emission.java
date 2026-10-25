package ua.zentix.incoming.client.sound;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.flight.FlightTrack;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.sound.Spectrum;
import ua.zentix.incoming.strike.Hearing;

/**
 * Что слушатель слышит от снаряда в этот тик: «запаздывающий» момент излучения {@code time} и всё, что из него
 * выводят слои звука ({@link EngineSound.Layer#tone}) — положение, расстояние, направление на ухо, скорость, Доплер,
 * фаза полёта и путь звука до уха ({@link #heard}: уровень каждой шумящей части после пути). Считается раз в тик на
 * снаряд, общий для всех его слоёв.
 *
 * @param nx      единичный вектор от источника к уху
 * @param radial  проекция скорости на него (больше нуля — снаряд идёт на слушателя)
 * @param height  высота источника над землёй под слушателем, м ({@link Hearing#height})
 * @param onboard слушатель сидит на самом снаряде (камера снаряда)
 */
record Emission(double time, double x, double y, double z, double distance, double nx, double ny, double nz, Vec3 velocity,
                double radial, double doppler, int phase, double phaseAge, double height, Propagation.Weather weather, boolean onboard,
                Heard[] parts) {
    /** Слушатель впереди снаряда, если тот идёт на него не дальше этого угла от курса; позади — с {@link #BEHIND_DEG}. */
    static final double AHEAD_DEG = 45, BEHIND_DEG = 100;
    private static final double AHEAD_COS = Math.cos(Math.toRadians(AHEAD_DEG)), BEHIND_COS = Math.cos(Math.toRadians(BEHIND_DEG));

    /**
     * Шумящая часть у уха после пути по открытому воздуху: мощность звука у источника, A-уровень у уха, насколько она
     * громче фона в самой выгодной октаве ({@link Propagation#margin}) и доля верхов (октава 4 кГц против всего звука).
     */
    record Heard(double power, double level, double margin, double highs) {
        static final Heard SILENT = new Heard(Noise.SILENT, Noise.SILENT, Double.NEGATIVE_INFINITY, 1);
    }

    /** Слышимое из момента te: его данные о снаряде должны быть ({@link FlightTrack#covers}). */
    static Emission at(FlightTrack track, double te, Vec3 ear, boolean onboard, Propagation.Weather weather) {
        double[] p = new double[3];
        track.at(te, p);
        double dx = ear.x - p[0], dy = ear.y - p[1], dz = ear.z - p[2];
        double d = Math.max(0.5, Math.sqrt(dx * dx + dy * dy + dz * dz));
        double nx = dx / d, ny = dy / d, nz = dz / d;
        Vec3 v = track.velocity(te);
        return new Emission(te, p[0], p[1], p[2], d, nx, ny, nz, v, v.x * nx + v.y * ny + v.z * nz,
                Acoustics.doppler(v.x, v.y, v.z, nx, ny, nz), track.phase(te), track.phaseAge(te),
                Hearing.height(new Vec3(p[0], p[1], p[2]), ear), weather, onboard, new Heard[Noise.Part.values().length]);
    }

    /** Путь звука до уха: ухо на {@link Propagation#EAR} над лугом, источник — на {@link #height}. */
    Propagation.Path path(double distance) {
        return Propagation.Path.over(distance, height, weather);
    }

    /** Часть {@code part} шума снаряда у уха (считается раз за тик). */
    Heard heard(Noise noise, Noise.Part part, boolean drilling) {
        Heard h = parts[part.ordinal()];
        if (h != null) return h;
        double lw = noise.power(part, FlightPhase.byId(phase), phaseAge, velocity.length(), drilling);
        if (lw == Noise.SILENT) h = Heard.SILENT;
        else {
            Spectrum spectrum = noise.spectrum(part);
            double[] bands = new double[Propagation.BANDS];
            double level = Propagation.level(lw, spectrum, path(distance), bands);
            h = new Heard(lw, level, Propagation.margin(bands, weather), highs(lw, spectrum, path(distance)));
        }
        parts[part.ordinal()] = h;
        return h;
    }

    /**
     * Доля верхов части {@code part}, как если бы до уха было {@code distance} блоков (та же высота источника и погода,
     * воздух — в условиях записей, {@link Propagation.Weather#recorded}): сколько их съел бы путь, уже вошедший в дальнюю
     * запись.
     */
    double heardAt(Noise noise, Noise.Part part, boolean drilling, double distance) {
        double lw = noise.power(part, FlightPhase.byId(phase), phaseAge, velocity.length(), drilling);
        return lw == Noise.SILENT ? 1 : highs(lw, noise.spectrum(part), Propagation.Path.over(distance, height, weather.recorded()));
    }

    /**
     * Доля верхов звука мощностью {@code lw} со спектром {@code spectrum} после пути {@code path}: насколько октава
     * 4 кГц ослабла по пути сильнее, чем звук в целом (EFX глушит их выше 5 кГц).
     */
    static double highs(double lw, Spectrum spectrum, Propagation.Path path) {
        double[] bands = new double[Propagation.BANDS];
        double level = Propagation.level(lw, spectrum, path, bands);
        int hf = Propagation.BANDS - 2;
        double emitted = lw + spectrum.aWeighting(), lost4k = lw + spectrum.z(hf) + Propagation.aWeight(hf) - bands[hf];
        return Math.min(1, Math.pow(10, -(lost4k - (emitted - level)) / 20));
    }

    /**
     * Насколько слушатель впереди снаряда: 1 — снаряд идёт на него не дальше {@link #AHEAD_DEG} от курса, 0 — слушатель
     * сбоку-сзади (дальше {@link #BEHIND_DEG}), между — плавно. Шум вентилятора и рассекаемого воздуха уносит вперёд:
     * на пролёте он стихает за доли секунды, а не обрывается в точке, где снаряд ближе всего.
     */
    double ahead() {
        double v = velocity.length();
        return v < 1.0e-6 ? 0 : Acoustics.smoothstep(BEHIND_COS, AHEAD_COS, radial / v);
    }

    Vec3 position() {
        return new Vec3(x, y, z);
    }

    boolean approaching() {
        return radial > 0;
    }
}
