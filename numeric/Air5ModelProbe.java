import ua.zentix.incoming.nuclear.model.*;
import java.util.Locale;
/** Diagnostic only. Calls the unmodified pinned production JAR; no Minecraft startup. */
class Air5ModelProbe {
 public static void main(String[] args) {
  System.out.println("yield_kt,mode,hob_m,t_s,ball_nominal_radius_m,roll_share,cap_bottom_m,cap_top_m,cap_radius_m,ring_bottom_m,dome_bottom_m,stem_top_m,stem_radius_m,stem_cross_tau,ground_dust_top_at_axis_m,ground_dust_vertical_tau_at_axis,stem_start_delay_after_front_s,soil_share,cap_drop_sigma_per_m");
  for(double y: new double[]{5,15}) for(boolean surface:new boolean[]{false,true}) {
   double hob=surface?0:Yield.optimalBurstHeight(y);
   double rf=FireballModel.maxRadius(y,surface);
   StemDust dust=StemDust.of(y,hob,surface,StemDust.CLEAR);
   DustModel.Column col=DustModel.column(0,hob,y,surface,StemDust.CLEAR,DustModel.Surface.REFERENCE);
   for(double t:new double[]{0.1,0.5,1,3,5,7,10,15,20,30,45,60,90,120,180,240,360,600}) {
    // groundFront=0 suppresses only the unreported skirt. It has no effect on any reported field.
    MushroomModel.Shape s=MushroomModel.shape(t,y,hob,surface,0,dust);
    double age=t-HobBlast.arrivalSeconds(0,hob,y);
    System.out.printf(Locale.ROOT,"%.0f,%s,%.9f,%.3f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f%n",y,surface?"GROUND":"AIR",hob,t,rf,MushroomModel.rollUp(t,y,surface),s.capBottom(),s.top(),s.capR(),s.capY()-s.ringB(),s.domeY()-s.domeH(),s.stemTop(),s.stemR(),s.stemTau(),col.height(age),col.depth(age),DustModel.stemFrom(hob,y,rf),MushroomModel.soil(y,hob,surface),s.capDrops());
   }
  }
 }
}
