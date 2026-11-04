package ua.zentix.incoming.nuclear.world;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.*;

/** Private dev-only evidence harness, never part of production diff. */
@GameTestHolder(Incoming.MOD_ID) @PrefixGameTestTemplate(false)
public final class RealCorpusBench {
 @GameTest(template="range",timeoutTicks=10000,batch="mt_s2_real_corpus",skyAccess=true)
 public static void paired(GameTestHelper h) throws Exception {
  Path root=Path.of("../run/mt-s2/corpus");
  if(!Files.exists(root)) root=Path.of("../../run/mt-s2/corpus");
  if(!Files.exists(root)) root=Path.of("/mnt/data/projects/incoming/mod/run/wt-MT-OPT/mod/run/mt-s2/corpus");
  var manifest=JsonParser.parseString(Files.readString(root.resolve("manifest.json"))).getAsJsonObject();
  Map<Long,JsonObject> roots=new LinkedHashMap<>();
  for(var item:manifest.getAsJsonArray("roots")) {var r=item.getAsJsonObject();var c=r.getAsJsonArray("chunk"); roots.putIfAbsent(ChunkPos.asLong(c.get(0).getAsInt(),c.get(1).getAsInt()),r);}
  var cases=new ArrayList<>(roots.entrySet());
  int limit=Integer.getInteger("incoming.corpus.limit",cases.size()); if(limit<cases.size())cases.subList(limit,cases.size()).clear();
  var base=root;
  var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
  var seed=RuinContext.class.getDeclaredField("noise");seed.setAccessible(true);
  var recording=new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
  recording.setMaxSize(128L<<20); recording.start();
  Path recordingPath=root.getParent().resolve("real-corpus-"+System.currentTimeMillis()+".jfr");
  ua.zentix.incoming.gametest.StrikeGameTests.afterTest(h,()->{try{recording.stop();recording.dump(recordingPath);recording.close();}catch(Exception e){throw new RuntimeException(e);}});
  var level=h.getLevel(); h.assertTrue(level.getHeight()==manifest.get("height").getAsInt() && level.getMinBuildHeight()==manifest.get("minY").getAsInt(),"Source geometry mismatch");
  for(int row=0;row<cases.size()*4;row++) {
   int index=row;
   h.runAtTickTime(index+1,()->{
   var entry=cases.get(index/4); double psi=new double[]{1,6,20,50}[index%4];
   var centre=new ChunkPos(entry.getKey()); var reads=new DiskShots.Read[25];long start=System.nanoTime();
   try {
    for(int z=-2;z<=2;z++)for(int x=-2;x<=2;x++) {
     var pos=new ChunkPos(centre.x+x,centre.z+z); var file=base.resolve("chunks/"+pos.x+"_"+pos.z+".nbt.gz");
     var tag=NbtIo.readCompressed(file,NbtAccounter.create(16L<<20));
     var read=DiskShots.parse(level,pos,tag);h.assertTrue(read.skip()==null,"Invalid corpus source "+pos+": "+read.skip()); reads[(z+2)*5+x+2]=read;
    }
    long parseNs=System.nanoTime()-start;start=System.nanoTime();
    for(int i=0;i<reads.length;i++)reads[i]=reads[i].primed(Blast.SERVER);
    long primeNs=System.nanoTime()-start;
    var centreRead=reads[12];int ground=centreRead.motion()[8*16+8]-1;
    double hob=Yield.optimalBurstHeight(1000),distance=HobBlast.rangeFor(psi,hob,1000);
    var d=new Detonation(100,new Vec3(centre.getMiddleBlockX()-distance,ground+hob,centre.getMiddleBlockZ()),ground,1000,false,0,0,3,20000,42,1,false,.25f,0,true);
    var ctx=new RuinContext(level,d);seed.set(ctx,new WorldNoise(manifest.get("seed").getAsLong(),1));
    var shots=new ChunkShot[25];start=System.nanoTime();long shotBytes=0;
    for(int i=0;i<reads.length;i++){shots[i]=ChunkShot.fromDisk(reads[i],null,null,false);ctx.restoreShot(shots[i].pos().toLong(),shots[i]);shotBytes+=shots[i].bytes;}
    var grid=new RuinContext.Grid(ctx,centre,level.getMinBuildHeight(),level.getMaxBuildHeight(),shots,Blast.SERVER,true,true,true);
    long snapshotNs=System.nanoTime()-start;start=System.nanoTime();
    for(int z=-1;z<=1;z++)for(int x=-1;x<=1;x++)grid.blast(x,z);
    long blastNs=System.nanoTime()-start;
    for(int sample=0;sample<4;sample++) {
     ReferenceCollapse old;Collapse now;long a,b,oldNs,newNs,oldBytes,newBytes;
     if(sample%2==0){b=bean.getThreadAllocatedBytes(Thread.currentThread().threadId());a=System.nanoTime();old=ReferenceCollapse.solve(grid);oldNs=System.nanoTime()-a;oldBytes=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-b;b=bean.getThreadAllocatedBytes(Thread.currentThread().threadId());a=System.nanoTime();now=Collapse.solve(grid);newNs=System.nanoTime()-a;newBytes=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-b;}
     else{b=bean.getThreadAllocatedBytes(Thread.currentThread().threadId());a=System.nanoTime();now=Collapse.solve(grid);newNs=System.nanoTime()-a;newBytes=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-b;b=bean.getThreadAllocatedBytes(Thread.currentThread().threadId());a=System.nanoTime();old=ReferenceCollapse.solve(grid);oldNs=System.nanoTime()-a;oldBytes=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-b;}
     RuinStorageGameTests.sameCollapse(h,old,now,"real "+centre+"/"+psi+"/"+sample);
     a=System.nanoTime();var plan=RuinPlanner.finish(grid,now);long finishNs=System.nanoTime()-a;
     a=System.nanoTime();var encoded=plan.detached().store();long encodeNs=System.nanoTime()-a;
     var restored=RuinPlan.restore(encoded,ctx);h.assertTrue(plan.differs(restored)==null,"Real durable plan differs");
     Incoming.LOG.info("MT_S2_REAL case={} group={} x={} z={} psi={} sample={} warm={} crafted={} parseNs={} primeNs={} snapshotNs={} blastNs={} oldNs={} newNs={} finishNs={} encodeNs={} oldBytes={} newBytes={} shotBytes={} planBytes={} changes={} support={} direct={}",index,entry.getValue().get("group").getAsString(),centre.x,centre.z,psi,sample,sample<2,entry.getValue().get("crafted_above64").getAsInt(),parseNs,primeNs,snapshotNs,blastNs,oldNs,newNs,finishNs,encodeNs,oldBytes,newBytes,shotBytes,plan.bytes(),plan.changedBlocks(),now.supportChecked,now.directSupport);
    }
   }catch(Exception e){h.fail("Real corpus case "+index+": "+e);}
   });
  }
  h.runAtTickTime(cases.size()*4+1,()->{Incoming.LOG.info("MT_S2_REAL_DONE cases={} pairs={} recorded={}",cases.size()*4,cases.size()*16,recordingPath);h.succeed();});
 }
}
