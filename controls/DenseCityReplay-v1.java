package ua.zentix.incoming.nuclear.world;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.model.WorldNoise;

/** Private bounded replay: exact failed-case ledger plus a fresh, translated real-city cohort.
 * This is not a reconstruction of the stopped world's block states or a clean timing benchmark. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DenseCityReplay {
    private static final Path INPUT = Path.of(System.getProperty("incoming.replay.ledger"));
    private static final Path CORPUS = Path.of(System.getProperty("incoming.replay.corpus"));
    private static final long COMPETING = 145L << 20;
    private static final int SHIFT_X = 100_000, SHIFT_Z = 100_000;
    private static final long EPOCH = 2_040_660_000L;
    private static final int[][] SOURCES = {{-13,-54},{-12,-54},{-13,-53}};
    private record Target(LevelChunk chunk, RuinPlan expected) {}

    @GameTest(template = "pad", batch = "mt_s7_dense_replay", timeoutTicks = 1400, skyAccess = true)
    public static void actualSavedMaterialAndDenseCityConsumers(GameTestHelper h) throws Exception {
        StrikeGameTests.gameSpeed(h);
        ServerLevel level = h.getLevel();
        NuclearStrikes.clear(level);
        RuinMatterGameTests.clearFixture(level);
        var world = NuclearWorld.get(level);
        var other = NuclearWorld.get(level.getServer().getLevel(Level.NETHER));
        long oldReservation = other.materialReservation;
        long oldTime = level.getGameTime();
        var oldClock = NuclearWorld.clock(level.getServer());
        var clock = WorkClock.counting(1_000_000);
        NuclearWorld.useClock(level.getServer(), clock);
        RuinMemory.Reservation[] importedOwner = {null};
        var area = new ua.zentix.incoming.strike.AreaLoader.Area(
                net.minecraft.server.level.TicketType.create("incoming_dense_replay", java.util.Comparator.<UUID>naturalOrder()),
                new ChunkPos(SHIFT_X-13,SHIFT_Z-54), 5, UUID.randomUUID(), false);
        ua.zentix.incoming.strike.StrikeWorld.get(level).areas().hold(level, area);
        StrikeGameTests.afterTest(h, () -> {
            if(importedOwner[0]!=null)importedOwner[0].close();
            other.materialReservation = oldReservation;
            ScarQueue.seenForTest(null);
            NuclearWorld.useClock(level.getServer(), oldClock);
            NuclearStrikes.clear(level);
            RuinMatterGameTests.clearFixture(level);
            ua.zentix.incoming.strike.StrikeWorld.get(level).areas().release(level, area);
            RuinWorkers.shutdown();
            ((net.minecraft.world.level.storage.ServerLevelData) level.getLevelData()).setGameTime(oldTime);
        });
        var reading = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                var measure = LedgerScan.measure(INPUT,"incoming_ruin_matter",java.util.Set.of(),NuclearPrep.PLANS_CAP);
                Incoming.LOG.info("DENSE_REPLAY original cold ownership {} row {} cap {}",measure.owned(),measure.row(),NuclearPrep.PLANS_CAP);
                // The baseline runtime already owned this ledger although its cold estimator
                // refuses it. Replay that resident state explicitly, without calling it a valid
                // cold-load receipt. The candidate must additionally pass the real import gate.
                var value = (RuinMatter) LedgerReader.read(INPUT,"incoming_ruin_matter",level.registryAccess(),16L<<20,java.util.Set.of());
                return new Loaded(value,measure);
            } catch (Exception error) { throw new RuntimeException(error); }
        },net.minecraft.Util.ioPool());
        List<Target> targets = new ArrayList<>();
        List<LevelChunk> cohort = new ArrayList<>();
        List<Long> keys = new ArrayList<>();
        boolean[] installed = {false};
        long[] began = {0}, peak = {0}, releasedBefore = {0};
        long[] savedCounts = new long[3];
        double[] savedVolume = new double[1];
        int[] phase = {0};
        var resultFile = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data/replay-roundtrip-"+UUID.randomUUID()+".dat");
        var loaded = new Loaded[1];
        var context = new RuinContext[1];
        var plans = new RetainedPlans[1];
        var event = new Detonation[1];
        var roundtrip = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<RuinMatter>>();
        h.succeedWhen(() -> {
            if (!installed[0]) {
                h.assertTrue(reading.isDone(),"Actual ledger is still importing");
                loaded[0] = reading.join();
                RuinMatter matter = loaded[0].matter;
                importedOwner[0]=RuinMemory.reserveInputs(level,matter.retainedBytes());
                h.assertTrue(importedOwner[0]!=null,"Actual resident replay input cannot be owned");
                ((net.minecraft.world.level.storage.ServerLevelData) level.getLevelData()).setGameTime(EPOCH);
                event[0] = detonation(3,SHIFT_X*16,SHIFT_Z*16);
                try {
                    // Exactly the same immutable source bytes and destination coordinates in old/new.
                    var copied = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
                    for (int[] centre : SOURCES) for (int z=-2;z<=2;z++) for(int x=-2;x<=2;x++) {
                        int sx=centre[0]+x,sz=centre[1]+z;
                        if(copied.add(ChunkPos.asLong(sx,sz))) copySource(level,sx,sz);
                    }
                    context[0] = world.ruins(level,event[0],level.getGameTime());
                    field(context[0],"noise").set(context[0],new WorldNoise(-1905315873147090944L,1));
                    plans[0] = new RetainedPlans(0);
                    var store = new RuinPages(new RegionStorageInfo("gametest",level.dimension(),"incoming_preparation"),
                            resultFile.getParent().resolve("replay-plans-"+UUID.randomUUID()));
                    plans[0].spill(store,context[0],world.prep().readAccounting(),
                            () -> Math.max(0,NuclearPrep.PLANS_CAP-NuclearPrep.residentMemory(level)));
                    context[0].restoredPlans=plans[0];
                    for(int[] centre:SOURCES) {
                        ChunkPos pos=new ChunkPos(SHIFT_X+centre[0],SHIFT_Z+centre[1]);
                        var chunk=level.getChunkSource().getChunkNow(pos.x,pos.z);
                        h.assertTrue(chunk!=null,"Source cohort must be FULL");
                        h.assertTrue(context[0].submit(level,chunk,RuinWorkers.Priority.SEEN),"Real source worker admission refused");
                        cohort.add(chunk);keys.add(pos.toLong());
                    }
                } catch(Exception error) { throw new RuntimeException(error); }
                releasedBefore[0]=matter.released();
                installed[0]=true;
                Incoming.LOG.info("DENSE_REPLAY installed exact ledger released {} pending {} support {} bytes {}",
                        matter.released(),matter.pending(),matter.pendingSupport(),matter.retainedBytes());
            }
            if(phase[0]==0) {
                if(targets.isEmpty()) {
                    for(LevelChunk chunk:cohort)h.assertTrue(context[0].done(chunk.getPos().toLong()),"Real city solver still running");
                    for(LevelChunk chunk:cohort) {
                        RuinPlan plan=context[0].collect(level,chunk);
                        h.assertTrue(plan!=null && plan.changedBlocks()>4000,"Real source did not produce dense damage");
                        targets.add(new Target(chunk,plan));plans[0].put(chunk.getPos().toLong(),plan);
                        try {
                            Incoming.LOG.info("DENSE_REPLAY input chunk {} changed {} material {} plan {}",chunk.getPos(),plan.changedBlocks(),
                                    ((long[])field(plan,"material").get(plan)).length,plan.bytes());
                        } catch(Exception error) {throw new RuntimeException(error);}
                    }
                }
                plans[0].pump(Math.max(0,NuclearPrep.PLANS_CAP-NuclearPrep.residentMemory(level)));
                h.assertTrue(plans[0].pendingWrites()==0,"Actual plan spill receipts pending");
                RuinMatter input=loaded[0].matter;
                level.getDataStorage().set("incoming_ruin_matter",input); world.ledger(input);
                importedOwner[0].close();importedOwner[0]=null;
                NuclearEvents.get(level).add(detonation(2,0,0));NuclearEvents.get(level).add(event[0]);
                other.materialReservation=oldReservation+COMPETING;
                ScarQueue.seenForTest(keys::contains);
                for(Target target:targets)world.scars().offer(target.chunk,event[0]);
                began[0]=level.getGameTime();phase[0]=1;
            }
            RuinMatter matter=loaded[0].matter;
            if(phase[0]==1) {
                long owned=NuclearPrep.residentMemory(level);peak[0]=Math.max(peak[0],owned);
                h.assertTrue(other.materialReservation==oldReservation+COMPETING,"Fixture refunded competing ownership");
                h.assertTrue(owned<=NuclearPrep.PLANS_CAP,"Whole replay owner exceeds cap: "+owned);
                int pending=0;
                for(Target target:targets) {
                    h.assertTrue(level.getChunkSource().getChunkNow(target.chunk.getPos().x,target.chunk.getPos().z)==target.chunk,
                            "Required dense chunk was unloaded");
                    if(world.scars().queued(target.chunk.getPos().toLong()))pending++;
                    else h.assertTrue(target.chunk.getExistingData(ua.zentix.incoming.registry.ModAttachments.CHUNK_SCAR).orElse(0)==3,
                            "Required dense chunk left queue without scar");
                }
                long age=level.getGameTime()-began[0];
                if(age%20==0)Incoming.LOG.info("DENSE_REPLAY age {} pending {} resident {} ledger {} deposited {} moving {} support {} stats {}",
                        age,pending,owned,matter.retainedBytes(),matter.deposited(),matter.pending(),matter.pendingSupport(),
                        java.util.Arrays.toString(world.scars().ruinStats()));
                if(age%20==0)for(Target target:targets)if(world.scars().queued(target.chunk.getPos().toLong()))
                    diagnose(level,world,matter,target,event[0],age);
                h.assertTrue(age<160 || pending==0,"Full real dense cohort did not progress with the actual ledger");
                h.assertTrue(pending==0,"Dense replay still has required consumers: "+pending);
                h.assertTrue(matter.released()>releasedBefore[0],"No new physical material was captured");
                h.assertTrue(matter.pending()>0 && matter.pendingSupport()>0,"Concurrent actual material/support drained before consumers");
                h.assertTrue(context[0].tasks()==0 && context[0].pendingWindowReads()==0 && plans[0].pendingReads()==0,
                        "Successful cohort orphaned a producer/read");
                h.assertTrue(loaded[0].measure.owned()<=NuclearPrep.PLANS_CAP,"The original native ledger cannot pass normal cold admission");
                Incoming.LOG.info("DENSE_REPLAY ALL {} native consumers in {} ticks; peak {}, released delta {}",
                        targets.size(),age,peak[0],matter.released()-releasedBefore[0]);
                savedCounts[0]=matter.released();savedCounts[1]=matter.deposited();savedCounts[2]=matter.pending();
                savedVolume[0]=matter.releasedVolume();
                matter.setDirty();matter.save(resultFile.toFile(),level.registryAccess());phase[0]=2;
            }
            h.assertTrue(matter.writable(),"Actual ledger normal save is pending");
            if(roundtrip.get()==null)roundtrip.set(java.util.concurrent.CompletableFuture.supplyAsync(()->{
                try {
                    var measure=LedgerScan.measure(resultFile,"incoming_ruin_matter",java.util.Set.of(),NuclearPrep.PLANS_CAP);
                    if(measure.owned()>NuclearPrep.PLANS_CAP)throw new IllegalStateException("Saved replay cannot pass normal cold admission: "+measure.owned());
                    var value=(RuinMatter)LedgerReader.read(resultFile,"incoming_ruin_matter",level.registryAccess(),measure.row(),java.util.Set.of());
                    if(value.retainedBytes()>measure.owned())throw new IllegalStateException("Actual imported ownership exceeds its reservation");
                    return value;
                }catch(Exception error){throw new RuntimeException(error);}
            },net.minecraft.Util.ioPool()));
            h.assertTrue(roundtrip.get().isDone(),"Actual normal streaming reload is pending");
            RuinMatter after=roundtrip.get().join();
            h.assertTrue(after.released()==savedCounts[0] && after.deposited()==savedCounts[1] && after.pending()==savedCounts[2],
                    "Actual count ledger changed on normal reload");
            h.assertTrue(after.releasedVolume()==savedVolume[0],"Actual material volume changed on reload");
        });
    }

    private record Loaded(RuinMatter matter,LedgerScan.Measure measure) {}
    private static Detonation detonation(int id,int dx,int dz) {
        return new Detonation(id,new Vec3(3104.5+dx,63,560.5+dz),63,1000,false,EPOCH-12000,
                0,0,20000,42,1,false,.25f,6000,false);
    }
    private static void copySource(ServerLevel level,int x,int z) throws Exception {
        CompoundTag tag=NbtIo.readCompressed(CORPUS.resolve("chunks/"+x+"_"+z+".nbt.gz"),NbtAccounter.create(16L<<20));
        ChunkPos pos=new ChunkPos(x+SHIFT_X,z+SHIFT_Z);
        tag.putInt("xPos",pos.x);tag.putInt("zPos",pos.z);
        var read=DiskShots.parse(level,pos,tag);
        if(read.skip()!=null)throw new IllegalStateException("Strict real source parse failed "+pos+": "+read.skip());
        read=read.primed(Blast.SERVER);
        LevelChunk chunk=level.getChunk(pos.x,pos.z);
        for(int i=0;i<chunk.getSectionsCount();i++) {
            var states=read.states()[i]==null ? new net.minecraft.world.level.chunk.PalettedContainer<>(
                    net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY,
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                    net.minecraft.world.level.chunk.PalettedContainer.Strategy.SECTION_STATES)
                    : RuinPlan.copyStates(read.states()[i]);
            chunk.getSections()[i]=new LevelChunkSection(states,chunk.getSection(i).getBiomes());
        }
        Heightmap.primeHeightmaps(chunk,java.util.EnumSet.allOf(Heightmap.Types.class));
        for(var value:tag.getList("block_entities",10)) {
            CompoundTag entity=((CompoundTag)value).copy();
            entity.putInt("x",entity.getInt("x")+SHIFT_X*16);entity.putInt("z",entity.getInt("z")+SHIFT_Z*16);
            chunk.setBlockEntityNbt(entity);
        }
    }
    private static Field field(Object owner,String name)throws ReflectiveOperationException {
        Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f;
    }

    private static void diagnose(ServerLevel level,NuclearWorld world,RuinMatter matter,Target target,Detonation d,long age) {
        try {
            var parked=(java.util.Map<?,?>)field(world.scars(),"parked").get(world.scars());
            Object kept=parked.get(target.chunk.getPos().toLong());
            RuinPlan plan=target.expected;
            if(kept!=null) {
                var accessor=kept.getClass().getDeclaredMethod("plan");accessor.setAccessible(true);
                plan=(RuinPlan)accessor.invoke(kept);
            }
            long[] material=(long[])field(plan,"material").get(plan);
            long capture;
            try {
                var method=RuinMatter.class.getDeclaredMethod("removalBytes",long[].class);method.setAccessible(true);
                capture=(long)method.invoke(matter,(Object)material);
            } catch(NoSuchMethodException old) {
                var method=RuinMatter.class.getDeclaredMethod("removalBytes",int.class);method.setAccessible(true);
                capture=(long)method.invoke(matter,material.length);
            }
            String mismatch="none";
            int[] cells=(int[])field(plan,"cells").get(plan);
            var olds=(net.minecraft.world.level.block.state.BlockState[])field(plan,"olds").get(plan);
            for(int i=0;i<cells.length;i++) {
                int c=cells[i];BlockPos at=new BlockPos(target.chunk.getPos().getMinBlockX()+(c&15),
                        target.chunk.getMinBuildHeight()+((c>>>12&255)<<4)+(c>>>8&15),
                        target.chunk.getPos().getMinBlockZ()+(c>>>4&15));
                var live=target.chunk.getBlockState(at);
                if(live!=olds[i]) {
                    var original=matter.original(level,at);
                    mismatch=at+":"+olds[i]+"->"+live+",journal="+original;
                    break;
                }
            }
            Incoming.LOG.info("DENSE_REPLAY consumer age {} chunk {} plan {} memoryWaiting {} current {} materials {} required {} resident {} mismatch {}",
                    age,target.chunk.getPos(),kept==null?"reference":"parked",plan.memoryWaiting(),plan.current(target.chunk,d),
                    material.length,capture,NuclearPrep.residentMemory(level),mismatch);
        } catch(Exception error) {throw new RuntimeException(error);}
    }
}
