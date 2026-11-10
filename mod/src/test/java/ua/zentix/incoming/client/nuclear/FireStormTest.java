package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.ThermalModel;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Firestorm ({@link FireStorm}): sources on the grid - according to the law of ignition of ruins and sound, with flammability of areas; ignition -
 *  with a flash, the wave knocks down part of the fire; flames and smoke flare up, subside and burn out over hours of the detonation clock; map; tornadoes are rare, short,
 *  with strong foci.
 */
class FireStormTest {
    private static final Detonation AIR = new Detonation(1, new Vec3(0, 64 + 500, 0), 64, 15, false, 0, 0.5f, 6, 20_000, 7, 1, false, 1);
    private static final Detonation GROUND = new Detonation(2, new Vec3(0, 64, 0), 64, 15, true, 0, 0.5f, 6, 20_000, 9, 1, false, 1);
    private static final Detonation SMALL = new Detonation(3, new Vec3(0, 64, 0), 64, 1, true, 0, 0.5f, 6, 20_000, 11, 1, false, 1);

    private static FireStorm.Grid grid(Detonation d) {
        return FireStorm.build(d, new PallField(d).fireReach());
    }

    /** The middle of the cell {@code c} from the epicenter, blocks. */
    private static double[] centre(FireStorm.Grid g, int c) {
        double half = g.side() * g.cell() / 2;
        return new double[]{(c % g.side() + 0.5) * g.cell() - half, (c / g.side() + 0.5) * g.cell() - half};
    }

    @Test
    void fullYieldEnvelopeIsCoveredWithoutGrowingSourceColumnsOrMapStorage() {
        for (double yield : new double[]{15, 100, 1000}) for (boolean surface : new boolean[]{false, true}) {
            double height = surface ? 0 : ua.zentix.incoming.nuclear.model.Yield.optimalBurstHeight(yield);
            Detonation d = new Detonation(91, new Vec3(300000, 64 + height, -400000), 64, yield, surface,
                    0, 0.5f, 6, 20000, 42, 1, false, .25f);
            double reach = new PallField(d).fireReach();
            FireStorm.Grid g = grid(d);
            assertTrue(g.side() <= FireStorm.MAX_SIDE && g.side() * g.cell() / 2 >= reach * 1.05,
                    "clipped ignition coverage at " + yield + " kt");
            assertEquals(32, FireStorm.sourceCell(g.cell()), 1e-9, "larger map cells inflated visible columns");
            assertEquals(FireStorm.width(20, 32), FireStorm.width(20, FireStorm.sourceCell(g.cell())), 1e-9);
            if (yield == 1000) {
                boolean[] quadrants = new boolean[4];
                for (int c : g.index()) {
                    double[] p = centre(g, c);
                    if (Math.max(Math.abs(p[0]), Math.abs(p[1])) > 4096)
                        quadrants[(p[0] < 0 ? 1 : 0) + (p[1] < 0 ? 2 : 0)] = true;
                }
                for (boolean present : quadrants) assertTrue(present, "former clipped quadrant has no sources");
            }
        }
    }

