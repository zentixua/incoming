package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.defense.InterceptorSpec;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Stability;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dust behind the wave of an ordinary explosion (F3, “plate”): one law with the nuke, the height of the explosion, dust in time, and not the final
 *  state from the first frame.
 */
class GroundDustTest {
    private static final CloudModel.Air DAY = new CloudModel.Air(CloudModel.N_STANDARD, 4, -3, 0.1, (int) Stability.C, 0.5);

    /** Cloud billows at age t: {x, y, z, σh, σv, soot, dust}. */
    private static List<double[]> blobs(CloudModel m, double t) {
        List<double[]> out = new ArrayList<>();
        m.blobs(t, (x, y, z, sh, sv, soot, dust, water, k, rise) -> out.add(new double[]{x, y, z, sh, sv, soot, dust}));
        return out;
    }

    /** The same without the skirt of dust destroyed ({@link CloudModel#crushed}: it has its own law, not Kuhl). */
    private static List<double[]> waveBlobs(CloudModel m, double t) {
        List<double[]> out = new ArrayList<>();
        m.blobs(t, new CloudModel.Blobs() {
            @Override
            public void blob(double x, double y, double z, double sh, double sv, double soot, double dust, double water, double k, double rise) {
                out.add(new double[]{x, y, z, sh, sv, soot, dust});
            }

            @Override
            public void crushed(double x, double y, double z, double sh, double sv, double dust, double kelvin) {
            }
        });
        return out;
    }

    /**
     * Power of the same pressure near the ground: nuclear ground W/0,5 kg (the share of energy in the wave is half, Glasstone and Dolan
     * §1.24) against the charge W kg TNT on the ground according to Kinney-Graham - pressure within a third from 1 to 50 psi for the charges of
     *  games (two different fits: Broad for nuclear, Kinney-Graham for explosive).
     */
    @Test
    void equivalentYieldGivesTheSamePressure() {
        for (double kg : new double[]{6.4, 50, 150, 1000}) {
            double y = CloudModel.equivalentKt(kg);
            for (double r = 1; r < 1000; r *= 1.2) {
                double he = BlastModel.psi(BlastModel.surfaceOverpressureKpa(r, kg));
                if (he < 1 || he > 50) continue;
                double ratio = HobBlast.psi(r, 0, y) / he;
                assertTrue(ratio > 0.67 && ratio < 1.5, kg + " kg per " + r + " m: " + he + " psi BB vs " + HobBlast.psi(r, 0, y));
            }
        }
    }

    /**
     * One law: the wave layer of an ordinary explosion is the same extension and the same layer Kuhl as the nuke ({@code DustModel.column}),
     *  without flash light and debris dust, with the time of saltation development (in the nuke the phase is longer than it); the entire raised mass is
     *  integral of the same law over the ground.
     */
    @Test
    void oneDustLawBehindTheWave() {
        for (double kg : new double[]{6.4, 50, 150}) {
            double y = CloudModel.equivalentKt(kg), ball = 1.7 * Math.cbrt(kg);
            for (double r = 0.5; r < 200; r *= 1.5) {
                DustModel.Column he = DustModel.chemical(r, 0, y, ball), nuke = DustModel.column(r, 0, y, true, 20_000, DustModel.Surface.REFERENCE);
                // the same takeaway; in explosives, saltation does not have time to develop in milliseconds of the phase (DustModel.SALTATION_S) - no more than
                //  steady state, and the suspension at the charge (u * from 10 thresholds) is the same
                assertTrue(he.soil() <= nuke.soil() + 1e-12, kg + " kg per " + r + " m: offset greater than established");
                if (r <= 1.5 * Math.cbrt(kg)) assertEquals(1, he.soil() / nuke.soil(), 0.02, kg + " kg per " + r + " m: suspension");
                assertEquals(nuke.first(), he.first(), 1e-12, kg + " kg per " + r + " m: top layer Kuhl");
                assertEquals(nuke.positive(), he.positive(), 1e-12);
                assertEquals(0, he.buoyant(), "explosives do not have flashes that warm the earth");
                assertEquals(0, he.debris(), "there is no dust or debris near the field");
            }
            // integral of the law over the ground (small step) - what the layer raised (near the ground and under the ball)
            double outer = HobBlast.rangeFor(GroundDust.OUTER_PSI, 0, y), sum = 0;
            int n = 4000;
            for (int i = 0; i < n; i++) {
                double r0 = outer * Math.pow((double) i / n, 2), r1 = outer * Math.pow((i + 1.0) / n, 2), r = 0.5 * (r0 + r1);
                sum += DustModel.chemical(r, 0, y, ball).mass() * Math.PI * (r1 * r1 - r0 * r0);
            }
            GroundDust g = new CloudModel(BlastSource.of(kg > 100 ? WeaponType.MISSILE : WeaponType.DRONE, false), DAY).ground;
            if (kg == 6.4) g = new CloudModel(BlastSource.of(WeaponType.ROCKET, false), DAY).ground;
            assertEquals(1, (g.total + g.underBall(1e3)) / sum, 0.05, kg + " kg: raised by the layer against the integral of the law");
        }
    }

    /**
     * Dust in time: there is none before the front, in the first second not a single billow of dust near the ground is wider than the wave front at that moment, the mass
     *  grows from zero; the top of the layer in 0,1 s is the dusty layer Kuhl (decimeters in Shahed), and not the radius of the Church cloud.
     */
    @Test
    void dustComesWithTheFront() {
        CloudModel m = new CloudModel(BlastSource.of(WeaponType.DRONE, false), DAY);
        double before = -1;
        for (double t : new double[]{0, 0.002, 0.005, 0.01, 0.03, 0.06, 0.1, 0.2, 0.3, 1}) {
            double front = m.ground.front(t), mass = 0;
            for (double[] b : waveBlobs(m, t)) {
                if (b[6] <= 0 || b[5] > 0 || b[1] > 2 * b[4] + 0.5) continue; // dust near the ground (cloud and stem - higher)
                mass += b[6];
                double r = Math.hypot(b[0] - m.ground.drift(t, b[1])[0], b[2] - m.ground.drift(t, b[1])[1]);
                // visible edge of billows (thickness at the edge - 0,05: for a thick one ~3,7σ, no further than 4σ) - not behind the front
                assertTrue(r + GroundDust.EDGE_SIGMAS * b[3] <= front + 0.5, "t " + t + ": billows on " + r + " m, σ " + b[3] + " - behind the front " + front);
                if (t <= 0.1) assertTrue(b[4] < 1, "t " + t + ": σ of the layer vertically " + b[4] + " — Kuhl, decimeters");
            }
            if (t == 0) assertEquals(0, mass, "there is no dust before the front");
            // during the wave phase, the dust only arrives (then the coarse dust settles)
            if (t <= 0.1) assertTrue(mass >= before - 1e-9, "t " + t + ": dust near the ground grows from zero, " + before + " → " + mass);
            before = mass;
        }
        assertTrue(before > 10, "a second later there are tens of kg of dust near the ground: " + before);
    }

    /**
     * The earth is where it is: there is no crater dust above the crater threshold; at the explosion of an anti-aircraft missile at the altitude of the Shahed
     * , the dust wave is only near the ground (not a single billows of dust at the height of the explosion), and from a height of 100 m it is less than half a kilogram.
     */
    @Test
    void airburstHasNoGroundDustInTheAir() {
        double kg = InterceptorSpec.SAM.passport().warhead().tnt();
        BlastSource sam = BlastSource.air(kg);
        double ball = 1.7 * Math.cbrt(kg);
        assertTrue(CloudModel.digs(kg, 0));
        assertFalse(CloudModel.digs(kg, CloudModel.CRATER_HOB * ball * 1.01), "there is no crater threshold above");
        for (double hob : new double[]{CloudModel.CRATER_HOB * ball * 1.5, 10, 45, 100, 300}) {
            CloudModel m = new CloudModel(sam, DAY, false, hob);
            assertEquals(0, m.dust, hob + " m: no dust crater");
            if (hob > ball) assertEquals(0, m.ground.underBall(10), hob + " m: the ball does not touch the earth");
            for (double t : new double[]{0.05, 0.1, 0.5, 1, 3, 10, 30}) {
                double aloft = 0;
                for (double[] b : blobs(m, t)) if (b[6] > 0 && b[1] + hob > 3 * b[4] + 10) aloft += b[6];
                // rupture at flight altitude Shahed and above: dust in the air (above the layer near the ground) - less than 0,1 kg: no cloud of dust at the
                //  rupture point, no “plate”; below - the stem suction can raise the dust of the layer (the law of the nuke)
                if (hob >= 45) assertTrue(aloft < 0.1, hob + " m, t " + t + ": dust in the air " + aloft + " kg");
                if (hob >= 100) assertTrue(m.ground.total < 0.5, hob + " m: dust near the ground " + m.ground.total + " kg (Shahed has hundreds on the ground)");
            }
        }
    }

