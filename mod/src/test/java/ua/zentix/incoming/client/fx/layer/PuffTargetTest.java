package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

/** The native pixel budget is independent of the one-quad floor, screen size, clipping and integer raster rounding. */
class PuffTargetTest {
    @Test
    void denseOverlappingCloudsStayWithinFourNativeScreens() {
        for (int[] frame : new int[][]{{320, 180}, {1280, 720}, {2560, 1600}, {1279, 719}}) {
            for (int count : new int[]{1, 4, 5, 19, 33, 72, 200, 800}) {
                float[] rects = new float[count * 4];
                for (int i = 0; i < count; i++) { rects[4*i+2] = 1; rects[4*i+3] = 1; }
                assertBudget(rects, count, frame[0], frame[1]);
            }
        }
    }

    @Test
    void clippedPartialCloudsIncludeEveryEdgePixel() {
        Random rnd = new Random(428);
        int count = 1500;
        float[] rects = new float[count * 4];
        for (int i = 0; i < count; i++) {
            float x = rnd.nextFloat(), y = rnd.nextFloat();
            rects[4*i] = Math.max(0, x - rnd.nextFloat() * 0.3f);
            rects[4*i+1] = Math.max(0, y - rnd.nextFloat() * 0.3f);
            rects[4*i+2] = Math.min(1, x + rnd.nextFloat() * 0.3f);
            rects[4*i+3] = Math.min(1, y + rnd.nextFloat() * 0.3f);
        }
        assertBudget(rects, count, 1280, 720);
        assertBudget(rects, count, 2560, 1600);
        assertEquals(1, PuffTarget.plan(new float[4], 1, 1280, 720, 4).step());
    }

    private static void assertBudget(float[] rects, int n, int w, int h) {
        PuffTarget.Resolution plan = PuffTarget.plan(rects, n, w, h, 4);
        int step = plan.step();
        assertTrue(PuffTarget.workPixels(rects, n, w, h, step, plan.keepSmall()) <= 4.0 * w * h,
                "count=" + n + " frame=" + w + "x" + h + " step=" + step);
        if (step > 1) assertTrue(PuffTarget.workPixels(rects, n, w, h, step-1, plan.keepSmall()) > 4.0 * w * h,
                "uses the finest target that fits");
    }

    @Test
    void tinySmokeStaysNativeAlongsideLargeClouds() {
        float[] b = new float[21 * 4];
        for (int i = 0; i < 20; i++) { b[4*i+2] = 1; b[4*i+3] = 1; }
        b[82] = 2f / 1280;
        b[83] = 2f / 720;
        PuffTarget.Resolution plan = PuffTarget.plan(b, 21, 1280, 720, 4);
        assertTrue(plan.keepSmall());
        assertFalse(PuffTarget.reduced(b, 20, 1280, 720, plan.step()), "a far 2px club retains its native footprint");
        assertBudget(b, 21, 1280, 720);
    }

    @Test
    void denseClubsRetainDetailBesideLargeThinFieldsWithinTheSameFourScreens() {
        for (int[] size : new int[][]{{1280,720},{2560,1600},{1279,719}}) {
            float[] dense=new float[76*4], broad=new float[76*4];
            for(int i=0;i<4;i++) { dense[4*i+2]=1; dense[4*i+3]=1; }
            for(int i=4;i<76;i++) { broad[4*i+2]=1; broad[4*i+3]=1; }
            PuffTarget.FrameResolution plan=PuffTarget.planFrame(dense,broad,76,size[0],size[1],4);
            assertTrue(plan.fine().step()<plan.thin().step(),"a low-contrast field cannot blur the detailed club");
            double work=PuffTarget.workPixels(dense,76,size[0],size[1],plan.fine().step(),plan.fine().keepSmall())
                    +PuffTarget.workPixels(broad,76,size[0],size[1],plan.thin().step(),plan.thin().keepSmall());
            assertTrue(work<=4.0*size[0]*size[1],"both resolutions and native small fragments count in one budget");
        }
        int[] order={0,1,2,3,4}, steps={2,2,7,1,7};
        assertEquals(2,FxQuads.groupEnd(order,steps,0,5));
        assertEquals(3,FxQuads.groupEnd(order,steps,2,5));
        assertEquals(4,FxQuads.groupEnd(order,steps,3,5));
    }

    @Test
    void offscreenObjectsDoNotSplitVisibleRuns() {
        int[] order = {4, 3, 2, 1, 0};
        float[] rects = new float[20];
        for (int q : new int[]{4, 2, 0}) { rects[4*q+2] = 1; rects[4*q+3] = 1; }
        assertEquals(3, FxQuads.visibleOrder(order, rects, 5));
        assertArrayEquals(new int[]{4, 2, 0}, Arrays.copyOf(order, 3));
        assertEquals(3, FxQuads.groupEnd(order, new int[]{2, 1, 2, 1, 2}, 0, 3));
    }

    @Test
    void nativeObjectsStayBetweenTheSameParticleRuns() {
        // Input: smoke, model, smoke, spark, smoke. Depths deliberately reorder them.
        float[] depths = {10, 40, 50, 20, 30};
        int[] smoke = {2, 1, 2, 1, 2};
        int[] order = new FxQuads.FarFirst().sort(depths, 5);
        assertArrayEquals(new int[]{2, 1, 4, 3, 0}, Arrays.copyOf(order, 5));
        int groups = 0;
        for (int i = 0; i < 5;) {
            int end = FxQuads.groupEnd(order, smoke, i, 5);
            assertEquals(i + 1, end, "never move smoke across a sharp object");
            i = end;
            groups++;
        }
        assertEquals(5, groups);
    }
}
