package ua.zentix.incoming.strike;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.defense.InterceptorSpec;
import ua.zentix.incoming.guidance.Ballistics;
import ua.zentix.incoming.guidance.BombDrop;
import ua.zentix.incoming.guidance.FlightController;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.util.StandardAtmosphere;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Шкала полёта мира ({@link FlightScale}): игровые числа при 0, настоящие при 1, между — по логарифму. */
class FlightScaleTest {
    private static final double[] XS = {0, 0.5, 1};

    /** Все аппараты паспорта по имени: сущности полёта и бомба B-2. */
    private static Map<String, WeaponSpec.Airframe> airframes() {
        Map<String, WeaponSpec.Airframe> all = new LinkedHashMap<>();
        for (WeaponType w : WeaponType.values()) {
            all.put(w.name(), w.spec().airframe());
            if (w.spec().payload() != null) all.put(w.name() + " payload", w.spec().payload());
        }
        return all;
    }

    @Test
    void blendIsGameAtZeroRealAtOneGeometricBetween() {
        assertEquals(2, FlightScale.blend(2, 8, 0));
        assertEquals(8, FlightScale.blend(2, 8, 1), 1e-12);
        assertEquals(4, FlightScale.blend(2, 8, 0.5), 1e-12);
        assertEquals(170, FlightScale.blend(170, 0, 1), "нет настоящего числа — игровое");
        assertEquals(0, FlightScale.blend(0, 7200, 1), "нет игрового — игровое");
    }

    /** Крейсер, пике и высота каждого изделия: при 0 — ровно игровые, при 1 — настоящие, при 0,5 — среднее геометрическое. */
    @Test
    void everyArticleAtZeroHalfOne() {
        airframes().forEach((name, a) -> {
            Article.Envelope real = a.article().real();
            check(a.gameCruise(), real.cruise() / 20, a::cruiseSpeed, name + " крейсер");
            check(a.gameDive(), real.dive() / 20, a::diveSpeed, name + " пике");
            if (real.altitude() > 0 && a.gameHeight() > 0) check(a.gameHeight(), real.altitude(), a::cruiseHeight, name + " высота");
            else for (double x : XS) assertEquals(a.gameHeight(), a.cruiseHeight(x), name + ": без настоящей высоты — игровая");
        });
    }

    private static void check(double game, double real, java.util.function.DoubleUnaryOperator at, String what) {
        assertEquals(game, at.applyAsDouble(0), 0, what + " при 0");
        assertEquals(Math.sqrt(game * real), at.applyAsDouble(0.5), 1e-9 * real, what + " при 0,5");
        assertEquals(real, at.applyAsDouble(1), 1e-9 * real, what + " при 1");
    }

    /**
     * Порядок скоростей изделий: где игра и жизнь согласны (одно не медленнее другого и там, и там), он тот же при любой
     * шкале. Шахед, «Ланцет», ракета и B-2 согласны: «Ланцет» медленнее шахеда, шахед — ракеты, ракета не быстрее B-2.
     * «Град» в игре медленнее B-2, а в жизни сходит с пакета втрое быстрее его — на шкале он его обгоняет, как в жизни.
     */
    @Test
    void orderOfSpeedsKept() {
        Map<String, WeaponSpec.Airframe> all = airframes();
        for (double x = 0; x <= 1.0001; x += 0.125) {
            for (var a : all.entrySet()) {
                for (var b : all.entrySet()) {
                    WeaponSpec.Airframe p = a.getValue(), q = b.getValue();
                    if (p.gameCruise() <= q.gameCruise() && p.cruiseSpeed(1) <= q.cruiseSpeed(1)) {
                        assertTrue(p.cruiseSpeed(x) <= q.cruiseSpeed(x) * (1 + 1e-12), a.getKey() + " быстрее " + b.getKey() + " при " + x);
                    }
                }
            }
            double lancet = WeaponSpec.LOITER.airframe().cruiseSpeed(x), shahed = WeaponSpec.DRONE.airframe().cruiseSpeed(x),
                    missile = WeaponSpec.MISSILE.airframe().cruiseSpeed(x), bomber = WeaponSpec.BUNKER.airframe().cruiseSpeed(x);
            assertTrue(lancet < shahed && shahed < missile && missile <= bomber * (1 + 1e-12), "при " + x);
        }
    }

    /** Зенитная ракета: наибольшая скорость 14 → 50 блоков/тик (1000 м/с у 9М96), разгон — за те же тики. */
    @Test
    void interceptorSpeed() {
        InterceptorSpec s = InterceptorSpec.SAM;
        check(s.gameMaxSpeed(), s.passport().maxSpeed() / 20, s::maxSpeed, "ЗУР");
        assertEquals(s.gameBoostAccel(), s.boostAccel(0), 0);
        double reach = (s.gameMaxSpeed() - s.launchSpeed()) / s.gameBoostAccel();
        for (double x : XS) assertEquals(s.maxSpeed(x), s.launchSpeed() + s.boostAccel(x) * reach, 1e-9, "разгон за те же тики при " + x);
    }

    /** Сопротивление без мотора: игровое без кривой Маха при 0, настоящее ρ₀·CdA/2m с ней при 1. */
    @Test
    void dragFromGameToArticle() {
        for (WeaponSpec.Airframe a : List.of(WeaponSpec.ROCKET.airframe(), WeaponSpec.BUNKER.payload())) {
            Ballistics.Drag zero = a.drag(0), one = a.drag(1);
            assertEquals(a.ballistic().drag(), zero.coefficient(), 0);
            assertEquals(0, zero.mach());
            assertEquals(StandardAtmosphere.density(0) * a.article().cda() / (2 * a.article().dryMass()), one.coefficient(), 1e-15);
            assertEquals(1, one.mach());
        }
        assertEquals(null, WeaponSpec.DRONE.airframe().drag(1), "на моторе — без сопротивления полёта без мотора");
    }