    @Test
    void sourcesFollowTheIgnitionLawInsideTheZone() {
        for (Detonation d : List.of(AIR, GROUND)) {
            double reach = new PallField(d).fireReach();
            FireStorm.Grid g = grid(d);
            assertEquals(FireStorm.CELL, g.cell(), 1e-9, "at 15 kt - cell 32 block");
            assertTrue(g.side() <= FireStorm.MAX_SIDE && g.side() * g.cell() / 2 >= reach, "the map covers the area: " + g.side());
            assertTrue(g.index().length > 5000, "cells " + g.index().length);
            double inner = d.surface() ? 0.8 * d.fireballRadius() : 0;
            // where everything is set on fire (q ≥ 20 for any district), average strength - the share of “there is something to burn” minus
            //  knocked down by the wave: districts and cells on average ×1
            double sum = 0, expected = 0, sumBefore = 0, expectedBefore = 0;
            int n = 0;
            for (int k = 0; k < g.index().length; k++) {
                double[] xz = centre(g, g.index()[k]);
                double r = Math.hypot(xz[0], xz[1]);
                assertTrue(r <= reach * 1.05 + 1e-6 && r >= inner - 1e-6, "cell out of zone: " + r);
                assertTrue(g.before()[k] >= 0.02f, "weak cells are discarded");
                assertTrue(g.strength()[k] <= g.before()[k] && g.strength()[k] >= FireField.BLOWN_KEEP * g.before()[k] - 1e-6, "the wave knocks down no more than half");
                assertEquals(Math.pow(g.strength()[k], FireStorm.COLUMN_POWER), g.column()[k], 1e-5, "pillar - strength to degree");
                Vec3 p = new Vec3(d.burst().x + xz[0], d.groundY() + 1, d.burst().z + xz[1]);
                if (d.fluence(p) * Math.exp(-3 * FireStorm.SIGMA_EDGE) >= ThermalModel.igniteAll(d.yieldKt())) {
                    sum += g.strength()[k];
                    expected += FireField.SHARE * (1 - 0.5 * FireStorm.smooth(1.5, 2.5, d.psi(p)));
                    sumBefore += g.before()[k];
                    expectedBefore += FireField.SHARE;
                    n++;
                }
            }
            assertTrue(n > 200, "cells where everything is set on fire: " + n);
            assertTrue(sum / expected > 0.8 && sum / expected < 1.25, "average force against the law: " + sum / expected);
            assertTrue(sumBefore / expectedBefore > 0.8 && sumBefore / expectedBefore < 1.25, "before the wave - against the law: " + sumBefore / expectedBefore);
            // the edge of the zone is torn: not all cells are burning near the ignition border
            double edgeIn = 0, edgeAll = 0;
            for (int j = 0; j < g.side(); j++) {
                for (int i = 0; i < g.side(); i++) {
                    double[] xz = centre(g, j * g.side() + i);
                    double r = Math.hypot(xz[0], xz[1]);
                    if (r < 0.9 * reach || r > reach) continue;
                    edgeAll++;
                    if (Arrays.binarySearch(g.index(), j * g.side() + i) >= 0) edgeIn++;
                }
            }
            assertTrue(edgeIn > 0.05 * edgeAll && edgeIn < 0.95 * edgeAll, "edge: lit " + edgeIn + " from " + edgeAll);
        }
    }

    @Test
    void sourcesAreDeterministicPerDetonation() {
        FireStorm.Grid a = grid(AIR), b = grid(AIR), c = grid(new Detonation(4, AIR.burst(), 64, 15, false, 0, 0.5f, 6, 20_000, 8, 1, false, 1));
        assertArrayEquals(a.index(), b.index());
        assertArrayEquals(a.strength(), b.strength());
        assertArrayEquals(a.before(), b.before());
        assertArrayEquals(a.start(), b.start());
        assertArrayEquals(a.arrival(), b.arrival());
        assertEquals(a.whirls(), b.whirls());
        assertFalse(Arrays.equals(a.strength(), c.strength()), "another seed has other areas");
    }

