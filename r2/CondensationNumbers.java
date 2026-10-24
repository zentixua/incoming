import ua.zentix.incoming.client.fx.smoke.Condensation;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.ChemicalFront;
public class CondensationNumbers {
 public static void main(String[] args) {
  System.out.println("kg,radius_m,RH,peak_kPa,min_delta_kPa,dry_C,dewpoint_C,water_g_m3,arrival_s,positive_s,reach_m,life_s");
  for(double rh: new double[]{.1,.5,.8,1}) {
   var c=new Condensation(50,true,rh);var f=ChemicalFront.cached(50,true);
   for(double r: new double[]{10,20,50,100}) {
    double p=f.kpa(r),delta=-Math.min(101325*.5,1000*p/Math.exp(2));
    double vapor=rh*Condensation.saturation(288.15)*(101325+delta)/101325;
    double l=Math.log(vapor/610.94), dew=243.04*l/(17.625-l);
    System.out.printf(java.util.Locale.ROOT,"50,%.0f,%.2f,%.4f,%.4f,%.4f,%.4f,%.6f,%.6f,%.6f,%.2f,%.6f%n",r,rh,p,delta/1000,Condensation.temperature(p,delta)-273.15,dew,1000*Condensation.water(p,delta,rh),f.arrivalSeconds(r),BlastModel.chemicalPhaseSeconds(r,50,true),c.reach,c.life);
   }
  }
 }
}