    /**
     * Weakening of dust in baskets: the coarse dust settles first and weakens little - the share of attenuation in the air is not less than the share of
     *  mass; the explosion cloud holds the dust by weakening (in 3.5.0 - by mass: the cloud paled faster than its thickness settled).
     */
    @Test
    void fineDustOutlastsCoarse() {
        for (double j = 0; j < 1e4; j = j * 2 + 0.1) {
            assertTrue(DustModel.extinctionAloft(j) >= DustModel.airborne(j) - 1e-12, "∫dt/h " + j);
        }
        assertEquals(1, DustModel.extinctionAloft(0), 1e-12);
        assertTrue(DustModel.extinctionAloft(60) > 0.7 && DustModel.airborne(60) < 0.35, "in a minute in a layer of one meter");
    }

    /**
     * Grass holds the ground: in a meadow (loose - zero) wave dust rises only at the crater - many times less than over bare
     *  soil, and no further than ~15 m at Shahed (break threshold 3 m/s versus 0,34, Gaj &amp; Small).
     */
    @Test
    void grassHoldsTheSoil() {
        BlastSource s = BlastSource.of(WeaponType.DRONE, false);
        CloudModel bare = new CloudModel(s, DAY, false, 0, GroundDust.Ground.BARE), meadow = new CloudModel(s, DAY, false, 0, GroundDust.Ground.uniform(0, 1));
        assertTrue(meadow.ground.total < 0.25 * bare.ground.total, "meadow " + meadow.ground.total + " against " + bare.ground.total);
        DustModel.Column far = DustModel.chemical(20, 0, CloudModel.equivalentKt(50), 6.3, DustModel.Surface.COVERED);
        assertTrue(far.soil() * DustModel.K_MIX < 0.01, "in the meadow at 20 m there is almost no dust: thick " + far.soil() * DustModel.K_MIX);
    }

    /**
     * The water does not generate dust: near the shore (half of the sectors are water), the dust of the wave lies only over the land - not a single billows of dust near the ground in the
     *  sectors of the water (a bomb near the beach: a carpet of dust lay on the lake, footage F2a).
     */
    @Test
    void waterGivesNoDust() {
        double[][] loose = new double[GroundDust.SECTORS][GroundDust.BANDS.length], covered = new double[GroundDust.SECTORS][GroundDust.BANDS.length];
        for (int k = 0; k < GroundDust.SECTORS / 2; k++) java.util.Arrays.fill(loose[k], 1);
        CloudModel m = new CloudModel(BlastSource.of(WeaponType.DRONE, false), DAY, false, 0, new GroundDust.Ground(loose, covered));
        int land = 0;
        for (double t : new double[]{0.1, 1, 3}) {
            // Test the wave's source, not land-origin crushed material that can be thrown/carried over water.
            for (double[] b : layer(m.ground, t)) {
                if (b[5] <= 0) continue;
                double[] d = m.ground.drift(t, b[1]);
                double ang = Math.atan2(b[2] - d[1], b[0] - d[0]);
                if (ang < 0) ang += 2 * Math.PI;
                int sector = (int) (ang / (2 * Math.PI) * GroundDust.SECTORS);
                assertTrue(sector < GroundDust.SECTORS / 2, "t " + t + ": dust over water, sector " + sector);
                land++;
            }
        }
        assertTrue(land > 0, "there is dust over the land");
    }

    /**
     * Narrow beach: bare soil only at the crater (up to 6 m), then there is water. Dust near the ground is only where the beach is: not a single billow is
     *  further than 6 m·2 in the middle (dust from the pebbles near the crater is not carried further onto the water; footage F2a: a carpet on the lake).
     */
    @Test
    void narrowBeachKeepsTheDustOnIt() {
        double[][] loose = new double[GroundDust.SECTORS][GroundDust.BANDS.length], covered = new double[GroundDust.SECTORS][GroundDust.BANDS.length];
        for (int k = 0; k < GroundDust.SECTORS; k++) {
            loose[k][0] = 1;
            loose[k][1] = 1;
        }
        CloudModel m = new CloudModel(BlastSource.of(WeaponType.MISSILE, false), DAY, false, 0, new GroundDust.Ground(loose, covered));
        CloudModel open = new CloudModel(BlastSource.of(WeaponType.MISSILE, false), DAY, false, 0, GroundDust.Ground.BARE);
        assertTrue(m.ground.total < 0.3 * open.ground.total, "beach " + m.ground.total + " against a bare field " + open.ground.total);
    }

    /**
     * Dust near the ground lives for minutes and leaves with the wind near the ground: through 30 s Shahed during the day the layer is still visible, its middle is on the
     *  path of the wind near the ground, no higher than tens of meters (and the cloud is higher).
     */
    @Test
    void dustStaysAtTheGround() {
        CloudModel m = new CloudModel(BlastSource.of(WeaponType.DRONE, false), DAY);
        assertTrue(m.groundTauForTest(30) > 0.5, "30 s: layer visible, " + m.groundTauForTest(30));
        assertTrue(m.ground.life > 120, "the layer lives for minutes: " + m.ground.life);
        double low = Double.MAX_VALUE;
        for (double[] b : blobs(m, 30)) if (b[6] > 0 && b[5] == 0) low = Math.min(low, b[1]);
        assertTrue(low < 0.5 * m.height(30), "the earth has dust, and not just a cloud: " + low + " against " + m.height(30));
    }

