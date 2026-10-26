package ua.zentix.replayscan.film;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FPV pilot over terrain without a world: a flat field, a ridge, smoke from a previous impact in the direct-pass path,
 * and the replay chunk boundary. The flight must avoid all of these like a pilot who can see, while filming the impact.
 */
class PilotTest {
    private static final double ASPECT = 1080.0 / 1920, FOV = 80;
    private static final int A = 110, B = 290, HIT = 200;
    private static final Vec3 HIT_POS = new Vec3(0.5, 64, 0.5);

    /** Shahed from the west: 2.1 blocks per tick, diving for the last 40 ticks. */
    private static Subject shahed() {
        return shahed(-1, 0);
    }

    /** Shahed arriving from {@code (fx, fz)} (unit vector from the impact). */
    private static Subject shahed(double fx, double fz) {
        List<double[]> path = new ArrayList<>();
        for (int t = 40; t <= HIT; t += 2) {
            double r = 2.1 * (HIT - t), y = t < HIT - 40 ? 95 : 64 + 31 * (HIT - t) / 40.0;
            path.add(new double[]{t, 0.5 + fx * r, y, 0.5 + fz * r});
        }
        return new Subject("00000000-0000-0000-0000-000000000001", 3.5, path, List.of(new Subject.Key(HIT, HIT_POS, 12)), null, 0);
    }

    private static Pilot pilot(Pilot.Terrain terrain, List<Plume> extra) {
        return pilot(terrain, extra, shahed());
    }

    private static Pilot pilot(Pilot.Terrain terrain, List<Plume> extra, Subject s) {
        Subject.Key main = Pilot.mainKey(s, A, B);
        assertNotNull(main);
        List<Plume> plumes = new ArrayList<>(extra);
        plumes.add(new Plume(HIT, HIT_POS, 12, Plume.Look.DRONE));
        return new Pilot(A, B, FOV, ASPECT, s, main, plumes, new Pilot.Grid(terrain, 0, 0, 400), 42);
    }

    /** Flight follows the drone rules and the pass filmed the impact; returns the path by tick. */
    private static Pilot.Track check(Pilot p, Pilot.Flight f) {
        Pilot.Track tr = p.fly(f.controls());
        for (int t = A; t <= B; t++) {
            int i = t - tr.from();
            Vec3 pos = tr.pos()[i];
            double high = p.grid.high(pos.x, pos.z);
            assertFalse(Double.isNaN(high), f.name() + ": over chunks the client lacks at tick " + t + " " + pos);
            assertTrue(pos.y - high >= Pilot.CLEAR - 0.5, f.name() + ": " + (pos.y - high) + " blocks above ground at tick " + t);
            assertTrue(tr.vel()[i].length() <= Pilot.V_MAX + 0.1, f.name() + ": speed " + tr.vel()[i].length() + " at tick " + t);
            assertTrue(tr.acc()[i].length() <= Pilot.A_MAX * 1.5, f.name() + ": acceleration " + tr.acc()[i].length() + " at tick " + t);
            assertTrue(Math.abs(tr.roll()[i]) <= Pilot.ROLL + 1e-6);
            for (Plume pl : p.plumes) assertTrue(pl.clearance(pos, t) > 0, f.name() + ": in smoke of " + pl + " at tick " + t);
        }
        int ahead = 0, n = 0;
        for (int t = A; t <= B; t += 2, n++) if (p.smother((t - A) / 2, p.view(tr, t - tr.from())) >= FrameLog.SMOTHERED) ahead++;
        assertTrue(ahead <= n / 20, f.name() + ": smoke fills the frame in " + ahead + " of " + n + " samples");
        int seen = 0;
        for (int t = HIT; t <= HIT + FrameLog.KEY_CHECK; t++) {
            int i = t - tr.from();
            Vec3 k = Cameras.keyPoint(p.main, t);
            if (p.grid.seen(p.view(tr, i), k, p.main.size())) seen++;
        }
        assertTrue(seen >= FrameLog.KEY_CHECK, f.name() + ": the hit is in frame in " + seen + " of its first ticks");
        double d = tr.pos()[HIT - tr.from()].distanceTo(HIT_POS);
        assertTrue(d >= 18 && d <= 90, f.name() + ": " + d + " blocks from the hit when it goes off");
        return tr;
    }

