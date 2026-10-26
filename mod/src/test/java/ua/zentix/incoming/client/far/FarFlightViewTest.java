package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.client.ClientWeaponSpec.FarLook;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.client.fx.plume.Flame;
import ua.zentix.incoming.util.Stability;
import ua.zentix.incoming.client.fx.plume.TrailModel.Trail;
import ua.zentix.incoming.util.Wind;
import ua.zentix.incoming.client.flight.FlightTrack;
import ua.zentix.incoming.client.render.FarModels;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.strike.WeaponType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Projectile in the distance: shadow of the body and trail at any range, torch day and night, trail points by phases and packets. */
class FarFlightViewTest {
    private static final double PIXEL = Math.toRadians(70) / 1080;
    private static final UUID ID = UUID.randomUUID();

    private static FarLook far(WeaponType weapon) {
        return ClientWeaponSpec.of(weapon).airframe(false).far();
    }

    @Test
    void bodyIsTrueSizeAndCoversTheSameSky() {
        double[] out = new double[2];
        for (WeaponType w : WeaponType.values()) {
            FarLook look = far(w);
            double r = Math.sqrt(look.area() / Math.PI);
            for (double d : new double[]{50, 300, 1000, 4000, 8000}) {
                FarFlightView.body(look.area(), d, PIXEL, out);
                assertEquals(Math.max(r, 0.5 * Sight.MIN_PIXELS * PIXEL * d), out[0], 1e-9, w + " on " + d + ": true size or spot");
                assertEquals(r * r, out[1] * out[0] * out[0], 1e-9, w + " on " + d + ": the same sky is closed");
            }
        }
    }

    @Test
    void droneFadesWithDistanceLikeACamera() {
        // silhouette of Shahed (span 2,5 m) - circle 1,07 m: beyond 1,5 km - 1,3 px, spot in 1,5 px a little paler; beyond 4 km - pale dot
        double[] out = new double[2];
        FarFlightView.body(far(WeaponType.DRONE).area(), 1500, PIXEL, out);
        assertEquals(0.5 * Sight.MIN_PIXELS * PIXEL * 1500, out[0], 1e-9);
        assertTrue(out[1] > 0.6 && out[1] < 0.8, "for 1,5 km: " + out[1]);
        FarFlightView.body(far(WeaponType.DRONE).area(), 4000, PIXEL, out);
        assertTrue(out[1] > 0.05 && out[1] < 0.15, "for 4 km: " + out[1]);
        // B-2 (range 52 m) for 8 km - more spot: real size and continuous
        FarFlightView.body(far(WeaponType.BUNKER).area(), 8000, PIXEL, out);
        assertEquals(1, out[1], 1e-12);
    }

    @Test
    void thinTrailKeepsItsCoverage() {
        double[] out = new double[2];
        FarFlightView.ribbon(4, 100, PIXEL, out);
        assertEquals(2 * FarSprites.RIBBON, out[0], 1e-9, "close - its width");
        assertEquals(1, out[1], 1e-12);
        FarFlightView.ribbon(1, 5000, PIXEL, out);
        double drawn = out[0] / FarSprites.RIBBON;
        assertEquals(0.75 * PIXEL * 5000, drawn, 1e-9, "already a pixel - one and a half pixels");
        assertEquals(1, 2 * drawn * out[1], 1e-9, "and paler by the same amount");
    }

    @Test
    void rocketMotorIsSparkByDayAndGlareAtNight() {
        Flame flame = far(WeaponType.ROCKET).stage(FlightPhase.BOOST).flame(Air.SEA);
        assertNotNull(flame);
        double d = 2000, t = Sight.transmittance(d, Sight.CLEAR);
        double[] day = new double[5], night = new double[5];
        Sight.light(flame.lightRadius(), Sight.adapted(flame.brightness(), 1) * t, d, PIXEL, day);
        Sight.light(flame.lightRadius(), Sight.adapted(flame.brightness(), 0.17) * t, d, PIXEL, night);
        assertTrue(day[2] < 1 && day[4] == 0, "during the day - a spark without a veil: " + day[2]);
        assertTrue(night[2] > 1 && night[4] / d > 0.01, "at night - glare and veil: " + night[2] + ", " + Math.toDegrees(night[4] / d) + "°");
        // at night visible even for 8 km
        double far = 8000;
        Sight.light(flame.lightRadius(), Sight.adapted(flame.brightness(), 0.17) * Sight.transmittance(far, Sight.CLEAR), far, PIXEL, night);
        assertTrue(night[2] > 1, "for 8 km at night: " + night[2]);
    }

