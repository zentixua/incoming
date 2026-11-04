package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import java.util.List;

/** Disposable registered-continuation probe; timing assertions are deliberately absent. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ReviewSupportBench {
    private ReviewSupportBench() {}
    private static Object field(Object object, String name) {
        try {
            var f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    @GameTest(template="range", batch="mt_review_support_bench", timeoutTicks=1200, skyAccess=true)
    public static void measure(GameTestHelper h) throws java.io.IOException {
        var level = h.getLevel();
        BlockPos corner = h.absolutePos(new BlockPos(8, 35, 8));
        for (int sample=0;sample<9;sample++) {
            RuinMatterGameTests.clearFixture(level);
            var ledger = RuinMatter.get(level);
            for(int x=0;x<48;x++)for(int z=0;z<48;z++)
                for(int y=-2;y<=0;y++)level.setBlock(corner.offset(x,y,z),Blocks.AIR.defaultBlockState(),18);
            int side = new int[]{16,32,48}[sample%3];
            var chunks = new java.util.HashSet<Long>();
            for(int x=0;x<side;x++)for(int z=0;z<side;z++) {
                BlockPos at=corner.offset(x,0,z);
                level.setBlock(at,Blocks.WHITE_CONCRETE.defaultBlockState(),18);
                chunks.add(new ChunkPos(at).toLong());
            }
            level.setBlock(corner.below(),Blocks.GLASS.defaultBlockState(),18);
            level.setBlock(corner.below(2),Blocks.OBSIDIAN.defaultBlockState(),18);
            var events=NuclearEvents.get(level);
            var d=new Detonation(events.nextId(),Vec3.atCenterOf(corner.west(3000)),corner.getY(),15,false,
                    level.getGameTime(),0,3,20000,42,1,false,.25f,6000,false);
            events.add(d);
            try {
                var source=RuinMatter.capture(level,corner.below(),level.getBlockState(corner.below()),d);
                ColumnScar.replace(level,corner.below(),source.state,Blocks.AIR.defaultBlockState());
                ledger.release(List.of(source)); ledger.support(level,List.of(source),d);
                WorkClock clock=WorkClock.counting(1);
                long nanos=0,max=0;int units=0,peakGraph=0,peakSeeds=0;
                while(ledger.pendingSupport()>0 && units<20000) {
                    clock.start(1); long began=System.nanoTime();ledger.work(level,clock);long took=System.nanoTime()-began;
                    nanos+=took;max=Math.max(max,took);units++;
                    Object search=field(ledger,"search");
                    if(search!=null) {
                        int graph=((java.util.Collection<?>)field(search,"seen")).size()
                                +((java.util.Map<?,?>)field(search,"component")).size()
                                +((java.util.Collection<?>)field(search,"frontier")).size();
                        peakGraph=Math.max(peakGraph,graph);
                    }
                    peakSeeds=Math.max(peakSeeds,ledger.pendingSupport());
                }
                h.assertTrue(ledger.pendingSupport()==0 && ledger.released()==side*side+1L,"Support continuation did not finish");
                long began=System.nanoTime();CompoundTag tag=ledger.save(new CompoundTag(),level.registryAccess());
                var bytes=new java.io.ByteArrayOutputStream();NbtIo.write(tag,new java.io.DataOutputStream(bytes));
                long save=System.nanoTime()-began;
                began=System.nanoTime();var loaded=RuinMatter.load(tag,level.registryAccess());long load=System.nanoTime()-began;
                h.assertTrue(loaded.pending()==side*side,"Saved ledger lost pending roof material");
                Incoming.LOG.info("MTSUPPORT sample={} side={} chunks={} released={} units={} totalMs={} perChunkMs={} maxUnitMs={} peakGraphEntries={} peakSupportSeeds={} savedBytes={} saveMs={} loadMs={}",
                        sample,side,chunks.size(),ledger.released(),units,nanos/1e6,nanos/1e6/chunks.size(),max/1e6,
                        peakGraph,peakSeeds,bytes.size(),save/1e6,load/1e6);
            } finally {events.detonations().remove(d);RuinMatterGameTests.clearFixture(level);}
        }
        h.succeed();
    }
}
