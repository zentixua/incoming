package ua.zentix.incoming.client.fx.smoke;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DustComparisonTest {
 @Test void streets() {
  var air=new CloudModel.Air(.01,4,-3,.2,3,.5);
  GroundDust.Relief terrain=(x,z)-> Math.abs(x)%30<8 ? 0:20;
  var a=GroundDust.skirt(150,0,1000,new GroundDust.Ground(GroundDust.Ground.filled(1),GroundDust.Ground.filled(0),GroundDust.Ground.filled(0),GroundDust.Ground.filled(0),terrain),air);
  var b=ReferenceGroundDust.skirt(150,0,1000,new ReferenceGroundDust.Ground(ReferenceGroundDust.Ground.filled(1),ReferenceGroundDust.Ground.filled(0),ReferenceGroundDust.Ground.filled(0),ReferenceGroundDust.Ground.filled(0),terrain::at),air);
  assertEquals(b.life,a.life,0);
  for(double t:new double[]{.1,.5,1,3,10,30,100,1}) {
   var x=new java.util.ArrayList<double[]>(); var y=new java.util.ArrayList<double[]>();
   a.blobs(t,1,(u,v,w,h,d,m)->x.add(new double[]{u,v,w,h,d,m}));
   b.blobs(t,1,(u,v,w,h,d,m)->y.add(new double[]{u,v,w,h,d,m}));
   assertEquals(y.size(),x.size());
   for(int i=0;i<x.size();i++) assertArrayEquals(y.get(i),x.get(i),0,"street at "+t);
  }
 }
 @Test void exact() {
  var air=new CloudModel.Air(.01,3,-2,.2,3,.5);
  for (double kg : new double[]{6,50,150,1000}) for (double hob : new double[]{0,10,40}) {
   var a=new GroundDust(kg,hob,1,air);
   var b=new ReferenceGroundDust(kg,hob,1,air);
   assertEquals(b.life,a.life,0,"life");
   for(double t : new double[]{.02,.1,.5,1,3,10,50,300,1800,3600,1}) {
    assertEquals(b.tau(t),a.tau(t),0,"tau "+t);
    var ba=new SmokeBounds(); var bb=new SmokeBounds();
    a.bounds(t,-100,100,ba); b.bounds(t,-100,100,bb);
    assertEquals(bb.minX,ba.minX,0); assertEquals(bb.maxX,ba.maxX,0);
   }
  }
 }
}