    @Test
    void cellsIgniteWithTheFlash() {
        FireStorm.Grid g = grid(AIR);
        int early = 0;
        for (int k = 0; k < g.index().length; k++) {
            double[] xz = centre(g, g.index()[k]);
            double wave = AIR.arrivalTicks(Math.hypot(Math.hypot(xz[0], xz[1]), AIR.burst().y - AIR.groundY()));
            Vec3 p = new Vec3(AIR.burst().x + xz[0], AIR.groundY() + 1, AIR.burst().z + xz[1]);
            double flash = AIR.flashTicks(AIR.fluence(p), ThermalModel.ignite(AIR.yieldKt()));
            assertTrue(flash >= 0 && flash <= AIR.pulseTicks() + 1e-6, "light reaches a threshold per pulse: " + flash);
            assertTrue(g.start()[k] >= flash - 1e-3 && g.start()[k] <= flash + 20 * FireStorm.IGNITE_SPREAD_S + 1, "arson " + g.start()[k] + ", flash " + flash);
            assertEquals(wave, g.arrival()[k], 1e-2, "wave");
            if (g.start()[k] < wave) early++;
            assertTrue(g.peak()[k] >= FireStorm.GROW_S + 0.7 * FireStorm.PEAK_S - 1e-3 && g.peak()[k] <= FireStorm.GROW_S + 1.3 * FireStorm.PEAK_S + 1e-3);
        }
        assertTrue(early > g.index().length / 10, "cells lit up before the wave: " + early + " from " + g.index().length);
    }

    @Test
    void waveBlowsOutPartOfTheFireThatTheFlashLit() {
        FireStorm.Grid g = grid(AIR);
        float[] map = new float[g.side() * g.side() * FireStorm.CHANNELS];
        int checked = 0;
        for (int k = 0; k < g.index().length && checked < 50; k++) {
            if (g.before()[k] <= g.strength()[k] || g.start()[k] + 40 >= g.arrival()[k]) continue;
            checked++;
            assertEquals(g.before()[k], g.strengthAt(k, g.arrival()[k] - 1), 1e-6, "until the wave - everything is lit");
            assertEquals(g.strength()[k], g.strengthAt(k, g.arrival()[k] + FireStorm.BLOW_TICKS), 1e-6, "after - without being shot down");
            double mid = g.strengthAt(k, g.arrival()[k] + FireStorm.BLOW_TICKS / 2);
            assertTrue(mid < g.before()[k] && mid > g.strength()[k], "knocks down smoothly: " + mid);
            double ticks = (g.start()[k] + g.arrival()[k]) / 2;
            FireStorm.fill(g, ticks, null, map);
            int c = g.index()[k];
            double age = (ticks - g.start()[k]) / 20;
            assertEquals(g.before()[k] * FireStorm.flame(age, g.peak()[k]), map[4 * c], 1e-5);
            assertTrue(map[4 * c] > 0, "burns until the wave");
        }
        assertTrue(checked > 0, "there are cells that the wave knocks down already burning");
    }

    @Test
    void smallScaleKeepsEnoughCells() {
        double reach = new PallField(SMALL).fireReach();
        FireStorm.Grid g = grid(SMALL);
        assertTrue(g.cell() <= 2 * reach / 24 + 1e-9 && g.cell() >= 2, "cell " + g.cell());
        assertTrue(g.index().length > 100, "cells " + g.index().length);
        assertTrue(g.whirls().size() >= 1, "a vortex at 1 kt too");
    }

    @Test
    void flameGrowsPeaksAndDiesDown() {
        double peak = FireStorm.GROW_S + FireStorm.PEAK_S;
        assertEquals(0, FireStorm.flame(0, peak), 1e-12);
        // t-squared growth: a quarter at half the growth time, full at its end
        assertEquals(0.25, FireStorm.flame(FireStorm.GROW_S / 2, peak), 1e-9);
        assertEquals(1, FireStorm.flame(FireStorm.GROW_S, peak), 1e-9);
        double last = 0;
        for (double t = 1; t <= peak; t += 1) {
            double f = FireStorm.flame(t, peak);
            assertTrue(f >= last - 1e-12, "does not subside until the peak: " + t);
            last = f;
        }
        for (double t = peak + 1; t < peak + 3 * FireStorm.DECAY_S; t += 10) {
            double f = FireStorm.flame(t, peak);
            assertTrue(f <= last + 1e-12 && f >= FireStorm.EMBERS - 1e-9, "subsides to decay: " + t);
            last = f;
            assertTrue(FireStorm.smoke(t, peak) >= f, "smoke - longer than flame");
        }
        assertEquals(FireStorm.EMBERS + (1 - FireStorm.EMBERS) * Math.exp(-1), FireStorm.flame(peak + FireStorm.DECAY_S, peak), 1e-9);
    }

