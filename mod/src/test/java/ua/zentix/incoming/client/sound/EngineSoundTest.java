package ua.zentix.incoming.client.sound;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.flight.FlightTrack;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.guidance.Ballistics;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.strike.Hearing;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Слои звука на всём пути снаряда: громкость — от мощности звука и пути, тон — от оборотов и Доплера. */
class EngineSoundTest {
    private static final UUID ID = UUID.randomUUID();
    private static final Vec3 EAR = Vec3.ZERO;
    private static final Propagation.Weather NIGHT = Propagation.Weather.NIGHT;
    /** Маршевая скорость ракеты, блоков/тик. */
    private static final double V = WeaponSpec.MISSILE.airframe().cruiseSpeed();
    private static final EngineSound.Layer[] MISSILE = {EngineSound.Layer.MISSILE_FRONT, EngineSound.Layer.MISSILE_REAR,
            EngineSound.Layer.MISSILE_DIVE, EngineSound.Layer.MISSILE_FAR, EngineSound.Layer.MISSILE_WHISTLE};

    /** Снаряд оружия {@code w} с {@code from} блоков прямо на слушателя на высоте {@code y}, {@code ticks} тиков, пакеты раз в {@code every}. */
    private static FlightTrack straight(WeaponType w, double from, double y, double v, FlightPhase ph, Vec3 aim, int ticks, int every) {
        FlightTrack t = new FlightTrack(ID, w, false);
        for (int k = 0; k <= ticks; k += every) {
            t.record(k, new S2C.FarFlight(ID, w.id(), false, false, true, new Vec3(from - v * k, y, 0), new Vec3(-v, 0, 0), 90, 0, 0,
                    ph.ordinal(), 200 + k, aim));
        }
        return t;
    }

    private static FlightTrack missile(double from, Vec3 aim, int ticks) {
        return straight(WeaponType.MISSILE, from, 12, V, FlightPhase.CRUISE, aim, ticks, 1);
    }

    private static Emission heard(FlightTrack t, double now, Vec3 ear) {
        double te = Acoustics.emissionTime(t, now, ear.x, ear.y, ear.z);
        return Double.isNaN(te) || !t.covers(te) ? null : Emission.at(t, te, ear, false, NIGHT);
    }

    private static double gain(EngineSound.Layer layer, FlightTrack t, double now, Vec3 ear) {
        Emission e = heard(t, now, ear);
        return e == null ? 0 : layer.tone(t, e).gain();
    }

    /** Громкость мотора ракеты — всех его записей вместе (по мощности). */
    private static double engine(FlightTrack t, double now, Vec3 ear) {
        double g = 0;
        for (EngineSound.Layer l : MISSILE) g += Math.pow(gain(l, t, now, ear), 2);
        return Math.sqrt(g);
    }

    @Test
    void missileWhistleWarnsFromWhereItBecomesAudible() {
        // путь по пакетам (раз в 2 тика), как у ракеты вне мира, с края слышимости ночью на слушателя у цели: свист
        // слышно до прихода ракеты на d·(1/v − 1/c), и история пути не теряет слышимую точку
        Noise noise = WeaponSpec.MISSILE.airframe().noise();
        double from = Math.min(Hearing.range(noise, FlightPhase.CRUISE, 200, V, false, 12, NIGHT), 5000);
        int arrival = (int) (from / V);
        FlightTrack t = straight(WeaponType.MISSILE, from, 12, V, FlightPhase.CRUISE, EAR, arrival, 2);
        int first = -1;
        double firstDistance = 0;
        for (int k = 0; k <= arrival && first < 0; k++) {
            Emission e = heard(t, k, EAR);
            if (e != null && EngineSound.Layer.MISSILE_WHISTLE.tone(t, e).gain() >= VoiceBudget.AUDIBLE) {
                first = k;
                firstDistance = e.distance();
            }
        }
        assertTrue(first >= 0, "свиста не было");
        assertTrue(firstDistance > 0.6 * from, "свист слышно с " + Math.round(firstDistance) + " блоков, слышимость — до " + Math.round(from));
        double lead = firstDistance * (1 / V - 1 / Acoustics.SPEED);
        assertTrue(arrival - first >= 0.9 * lead, "свист за " + (arrival - first) + " тиков до прихода ракеты, ожидание ~" + Math.round(lead));
        // ближе — громче (вблизи вой вентилятора — уже ближняя запись спереди)
        assertTrue(engine(t, arrival - 30, EAR) > engine(t, first + 30, EAR));
    }