    /** House: roof of radius {@code roof} m at the height of the explosion, surrounded by a street 15 m below; Bare soil everywhere. */
    private static GroundDust.Ground house(double roof) {
        double[][] h = new double[GroundDust.SECTORS][GroundDust.BANDS.length];
        for (double[] row : h) for (int b = 0; b < row.length; b++) row[b] = GroundDust.BANDS[b] < roof ? 0 : -15;
        return new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), h,
                (x, z) -> Math.hypot(x, z) < roof ? 0 : -15);
    }

    @Test
    void crushedMaterialAtAnEdgeStartsAtTheImpactBeforeFalling() {
        CloudModel.Air air = new CloudModel.Air(CloudModel.N_STANDARD, 4, 0, 0.1, (int) Stability.C, 0.5);
        // The impact is on a narrow ledge. Every 3 m band sample already sees the street 15 m below it.
        var dust = new CloudModel(BlastSource.of(WeaponType.DRONE, false), air, false, 0, house(1)).crushed;
        var first = layer(dust, 0.1);
        assertTrue(!first.isEmpty());
        for (double[] b : first) assertTrue(b[1] - 0.8 * b[4] > -1, "not born on the street below the impact");
        for (double t = 0.25; t <= 60; t += 0.25) {
            for (double[] b : layer(dust, t)) {
                double base = b[1] - 0.8 * b[4];
                assertTrue(base >= -15 - 1e-6 && base <= 1e-6, "falls between the impact and the street");
                if (t == 60) assertTrue(base <= -14, "reaches the street by the dense-current/lee law");
            }
        }
    }

    /**
     * The skirt grows from the impact point (review R462B: arrival beyond the skirt edge was its end, so at
     * {@value GroundDust#SKIRT_S} s the front jumped from 22 m to the ring ends, while a billow at the edge sat
     * in the middle of the whole ring, beyond the skirt). Shahed and cruise missile on a level street in calm
     * air: until {@value GroundDust#SKIRT_S} s, the front is no farther than R·t/{@value GroundDust#SKIRT_S};
     * every billow center is inside the front. Afterward, the front never jumps between adjacent frames.
     */
    @Test
    void skirtGrowsFromTheImpact() {
        CloudModel.Air calm = new CloudModel.Air(CloudModel.N_STANDARD, 0, 0, 0.1, (int) Stability.C, 0.5);
        GroundDust.Ground flat = new GroundDust.Ground(GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), GroundDust.Ground.filled(1),
                GroundDust.Ground.filled(0), (x, z) -> 0);
        for (WeaponType w : List.of(WeaponType.DRONE, WeaponType.MISSILE)) {
            BlastSource src = BlastSource.of(w, false);
            GroundDust dust = new CloudModel(src, calm, false, 0, flat).crushed;
            double reach = GroundDust.SKIRT_R0 * 2 * Math.cbrt(src.chargeKg());
            double last = 0;
            for (double t = 0.05; t <= 2 + 1e-9; t += 0.05) {
                double front = dust.front(t);
                if (t <= GroundDust.SKIRT_S + 1e-9) {
                    assertTrue(front <= reach * t / GroundDust.SKIRT_S + 1e-6, w + ", " + t + " s: front " + front + " further than the skirt");
                    for (double[] b : layer(dust, t)) {
                        double r = Math.hypot(b[0], b[2]);
                        assertTrue(r <= Math.max(front, 1) + 1e-6, w + ", " + t + " s: billows " + r + " m behind the front " + front);
                    }
                }
                assertTrue(front - last <= reach * 0.05 / GroundDust.SKIRT_S + 1e-6, w + ", " + t + " s: front jumped " + last + " → " + front);
                last = front;
            }
        }
    }

    /**
     * At the intersection of the block (houses 10×10×12 m, streets along 6 m) the gas carries a skirt along the streets, and not into the houses: where the entire arc of the sector
     *  in the ring is above the impact on {@link GroundDust#WALL}, skirts no, the billows of the rest are walking along the streets, the mass is the same as that of the skirt in the
     *  field.
     */
    @Test
    void skirtGoesDownTheStreets() {
        GroundDust.Relief town = (x, z) -> Math.floorMod((int) Math.floor(x + 13), 16) < 10 && Math.floorMod((int) Math.floor(z + 13), 16) < 10 ? 12 : 0;
        double[][] h = new double[GroundDust.SECTORS][GroundDust.BANDS.length];
        for (int k = 0; k < GroundDust.SECTORS; k++) {
            double ang = 2 * Math.PI * (k + 0.5) / GroundDust.SECTORS;
            for (int b = 0; b < GroundDust.BANDS.length; b++) h[k][b] = town.at(GroundDust.BANDS[b] * Math.cos(ang), GroundDust.BANDS[b] * Math.sin(ang));
        }
        CloudModel.Air calm = new CloudModel.Air(CloudModel.N_STANDARD, 0, 0, 0.1, (int) Stability.C, 0.5);
        GroundDust.Ground streets = new GroundDust.Ground(GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), GroundDust.Ground.filled(1), h, town);
        GroundDust.Ground field = new GroundDust.Ground(GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), GroundDust.Ground.filled(1),
                GroundDust.Ground.filled(0), (x, z) -> 0);
        GroundDust inTown = new CloudModel(BlastSource.of(WeaponType.DRONE, false), calm, false, 0, streets).crushed;
        GroundDust inField = new CloudModel(BlastSource.of(WeaponType.DRONE, false), calm, false, 0, field).crushed;
        assertEquals(inField.total, inTown.total, 1e-9 * inField.total);
        // billows that the player sees (review of R468: the direction was chosen at the original radii, and the billows were stretched by range
        //  and drain - at the impact in 6,5 m from the intersection 8 from 22 the middles were in houses): to 2 s in calm in the middle of all noticeable
        //  skirt puffs - on the pavement (at the edge - up to a meter), and at the intersection, and in the streets next to it
        for (double[] at : new double[][]{{0, 0}, {0.5, 6.5}, {-2, 9}}) {
            GroundDust.Relief shifted = (x, z) -> town.at(x + at[0], z + at[1]);
            GroundDust.Ground g = new GroundDust.Ground(GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), GroundDust.Ground.filled(1),
                    GroundDust.Ground.filled(0), shifted);
            GroundDust d = new CloudModel(BlastSource.of(WeaponType.DRONE, false), calm, false, 0, g).crushed;
            int seen = 0;
            for (double t = 0.25; t <= 2 + 1e-9; t += 0.25) {
                for (double[] c : layer(d, t)) {
                    if (c[6] < 0.005 * d.total) continue;
                    seen++;
                    // at the edge of the street - up to a meter (the dam moves the middle by fractions of a meter)
                    double low = shifted.at(c[0], c[2]);
                    for (double[] e : new double[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) low = Math.min(low, shifted.at(c[0] + e[0], c[2] + e[1]));
                    assertTrue(low <= GroundDust.WALL, "blow " + at[0] + ", " + at[1] + "; " + t + " s: billows in the house, " + c[0] + ", " + c[2]);
                }
            }
            assertTrue(seen > 20, "bills " + seen);
        }
        int housed = 0, inStreet = 0;
        for (int k = 0; k < GroundDust.SECTORS; k++) {
            for (int i = 0; i < GroundDust.RINGS; i++) {
                double r = inTown.ring(i);
                boolean open = false;
                for (int j = 0; j < GroundDust.ARC; j++) {
                    double phi = 2 * Math.PI * (k + (j + 0.5) / GroundDust.ARC) / GroundDust.SECTORS;
                    open |= town.at(r * Math.cos(phi), r * Math.sin(phi)) <= GroundDust.WALL;
                }
                if (!open) {
                    assertEquals(0, inTown.mass(k, i), 0, "sector " + k + ", ring " + i + " - in the house");
                    housed++;
                } else if (inTown.mass(k, i) > 0 && r > 4) {
                    // billows sector - on the street, not in the house
                    double phi = inTown.heading(k, GroundDust.band(r));
                    assertTrue(town.at(r * Math.cos(phi), r * Math.sin(phi)) <= GroundDust.WALL, "sector " + k + ", ring " + i + ": billows in the house");
                    inStreet++;
                }
            }
        }
        // the middle of the arc of the sector at this intersection further 8 m - in the house: without turning to the streets, the billows would have stood there
        assertTrue(inStreet > 20, "billows in the streets " + inStreet + ", rings entirely in houses " + housed);
        // in the streets the skirt goes further than in the field: the same area πR²/8 per sector - along the open pavement (counting on the grid
        // 0,1 m, regardless of arc samples), no further than REACH_MAX·R
        double skirtR = inField.front(GroundDust.SKIRT_S);
        for (int k = 0; k < GroundDust.SECTORS; k++) {
            assertEquals(1, inField.reach(k), 0, "field");
            double need = Math.PI * skirtR * skirtR / GroundDust.SECTORS, lo = 2 * Math.PI * k / GroundDust.SECTORS, hi = lo + 2 * Math.PI / GroundDust.SECTORS;
            java.util.List<Double> open = new java.util.ArrayList<>();
            for (double x = -3 * skirtR; x <= 3 * skirtR; x += 0.1) {
                for (double z = -3 * skirtR; z <= 3 * skirtR; z += 0.1) {
                    double phi = Math.atan2(z, x);
                    if (phi < 0) phi += 2 * Math.PI;
                    if (phi >= lo && phi < hi && town.at(x, z) <= GroundDust.WALL) open.add(Math.hypot(x, z));
                }
            }
            java.util.Collections.sort(open);
            int cells = (int) Math.ceil(need / 0.01);
            double streetR = Math.min(cells < open.size() ? open.get(cells) : Double.MAX_VALUE, GroundDust.REACH_MAX * skirtR);
            assertTrue(streetR > 1.2 * skirtR, "sector " + k + ": along the streets beyond the field");
            assertEquals(streetR / skirtR, inTown.reach(k), 0.12 * streetR / skirtR, "sector " + k);
        }
    }

    /**
     * The skirt does not climb onto the house at the point of impact (review R462B: the billows path began from the point of the strip, and it was already on the roof - dust
     *  was born on the roof 20 m above the impact): Shahed on the street, 2 m from it the wall of the house is 20 m all over half-plane x > 2,
     * wind to the house. billows skirts up to 30 s the bottom lying no higher than 2 m above the street.
     */
    @Test
    void skirtStaysBelowAWallBesideTheImpact() {
        CloudModel.Air air = new CloudModel.Air(CloudModel.N_STANDARD, 4, 0, 0.1, (int) Stability.C, 0.5);
        GroundDust.Relief house = (x, z) -> x > 2 ? 20 : 0;
        double[][] h = new double[GroundDust.SECTORS][GroundDust.BANDS.length];
        for (int k = 0; k < GroundDust.SECTORS; k++) {
            double ang = 2 * Math.PI * (k + 0.5) / GroundDust.SECTORS;
            for (int b = 0; b < GroundDust.BANDS.length; b++) h[k][b] = house.at(GroundDust.BANDS[b] * Math.cos(ang), GroundDust.BANDS[b] * Math.sin(ang));
        }
        GroundDust.Ground g = new GroundDust.Ground(GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), GroundDust.Ground.filled(1), h, house);
        GroundDust dust = new CloudModel(BlastSource.of(WeaponType.DRONE, false), air, false, 0, g).crushed;
        for (double t = 0.25; t <= 30; t += 0.25) {
            for (double[] b : layer(dust, t)) {
                double base = b[1] - 0.8 * b[4];
                assertTrue(base <= 2, t + " s: skirt at height " + base + " at billows (" + b[0] + ", " + b[2] + ")");
            }
        }
    }

    /**
     * billows layer of dust aged t: {x, y, z, σh, σv, dust (attenuation in kg of mixture), mass of dust in the air, surface with which it raised}.
     */
    /**
     * Wind in the city: below the roofs, the demolition of the skirt is along the street and no further than the wall (review R468.r1: 4 from 24 midpoints above houses on
     * 20 s at 3 m/s across the streets; review R472: houses 8 m - demolition through the wall of the lobes above the projection, 8 billows in houses on
     * 21 s; blow at 6,5 m from the intersection into the wind diagonally - billows jumps back to 55 m into the house, the dam of the path is not
     *  coordinated with the stop; review R472b: wind 6 m/s, houses 8 m - billows path went from the place further than the front, billows on 0,6 s
     *  in the house on 1,7 m). Frame every 0,05 s to 30 s: the middle of each noticeable billows is on the pavement (at the edge - up to
     *  meters) or above the roof, and the billows do not jump out of frame.
     */
    @Test
    void skirtInTheWindStaysInTheStreets() {
        for (double roof : new double[]{12, 8}) {
            for (double[] at : new double[][]{{0, 0}, {0.5, 6.5}}) {
                for (double[] wind : new double[][]{{3, 0}, {3 / Math.sqrt(2), 3 / Math.sqrt(2)}, {6, 0}}) {
                    GroundDust.Relief town = town(roof, at);
                    GroundDust d = townSkirt(town, wind);
                    String where = "at home " + roof + " m, blow " + at[0] + ", " + at[1] + ", wind " + wind[0] + ", " + wind[1];
                    int seen = 0;
                    List<double[]> was = List.of();
                    for (int f = 1; f <= 600; f++) {
                        double t = 0.05 * f;
                        List<double[]> now = layer(d, t);
                        for (double[] c : now) {
                            if (c[6] < 0.005 * d.total) continue;
                            seen++;
                            double low = town.at(c[0], c[2]);
                            for (double[] e : new double[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) low = Math.min(low, town.at(c[0] + e[0], c[2] + e[1]));
                            assertTrue(low <= GroundDust.WALL || c[1] > low, where + ", " + t + " s: billows in the house, " + c[0] + ", " + c[1] + ", " + c[2]);
                            if (t > 1 && !was.isEmpty()) {
                                double near = Double.MAX_VALUE;
                                for (double[] b : was) near = Math.min(near, Math.hypot(c[0] - b[0], c[2] - b[2]));
                                assertTrue(near < 2, where + ", " + t + " s: billows jumped on " + near + " m, " + c[0] + ", " + c[2]);
                            }
                        }
                        was = now;
                    }
                    assertTrue(seen > 1000, where + ":billows " + seen);
                }
            }
        }
    }

    /**
     * A skirt in the city in the wind does not depend on the frame rate: frames through 1/20 and 1/60 s give in the same seconds one and
     *  the same layer (review R472b: the axis of the street in the cache by cell was calculated at the place of the first question - to 30 s the same billow
     *  in 80 m from itself at a different frequency).
     */
    @Test
    void skirtInTheTownDoesNotDependOnTheFrameRate() {
        for (double roof : new double[]{12, 8}) {
            for (double[] at : new double[][]{{0, 0}, {0.5, 6.5}}) {
                for (double[] wind : new double[][]{{3, 0}, {3 / Math.sqrt(2), 3 / Math.sqrt(2)}}) {
                    GroundDust.Relief town = town(roof, at);
                    GroundDust slow = townSkirt(town, wind), fast = townSkirt(town, wind);
                    String where = "at home " + roof + " m, blow " + at[0] + ", " + at[1] + ", wind " + wind[0] + ", " + wind[1];
                    for (int f = 1; f <= 600; f++) {
                        List<double[]> a = layer(slow, f / 20.0);
                        for (int i = 1; i <= 3; i++) {
                            List<double[]> b = layer(fast, (3 * f - 3 + i) / 60.0);
                            if (i < 3) continue;
                            assertEquals(a.size(), b.size(), where + ", " + f / 20.0 + " s");
                            for (int c = 0; c < a.size(); c++) {
                                for (int j = 0; j < 3; j++) {
                                    assertEquals(a.get(c)[j], b.get(c)[j], 1e-6, where + ", " + f / 20.0 + " s, billows " + c);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Impact into a hole (crater of the previous impact, elongated 12×6 m, depth 2,5 and 4 m) in a field with wind 3 m/s: the edge of the hole and the field
     *  around - not walls, the skirt goes with by the wind, as if from a flat field (stand PR #472: at the raid from 100 blows of the skirt of blows in
     *  the old craters stood in them for tens of seconds - a wall of dust in the middle of the field at 76 s).
     */
    @Test
    void skirtLeavesAPitLikeAField() {
        double[] wind = {1.8, 2.4};
        GroundDust flat = townSkirt((x, z) -> 0, wind);
        for (double depth : new double[]{2.5, 4}) {
            GroundDust pit = townSkirt((x, z) -> (x / 6) * (x / 6) + (z / 3) * (z / 3) < 1 ? 0 : depth, wind);
            for (double t : new double[]{5, 20, 40}) {
                double[] a = centre(flat, t), b = centre(pit, t);
                assertEquals(a[0], b[0], 1, depth + " m, " + t + " s");
                assertEquals(a[1], b[1], 1, depth + " m, " + t + " s");
                if (t >= 20) assertTrue(b[2] < 0.01, depth + " m, " + t + " s: by the pit " + b[2] + " dust");
            }
        }
    }

    /** The middle of the layer by mass in the air (x, z) and its share in 8 m from the impact. */
    private static double[] centre(GroundDust g, double t) {
        double[] s = new double[4];
        for (double[] c : layer(g, t)) {
            s[0] += c[6];
            s[1] += c[6] * c[0];
            s[2] += c[6] * c[2];
            if (Math.hypot(c[0], c[2]) < 8) s[3] += c[6];
        }
        return new double[]{s[1] / s[0], s[2] / s[0], s[3] / s[0]};
    }

    /** Quarter: houses 10×10 m height roof, streets along 6 m, blow to {@code at} from the intersection. */
    private static GroundDust.Relief town(double roof, double[] at) {
        return (x, z) -> Math.floorMod((int) Math.floor(x + at[0] + 13), 16) < 10
                && Math.floorMod((int) Math.floor(z + at[1] + 13), 16) < 10 ? roof : 0;
    }

    /** Shahed skirt in the quarter with the wind (x, z), m/s. */
    private static GroundDust townSkirt(GroundDust.Relief town, double[] wind) {
        GroundDust.Ground g = new GroundDust.Ground(GroundDust.Ground.filled(0), GroundDust.Ground.filled(0),
                GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), town);
        CloudModel.Air breeze = new CloudModel.Air(CloudModel.N_STANDARD, wind[0], wind[1], 0.1, (int) Stability.C, 0.5);
        return new CloudModel(BlastSource.of(WeaponType.DRONE, false), breeze, false, 0, g).crushed;
    }

    private static List<double[]> layer(GroundDust g, double t) {
        List<double[]> out = new ArrayList<>();
        g.probe(t, (x, y, z, sh, sv, d, m, src) -> out.add(new double[]{x, y, z, sh, sv, d, m, src}));
        return out;
    }

    /**
     * A heavy billow over a void falls by the MTT thermal law: the closed-form {@code GroundDust.fall} matches the numerical
     * thermal {@link Thermal} (RK4 of the same equations), with the same buoyancy g·M/ρ and volume in neutral air.
     */
    @Test
    void denseDustFallsLikeAThermal() {
        CloudModel.Air neutral = new CloudModel.Air(1e-3, 0, 0, 0.1, (int) Stability.D, 0.5);
        for (double[] c : new double[][]{{15, 5, 1.5}, {2, 1, 0.3}, {40, 12, 4}}) {
            double dust = c[0], sh = c[1], sv = c[2], volume = 2 * Math.PI * sh * sh * 1.25 * sv;
            double b = CloudModel.G * dust / CloudModel.RHO;
            Thermal th = new Thermal(b, b / volume, 0, neutral);
            for (double t = 1; t <= 60; t += 1) {
                double numeric = th.height(t) - 0.5 * th.r0, closed = GroundDust.fall(dust, sh, sv, t);
                assertEquals(numeric, closed, 0.03 * numeric + 0.02, dust + " kg, σ " + sh + "/" + sv + ", " + t + " s");
            }
        }
    }

    /**
     * An impact on the roof and an impact on the ground are the same physics: the roof in all directions (the surface at the height of the explosion) has the same layer as a
     *  impact on bare ground, and a flat surface at 20 m below gives the same as a gap at 20 m above the ground - the same dust at
     *  the same height above its surface.
     */
    @Test
    void roofAndGroundAreOneLaw() {
        BlastSource s = BlastSource.of(WeaponType.MISSILE, false);
        for (double drop : new double[]{0, 20}) {
            GroundDust.Ground flat = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), GroundDust.Ground.filled(0),
                    GroundDust.Ground.filled(-drop), (x, z) -> -drop);
            GroundDust a = new CloudModel(s, DAY, false, 0, flat).ground, b = new CloudModel(s, DAY, false, drop, GroundDust.Ground.BARE).ground;
            assertEquals(b.total, a.total, 1e-9 * b.total);
            for (double t : new double[]{0.1, 1, 10, 60}) {
                List<double[]> la = layer(a, t), lb = layer(b, t);
                assertEquals(lb.size(), la.size(), drop + " m, " + t + " s");
                for (int i = 0; i < la.size(); i++) {
                    for (int j = 0; j < 6; j++) assertEquals(lb.get(i)[j], la.get(i)[j], 1e-9, drop + " m, " + t + " s, billows " + i);
                    assertTrue(la.get(i)[1] + drop <= 2 * la.get(i)[4] + 0.5, "layer - on its surface");
                }
            }
        }
    }

    /**
     * Billow path nodes from {@code from} to {@code to} s: billow level is not the midpoint of a step there.
     */
    private static List<Double> nodes(double from, double to) {
        List<Double> out = new ArrayList<>();
        for (double t : GroundDust.PATH) if (t >= from && t <= to) out.add(t);
        return out;
    }

    /** The surface under billows {x, y, z, σh, …} - as the layer sees it (following its trace, {@code GroundDust.under}). */
    private static double under(GroundDust g, double[] b) {
        return g.under(b[0], b[2], 0.5 * b[3]);
    }

    /**
     * Dust does not hang over the void (complaint 23.10: brown layer at roof height): when hitting the roof of a house (10 and 30 m) with the street at
     * 15 m below, in the wind and in the calm, each billows whose path has gone over the edge descends faster of the two - heavy thermal
     *  (numerical {@link Thermal}: the mass of dust in the air and the size of the billows at the moment of exit - regardless of the model) or the wind behind the
     *  edge ({@code GroundDust.LEE} per meter of drift): not slower and not faster (±10%), and when the law would bring it to
     *  street, he is on it; does not go below the surface.
     */
    @Test
    void dustNeverHangsOverTheVoid() {
        CloudModel.Air neutral = new CloudModel.Air(1e-3, 0, 0, 0.1, (int) Stability.D, 0.5);
        for (double roof : new double[]{10, 30}) {
            for (double wind : new double[]{4, 0}) for (boolean crushed : new boolean[]{false, true}) {
                CloudModel.Air air = new CloudModel.Air(CloudModel.N_STANDARD, wind, 0, 0.1, (int) Stability.C, 0.5);
                CloudModel model = new CloudModel(BlastSource.of(WeaponType.DRONE, false), air, false, 0, house(roof));
                GroundDust dust = crushed ? model.crushed : model.ground;
                List<Double> ts = nodes(1, 180);
                int n = layer(dust, ts.getFirst()).size();
                double[] lifted = new double[n], crossed = new double[n], last = new double[n];
                double[][] edge = new double[n][];
                Thermal[] law = new Thermal[n];
                java.util.Arrays.fill(crossed, -1);
                int fell = 0;
                for (double t : ts) {
                    List<double[]> now = layer(dust, t);
                    assertEquals(n, now.size(), "billows the same");
                    for (int i = 0; i < n; i++) {
                        double[] b = now.get(i);
                        double base = b[1] - 0.8 * b[4], below = under(dust, b);
                        if (t == ts.getFirst()) lifted[i] = last[i] = base;
                        String at = "house " + roof + " m, wind " + wind + ", " + t + " s, billows " + i;
                        assertTrue(base >= below - 1e-6 || base >= last[i] - 1e-6, at + ": goes below the surface " + below);
                        last[i] = base;
                        // billows at the windward edge (the skirt reaches the edge of the roof) the wind blows back onto the roof: lie down on it -
                        //  going beyond the edge is counted again
                        if (crossed[i] >= 0 && below >= lifted[i] - GroundDust.DROP) {
                            crossed[i] = -1;
                            lifted[i] = base;
                        }
                        if (crossed[i] < 0 && below < lifted[i] - GroundDust.DROP) {
                            crossed[i] = t;
                            edge[i] = dust.drift(t, 0.8 * b[4]);
                            double volume = 2 * Math.PI * b[3] * b[3] * 1.25 * b[4], buoyancy = CloudModel.G * b[6] / CloudModel.RHO;
                            law[i] = new Thermal(buoyancy, buoyancy / volume, 0, neutral);
                        }
                        if (crossed[i] < 0) continue;
                        // sinking - the faster of the two: heavy thermal or wind behind the edge (LEE meters per meter of drift)
                        double dt = t - crossed[i], z = law[i].height(dt) - 0.5 * law[i].r0;
                        double[] d = dust.drift(t, 0.8 * b[4]);
                        double lee = GroundDust.LEE * Math.hypot(d[0] - edge[i][0], d[1] - edge[i][1]), down = Math.max(z, lee);
                        // tolerance - per step of path nodes (the moment of going beyond the edge is known to the node): ±0,5 s the law and drift
                        double[] da = dust.drift(t + 0.5, 0.8 * b[4]), db = dust.drift(Math.max(0, t - 0.5), 0.8 * b[4]);
                        double step = law[i].height(dt + 0.5) - law[i].height(Math.max(0, dt - 0.5)) + GroundDust.LEE * Math.hypot(da[0] - db[0], da[1] - db[1]);
                        assertTrue(base <= Math.max(below, lifted[i] - 0.9 * down + step) + 0.3, at + ": hanging over the void, " + base + " against the law " + (lifted[i] - down));
                        assertTrue(base >= Math.min(below, lifted[i] - 1.1 * down - step) - 0.3, at + ": falls faster than the law, " + base + " against " + (lifted[i] - down));
                        if (base <= below + 0.01 && lifted[i] > below + 5) fell++;
                    }
                }
                if (roof >= 30 && (!crushed || wind > 0)) assertTrue(fell > 0, "house " + roof + ", wind " + wind + ", crushed " + crushed + ": dust from the roof reached the street");
            }
        }
    }

    /**
     * How much dust is based on the material, and not “ground or building”: on solid (stone, brick) - only settled dust
     *  ({@code Surface.HARD}, 0,6 g/m²), tens of times less than on bare ground.
     */
    @Test
    void hardSurfaceHoldsOnlySettledDust() {
        BlastSource s = BlastSource.of(WeaponType.MISSILE, false);
        GroundDust.Ground stone = new GroundDust.Ground(GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), GroundDust.Ground.filled(1),
                GroundDust.Ground.filled(Double.NaN), GroundDust.Relief.UNKNOWN);
        GroundDust hard = new CloudModel(s, DAY, false, 0, stone).ground, bare = new CloudModel(s, DAY, false, 0, GroundDust.Ground.BARE).ground;
        double outer = HobBlast.rangeFor(GroundDust.OUTER_PSI, 0, CloudModel.equivalentKt(s.chargeKg()));
        assertTrue(hard.total + hard.underBall(1e3) <= DustModel.Surface.HARD.supply() * Math.PI * outer * outer * 1.0001, "no more than settled dust");
        assertTrue(hard.total < 0.05 * bare.total, "stone " + hard.total + " against bare ground " + bare.total);
    }

    /**
     * A layer heavier than air spreads by gravitational flow (Huppert &amp; Simpson 1980): in the calm d(R²)/dt =
     * 2·Fr·√(g·M/(πρ)) by the dust that is still in the air (in the first seconds - almost all).
     */
    @Test
    void dustSheetSlumpsLikeAGravityCurrent() {
        GroundDust g = new CloudModel(BlastSource.of(WeaponType.DRONE, false), CloudModel.Air.STANDARD).ground;
        double r2 = (Math.pow(g.stretch(1.5), 2) - Math.pow(g.stretch(1), 2)) * g.slumpFrom / 0.5;
        double law = 2 * GroundDust.FR * Math.sqrt(CloudModel.G * g.total / (Math.PI * CloudModel.RHO));
        assertTrue(r2 <= law * 1.02 && r2 > 0.6 * law, "d(R²)/dt " + r2 + " against the law over all the dust raised " + law);
        assertTrue(g.stretch(60) > g.stretch(10) && g.stretch(10) > 1, "spreads");
    }

    /**
     * The fallen dust does not jump onto the roof of the next house (review: without memory of the path, billows, who came off the roof, jumped onto the roof
     *  of the neighboring one and fell at once): two houses (roof of loose earth up to 10 m and house 15 m at 40–70 m downwind), street at 15 m below,
     * wind 4 m/s; and where the relief further than 30 m has disappeared - the level of billows from node to node of the path rises no steeper than 45° along the path of
     *  demolition by the wind (the wind carries the layer through the house), and dust from the roof reaches the street.
     */
    @Test
    void fallenDustStaysDown() {
        CloudModel.Air air = new CloudModel.Air(CloudModel.N_STANDARD, 4, 0, 0.1, (int) Stability.C, 0.5);
        double[][] h = new double[GroundDust.SECTORS][GroundDust.BANDS.length];
        for (double[] row : h) for (int b = 0; b < row.length; b++) row[b] = GroundDust.BANDS[b] < 10 ? 0 : -15;
        for (boolean lost : new boolean[]{false, true}) {
            GroundDust.Relief relief = (x, z) -> {
                if (Math.hypot(x, z) < 10) return 0;
                if (lost && Math.hypot(x, z) > 30) return Double.NaN;
                return x > 40 && x < 70 && Math.abs(z) < 15 ? 0 : -15;
            };
            GroundDust.Ground g = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), h, relief);
            GroundDust dust = new CloudModel(BlastSource.of(WeaponType.DRONE, false), air, false, 0, g).ground;
            // with 3 s: in the first seconds the place of billows in the frame is sandwiched by the wave front and does not coincide with the path
            List<Double> ts = nodes(3, 120);
            for (double t : nodes(0.25, 3)) layer(dust, t);
            List<double[]> prev = layer(dust, ts.getFirst());
            double before = ts.getFirst();
            int landed = 0;
            for (double t : ts.subList(1, ts.size())) {
                List<double[]> now = layer(dust, t);
                for (int i = 0; i < now.size(); i++) {
                    double[] a = prev.get(i), b = now.get(i);
                    // the layer is carried upward only by the wind: the path is wind drift at the height of the middle of the layer
                    double[] w0 = dust.drift(before, 0.8 * a[4]), w1 = dust.drift(t, 0.8 * b[4]);
                    double rise = (b[1] - 0.8 * b[4]) - (a[1] - 0.8 * a[4]), path = Math.hypot(w1[0] - w0[0], w1[1] - w0[1]);
                    assertTrue(rise <= GroundDust.DROP * path + 0.8 * Math.abs(b[4] - a[4]) + 1e-6,
                            (lost ? "the relief has disappeared, " : "") + t + " s, billows " + i + ": climbed " + rise + " for " + path + " m of way");
                    if (b[1] - 0.8 * b[4] <= -15 + 1e-6 && a[1] - 0.8 * a[4] > -15 + 1e-6) landed++;
                }
                prev = now;
                before = t;
            }
            if (!lost) assertTrue(landed > 0, "dust from the roof reached the street");
        }
    }

    /**
     * The layer goes along the slope, and does not come off from it (second review: the threshold of the edge was a grid step - above the slope down 17°
     *  the billow hung for a hundred meters, and up the slope it went into it; eighth - starting from the point of the strip: on the slope 31° billows immediately
     *  “fell from the edge” and hung above the slope): impact into bare ground on the slope 10°, 17°, 31° and 42° downwind and upwind 4 m/s -
     * s 3 to 300 s bottom each billows at the nodes of the path - on the surface below it (within the block).
     */
    @Test
    void dustFollowsSlopes() {
        CloudModel.Air air = new CloudModel.Air(CloudModel.N_STANDARD, 4, 0, 0.1, (int) Stability.C, 0.5);
        for (double slope : new double[]{-0.9, -0.6, -0.3, -0.18, 0.18, 0.3, 0.6, 0.9}) {
            double[][] h = new double[GroundDust.SECTORS][GroundDust.BANDS.length];
            for (int k = 0; k < GroundDust.SECTORS; k++) {
                double ang = 2 * Math.PI * (k + 0.5) / GroundDust.SECTORS;
                for (int b = 0; b < GroundDust.BANDS.length; b++) h[k][b] = Math.floor(slope * GroundDust.BANDS[b] * Math.cos(ang));
            }
            GroundDust.Relief relief = (x, z) -> Math.floor(slope * x);
            GroundDust.Ground g = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), h, relief);
            GroundDust dust = new CloudModel(BlastSource.of(WeaponType.DRONE, false), air, false, 0, g).ground;
            // as in the game - from the first frame (the path is built according to what the first call saw); check - with 3 s: in the first
            //  second the growing trace lowers the bottom under the billows faster than the layer can lie down (by law)
            for (double t = 1 / 60.0; t < 3; t += 1 / 60.0) layer(dust, t);
            for (double t : nodes(3, 300)) {
                for (double[] b : layer(dust, t)) {
                    double base = b[1] - 0.8 * b[4], below = under(dust, b);
                    String at = "slope " + slope + ", " + t + " s: bottom " + base + " above the relief " + below;
                    // on a slope almost at 45° (ascent limit), the relief steps give a lag of another block
                    assertTrue(Math.abs(base - below) <= (Math.abs(slope) > 0.7 ? 2 : 1) * GroundDust.DROP + 1e-6, at);
                }
            }
        }
    }

    /**
     * The pillars, trunks and lanterns of billows do not hold (third review: the surface at one point - a pillar in a block stopped a billow
     *  tens of meters wide): a rocket on bare ground, wind 4 m/s, pillars 1×1 in 6 m in every hundredth column - billows go
     *  in the same way and at the same height as without them (up to 60 s - no further than 2 m, bottom - within the block).
     */
    @Test
    void postsDoNotHoldTheLayer() {
        CloudModel.Air air = new CloudModel.Air(CloudModel.N_STANDARD, 4, 0, 0.1, (int) Stability.C, 0.5);
        // pillars and trunks - in every hundredth column, scattered
        GroundDust.Relief posts = (x, z) -> Math.floorMod(java.util.Objects.hash((int) Math.floor(x), (int) Math.floor(z)) * 0x9E3779B1, 100) == 0 ? 6 : 0;
        GroundDust.Ground withPosts = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), GroundDust.Ground.filled(0),
                GroundDust.Ground.filled(0), posts);
        GroundDust.Ground open = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), GroundDust.Ground.filled(0),
                GroundDust.Ground.filled(0), (x, z) -> 0);
        BlastSource s = BlastSource.of(WeaponType.MISSILE, false);
        GroundDust a = new CloudModel(s, air, false, 0, withPosts).ground, b = new CloudModel(s, air, false, 0, open).ground;
        for (double t = 0.3; t <= 60; t += 0.5) {
            List<double[]> la = layer(a, t), lb = layer(b, t);
            assertEquals(lb.size(), la.size());
            for (int i = 0; i < la.size(); i++) {
                double d = Math.hypot(la.get(i)[0] - lb.get(i)[0], la.get(i)[2] - lb.get(i)[2]);
                double up = (la.get(i)[1] - 0.8 * la.get(i)[4]) - (lb.get(i)[1] - 0.8 * lb.get(i)[4]);
                assertTrue(d <= 2, t + " s, billows " + i + ": the pillars were delayed for " + d + " m");
                assertTrue(Math.abs(up) <= GroundDust.DROP, t + " s, billows " + i + ": the pillars raised the layer to " + up + " m");
            }
        }
    }

    /**
     * Falling billows over an unknown relief (no chunks and maps) falls further - to the lowest surface that the
     *  explosion saw, and does not hang (third review): Shahed into the roof of 10 m, the street is 15 m below, the relief is known only up to 14 m - to 120 s
     *  no billows are higher than 1 m above the street.
     */
    @Test
    void fallingDustOverUnknownReliefStillFalls() {
        CloudModel.Air air = new CloudModel.Air(CloudModel.N_STANDARD, 4, 0, 0.1, (int) Stability.C, 0.5);
        double[][] h = new double[GroundDust.SECTORS][GroundDust.BANDS.length];
        for (double[] row : h) for (int b = 0; b < row.length; b++) row[b] = GroundDust.BANDS[b] < 10 ? 0 : -15;
        GroundDust.Relief known = (x, z) -> Math.hypot(x, z) < 10 ? 0 : Math.hypot(x, z) < 14 ? -15 : Double.NaN;
        GroundDust.Ground g = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), h, known);
        GroundDust dust = new CloudModel(BlastSource.of(WeaponType.DRONE, false), air, false, 0, g).ground;
        for (double t = 0.3; t <= 120; t += 0.5) layer(dust, t);
        for (double[] b : layer(dust, 120)) assertTrue(b[1] - 0.8 * b[4] <= -15 + 1 + 1e-6, "120 s: billows in " + b[0] + " m, bottom " + (b[1] - 0.8 * b[4]));
    }

    /**
     * In the calm, the dust of the street does not climb onto the roofs (fourth review: the lifting limit was a block per sample, and the motionless billows,
     * , whose growing trail touched the houses, rose to the height of the roofs above the street): Shahed at the intersection (houses 10×10 height 12 m,
     *  streets along 6 m), calm - up to 240 s the bottom of billows raised from the street, while the layer is thinner than houses (2σv < 12), not higher than 1 m above the
     *  street, and thicker - covers the block: the bottom is not higher than half the height of the houses or a quarter of its thickness (2σv).
     */
    @Test
    void calmStreetDustStaysInTheStreet() {
        GroundDust.Relief town = GroundDustTest::town;
        double[][] h = new double[GroundDust.SECTORS][GroundDust.BANDS.length];
        for (int k = 0; k < GroundDust.SECTORS; k++) {
            double ang = 2 * Math.PI * (k + 0.5) / GroundDust.SECTORS;
            for (int b = 0; b < GroundDust.BANDS.length; b++) h[k][b] = town.at(GroundDust.BANDS[b] * Math.cos(ang), GroundDust.BANDS[b] * Math.sin(ang));
        }
        GroundDust.Ground g = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), h, town);
        GroundDust dust = new CloudModel(BlastSource.of(WeaponType.DRONE, false), CloudModel.Air.STANDARD, false, 0, g).ground;
        List<Double> ts = nodes(1, 240);
        List<double[]> first = layer(dust, ts.getFirst());
        for (double t : ts) {
            List<double[]> now = layer(dust, t);
            for (int i = 0; i < now.size(); i++) {
                double[] b = now.get(i);
                // dust raised from the street (by the surface from which it was picked up, and not by where the billows is already standing)
                if (b[7] > 0.5) continue;
                double base = b[1] - 0.8 * b[4];
                // layer thinner than houses (2σv < 12) - on the street; thicker - overflows across the roofs, but lies no higher than the middle top
                // under its trace
                if (2 * b[4] < 9) assertTrue(base <= 1 + 1e-6, t + " s, billows " + i + ": fine dust of the street at height " + base);
                else assertTrue(base <= Math.max(4.5, 0.5 * b[4]) + 1e-6, t + " s, billows " + i + ": thick dust of the street at height " + base);
            }
        }
    }

    /**
     * In the wind, the dust of the street also does not rise to the height of the roofs (fifth review: the wind raised the layer on the house for meters, and behind the house
     *  lowered it in minutes - a brown layer at the height of the roofs above the city): Shahed at an intersection with loose soil, wind 5 m/s - in the
     *  quarter (at home 10×10×12 m, streets along 6 m) and in front of the tower 40 m wide 20 m in 30–50 m downwind to 120 s the bottom of billows,
     *  raised from the street, while the layer is thinner than the house, not higher than the block above the street (the layer does not climb the walls, in buildings the wind
     *  goes on top, skimming flow), and the thicker one covers the houses: the bottom is not higher than half of their height or a quarter of its thick
     *  (for a moment, while the trail is all over the roofs - no longer than 3 in a row).
     */
    @Test
    void windyStreetDustStaysInTheStreet() {
        CloudModel.Air air = new CloudModel.Air(CloudModel.N_STANDARD, 5, 0, 0.1, (int) Stability.C, 0.5);
        GroundDust.Relief town = GroundDustTest::town;
        GroundDust.Relief tower = (x, z) -> x > 30 && x < 50 && Math.abs(z) < 10 ? 40 : 0;
        for (GroundDust.Relief relief : List.of(town, tower)) {
            double[][] h = new double[GroundDust.SECTORS][GroundDust.BANDS.length];
            for (int k = 0; k < GroundDust.SECTORS; k++) {
                double ang = 2 * Math.PI * (k + 0.5) / GroundDust.SECTORS;
                for (int b = 0; b < GroundDust.BANDS.length; b++) h[k][b] = relief.at(GroundDust.BANDS[b] * Math.cos(ang), GroundDust.BANDS[b] * Math.sin(ang));
            }
            GroundDust.Ground g = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), h, relief);
            GroundDust dust = new CloudModel(BlastSource.of(WeaponType.DRONE, false), air, false, 0, g).ground;
            String scene = relief == town ? "quarter" : "tower";
            List<Double> ts = nodes(1, 120);
            List<double[]> first = layer(dust, ts.getFirst());
            double[] high = new double[first.size()];
            double last = ts.getFirst();
            for (double t : ts) {
                List<double[]> now = layer(dust, t);
                for (int i = 0; i < now.size(); i++) {
                    double[] b = now.get(i);
                    if (b[7] > 0.5) continue;
                    double base = b[1] - 0.8 * b[4], wall = relief == town ? 9 : 40;
                    // a layer thinner than houses - on the street; thicker - shimmers, but lies no higher than the middle top under its trace
                    if (2 * b[4] < wall) assertTrue(base <= 1 + 1e-6, scene + ", " + t + " s, billows " + i + ": fine dust of the street at height " + base);
                    // thicker - covers: the bottom is not higher than half the house or a quarter of its thickness (there is no layer above the empty street)
                    else {
                        // for a moment the trail is all over the roofs - the bottom rises a knot or two; longer than 3 s a row - already a layer
                        if (base > Math.max(0.5 * wall, 0.5 * b[4])) high[i] += t - last;
                        else high[i] = 0;
                        assertTrue(high[i] <= 3, scene + ", " + t + " s, billows " + i + ": thick dust of the street at height " + base + " already " + high[i] + " s");
                    }
                }
                last = t;
            }
        }
    }

    /**
     * The step and the slope behind it (sixth and seventh reviews: a step, once encountered, remained a wall or “covered” forever,
     *  and billows went under the terrace and under the hill): bare soil, wind 5 m/s - step in 2 block (below the layer) and in 4 block (above the
     *  early layer) at 10–20 m from the impact and the slope 17° behind it. The bottom of the billows is nowhere deeper under the surface beneath it than the thickness of the
     *  layer or step at which it waits, and the billows, which has passed the step further than its width (σh) and another 20 m, is again on the
     *  surface (within two blocks).
     */
    @Test
    void dustClimbsStepsAndHillsBehindThem() {
        CloudModel.Air air = new CloudModel.Air(CloudModel.N_STANDARD, 5, 0, 0.1, (int) Stability.C, 0.5);
        for (double[] c : new double[][]{{20, 2}, {10, 4}}) {
            double at = c[0], rise = c[1];
            GroundDust.Relief step = (x, z) -> x < at ? 0 : Math.floor(rise + 0.3 * Math.max(0, x - at - 10));
            double[][] h = new double[GroundDust.SECTORS][GroundDust.BANDS.length];
            for (int k = 0; k < GroundDust.SECTORS; k++) {
                double ang = 2 * Math.PI * (k + 0.5) / GroundDust.SECTORS;
                for (int b = 0; b < GroundDust.BANDS.length; b++) h[k][b] = step.at(GroundDust.BANDS[b] * Math.cos(ang), GroundDust.BANDS[b] * Math.sin(ang));
            }
            GroundDust.Ground g = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), h, step);
            GroundDust dust = new CloudModel(BlastSource.of(WeaponType.DRONE, false), air, false, 0, g).ground;
            for (double t : nodes(3, 90)) {
                for (double[] b : layer(dust, t)) {
                    double base = b[1] - 0.8 * b[4], below = under(dust, b);
                    String where = "step " + rise + " in " + at + " m, " + t + " s: billows " + b[0] + " m, bottom " + base + ", surface " + below + ", σv " + b[4];
                    // thinner than a step - it waits at its level (no deeper than the step itself), thicker - on the surface
                    assertTrue(base >= below - Math.max(2 * b[4], rise) - GroundDust.DROP - 1e-6, where + " - deeper thickness and steps");
                    if (b[0] > at + b[3] + 20) assertTrue(base >= below - 2 * GroundDust.DROP - 1e-6, where + " - did not come to the surface");
                }
            }
        }
    }

    /**
     * Quarter: plots 16×16 with a street shift, on the plot there is a house 10×10 with a height of 9–15 m (according to the hash of the plot), between them there are streets along 6 m;
     * crossroads - at the point of the explosion. Not in a linear mod: a real city has more than one step of houses.
     */
    private static double town(double x, double z) {
        int cx = Math.floorDiv((int) Math.floor(x) + 11, 16), cz = Math.floorDiv((int) Math.floor(z) + 11, 16);
        int hash = java.util.Objects.hash(cx, cz) * 0x9E3779B1;
        int ox = Math.floorMod(hash >> 4, 3), oz = Math.floorMod(hash >> 8, 3);
        boolean house = Math.floorMod((int) Math.floor(x) + 11, 16) - ox >= 0 && Math.floorMod((int) Math.floor(x) + 11, 16) - ox < 10
                && Math.floorMod((int) Math.floor(z) + 11, 16) - oz >= 0 && Math.floorMod((int) Math.floor(z) + 11, 16) - oz < 10;
        return house && !(cx == 0 && cz == 0 && Math.hypot(x, z) < 3) ? 9 + Math.floorMod(hash >> 12, 7) : 0;
    }

    /**
     * A cliff, and behind it a steep slope (ninth review: the falling billows descended only by the law of fall, and the slope 31° below it
     *  went away faster - by the minute it was hanging 50 m above the slope): rocket on bare ground, wind 5 m/s, cliff 8 m at 15 m from
     *  impact and the slope 31° behind it; frames from the first - from 60 s billows for 60 m from the impact - on the surface below them (within
     *  two blocks).
     */
    @Test
    void dustFallingOffACliffLandsOnTheSlopeBelow() {
        CloudModel.Air air = new CloudModel.Air(CloudModel.N_STANDARD, 5, 0, 0.1, (int) Stability.D, 0.5);
        GroundDust.Relief cliff = (x, z) -> x < 15 ? 0 : Math.floor(-8 - 0.6 * (x - 15));
        double[][] h = new double[GroundDust.SECTORS][GroundDust.BANDS.length];
        for (int k = 0; k < GroundDust.SECTORS; k++) {
            double ang = 2 * Math.PI * (k + 0.5) / GroundDust.SECTORS;
            for (int b = 0; b < GroundDust.BANDS.length; b++) h[k][b] = cliff.at(GroundDust.BANDS[b] * Math.cos(ang), GroundDust.BANDS[b] * Math.sin(ang));
        }
        GroundDust.Ground g = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), h, cliff);
        GroundDust dust = new CloudModel(BlastSource.of(WeaponType.MISSILE, false), air, false, 0, g).ground;
        for (double t = 1 / 60.0; t < 60; t += 1 / 60.0) layer(dust, t);
        for (double t : nodes(60, 240)) {
            for (double[] b : layer(dust, t)) {
                if (b[0] < 60) continue;
                double base = b[1] - 0.8 * b[4], below = under(dust, b);
                assertTrue(base <= below + 2 * GroundDust.DROP + 1e-6, t + " s: billows " + b[0] + " m hanging on " + (base - below) + " above the slope");
            }
        }
    }

    /**
     * There is a ravine nearby and a steep slope up near the rocket (tenth review: the minimum on the trail pulled wide billows to the bottom of the ravine at 6 m,
     * , and the dust from this side lay underground for a minute; starting the outer ring from the point of the strip at 48 m planted the billows into the slope): footage
     *  from the first, wind 5 m/s. Ravine 6×25 m in 12 m from the impact - with 3 s billows, whose footprint never fell on the ravine at least ~20%,
     *  not lower than the level ground by more than a block (flowed into the ravine accumulates in it); slope 39° upwind - with 3 s the bottom of each billows not lower than the surface
     * below it by more than two blocks.
     */
    @Test
    void gullyAndSteepUpslopeKeepDustOnTheSurface() {
        CloudModel.Air air = new CloudModel.Air(CloudModel.N_STANDARD, 5, 0, 0.1, (int) Stability.D, 0.5);
        GroundDust.Relief gully = (x, z) -> x >= 12 && x < 18 ? -25 : 0;
        GroundDust.Relief slope = (x, z) -> Math.floor(0.8 * x);
        for (GroundDust.Relief relief : List.of(gully, slope)) {
            double[][] h = new double[GroundDust.SECTORS][GroundDust.BANDS.length];
            for (int k = 0; k < GroundDust.SECTORS; k++) {
                double ang = 2 * Math.PI * (k + 0.5) / GroundDust.SECTORS;
                for (int b = 0; b < GroundDust.BANDS.length; b++) h[k][b] = relief.at(GroundDust.BANDS[b] * Math.cos(ang), GroundDust.BANDS[b] * Math.sin(ang));
            }
            GroundDust.Ground g = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), h, relief);
            GroundDust dust = new CloudModel(BlastSource.of(WeaponType.MISSILE, false), air, false, 0, g).ground;
            String scene = relief == gully ? "ravine" : "slope";
            for (double t = 1 / 60.0; t < 3; t += 1 / 60.0) layer(dust, t);
            boolean[] inside = new boolean[layer(dust, 3).size()];
            for (double t : nodes(3, 120)) {
                List<double[]> now = layer(dust, t);
                for (int i = 0; i < now.size() && i < inside.length; i++) {
                    double[] b = now.get(i);
                    double base = b[1] - 0.8 * b[4], centre = relief.at(b[0], b[2]);
                    if (relief == gully) {
                        // billows, whose trace at least once noticeably (from ~20%: three samples from 13) lay on a ravine, flows into it and accumulates
                        //  there (heavy air is in the lowlands) - does not count; the rest don't go to the bottom
                        int over = 0;
                        for (double[] d : GroundDust.FOOTPRINT) if (gully.at(b[0] + 0.5 * b[3] * d[0], b[2] + 0.5 * b[3] * d[1]) < 0) over++;
                        if (over >= 3) inside[i] = true;
                        if (!inside[i]) assertTrue(base >= -GroundDust.DROP - 1e-6, scene + ", " + t + " s, billows " + i + ": at the bottom of the ravine, although above it " + over + " of 13 trace samples, bottom " + base);
                    } else {
                        assertTrue(base >= under(dust, b) - 2 * GroundDust.DROP - 1e-6, scene + ", " + t + " s, billows " + i + ": on the slope, bottom " + base + " under " + under(dust, b));
                    }
                }
            }
        }
    }
}
