package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class ProjectedBoundsTest {
    @Test
    void groundCrossingTheEyeDoesNotSpendTheSkyBudget() {
        float[] quad={-1,-0.5f,0,1, 1,-0.5f,0,1, 1,-0.5f,0,-1, -1,-0.5f,0,-1};
        float[] rect=new float[4];
        new ProjectedBounds().quad(quad,rect,0);
        assertArrayEquals(new float[]{0,0,1,0.25f},rect,1e-6f);
        assertEquals(0.25,PuffTarget.pixels(rect,1,1280,720)/(1280.0*720),1e-12);
    }

    @Test
    void fullyBehindOrOutsideQuadsDoNotDrawOrSplitRuns() {
        float[] rect=new float[4]; ProjectedBounds clip=new ProjectedBounds();
        clip.quad(new float[]{-1,-1,0,-1, 1,-1,0,-1, 1,1,0,-1, -1,1,0,-1},rect,0);
        assertArrayEquals(new float[4],rect);
        clip.quad(new float[]{3,-1,0,1, 4,-1,0,1, 4,1,0,1, 3,1,0,1},rect,0);
        assertArrayEquals(new float[4],rect);
        clip.quad(new float[]{-.5f,-.5f,0,1, .5f,-.5f,0,1, .5f,.5f,0,1, -.5f,.5f,0,1},rect,0);
        assertArrayEquals(new float[]{.25f,.25f,.75f,.75f},rect,1e-6f);
    }

    @Test
    void everyVisibleTriangleSampleLiesInsideItsChargedRectangle() {
        Random random=new Random(428); ProjectedBounds clip=new ProjectedBounds();
        float[] q=new float[16],rect=new float[4];
        for(int trial=0;trial<300;trial++) {
            for(int j=0;j<4;j++) {
                q[4*j]=(random.nextFloat()-.5f)*8;
                q[4*j+1]=(random.nextFloat()-.5f)*8;
                q[4*j+2]=(random.nextFloat()-.5f)*8;
                q[4*j+3]=(random.nextFloat()-.3f)*8;
            }
            clip.quad(q,rect,0);
            for(int[] tri:new int[][]{{0,1,2},{2,3,0}}) for(int sample=0;sample<400;sample++) {
                float u=random.nextFloat(),v=random.nextFloat(); if(u+v>1) {u=1-u;v=1-v;}
                float x=q[4*tri[0]]*(1-u-v)+q[4*tri[1]]*u+q[4*tri[2]]*v;
                float y=q[4*tri[0]+1]*(1-u-v)+q[4*tri[1]+1]*u+q[4*tri[2]+1]*v;
                float w=q[4*tri[0]+3]*(1-u-v)+q[4*tri[1]+3]*u+q[4*tri[2]+3]*v;
                if(w>1e-5f && Math.abs(x)<=w && Math.abs(y)<=w) {
                    x=x/w*.5f+.5f; y=y/w*.5f+.5f;
                    assertTrue(x>=rect[0]-1e-5 && x<=rect[2]+1e-5 && y>=rect[1]-1e-5 && y<=rect[3]+1e-5);
                }
            }
        }
    }
}