    @Test
    void missileEngineDoesNotDipOnPassOrLayerHandoff() {
        // ракета проходит в 12 блоках над головой и уходит дальше: мощность мотора (все его слои — разные записи,
        // складываются по мощности) на подлёте только растёт, вслед только падает — без провала над головой и на смене
        // ближних записей дальними, спереди задними
        FlightTrack t = missile(1200, new Vec3(-3000, 0, 0), 600);
        double prev = -1;
        boolean prevApproaching = true;
        int passed = 0;
        for (double now = 5; now <= 600; now += 0.5) {
            Emission e = heard(t, now, EAR);
            if (e == null) continue;
            double power = 0;
            for (EngineSound.Layer l : MISSILE) power += Math.pow(l.tone(t, e).gain(), 2);
            if (prev >= 0 && e.approaching() == prevApproaching) {
                String at = "тик " + now + ", " + Math.round(e.distance()) + " блоков, " + (e.approaching() ? "подлёт" : "вслед");
                if (e.approaching()) assertTrue(power >= prev * (1 - 1e-9), at + ": тише, чем тиком раньше: " + prev + " → " + power);
                else assertTrue(power <= prev * (1 + 1e-9), at + ": громче, чем тиком раньше: " + prev + " → " + power);
            }
            if (!e.approaching()) passed++;
            prev = power;
            prevApproaching = e.approaching();
        }
        assertTrue(passed > 40, "ракета ушла за слушателя: отсчётов вслед " + passed);
    }

    @Test
    void layersOfOneEngineShareItsPower() {
        // ближняя и дальняя записи шахеда вместе звучат как один мотор: сумма мощностей — громкость по уровню у уха
        FlightTrack t = straight(WeaponType.DRONE, 1500, 45, 2.1, FlightPhase.CRUISE, new Vec3(-2000, 0, 0), 700, 1);
        for (int now = 100; now <= 700; now += 10) {
            Emission e = heard(t, now, EAR);
            if (e == null) continue;
            double near = EngineSound.Layer.DRONE_NEAR.tone(t, e).gain(), far = EngineSound.Layer.DRONE_FAR.tone(t, e).gain();
            Emission.Heard h = e.heard(EngineSound.noise(t), Noise.Part.ENGINE, false);
            assertEquals(Loudness.gain(h.level(), h.margin()), Math.sqrt(near * near + far * far), 1e-9, "тик " + now);
        }
    }

    @Test
    void shareKeepsPowerOfTwoLayers() {
        for (double w = 0; w <= 1; w += 0.05) {
            double a = EngineSound.share(w), b = EngineSound.share(1 - w);
            assertEquals(1, a * a + b * b, 1e-12);
        }
    }

    @Test
    void propellerToneFollowsAirspeed() {
        // винт фиксированного шага: обороты — по скорости (поступь та же), не ниже оборотов на месте и не выше предела
        Noise.Propeller p = (Noise.Propeller) WeaponSpec.DRONE.airframe().noise().engine();
        assertEquals(p.idle(), p.revs(FlightPhase.READY, 0, 0), 1e-12);
        double cruise = WeaponSpec.DRONE.airframe().cruiseSpeed();
        assertEquals(cruise * 20 / p.speed(), p.revs(FlightPhase.CRUISE, 0, cruise), 1e-12);
        assertEquals(p.limit(), p.revs(FlightPhase.TERMINAL, 0, 100), 1e-12);
        // мощность ∝ n³: на 10% больше оборотов — на 1,24 дБ громче
        assertEquals(30 * Math.log10(1.1), p.power(FlightPhase.CRUISE, 0, 1.1 * p.speed() / 20) - p.power(FlightPhase.CRUISE, 0, p.speed() / 20), 1e-9);
    }