    /**
     * 10.11.2026, 500 kt ground burst seen from 5.3 km: the storm smoke stood 840 m tall within seconds and hid the mushroom
     * before the wave arrived. Fires are young then: until the wave crosses a cell, its smoke is a few per mille of full
     * (real wave speed: 10.8 s there; game speed 0.35: 40 s), and no column stands above the height its head could climb.
     */
    @Test
    void noStormSmokeBeforeTheFiresGrow() {
        Detonation d = new Detonation(2, new Vec3(-3350, 117, 3076), 116, 500, true, 0, 0.5f, 6, 20_000, 42, 1, false, 0.35f);
        FireStorm.Grid g = grid(d);
        float[] map = new float[g.side() * g.side() * FireStorm.CHANNELS];
        for (double seconds : new double[]{10, 20, 40}) {
            double ticks = 20 * seconds;
            FireStorm.fill(g, ticks, null, map);
            double top = d.blocks(PallField.hazeTopM(500));
            for (int k = 0; k < g.index().length; k++) {
                int c = g.index()[k];
                double age = (ticks - g.start()[k]) / 20;
                double strongest = Math.pow(Math.max(g.before()[k], g.strength()[k]), FireStorm.COLUMN_POWER);
                assertTrue(map[4 * c + 1] <= strongest * Math.pow(seconds / FireStorm.GROW_S, 2) + 1e-7, "smoke at " + seconds + " s");
                // the column head is no higher than its updraft could carry smoke since ignition
                double head = map[4 * c + 1] <= 0 ? 0 : top * map[4 * c + 2] / map[4 * c + 1];
                assertTrue(head <= d.blocks(FireStorm.UPDRAFT_MS * Math.max(0, age)) + 1e-3 * top, "column head " + head + " at age " + age);
                assertEquals(0, FireStorm.feedAt(head + 2, top, map[4 * c + 1], map[4 * c + 2]), 1e-9, "no smoke above the head");
            }
        }
        // the column reaches its top after top / updraft and stands there afterwards
        double topM = PallField.hazeTopM(500);
        assertEquals(1, FireStorm.risen(topM / FireStorm.UPDRAFT_MS, topM), 1e-9);
        assertEquals(0.5, FireStorm.risen(topM / FireStorm.UPDRAFT_MS / 2, topM), 1e-9);
    }

    @Test
    void mapIsTheLawAndBurnsOut() {
        FireStorm.Grid g = grid(AIR);
        float[] map = new float[g.side() * g.side() * FireStorm.CHANNELS];
        double ticks = 20 * 300;
        FireStorm.fill(g, ticks, null, map);
        boolean[] cell = new boolean[g.side() * g.side()];
        for (int k = 0; k < g.index().length; k++) {
            int c = g.index()[k];
            cell[c] = true;
            double age = (ticks - g.start()[k]) / 20;
            assertEquals(g.strength()[k] * FireStorm.flame(age, g.peak()[k]), map[4 * c], 1e-5);
            assertEquals(g.column()[k] * FireStorm.smoke(age, g.peak()[k]), map[4 * c + 1], 1e-5);
            assertTrue(map[4 * c + 2] <= map[4 * c + 1] + 1e-7, "rise - share presentation");
        }
        for (int c = 0; c < cell.length; c++) if (!cell[c]) assertEquals(0, map[4 * c] + map[4 * c + 1] + map[4 * c + 2], 0, "outside the zone is empty");
        // before the flash - nothing; once the city has burnt out (detonation clock) - nothing
        FireStorm.fill(g, 0, null, map);
        for (float v : map) assertEquals(0, v, 0);
        FireStorm.fill(g, 20 * FireStorm.OUT_S + 20 * 600, null, map);
        for (float v : map) assertEquals(0, v, 0);
    }

