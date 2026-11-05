package ua.zentix.incoming.nuclear.world;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.nuclear.model.Yield;
import java.util.concurrent.CompletableFuture;
import java.util.Locale;

/** Disposable end-to-end CPU probe, retained only on the evidence branch. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PipelineBench {
    public static long matterNanos, matterCalls, matterMax;
    @GameTest(template="range",batch="mt_pipeline",timeoutTicks=24000,skyAccess=true)
    public static void measure(GameTestHelper h) {
        var level=h.getLevel();
        ChunkPos center=new ChunkPos(h.absolutePos(new BlockPos(32,2,32)));
        var chunk=level.getChunkSource().getChunkNow(center.x,center.z);
        h.assertTrue(chunk!=null,"Benchmark chunk not ready");
        int ground=h.absolutePos(new BlockPos(0,2,0)).getY();
        int[] sample={0}; long[] tick={0}; long[] accepted={0}, peakLedger={0}, peakGraph={0}, peakSupport={0}, peakBudget={0};
        CompletableFuture<?>[] light={null};
        long[] lightStart={0},lightDone={0};
        String[] row={null};
        double[] applyMs={0};
        Detonation[] active={null};
        h.onEachTick(()->{
            var ledgerNow=RuinMatter.get(level);
            peakLedger[0]=Math.max(peakLedger[0],ledgerNow.retainedBytes());
            peakSupport[0]=Math.max(peakSupport[0],ledgerNow.pendingSupport());
            peakBudget[0]=Math.max(peakBudget[0],NuclearPrep.memory(level, false));
            try { var f=RuinMatter.class.getDeclaredField("search"); f.setAccessible(true); var graph=(RuinSupport)f.get(ledgerNow); if(graph!=null)peakGraph[0]=Math.max(peakGraph[0],graph.retainedBytes()); } catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}
            if(light[0]!=null && light[0].isDone() && row[0]!=null){
                long began=System.nanoTime();
                var packet=new ClientboundLevelChunkWithLightPacket(chunk,level.getChunkSource().getLightEngine(),null,null);
                var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),level.registryAccess(),net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
                ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buffer,packet);
                int bytes=buffer.readableBytes();buffer.release();
                Incoming.LOG.info("MTPIPE {} lightBarrierMs={} encodeMs={} packetBytes={}",row[0],
                        f((lightDone[0]-lightStart[0])/1e6),f((System.nanoTime()-began)/1e6),bytes);
                row[0]=null;
            }
            if(tick[0]>0 && level.getGameTime()-tick[0]<240)return;
            if(tick[0]>0 && RuinMatter.get(level).busy()) {
                h.assertTrue(level.getGameTime()-tick[0]<2400,"Registered material/support work did not settle");
                return;
            }
            if(tick[0]>0){
                try {
                    Class<?> type=Class.forName("ua.zentix.incoming.nuclear.world.RuinMatter");
                    matterNanos=type.getField("benchNanos").getLong(null);
                    matterCalls=type.getField("benchCalls").getLong(null);
                    matterMax=type.getField("benchMax").getLong(null);
                    Object ledger=type.getMethod("get",net.minecraft.server.level.ServerLevel.class).invoke(null,level);
                    Incoming.LOG.info("MTMAT pending={} released={} deposited={}",type.getMethod("pending").invoke(ledger),type.getMethod("released").invoke(ledger),type.getMethod("deposited").invoke(ledger));
                    for(String field:new String[]{"benchNanos","benchCalls","benchMax"})type.getField(field).setLong(null,0);
                } catch(ClassNotFoundException ignored) {} catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
                Incoming.LOG.info("MTMAT sample={} totalMs={} calls={} maxCallMs={}",sample[0]-1,f(matterNanos/1e6),matterCalls,f(matterMax/1e6));
                Incoming.LOG.info("MTINCLUDED sample={} applyMs={} supportMs={} applyPlusSupportMs={} allMaterialMs={} supportCalls={} maxSupportUnitMs={}",
                        sample[0]-1,f(applyMs[0]),f(RuinSupport.benchNanos/1e6),f(applyMs[0]+RuinSupport.benchNanos/1e6),
                        f(matterNanos/1e6),RuinSupport.benchCalls,f(RuinSupport.benchMax/1e6));
                long saveStart=System.nanoTime();
                var saved=RuinMatter.get(level).save(new net.minecraft.nbt.CompoundTag(),level.registryAccess());
                double saveMs=(System.nanoTime()-saveStart)/1e6;
                long heap=java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                Incoming.LOG.info("MTEND sample={} elapsedMs={} ledgerPeak={} graphPeak={} supportPeak={} reservationPeak={} heapUsed={} saveMs={} savedNbtBytes={}",sample[0]-1,f((System.nanoTime()-accepted[0])/1e6),peakLedger[0],Math.max(peakGraph[0],RuinSupport.benchPeak),peakSupport[0],peakBudget[0],heap,f(saveMs),saved.sizeInBytes());
                peakLedger[0]=peakGraph[0]=peakSupport[0]=peakBudget[0]=0;
                RuinSupport.benchNanos=RuinSupport.benchCalls=RuinSupport.benchMax=RuinSupport.benchPeak=0;
                matterNanos=matterCalls=matterMax=0;
                ua.zentix.incoming.nuclear.NuclearStrikes.clear(level);
                RuinMatterGameTests.clearFixture(level);
            }
            if(sample[0]==12){h.succeed();return;}
            int kind=sample[0]%2;
            double kt = new double[]{15,100,1000}[(sample[0]/2)%3];
            // Fixture setup is excluded from the production-stage timing. No disk load in a timed stage.
            for(int z=1;z<15;z++)for(int x=1;x<15;x++)for(int y=0;y<=65;y++){
                BlockState state=Blocks.AIR.defaultBlockState();
                boolean edge=x==1||x==14||z==1||z==14;
                if(y==0)state=Blocks.STONE.defaultBlockState();
                else if(kind==0){if(y==6)state=Blocks.STONE.defaultBlockState();else if(y<6&&edge)state=Blocks.GLASS.defaultBlockState();}
                else if(y<=60){if(y%5==0||((x==1||x==14)&&(z==1||z==14)))state=(kind==1?Blocks.WHITE_CONCRETE:Blocks.OAK_LOG).defaultBlockState();else if(edge)state=Blocks.GLASS.defaultBlockState();}
                level.setBlock(new BlockPos(center.getMinBlockX()+x,ground+y,center.getMinBlockZ()+z),state,18);
            }
            double height=Yield.optimalBurstHeight(kt),range=HobBlast.rangeFor(kind==0?2:6,height,kt);
            Detonation d=new Detonation(ua.zentix.incoming.nuclear.NuclearEvents.get(level).nextId(),new Vec3(center.getMiddleBlockX()-range,ground+height,center.getMiddleBlockZ()),ground,kt,false,level.getGameTime(),0,3,20000,42,1,false,.25f,0,false);
            active[0]=d;
            ua.zentix.incoming.nuclear.NuclearEvents.get(level).add(d);
            long t0=System.nanoTime(); accepted[0]=t0;
            RuinContext context=NuclearWorld.get(level).ruins(level,d,level.getGameTime());
            var grid=context.grid(level,center,false);
            long t1=System.nanoTime();
            for(int z=-1;z<=1;z++)for(int x=-1;x<=1;x++)grid.blast(x,z);
            long t2=System.nanoTime();
            var collapsed=Collapse.solve(grid);long t3=System.nanoTime();
            var plan=RuinPlanner.finish(grid,collapsed);long t4=System.nanoTime();
            h.assertTrue(plan.apply(level,chunk,d,new ColumnScar.Budget(false)),"Fresh benchmark plan rejected");
            long t5=System.nanoTime();
            var ledger=RuinMatter.get(level); peakLedger[0]=Math.max(peakLedger[0],ledger.retainedBytes()); peakSupport[0]=Math.max(peakSupport[0],ledger.pendingSupport()); peakBudget[0]=Math.max(peakBudget[0],NuclearPrep.memory(level,false)); long checkpointStart=System.nanoTime(); var checkpoint=ledger.save(new net.minecraft.nbt.CompoundTag(),level.registryAccess()); Incoming.LOG.info("MTOWN sample={} pending={} support={} retained={} checkpointNbtBytes={} checkpointMs={}",sample[0],ledger.pending(),ledger.pendingSupport(),ledger.retainedBytes(),checkpoint.sizeInBytes(),f((System.nanoTime()-checkpointStart)/1e6));
            applyMs[0]=(t5-t4)/1e6;
            row[0]="sample="+sample[0]+" kt="+kt+" kind="+kind+" changed="+plan.changedBlocks()+" planBytes="+plan.bytes()
                    +" snapshotMs="+f((t1-t0)/1e6)+" blastMs="+f((t2-t1)/1e6)+" collapseMs="+f((t3-t2)/1e6)
                    +" finishMs="+f((t4-t3)/1e6)+" applyMs="+f((t5-t4)/1e6);
            lightStart[0]=System.nanoTime();
            light[0]=level.getChunkSource().getLightEngine().waitForPendingTasks(center.x,center.z)
                    .thenRun(()->lightDone[0]=System.nanoTime());
            tick[0]=level.getGameTime();sample[0]++;
        });
    }
    private static String f(double value){return String.format(Locale.ROOT,"%.4f",value);}
}
