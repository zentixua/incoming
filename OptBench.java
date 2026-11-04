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
 @GameTest(template="pad",timeoutTicks=200,batch="mt_opt_matter",skyAccess=true)
 public static void matter(GameTestHelper h) {
  var ledger=new RuinMatter();
  var pieces=new java.util.ArrayList<RuinMatter.Piece>();
  for(int i=0;i<100000;i++) pieces.add(new RuinMatter.Piece(new net.minecraft.core.BlockPos(i%1000,100,i/1000),
    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),null,new net.minecraft.world.phys.Vec3(.123,2.51,-.17),17));
  ledger.release(pieces);
  ledger.support(h.getLevel(),pieces,new ua.zentix.incoming.nuclear.Detonation(12,net.minecraft.world.phys.Vec3.ZERO,0,15,true,0,0,0,20000,42,1,false,.25f));
  var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
  for(int i=0;i<8;i++){
   long alloc=bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
   long a=System.nanoTime();var nbt=ledger.save(new net.minecraft.nbt.CompoundTag(),h.getLevel().registryAccess());long b=System.nanoTime();
   var copy=nbt.copy();long c=System.nanoTime();
   Incoming.LOG.info("MTMATTER sample={} count={} saveNs={} copyNs={} nbtBytes={} allocated={} heap={}",i,ledger.pending(),b-a,c-b,copy.sizeInBytes(),bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-alloc,java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
  }
  h.succeed();
 }

}
