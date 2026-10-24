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
    public static String target() {
        try {
            Class<?> type=ua.zentix.incoming.client.fx.layer.PuffTarget.class;
            int[] values=new int[4]; String[] names={"fbo","activeW","activeH","step"};
            for(int i=0;i<4;i++) {var f=type.getDeclaredField(names[i]);f.setAccessible(true);values[i]=f.getInt(null);}
            if(values[0]<0 || values[3]<2) return "target not reduced";
            int previous=org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING);
            double[][] points={{0,0},{0,0.5},{0,-0.5},{-0.5,0.75},{0.5,0.75}};
            double[] trans=new double[5];
            try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
                var rgba=stack.mallocFloat(4);
                org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER,values[0]);
                for(int i=0;i<5;i++) {
                    int x=Math.clamp((int)((points[i][0]+1)*0.5*values[1]),0,values[1]-1);
                    int y=Math.clamp((int)((points[i][1]+1)*0.5*values[2]),0,values[2]-1);
                    org.lwjgl.opengl.GL11.glReadPixels(x,y,1,1,org.lwjgl.opengl.GL11.GL_RGBA,org.lwjgl.opengl.GL11.GL_FLOAT,rgba);
                    trans[i]=1-rgba.get(3);
                }
            } finally {org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER,previous);}
            var qf=ua.zentix.incoming.client.fx.layer.FxLayer.class.getDeclaredField("QUADS");qf.setAccessible(true);
            var quads=qf.get(null);var qt=quads.getClass();
            var sf=qt.getDeclaredField("stage");sf.setAccessible(true);long stage=sf.getLong(quads);
            var nf=qt.getDeclaredField("n");nf.setAccessible(true);int count=nf.getInt(quads);
            var bf=qt.getDeclaredField("bounds");bf.setAccessible(true);float[] rects=(float[])bf.get(quads);
            double[] fill=new double[3];int[] totals=new int[3];
            var df=ua.zentix.incoming.client.fx.layer.GaussianData.class.getDeclaredField("data");df.setAccessible(true);
            float[] data=(float[])df.get(null); double[] tiers=new double[4];int[] numbers=new int[4];
            for(int i=0;i<count;i++) {
                long at=stage+i*4L*36;
                int marker=org.lwjgl.system.MemoryUtil.memGetByte(at+33);
                if(marker> -13 || marker< -50 || org.lwjgl.system.MemoryUtil.memGetShort(at+30)==0)continue;
                int mix=(org.lwjgl.system.MemoryUtil.memGetShort(at+26)&0xffff)-1, bucket=mix==0?0:mix==32766?2:1;
                double area=(rects[4*i+2]-rects[4*i])*(rects[4*i+3]-rects[4*i+1]);
                totals[bucket]++;fill[bucket]+=area;
                int g=8*((org.lwjgl.system.MemoryUtil.memGetShort(at+30)&65535)-1);
                double peak=data[g+5]/(2*Math.PI*data[g+3]*Math.min(data[g+3],data[g+4]));
                int t=peak<0.02?0:peak<0.1?1:peak<1?2:3;tiers[t]+=area;numbers[t]++;
            }
            String groups=String.format(Locale.ROOT,"; Gaussian native bounds core/mixed/bake %.2f/%.2f/%.2f counts %d/%d/%d",
                    fill[0],fill[1],fill[2],totals[0],totals[1],totals[2]);
            return String.format(Locale.ROOT,"puff T @same NDC: %.4f/%.4f/%.4f/%.4f/%.4f",trans[0],trans[1],trans[2],trans[3],trans[4])+groups+String.format(Locale.ROOT,
                    "; peak <.02/.1/1/>1 fill %.2f/%.2f/%.2f/%.2f count %d/%d/%d/%d",tiers[0],tiers[1],tiers[2],tiers[3],numbers[0],numbers[1],numbers[2],numbers[3]);
        } catch(ReflectiveOperationException e) {throw new IllegalStateException(e);}
    }

    public static String sample(Vec3 eye, Matrix4fc inverseProjectionView, float partial) {
        double[] clear = {1,1,1,1,1};
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
                if (!(sh>0) || !(sv>0) || !(ext>0)) return;
                double coverage=source.drawsGlow()?1-CloudModel.glowShare(kelvin,Math.pow(FarView.ambient(net.minecraft.client.Minecraft.getInstance().level,partial),Sight.ADAPT)):1;
                double[] tau=new double[rays.length];
                double len = 2*Math.sqrt(ax*ax+ay*ay+az*az);
                int n = Math.max(1, Math.min(64, (int)Math.ceil(len/(0.25*Math.min(sh,sv)))));
                for (int j=0; j<n; j++) {
                    double f=2*(j+0.5)/n-1;
                    for (int k=0; k<rays.length; k++) {
                        Vector3f r=rays[k];
                        tau[k] += SmokePuffs.sightDepth(x+f*ax-eye.x,y+f*ay-eye.y,z+f*az-eye.z,sh,sv,ext/n,r.x,r.y,r.z);
                    }
                }
                for(int k=0;k<rays.length;k++) clear[k]*=1-coverage*(-Math.expm1(-tau[k]));
            });
            return true;
        });
        return String.format(Locale.ROOT, "model T @NDC (0,0)/(0,.5)/(0,-.5)/(-.5,.75)/(.5,.75): %.4f/%.4f/%.4f/%.4f/%.4f", clear[0],clear[1],clear[2],clear[3],clear[4]);
    }
}