    @Test
    void rocketHowlsWhereverItIsAboveTheBackground() {
        // снаряд РСЗО на 1200 блоков (вершина дуги ~360 блоков): после выгорания вой слышно везде, где обтекание громче
        // фона с запасом, а за краем слышимости — тишина
        double range = 1200;
        int ticks = Ballistics.ticksFor(Vec3.ZERO, new Vec3(range, 0, 0), 50, 50);
        Vec3 v0 = Ballistics.launchVelocity(Vec3.ZERO, new Vec3(range, 0, 0), ticks);
        FlightTrack t = new FlightTrack(ID, WeaponType.ROCKET, false);
        for (int k = 0; k <= ticks; k++) {
            Vec3 p = Ballistics.at(Vec3.ZERO, v0, k), v = Ballistics.at(Vec3.ZERO, v0, k + 1).subtract(p);
            FlightPhase ph = k < 40 ? FlightPhase.BOOST : v.y < 0 ? FlightPhase.TERMINAL : FlightPhase.CRUISE;
            t.record(k, new S2C.FarFlight(ID, WeaponType.ROCKET.id(), false, false, true, p, v, 90, 0, 0, ph.ordinal(), k, new Vec3(range, 0, 0)));
        }
        int heard = 0, silent = 0;
        for (double x : new double[] {-10, range / 4, range / 2, range + 5}) {
            Vec3 ear = new Vec3(x, 2, 0);
            for (int now = 0; now <= ticks + 100; now += 5) {
                Emission e = heard(t, now, ear);
                if (e == null || e.time() < 42 || e.time() > ticks) continue;
                Emission.Heard h = e.heard(EngineSound.noise(t), Noise.Part.BODY, false);
                double gain = EngineSound.Layer.ROCKET_AIR.tone(t, e).gain();
                // направленность обтекания — до −5,2 дБ вслед
                if (h.margin() > Propagation.FADE + 5.3) {
                    assertTrue(gain > 0, "слушатель у " + x + ", тик " + now + ": в " + Math.round(e.distance()) + " блоках вой " + gain);
                    heard++;
                } else if (h.margin() <= 0) {
                    assertEquals(0, gain, 0, "за краем слышимости");
                    silent++;
                }
            }
        }
        assertTrue(heard > 5 && silent > 5, "слышимых отсчётов: " + heard + ", за краем — " + silent);
    }

    @Test
    void whistleFadesThroughThePassInsteadOfCuttingOff() {
        // ракета проходит в 60 блоках сбоку: вой вентилятора, который уносит вперёд, стихает за пролёт, а не обрывается
        Vec3 ear = new Vec3(0, 12, 60);
        FlightTrack t = missile(600, new Vec3(-700, 0, 0), 300);
        double prev = -1, peak = 0, worst = 0;
        for (double now = 40; now <= 220; now += 1) {
            double g = 0;
            Emission e = heard(t, now, ear);
            if (e != null) for (EngineSound.Layer l : MISSILE) g += Math.pow(l.tone(t, e).gain(), 2);
            g = Math.sqrt(g);
            if (prev >= 0) worst = Math.max(worst, Math.abs(g - prev));
            peak = Math.max(peak, g);
            prev = g;
        }
        assertTrue(peak > 0.05, "ракета на пролёте " + peak);
        assertTrue(worst < 0.15 * peak, "скачок громкости за тик " + worst + " при пике " + peak);
    }

    @Test
    void supersonicRoundCracksBeforeItIsHeard() {
        // снаряд на 1,4 М (синтетическая скорость: в нынешнем полёте сверхзвука нет) проходит в 100 блоках над
        // слушателем: пока конус Маха не дошёл, его не слышно вовсе; в тик, когда дошёл, — N-волна, и только с ним
        // начинается вой
        double v = 1.4 * Acoustics.SPEED;
        FlightTrack t = straight(WeaponType.ROCKET, 3000, 100, v, FlightPhase.TERMINAL, new Vec3(-5000, 0, 0), 300, 1);
        double[] roots = new double[1];
        boolean reached = false;
        int boomAt = -1;
        for (int now = 0; now <= 300; now++) {
            boolean r = Acoustics.emissions(t, now, 0, 0, 0, roots) > 0;
            if (!r) assertEquals(0, gain(EngineSound.Layer.ROCKET_AIR, t, now, EAR), 0, "тик " + now + ": вой до прихода конуса");
            if (r && !reached) {
                Booms.Crack c = Booms.crack(t, roots[0], EAR, NIGHT);
                assertNotNull(c, "конус дошёл без N-волны");
                assertTrue(c.wave().pressure() > 50 && c.volume() > 0, "волна " + c.wave() + ", громкость " + c.volume());
                boomAt = now;
            }
            reached |= r;
        }
        // за последней записью путь лишь дотянут по скорости — там хлопка нет
        assertNull(Booms.crack(t, t.lastTick() + 1, EAR, NIGHT));
        // конус догоняет ухо, когда снаряд прошёл h·√(M² − 1) за ним
        double overhead = 3000 / v, expected = overhead + 100 * Math.sqrt(1.4 * 1.4 - 1) / v;
        assertEquals(expected, boomAt, 1.5, "N-волна на тике");
        // дозвуковой снаряд не хлопает
        FlightTrack slow = straight(WeaponType.ROCKET, 600, 100, 4, FlightPhase.TERMINAL, new Vec3(-5000, 0, 0), 300, 1);
        for (int now = 0; now <= 300; now++) {
            if (Acoustics.emissions(slow, now, 0, 0, 0, roots) > 0) {
                assertNull(Booms.crack(slow, roots[0], EAR, NIGHT));
                break;
            }
        }
    }
}
