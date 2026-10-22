package ua.zentix.incoming.strike;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.defense.InterceptorSpec;
import ua.zentix.incoming.entity.LauncherRack;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.world.BlockResponse;
import ua.zentix.incoming.util.StandardAtmosphere;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Числа паспорта согласованы между собой: выводимые (радиус разворота, упреждение сирены) — как в описании оружия,
 * а подобранные (геометрия атаки, последний участок, пакет пусковой) не противоречат выводимым. Поменяв скорость или
 * поворот в паспорте, этот тест скажет, какое подобранное число пересмотреть.
 */
class WeaponSpecTest {
    private static final List<WeaponSpec> GUIDED = List.of(WeaponSpec.DRONE, WeaponSpec.MISSILE);

    @Test
    void turnRadiusIsSpeedOverTurnRate() {
        // шахед ~40 блоков, ракета ~76, B-2 ~690 (описание BomberEntity)
        assertEquals(40.1, WeaponSpec.DRONE.airframe().turnRadius(WeaponSpec.DRONE.airframe().cruiseSpeed()), 0.1);
        assertEquals(76.4, WeaponSpec.MISSILE.airframe().turnRadius(WeaponSpec.MISSILE.airframe().cruiseSpeed()), 0.1);
        assertEquals(687.5, WeaponSpec.BUNKER.airframe().turnRadius(WeaponSpec.BUNKER.airframe().cruiseSpeed()), 0.1);
    }

    /**
     * Как снаряд видит и бьёт ЗРК: B-2 и «Ланцет» малозаметны, бомба под B-2 — почти не видна; МБР и РСЗО ЗРК видит,
     * но не перехватывает (МБР — не его цель, «Град» дешевле ракеты); шахед, ракета, «Ланцет» и B-2 — да.
     */
    @Test
    void radarSignature() {
        for (WeaponType w : WeaponType.values()) {
            WeaponSpec.Signature r = w.spec().airframe().radar();
            assertTrue(r.visibility() > 0 && r.visibility() <= 1, w + ": заметность " + r.visibility());
            assertEquals(w == WeaponType.DRONE || w == WeaponType.MISSILE || w == WeaponType.LOITER || w == WeaponType.BUNKER, r.intercept(),
                    w + ": перехват");
        }
        assertTrue(WeaponSpec.BUNKER.airframe().radar().visibility() <= 0.2, "B-2 виден издалека");
        assertTrue(WeaponSpec.LOITER.airframe().radar().visibility() < WeaponSpec.DRONE.airframe().radar().visibility());
        assertTrue(WeaponSpec.MISSILE.airframe().radar().visibility() < WeaponSpec.DRONE.airframe().radar().visibility());
        WeaponSpec.Airframe bomb = WeaponSpec.BUNKER.payload();
        assertTrue(bomb != null && !bomb.radar().intercept() && bomb.radar().visibility() <= WeaponSpec.BUNKER.airframe().radar().visibility());
    }

    @Test
    void sirenLeadIsSecondsInTicks() {
        assertEquals(500, WeaponSpec.DRONE.sirenLead());
        assertEquals(300, WeaponSpec.MISSILE.sirenLead());
        assertEquals(160, WeaponSpec.ROCKET.sirenLead());
        assertEquals(400, WeaponSpec.LOITER.sirenLead());
        assertEquals(400, WeaponSpec.BUNKER.sirenLead());
    }

    /**
     * Заряд боевой части есть у того, у кого есть наземный взрыв, и стёкла (порог хрупкого) он выбивает до дальности
     * из описания оружия.
     */
    @Test
    void chargeBreaksGlassAsDescribed() {
        for (WeaponSpec w : List.of(WeaponSpec.DRONE, WeaponSpec.MISSILE, WeaponSpec.ROCKET, WeaponSpec.LOITER, WeaponSpec.BUNKER, WeaponSpec.NUKE)) {
            assertEquals(w.blast() == WeaponSpec.Blast.NONE, w.charge() == 0, () -> w.blast() + ": заряд " + w.charge());
        }
        assertEquals(70, glassRange(WeaponSpec.DRONE), 7);
        assertEquals(105, glassRange(WeaponSpec.MISSILE), 10);
        assertEquals(37, glassRange(WeaponSpec.ROCKET), 4);
        assertEquals(29, glassRange(WeaponSpec.LOITER), 3);
    }

