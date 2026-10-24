package ua.zentix.incoming.client.fx.plume;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.BoosterLifetime;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Горячее сопло ускорителя: свет из остывания стенок, без скачков, цвет — по кривой чёрного тела. */
class NozzleGlowTest {
    private static final WeaponType[] BOOSTED = {WeaponType.DRONE, WeaponType.MISSILE};
    /** Шаг проверки, тиков. */
    private static final double STEP = 0.01;
    /** Свет тусклее этого не виден и ночью: порог 0,02 белого экрана при привыкании ×1 200, в единицах кадра. */
    private static final double VISIBLE = 0.02 / 1200;

    private static NozzleGlow glow(WeaponType w) {
        return ClientWeaponSpec.buildNozzleGlow(w).orElseThrow();
    }

    /** Независимые опорные точки JANAF C(ref): https://janaf.nist.gov/tables/C-002.html; Дж/(моль·K) → Дж/(кг·K). */
    @Test
    void graphiteHeatMatchesJanafMassUnits() {
        double[][] reference = {{250, 6.816}, {298.15, 8.517}, {400, 11.817}, {1000, 21.610},
                {2000, 25.094}, {3000, 26.611}, {3500, 27.225}};
        for (double[] row : reference) {
            assertEquals(row[1] / 0.012011, NozzleGlow.Material.GRAPHITE.heat(row[0]), 0.1,
                    "JANAF C(ref) при " + row[0] + " K, Дж/(кг·K)");
        }
    }

    /**
     * Путь сопла: поджиг, разгон со спадом, отделившийся ускоритель 20 с (его наибольшая жизнь в мире); fromTail — только
     * с начала спада тяги (там факел гаснет и сопло видно).
     */
    private static java.util.List<NozzleGlow.Shine> timeline(WeaponType w, boolean fromTail) {
        NozzleGlow g = glow(w);
        WeaponSpec.LaunchProfile lp = w.spec().airframe().launchProfile();
        double tail = w.spec().airframe().thrust().tail();
        java.util.List<NozzleGlow.Shine> out = new java.util.ArrayList<>();
        if (!fromTail) for (double a = 0; a < lp.ignitionTicks(); a += STEP) out.add(g.at(g.seconds(FlightPhase.IGNITION, a)));
        for (double a = fromTail ? lp.boostTicks() - tail : 0; a <= lp.boostTicks(); a += STEP) out.add(g.at(g.seconds(FlightPhase.BOOST, a)));
        for (double a = 0; a <= 400; a += STEP) out.add(g.spent(a));
        return out;
    }

    /** Температура стенок по настоящему времени — без ступенек (таблица и её шаг), и игровое время сшито по фазам. */
    @Test
    void temperatureHasNoSteps() {
        for (WeaponType w : BOOSTED) {
            NozzleGlow g = glow(w);
            NozzleGlow.Shine prev = g.at(0);
            for (double t = 0.0005; t <= g.burn() + NozzleGlow.AFTER; t += 0.0005) {
                NozzleGlow.Shine s = g.at(t);
                assertTrue(Math.abs(s.coneKelvin() - prev.coneKelvin()) < 5, w + " раструб на " + t + " с: " + prev.coneKelvin() + " → " + s.coneKelvin());
                assertTrue(Math.abs(s.throatKelvin() - prev.throatKelvin()) < 5, w + " горло на " + t + " с");
                prev = s;
            }
            WeaponSpec.LaunchProfile lp = w.spec().airframe().launchProfile();
            assertEquals(g.seconds(FlightPhase.IGNITION, lp.ignitionTicks()), g.seconds(FlightPhase.BOOST, 0), 1e-9, w + ": поджиг → разгон");
            double last = -1;
            for (double a = 0; a <= lp.boostTicks(); a += STEP) {
                double sec = g.seconds(FlightPhase.BOOST, a);
                assertTrue(sec >= last, w + ": время идёт вперёд");
                last = sec;
            }
        }
    }

    /** С начала спада тяги — факел гаснет, сопло на виду — до конца жизни ускорителя: свет без ступенек. */
    @Test
    void lightFadesWithoutSteps() {
        for (WeaponType w : BOOSTED) {
            java.util.List<NozzleGlow.Shine> line = timeline(w, true);
            for (int i = 1; i < line.size(); i++) {
                NozzleGlow.Shine a = line.get(i - 1), b = line.get(i);
                double la = a.coneLuminance() + a.throatLuminance(), lb = b.coneLuminance() + b.throatLuminance();
                // скачок — то, что видно: ночью глаз привыкает к темноте ×1 200 (Sight.adapted), порог видимости — 0,02 белого
                assertTrue(Math.abs(lb - la) <= 0.02 * Math.max(la, lb) + VISIBLE, w + " свет, шаг " + i + ": " + la + " → " + lb);
            }
        }
    }