    /**
     * Night launch from behind (scene look gate launch-night-behind): camera at 8,4 m from the middle of the rocket accelerator torch. The eye
     *  also gets used to the fire in itself ({@code HeatPass} collects it in the frame, {@link Sight#exposure} - one habituation per frame):
     *  the torch is brighter than white by a few times, and the glare is a pale halo. With one night ×1200 (complaint 22.10: “the frame fills
     *  with orange”) the glare was in full force across the entire screen. In the distance, the torch does not retrain the eye: the glare and veil are 2 km away - as they were.
     */
    @Test
    void nearFlameAtNightAdaptsTheEye() {
        Flame flame = far(WeaponType.MISSILE).stage(FlightPhase.BOOST).flame(Air.SEA);
        assertNotNull(flame);
        double night = 0.17, d = 8.4, r = flame.lightRadius();
        double[] out = new double[5];
        // in the old way: only the light of the sky
        double old = flame.brightness() * Sight.adapted(1, night);
        Sight.light(r, old, d, PIXEL, out);
        assertEquals(Sight.HALO, Math.min(Sight.HALO, Sight.SCATTER * old), 1e-12, "night ×1200: glare in full force");
        assertTrue(out[3] / d > Math.toRadians(70), "and wider than the screen: " + Math.toDegrees(out[3] / d) + "°");
        double exposure = exposureNear(flame, d, night);
        double seen = flame.brightness() * exposure;
        Sight.light(r, seen, d, PIXEL, out);
        assertTrue(Math.min(Sight.HALO, Sight.SCATTER * seen) < 0.1, "the glare of the torch near the eye is pale: " + Sight.SCATTER * seen);
        assertTrue(seen > 1, "the torch itself is brighter than white: " + seen);
        // illumination of the white wall near the eye: in the old way - hundreds of times brighter than white (the screen will not show it)
        double wall = HeatPassAtEye.at(flame, d);
        assertTrue(wall * Sight.adapted(1, night) > 500, "in the old way - hundreds of times brighter than white: " + wall * Sight.adapted(1, night));
        // for 2 km the fire in the eye hardly touches the eye
        assertTrue(exposureNear(flame, 2000, night) > 0.95 * Sight.adapted(1, night), "far away - night addiction");
    }

    /**
     * Accustoming the eye of a frame with one torch in d blocks - in the same way as in the game: the torch of the frame ({@link FarFlightView#lit}) -
     *  into the light of the world of this frame ({@link FarFlightView#heat}) and into the light of the eye of the next ({@link FarFlightView#flamesAtEye},
     * {@code HeatPass.Frame.eye}), addiction - {@link Sight#exposure}.
     */
    private static double exposureNear(Flame flame, double d, double ambient) {
        try {
            FarFlightView.lit(d, 0, 0, 0, -1, 0, flame, Vec3.ZERO, 0, Double.POSITIVE_INFINITY);
            FarFlightView.heat(new ua.zentix.incoming.client.fx.layer.HeatPass.Frame(), 0);
            ua.zentix.incoming.client.fx.layer.HeatPass.Frame next = new ua.zentix.incoming.client.fx.layer.HeatPass.Frame();
            next.eye(FarFlightView.flamesAtEye());
            return Sight.exposure(ambient, next.atEye());
        } finally {
            FarFlightView.reset();
        }
    }