    /** Water beneath ground zero resolved at the burst ({@link NukeWater#resolve}): {@code under} 0 land, 1 sea. */
    private static BurstWater water(double under) {
        BurstWater w = new BurstWater();
        w.set(under, 0);
        w.settle(NukeWater.Source.TILES);
        return w;
    }

    /**
     * Unseen cells follow ground zero as it was at the burst, not as it reads later: a land burst whose crater later reads as
     * water (it fills, or its map tile changes) keeps the whole unseen storm burning. Before ground zero is resolved, nothing
     * is decided from a guess.
     */
    @Test
    void unseenCellsKeepTheBurstWaterWhenGroundZeroLaterReadsWater() throws InterruptedException {
        FireStorm s = new FireStorm(GROUND, new PallField(GROUND));
        for (int i = 0; i < 600 && s.grid() == null; i++) Thread.sleep(10);
        FireStorm.Grid g = s.grid();
        assertNotNull(g);
        FireStorm.Surface unknown = (x, z) -> -1;
        int sweep = g.index().length / FireStorm.SURVEY_CELLS + 1;
        // a zone cell away from ground zero
        double[] far = centre(g, g.index()[g.index().length / 4]);
        BurstWater w = new BurstWater();
        w.set(1, 0);
        for (int i = 0; i < sweep; i++) s.survey(unknown, w);
        assertEquals(1, s.dryAt(GROUND.burst().x + far[0], GROUND.burst().z + far[1]), 0, "an unresolved guess decides nothing");
        w.set(0, 0);
        w.settle(NukeWater.Source.TILES);
        for (int i = 0; i < sweep; i++) s.survey(unknown, w);
        assertTrue(s.refresh(20 * 180));
        double before = s.burning();
        assertTrue(before > 0);
        // the crater at ground zero now reads as water: the resolved burst water stays, the storm keeps burning
        w.set(1, 1);
        assertEquals(0, w.under(), 0, "burst water is fixed once settled");
        for (int i = 0; i < 3 * sweep; i++) s.survey(unknown, w);
        assertEquals(1, s.dryAt(GROUND.burst().x + far[0], GROUND.burst().z + far[1]), 0);
        s.refresh(20 * 181 + FireStorm.FILL_SLOW);
        assertEquals(before, s.burning(), before * 0.05, "the unseen storm does not go out");
    }