    private static double glassRange(WeaponSpec w) {
        return BlastModel.rangeForSurfaceOverpressure(BlastModel.kpa(BlockResponse.FRAGILE_PSI), w.charge());
    }

    /**
     * Промах: у ракеты и бомбы меньше, чем у шахеда; «Ланцет» (камера), МБР и РСЗО (у неё рассеивание) — без промаха.
     * Выборка промахов шахеда — с его СКО по каждой оси, по горизонтали.
     */
    @Test
    void missIsPerWeapon() {
        assertTrue(WeaponSpec.MISSILE.route().error() < WeaponSpec.DRONE.route().error());
        assertTrue(WeaponSpec.BUNKER.route().error() < WeaponSpec.DRONE.route().error());
        RandomSource random = RandomSource.create(1);
        for (WeaponSpec w : List.of(WeaponSpec.LOITER, WeaponSpec.NUKE, WeaponSpec.ROCKET)) assertEquals(Vec3.ZERO, w.route().miss(random));
        int n = 20_000;
        double sx = 0, sz = 0;
        for (int i = 0; i < n; i++) {
            Vec3 m = WeaponSpec.DRONE.route().miss(random);
            assertEquals(0, m.y);
            sx += m.x * m.x;
            sz += m.z * m.z;
        }
        assertEquals(WeaponSpec.DRONE.route().error(), Math.sqrt(sx / n), 0.05);
        assertEquals(WeaponSpec.DRONE.route().error(), Math.sqrt(sz / n), 0.05);
    }

    /** Тревога звучит раньше, чем снаряд выходит на последний прямой участок: у цели успевают услышать заход. */
    @Test
    void sirenBeforeFinalLeg() {
        for (WeaponSpec w : List.of(WeaponSpec.DRONE, WeaponSpec.MISSILE, WeaponSpec.BUNKER)) {
            double warned = w.sirenLead() * w.airframe().cruiseSpeed();
            assertTrue(warned >= w.finalLeg(), "тревога за " + warned + " блоков, последний участок " + w.finalLeg());
        }
    }

    /**
     * Промах ближе {@code reattackMin} атаку не отменяет: он меньше круга разворота с запасом, иначе снаряд у самой цели
     * ушёл бы на новый заход там, где взрыватель ещё добирает.
     */
    @Test
    void reattackInsideTurnCircle() {
        for (WeaponSpec w : GUIDED) {
            WeaponSpec.Airframe a = w.airframe();
            double circle = a.turnRadius(a.cruiseSpeed()) * WeaponSpec.TURN_MARGIN;
            assertTrue(a.attack().reattackMin() > 0 && a.attack().reattackMin() < circle,
                    "reattackMin " + a.attack().reattackMin() + ", круг разворота " + circle);
        }
    }

    /**
     * Горка начинается вне круга разворота на скорости пикирования (иначе цель на атаке сразу «внутри круга» и атака
     * отменяется), не дальше последнего прямого участка и в полосе, которую видно у цели; заход с горкой — издалека,
     * чем её начало.
     */
    @Test
    void popUpGeometryFitsTurnAndLegs() {
        WeaponSpec.Airframe a = WeaponSpec.MISSILE.airframe();
        WeaponSpec.Attack attack = a.attack();
        assertTrue(attack.terminalRange() > a.turnRadius(a.diveSpeed()) * WeaponSpec.TURN_MARGIN);
        assertTrue(attack.terminalRange() <= WeaponSpec.MISSILE.finalLeg());
        assertTrue(attack.terminalRange() <= a.visibleLeg());
        assertTrue(attack.popUpMinRange() > attack.terminalRange());
    }

    /** Последний прямой участок вмещает два радиуса разворота: снаряд выходит из обхода на курс цели до атаки. */
    @Test
    void finalLegFitsTurn() {
        for (WeaponSpec w : GUIDED) {
            WeaponSpec.Airframe a = w.airframe();
            assertTrue(w.finalLeg() >= 2 * a.turnRadius(a.cruiseSpeed()), "последний участок " + w.finalLeg());
        }
    }

