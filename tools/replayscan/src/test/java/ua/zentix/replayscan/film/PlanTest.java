package ua.zentix.replayscan.film;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Film plan as written by {@code tools/clips/plan.py}, subject by tick, frame and camera keys. */
class PlanTest {
    private static final String PLAN = """
            {"replay": "r.zip", "out": "film", "width": 1080, "height": 1920, "fps": 60, "bitrate": 40000000, "tries": 3,
             "incoming": "2.10.3", "previews": {"rockets-0": ["rockets-0-impact"]},
             "shots": [{"name": "rockets-0-impact", "a": 100, "b": 260, "dim": "minecraft:overworld", "warm": 200, "hold": 20,
               "far": false, "why": "test",
               "cameras": [{"kind": "pov", "player": "player:ZentixUA", "fov": 70},
                           {"kind": "tripod", "around": [0, 64, 100], "dist": [60, 90], "elev": [6, 16], "share": 0.3, "follow": true, "size": 8},
                           {"kind": "fixed", "eye": [0, 80, 0], "aim": "subject", "fov": 12, "rise": [0, 24]}],
               "subject": {"size": 3, "uuid": "00000000-0000-0000-0000-000000000001",
                           "path": [[120, 0, 80, 0], [160, 0, 72, 80], [140, 0, 76, 40]],
                           "keys": [{"t": 170, "pos": [0, 64, 100], "size": 8}]},
               "smoke": [[50, 30, 64, 100]],
               "need": {"subject": 0.4, "near": 0.5, "keys": 1, "share": 0.004}}]}
            """;

    private static Plan plan() throws Exception {
        Path f = Files.createTempFile("plan", ".json");
        Files.writeString(f, PLAN);
        return Plan.read(f);
    }

    @Test
    void readsPlanOfPlanPy() throws Exception {
        Plan p = plan();
        assertEquals(1080, p.width());
        assertEquals(1, p.shots().size());
        Plan.Shot s = p.shots().get(0);
        assertEquals(3, s.cameras().size());
        assertEquals("player:ZentixUA", s.cameras().get(0).player());
        assertTrue(s.cameras().get(1).follow());
        assertTrue(s.cameras().get(2).follow(), "aim: subject — camera tracks the subject");
        assertArrayEquals(new double[]{0, 24}, s.cameras().get(2).rise());
        assertArrayEquals(new double[]{0}, s.cameras().get(1).rise(), "without rise — the eye itself");
        assertEquals(0.5, s.need().near());
        assertEquals(1, s.need().loud(), "off-screen loudness no greater than on-screen loudness by default");
        assertEquals(-1, s.time(), "without time — the recorded time of day");
        assertTrue(p.loud().matcher("incoming:blast.near").find());
        assertFalse(p.loud().matcher("incoming:drone.engine").find());
        // Grad flight sound is the approach, not a motor; an explosion does not count as flight sound
        assertTrue(p.engine().matcher("incoming:rocket.incoming").find());
        assertTrue(p.engine().matcher("airstrike:drone.engine").find());
        assertTrue(p.engine().matcher("incoming:loiter.dive").find());
        assertFalse(p.engine().matcher("incoming:rocket.blast").find());
    }

    @Test
    void onlySmokeOfTheLiveReplayIsInFrame() throws Exception {
        // rendering runs live from a − warm = −100: an earlier impact comes from seeking, and Incoming does not render it
        Plan.Shot s = plan().shots().get(0);
        Plan.Shot t = new Plan.Shot(s.name(), s.a(), s.b(), s.dim(), s.warm(), s.hold(), s.far(), s.cameras(), s.subject(),
                List.of(new double[]{-150, 0, 64, 0}, new double[]{-100, 0, 64, 0}, new double[]{120, 0, 64, 0}), s.need(), s.time());
        assertEquals(List.of(-100.0, 120.0), t.live().stream().map(r -> r[0]).toList());
        assertEquals(3, t.smoke().size());
    }