    /** Illumination of the white wall near the eye from the torch in d blocks (linear, noon - 1): brightness × (r/d)². */
    private static final class HeatPassAtEye {
        static double at(Flame flame, double d) {
            double r = flame.lightRadius();
            return flame.brightness() * r * r / Math.max(d * d, r * r);
        }
    }

    /**
     * SAM trace close up (complaint 22.10: “a flat tape with straight edges, the camera has a plane across the frame”): the tube, large on the
     *  screen, is drawn by billows like clouds, and the tape remains where the tube is - a line of several pixels. The width is at the
     *  point of the segment closest to the eye: the segment that passes near the camera itself is only billows.
     */
    @Test
    void wideTubeOnScreenIsPuffsNotARibbon() {
        double width = 5;
        assertEquals(1, FarFlightView.puffShare(width, 30, PIXEL), 1e-12, "5 m in 30 blocks – billows");
        assertEquals(1, FarFlightView.puffShare(width, 0.5, PIXEL), 1e-12, "eye at the tube - billows and veil, not a plane");
        assertEquals(0, FarFlightView.puffShare(width, 2000, PIXEL), 1e-12, "for 2 km - line: tape");
        // salvo "Grad" for 1,7 km (frames VXC4, 22.10): trail in 10 s - 11 m (5,7 px) - billows, not a strip of tapes; in 1 s - 3 m
        //  (1,6 px, cross-section narrower than a pixel) - tape
        assertEquals(1, FarFlightView.puffShare(11, 1700, PIXEL), 1e-12, "trail \"Grad\" in 10 s for 1,7 km - billows");
        assertEquals(0, FarFlightView.puffShare(3, 1700, PIXEL), 1e-12, "trail in 1 s for 1,7 km - tape");
        double prev = 1;
        for (double gap = 10; gap < 3000; gap *= 1.05) {
            double s = FarFlightView.puffShare(width, gap, PIXEL);
            assertTrue(s <= prev + 1e-12, "further - less billows, no jumps");
            assertTrue(prev - s < 0.2, "smooth transition: " + gap);
            prev = s;
        }
    }

    /**
     * The trail smoke is from the end of the bright flame ({@link ua.zentix.incoming.client.fx.plume.TrailModel#emerged}, {@link Flame#flame}):
     *  the substance at the nozzle is the flame itself, after the end of the burnout the smoke appears, towards one and a half lengths - all of it; without flame - smoke
     *  immediately. The cooling tail to the Draper point ({@link Flame#length}) does not shine on the screen: there is already smoke there, otherwise
     *  there would be a gap of tens of meters between the fire and the smoke (complaint from the review of frames 22.10). The path is the one that the projectile flew
     *  from the birth point: the SAM rocket (launch at 16 m/s, ~180 m/s²) has a point born at the launch, through 0,5 s already at ~30 m.
     */
    @Test
    void trailSmokeStartsWhereTheFlameEnds() {
        Flame f = far(WeaponType.MISSILE).stage(FlightPhase.BOOST).flame(Air.SEA);
        assertNotNull(f);
        double flame = f.flame();
        assertTrue(f.tailLuminance() < 0.01 * f.flameLuminance(), "the tail behind the flame does not shine: " + f.tailLuminance());
        assertEquals(0, ua.zentix.incoming.client.fx.plume.TrailModel.emerged(0, flame), 1e-12, "the nozzle has a flame");
        assertEquals(0, ua.zentix.incoming.client.fx.plume.TrailModel.emerged(flame, flame), 1e-12);
        assertEquals(1, ua.zentix.incoming.client.fx.plume.TrailModel.emerged(1.5 * flame, flame), 1e-12, "behind the flame is smoke");
        assertTrue(1.5 * flame < 0.5 * f.length(), "smoke - long before the Draper point: " + flame + " against " + f.length());
        assertEquals(1, ua.zentix.incoming.client.fx.plume.TrailModel.emerged(0, 0), 1e-12, "without flame - smoke immediately");
        double prev = 0;
        for (double x = 0; x <= 3 * flame; x += 0.05) {
            double e = ua.zentix.incoming.client.fx.plume.TrailModel.emerged(x, flame);
            assertTrue(e >= prev && e - prev < 0.05, "smoothly: " + x);
            prev = e;
        }
        // path of the accelerating SAM rocket for 0,5 s: v₀·t + a·t²/2
        Flame sam = ua.zentix.incoming.client.fx.plume.FlameModel.at(ClientWeaponSpec.SAM_BOOST, 0);
        double path = 16 * 0.5 + 0.5 * 180 * 0.25;
        assertEquals(1, ua.zentix.incoming.client.fx.plume.TrailModel.emerged(path, sam.flame()), 1e-12, "after 0,5 s after launch - smoke");
    }