    /**
     * Точка маршрута взята около радиуса разворота: ближе снаряд кружил бы вокруг неё, а много дальше срезал бы поворот.
     * Дальность по маршруту оператора — путь до цели на краю карты по умолчанию (10 000 блоков) с обходом в полтора раза
     * длиннее прямой.
     */
    @Test
    void waypointCaptureAndReach() {
        for (WeaponSpec w : List.of(WeaponSpec.DRONE, WeaponSpec.MISSILE, WeaponSpec.LOITER)) {
            WeaponSpec.Airframe a = w.airframe();
            double r = a.turnRadius(a.cruiseSpeed());
            assertTrue(w.capture() >= 0.95 * r && w.capture() <= 2 * r, "захват точки " + w.capture() + ", радиус разворота " + r);
            assertTrue(w.route().reach() >= 1.5 * 10_000, "дальность по маршруту " + w.route().reach());
        }
        for (WeaponSpec w : List.of(WeaponSpec.BUNKER, WeaponSpec.NUKE, WeaponSpec.ROCKET)) assertTrue(!w.route().waypoints());
    }

    /** Ячейка пусковой занята, пока снаряд горит на направляющей и сходит с неё; пуски не чаще, чем освобождается ячейка. */
    @Test
    void rackHoldsSlotThroughLaunch() {
        for (WeaponSpec w : List.of(WeaponSpec.DRONE, WeaponSpec.MISSILE, WeaponSpec.LOITER)) {
            WeaponSpec.LaunchProfile lp = w.airframe().launchProfile();
            LauncherRack rack = w.rack();
            assertTrue(rack != null && lp != null);
            assertTrue(rack.busyTicks() >= lp.ignitionTicks() + lp.railTicks(), w.launch() + ": ячейка " + rack.busyTicks());
            assertTrue(rack.spacing() <= rack.busyTicks());
        }
    }

    /** У каждого вида оружия свой паспорт, и порядок видов (номера в сети и сохранениях) не менялся. */
    @Test
    void everyWeaponHasSpec() {
        assertEquals(List.of(WeaponType.DRONE, WeaponType.MISSILE, WeaponType.BUNKER, WeaponType.NUKE, WeaponType.ROCKET, WeaponType.LOITER),
                List.of(WeaponType.values()));
        for (WeaponType w : WeaponType.values()) {
            assertTrue(w.spec() != null, w.getSerializedName());
            assertEquals(w.ordinal(), w.id());
        }
        assertTrue(WeaponType.BUNKER.spec().payload() != null);
    }

    /**
     * Боеприпас у каждого оружия: по предмету на снаряд, а пакет «Града» — на все трубы пусковой (40, как перезарядка
     * БМ-21): залп по умолчанию из пульта в него помещается.
     */
    @Test
    void munitionPerShotAndGradPack() {
        for (WeaponType w : WeaponType.values()) {
            if (w == WeaponType.ROCKET) continue;
            assertEquals(1, w.spec().munition().rounds(), w.getSerializedName());
        }
        assertEquals(LauncherRack.ROCKET.slots(), WeaponSpec.ROCKET.munition().rounds());
        assertEquals(40, WeaponSpec.ROCKET.munition().rounds());
        assertTrue(WeaponSpec.ROCKET.salvo().count() <= WeaponSpec.ROCKET.munition().rounds());
    }