    /**
     * Длины, выведенные из скорости, растут с маршевой: связи паспорта ({@code WeaponSpecTest}) держатся на всей шкале —
     * последний участок вмещает два радиуса разворота, точка маршрута берётся около радиуса, атака не отменяется ближе
     * круга, горка — вне круга на скорости пике и не дальше участка и полосы подлёта, тревога — раньше участка.
     */
    @Test
    void lengthsFollowSpeed() {
        for (double x : XS) {
            for (WeaponType t : List.of(WeaponType.DRONE, WeaponType.MISSILE)) {
                WeaponSpec w = t.spec();
                WeaponSpec.Airframe a = w.airframe();
                double r = a.turnRadius(a.cruiseSpeed(x));
                assertTrue(w.finalLeg(x) >= 2 * r, t + " участок при " + x);
                assertTrue(w.capture(x) >= 0.95 * r && w.capture(x) <= 2 * r, t + " захват при " + x);
                assertTrue(a.attack(x).reattackMin() < r * WeaponSpec.TURN_MARGIN, t + " повторный заход при " + x);
                assertTrue(w.sirenLead() * a.cruiseSpeed(x) >= w.finalLeg(x), t + " тревога при " + x);
            }
            WeaponSpec.Airframe m = WeaponSpec.MISSILE.airframe();
            WeaponSpec.Attack at = m.attack(x);
            assertTrue(at.terminalRange() > m.turnRadius(m.diveSpeed(x)) * WeaponSpec.TURN_MARGIN, "горка вне круга при " + x);
            assertTrue(at.terminalRange() <= WeaponSpec.MISSILE.finalLeg(x) && at.terminalRange() <= m.visibleLeg(x), "горка при " + x);
            assertTrue(at.popUpMinRange() > at.terminalRange());
            double lancet = WeaponSpec.LOITER.airframe().turnRadius(WeaponSpec.LOITER.airframe().cruiseSpeed(x));
            assertTrue(WeaponSpec.LOITER.capture(x) >= 0.95 * lancet, "«Ланцет»: захват при " + x);
        }
    }

    /**
     * «Град» издалека: пакет встаёт на дальность траектории при угле пакета на скорости схода шкалы. При 0 — ровно 600
     * блоков, как было; при 1 расчёт огня на этот вынос даёт сход на настоящих 683 м/с (±2 %), вынос — ~20 км, как у
     * 9М22У на наибольшей дальности (20,4 км под 53°; здесь без участка работы двигателя — приближённо, 15–25 км).
     */
    @Test
    void gradFromAfarFiresAtTheBlendedSpeed() {
        WeaponSpec g = WeaponSpec.ROCKET;
        assertEquals(600, g.standoff(0), 0);
        for (double x : new double[]{0.5, 1}) {
            double d = g.standoff(x);
            Vec3 from = new Vec3(0, Air.SEA, 0), to = new Vec3(d, Air.SEA, 0);
            double v = Ballistics.aim(from, to, g.rack().elevation(), g.airframe().drag(x), Ballistics.Wind.CALM).length();
            assertEquals(g.airframe().cruiseSpeed(x), v, 0.02 * g.airframe().cruiseSpeed(x), "сход при " + x + " с " + d + " блоков");
        }
        assertTrue(g.standoff(1) > 15_000 && g.standoff(1) < 25_000, "вынос при 1: " + g.standoff(1));
    }

    /**
     * Бомба при 1: B-2 на настоящем эшелоне 12 км и 250 м/с сбрасывает её у черты сброса (тот же угол на точку, что на
     * игровом); с настоящим сопротивлением и кривой Маха она падает ~50 с и попадает со скоростью удара паспорта GBU-57
     * (511 м/с; у нас она идёт рулями прямо на точку, а не по баллистике — ±10 %). Пробитие у цели — по этой скорости.
     */
    @Test
    void bombAtRealScaleHitsAtPassportSpeed() {
        WeaponSpec.Airframe b2 = WeaponSpec.BUNKER.airframe(), bomb = WeaponSpec.BUNKER.payload();
        double h = b2.cruiseHeight(1), line = h * WeaponSpec.BUNKER.route().gameFinalLeg() / b2.gameHeight();
        Ballistics.Drag drag = bomb.drag(1);
        Vec3 aim = new Vec3(0, Air.SEA, 0), velocity = new Vec3(0, 0, b2.cruiseSpeed(1));
        Vec3 release = aim.add(0, h, -line);
        assertEquals(0, BombDrop.miss(release, velocity, aim, drag), "попадает с черты сброса");
        FlightController f = new FlightController(0, 0);
        Vec3 pos = release.add(0, -BombDrop.DROP_BELOW, 0);
        double v = velocity.length();
        int t = 0;
        while (pos.distanceTo(aim) > v + BombDrop.REACH_PAD && t < 5000) {
            v = BombDrop.steer(f, pos, v, aim, drag);
            pos = pos.add(f.forward().scale(v));
            t++;
        }
        double impact = bomb.article().impact().speed();
        assertEquals(impact, v * 20, 0.1 * impact, "скорость удара, м/с");
        assertTrue(t / 20.0 > 35 && t / 20.0 < 65, "падение " + t / 20.0 + " с");
    }
}
