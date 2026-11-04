package ua.zentix.incoming.nuclear.world;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import java.lang.management.ManagementFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.*;

/** Disposable Stage A diagnostic: paired solver, immutable synthetic snapshots, no live world changes. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MtRuinBench {
    private static final BlockState AIR=Blocks.AIR.defaultBlockState();
    private static final int FLOOR=64, TOP=192;
    private static final com.sun.management.ThreadMXBean CPU=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    private static String f(double v){return String.format(Locale.ROOT,"%.4f",v);}
    private static void row(String s){Incoming.LOG.info("MTRUIN "+s);}
    interface Shape {BlockState at(int x,int y,int z);}
    private static Object field(Object o,String n)throws Exception{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    @SuppressWarnings("unchecked")
    private static DiskShots.Read make(ChunkPos p,int minY,int height,Shape shape){
        var states=(PalettedContainer<BlockState>[])new PalettedContainer<?>[height/16];
        for(int y=FLOOR-1;y<=TOP;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            BlockState st=y<=FLOOR?Blocks.STONE.defaultBlockState():shape.at(p.x*16+x,y,p.z*16+z);
            if(st.isAir())continue;
            int s=(y-minY)>>4;
            if(states[s]==null)states[s]=new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,AIR,PalettedContainer.Strategy.SECTION_STATES);
            states[s].set(x,y&15,z,st);Blast.ensure(st);
        }
        return new DiskShots.Read(p,minY,states,new int[256*RuinPlan.HEIGHTMAP_TYPES.length],15,null,0);
    }
    private static Shape hall(Block roof,int r){return (x,y,z)->{
        int dx=Math.abs(x-8),dz=Math.abs(z-8);
        if(dx>r||dz>r)return AIR;
        if(y==70)return roof.defaultBlockState();
        if(y>=65&&y<70&&(dx==r||dz==r))return Blocks.GLASS.defaultBlockState();
        return AIR;
    };}
    private static Shape tower(Block material){return (x,y,z)->{
        if(x<0||x>15||z<0||z>15||y>145)return AIR;
        boolean corner=(x==0||x==15)&&(z==0||z==15);
        if(corner||y%5==0)return material.defaultBlockState();
        if(x==0||x==15||z==0||z==15)return Blocks.GLASS.defaultBlockState();
        return AIR;
    };}
    private static Shape trunks(int height,boolean crown){return (x,y,z)->{
        if(x==8&&z==8&&y>=65&&y<65+height)return Blocks.OAK_LOG.defaultBlockState();
        if(crown&&Math.abs(x-8)<=1&&Math.abs(z-8)<=1&&y>=65+height&&y<=66+height)return Blocks.OAK_LEAVES.defaultBlockState();
        return AIR;
    };}
    private static Detonation det(double kt,double psi){
        double h=Yield.optimalBurstHeight(kt),r=HobBlast.rangeFor(psi,h,kt);
        return new Detonation(700,new Vec3(8-r,65+h,8),65,kt,false,0,0,3,20000,42,1,false,.25f,6000,false);
    }
    private static boolean bearing(BlockState s){return Blast.props(s).bearing();}
    /** Exact six-neighbor connectivity diagnostic, not a load or structural-strength solver. */
    private static int disconnected(BlockState[] a){
        int sx=48,sy=TOP-FLOOR+1,n=a.length;boolean[] seen=new boolean[n];int[] q=new int[n];int head=0,tail=0;
        for(int z=0;z<sx;z++)for(int x=0;x<sx;x++){int i=z*sx+x;if(bearing(a[i])){seen[i]=true;q[tail++]=i;}}
        while(head<tail){int i=q[head++],x=i%sx,z=i/sx%sx,y=i/(sx*sx);
            int[] ns={x>0?i-1:-1,x<sx-1?i+1:-1,z>0?i-sx:-1,z<sx-1?i+sx:-1,y>0?i-sx*sx:-1,y<sy-1?i+sx*sx:-1};
            for(int j:ns)if(j>=0&&!seen[j]&&bearing(a[j])){seen[j]=true;q[tail++]=j;}
        }
        int out=0;for(int i=48*48;i<n;i++)if(!seen[i]&&bearing(a[i]))out++;return out;
    }
    private static int index(int x,int y,int z){return ((y-FLOOR)*48+z+16)*48+x+16;}
    private static void run(ServerLevel level,String name,Shape shape,double kt,double psi,boolean inject,int rep)throws Exception{
        Detonation d=det(kt,psi);RuinContext ctx=new RuinContext(level,d);
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)ctx.putDisk(level,make(new ChunkPos(x,z),level.getMinBuildHeight(),level.getHeight(),shape));
        RuinPlanner.ensureOwnStates();
        if(inject){
            @SuppressWarnings("unchecked") var map=(Map<Long,Blast>)field(ctx,"blasts");
            for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){
                var g=ctx.cachedGridForTest(level,new ChunkPos(x,z));var w=g.windowOf(0,0);var cut=new it.unimi.dsi.fastutil.ints.IntArrayList();
                for(int y=65;y<70;y++)for(int lz=0;lz<16;lz++)for(int lx=0;lx<16;lx++)if(w.get(lx+16,y,lz+16).is(Blocks.GLASS))cut.add(((y-w.minY)>>4)<<12|(y&15)<<8|lz<<4|lx);
                int[] positions=cut.toIntArray();Arrays.sort(positions);
                map.put(ChunkPos.asLong(x,z),new Blast(w.stamp(),w.minY,positions,List.of(),List.of()));
            }
        }
        BlockState[] before=new BlockState[48*48*(TOP-FLOOR+1)],after=new BlockState[before.length];
        for(int y=FLOOR;y<=TOP;y++)for(int z=-16;z<32;z++)for(int x=-16;x<32;x++)before[index(x,y,z)]=after[index(x,y,z)]=y==FLOOR?Blocks.STONE.defaultBlockState():shape.at(x,y,z);
        long ns=0,blastNs=0,collapseNs=0,finishNs=0,planBytes=0,removed=0,rubbleEligible=0,trees=0,overheadRubble=0,outside=0;
        for(int cx=-1;cx<=1;cx++)for(int cz=-1;cz<=1;cz++){
            var g=ctx.cachedGridForTest(level,new ChunkPos(cx,cz));long start=System.nanoTime();
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)g.blast(dx,dz);
            long t1=System.nanoTime();var c=Collapse.solve(g);long t2=System.nanoTime();var plan=RuinPlanner.finish(g,c);long end=System.nanoTime();
            blastNs+=t1-start;collapseNs+=t2-t1;finishNs+=end-t2;ns+=end-start;planBytes+=plan.bytes();outside+=plan.outsideCount();removed+=c.changes.size();trees+=c.blast().trees().size();
            for(int i=0;i<256;i++){rubbleEligible+=c.removed[i];if(c.removed[i]>=3&&c.ground[i]>70)overheadRubble++;}
            int[] cells=(int[])field(plan,"cells");BlockState[] palette=(BlockState[])field(plan,"states");
            for(int cell:cells){int x=cx*16+(cell&15),z=cz*16+((cell>>4)&15),y=level.getMinBuildHeight()+((cell>>12)&255)*16+((cell>>8)&15);
                if(y>=FLOOR&&y<=TOP)after[index(x,y,z)]=palette[(cell>>>20)&1023];}
        }
        int initial=0,left=0,added=0,logsBefore=0,logsAfter=0,glassBefore=0,glassAfter=0,high=0;
        for(int i=48*48;i<before.length;i++){
            if(bearing(before[i]))initial++;if(bearing(after[i]))left++;
            if(before[i].isAir()&&!after[i].isAir())added++;
            if(before[i].is(net.minecraft.tags.BlockTags.LOGS))logsBefore++;if(after[i].is(net.minecraft.tags.BlockTags.LOGS))logsAfter++;
            if(before[i].is(Blocks.GLASS))glassBefore++;if(after[i].is(Blocks.GLASS))glassAfter++;
            if(!after[i].isAir())high=Math.max(high,i/(48*48)+FLOOR);
        }
        long graphStart=System.nanoTime();int floating=disconnected(after);long graphNs=System.nanoTime()-graphStart;
        row("case="+name+" rep="+rep+" kt="+kt+" psi="+psi+" inject="+inject+" initial="+initial+" left="+left+" glassBefore="+glassBefore+" glassAfter="+glassAfter+" floating="+floating+" added="+added+" rubbleEligible="+rubbleEligible+" highGroundRubbleColumns="+overheadRubble+" outsideLogs="+outside+" trees="+trees+" logsBefore="+logsBefore+" logsAfter="+logsAfter+" maxY="+high+" planKiB="+f(planBytes/1024.0)+" totalMs="+f(ns/1e6)+" blastMs="+f(blastNs/1e6)+" collapseMs="+f(collapseNs/1e6)+" finishMs="+f(finishNs/1e6)+" graphMs="+f(graphNs/1e6));
        if(rep==2){
            Path path=Path.of("/mnt/data/projects/incoming/mod/run/wt-MT-B2/mod/run/mt-b2/evidence/cpu-main",name+".tsv.gz");
            try(var w=new PrintWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(path)),java.nio.charset.StandardCharsets.UTF_8))){
                w.println("x\ty\tz\tbefore\tafter");
                for(int y=65;y<=TOP;y++)for(int z=-16;z<32;z++)for(int x=-16;x<32;x++){int i=index(x,y,z);if(before[i].isAir()&&after[i].isAir())continue;w.println(x+"\t"+y+"\t"+z+"\t"+net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(before[i].getBlock())+"\t"+net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(after[i].getBlock()));}
            }
        }
    }
    @GameTest(template="pad",timeoutTicks=100,batch="mt_ruin",skyAccess=true)
    public static void measure(GameTestHelper h)throws Exception{
        for(Block b:new Block[]{Blocks.STONE,Blocks.WHITE_CONCRETE,Blocks.IRON_BLOCK,Blocks.GLASS,Blocks.OAK_LOG,Blocks.OAK_LEAVES,Blocks.SMOOTH_STONE_SLAB,Blocks.CHAIN}){
            var p=Blast.props(b.defaultBlockState());row("material="+b+" kind="+p.response().kind()+" threshold="+p.threshold()+" span="+p.span()+" rubble="+p.rubble()+" breakable="+p.breakable());
        }
        for(double kt:new double[]{15})for(double psi:new double[]{.5,1,2,5,10,20}){double h0=Yield.optimalBurstHeight(kt),r=HobBlast.rangeFor(psi,h0,kt),p0=BlastModel.kpa(psi);row("loads kt="+kt+" psi="+psi+" range="+f(r)+" phase="+f(HobBlast.positivePhaseSeconds(r,h0,kt))+" qKpa="+f(BlastModel.dynamicPressureKpa(p0))+" wind="+f(BlastModel.windSpeed(p0)));}
        for(int rep=0;rep<3;rep++){
            run(h.getLevel(),"hall-stone-natural",hall(Blocks.STONE,12),15,2,false,rep);
            run(h.getLevel(),"hall-concrete-natural",hall(Blocks.WHITE_CONCRETE,12),15,2,false,rep);
            run(h.getLevel(),"hall-stone-controlled",hall(Blocks.STONE,12),15,.05,true,rep);
            run(h.getLevel(),"hall-concrete-controlled",hall(Blocks.WHITE_CONCRETE,12),15,.05,true,rep);
            run(h.getLevel(),"tower-concrete-6",tower(Blocks.WHITE_CONCRETE),15,6,false,rep);
            run(h.getLevel(),"tower-concrete-20",tower(Blocks.WHITE_CONCRETE),15,20,false,rep);
            run(h.getLevel(),"tower-stone-20",tower(Blocks.STONE),15,20,false,rep);
            run(h.getLevel(),"tower-log-20",tower(Blocks.OAK_LOG),15,20,false,rep);
            run(h.getLevel(),"column-concrete-6",(x,y,z)->x==8&&z==8&&y<146?Blocks.WHITE_CONCRETE.defaultBlockState():AIR,15,6,false,rep);
            run(h.getLevel(),"bare-log-column",trunks(16,false),15,20,false,rep);
            run(h.getLevel(),"tree-16",trunks(16,true),15,20,false,rep);
            run(h.getLevel(),"tree-30",trunks(30,true),15,20,false,rep);

        }
        h.succeed();
    }
}