    @Test
    void subjectFollowsPathThenEvent() throws Exception {
        Subject s = plan().shots().get(0).subject();
        assertEquals(170, s.firstKey());
        // path is sorted: 150 is between 140 and 160
        assertEquals(60, s.aim(150).z, 1e-9);
        assertEquals(3, s.aimSize(150));
        // before appearance: position of the first path point
        assertEquals(0, s.aim(100).z, 1e-9);
        // event: look at it, the fireball rises
        assertEquals(100, s.aim(175).z, 1e-9);
        assertTrue(s.aim(210).y > 64);
        assertEquals(8, s.aimSize(175));
        // after events and path: position of the last event
        assertEquals(100, s.aim(400).z, 1e-9);
        // approach from north to south
        assertEquals(1, s.heading().z, 1e-6);
        // path velocity: 40 blocks south and 4 down over 20 ticks
        assertEquals(Math.hypot(40, 4) / 20, s.speed(150), 1e-9);
    }

    @Test
    void pathCameraFliesBetweenKeys() throws Exception {
        Path f = Files.createTempFile("plan", ".json");
        Files.writeString(f, """
                {"replay": "r.zip", "out": "film", "shots": [{"name": "fly-0-fly", "a": 100, "b": 300, "far": true,
                  "cameras": [{"kind": "path", "fov": 60, "rise": [0, 16], "keys": [{"t": 300, "eye": [200, 120, 0], "aim": [200, 100, 100]},
                                                                  {"t": 100, "eye": [0, 120, 0], "aim": [0, 100, 100]}]}],
                  "subject": {}, "need": {"subject": 0, "near": 0, "keys": 0, "share": 0}}]}
                """);
        Plan.Camera c = Plan.read(f).shots().get(0).cameras().get(0);
        assertEquals(new Vec3(0, 120, 0), c.eye(), "eye from the earliest key");
        Cameras.Path p = Cameras.along(c.keys());
        assertEquals(100, p.at(200).pos().x, 1e-9);
        assertEquals(0, p.at(50).pos().x, 1e-9, "before the first key: at that key");
        assertEquals(200, p.at(400).pos().x, 1e-9, "after the last key: at that key");
        assertEquals(0, p.at(150).yaw(), 1e-6, "look south throughout the path");
        assertEquals(Math.toDegrees(Math.atan2(20, 100)), p.at(250).pitch(), 1e-4);
        assertEquals(60, p.at(250).fov());
        assertArrayEquals(new double[]{0, 16}, c.rise());
    }

    @Test
    void lensFitsEventWidth() {
        // an 18-block fireball at 500 blocks is 12% of the vertical frame width
        double fov = Cameras.fovFor(18, 500, 0.12, 9 / 16.0);
        double tanX = Math.tan(Math.toRadians(fov / 2)) * 9 / 16.0;
        assertEquals(0.12, 18 / (2 * 500 * tanX), 1e-6);
        assertEquals(70, Cameras.fovFor(100, 10, 0.5, 9 / 16.0), "no wider than 70°");
    }

    @Test
    void inFrameUsesAspect() {
        Cameras.Pose p = Cameras.Pose.look(Vec3.ZERO, new Vec3(0, 0, 100), 70);
        assertEquals(0, p.yaw(), 1e-6, "look south: yaw 0");
        // 30 blocks sideways at 100 blocks: in the wide frame and also in the vertical frame (tan 0.7·0.5625 = 0.39 > 0.3)
        assertTrue(Cameras.View.of(p, 9 / 16.0).in(new Vec3(30, 0, 100), 1));
        // 50 sideways: only in the wide frame
        assertFalse(Cameras.View.of(p, 9 / 16.0).in(new Vec3(50, 0, 100), 1));
        assertTrue(Cameras.View.of(p, 16 / 9.0).in(new Vec3(50, 0, 100), 1));
    }