    /**
     * Взрыв на земле — с зарядом; топливо к удару — у тех, кто летит на нём (шахед — бензин, ракета — JP-10), у
     * «Ланцета» (батарея), «Града» (двигатель выгорел), бомбы и боеголовки — ноль. Числа и их источник — в Javadoc паспорта.
     */
    @Test
    void surfaceBlastsHaveChargeAndFuelAtImpact() {
        for (WeaponType w : WeaponType.values()) {
            WeaponSpec s = w.spec();
            if (s.blast() != WeaponSpec.Blast.NONE) assertTrue(s.charge() > 0, w + ": заряд " + s.charge());
            assertTrue(s.fuelAtImpact() >= 0 && Double.isFinite(s.fuelAtImpact()), w + ": топливо " + s.fuelAtImpact());
        }
        assertEquals(30, WeaponSpec.DRONE.fuelAtImpact());
        assertEquals(275, WeaponSpec.MISSILE.fuelAtImpact());
        for (WeaponSpec s : List.of(WeaponSpec.LOITER, WeaponSpec.ROCKET, WeaponSpec.BUNKER, WeaponSpec.NUKE)) assertEquals(0, s.fuelAtImpact());
    }

    // ---- изделия по ТТХ: выведенные законом числа против открытых данных

    private static final double RHO0 = StandardAtmosphere.density(0);

    /** Все сущности полёта всех оружий (у B-2 — и бомба). */
    private static List<WeaponSpec.Airframe> airframes() {
        List<WeaponSpec.Airframe> out = new ArrayList<>();
        for (WeaponType w : WeaponType.values()) {
            out.add(w.spec().airframe());
            if (w.spec().payload() != null) out.add(w.spec().payload());
        }
        return out;
    }

    /**
     * У каждого изделия все поля — настоящие числа: массы, размеры, сопротивление, скорости; у кого бьёт — условия
     * удара; БЧ — ТНТ не меньше нуля, корпус и ВВ не больше всей массы; топливо к удару не больше топлива на борту.
     */
    @Test
    void everyAirframeHasArticle() {
        for (WeaponSpec.Airframe a : airframes()) {
            Article r = a.article();
            String n = a.noise() + "";
            for (double v : new double[]{r.dryMass(), r.launchMass(), r.length(), r.diameter(), r.span(), r.cda(), r.real().cruise(), r.real().dive()})
                assertTrue(v > 0 && Double.isFinite(v), n);
            assertTrue(r.launchMass() >= r.dryMass(), n);
            assertTrue(r.real().dive() >= r.real().cruise(), n);
            assertTrue(r.fuelAtImpact() >= 0 && r.fuelAtImpact() <= r.launchMass() - r.dryMass(), n);
            if (r.warhead() != null) {
                assertTrue(r.warhead().tnt() > 0 && r.warhead().explosive() > 0 && r.warhead().caseMass() >= 0, n);
                assertTrue(r.warhead().explosive() + r.warhead().caseMass() <= r.dryMass(), n);
            }
            if (r.booster() != null) assertTrue(r.booster().propellant() > 0 && r.booster().jettison() >= 0, n);
        }
        for (WeaponType w : WeaponType.values()) {
            WeaponSpec s = w.spec();
            assertTrue(s.striker().article().impact() != null, w + ": условия удара");
            if (s.blast() != WeaponSpec.Blast.NONE) assertTrue(s.striker().article().warhead() != null, w + ": БЧ");
        }
        assertTrue(WeaponSpec.BUNKER.airframe().article().impact() == null, "B-2 сам не бьёт");
    }

    /** Решения Артёма: ракета — «Томагавк» ~150 кг ТНТ, шахед — исходный с 50 кг; MOP — 2,4 т ВВ. */
    @Test
    void chargesByDecision() {
        assertEquals(150, WeaponSpec.MISSILE.charge());
        assertEquals(50, WeaponSpec.DRONE.charge());
        assertEquals(6.4, WeaponSpec.ROCKET.charge());
        assertEquals(3, WeaponSpec.LOITER.charge());
        assertEquals(2423, WeaponSpec.BUNKER.striker().article().warhead().explosive());
        assertEquals(0, WeaponSpec.BUNKER.charge(), "бомба рвётся под землёй: наземного взрыва нет");
        assertEquals(0, WeaponSpec.NUKE.charge());
    }

    /** Игровые маршевые скорости — не больше настоящих (не по физике, чтобы подлёт было видно). */
    @Test
    void gameSpeedsBelowReal() {
        for (WeaponSpec s : List.of(WeaponSpec.DRONE, WeaponSpec.MISSILE, WeaponSpec.BUNKER)) {
            WeaponSpec.Airframe a = s.airframe();
            assertTrue(a.cruiseSpeed() * 20 <= a.article().real().cruise(), s.blast() + "");
        }
    }

