package ua.zentix.incoming.client.fx.plume;

import ua.zentix.incoming.util.Stability;

import ua.zentix.incoming.util.Air;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.client.fx.plume.TrailModel.Trail;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.StandardAtmosphere;
import ua.zentix.incoming.util.Wind;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** След: масса на метр, оптика, Бриггс, всплытие, инверсия. */
class TrailModelTest {
    private static final Air DAY = new Air(new Wind(0, 10), Stability.B, 0.5), NIGHT = new Air(new Wind(0, 10), Stability.F, 0.5);

    private static Trail trail(WeaponType w, FlightPhase ph) {
        return ClientWeaponSpec.of(w).airframe(false).far().stage(ph).trail();
    }

    @Test
    void widthFollowsRichardsonUpToBriggsInTwoClasses() {
        Trail t = trail(WeaponType.ROCKET, FlightPhase.BOOST);
        for (Air air : new Air[]{DAY, NIGHT}) {
            double u = 4;
            for (double age : new double[]{1, 30, 300, 1200}) {
                double x = u * age, briggs = Stability.sigmaY(air.stability(), x) * Stability.sigmaZ(air.stability(), x);
                double rel = Math.min(TrailModel.RICHARDSON * TrailModel.dissipation(air.stability()) * age * age * age / 6, briggs);
                assertEquals(Math.sqrt(t.sigma0() * t.sigma0() + rel), TrailModel.sigma(t, age, air.stability(), u, 0), 1e-9);
            }
            // след — клуб: в первые минуты он уже усреднённого шлейфа (тот мотают вихри крупнее его)
            double x = u * 30;
            assertTrue(TrailModel.spread(30, air.stability(), u) < 0.25 * Stability.sigmaY(air.stability(), x) * Stability.sigmaZ(air.stability(), x));
        }
        assertEquals(5e-3, TrailModel.dissipation(Stability.B), 1e-12);
        assertEquals(Math.sqrt(1e-3 * 1e-4), TrailModel.dissipation(3.5), 1e-12, "между классами — по логарифму");
        // Бриггс: класс B у 1 км — σ_y 152, σ_z 120; F — 38, 12,3
        assertEquals(0.16 * 1000 / Math.sqrt(1.1), Stability.sigmaY(Stability.B, 1000), 1e-9);
        assertEquals(120, Stability.sigmaZ(Stability.B, 1000), 1e-9);
        assertEquals(0.016 * 1000 / 1.3, Stability.sigmaZ(Stability.F, 1000), 1e-9);
        assertTrue(TrailModel.sigma(t, 60, Stability.B, 4, 0) > 2 * TrailModel.sigma(t, 60, Stability.F, 4, 0), "днём расплывается быстрее");
    }

    @Test
    void pasquillTable() {
        assertEquals(Stability.A, Stability.of(1, 1, 0), 1e-9, "полдень, штиль");
        assertEquals(Stability.C, Stability.of(8, 1, 0), 1e-9, "полдень, сильный ветер");
        assertEquals(Stability.D, Stability.of(8, Math.sin(Math.toRadians(25)), 0), 1e-9, "низкое солнце, сильный ветер");
        assertEquals(Stability.F, Stability.of(1, -0.5, 0), 1e-9, "ясная ночь, штиль");
        assertEquals(Stability.D, Stability.of(1, -0.5, 1), 1e-9, "ночь в дождь");
        assertEquals(Stability.D, Stability.of(1, 1, 1), 1e-9, "день в дождь");
    }

    @Test
    void noTrailFromBatteryOrTurbofanByDay() {
        for (FlightPhase ph : FlightPhase.values()) {
            assertEquals(null, ClientWeaponSpec.of(WeaponType.LOITER).airframe(false).far().stage(ph).trail(), "батарея");
        }
        double[] out = new double[2];
        Trail fan = trail(WeaponType.MISSILE, FlightPhase.CRUISE), piston = trail(WeaponType.DRONE, FlightPhase.CRUISE);
        for (Trail t : new Trail[]{fan, piston}) {
            for (double speed : new double[]{40, 100, 250}) {
                TrailModel.birth(t, TrailModel.inverseSpeed(speed, 5), 100, 5, DAY, out);
                assertEquals(0, out[0], 1e-9, t.motor().name() + " на " + speed + " м/с");
            }
        }
    }