    @Test
    void waterIceAndSnowThatTheClientKnowsDoNotBurn() throws InterruptedException {
        FireStorm s = new FireStorm(AIR, new PallField(AIR));
        for (int i = 0; i < 600 && s.grid() == null; i++) Thread.sleep(10);
        FireStorm.Grid g = s.grid();
        assertNotNull(g);
        // to the west of the epicenter - the sea, at the very epicenter - we don’t know, to the east - land
        FireStorm.Surface surface = (x, z) -> Math.abs(x - AIR.burst().x) < 100 ? -1 : x < AIR.burst().x ? 1 : 0;
        for (int i = 0; i <= g.index().length / FireStorm.SURVEY_CELLS; i++) s.survey(surface, water(0));
        assertEquals(0, s.dryAt(AIR.burst().x - 500, AIR.burst().z), 0);
        assertEquals(1, s.dryAt(AIR.burst().x + 500, AIR.burst().z), 0);
        assertEquals(1, s.dryAt(AIR.burst().x + 5, AIR.burst().z), 0, "we don’t know, there is land under the epicenter - it burns, as in the model");
        // the player left, the chunks were unloaded: the sea seen remains the sea
        for (int i = 0; i <= g.index().length / FireStorm.SURVEY_CELLS; i++) s.survey((x, z) -> -1, water(0));
        assertEquals(0, s.dryAt(AIR.burst().x - 500, AIR.burst().z), 0, "saw the sea - it doesn’t burn and then");
        // there is a sea under the epicenter: where we don’t know anything, it doesn’t burn (an explosion over the sea in the distance)
        FireStorm sea = new FireStorm(AIR, new PallField(AIR));
        for (int i = 0; i < 600 && sea.grid() == null; i++) Thread.sleep(10);
        for (int i = 0; i <= g.index().length / FireStorm.SURVEY_CELLS; i++) sea.survey(surface, water(1));
        assertEquals(0, sea.dryAt(AIR.burst().x + 5, AIR.burst().z), 0, "we don’t know, the sea is under the epicenter - it doesn’t burn");
        assertEquals(1, sea.dryAt(AIR.burst().x + 500, AIR.burst().z), 0, "the land we know is burning");
        // map at the 3rd minute
        assertTrue(s.refresh(20 * 180));
        float[] map = s.map();
        double half = g.side() * g.cell() / 2, west = 0, east = 0, middle = 0;
        for (int c = 0; c < g.side() * g.side(); c++) {
            double x = (c % g.side() + 0.5) * g.cell() - half;
            double v = map[4 * c] + map[4 * c + 1];
            if (x < -100 - g.cell()) west += v;
            else if (x > 100 + g.cell()) east += v;
            else if (Math.abs(x) < 100 - g.cell()) middle += v;
        }
        assertEquals(0, west, 0, "the sea doesn't burn");
        // at 3 minutes fires have grown to (180 / GROW_S)² of full: 85 at the old threshold's scale
        assertTrue(east > 100 * FireStorm.growth(180) && middle > 0, "the land and the unknown are burning: " + east + ", " + middle);
    }

    @Test
    void noStormWhereTheServerStartsNoFires() {
        assertEquals(0, new PallField(AIR.withFires(false)).fireReach(), 0, "no haze over the fires, no glow");
        FireStorm off = new FireStorm(AIR.withFires(false), new PallField(AIR));
        assertEquals(0, off.gate(), 0, "from the first frame");
        assertEquals(0, off.trust(), 0);
        FireStorm on = new FireStorm(AIR, new PallField(AIR));
        assertEquals(1, on.gate(), 0);
        assertEquals(1, on.trust(), 0);
        on.seen(0);
        assertEquals(0, on.trust(), 0, "in its chunks the model was waiting for fire, but there was none");
        on.seen(0.15);
        assertEquals(0.5, on.trust(), 1e-12);
    }

    @Test
    void whirlsAreRareShortAndAtTheStrongestFires() {
        for (Detonation d : List.of(AIR, GROUND)) {
            double reach = new PallField(d).fireReach();
            FireStorm.Grid g = grid(d);
            List<FireStorm.Whirl> ws = g.whirls();
            assertTrue(ws.size() >= 2 && ws.size() <= FireStorm.MAX_WHIRLS, "tornadoes " + ws.size());
            float[] sorted = g.strength().clone();
            Arrays.sort(sorted);
            float strong = sorted[(int) (sorted.length * 0.97)];
            double busy = 0, last = -1;
            for (int i = 0; i < ws.size(); i++) {
                FireStorm.Whirl w = ws.get(i);
                assertTrue(w.startS() >= last, "by time");
                last = w.startS();
                double x = w.x() - d.burst().x, z = w.z() - d.burst().z;
                assertTrue(Math.hypot(x, z) <= 0.8 * reach + 1e-6, "in the depths of the fire");
                int at = Arrays.binarySearch(g.index(), cellAt(g, x, z));
                assertTrue(at >= 0 && g.strength()[at] >= strong, "at a strong hearth: " + (at >= 0 ? g.strength()[at] : -1));
                assertTrue(w.startS() >= g.start()[at] / 20.0 + FireStorm.WHIRL_FIRST_S, "when the storm is strong");
                assertTrue(w.lengthS() >= 45 && w.lengthS() <= 90, "short: " + w.lengthS());
                assertTrue(w.radius() > 3 && w.radius() <= 14 && w.height() >= 90 && w.height() <= 130);
                assertEquals(0, w.strength(w.startS()), 0);
                assertEquals(0, w.strength(w.startS() + w.lengthS()), 0);
                assertEquals(1, w.strength(w.startS() + w.lengthS() / 2), 1e-9);
                // its own hearth on the detonation clock: full when due in real time, none once burned out (fast day, sleep)
                assertEquals(g.start()[at] / 20.0, w.fireS(), 1e-3);
                assertEquals(1, w.fire(w.startS()), 1e-9, "a whirl rises over a full fire");
                assertEquals(0, w.fire(w.fireS() + FireStorm.OUT_S), 0, "no whirl over a burned-out city");
                for (int j = 0; j < i; j++) {
                    assertTrue(Math.hypot(w.x() - ws.get(j).x(), w.z() - ws.get(j).z()) >= FireStorm.WHIRL_GAP * Math.min(1, reach / 1000) - 1e-6, "separately");
                }
                busy += w.lengthS();
            }
            // an event, not wallpaper: together - a few minutes on the night of the fire
            assertTrue(busy <= FireStorm.MAX_WHIRLS * 90, "tornadoes are coming " + busy + " s");
        }
    }

