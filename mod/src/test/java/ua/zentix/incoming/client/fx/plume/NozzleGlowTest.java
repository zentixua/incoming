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

/** Hot accelerator nozzle: light from cooling walls, without jumps, color - along the black body curve. */
class NozzleGlowTest {
    private static final WeaponType[] BOOSTED = {WeaponType.DRONE, WeaponType.MISSILE};
    /** Verification step, tick. */
    private static final double STEP = 0.01;
    /** Light dimmer than this is not visible at night either: the threshold 0,02 of a white screen during habituation ×1 200, in frame units. */
    private static final double VISIBLE = 0.02 / 1200;

    private static NozzleGlow glow(WeaponType w) {
        return ClientWeaponSpec.buildNozzleGlow(w).orElseThrow();
    }

    /** Independent reference points JANAF C(ref): https://janaf.nist.gov/tables/C-002.html; J/(mol·K) → J/(kg·K). */
    @Test
    void graphiteHeatMatchesJanafMassUnits() {
        double[][] reference = {{250, 6.816}, {298.15, 8.517}, {400, 11.817}, {1000, 21.610},
                {2000, 25.094}, {3000, 26.611}, {3500, 27.225}};
        for (double[] row : reference) {
            assertEquals(row[1] / 0.012011, NozzleGlow.Material.GRAPHITE.heat(row[0]), 0.1,
                    "JANAF C(ref) at " + row[0] + " K, J/(kg K)");
        }
    }

    /**
     * Nozzle path: ignition, acceleration with decay, separated accelerator 20 s (its longest life in the world); fromTail - only
     *  from the beginning of the thrust decline (there the torch goes out and the nozzle is visible).
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

    /** The temperature of the walls at the present time is without steps (table and its step), and the game time is stitched in phases. */
    @Test
    void temperatureHasNoSteps() {
        for (WeaponType w : BOOSTED) {
            NozzleGlow g = glow(w);
            NozzleGlow.Shine prev = g.at(0);
            for (double t = 0.0005; t <= g.burn() + NozzleGlow.AFTER; t += 0.0005) {
                NozzleGlow.Shine s = g.at(t);
                assertTrue(Math.abs(s.coneKelvin() - prev.coneKelvin()) < 5, w + " bell on " + t + " from: " + prev.coneKelvin() + " → " + s.coneKelvin());
                assertTrue(Math.abs(s.throatKelvin() - prev.throatKelvin()) < 5, w + " throat on " + t + " s");
                prev = s;
            }
            WeaponSpec.LaunchProfile lp = w.spec().airframe().launchProfile();
            assertEquals(g.seconds(FlightPhase.IGNITION, lp.ignitionTicks()), g.seconds(FlightPhase.BOOST, 0), 1e-9, w + ": ignition → acceleration");
            double last = -1;
            for (double a = 0; a <= lp.boostTicks(); a += STEP) {
                double sec = g.seconds(FlightPhase.BOOST, a);
                assertTrue(sec >= last, w + ": time moves forward");
                last = sec;
            }
        }
    }

    /** From the beginning of the decline in thrust - the torch goes out, the nozzle is in sight - until the end of the accelerator's life: light without steps. */
    @Test
    void lightFadesWithoutSteps() {
        for (WeaponType w : BOOSTED) {
            java.util.List<NozzleGlow.Shine> line = timeline(w, true);
            for (int i = 1; i < line.size(); i++) {
                NozzleGlow.Shine a = line.get(i - 1), b = line.get(i);
                double la = a.coneLuminance() + a.throatLuminance(), lb = b.coneLuminance() + b.throatLuminance();
                // a jump is what is visible: at night the eye gets used to the darkness ×1 200 (Sight.adapted), visibility threshold - 0,02 white
                assertTrue(Math.abs(lb - la) <= 0.02 * Math.max(la, lb) + VISIBLE, w + " light, step " + i + ": " + la + " → " + lb);
            }
        }
    }

    /**
     * The table is a solution to a law, not a grid: with a step three times finer in time and in thickness the same temperatures (for carbon fiber
     *  the stability step is 0,1–0,3 s, and the decline in thrust took place in one or two steps - for Shahed the bell at the compartment went to 170 K
     *  is hotter; with 20 nodes in thickness - 125 K is colder).
     */
    @Test
    void tableIsTheSolutionNotTheGrid() {
        for (WeaponType w : BOOSTED) {
            NozzleGlow g = glow(w);
            for (NozzleGlow.Wall wall : new NozzleGlow.Wall[]{g.throatWall, g.coneWall}) {
                // the standard is smaller both in time and in thickness: the unit is thicker than the heating depth for the decline, the bell at the compartment was underestimated
                double[] coarse = NozzleGlow.surface(wall, g.burn(), g.tail, 10, 1, 1), fine = NozzleGlow.surface(wall, g.burn(), g.tail, 10, 3, 3);
                // with 0,5 s: in the first fractions of a second of ignition, the heated layer is thinner than the assembly (for carbon fiber for 0,05 s - 0,16 mm), but the
                //  wall is then covered by a full torch, and by the time of decline the difference goes away
                for (int i = (int) Math.round(0.5 / NozzleGlow.SAMPLE); i < coarse.length; i++) {
                    double t = i * NozzleGlow.SAMPLE;
                    assertEquals(fine[i], coarse[i], 15, w + " " + wall.material() + " on " + t + " s");
                }
            }
        }
    }

