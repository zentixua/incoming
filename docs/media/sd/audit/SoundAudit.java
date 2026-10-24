package ua.zentix.incoming.client.sound;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import ua.zentix.incoming.client.far.Sightline;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.sound.*;
import ua.zentix.incoming.strike.WeaponType;

public class SoundAudit {
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        double bx=-417.5, by=64, bz=-33.8, ex=-416, ey=82, ez=-82;
        double d=Math.sqrt((bx-ex)*(bx-ex)+(by-ey)*(by-ey)+(bz-ez)*(bz-ez));
        BlastSource s=new BlastSource(null,50,0,true,false);
        double pa=BlastMix.pressure(s,d);
        System.out.printf("BLAST d %.3f p %.3f Pa Lpeak %.3f dBZ phase %.4f s output %.3f LUFS layers %s%n",d,pa,20*Math.log10(pa/2e-5),BlastMix.phase(s,d),BlastMix.loudness(pa),BlastMix.at(s,d));
        var motor=WeaponType.MISSILE.spec().airframe().noise().motor();
        for (double age: new double[]{0,6,20,28,34,39,40}) {
            double lw=motor.power(FlightPhase.BOOST,age);
            var path=Propagation.Path.over(30,1.7,Propagation.Weather.NOON);
            double[] bands=new double[8];
            double level=Propagation.level(lw,motor.spectrum(),path,bands);
            double gain=Loudness.gain(level,Propagation.margin(bands,Propagation.Weather.NOON));
            System.out.printf("BOOST age %.1f Lw %.3f LwA %.3f LrA %.3f output %.3f LUFS v %.4f%n",age,lw,lw+motor.spectrum().aWeighting(),level,Loudness.ENGINE_LUFS+20*Math.log10(gain),Loudness.motor(gain));
        }
        System.out.printf("TRANSFER REF %.4f ceiling %.3f ceilingLevel %.4f%n",Loudness.REF,Loudness.CEILING_LUFS,Loudness.CEILING_LEVEL);
        double shotLw=motor.power(FlightPhase.BOOST,0);
        for(var shape:List.of(Spectra.LAUNCH_BOOSTER,Spectra.BOOSTER_ENGINE)){
            double[] bands=new double[8];
            double level=Propagation.level(shotLw,shape,Propagation.Path.over(30,1.7,Propagation.Weather.NOON),bands);
            double gain=Loudness.gain(level,Propagation.margin(bands,Propagation.Weather.NOON));
            System.out.printf("LAUNCH shape %s LwA %.3f LrA %.3f gain %.4f v %.4f output %.3f LUFS stereoCalibration %.3f LUFS%n",shape==Spectra.LAUNCH_BOOSTER?"shot":"loop",shotLw+shape.aWeighting(),level,gain,Loudness.motor(gain),Loudness.ENGINE_LUFS+20*Math.log10(gain),Loudness.ENGINE_LUFS+20*Math.log10(gain)-4);
        }
        Map<Long,short[]> chunks=new HashMap<>();
        try(var in=new DataInputStream(new GZIPInputStream(new FileInputStream(args[0])))) {
            while(true) {
                int x,z;
                try { x=in.readInt(); z=in.readInt(); } catch(EOFException end) {break;}
                short[] h=new short[256]; for(int i=0;i<256;i++)h[i]=in.readShort();
                chunks.put((long)x<<32 | (z&0xffffffffL),h);
            }
        }
        Sightline.Heights heights=(x,z)->{
            var h=chunks.get((long)(x>>4)<<32 | ((z>>4)&0xffffffffL));
            return h==null?Double.NaN:h[(z&15)*16+(x&15)];
        };
        TerrainEcho.Blast blast=new TerrainEcho.Blast(){
            public double arrivalTicks(double r){return r/(343.0/20);}
            public double pressure(double r){return BlastModel.overpressureKpa(r,50,true);}
        };
        for(int radius:new int[]{192,4096}) {
            int[] known={0},unknown={0};
            Sightline.Heights h=(x,z)->{
                double v=Math.hypot(x-ex,z-ez)>radius?Double.NaN:heights.at(x,z);
                if(Double.isNaN(v))unknown[0]++;else known[0]++;
                return v;
            };
            var found=TerrainEcho.trace(h,ex,ey,ez,bx,by,bz,blast);
            try(var csv=new PrintWriter(new File(args[0]).getParent()+"/reflections-"+radius+".csv")){
                csv.println("x,y,z,delay_s,r1_m,r2_m,extra_m,energy_db,early_s,spread_s,air_63_db,air_125_db,air_1000_db,air_2000_db");
                for(var r:found){
                    double r1=r.extraPath()+d-r.distance();
                    csv.printf("%.4f,%.4f,%.4f,%.6f,%.4f,%.4f,%.4f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f%n",r.x(),r.y(),r.z(),r.delay()/20,r1,r.distance(),r.extraPath(),10*Math.log10(r.energy()),r.early()/20,r.spread()/20,Air.alpha(63)*r.extraPath(),Air.alpha(125)*r.extraPath(),Air.alpha(1000)*r.extraPath(),Air.alpha(2000)*r.extraPath());
                }
            }
            System.out.printf("TERRAIN radius %d known %d unknown %d taps %d sum %.3fdB%n",radius,known[0],unknown[0],found.size(),10*Math.log10(found.stream().mapToDouble(TerrainEcho.Reflection::energy).sum()));
            found.stream().sorted(Comparator.comparingDouble(TerrainEcho.Reflection::energy).reversed()).limit(12).forEach(r->
                System.out.printf("TAP t %.3fs xyz %.1f %.1f %.1f r2 %.1f extra %.1f E %.3fdB spread %.3fs%n",r.delay()/20,r.x(),r.y(),r.z(),r.distance(),r.extraPath(),10*Math.log10(r.energy()),r.spread()/20));
            for(int sec=0;sec<26;sec++){
                final int at=sec;
                var rs=found.stream().filter(r->r.delay()/20>=at&&r.delay()/20<at+1).toList();
                if(!rs.isEmpty())System.out.printf("BIN %d n%d E%.3fdB%n",sec,rs.size(),10*Math.log10(rs.stream().mapToDouble(TerrainEcho.Reflection::energy).sum()));
            }
            found.stream().filter(r->r.delay()/20>=3).sorted(Comparator.comparingDouble(TerrainEcho.Reflection::energy).reversed()).limit(6).forEach(r->
                System.out.printf("LATE_TAP t %.3fs xyz %.1f %.1f %.1f r2 %.1f extra %.1f E %.3fdB spread %.3fs%n",r.delay()/20,r.x(),r.y(),r.z(),r.distance(),r.extraPath(),10*Math.log10(r.energy()),r.spread()/20));
            if(radius==4096&&args.length>1){
                for(int v=1;v<=6;v++){
                    float[][] pcm=new float[1][];
                    int rate;
                    try(var in=new FileInputStream(args[1]+"/blast_far_"+v+".ogg")){rate=Envelope.read(in,pcm);}
                    float[] grain=EchoMix.grain(pcm[0],1,rate,BlastEchoes.GRAIN_SECONDS);
                    var mix=EchoMix.render(grain,found);
                    System.out.printf("RENDER variant %d tracks %d t60 %.4f maxEnd %.4f%n",v,mix.tracks().size(),mix.t60(),mix.tracks().stream().mapToDouble(t->t.start()/20+t.seconds()).max().orElse(0));
                    for(int sec=0;sec<12;sec++){
                        double energy=0;
                        for(var t:mix.tracks()){
                            int a=Math.max(0,(int)Math.round((sec-t.start()/20)*EchoMix.RATE)),b=Math.min(t.pcm().length,(int)Math.round((sec+1-t.start()/20)*EchoMix.RATE));
                            for(int i=a;i<b;i++){double val=t.pcm()[i]/32768.0*t.gain()*BlastMix.uncapped(BlastMix.loudness(pa)-BlastMix.FAR_LUFS);energy+=val*val;}
                        }
                        System.out.printf("RENDER_BIN v%d sec%d powerSum %.3f dBFS%n",v,sec,10*Math.log10(energy/EchoMix.RATE+1e-30));
                    }
                }
            }
        }
        for(double r:new double[]{500,1100,2000}) {
            double p1=1000*blast.pressure(r), extra=2*r-d;
            System.out.printf("LATE r %.1f extra %.1f delay %.3f p1 %.3f pRatio %.3fdB divergenceViaImage %.3fdB air63 %.3f air125 %.3f air1k %.3f air2k %.3f%n",r,extra,extra/343,p1,20*Math.log10(p1/pa),20*Math.log10(d/(2*r)),Air.alpha(63)*extra,Air.alpha(125)*extra,Air.alpha(1000)*extra,Air.alpha(2000)*extra);
            double mirror=1000*blast.pressure(2*r)*Math.sqrt(.8);
            System.out.printf("PLANE r %.1f reflectedPeak %.3f Pa (before air absorption), speaker %.3f LUFS%n",r,mirror,BlastMix.loudness(mirror));
        }
    }
}