    private static int cellAt(FireStorm.Grid g, double x, double z) {
        double half = g.side() * g.cell() / 2;
        return (int) Math.floor((z + half) / g.cell()) * g.side() + (int) Math.floor((x + half) / g.cell());
    }

    @Test
    void lawsShapeTheColumns() {
        double cell = FireStorm.CELL, hb = FireStorm.BEND_HEIGHT;
        assertEquals(0, FireStorm.bend(-10, hb), 0);
        assertEquals(2 * hb, FireStorm.bend(hb, hb), 1e-9, "at height hb drift is twice as large as direct");
        assertEquals(FireStorm.PILLAR * cell, FireStorm.width(0, cell), 1e-9);
        assertTrue(FireStorm.width(0, cell) < 0.35 * cell, "pillars of cells stand apart near the ground");
        assertTrue(FireStorm.width(80, cell) > 0.6 * cell, "to 80 blocks - merge");
        assertEquals(0, FireStorm.lod(FireStorm.width(0, cell), cell), 1e-9, "near the ground - a cage");
        assertEquals(1, FireStorm.lod(cell, cell), 1e-9);
        assertTrue(FireStorm.glowLod(200, cell) > FireStorm.glowLod(50, cell));
        assertEquals(0, FireStorm.column(-40, 260), 1e-12);
        assertEquals(1, FireStorm.column(0.3 * 260, 260), 1e-12);
        assertEquals(0, FireStorm.column(260, 260), 1e-12);
    }

    @Test
    void mapRefreshesAndFollowsRewind() throws InterruptedException {
        PallField pall = new PallField(AIR);
        FireStorm s = new FireStorm(AIR, pall);
        long until = System.nanoTime() + 30_000_000_000L;
        while (s.grid() == null && System.nanoTime() < until) Thread.sleep(20);
        assertNotNull(s.grid(), "mesh in the background");
        assertTrue(s.refresh(20 * 30));
        int v = s.version();
        assertFalse(s.refresh(20 * 30 + 1), "not every frame");
        assertTrue(s.refresh(20 * 30 + FireStorm.FILL_FAST), "while the fire is young - often");
        assertTrue(s.refresh(20 * 10), "rewind - immediately");
        assertEquals(v + 2, s.version());
        assertTrue(s.burning() > 0);
        double late = 20 * 900;
        assertTrue(s.refresh(late));
        assertFalse(s.refresh(late + FireStorm.FILL_FAST), "fire is no longer young - less often");
        assertTrue(s.refresh(late + FireStorm.FILL_SLOW));
    }
}