    @Test
    void cruiseMissileEngineDoesNotGlow() {
        // turbofan exhaust — ~700 K without particles: neither day nor night
        assertNull(far(WeaponType.MISSILE).stage(FlightPhase.CRUISE).flame(Air.SEA));
    }

    // ---------------------------------------------------------------- trail points along the way

    private static S2C.FarFlight packet(WeaponType weapon, long tick, FlightPhase phase) {
        return new S2C.FarFlight(ID, weapon.id(), false, false, true, new Vec3(tick * 3.0, 80 + tick, 0), new Vec3(3, 1, 0), 0, 0, 0,
                phase.ordinal(), 0, new Vec3(9000, 80, 0));
    }

    /** Path by packets times per 2 tick: phase - by tick. */
    private static FlightTrack track(WeaponType weapon, long from, long to, java.util.function.LongFunction<FlightPhase> phase) {
        FlightTrack t = new FlightTrack(ID, weapon, false);
        for (long tick = from; tick <= to; tick += 2) t.record(tick, packet(weapon, tick, phase.apply(tick)));
        return t;
    }

    private record Point(long serial, long tick, Trail style, long prev, double x, boolean last) {}

    private static List<Point> points(TrailPoints p) {
        List<Point> out = new ArrayList<>();
        for (long s = p.first(); s < p.end(); s++) {
            int i = TrailPoints.index(s);
            out.add(new Point(s, p.birth[i], p.style[i], p.prev[i], p.x[i], p.last[i]));
        }
        return out;
    }

    /**
     * The "Grad" trail is one tape along the path from the launch rail exit to the actual burnout path ({@link Trail#reach}): the game engine
     *  goes out at tick 50 (the phase of the next packet is with tick 51), and the tape goes further until the path reaches the section, and fades out at that point.
     */
    @Test
    void rocketTrailRunsAlongTheRealBurnPath() {
        FarLook look = far(WeaponType.ROCKET);
        Trail trail = look.stage(FlightPhase.BOOST).trail();
        FlightTrack t = track(WeaponType.ROCKET, 10, 400, tick -> tick <= 50 ? FlightPhase.BOOST : FlightPhase.CRUISE);
        TrailPoints p = new TrailPoints();
        FarFlightView.sample(new FarFlightView.Far(t, 1), 400, p);
        List<Point> pts = points(p);
        // path — √10 block per tick: burnout section passed in tick 10 + ⌈reach / √10⌉
        double step = Math.sqrt(10);
        long end = 10 + (long) Math.ceil(trail.reach() / step);
        Point last = pts.getLast();
        assertEquals(end, last.tick, "the tape ends where the burnout section is passed");
        assertTrue(last.last, "and fades out there");
        assertTrue(pts.stream().anyMatch(pt -> pt.tick > 60), "after the torch there is still smoke");
        for (int i = 0; i < pts.size(); i++) {
            Point pt = pts.get(i);
            assertSame(trail, pt.style);
            assertEquals(pt.tick * 3.0, pt.x, 1e-9, "place - on the way");
            assertEquals(i == 0 ? TrailPoints.NONE : pts.get(i - 1).serial, pt.prev, "one tape");
            assertEquals(i == pts.size() - 1, pt.last, "ribbon fades out only at the segment end");
        }
    }

