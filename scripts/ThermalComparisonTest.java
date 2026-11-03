package ua.zentix.incoming.client.fx.smoke;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ThermalComparisonTest {
 @Test void exact() {
  for (double n : new double[]{.005,.01,.02}) for (double heat : new double[]{0,1e6,1e9,1e12}) {
   var air=new CloudModel.Air(n,3,-2,.2,3,.5);
   var a=new Thermal(Thermal.buoyancy(heat),2,1,air);
   var b=new ReferenceThermal(Thermal.buoyancy(heat),2,1,air);
   for(double t : new double[]{0,.02,.05,.5,1,10,25,50,100,150,300,600,3600,1,2}) {
    assertEquals(b.height(t),a.height(t),0,"height");
    assertEquals(b.radius(t),a.radius(t),0,"radius");
    assertEquals(b.kelvin(t),a.kelvin(t),0,"temp");
    assertEquals(b.driftX(t),a.driftX(t),0,"drift at "+t);
    assertEquals(b.driftZ(t),a.driftZ(t),0,"drift at "+t);
   }
  }
 }
}