    @Test
    void viewOfPoseMatchesFrame() {
        // evaluation frame matches the render: a point near the width edge, the fraction of frame width occupied by an object
        Cameras.Pose p = Cameras.Pose.look(new Vec3(0, 64, 0), new Vec3(0, 64, 100), 70);
        Cameras.View v = Cameras.View.of(p, 9 / 16.0);
        double edge = 100 * Math.tan(Math.toRadians(35)) * 9 / 16.0;
        assertTrue(v.in(new Vec3(edge * 0.99, 64, 100), 1));
        assertFalse(v.in(new Vec3(edge * 1.01, 64, 100), 1));
        assertFalse(v.in(new Vec3(edge * 0.9, 64, 100), Cameras.SAFE), "evaluation field is narrower than the frame");
        assertFalse(v.in(new Vec3(0, 64, -100), 1), "behind the camera");
        assertEquals(0.5, v.share(new Vec3(0, 64, 100), edge), 1e-9);
        assertEquals(1, v.right().dot(new Vec3(-1, 0, 0)), 1e-9, "looking south: right is west");
        assertEquals(1, v.up().y, 1e-9);
    }

    @Test
    void keyPointRises() {
        Subject.Key k = new Subject.Key(100, new Vec3(0, 64, 0), 20);
        assertEquals(64, Cameras.keyPoint(k, 100).y, 1e-9);
        assertEquals(64 + 20 * 0.4 * 0.5, Cameras.keyPoint(k, 120).y, 1e-9);
        assertEquals(64 + 20 * 0.4, Cameras.keyPoint(k, 200).y, 1e-9, "no higher than 0.4 of the diameter");
    }

    @Test
    void editorStateKeysEveryTickAtFeet() {
        Cameras.Path path = t -> Cameras.Pose.look(new Vec3(0, 80, 0), new Vec3(Math.sin(t / 10.0) * 10, 64, 50), 30);
        JsonObject st = JsonParser.parseString(Export.editorState(path, 100, 120, 1.62, false, -1)).getAsJsonObject();
        assertNull(st.getAsJsonObject("replayVisuals").get("overrideTimeOfDay"), "time of day matches the recording");
        JsonObject scene = st.getAsJsonArray("scenes").get(0).getAsJsonObject();
        assertEquals(100, scene.get("exportStartTicks").getAsInt());
        JsonObject cam = scene.getAsJsonArray("keyframeTracks").get(0).getAsJsonObject().getAsJsonObject("keyframesByTick");
        assertNotNull(cam.get("58"), "keys start with a margin before the beginning");
        assertEquals(80 - 1.62, cam.getAsJsonObject("110").getAsJsonArray("position").get(1).getAsDouble(), 1e-9);
        // through the player eyes: no tracks
        JsonObject pov = JsonParser.parseString(Export.editorState(null, 100, 120, 1.62, true, 18000)).getAsJsonObject();
        assertEquals(0, pov.getAsJsonArray("scenes").get(0).getAsJsonObject().getAsJsonArray("keyframeTracks").size());
        assertEquals(18000, pov.getAsJsonObject("replayVisuals").get("overrideTimeOfDay").getAsLong(), "night in the render");
    }

    @Test
    void pathRollReachesKeyframes() {
        List<Plan.PathKey> keys = List.of(new Plan.PathKey(100, new Vec3(0, 80, 0), new Vec3(0, 70, 50), 0, 80),
                new Plan.PathKey(120, new Vec3(0, 80, 20), new Vec3(0, 70, 70), 20, 80));
        Cameras.Path path = Cameras.along(keys);
        assertEquals(10, path.roll(110), 1e-9, "bank between keys");
        assertEquals(20, path.roll(130), 1e-9, "after the last key: its value");
        JsonObject st = JsonParser.parseString(Export.editorState(path, 100, 120, 1.62, false, -1)).getAsJsonObject();
        JsonObject cam = st.getAsJsonArray("scenes").get(0).getAsJsonObject().getAsJsonArray("keyframeTracks").get(0).getAsJsonObject()
                .getAsJsonObject("keyframesByTick");
        assertEquals(5, cam.getAsJsonObject("105").get("roll").getAsDouble(), 1e-6);
    }