    /**
     * Таблица — решение закона, а не сетки: с шагом втрое мельче по времени и по толщине те же температуры (у углепластика
     * шаг устойчивости 0,1–0,3 с, и спад тяги проходил за один-два шага — у шахеда раструб на отделении выходил на 170 K
     * горячее; с 20 узлами по толщине — на 125 K холоднее).
     */
    @Test
    void tableIsTheSolutionNotTheGrid() {
        for (WeaponType w : BOOSTED) {
            NozzleGlow g = glow(w);
            for (NozzleGlow.Wall wall : new NozzleGlow.Wall[]{g.throatWall, g.coneWall}) {
                // эталон мельче и по времени, и по толщине: узел толще глубины прогрева за спад занижал раструб на отделении
                double[] coarse = NozzleGlow.surface(wall, g.burn(), g.tail, 10, 1, 1), fine = NozzleGlow.surface(wall, g.burn(), g.tail, 10, 3, 3);
                // с 0,5 с: в первые доли секунды поджига прогретый слой тоньше узла (у углепластика за 0,05 с — 0,16 мм), но
                // стенку тогда закрывает полный факел, и к спаду разница уходит
                for (int i = (int) Math.round(0.5 / NozzleGlow.SAMPLE); i < coarse.length; i++) {
                    double t = i * NozzleGlow.SAMPLE;
                    assertEquals(fine[i], coarse[i], 15, w + " " + wall.material() + " на " + t + " с");
                }
            }
        }
    }

    /** Последний кадр и удаление: ночная адаптация и та же подача, что у PlumeRenderer, при любом времени падения. */
    @Test
    void cleanupCannotRemoveAVisibleHotNozzle() {
        for (WeaponType w : BOOSTED) {
            NozzleGlow g = glow(w);
            for (int end = BoosterLifetime.FADE_TICKS + BoosterLifetime.HIDDEN_TICKS; end <= BoosterLifetime.MAX_AGE; end++) {
                double previous = Double.NaN;
                for (double remaining = BoosterLifetime.HIDDEN_TICKS + BoosterLifetime.FADE_TICKS; remaining >= -1; remaining -= 0.05) {
                    NozzleGlow.Shine s = g.spent(end - remaining);
                    float visibility = BoosterLifetime.visibility(remaining);
                    float[] cone = Sight.onScreen(NozzleGlow.color(s.coneKelvin()), 1200 * s.coneLuminance(), visibility);
                    float[] throat = Sight.onScreen(NozzleGlow.color(s.throatKelvin()), 1200 * s.throatLuminance(), visibility);
                    double screen = Math.max(cone[0], throat[0]);
                    if (!Double.isNaN(previous)) assertTrue(Math.abs(screen - previous) < 0.02, w + " последний свет: " + remaining);
                    if (remaining <= 1) {
                        assertTrue(screen < 0.02, w + " виден в последнем кадре");
                        assertTrue((s.coneLuminance() + s.throatLuminance()) * visibility < VISIBLE, w + " свет при удалении");
                    }
                    previous = screen;
                }
                assertEquals(0, BoosterLifetime.visibility(0), w + " удаление");
            }
        }
    }

    /** Конец работы — без ступеньки в самом законе: поток в поверхность непрерывен по доле тяги. */
    @Test
    void burnoutIsNotASwitch() {
        for (WeaponType w : BOOSTED) {
            NozzleGlow g = glow(w);
            for (NozzleGlow.Wall wall : new NozzleGlow.Wall[]{g.throatWall, g.coneWall}) {
                for (double t = 400; t <= 3000; t += 100) {
                    double on = NozzleGlow.flux(wall, t, 1e-12, 288.15), off = NozzleGlow.flux(wall, t, 0, 288.15);
                    assertEquals(off, on, 1e-3 * Math.abs(off) + 1, w + " " + wall.material() + " при " + t + " K");
                }
            }
        }
    }

    @Test
    void separationKeepsTheSameNozzle() {
        for (WeaponType w : BOOSTED) {
            NozzleGlow g = glow(w);
            int boost = w.spec().airframe().launchProfile().boostTicks();
            NozzleGlow.Shine on = g.at(g.seconds(FlightPhase.BOOST, boost)), off = g.spent(0);
            assertEquals(on.coneKelvin(), off.coneKelvin(), 1e-6, w + ": отделение — тот же миг остывания");
            assertEquals(on.throatKelvin(), off.throatKelvin(), 1e-6, w.name());
            assertEquals(g.burn(), g.seconds(FlightPhase.BOOST, boost), 1e-9, w + ": к отделению — настоящее время работы");
        }
    }