    @Test
    void fliesLowPastTheHitOnFlatGround() {
        Pilot p = pilot((x, z) -> 64, List.of());
        List<Pilot.Flight> flights = p.plan(3);
        assertEquals(3, flights.size());
        for (Pilot.Flight f : flights) {
            Pilot.Track tr = check(p, f);
            double sum = 0;
            int n = 0;
            for (int t = A; t <= B; t += 2, n++) sum += tr.pos()[t - tr.from()].y - 64;
            assertTrue(sum / n <= Pilot.HIGH + 4, f.name() + ": " + sum / n + " blocks above the field on average — not low");
        }
        // different: from another side or heading
        assertFalse(flights.get(0).name().equals(flights.get(1).name()) && flights.get(1).name().equals(flights.get(2).name()));
    }

    @Test
    void climbsOverARidgeInsteadOfThroughIt() {
        // a 40-block-high ridge across the path, 40–48 blocks north of the impact, and high-rises to the south
        Pilot.Terrain t = (x, z) -> z >= -48 && z <= -40 ? 104 : z >= 35 && z <= 45 && Math.abs(x) < 60 ? 94 : 64;
        Pilot p = pilot(t, List.of());
        for (Pilot.Flight f : p.plan(2)) check(p, f);
    }

    @Test
    void keepsOutOfEarlierSmoke() {
        // Grad smoke from 3–6 s ago within 45–55 blocks of the site (fireball cloud, 20-block dust ring, column): a direct
        // pass would go through it
        List<Plume> smoke = List.of(new Plume(HIT - 120, new Vec3(45.5, 64, 0.5), 8, Plume.Look.ROCKET),
                new Plume(HIT - 60, new Vec3(0.5, 64, 50.5), 8, Plume.Look.ROCKET), new Plume(HIT - 90, new Vec3(-38.5, 64, -38.5), 8, Plume.Look.ROCKET));
        Pilot p = pilot((x, z) -> 64, smoke);
        for (Pilot.Flight f : p.plan(2)) {
            Pilot.Track tr = check(p, f);
            int smoky = 0, n = 0;
            for (int t = A; t <= HIT + 40; t += 2, n++) {
                Vec3 pos = tr.pos()[t - tr.from()];
                Vec3 q = p.focus(t);
                int at = t;
                if (smoke.stream().anyMatch(pl -> pl.blocks(pos, q, at))) smoky++;
            }
            assertTrue(smoky <= n / 5, f.name() + ": the hit is behind smoke in " + smoky + " of " + n + " samples");
        }
    }

    @Test
    void staysOutOfTheSalvoSmokeAfterTheHit() {
        // pass 03:53 (13.10): Grads one second before and one second after the impact, 8–10 blocks from it; the previous
        // pilot model flew 13–18 blocks away, and the frame was full of smoke for the last 2 s (check: camera outside smoke,
        // no smoke in front of the lens)
        List<Plume> salvo = List.of(new Plume(HIT - 25, new Vec3(-0.5, 64, -7.5), 8, Plume.Look.ROCKET),
                new Plume(HIT + 24, new Vec3(-9.5, 64, -0.5), 8, Plume.Look.ROCKET));
        Pilot p = pilot((x, z) -> 64, salvo);
        for (Pilot.Flight f : p.plan(2)) check(p, f);
    }

    @Test
    void smokePast24BlocksFillsTheFrame() {
        // rocket cloud 2 s after impact, 55 blocks in front of the camera: the frame center falls 24 blocks short of it,
        // but the render grid (up to 40 blocks, across the whole frame) is obscured; turn away and it is clear
        Pilot p = pilot((x, z) -> 64, List.of(new Plume(A, new Vec3(55.5, 64, 0.5), 18, Plume.Look.MISSILE)));
        int k = 20;
        Vec3 eye = new Vec3(0.5, 74, 0.5);
        Cameras.View at = Cameras.View.of(Cameras.Pose.look(eye, new Vec3(55.5, 74, 0.5), FOV), ASPECT);
        Cameras.View away = Cameras.View.of(Cameras.Pose.look(eye, new Vec3(-55.5, 74, 0.5), FOV), ASPECT);
        assertTrue(p.smokeClearance(k, eye.x + 24, eye.y, eye.z, 99) > 0, "24 blocks ahead is still clear");
        assertTrue(p.smother(k, at) >= FrameLog.SMOTHERED, "frame at the smoke: " + p.smother(k, at));
        assertEquals(0, p.smother(k, away));
    }

