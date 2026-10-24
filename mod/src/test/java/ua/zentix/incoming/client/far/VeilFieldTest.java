package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VeilFieldTest {
    @Test
    void angularMeshMatchesTheModelAndRejectsACentralRayFogValue() {
        FarView view=SmokeFillTest.view(Vec3.ZERO,new Vector3f(1,0,0));
        double oldWorst=0;
        for(double vertical:new double[]{1,3,5,15,30}) for(double peak:new double[]{0.1,1,10}) {
            double sh=30, px=0.7*sh, py=0.35*vertical, ext=peak*2*Math.PI*sh*sh;
            VeilField field=new VeilField(); field.begin(view,16.0/9);
            field.add(px,py,0,sh,vertical,ext,1,0.4f,0.3f,0.2f);
            float[] rgba=field.finish();
            double central=Math.exp(-SmokePuffs.sightDepth(px,py,0,sh,vertical,ext,1,0,0));
            for(int x=0;x<47;x++) for(int y=0;y<29;y++) {
                double u=field.width*(2*(x+0.37)/47-1),v=field.height*(2*(y+0.61)/29-1);
                double len=Math.sqrt(1+u*u+v*v);
                Vector3f l=view.left(),up=view.up();
                double real=Math.exp(-SmokePuffs.sightDepth(px,py,0,sh,vertical,ext,
                        (1+u*l.x+v*up.x)/len,(u*l.y+v*up.y)/len,(u*l.z+v*up.z)/len));
                double got=1-VeilField.alpha(rgba,u,v,field.width,field.height);
                assertEquals(real,got,0.02,"flattening "+vertical+" peak "+peak+" ray "+u+","+v);
                oldWorst=Math.max(oldWorst,Math.abs(real-central));
            }
        }
        assertTrue(oldWorst>0.2,"the old screen-wide central ray must fail this class of scene");
    }

    @Test
    void overlappingFieldsMultiplyTransmittanceAndClearBetweenFrames() {
        FarView view=SmokeFillTest.view(Vec3.ZERO,new Vector3f(1,0,0));
        VeilField field=new VeilField(); field.begin(view,16.0/9);
        field.add(20,4,0,30,15,5000,1,0.4f,0.3f,0.2f);
        float[] first=field.finish().clone();
        field.add(20,4,0,30,15,5000,1,0.4f,0.3f,0.2f);
        float[] both=field.finish();
        for(int i=3;i<both.length;i+=4) assertEquals(Math.pow(1-first[i],2),1-both[i],1e-7);
        field.clear(); field.begin(view,16.0/9);
        for(float f:field.finish()) assertEquals(0,f);
    }
}
