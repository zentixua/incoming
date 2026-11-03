package ua.zentix.incoming.client.fx.smoke;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.util.LocalWind;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.strike.WeaponType;
import java.util.Arrays;
class ReviewSamplesTest {
 @Test void samples() {
  var air=new CloudModel.Air(.01,4,-3,.2,3,.5,new LocalWind(0,0,20,10,1,2,.1,80));
  var t=new ReferenceThermal(ReferenceThermal.buoyancy(1e9),2,1,air);
  double end=Math.ceil(Math.PI/.01/.05)*.05;
  System.out.println("APEX "+t.topSeconds+" END "+end);
  for(double s:new double[]{.025,.05,t.topSeconds-.025,t.topSeconds,t.topSeconds+.025,end-.025,end,end+.025})
   System.out.println("THERMAL "+Arrays.toString(new double[]{s,t.height(s),t.radius(s),t.driftX(s),t.driftZ(s)}));
  var m=new CloudModel(BlastSource.of(WeaponType.DRONE,false),air);
  double[] shear=new double[(int)Math.ceil(Math.min(m.life,CloudModel.LIFE_MAX)/.5)+2];
  for(int i=1;i<shear.length;i++) {
   double tm=(i-.5)*.5,z=m.height(tm)+m.hob,v=m.sigmaV(tm);
   shear[i]=shear[i-1]+.5*Math.hypot(air.windX(),air.windZ())*(air.wind(z+v)-air.wind(Math.max(z-v,1)))*.5;
  }
  double last=(shear.length-1)*.5;
  System.out.println("SHEAR END "+last);
  for(double s:new double[]{.25,.5,.75,30,last-.25,last,last+.25}) {
   double f=s/.5;int i=(int)Math.min(shear.length-2,Math.floor(f));
   System.out.println("SHEAR "+s+" "+(shear[i]+(shear[i+1]-shear[i])*Math.min(1,f-i)));
  }
  ReferenceGroundDust.Relief relief=(x,z)->Math.abs(x)%30<8?0:20;
  var d=ReferenceGroundDust.skirt(150,0,1000,new ReferenceGroundDust.Ground(ReferenceGroundDust.Ground.filled(1),ReferenceGroundDust.Ground.filled(0),ReferenceGroundDust.Ground.filled(0),ReferenceGroundDust.Ground.filled(0),relief),air);
  for(double s:new double[]{1,30,1,30}) {
   double[] sum=new double[6];int[] n={0};
   d.blobs(s,1,(x,y,z,h,v,mass)->{int k=++n[0];double[] a={x,y,z,h,v,mass};for(int j=0;j<6;j++)sum[j]+=k*a[j];});
   System.out.println("STREET "+s+" "+n[0]+" "+Arrays.toString(sum));
  }
 }
}
