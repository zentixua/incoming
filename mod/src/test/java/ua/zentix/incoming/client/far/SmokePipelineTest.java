package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke planned off the client thread ({@link SmokePuffs#flush} with a {@link SmokePuffs.Planner}; LEAN-BG 27.10): each
 * frame draws the previous frame's plan, anchored at its own eye. On the 100-strike raid the planned frame k + 1 draws
 * exactly what the inline frame k drew; with a real worker thread too, and leaving the world while a plan runs drains it.
 */
class SmokePipelineTest {
    /** Plans at once, on the caller's thread. */
    static final SmokePuffs.Planner DIRECT = new SmokePuffs.Planner() {
        @Override public void start(Runnable plan) { plan.run(); }
        @Override public boolean await(long nanos) { return true; }
        @Override public void stop() {}
    };

    /** Every call of the frame, as numbers (sprites by identity), and the veil sampled on a grid of rays. */
    static final class Calls implements SmokePuffs.Out {
        final List<double[]> calls = new ArrayList<>();
        final List<FxAtlas.Sprite> sprites = new ArrayList<>();
        double[] veil;

        @Override
        public void puff6(double dx, double dy, double dz, double halfA, double halfB, FxAtlas.Sprite sprite, float mix, float shade, float r, float g,
                          float b, float a) {
            calls.add(new double[]{0, dx, dy, dz, halfA, halfB, mix, shade, r, g, b, a});
            sprites.add(sprite);
        }

        @Override
        public void puff6Flat(double dx, double dy, double dz, double halfA, double halfB, double soft, double angle, FxAtlas.Sprite sprite, float mix,
                              float shade, float r, float g, float b, float a) {
            calls.add(new double[]{1, dx, dy, dz, halfA, halfB, soft, angle, mix, shade, r, g, b, a});
            sprites.add(sprite);
        }

        @Override
        public void veil(SmokePuffs.Veil veil, int light) {
            this.veil = new double[19 * 19 + 1];
            for (int i = 0; i <= 18; i++) for (int j = 0; j <= 18; j++) this.veil[i * 19 + j] = veil.through(i / 9.0 - 1, j / 9.0 - 1);
            this.veil[19 * 19] = light;
        }

        @Override
        public boolean ribbon(double ax, double ay, double az, double halfA, float alphaA, int lightA, double bx, double by, double bz, double halfB,
                              float alphaB, int lightB, float r, float g, float b, float[] joint) {
            calls.add(new double[]{2, ax, ay, az, halfA, alphaA, lightA, bx, by, bz, halfB, alphaB, lightB, r, g, b, joint[0], joint[1], joint[2]});
            sprites.add(null);
            return true;
        }
    }

    @AfterEach
    void inline() {
        SmokePuffs.planner(null);
        SmokePuffs.reset();
        FarFlightView.reset();
    }

    private static Calls frame(List<SmokeGrid.Source> raid, double now, FarView view) {
        Calls c = new Calls();
        FarFlightView.budget();
        for (SmokeGrid.Source b : raid) b.emit(now, SmokePuffs.sink(view, c, b, now));
        SmokePuffs.flush();
        return c;
    }

    private static List<Calls> run(List<SmokeGrid.Source> raid, double now, FarView view, SmokePuffs.Planner planner, int frames) {
        SmokePuffs.planner(planner);
        SmokePuffs.reset();
        FarFlightView.reset();
        List<Calls> out = new ArrayList<>();
        for (int k = 0; k < frames; k++) {
            out.add(frame(raid, now, view));
            if (planner instanceof SmokePuffs.Worker w) assertTrue(w.await(10_000_000_000L), "the plan did not finish");
        }
        return out;
    }

    /** Same calls in the same order; positions may differ by the eye's step (ox, oy, oz). */
    private static String compare(Calls expected, Calls actual, double ox, double oy, double oz, double tolerance) {
        if (expected.calls.size() != actual.calls.size()) return "calls " + expected.calls.size() + " vs " + actual.calls.size();
        for (int k = 0; k < expected.calls.size(); k++) {
            double[] e = expected.calls.get(k), a = actual.calls.get(k);
            if (e.length != a.length || expected.sprites.get(k) != actual.sprites.get(k)) return "call " + k + " differs in kind or sprite";
            for (int i = 0; i < e.length; i++) {
                int axis = e[0] == 2 && (i == 1 || i == 7) ? 0 : e[0] == 2 && (i == 2 || i == 8) ? 1 : e[0] == 2 && (i == 3 || i == 9) ? 2
                        : e[0] < 2 && i >= 1 && i <= 3 ? i - 1 : -1;
                double shift = axis == 0 ? ox : axis == 1 ? oy : axis == 2 ? oz : 0;
                if (!(Math.abs(e[i] + shift - a[i]) <= tolerance)) return "call " + k + " field " + i + ": " + (e[i] + shift) + " vs " + a[i];
            }
        }
        if ((expected.veil == null) != (actual.veil == null)) return "veil present " + (expected.veil != null) + " vs " + (actual.veil != null);
        if (expected.veil != null) for (int i = 0; i < expected.veil.length; i++)
            if (!(Math.abs(expected.veil[i] - actual.veil[i]) <= 1e-9)) return "veil ray " + i + ": " + expected.veil[i] + " vs " + actual.veil[i];
        return null;
    }

    @Test
    void plannedFrameDrawsTheInlineFrameOneLater() {
        double now = 1000;
        List<SmokeGrid.Source> raid = SmokeRaidTest.raid(now);
        for (int camera = 1; camera < 3; camera++) {
            FarView view = SmokeRaidTest.view(camera);
            List<Calls> inline = run(raid, now, view, null, 6), planned = run(raid, now, view, DIRECT, 7);
            assertTrue(planned.getFirst().calls.isEmpty(), "the first frame has no plan yet");
            for (int k = 0; k < inline.size(); k++) {
                String diff = compare(inline.get(k), planned.get(k + 1), 0, 0, 0, 0);
                assertEquals(null, diff, SmokeRaidTest.NAMES[camera] + ", frame " + k);
            }
            System.out.printf(Locale.ROOT, "PIPELINE %s: %d calls, veil %b, identical one frame later%n", SmokeRaidTest.NAMES[camera],
                    inline.getLast().calls.size(), inline.getLast().veil != null);
        }
    }

    /**
     * The plan's quads move with the eye: drawn at the eye of the frame that draws them, they stay where they were planned. A step
     * beyond {@code Recording.STEP} plans the frame at once instead (the old plan's fill would not hold that close).
     */
    @Test
    void replayIsAnchoredAtTheCurrentEye() {
        double now = 1000;
        List<SmokeGrid.Source> raid = SmokeRaidTest.raid(now);
        FarView first = SmokeRaidTest.view(1);
        Vector3f f = first.forward();
        Vec3 step = new Vec3(0.5, 0.2, -0.4);
        FarView second = SmokeFillTest.view(first.camera().add(step), new Vector3f(f));
        Calls planned = run(raid, now, first, null, 1).getFirst();
        SmokePuffs.planner(DIRECT);
        SmokePuffs.reset();
        FarFlightView.reset();
        frame(raid, now, first);
        Calls drawn = frame(raid, now, second);
        assertFalse(drawn.calls.isEmpty());
        assertTrue(planned.veil != null && drawn.veil != null, "the veil is laid at the current eye, not dropped");
        // the veil is a grid in front of the current eye: it is rebuilt there, not moved
        planned.veil = drawn.veil = null;
        assertEquals(null, compare(planned, drawn, -step.x, -step.y, -step.z, 1e-9), "replayed at the moved eye");
        // a long step: this frame is planned at the new eye
        FarView far = SmokeFillTest.view(first.camera().add(3, 1, -2), new Vector3f(f));
        List<Calls> inline = run(raid, now, List.of(first, far), null), jumped = run(raid, now, List.of(first, far), DIRECT);
        assertEquals(null, compare(inline.get(1), jumped.get(1), 0, 0, 0, 0), "a long step plans at once");
    }

    /** A real worker thread gives the same frames; leaving the world while it plans waits for it and stops the thread. */
    @Test
    void workerThreadMatchesAndDrainsOnWorldExit() {
        double now = 1000;
        List<SmokeGrid.Source> raid = SmokeRaidTest.raid(now);
        FarView view = SmokeRaidTest.view(2);
        List<Calls> inline = run(raid, now, view, null, 4);
        SmokePuffs.Worker worker = (SmokePuffs.Worker) SmokePuffs.worker();
        List<Calls> planned = run(raid, now, view, worker, 5);
        for (int k = 0; k < inline.size(); k++) assertEquals(null, compare(inline.get(k), planned.get(k + 1), 0, 0, 0, 0), "frame " + k);
        // a plan is running: leave the world
        frame(raid, now, view);
        SmokePuffs.reset();
        assertFalse(alive(), "the smoke thread outlived the world");
        // the next world plans again, from a fresh start
        Calls empty = frame(raid, now, view);
        assertTrue(empty.calls.isEmpty(), "a plan from the previous world was drawn");
        assertTrue(worker.await(10_000_000_000L));
        Calls next = frame(raid, now, view);
        assertFalse(next.calls.isEmpty());
        assertTrue(alive());
    }

    /** A view of the edge camera with the frame half-diagonal edge (radians), pixel angle matching it. */
    private static FarView view(double edge) {
        FarView v = SmokeRaidTest.view(1);
        double pixel = 2 * Math.tan(edge) / Math.hypot(1920, 1080);
        return new FarView(v.camera(), new Vector3f(v.forward()), new Vector3f(v.left()), new Vector3f(v.up()), 0, 1000, pixel, edge, 1, 1,
                new Vector3f(1, 1, 1), 32000, 192, 192, 0, Sight.Air.at(0, 20000));
    }

    private static List<Calls> run(List<SmokeGrid.Source> raid, double now, List<FarView> views, SmokePuffs.Planner planner) {
        SmokePuffs.planner(planner);
        SmokePuffs.reset();
        FarFlightView.reset();
        List<Calls> out = new ArrayList<>();
        for (FarView v : views) out.add(frame(raid, now, v));
        return out;
    }

    /**
     * Review of #511: the two alternating plans continue one occlusion-grid history. A first frame needing 49 cells takes the
     * 96-cell tier; a following 45 keeps it (hysteresis) — inline and planned alike, not 96 and 48 on alternate frames.
     */
    @Test
    void occlusionTierCarriesAcrossPlans() {
        double now = 1000;
        List<SmokeGrid.Source> raid = SmokeRaidTest.raid(now);
        List<FarView> views = new ArrayList<>();
        views.add(view(Math.atan(24.0 / 49)));
        for (int k = 0; k < 7; k++) views.add(view(Math.atan(24.0 / 45)));
        List<Calls> inline = run(raid, now, views, null), planned = run(raid, now, views, DIRECT);
        // the projection changes at frame 1: planned at once there, then one frame behind
        assertEquals(null, compare(inline.get(1), planned.get(1), 0, 0, 0, 0), "frame 1, planned at once");
        for (int k = 2; k < views.size(); k++) assertEquals(null, compare(inline.get(k - 1), planned.get(k), 0, 0, 0, 0), "frame " + k);
    }

    /**
     * Review of #511: a plan made before a 4× zoom covered 121 screens drawn after it (budget 16). A projection change plans
     * the frame at once: the zoomed frame is the inline one, within the budget, and the following ones lag one frame again.
     */
    @Test
    void zoomPlansTheFrameAtOnceWithinTheBudget() {
        double now = 1000;
        List<SmokeGrid.Source> raid = SmokeRaidTest.raid(now);
        for (int camera = 0; camera < 3; camera++) {
            FarView wide = SmokeRaidTest.view(camera);
            double pixel = wide.pixel() / 4;
            FarView zoom = new FarView(wide.camera(), new Vector3f(wide.forward()), new Vector3f(wide.left()), new Vector3f(wide.up()), 0, 1000,
                    pixel, Math.atan(0.5 * pixel * Math.hypot(1920, 1080)), 1, 1, new Vector3f(1, 1, 1), 32000, 192, 192, 0, Sight.Air.at(0, 20000));
            List<FarView> views = new ArrayList<>();
            for (int k = 0; k < 8; k++) views.add(wide);
            for (int k = 0; k < 4; k++) views.add(zoom);
            List<Calls> inline = run(raid, now, views, null), planned = run(raid, now, views, DIRECT);
            for (int k = 1; k < 8; k++) assertEquals(null, compare(inline.get(k - 1), planned.get(k), 0, 0, 0, 0), "wide frame " + k);
            assertEquals(null, compare(inline.get(8), planned.get(8), 0, 0, 0, 0), SmokeRaidTest.NAMES[camera] + ": the zoomed frame is planned at once");
            for (int k = 9; k < views.size(); k++) assertEquals(null, compare(inline.get(k - 1), planned.get(k), 0, 0, 0, 0), "zoomed frame " + k);
            // the zoomed frame's drawn fill, measured by the screen oracle, keeps the budget
            SmokePuffs.planner(DIRECT);
            SmokePuffs.reset();
            FarFlightView.reset();
            for (int k = 0; k < 8; k++) frame(raid, now, wide);
            SmokeFillTest.Screen screen = new SmokeFillTest.Screen(zoom);
            FarFlightView.budget();
            for (SmokeGrid.Source b : raid) b.emit(now, SmokePuffs.sink(zoom, screen, b, now));
            SmokePuffs.flush();
            assertTrue(screen.fill() <= SmokePuffs.FILL_BUDGET + 1e-9, SmokeRaidTest.NAMES[camera] + ": zoomed fill " + screen.fill());
        }
    }

    /** Review of #511: the camera's vectors are copied into the filling Draw's storage, never into a Draw being planned. */
    @Test
    void cameraBasisIsNeverAPlanningDraw() {
        double now = 1000;
        List<SmokeGrid.Source> raid = SmokeRaidTest.raid(now);
        FarView v = SmokeRaidTest.view(1);
        Runnable[] held = new Runnable[1];
        SmokePuffs.Planner blocked = new SmokePuffs.Planner() {
            @Override public void start(Runnable plan) { held[0] = plan; }
            @Override public boolean await(long nanos) { return held[0] == null; }
            @Override public void stop() { if (held[0] != null) held[0].run(); held[0] = null; }
        };
        SmokePuffs.planner(blocked);
        SmokePuffs.reset();
        Vector3f[] first = SmokePuffs.cameraBasis();
        frame(raid, now, v);
        Vector3f[] planning = first, filling = SmokePuffs.cameraBasis();
        assertTrue(filling != planning, "the sealed Draw still lends its storage");
        // the plan is still running: the next frame keeps filling the same Draw and leaves the planned one alone
        frame(raid, now, v);
        assertTrue(SmokePuffs.cameraBasis() == filling);
        blocked.stop();
    }

    private static boolean alive() {
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.getName().equals("Incoming Smoke") && t.isAlive()) return true;
        return false;
    }
}
