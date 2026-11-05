package ua.zentix.incoming.nuclear.model;
import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.nuclear.Detonation;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.assertTrue;
/** Private measurement of changed hot lookups, not a GPU or complete-frame benchmark. */
class Air8HotPathProbeTest {
 private static final double[] VIS={20000,5000,8000,10000};
 private static final StemDust.Source[] SOURCES=new StemDust.Source[4];
 private static final StemDust.Request[] REQUESTS=new StemDust.Request[4];
 private static volatile double sink;
 private static final int N=500000;
 private static final com.sun.management.ThreadMXBean BEAN=(com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
 private static void spin(boolean legacy,int n) {
  double sum=0;
  for(int i=0;i<n;i++) {
   int c=i&3; double t=(i%12000)/100.0;
   if(legacy) {
    Air8LegacyDust d=Air8LegacyDust.ready(15,592,false,VIS[c]);
    sum+=d.tau(t,200)+d.top(t)+HobBlast.arrivalSeconds(500,592,15);
   } else {
    StemDust d=REQUESTS[c].ready();
    sum+=d.tau(t,200)+d.top(t)+SOURCES[c].arrivalSeconds(500);
   }
  }
  sink=sum;
 }
 private static long sample(String name,boolean legacy) {
  long id=Thread.currentThread().threadId();long bytes=BEAN.getThreadAllocatedBytes(id), cpu=BEAN.getCurrentThreadCpuTime();
  spin(legacy,N);
  long elapsed=BEAN.getCurrentThreadCpuTime()-cpu, allocated=BEAN.getThreadAllocatedBytes(id)-bytes;
  System.out.printf(Locale.ROOT,"AIR8_HOT,%s,%d,%d,%d,%.9f,%.9f%n",name,N,elapsed,allocated,(double)elapsed/N,(double)allocated/N);
  return allocated;
 }
 @Test void changedLookupsHaveNoSteadyFrameAllocation() {
  BEAN.setThreadAllocatedMemoryEnabled(true); BEAN.setThreadCpuTimeEnabled(true);
  for(int c=0;c<4;c++) {
   var d=new Detonation(1,new Vec3(0,656,0),64,15,false,0,0,0,(float)VIS[c],7,1,false,0.25f);
   SOURCES[c]=new StemDust.Source(15,592,false,VIS[c],d.front(),d.scale());
   REQUESTS[c]=StemDust.request(SOURCES[c]);StemDust.of(SOURCES[c]);Air8LegacyDust.of(15,592,false,VIS[c]);
  }
  for(int warm=0;warm<4;warm++){spin(true,50000);spin(false,50000);}
  sample("A1-physical-clock",true);long b=sample("B-actual-clock",false);sample("A2-physical-clock",true);
  assertTrue(Double.isFinite(sink));
  assertTrue(b<=1024,"at most fixed measurement overhead, no per-call allocation: "+b);
 }
}
