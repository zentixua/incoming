from pathlib import Path
root=Path('/mnt/data/projects/incoming/mod/run/wt-MT-B5-probe')
base=root/'mod/src/main/java/ua/zentix/incoming/nuclear/world'
for name,signature,return_type,args in [('RuinMatter','void work(ServerLevel level, WorkClock clock, int units)','void','level, clock, units'),('RuinSupport','boolean step(ServerLevel level, RuinMatter ledger, Detonation d)','boolean','level, ledger, d')]:
 p=base/(name+'.java');s=p.read_text();method='work' if name=='RuinMatter' else 'step';internal=signature.replace(method+'(',method+'Measured(')
 wrapper='public static long benchNanos, benchCalls, benchMax, benchPeak;\n    '+signature+' {\n        long began = System.nanoTime();\n        try { '+('return ' if return_type!='void' else '')+method+'Measured('+args+'); }\n        finally { long elapsed = System.nanoTime()-began; benchNanos+=elapsed; benchCalls++; benchMax=Math.max(benchMax,elapsed); benchPeak=Math.max(benchPeak,retainedBytes()); }\n    }\n    '+internal
 s=s.replace(signature,wrapper);p.write_text(s)
s=Path('/mnt/data/projects/incoming/mod/run/wt-MT-B2/mod/run/mt-b2/evidence/final-head-pairs/PipelineBench.registered.java').read_text()
s=s.replace('sample[0]==9','sample[0]==12').replace('int kind=sample[0]%3;','int kind=sample[0]%2;\n            double kt = new double[]{15,100,1000}[(sample[0]/2)%3];')
s=s.replace('double height=Yield.optimalBurstHeight(15),range=HobBlast.rangeFor(kind==0?2:kind==1?6:20,height,15);','double height=Yield.optimalBurstHeight(kt),range=HobBlast.rangeFor(kind==0?2:6,height,kt);')
s=s.replace('new Detonation(8200+sample[0],','new Detonation(ua.zentix.incoming.nuclear.NuclearEvents.get(level).nextId(),').replace('ground,15,false,0,','ground,kt,false,level.getGameTime(),')
s=s.replace('" kind="+kind+" changed="','" kt="+kt+" kind="+kind+" changed="')
s=s.replace('long[] tick={0};','long[] tick={0}; long[] accepted={0}, peakLedger={0}, peakGraph={0}, peakSupport={0}, peakBudget={0};')
s=s.replace('h.onEachTick(()->{','h.onEachTick(()->{\n            var ledgerNow=RuinMatter.get(level);\n            peakLedger[0]=Math.max(peakLedger[0],ledgerNow.retainedBytes());\n            peakSupport[0]=Math.max(peakSupport[0],ledgerNow.pendingSupport());\n            peakBudget[0]=Math.max(peakBudget[0],NuclearPrep.memory(level, false));\n            try { var f=RuinMatter.class.getDeclaredField("search"); f.setAccessible(true); var graph=(RuinSupport)f.get(ledgerNow); if(graph!=null)peakGraph[0]=Math.max(peakGraph[0],graph.retainedBytes()); } catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}')
s=s.replace('RuinSupport.benchNanos=RuinSupport.benchCalls=RuinSupport.benchMax=0;', '''long saveStart=System.nanoTime();
                var saved=RuinMatter.get(level).save(new net.minecraft.nbt.CompoundTag(),level.registryAccess());
                double saveMs=(System.nanoTime()-saveStart)/1e6;
                long heap=java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                Incoming.LOG.info("MTEND sample={} elapsedMs={} ledgerPeak={} graphPeak={} supportPeak={} reservationPeak={} heapUsed={} saveMs={} savedNbtBytes={}",sample[0]-1,f((System.nanoTime()-accepted[0])/1e6),peakLedger[0],peakGraph[0],peakSupport[0],peakBudget[0],heap,f(saveMs),saved.sizeInBytes());
                peakLedger[0]=peakGraph[0]=peakSupport[0]=peakBudget[0]=0;
                RuinSupport.benchNanos=RuinSupport.benchCalls=RuinSupport.benchMax=RuinSupport.benchPeak=0;''')
s=s.replace('peakGraph[0],peakSupport[0]', 'Math.max(peakGraph[0],RuinSupport.benchPeak),peakSupport[0]')
s=s.replace('long t5=System.nanoTime();', 'long t5=System.nanoTime();\n            var ledger=RuinMatter.get(level); peakLedger[0]=Math.max(peakLedger[0],ledger.retainedBytes()); peakSupport[0]=Math.max(peakSupport[0],ledger.pendingSupport()); peakBudget[0]=Math.max(peakBudget[0],NuclearPrep.memory(level,false)); long checkpointStart=System.nanoTime(); var checkpoint=ledger.save(new net.minecraft.nbt.CompoundTag(),level.registryAccess()); Incoming.LOG.info("MTOWN sample={} pending={} support={} retained={} checkpointNbtBytes={} checkpointMs={}",sample[0],ledger.pending(),ledger.pendingSupport(),ledger.retainedBytes(),checkpoint.sizeInBytes(),f((System.nanoTime()-checkpointStart)/1e6));')
s=s.replace('long t0=System.nanoTime();' ,'long t0=System.nanoTime(); accepted[0]=t0;')
s=s.replace('RuinContext context=new RuinContext(level,d);', 'RuinContext context=NuclearWorld.get(level).ruins(level,d,level.getGameTime());')
s=s.replace('ua.zentix.incoming.nuclear.NuclearEvents.get(level).detonations().remove(active[0]);', 'ua.zentix.incoming.nuclear.NuclearStrikes.clear(level);')
p=root/'mod/src/devtest/java/ua/zentix/incoming/nuclear/world/PipelineBench.java';p.write_text(s)
