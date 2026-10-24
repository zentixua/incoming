package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Matrix4fc;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import java.util.Locale;

/** Independent analytic rays through the live source model, including aftermath fires (not the billboard renderer). */
public final class SmokeRayProbe {
    private SmokeRayProbe() {}
    public static String sample(Vec3 eye, Matrix4fc inverseProjectionView, float partial) {
        double[] taus = new double[5];
        double[][] pixels={{0,0},{0,0.5},{0,-0.5},{-0.5,0.75},{0.5,0.75}};
        Vector3f[] rays=new Vector3f[pixels.length];
        for(int i=0;i<pixels.length;i++) {
            Vector4f p=new Vector4f((float)pixels[i][0],(float)pixels[i][1],-1,1);
            inverseProjectionView.transform(p);
            rays[i]=new Vector3f(p.x/p.w,p.y/p.w,p.z/p.w).normalize();
        }
        double seconds = SmokeGrid.seconds(partial);
        SmokeGrid.quads(source -> {
            source.emit(seconds, (x,y,z,ax,ay,az,sh,sv,soot,dust,water,dr,dg,db,kelvin,rise) -> {
                double ext = SmokeGrid.SOOT_K * soot + SmokeGrid.dustK(dr,dg,db) * dust + SmokeGrid.DROP_K * water;
                if (source.drawsGlow()) ext *= 1-CloudModel.glowShare(kelvin, 1);
                double len = 2*Math.sqrt(ax*ax+ay*ay+az*az);
                int n = Math.max(1, Math.min(64, (int)Math.ceil(len/(0.25*Math.min(sh,sv)))));
                for (int j=0; j<n; j++) {
                    double f=2*(j+0.5)/n-1;
                    for (int k=0; k<rays.length; k++) {
                        Vector3f r=rays[k];
                        taus[k] += SmokePuffs.sightDepth(x+f*ax-eye.x,y+f*ay-eye.y,z+f*az-eye.z,sh,sv,ext/n,r.x,r.y,r.z);
                    }
                }
            });
            return true;
        });
        return String.format(Locale.ROOT, "model T @NDC (0,0)/(0,.5)/(0,-.5)/(-.5,.75)/(.5,.75): %.4f/%.4f/%.4f/%.4f/%.4f", Math.exp(-taus[0]),Math.exp(-taus[1]),Math.exp(-taus[2]),Math.exp(-taus[3]),Math.exp(-taus[4]));
    }
}
