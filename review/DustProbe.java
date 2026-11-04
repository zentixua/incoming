import ua.zentix.incoming.client.fx.smoke.DustGrid;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
public class DustProbe {
 public static void main(String[] args) {
  for (int count : new int[]{1,2}) {
   DustGrid.begin(true,0,64,40);
   DustGrid.add(0,64.1,0,8,.1,12,.9f,.85f,.8f,7,3);
   if(count==2) DustGrid.add(0,164.1,0,8,.1,1e-9,.9f,.85f,.8f,8,3);
   var f = DustGrid.finish();
   System.out.printf(java.util.Locale.ROOT,"count=%d cellY=%.8f yMin=%.8f yMax=%.8f horizontal=%.8f vertices=%d%n",f.count,f.dustCellY,f.min[1],f.max[1],f.corner[3],f.layerStart[SmokeGrid.CASCADES*SmokeGrid.N]);
  }
 }
}