    /** Last frame and removal: night adaptation and the same presentation as PlumeRenderer, at any fall time. */
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
                    if (!Double.isNaN(previous)) assertTrue(Math.abs(screen - previous) < 0.02, w + " last light: " + remaining);
                    if (remaining <= 1) {
                        assertTrue(screen < 0.02, w + " visible in the last frame");
                        assertTrue((s.coneLuminance() + s.throatLuminance()) * visibility < VISIBLE, w + " light when moving away");
                    }
                    previous = screen;
                }
                assertEquals(0, BoosterLifetime.visibility(0), w + " deletion");
            }
        }
    }

    /** The end of the work is without a step in the law itself: the flow into the surface is continuous according to the thrust fraction. */
    @Test
    void burnoutIsNotASwitch() {
        for (WeaponType w : BOOSTED) {
            NozzleGlow g = glow(w);
            for (NozzleGlow.Wall wall : new NozzleGlow.Wall[]{g.throatWall, g.coneWall}) {
                for (double t = 400; t <= 3000; t += 100) {
                    double on = NozzleGlow.flux(wall, t, 1e-12, 288.15), off = NozzleGlow.flux(wall, t, 0, 288.15);
                    assertEquals(off, on, 1e-3 * Math.abs(off) + 1, w + " " + wall.material() + " at " + t + " K");
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
            assertEquals(on.coneKelvin(), off.coneKelvin(), 1e-6, w + ": separation - the same moment of cooling");
            assertEquals(on.throatKelvin(), off.throatKelvin(), 1e-6, w.name());
            assertEquals(g.burn(), g.seconds(FlightPhase.BOOST, boost), 1e-9, w + ": to the department - current work time");
        }
    }

    @Test
    void afterBurnoutItOnlyCoolsAndReddens() {
        for (WeaponType w : BOOSTED) {
            NozzleGlow g = glow(w);
            NozzleGlow.Shine prev = g.spent(0);
            for (double a = STEP; a <= 400; a += STEP) {
                NozzleGlow.Shine s = g.spent(a);
                assertTrue(s.coneKelvin() <= prev.coneKelvin() + 1e-9 && s.throatKelvin() <= prev.throatKelvin() + 1e-9, w + " cools down: " + a);
                assertTrue(s.coneLuminance() + s.throatLuminance() <= prev.coneLuminance() + prev.throatLuminance() + 1e-12, w + " goes out: " + a);
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
                assertTrue(Math.abs((c >> sh & 255) - (ref >> sh & 255)) <= 3, "color at " + k + " K: " + Integer.toHexString(c) + " against " + Integer.toHexString(ref));
            }
            // as it cools down, the light turns red: red is the largest channel, green and blue only decrease towards it
            assertEquals(255, c >> 16 & 255, "red - highest at " + k);
            assertTrue((c >> 8 & 255) <= prevGreen && (c & 255) <= prevBlue, "blushes when " + k);
            prevGreen = c >> 8 & 255;
            prevBlue = c & 255;
            assertEquals(ua.zentix.incoming.nuclear.model.BlackBody.luminance(k), NozzleGlow.luminance(k),
                    0.03 * ua.zentix.incoming.nuclear.model.BlackBody.luminance(k), "brightness at " + k);
        }
    }

    @Test
    void coolsAsTheNoteSays() {
        NozzleGlow m = glow(WeaponType.MISSILE), d = glow(WeaponType.DRONE);
        // to the compartment, the Tomahawk bell is heated to yellow-white, but colder than gas
        assertTrue(m.spent(0).coneKelvin() > 2000 && m.spent(0).coneKelvin() < m.motor().temperature(), "bell at the compartment: " + m.spent(0).coneKelvin());
        // the bell goes out (below the Draper point, ~800 K) in tens of seconds, the massive throat holds red longer
        assertTrue(dark(m, true) > 10 && dark(m, true) < 60, "the bell of the rocket darkens behind " + dark(m, true) + " s");
        assertTrue(m.spent(20 * 20).throatKelvin() > 1000, "rocket throat through 20 s still red");
        // shahed has shorter work (2,5 s), smaller nozzle - in seconds
        assertTrue(dark(d, true) > 2 && dark(d, true) < 15, "shahed's bell darkens behind " + dark(d, true) + " s");
    }

    /** Seconds after working to the Draper point at the bell (cone) or throat. */
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
            assertTrue(rt < m.exitRadius(), w + ": the throat is already cut");
        }
    }

    @Test
    void throatIsSeenOnlyThroughTheExit() {
        NozzleGlow.Shine s = glow(WeaponType.MISSILE).spent(0);
        assertEquals(1, s.throatSeen(1), 1e-9, "strictly behind - all throat");
        double prev = 1;
        for (double deg = 0; deg <= 90; deg += 1) {
            double seen = s.throatSeen(Math.cos(Math.toRadians(deg)));
            assertTrue(seen <= prev + 1e-12 && seen >= 0, "decreases with angle: " + deg);
            prev = seen;
        }
        assertEquals(0, s.throatSeen(Math.cos(Math.toRadians(60))), 1e-9, "the side of the throat is closed by the wall of the bell");
        assertEquals(0, s.throatSeen(-0.5), "the cut is not visible from the front");
    }
}