    /** Шахед: крейсер из мощности винта и сопротивления ηP = ½ρv³·CdA — 185 км/ч ±10 % (Wikipedia). */
    @Test
    void shahedCruiseFromPowerAndDrag() {
        Article a = WeaponSpec.DRONE.airframe().article();
        Article.Propeller p = (Article.Propeller) a.sustainer();
        double v = Math.cbrt(2 * p.efficiency() * p.power() / (RHO0 * a.cda()));
        assertEquals(185 / 3.6, v, 185 / 3.6 * 0.1);
        assertEquals(a.real().cruise(), v, a.real().cruise() * 0.1);
        assertEquals(a.real().dive(), dive(a, 90), a.real().dive() * 0.05, "отвесно");
        assertEquals(a.impact().speed(), dive(a, a.impact().angle()), a.impact().speed() * 0.05, "удар в пике");
    }

    /** «Томагавк»: крейсер из тяги ТРДД и сопротивления T = ½ρv²·CdA — ~250 м/с ±5 % (М0,74). */
    @Test
    void tomahawkCruiseFromThrust() {
        Article a = WeaponSpec.MISSILE.airframe().article();
        double v = Math.sqrt(2 * ((Article.Turbofan) a.sustainer()).thrust() / (RHO0 * a.cda()));
        assertEquals(250, v, 250 * 0.05);
        // ускоритель Mk 106 разгоняет 1,6 т с места на ~210 м/с — дальше турбина
        Article.SolidMotor b = a.booster();
        double m0 = a.launchMass() + b.jettison();
        double dv = b.exhaust() * Math.log(m0 / (m0 - b.propellant()));
        assertTrue(dv > 150 && dv < a.real().cruise(), "Δv ускорителя " + dv);
    }

    /** «Ланцет»: отвесное пике 300 км/ч ±10 % из веса и CdA; удар — тот же баланс под углом удара. */
    @Test
    void lancetDiveFromWeight() {
        Article a = WeaponSpec.LOITER.airframe().article();
        assertEquals(300 / 3.6, dive(a, 90), 300 / 3.6 * 0.1);
        assertEquals(a.impact().speed(), dive(a, a.impact().angle()), a.impact().speed() * 0.05);
    }

    /**
     * «Град»: точка с массой, тяга двигателя паспорта, сопротивление CdA·f(M) в стандартной атмосфере — наибольшая
     * дальность 20,38 км ±3 % (Wikipedia), к выгоранию ~690 м/с ±3 %, удар — как в паспорте.
     */
    @Test
    void gradRangeWithMachDrag() {
        Article a = WeaponSpec.ROCKET.airframe().article();
        assertEquals(a.launchMass() - a.dryMass(), a.booster().propellant(), 0.01, "топливо — разница масс");
        double[] best = null;
        for (int deg = 40; deg <= 65; deg++) {
            double[] r = fly(a, a.booster(), Math.toRadians(deg), 0, 0);
            if (best == null || r[0] > best[0]) best = r;
        }
        assertEquals(20_380, best[0], 20_380 * 0.03, "дальность");
        assertEquals(690, best[1], 690 * 0.03, "к выгоранию");
        assertEquals(a.real().cruise(), best[1], 5);
        assertEquals(a.impact().speed(), best[2], 5, "скорость удара");
        assertEquals(a.impact().angle(), best[3], 1, "угол удара");
    }

    /** GBU-57: падение с высоты сброса на крейсерской скорости B-2 — удар как в паспорте (±2 %, ±1°). */
    @Test
    void mopDropImpact() {
        Article bomb = WeaponSpec.BUNKER.payload().article(), b2 = WeaponSpec.BUNKER.airframe().article();
        double[] r = fly(bomb, null, 0, b2.real().cruise(), b2.real().altitude());
        assertEquals(bomb.impact().speed(), r[2], bomb.impact().speed() * 0.02);
        assertEquals(bomb.impact().angle(), r[3], 1);
        assertTrue(r[4] > 45 && r[4] < 55, "падение ~50 с: " + r[4]);
    }