    @Test
    void afterBurnoutItOnlyCoolsAndReddens() {
        for (WeaponType w : BOOSTED) {
            NozzleGlow g = glow(w);
            NozzleGlow.Shine prev = g.spent(0);
            for (double a = STEP; a <= 400; a += STEP) {
                NozzleGlow.Shine s = g.spent(a);
                assertTrue(s.coneKelvin() <= prev.coneKelvin() + 1e-9 && s.throatKelvin() <= prev.throatKelvin() + 1e-9, w + " остывает: " + a);
                assertTrue(s.coneLuminance() + s.throatLuminance() <= prev.coneLuminance() + prev.throatLuminance() + 1e-12, w + " гаснет: " + a);
                prev = s;
            }
        }
    }

    @Test
    void colourIsTheBlackBodyCurve() {
        int prevGreen = 256, prevBlue = 256;
        for (double k = 3200; k >= 700; k -= 10) {
            int c = NozzleGlow.color(k), ref = FlameModel.color(k);
            for (int sh = 0; sh <= 16; sh += 8) {
                assertTrue(Math.abs((c >> sh & 255) - (ref >> sh & 255)) <= 3, "цвет при " + k + " K: " + Integer.toHexString(c) + " против " + Integer.toHexString(ref));
            }
            // остывая, свет краснеет: красный — наибольший канал, зелёный и синий к нему только убывают
            assertEquals(255, c >> 16 & 255, "красный — наибольший при " + k);
            assertTrue((c >> 8 & 255) <= prevGreen && (c & 255) <= prevBlue, "краснеет при " + k);
            prevGreen = c >> 8 & 255;
            prevBlue = c & 255;
            assertEquals(ua.zentix.incoming.nuclear.model.BlackBody.luminance(k), NozzleGlow.luminance(k),
                    0.03 * ua.zentix.incoming.nuclear.model.BlackBody.luminance(k), "яркость при " + k);
        }
    }

    @Test
    void coolsAsTheNoteSays() {
        NozzleGlow m = glow(WeaponType.MISSILE), d = glow(WeaponType.DRONE);
        // к отделению раструб «Томагавка» раскалён до жёлто-белого, но холоднее газа
        assertTrue(m.spent(0).coneKelvin() > 2000 && m.spent(0).coneKelvin() < m.motor().temperature(), "раструб у отделения: " + m.spent(0).coneKelvin());
        // раструб гаснет (ниже точки Дрейпера, ~800 K) за десятки секунд, массивное горло держит красный дольше
        assertTrue(dark(m, true) > 10 && dark(m, true) < 60, "раструб ракеты темнеет за " + dark(m, true) + " с");
        assertTrue(m.spent(20 * 20).throatKelvin() > 1000, "горло ракеты через 20 с ещё красное");
        // у шахеда работа короче (2,5 с), сопло меньше — за секунды
        assertTrue(dark(d, true) > 2 && dark(d, true) < 15, "раструб шахеда темнеет за " + dark(d, true) + " с");
    }

    /** Секунд после работы до точки Дрейпера у раструба (cone) или горла. */
    private static double dark(NozzleGlow g, boolean cone) {
        for (double s = 0; s < NozzleGlow.AFTER; s += 0.1) {
            NozzleGlow.Shine sh = g.spent(s * 20);
            if ((cone ? sh.coneKelvin() : sh.throatKelvin()) < 800) return s;
        }
        return NozzleGlow.AFTER;
    }

    @Test
    void throatFromTheMotorsFlow() {
        for (WeaponType w : BOOSTED) {
            Motor m = glow(w).motor();
            double rt = NozzleGlow.throatRadius(m);
            assertEquals(m.exhaust(), m.chamberPressure() * Math.PI * rt * rt * m.nozzles() / NozzleGlow.cStar(m), 1e-6 * m.exhaust(), w + ": ṁ = p·A/c*");
            assertTrue(rt < m.exitRadius(), w + ": горло уже среза");
        }
    }

    @Test
    void throatIsSeenOnlyThroughTheExit() {
        NozzleGlow.Shine s = glow(WeaponType.MISSILE).spent(0);
        assertEquals(1, s.throatSeen(1), 1e-9, "строго сзади — всё горло");
        double prev = 1;
        for (double deg = 0; deg <= 90; deg += 1) {
            double seen = s.throatSeen(Math.cos(Math.toRadians(deg)));
            assertTrue(seen <= prev + 1e-12 && seen >= 0, "убывает с углом: " + deg);
            prev = seen;
        }
        assertEquals(0, s.throatSeen(Math.cos(Math.toRadians(60))), 1e-9, "сбоку горло закрыто стенкой раструба");
        assertEquals(0, s.throatSeen(-0.5), "спереди среза не видно");
    }
}
