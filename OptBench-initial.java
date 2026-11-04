package ua.zentix.incoming.nuclear.world;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;
import ua.zentix.incoming.Incoming;
@GameTestHolder(Incoming.MOD_ID) @PrefixGameTestTemplate(false)
public final class OptBench {
 @GameTest(template="range",timeoutTicks=200,batch="mt_opt_bench_dense",skyAccess=true)
 public static void dense(GameTestHelper h)throws Exception { run(h,true); }
 @GameTest(template="range",timeoutTicks=200,batch="mt_opt_bench_natural",skyAccess=true)
 public static void natural(GameTestHelper h)throws Exception { run(h,false); }
 static void run(GameTestHelper h,boolean dense)throws Exception {
  var base=RuinStorageGameTests.fixture(h,dense);
  try(var recording=new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"))){
   recording.start();
   var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
   for(int i=0;i<32;i++){
    var ctx=new RuinContext(h.getLevel(),base.ctx().d);
    var g=new RuinContext.Grid(ctx,base.centre(),base.minY(),base.maxY(),base.shots(),base.view(),base.blockDamage(),base.treeFall(),base.fires());
    long alloc=bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
    long a=System.nanoTime(); for(int z=-1;z<=1;z++)for(int x=-1;x<=1;x++)g.blast(x,z);long b=System.nanoTime();
    var collapse=Collapse.solve(g);long c=System.nanoTime();var p=RuinPlanner.finish(g,collapse);long d=System.nanoTime();
    var nbt=p.store();long e=System.nanoTime();var restored=RuinPlan.restore(nbt,ctx);long f=System.nanoTime();
    h.assertTrue(nbt.equals(restored.store()),"serialization changed output");
    Incoming.LOG.info("MTOPT kind={} sample={} blastNs={} collapseNs={} finishNs={} encodeNs={} decodeNs={} planBytes={} nbtBytes={} allocated={} heap={}",dense?"dense":"natural",i,b-a,c-b,d-c,e-d,f-e,p.bytes(),nbt.sizeInBytes(),bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-alloc,java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
   }
   recording.stop();recording.dump(java.nio.file.Path.of("../../run/mt-opt/"+(dense?"dense":"natural")+".jfr"));
  }
  h.succeed();
 }
}