    /** 9М96: Δv двигателя по Циолковскому — не меньше наибольшей скорости; БЧ 24 кг (ВВ + корпус). */
    @Test
    void samPassportBy9M96() {
        InterceptorSpec.Passport p = InterceptorSpec.SAM.passport();
        double m0 = p.launchMass();
        assertTrue(p.boost().exhaust() * Math.log(m0 / (m0 - p.boost().propellant())) >= p.maxSpeed());
        assertEquals(24, p.warhead().explosive() + p.warhead().caseMass(), 1e-9);
        assertTrue(p.rangeMin() < p.rangeMax() && p.altitudeMin() < p.altitudeMax() && p.fuse() > 0);
        assertTrue(InterceptorSpec.SAM.maxSpeed() * 20 < p.maxSpeed(), "игровая медленнее настоящей");
    }

    /** Звук и факелы берут двигатель из паспорта: одна тяга и одно истечение на изделие. */
    @Test
    void noiseUsesArticleMotors() {
        assertEquals(WeaponSpec.DRONE_BOOSTER.thrust(), WeaponSpec.DRONE.airframe().noise().motor().thrust());
        assertEquals(WeaponSpec.MISSILE_BOOSTER.exhaust(), WeaponSpec.MISSILE.airframe().noise().motor().exhaust());
        assertEquals(WeaponSpec.ROCKET_MOTOR.thrust(), WeaponSpec.ROCKET.airframe().noise().motor().thrust());
        assertEquals(WeaponSpec.NUKE.ascent().thrust(), WeaponSpec.NUKE.airframe().noise().motor().thrust());
    }

    /** Установившаяся скорость в пике под углом θ к горизонту: тяга винта + mg·sin θ = ½ρv²·CdA. */
    private static double dive(Article a, double angle) {
        double m = (a.dryMass() + a.launchMass()) / 2, lo = 1, hi = 500;
        for (int i = 0; i < 100; i++) {
            double v = (lo + hi) / 2;
            double thrust = a.sustainer() instanceof Article.Propeller p ? p.thrust(v) : 0;
            if (thrust + m * Article.G * Math.sin(Math.toRadians(angle)) > 0.5 * RHO0 * v * v * a.cda()) lo = v;
            else hi = v;
        }
        return lo;
    }

    /**
     * Точка с массой над плоской землёй: тяга двигателя (если есть) вдоль скорости, сопротивление ½ρv²·CdA·f(M) по
     * стандартной атмосфере, шаг 0,01 с.
     *
     * @return дальность, м; скорость к выгоранию, м/с; скорость удара, м/с; угол удара, °; время полёта, с
     */
    private static double[] fly(Article a, Article.SolidMotor motor, double elevation, double v0, double h0) {
        double dt = 0.01, x = 0, h = h0, t = 0, burnout = Double.NaN;
        double v = Math.max(v0, 1e-3), vx = v * Math.cos(elevation), vy = v * Math.sin(elevation);
        double burn = motor == null ? 0 : motor.burn();
        while (true) {
            v = Math.hypot(vx, vy);
            double rho = StandardAtmosphere.density(Math.max(h, 0));
            double sound = Math.sqrt(1.4 * StandardAtmosphere.R_AIR * StandardAtmosphere.temperature(Math.max(h, 0)));
            double m = a.launchMass() - (motor == null ? 0 : motor.flow() * Math.min(t, burn));
            double thrust = t < burn ? motor.thrust() : 0;
            double drag = 0.5 * rho * v * v * a.cda() * Article.machFactor(v / sound);
            vx += (thrust - drag) * vx / v / m * dt;
            vy += ((thrust - drag) * vy / v / m - Article.G) * dt;
            x += vx * dt;
            h += vy * dt;
            t += dt;
            if (Double.isNaN(burnout) && t >= burn) burnout = Math.hypot(vx, vy);
            if (h < 0 && t > 1) return new double[]{x, burnout, Math.hypot(vx, vy), Math.toDegrees(Math.atan2(-vy, vx)), t};
        }
    }
}
