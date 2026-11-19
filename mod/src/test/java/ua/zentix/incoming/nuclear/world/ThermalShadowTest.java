package ua.zentix.incoming.nuclear.world;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.flash.FlashChar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Light source is the fireball, not the burst point. 1 Mt surface burst over the Silo map (16.11.2026): with every
 * nearer chunk known, a ray to the burst point on the ground was hidden for 86–99.8 % of ground columns at 0.5–6 km, so
 * settled chunks kept their turf while chunks ruined beside a player were scorched.
 */
class ThermalShadowTest {
    private static final double GROUND = 76;
    /** 1 Mt surface burst at map scale: ball 0.088·1000^0.4 km = 1 395 m high. */
    private static final Detonation MT = new Detonation(1, new Vec3(0, GROUND, 0), GROUND, 1000, true, 0, 0, 0, 20_000, 7, 1, false,
            Detonation.OLD_WAVE);
    /** Flat ground: first air one above the ground block everywhere. */
    private static final java.util.function.IntBinaryOperator FLAT = (x, z) -> (int) GROUND + 1;

    @BeforeAll
    static void boot() {
        // RuinPlanner and FlashChar hold block states
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.Map.of());
        Bootstrap.bootStrap();
    }

    private static Vec3 ground(double x) {
        return new Vec3(x + 0.5, GROUND + 1, 0.5);
    }

    @Test
    void surfaceBurstLightsFlatGroundKilometresAway() {
        assertEquals(1395, MT.fireballRadius(), 1);
        // the ground under the ball hides only its lowest half block
        for (double x : new double[]{500, 2000, 4800, 7000}) {
            assertEquals(1, ThermalShadow.share(MT, ThermalShadow.sparseLine(MT, ground(x), FLAT)), 1e-4, "sparse, " + x + " blocks");
            assertEquals(1, ThermalShadow.share(MT, ThermalShadow.windowLine(MT, ground(x), FLAT)), 1e-4, "window, " + x + " blocks");
        }
    }

    @Test
    void hillHidesPartOrAllOfTheBall() {
        // 20 blocks high 100 blocks toward the burst from a point 4.8 km out: the line on the axis is 937 m above the
        // ground, the lower two thirds of the 1 395 m ball are hidden
        java.util.function.IntBinaryOperator hill = (x, z) -> x == 4700 ? (int) GROUND + 21 : (int) GROUND + 1;
        double part = ThermalShadow.share(MT, ThermalShadow.windowLine(MT, ground(4800), hill));
        assertTrue(part > 0.1 && part < 0.5, "part of the ball: " + part);
        java.util.function.IntBinaryOperator ridge = (x, z) -> x == 4700 ? (int) GROUND + 40 : (int) GROUND + 1;
        assertEquals(0, ThermalShadow.share(MT, ThermalShadow.windowLine(MT, ground(4800), ridge)), 1e-9);
    }

    @Test
    void windowLineStopsAtTheWindowEdge() {
        // the ridge lies beyond the known heights: not an occluder (DESIGN-nuke §3.4 approximation)
        java.util.function.IntBinaryOperator window = (x, z) -> x < 4760 ? Integer.MIN_VALUE : x == 4700 ? (int) GROUND + 40 : (int) GROUND + 1;
        assertEquals(1, ThermalShadow.share(MT, ThermalShadow.windowLine(MT, ground(4800), window)), 1e-9);
    }

    @Test
    void ownColumnShadesAPointUnderIt() {
        // ground under a three-block glass column right under an airburst (a DH GameTest: with the column open its
        // ground lit, the ruins put fire where the glass stood) and 0.7 blocks off the axis
        Detonation air = new Detonation(3, new Vec3(0.5, GROUND + 40, 0.5), GROUND, 15, false, 0, 0, 0, 20_000, 7, 0.05f, false,
                Detonation.OLD_WAVE);
        java.util.function.IntBinaryOperator glass = (x, z) -> (x == 0 || x == -1) && z == 0 ? (int) GROUND + 4 : (int) GROUND + 1;
        for (Vec3 under : new Vec3[]{new Vec3(0.5, GROUND + 1, 0.5), new Vec3(-0.2, GROUND + 1, 0.5)}) {
            assertEquals(0, ThermalShadow.share(air, ThermalShadow.windowLine(air, under, glass)), 1e-9, "window, " + under);
            assertEquals(0, ThermalShadow.share(air, ThermalShadow.sparseLine(air, under, glass)), 1e-9, "sparse, " + under);
            assertEquals(1, ThermalShadow.share(air, ThermalShadow.windowLine(air, under, FLAT)), 1e-4, "open, " + under);
        }
    }

    @Test
    void highAirburstUnchangedOverFlatGround() {
        Detonation air = new Detonation(2, new Vec3(0, GROUND + 580, 0), GROUND, 15, false, 0, 0, 0, 20_000, 7, 1, false, Detonation.OLD_WAVE);
        assertEquals(1, ThermalShadow.share(air, ThermalShadow.sparseLine(air, ground(2000), FLAT)), 1e-9);
    }

    @Test
    void eyeSeesPartOfTheBallOverANearbyRidge() {
        // review of #664: 15 kt surface burst at (0.5, 0, 0.5), eye at (500.5, 1.62, 0.5), flat first air 1, a ridge
        // at x 420–430 with first air 29, first 48 blocks clear. Projected from a point 48 blocks ahead the ridge hid the
        // whole 260 m ball (horizon 267.6); from the eye about 16 % stays visible (≈12 cal/cm²)
        Detonation kt = new Detonation(4, new Vec3(0.5, 0, 0.5), 0, 15, true, 0, 0, 0, 20_000, 7, 1, false, Detonation.OLD_WAVE);
        java.util.function.IntBinaryOperator ridge = (x, z) -> x >= 420 && x <= 430 ? 29 : 1;
        Vec3 eye = new Vec3(500.5, 1.62, 0.5), dir = ThermalShadow.aim(kt).subtract(eye);
        Vec3 near = eye.add(dir.scale(48 / dir.length())), until = eye.add(dir.scale(Math.min(1, 512 / dir.length())));
        double share = ThermalShadow.share(kt, ThermalShadow.eyeLine(kt, eye, near, until, 8, ridge));
        assertTrue(share > 0.12 && share < 0.22, "share from the eye: " + share);
        assertEquals(0, ThermalShadow.share(kt, ThermalShadow.sparseFrom(kt, near, near, ridge)), 1e-9, "the displaced viewpoint sees nothing");
        // without the ridge the eye sees it all
        assertEquals(1, ThermalShadow.share(kt, ThermalShadow.eyeLine(kt, eye, near, until, 8, (x, z) -> 1)), 1e-3);
    }

    @Test
    void facesAndSlopesTakeLightFromTheBall() {
        // a top face and ground 4.8 km from 1 Mt surface burst: the burst point lies below them, the ball's middle 697 m up
        Vec3 ball = ThermalShadow.aim(MT);
        assertEquals(GROUND + 1395 / 2.0, ball.y, 1);
        assertEquals(0, FlashChar.incidence(MT.burst(), 4800, (int) GROUND, 0, Direction.UP), 1e-9, "top face from the burst point");
        assertEquals(696 / Math.hypot(4800, 696), FlashChar.incidence(ball, 4800, (int) GROUND, 0, Direction.UP), 0.01, "top face from the ball");
        // ground rising away from the burst, so facing it (a one-block step in the slope stencil): from the grazing burst
        // point any such slope took the ×2.5 cap; from the ball it gets the cosine ratio, still above flat ground
        int[] rising = new int[256], flat = new int[256];
        for (int c = 0; c < 256; c++) {
            rising[c] = (int) GROUND + ((c & 15) >= 8 ? 1 : 0);
            flat[c] = (int) GROUND;
        }
        int x = 4800 + 8, h = (int) GROUND + 1;
        double fromPoint = RuinPlanner.incidence(rising, 8, 8, h, x, 0, MT.burst());
        double fromBall = RuinPlanner.incidence(rising, 8, 8, h, x, 0, ball);
        assertEquals(2.5, fromPoint, 1e-9, "grazing: capped");
        assertTrue(fromBall > 1.3 && fromBall < 2.5, "from the ball: " + fromBall);
        assertEquals(1, RuinPlanner.incidence(flat, 8, 8, (int) GROUND, x, 0, ball), 1e-9, "flat ground");
    }

    @Test
    void discShareCountsOnlyTheBallAboveGround() {
        assertEquals(1, ThermalShadow.discShare(0, 100, 0), 1e-9);
        assertEquals(0, ThermalShadow.discShare(0, 100, 100), 1e-9);
        assertEquals(0.5, ThermalShadow.discShare(0, 100, 50), 1e-9);
        assertEquals(0.5, ThermalShadow.discShare(500, 100, 500), 1e-9);
    }
}
