package ua.zentix.incoming.nuclear.world;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;
import ua.zentix.incoming.Incoming;
@GameTestHolder(Incoming.MOD_ID) @PrefixGameTestTemplate(false)
public final class PairedCollapseBench {
 @GameTest(template="range",timeoutTicks=1200,batch="mt_s2_pair",skyAccess=true)
 public static void paired(GameTestHelper h) {
  var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
  for(String kind:new String[]{"dense","natural","columns","partial","floating","border"})
   for(double psi:new double[]{1,6,20,50}) {
    var grid=RuinStorageGameTests.corpus(h,kind,psi);
    for(int z=-1;z<=1;z++)for(int x=-1;x<=1;x++)grid.blast(x,z);
    for(int i=0;i<32;i++) {
     ReferenceCollapse reference; Collapse candidate; long oldNs,newNs,oldAlloc,newAlloc;
     long a,b;
     if(i%2==0) {
      b=bean.getThreadAllocatedBytes(Thread.currentThread().threadId());a=System.nanoTime();
      reference=ReferenceCollapse.solve(grid);oldNs=System.nanoTime()-a;oldAlloc=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-b;
      b=bean.getThreadAllocatedBytes(Thread.currentThread().threadId());a=System.nanoTime();
      candidate=Collapse.solve(grid);newNs=System.nanoTime()-a;newAlloc=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-b;
     } else {
      b=bean.getThreadAllocatedBytes(Thread.currentThread().threadId());a=System.nanoTime();
      candidate=Collapse.solve(grid);newNs=System.nanoTime()-a;newAlloc=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-b;
      b=bean.getThreadAllocatedBytes(Thread.currentThread().threadId());a=System.nanoTime();
      reference=ReferenceCollapse.solve(grid);oldNs=System.nanoTime()-a;oldAlloc=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-b;
     }
     RuinStorageGameTests.sameCollapse(h,reference,candidate,kind+"/"+psi+"/"+i);
     Incoming.LOG.info("MT_S2_PAIR kind={} psi={} sample={} warm={} centre={} burst={} oldNs={} newNs={} oldAlloc={} newAlloc={} support={} direct={}",
      kind,psi,i,i<8,grid.centre(),grid.ctx().d.burst(),oldNs,newNs,oldAlloc,newAlloc,candidate.supportChecked,candidate.directSupport);
    }
   }
  h.succeed();
 }
}
