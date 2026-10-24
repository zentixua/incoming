package ua.zentix.incoming.client.fx.layer;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class GaussianBoundsTest {
    private static final Vector3f F = new Vector3f(0,0,-1), L = new Vector3f(-1,0,0), U = new Vector3f(0,1,0);
    private static final double W = 1.25, H = 0.7;

    @Test
    void aFlatLayerCrossingTheEyeDoesNotReserveTheSky() {
        double[] r = new double[4];
        assertTrue(new GaussianBounds().project(0,-25,-30,100,1,2*Math.PI*100,F,L,U,W,H,r));
        assertTrue(r[3] < 0, "a box entirely below the eye has no sky projection");
        assertTrue((r[2]-r[0])*(r[3]-r[1])/(4*W*H) < 0.5, "sphere bounds wasted the whole screen");
    }

    @Test
    void everyForwardRayThroughTheSupportEllipsoidRemainsInsideTheRectangle() {
        Random random = new Random(7);
        GaussianBounds bounds = new GaussianBounds();
        double[] rect = new double[4];
        for(int sample=0;sample<200;sample++) {
            double x=160*random.nextDouble()-80, y=80*random.nextDouble()-40, z=160*random.nextDouble()-80;
            double sh=1+40*random.nextDouble(), sv=0.5+30*random.nextDouble(), ext=2*Math.PI*sh*Math.min(sh,sv);
            boolean visible=bounds.project(x,y,z,sh,sv,ext,F,L,U,W,H,rect);
            double cut2=2*Math.log(1e7);
            for(int j=0;j<21;j++) for(int i=0;i<35;i++) {
                double u=-W+(i+0.5)*2*W/35,v=-H+(j+0.5)*2*H/21;
                double rx=-u/sh,ry=v/sv,rz=-1/sh,px=x/sh,py=y/sv,pz=z/sh;
                double a=rx*rx+ry*ry+rz*rz,b=px*rx+py*ry+pz*rz,c=px*px+py*py+pz*pz-cut2;
                double disc=b*b-a*c;
                if(disc<0 || b+Math.sqrt(disc)<0) continue;
                assertTrue(visible && u>=rect[0]-1e-5 && u<=rect[2]+1e-5 && v>=rect[1]-1e-5 && v<=rect[3]+1e-5,
                        "homogeneous box clipping must not remove a forward Gaussian ray");
            }
        }
    }
}