    @Test
    void boosterSmokeIsWhiteAndHangsForMinutes() {
        double[] out = new double[TrailModel.LOOK];
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE, WeaponType.ROCKET, WeaponType.NUKE}) {
            Trail t = trail(w, FlightPhase.BOOST);
            for (Air air : new Air[]{DAY, NIGHT}) {
                double u = air.wind().at(30, Stability.alpha(air.stability())), inv = TrailModel.inverseSpeed(60, u);
                TrailModel.birth(t, inv, 100, u, air, out);
                double life = out[0];
                TrailModel.look(t, 1, inv, 0, u, 100, air, out);
                System.out.printf(Locale.ROOT, "%s class %.0f: life %.0f s, width at 1 s %.2f m, opacity %.2f, white %.2f, rise at life %.1f m%n",
                        w, air.stability(), life, out[0], out[1], out[3], TrailModel.rise(t, life, inv, air.brunt()));
                assertTrue(life > (w == WeaponType.DRONE ? 3 : 20), w + " виден: " + life);
                assertTrue(out[1] > 0.1 && out[1] <= 1);
            }
        }
        assertTrue(Smoke.reflectance(Smoke.ALUMINA.albedo) > Smoke.reflectance(Smoke.SALTS.albedo));
        assertTrue(Smoke.reflectance(Smoke.SOOT.albedo) < 0.05, "сажа — чёрная");
    }

    @Test
    void ribbonIsDrawnAtHalfTheCentreOpacity() {
        Trail t = trail(WeaponType.ROCKET, FlightPhase.BOOST);
        double[] out = new double[TrailModel.LOOK];
        double age = 20, u = 4, inv = TrailModel.inverseSpeed(300, u);
        double sigma = TrailModel.sigma(t, age, Stability.D, u, 0);
        double tau = TrailModel.smoke(t, inv) / (Math.sqrt(2 * Math.PI) * sigma);
        TrailModel.look(t, age, inv, 0, u, 0, 0, 0.012, Stability.D, out);
        assertEquals(1 - Math.exp(-tau), out[1], 1e-9, "непрозрачность середины");
        // на краю ленты непрозрачность — половина середины
        double y = out[0] / 2, edge = 1 - Math.exp(-tau * Math.exp(-y * y / (2 * sigma * sigma)));
        assertEquals(out[1] / 2, edge, 1e-9);
    }

    @Test
    void slowerVehicleLaysThickerTrail() {
        Trail t = trail(WeaponType.ROCKET, FlightPhase.BOOST);
        assertEquals(2 * TrailModel.smoke(t, TrailModel.inverseSpeed(400, 5)), TrailModel.smoke(t, TrailModel.inverseSpeed(200, 5)), 1e-12);
        assertEquals(1 / 5.0, TrailModel.inverseSpeed(0, 5), 1e-12, "на пусковой продукты уносит ветер");
        assertTrue(TrailModel.laid(6, 5) && !TrailModel.laid(4, 5), "след — пока снаряд обгоняет ветер, медленнее — облако старта");
        assertTrue(!TrailModel.laid(0.5, 0), "в штиль — не медленнее самого слабого ветра шлейфа");
        assertEquals(1 / TrailModel.CALM, TrailModel.inverseSpeed(0, 0), 1e-12, "в штиль — не гуще, чем при самом слабом ветре шлейфа");
    }

    @Test
    void warmTrailRisesLessAtNight() {
        Trail t = trail(WeaponType.NUKE, FlightPhase.BOOST);
        double inv = TrailModel.inverseSpeed(100, 5);
        double day = TrailModel.rise(t, 600, inv, DAY.brunt()), night = TrailModel.rise(t, 600, inv, NIGHT.brunt());
        assertTrue(day > night && night > 0);
        // в начале — свободный линейный термик z = (3b·t²/2)^⅓
        double b = 9.80665 * t.motor().heatFlow() * inv / (StandardAtmosphere.density(0) * 1004 * StandardAtmosphere.T0) / (Math.PI * 0.25);
        assertEquals(Math.cbrt(1.5 * b), TrailModel.rise(t, 1, inv, 0.012), 0.02 * Math.cbrt(1.5 * b));
    }

    @Test
    void contrailThresholdAtTwoAltitudes() {
        Motor b2 = ClientWeaponSpec.of(WeaponType.BUNKER).airframe(false).far().stage(FlightPhase.CRUISE).motor();
        // керосин, η 0,3, 250 гПа: G = 1,25·1004·25 000/(0,622·43,2·10⁶·0,7) = 1,67 Па/K — T_LM ≈ −41,8 °C
        double g = Contrail.slope(b2, 25_000);
        assertEquals(1.25 * 1004 * 25_000 / (0.622 * 43.2e6 * 0.7), g, 1e-9);
        double tlm = Contrail.thresholdSaturated(g) - 273.15;
        assertEquals(-46.46 + 9.43 * Math.log(g - 0.053) + 0.72 * Math.pow(Math.log(g - 0.053), 2), tlm, 1e-9);
        assertTrue(tlm > -44 && tlm < -40, "T_LM " + tlm);
        // сухой воздух требует холоднее: у U = 0 T_LC = T_LM − e_w(T_LM)/G (Schumann 1996)
        double tlmK = Contrail.thresholdSaturated(g);
        assertEquals(tlmK - Contrail.waterSaturation(tlmK) / g, Contrail.threshold(g, 0), 0.05);
        double half = Contrail.threshold(g, 0.5);
        assertTrue(half < tlmK && half > Contrail.threshold(g, 0), "между сухим и насыщенным: " + half);
        // и в ясную погоду (U = 0,5) на 11 км след встаёт
        assertTrue(Contrail.forms(b2, StandardAtmosphere.temperature(11_000), StandardAtmosphere.pressure(11_000), 0.5));
        // 170 м над морем (сегодняшний B-2): +14 °C — никогда; 11 км (−56,5 °C), насыщено — встаёт
        double h1 = 170, h2 = 11_000;
        assertFalse(Contrail.forms(b2, StandardAtmosphere.temperature(h1), StandardAtmosphere.pressure(h1), 1));
        assertTrue(Contrail.forms(b2, StandardAtmosphere.temperature(h2), StandardAtmosphere.pressure(h2), 1));
        // во влажном воздухе на 11 км лёд пересыщен — след держится; в сухом — испаряется
        assertTrue(Contrail.iceDeficit(StandardAtmosphere.temperature(h2), 1) < 0);
        assertTrue(Contrail.iceDeficit(StandardAtmosphere.temperature(h2), 0.5) > 0);
    }

    @Test
    void bomberContrailOnlyWhereItForms() {
        Trail t = trail(WeaponType.BUNKER, FlightPhase.CRUISE);
        double[] out = new double[2];
        double inv = TrailModel.inverseSpeed(250, 10);
        TrailModel.birth(t, inv, Air.SEA + 170, 10, DAY, out);
        assertEquals(0, out[0], 1e-9, "на 170 м инверсии нет");
        Air humid = new Air(new Wind(0, 10), Stability.D, 1);
        TrailModel.birth(t, inv, Air.SEA + 11_000, 10, humid, out);
        assertTrue(out[1] > 600, "на 11 км в пересыщенном воздухе — стойкая инверсия: " + out[1]);
        Air dry = new Air(new Wind(0, 10), Stability.D, 0.3);
        TrailModel.birth(t, inv, Air.SEA + 11_000, 10, dry, out);
        assertTrue(out[1] < 60, "в сухом воздухе лёд испаряется: " + out[1]);
    }

    /**
     * Жалоба FX-report 23.10: след ЗРК днём пропадал к ~15 с (σ шлейфа Бриггса, усреднённого за минуты), в 3.4.1 тонкий
     * след держался. Маршевый двигатель на 280 м/с (игровая скорость ракеты ЗРК): днём виден десятки секунд, ночью — минуты.
     */
    @Test
    void samTrailHangsForTensOfSeconds() {
        Trail t = Trail.of(ClientWeaponSpec.SAM_SUSTAIN, 1);
        double[] out = new double[TrailModel.LOOK];
        Air day = new Air(new Wind(0, 5), Stability.B, 0.5), night = new Air(new Wind(0, 5), Stability.F, 0.5);
        double inv = TrailModel.inverseSpeed(280, 5);
        TrailModel.birth(t, inv, 150, 5, day, out);
        double dayLife = out[0];
        TrailModel.look(t, 15, inv, 0, 5, 150, day, out);
        double at15 = out[1];
        TrailModel.look(t, 30, inv, 0, 5, 150, day, out);
        double at30 = out[1];
        TrailModel.birth(t, inv, 150, 5, night, out);
        double nightLife = out[0];
        System.out.printf(Locale.ROOT, "SAM sustain: day life %.0f s, opacity 15 s %.2f, 30 s %.2f; night life %.0f s%n", dayLife, at15, at30, nightLife);
        assertTrue(dayLife > 60, "днём виден дольше минуты: " + dayLife);
        // прежде (σ Бриггса) — 0,06 и 0,02
        assertTrue(at15 > 0.2 && at30 > 0.1, "днём к 15–30 с ещё заметен: " + at15 + ", " + at30);
        assertTrue(nightLife > 300, "ночью — минуты: " + nightLife);
    }

    @Test
    void wanderNoiseHasUnitSpread() {
        java.util.Random r = new java.util.Random(7);
        double sum = 0, sum2 = 0;
        int n = 400_000;
        for (int i = 0; i < n; i++) {
            double v = TrailModel.noise(r.nextLong(), r.nextDouble() * 1000 - 500, r.nextDouble() * 1000);
            sum += v;
            sum2 += v * v;
        }
        double mean = sum / n, std = Math.sqrt(sum2 / n - mean * mean);
        assertEquals(0, mean, 0.01);
        assertEquals(1, std, 0.01);
    }

    /** Мотание оси и расплывание следа вместе — усреднённый шлейф Бриггса: Var(сдвига) = σ_y² − σ_r² (σ_z² − σ_r² по высоте). */
    @Test
    void bendsMakeUpTheAveragedPlume() {
        double[] out = new double[3];
        for (double c : new double[]{Stability.B, Stability.D, Stability.F}) {
            for (double age : new double[]{10, 60, 300}) {
                double u = 5, x = u * age, rel = TrailModel.spread(age, c, u);
                double side = Stability.sigmaY(c, x), up = Stability.sigmaZ(c, x);
                double vx = 0, vy = 0, vz = 0;
                int n = 20_000;
                java.util.Random r = new java.util.Random(11);
                for (int i = 0; i < n; i++) {
                    TrailModel.wander(r.nextLong(), r.nextDouble() * 5000, age, c, u, 1, Double.POSITIVE_INFINITY, out);
                    vx += out[0] * out[0];
                    vy += out[1] * out[1];
                    vz += out[2] * out[2];
                }
                assertEquals(side * side - rel, vx / n, 0.1 * (side * side - rel), "вбок, класс " + c + ", " + age + " с");
                assertEquals(side * side - rel, vz / n, 0.1 * (side * side - rel), "вбок, класс " + c + ", " + age + " с");
                assertEquals(up * up - rel, vy / n, 0.1 * (up * up - rel), "по высоте, класс " + c + ", " + age + " с");
            }
        }
        TrailModel.wander(1, 100, 0, Stability.B, 5, 1, Double.POSITIVE_INFINITY, out);
        assertEquals(0, Math.abs(out[0]) + Math.abs(out[1]) + Math.abs(out[2]), 0, "у сопла оси никто не гнул");
        // у земли ось не уходит в неё: по высоте — не больше трети высоты над землёй
        double worst = 0, low = 0;
        java.util.Random r = new java.util.Random(3);
        for (int i = 0; i < 20_000; i++) {
            TrailModel.wander(r.nextLong(), r.nextDouble() * 5000, 120, Stability.B, 5, 1, 12, out);
            worst = Math.max(worst, -out[1]);
            if (-out[1] > 6) low++;
        }
        assertTrue(worst < 0.8 * 12, "в землю: " + worst);
        assertTrue(low > 0, "у земли ось всё же гнётся вниз");
    }

    /**
     * Изгибы движутся плавно: в самом бурном воздухе (класс A, ветер 8 м/с) ось за тик сдвигается не дальше 0,5 м — 10 м/с,
     * восходящий поток конвекции, — а не рывками, с которых лента дрожит.
     */
    @Test
    void bendsMoveSmoothly() {
        double[] a = new double[3], b = new double[3];
        java.util.Random r = new java.util.Random(5);
        double worst = 0;
        for (int i = 0; i < 5000; i++) {
            long seed = r.nextLong();
            double s = r.nextDouble() * 3000, age = 0.05 + r.nextDouble() * 600;
            TrailModel.wander(seed, s, age, Stability.A, 8, 14, Double.POSITIVE_INFINITY, a);
            TrailModel.wander(seed, s, age + 0.05, Stability.A, 8, 14, Double.POSITIVE_INFINITY, b);
            double d = Math.sqrt(sq(a[0] - b[0]) + sq(a[1] - b[1]) + sq(a[2] - b[2]));
            worst = Math.max(worst, d);
        }
        assertTrue(worst < 0.5, "за тик: " + worst);
    }

    private static double sq(double v) {
        return v * v;
    }

    @Test
    void riseTableReachesItsTop() {
        // безразмерный подъём: вершина выше уровня равновесия s = 1
        double top = TrailModel.risen(1e9);
        assertTrue(top > 1 && top < 1.6, "вершина " + top);
        assertEquals(Math.cbrt(0.5 * 0.04), TrailModel.risen(0.2), 0.01);
    }
}
