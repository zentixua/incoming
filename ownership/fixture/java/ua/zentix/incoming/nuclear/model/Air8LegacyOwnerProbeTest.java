package ua.zentix.incoming.nuclear.model;
import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
/** Original 452 algorithm, with only class renaming and controllable worker dispatch. */
class Air8LegacyOwnerProbeTest {
 @Test void originalCacheReadinessWithCompletionsBetweenFrames() throws Exception {
  for(String field:new String[]{"CACHE","PENDING","FAILED"}) {var f=Air8LegacyDust.class.getDeclaredField(field);f.setAccessible(true);Object v=f.get(null);if(v instanceof Map<?,?> m)m.clear();else ((Set<?>)v).clear();}
  var work=new ArrayDeque<Runnable>();Air8LegacyDust.WORKER=work::add;var trace=new StringBuilder();
  try {
   for(int frame=0;frame<12;frame++) {
    for(int i=0;i<24;i++)Air8LegacyDust.ready(15,592+i,false,20000);
    var selected=Air8LegacyDust.ready(15,615,false,20000);trace.append(selected==null?'0':'1');
    while(!work.isEmpty())work.remove().run();
   }
   System.out.println("STEM_ORIGINAL_24 "+trace);
   assertEquals("010101010101",trace.toString());
  } finally {while(!work.isEmpty())work.remove().run();Air8LegacyDust.WORKER=java.util.concurrent.ForkJoinPool.commonPool();}
 }
}