    @Test
    void boosterTrailEndsWhereThePistonLeavesNone() {
        // accelerator smoke - tape until it burns out; the piston exhaust is not visible at this speed - there are no dots after it
        FarLook look = far(WeaponType.DRONE);
        FlightTrack t = track(WeaponType.DRONE, 0, 40, tick -> tick <= 20 ? FlightPhase.BOOST : FlightPhase.CRUISE);
        TrailPoints p = new TrailPoints();
        FarFlightView.sample(new FarFlightView.Far(t, 1), 40, p);
        List<Point> pts = points(p);
        Trail booster = look.stage(FlightPhase.BOOST).trail();
        assertFalse(pts.isEmpty());
        for (Point pt : pts) assertSame(booster, pt.style);
        assertTrue(pts.getLast().last, "booster ribbon fades out");
        assertTrue(pts.getLast().tick <= 22);
    }

    @Test
    void ribbonToneMatchesSmokeTextures() throws java.io.IOException {
        // volume billows lie on the tape: with the same average tone they are its irregularities, not dark beads
        double light = 0, cover = 0;
        for (int k = 0; k < 16; k++) {
            String path = String.format(java.util.Locale.ROOT, "/assets/incoming/textures/fx/particle/smoke_%02d.png", k);
            try (java.io.InputStream in = FarFlightViewTest.class.getResourceAsStream(path)) {
                assertNotNull(in, path);
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(in);
                for (int y = 0; y < img.getHeight(); y++) {
                    for (int x = 0; x < img.getWidth(); x++) {
                        int argb = img.getRGB(x, y);
                        double a = (argb >>> 24) / 255.0;
                        light += a * ((argb >> 16 & 255) + (argb >> 8 & 255) + (argb & 255)) / (3 * 255.0);
                        cover += a;
                    }
                }
            }
        }
        assertEquals(FarFlightView.TONE, light / cover, 0.03);
    }

    @Test
    void everyTrailNozzleFitsTheFrameCache() {
        for (WeaponType w : WeaponType.values()) {
            for (boolean payload : new boolean[]{false, true}) {
                ClientWeaponSpec.ClientAirframe a = ClientWeaponSpec.of(w).airframe(payload);
                for (boolean booster : new boolean[]{false, true}) {
                    assertTrue(a.trailNozzles(booster).size() <= FarFlightView.MAX_NOZZLES, w + " " + payload + " " + booster);
                }
            }
        }
    }

    @Test
    void rocketTrailStartsAtTheMuzzleNotInTheTube() {
        // ignition - in a pipe: fire without a trace (a ribbon wider than the pipe would stick out from it), a trace - from the first tick of acceleration
        FlightTrack t = track(WeaponType.ROCKET, 0, 20, tick -> tick < 6 ? FlightPhase.IGNITION : FlightPhase.BOOST);
        TrailPoints p = new TrailPoints();
        FarFlightView.sample(new FarFlightView.Far(t, 1), 20, p);
        List<Point> pts = points(p);
        long boost = 0;
        while (t.phase(boost) != FlightPhase.BOOST.ordinal()) boost++;
        assertEquals(boost, pts.getFirst().tick);
        assertEquals(TrailPoints.NONE, pts.getFirst().prev);
    }

    /** Path B-2 at the height of y of the world. */
    private static FlightTrack bomber(double y) {
        FlightTrack t = new FlightTrack(ID, WeaponType.BUNKER, true);
        for (long tick = 0; tick <= 40; tick += 2) {
            t.record(tick, new S2C.FarFlight(ID, WeaponType.BUNKER.id(), true, false, true, new Vec3(0, y, tick * 12.0), new Vec3(0, 0, 12), 0, 0, 0,
                    FlightPhase.CRUISE.ordinal(), 0, new Vec3(0, 60, 9000)));
        }
        return t;
    }