    @Test
    void pathZoomReachesNativeFovTrackIncludingRaisedCandidates() throws Exception {
        Path file = Files.createTempFile("camera-zoom", ".json");
        try {
            Files.writeString(file, """
                    {"replay":"r.zip","out":"film","shots":[{"name":"zoom","a":100,"b":120,
                      "cameras":[{"kind":"path","fov":80,"rise":[0,16],"keys":[
                        {"t":100,"eye":[0,80,0],"aim":[0,80,100]},
                        {"t":120,"eye":[0,80,0],"aim":[0,80,100],"fov":140}]}],"subject":{}}]}
                    """);
            Plan.Shot shot = Plan.read(file).shots().get(0);
            for (Cameras.Candidate candidate : Cameras.of(shot.cameras().get(0), shot, 16 / 9.0, null)) {
                Cameras.Path path = candidate.path();
                assertEquals(80, path.at(90).fov(), "missing key FOV inherits the camera's lens");
                assertEquals(110, path.at(110).fov(), 1e-9, "lens interpolates between keys");
                assertEquals(140, path.at(130).fov(), "after the last key: keep that lens");
                JsonObject state = JsonParser.parseString(Export.editorState(path, 100, 120, 1.62, false, -1)).getAsJsonObject();
                JsonObject lenses = state.getAsJsonArray("scenes").get(0).getAsJsonObject()
                        .getAsJsonArray("keyframeTracks").get(1).getAsJsonObject().getAsJsonObject("keyframesByTick");
                assertEquals(110, lenses.getAsJsonObject("110").get("fov").getAsDouble(), 1e-9);
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void keyframeCameraPreservesGuiChoiceForNativeExport() throws Exception {
        for (boolean gui : new boolean[]{false, true}) {
            JsonObject json = JsonParser.parseString(PLAN).getAsJsonObject();
            JsonObject shotJson = json.getAsJsonArray("shots").get(0).getAsJsonObject();
            shotJson.add("cameras", JsonParser.parseString("""
                    [{"kind":"path","gui":%s,"fov":65,
                      "keys":[{"t":100,"eye":[0,80,0],"aim":[0,80,50]},
                              {"t":260,"eye":[0,80,10],"aim":[0,100,50]}]}]
                    """.formatted(gui)));
            Path file = Files.createTempFile("camera-gui", ".json");
            try {
                Files.writeString(file, json.toString());
                Plan.Shot shot = Plan.read(file).shots().get(0);
                List<Cameras.Candidate> candidates = Cameras.of(shot.cameras().get(0), shot, 16 / 9.0, null);
                assertEquals(1, candidates.size());
                assertEquals(gui, candidates.get(0).gui(), "camera conversion must not force noGui on the exporter");
                JsonObject state = JsonParser.parseString(Export.editorState(candidates.get(0).path(), 100, 260, 1.62,
                        candidates.get(0).gui(), -1)).getAsJsonObject();
                assertEquals(gui, state.getAsJsonObject("replayVisuals").get("showHotbar").getAsBoolean());
            } finally {
                Files.deleteIfExists(file);
            }
        }
    }

    @Test
    void rollTiltsFrameLikeFlashback() {
        // southward, banked 30° left: the top of the frame tilts left (east, +x), a point on the right horizon is below center
        Cameras.View v = Cameras.View.of(new Cameras.Pose(Vec3.ZERO, 0, 0, 90), 1).rolled(30);
        assertEquals(0.5, v.up().x, 1e-9);
        assertEquals(Math.cos(Math.toRadians(30)), v.up().y, 1e-9);
        Vec3 d = new Vec3(-5, 0, 10);
        assertEquals(-0.5 * Math.sin(Math.toRadians(30)), d.dot(v.up()) / d.dot(v.fwd()), 1e-9);
        assertEquals(0.5 * Math.cos(Math.toRadians(30)), d.dot(v.right()) / d.dot(v.fwd()), 1e-9);
    }
}
