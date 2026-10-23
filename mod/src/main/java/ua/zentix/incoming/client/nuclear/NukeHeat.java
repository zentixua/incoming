package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.fx.ClientAir;
import ua.zentix.incoming.client.fx.layer.HeatPass;
import ua.zentix.incoming.client.fx.smoke.FirePlume;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.CloudModel;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.nuclear.model.MushroomModel;
import ua.zentix.incoming.nuclear.model.ShockOptics;

/**
 * Ядерка в проходе дрожания ({@link HeatPass}): видимый фронт ударной волны и марево над шаром и над огненным штормом.
 * Всё — из модели подрыва у клиента, без пакетов.
 * <ul>
 * <li><b>Фронт</b> — сфера радиуса фронта в игре ({@link Detonation#frontRadius}) у подрыва, как только она отошла от шара
 * (до того фронт — край светящегося шара, и за ним не видно ничего); скачок показателя ({@link ShockOptics#indexJump}) —
 * по давлению фронта на этой сфере на четырёх высотах от земли до верха: у наземного подрыва вся полусфера — волна,
 * отражённая землёй ({@link Detonation#overpressureKpa}); у воздушного — падающая волна в свободном воздухе
 * ({@link HobBlast#freeAirPsi}), а у земли в области Маха — стебель Маха (давление на земле). Высоты тройной точки в модели
 * нет, и между землёй и следующей высотой скачок идёт по прямой (⚠ стебель — десятки–сотни метров у земли); толща
 * сжатия за фронтом — доля {@link ShockOptics#DECAY_SHARE} длины положительной фазы в игре (от фронта до
 * {@link Detonation#phaseEndRadius}). За краем стены пыли ({@link Detonation#HIT_KPA}) волна у модели — только звук, и
 * фронта не видно: от двух краёв до края скачок сходит на нет (<b>подача</b>: слабая волна там всё ещё скачок, но
 * её не рисует ничто, и свет за ней — тоже; волна, её приход и действие — те же).</li>
 * <li><b>Шар и шапка</b> — горячий газ того же закона, что у шара обычного взрыва ({@code FarBlasts.heat}): столб от низа
 * шапки до её верха радиусом шапки, температура — видимая ({@link MushroomModel#edgeTemperature}), вихри идут вверх со
 * скоростью подъёма облака ({@link CloudModel#riseSpeed}).</li>
 * <li><b>Огненный шторм</b> — поле очагов: клетка карты шторма — очаг из стольких блоков огня, сколько в ней горит
 * (сила клетки × пламя — доля колонок, {@link FireStorm#meanFlame}, средняя по зоне; × доверие модели огня), тем же
 * законом, что у пожаров Minecraft ({@link FirePlume}, Хескестад); столб — над всей зоной поджига, вихри — очага.</li>
 * </ul>
 */
public final class NukeHeat {
    /** Скачок фронта сходит на нет от этого давления, кПа, до края стены пыли (подача, см. выше). */
    static final double FADE_FROM_KPA = 2 * Detonation.HIT_KPA;

    private static final double[] DN = new double[4];
    private static final double[] CENTRE = new double[3];

    private NukeHeat() {}

    /** Фронты, шары и штормы подрывов кадра от глаза {@code eye}. */
    public static void heat(Vec3 eye, float partial, HeatPass.Frame f) {
        for (ClientNuclear.Active a : ClientNuclear.detonations()) {
            if (a.ticks(partial) <= 0) continue;
            front(a, eye, partial, f);
            ball(a, eye, partial, f);
            storm(a, eye, f);
        }
    }

    /** Доля скачка фронта при давлении {@code kpa}: от {@link #FADE_FROM_KPA} до края стены пыли — к нулю, за ним — 0. */
    static double seen(double kpa) {
        double x = Math.max(0, Math.min(1, (kpa - Detonation.HIT_KPA) / (FADE_FROM_KPA - Detonation.HIT_KPA)));
        return x * x * (3 - 2 * x);
    }

    /**
     * Скачок показателя на сфере фронта радиуса {@code r} блоков у подрыва {@code d} на четырёх высотах ровно от земли
     * ({@code low} — синус угла над подрывом у земли) до верха — в {@code out}; false — нигде не виден.
     */
    static boolean jumps(Detonation d, double r, double low, double[] out) {
        boolean any = false;
        Vec3 b = d.burst();
        double hob = Math.max(0, d.hobMetres());
        boolean air = !d.surface() && hob > 0;
        for (int k = 0; k < 4; k++) {
            double sine = low + (1 - low) * k / 3.0, across = Math.sqrt(Math.max(0, 1 - sine * sine));
            Vec3 p = new Vec3(b.x + r * across, b.y + r * sine, b.z);
            // у воздушного: на земле в области Маха — стебель (давление на земле), иначе — падающая волна
            boolean stem = k == 0 && d.metres(r * across) >= HobBlast.machStem(hob, d.yieldKt());
            double kpa = air && !stem ? BlastModel.kpa(HobBlast.freeAirPsi(d.metres(r), d.yieldKt())) : d.overpressureKpa(p);
            out[k] = ShockOptics.indexJump(kpa, HeatPass.N0) * seen(kpa);
            any |= out[k] > 0;
        }
        return any;
    }

    private static void front(ClientNuclear.Active a, Vec3 eye, float partial, HeatPass.Frame f) {
        Detonation d = a.d;
        double ticks = a.ticks(partial), r = d.frontRadius(ticks);
        if (r <= d.blocks(FireballModel.radius(a.seconds(partial), d.yieldKt(), d.surface()))) return;
        double low = Math.max(-1, Math.min(1, (d.groundY() - d.burst().y) / r));
        if (!jumps(d, r, low, DN)) return;
        double l = ShockOptics.DECAY_SHARE * (r - d.phaseEndRadius(ticks));
        Vec3 b = d.burst();
        f.front(b.x - eye.x, b.y - eye.y, b.z - eye.z, r, l, low, DN);
    }

    private static void ball(ClientNuclear.Active a, Vec3 eye, float partial, HeatPass.Frame f) {
        MushroomModel.Shape s = MushroomField.shape(a, partial);
        if (s == null) return;
        Detonation d = a.d;
        MushroomField.centre(a, s, CENTRE);
        double bottom = d.groundY() + d.blocks(s.capBottom()), top = d.groundY() + d.blocks(s.top());
        double kelvin = MushroomModel.edgeTemperature(s.t(), d.yieldKt(), d.surface());
        // м/с модели — те же блоки за секунду игры: и путь, и время — × масштаб
        double rise = CloudModel.riseSpeed(s.t(), Math.max(0, d.hobMetres()), d.yieldKt(), d.surface());
        f.hot(CENTRE[0] - eye.x, bottom - eye.y, CENTRE[2] - eye.z, top - bottom, d.blocks(s.capR()), kelvin, rise);
    }

    private static void storm(ClientNuclear.Active a, Vec3 eye, HeatPass.Frame f) {
        FireStorm s = a.storm;
        int cells = s.cells();
        if (!FireStorms.enabled || cells == 0) return;
        double burning = s.meanFlame() * s.gate();
        if (burning <= 0) return;
        double cell = s.cell(), blocks = burning * cell * cell;
        Detonation d = a.d;
        FirePlume p = new FirePlume(blocks, d.windSpeed(), ClientAir.now().stability());
        Vec3 b = d.burst();
        f.plumes(b.x - eye.x, d.groundY() - eye.y, b.z - eye.z, p, 1, cell * Math.sqrt(cells / Math.PI));
    }
}
