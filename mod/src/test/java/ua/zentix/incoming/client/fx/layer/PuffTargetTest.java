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
        assertEquals(1, PuffTarget.plan(new float[4], 1, 1280, 720, 4));
    }

    @Test
    void sharedMeshEdgesNeverChargeTheSameRasterPixelTwice() {
        int columns=16,rows=32,count=columns*rows+1;
        float[] bounds=new float[4*count];int[] group=new int[count];
        for(int y=0;y<rows;y++)for(int x=0;x<columns;x++) {
            int i=y*columns+x;group[i]=1;
            bounds[4*i]=(float)x/columns;bounds[4*i+1]=(float)y/rows;
            bounds[4*i+2]=(float)(x+1)/columns;bounds[4*i+3]=(float)(y+1)/rows;
        }
        // An independent overlaid card still costs its own pixels.
        bounds[4*(count-1)+2]=bounds[4*(count-1)+3]=1;
        PuffTarget.mergeMeshBounds(bounds,group,count,new int[2]);
        assertEquals(2*1279*719.0,PuffTarget.pixels(bounds,count,1279,719));
        assertBudget(bounds,count,1279,719);
    }

    @Test
    void nativeDistantDetailAndTheCloseMediumShareTheSameFourScreens() {
        for(int[] size:new int[][]{{1279,719},{2560,1600}}) {
            float[] bounds=new float[72*4];int[] order=new int[72],resolution=new int[72];
            for(int i=0;i<72;i++){order[i]=i;bounds[4*i+2]=bounds[4*i+3]=1;}
            double nativeWork=PuffTarget.nativePrefix(bounds,order,72,size[0],size[1],resolution);
            assertEquals(2*size[0]*(double)size[1],nativeWork);
            assertEquals(1,resolution[0]);assertEquals(1,resolution[1]);assertEquals(0,resolution[2]);
            int step=PuffTarget.plan(bounds,72,size[0],size[1],4-nativeWork/(size[0]*(double)size[1]));
            assertTrue(nativeWork+PuffTarget.workPixels(bounds,72,size[0],size[1],step)<=4*size[0]*(double)size[1]);
        }
    }

    private static void assertBudget(float[] rects, int n, int w, int h) {
        int step = PuffTarget.plan(rects, n, w, h, 4);
        assertTrue(PuffTarget.workPixels(rects, n, w, h, step) <= 4.0 * w * h,
                "count=" + n + " frame=" + w + "x" + h + " step=" + step);
        if (step > 1) assertTrue(PuffTarget.workPixels(rects, n, w, h, step-1) > 4.0 * w * h,
                "uses the finest target that fits");
    }

    @Test
    void interleavedSmokeUsesOneGridAndNeverSplitsForLightingOrSize() {
        for (int[] frame : new int[][]{{1280,720},{2560,1600},{1279,719}}) {
            float[] rects=new float[76*4];
            for(int i=0;i<76;i++) { rects[4*i+2]=1; rects[4*i+3]=1; }
            assertBudget(rects,76,frame[0],frame[1]);
        }
        int[] order={0,1,2,3,4}, steps={7,7,7,7,7};
        assertEquals(5,FxQuads.groupEnd(order,steps,0,5),"lighting and small-club LOD must not cause hundreds of full-screen resolves");
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
