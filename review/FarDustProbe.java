package ua.zentix.incoming.client.far;
import ua.zentix.incoming.client.fx.smoke.*;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import java.util.Locale;
public class FarDustProbe {
 public static void main(String[] args) {
  FarView v=new FarView(new Vec3(0,64,0),new Vector3f(1,0,0),new Vector3f(0,0,1),new Vector3f(0,1,0),0,1000,.001,1,1,1,new Vector3f(1),20000,128,Double.NaN,0,Sight.Air.at(64,20000));
  SmokeGrid.Source s=new SmokeGrid.Source(){
   public boolean emit(double t,SmokeGrid.Sink o){return true;}
   public double visible(double y,double r){return 0;}
   public double visible(double y,double r,double[] a,int i){return 0;}
  };
  DustGrid.begin(true,0,64,0);
  SmokePuffs.sink(v,null,s,0).ground(300,64,0,8,.1,12,.9f,.85f,.8f,288.15);
  System.out.printf("fully hidden source: dust grid count=%d%n",DustGrid.finish().count);
  double lo=798,hi=3000;
  for(int i=0;i<60;i++){double mid=.5*(lo+hi);if(CloudModel.glowShare(mid,1)<.9)lo=mid;else hi=mid;}
  double k=.5*(lo+hi),share=SmokePuffs.smokeShare(true,k,1),tau=200;
  System.out.printf(Locale.ROOT,"T=%.4f K, shown=%.6f; tau=200 -> intended opacity=%.9f, density-scaled opacity=%.9f%n",k,share,share*(1-Math.exp(-tau)),1-Math.exp(-tau*share));
 }
}