    @Test
    void staysInsideTheReplayChunks() {
        // the client has no chunks east of x = 90 or south of z = 70
        Pilot.Terrain t = (x, z) -> x >= 90 || z >= 70 ? Double.NaN : 64;
        Pilot p = pilot(t, List.of());
        for (Pilot.Flight f : p.plan(2)) {
            Pilot.Track tr = check(p, f);
            int out = 0, n = 0;
            for (int s = A; s <= B; s += 2, n++) if (p.grid.lowOut(p.view(tr, s - tr.from())) > 0) out++;
            // rendering rejects a shot where this happens in more than 2% of frames
            assertTrue(out <= n / 50, f.name() + ": frame bottom off chunks in " + out + " of " + n + " samples");
        }
    }

    @Test
    void passesAlongTheEdgeOfTheReplayChunks() {
        // impact 40 blocks from the boundary: the client has no chunks beyond z = 40 (a seaport the recorder never visited);
        // the Shahed flies along the boundary, then from the side without chunks, with a closer boundary (32 blocks:
        // moments.py margin for render distance)
        edge(40, shahed());
        edge(32, shahed(0, 1));
    }

    private static void edge(double at, Subject shell) {
        Pilot.Terrain t = (x, z) -> z >= at ? Double.NaN : 64;
        Pilot p = pilot(t, List.of(), shell);
        for (Pilot.Flight f : p.plan(2)) {
            Pilot.Track tr = check(p, f);
            int out = 0, n = 0;
            for (int s = A; s <= B; s += 2, n++) if (p.grid.lowOut(p.view(tr, s - tr.from())) > 0) out++;
            assertTrue(out <= n / 50, f.name() + ": frame bottom off chunks in " + out + " of " + n + " samples");
        }
    }

    @Test
    void sameSeedSameFlight() {
        Pilot.Flight one = pilot((x, z) -> 64, List.of()).plan(1).get(0), two = pilot((x, z) -> 64, List.of()).plan(1).get(0);
        assertEquals(one.name(), two.name());
        assertEquals(one.cost(), two.cost());
    }

    @Test
    void controlsFlyStraightAndInCircles() {
        Pilot p = pilot((x, z) -> 64, List.of());
        Vec3 at = new Vec3(10, 80, -5);
        // westward (heading 90°), one block per tick
        Pilot.Track line = p.fly(p.controls.steady(at, 90, 1, 0, 0));
        for (int t : new int[]{p.t0, p.tp - 37, p.tp, p.tp + 50, p.t1}) {
            Vec3 want = at.add(-(t - p.tp), 0, 0);
            assertTrue(line.pos()[t - p.t0].distanceTo(want) < 1e-6, "place at " + t + ": " + line.pos()[t - p.t0]);
            assertTrue(line.vel()[t - p.t0].distanceTo(new Vec3(-1, 0, 0)) < 1e-6);
        }
        // right turn of 2° per tick: a circle of radius 1 / 0.0349 = 28.6 blocks; a quarter turn in 45 ticks (west to north);
        // right bank is negative
        Pilot.Track turn = p.fly(p.controls.steady(at, 90, 1, 2, 0));
        double r = 1 / Math.toRadians(2);
        assertTrue(turn.pos()[p.tp + 45 - p.t0].distanceTo(at.add(-r, 0, -r)) < 0.3, "quarter circle: " + turn.pos()[p.tp + 45 - p.t0]);
        double far = 0;
        for (Vec3 q : turn.pos()) far = Math.max(far, q.distanceTo(at));
        assertEquals(2 * 28.65, far, 0.5, "circle diameter");
        int mid = p.tp - p.t0;
        assertTrue(turn.roll()[mid] < -10, "right turn banks right: " + turn.roll()[mid]);
    }

    @Test
    void handsShakeAfterTheBlastReachesTheCamera() {
        Cameras.Path still = new Cameras.Path() {
            @Override
            public Cameras.Pose at(double t) {
                return new Cameras.Pose(new Vec3(0, 80, 0), 0, 10, FOV);
            }
        };
        Cameras.Path h = Hands.humanize(still, 1, List.of(new Hands.Blast(100, new Vec3(0, 64, 60), 18)), 0, 200);
        double before = 0, after = 0;
        for (int t = 80; t < 100; t++) before = Math.max(before, Math.abs(h.at(t).pitch() - 10));
        // wave reaches the camera (62 blocks) in 3.6 ticks
        for (int t = 104; t < 124; t++) after = Math.max(after, Math.abs(h.at(t).pitch() - 10));
        assertTrue(before < 1.2, "hand tremor only before the blast: " + before);
        assertTrue(after > before + 0.5, "kick after the blast: " + after + " vs " + before);
        for (int t = 0; t <= 200; t++) assertTrue(h.at(t).pos().distanceTo(new Vec3(0, 80, 0)) < 0.6, "place stays at tick " + t);
    }
}
