package ua.zentix.incoming.client.fx.smoke;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.strike.WeaponType;
class CloudBuildProbeTest {
 @Test void measure() {
  for (int run=0; run<120; run++) {
   long start=System.nanoTime();
   var m=new CloudModel(BlastSource.of(WeaponType.DRONE,false), CloudModel.Air.STANDARD);
   System.out.printf(java.util.Locale.ROOT,"CLOUD_BUILD %d %.6f %.9f%n",run,(System.nanoTime()-start)/1e6,m.life);
  }
 }
}