    @Test
    void bomberLeavesNoContrailWhereAirIsWarm() {
        // today's 300 blocks are +14 °C: according to Schmidt-Appleman there is no inversion
        TrailPoints p = new TrailPoints();
        FarFlightView.sample(new FarFlightView.Far(bomber(300), 1), 40, p);
        assertTrue(p.isEmpty());
    }

    @Test
    void bomberLeavesAContrailFromEachEngine() {
        // at 11 km in humid air, inversion occurs for each of the four engines
        Air before = FarFlightView.air;
        FarFlightView.air = new Air(new Wind(0, 10), Stability.D, 1);
        TrailPoints p = new TrailPoints();
        try {
            FarFlightView.sample(new FarFlightView.Far(bomber(Air.SEA + 11_000), 1), 40, p);
        } finally {
            FarFlightView.air = before;
        }
        List<ClientWeaponSpec.At> engines = ClientWeaponSpec.of(WeaponType.BUNKER).airframe(false).engines();
        List<Point> pts = points(p);
        assertEquals(engines.size() * 11, pts.size(), "each nozzle has a dot once per 4 tick");
        for (int i = 0; i < pts.size(); i++) {
            Point pt = pts.get(i);
            // nose along +Z (yaw 0): left - along +X
            assertEquals(engines.get(i % engines.size()).x(), pt.x, 1e-4, "tape from your nozzle");
            assertEquals(i < engines.size() ? TrailPoints.NONE : pts.get(i - engines.size()).serial, pt.prev, "and yours");
        }
    }

    @Test
    void samplingInStepsGivesTheSameTrail() {
        FlightTrack t = track(WeaponType.NUKE, 0, 200, tick -> tick < 40 ? FlightPhase.IGNITION : FlightPhase.BOOST);
        TrailPoints once = new TrailPoints(), steps = new TrailPoints();
        FarFlightView.sample(new FarFlightView.Far(t, 1), 200, once);
        FarFlightView.Far f = new FarFlightView.Far(t, 1);
        for (long h = 0; h <= 200; h += 3) FarFlightView.sample(f, h, steps);
        FarFlightView.sample(f, 200, steps);
        assertEquals(points(once), points(steps));
    }

    @Test
    void modelReplacesTheDotBetweenTwoAndThreePixels() {
        assertEquals(0, FarFlightView.modelShare(0.5));
        assertEquals(0, FarFlightView.modelShare(FarModels.DOT_BELOW));
        assertEquals(0.5, FarFlightView.modelShare((FarModels.DOT_BELOW + FarModels.MODEL_FROM) / 2), 1e-12);
        assertEquals(1, FarFlightView.modelShare(FarModels.MODEL_FROM));
        assertEquals(1, FarFlightView.modelShare(300));
        double prev = 0;
        for (double px = FarModels.DOT_BELOW; px <= FarModels.MODEL_FROM; px += 0.01) {
            double k = FarFlightView.modelShare(px);
            assertTrue(k >= prev, "transition without jumps back: " + px);
            prev = k;
        }
        // simplified copies - with a margin against jitter at the border
        assertTrue(FarModels.COARSE_BELOW < FarModels.FULL_ABOVE);
        assertTrue(FarModels.MODEL_FROM < FarModels.COARSE_BELOW);
    }

    @Test
    void modelOutsideTheScreenIsNotDrawn() {
        // screen - cone in 30° from the middle to the corner, looking north (−Z)
        FarView view = new FarView(Vec3.ZERO, new Vector3f(0, 0, -1), new Vector3f(-1, 0, 0), new Vector3f(0, 1, 0), 0, 1000, PIXEL,
                Math.toRadians(30), 1, 1, new Vector3f(1, 1, 1), 20000, 192, 192, 0, Sight.Air.at(0, 20000));
        assertTrue(FarFlightView.inFrame(view, 0, 0, -500, 500, 2));
        assertTrue(FarFlightView.inFrame(view, at(29)[0], 0, at(29)[1], 500, 2));
        assertFalse(FarFlightView.inFrame(view, at(35)[0], 0, at(35)[1], 500, 2), "in 35° small model behind the edge");
        assertTrue(FarFlightView.inFrame(view, at(35)[0], 0, at(35)[1], 500, 60), "the wing B-2 in 60 blocks enters the frame at the edge");
        assertFalse(FarFlightView.inFrame(view, 0, 0, 500, 500, 2), "behind my back");
    }

    @Test
    void flameIsOneAcrossTheRenderDistanceEdge() {
        // the entity appears at the edge of the drawing: the core of the torch light is already all there, closer it is replaced by a jet of the same fraction,
        //  the path through the packets is always the whole light; highlight and veil - both (FarFlightView.collect)
        double world = 192;
        FarView view = new FarView(Vec3.ZERO, new Vector3f(0, 0, -1), new Vector3f(-1, 0, 0), new Vector3f(0, 1, 0), 0, 1000, PIXEL,
                Math.toRadians(30), 1, 1, new Vector3f(1, 1, 1), 20000, world, 192, 0, Sight.Air.at(0, 20000));
        for (double d = 0; d <= 2 * world; d += 4) {
            double jet = 1 - FarView.farShare(d, world), core = FarFlightView.flameCore(true, view, d);
            assertEquals(1, jet + core, 1e-12, "on " + d + ": jet and core together - one torch");
            assertEquals(1, FarFlightView.flameCore(false, view, d), 1e-12, "in packages - the whole world");
            if (d <= 0.8 * world) assertEquals(0, core, 1e-12, "near the core - jet");
            if (d >= world) assertEquals(1, core, 1e-12, "on the edge - like in packages");
        }
    }

    /** A point in 500 blocks at an angle deg from looking north: x and z. */
    private static double[] at(double deg) {
        return new double[]{500 * Math.sin(Math.toRadians(deg)), -500 * Math.cos(Math.toRadians(deg))};
    }

    /**
     * Grad trail covers the real burn segment at any flight speed (F6 decision): at game speed (~80 m/s), the motor burns
     * for 2 s, ~160 m, while the 9M22U trail is ~650 m (683 m/s at burnout after 1.9 s, spec); it is laid down even after
     * the plume (not physical) and ends at the same distance. Material per meter is real consumption over real distance:
     * total smoke is what the fuel produces.
     */
    @Test
    void gradTrailCoversTheRealBurnPath() {
        Trail trail = far(WeaponType.ROCKET).stage(FlightPhase.BOOST).trail();
        assertNotNull(trail);
        assertEquals(683 * 1.9 / 2, trail.reach(), 1, "burnout area according to spec");
        assertEquals(1.9, trail.burn(), 1e-9);
        // while the phase is burning and after it - the same trace, until the end of the section
        assertEquals(trail, FarFlightView.laying(trail, null, 10));
        assertEquals(trail, FarFlightView.laying(null, trail, 160), "the game engine burned out at 160 m - the smoke is still rising");
        assertEquals(trail, FarFlightView.laying(null, trail, 600));
        assertNull(FarFlightView.laying(null, trail, trail.reach() + 1), "there is no trace of the burnt area");
        assertNull(FarFlightView.laying(trail, null, 1300), "at real speed the phase goes further than the section - the trail ends there");
        // for a projectile without a burnout path, the trail is in phase
        Trail booster = far(WeaponType.MISSILE).stage(FlightPhase.BOOST).trail();
        assertEquals(0, booster.reach());
        assertNull(FarFlightView.laying(null, booster, 10), "rocket booster: trail ends with phase");
        // substance: per meter - along the real path, not according to the speed of the projectile; for the entire area - all the fuel smoke
        double perMetre = trail.motor().smokeFlow() * trail.inverseSpeed(80, 3);
        assertEquals(trail.motor().smokeFlow() * 1.9, perMetre * trail.reach(), 1e-9, "smoke from all fuel - throughout the entire area");
        assertEquals(trail.inverseSpeed(80, 3), trail.inverseSpeed(683, 3), 1e-12, "and at any flight speed");
    }
}
