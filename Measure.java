import java.lang.reflect.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import ua.zentix.incoming.nuclear.Detonation;
import net.minecraft.world.phys.Vec3;
public final class Measure {
    static Field field(Class<?> c, String name) throws Exception {
        for (; c != null; c = c.getSuperclass()) try { var f=c.getDeclaredField(name); f.setAccessible(true); return f; } catch(NoSuchFieldException e) {}
        throw new NoSuchFieldException(name);
    }
    static Object value(Object o, String name) throws Exception { try { return field(o.getClass(),name).get(o); } catch(NoSuchFieldException e) { if(name.equals("elementData"))return field(o.getClass(),"a").get(o);throw e;} }
    static long graph(Object root) throws Exception {
        var seen=new IdentityHashMap<Object,Boolean>(); var q=new ArrayDeque<Object>();q.add(root);long bytes=0;
        while(!q.isEmpty()) {var o=q.removeFirst();if(seen.put(o,true)!=null)continue; Class<?> c=o.getClass();
            if(c.isEnum() || o instanceof String)continue;
            bytes+=Agent.instrumentation.getObjectSize(o);
            if(c.isArray()) {if(!c.componentType().isPrimitive())for(Object x:(Object[])o)if(x!=null)q.add(x); continue;}
            for(Class<?> k=c;k!=null && k!=Object.class;k=k.getSuperclass())for(Field f:k.getDeclaredFields()) {
                if(Modifier.isStatic(f.getModifiers())||f.getType().isPrimitive())continue;f.setAccessible(true);var x=f.get(o);if(x!=null)q.add(x);
            }
        }return bytes;
    }
    static void describe(Object l) throws Exception {
        long[] order=(long[])value(l,"order"), edge=(long[])value(l,"edge");
        var tiles=(List<?>)value(l,"tiles"); var asked=(Map<?,?>)value(l,"askedBy"); var scan=value(l,"toScan");
        long listCapacity=0, refs=0;for(Object x:asked.values()){listCapacity+=((Object[])value(x,"elementData")).length;refs+=((List<?>)x).size();}
        System.out.printf(Locale.ROOT,"COUNTS order=%d edge=%d tiles=%d tileBacking=%d askedKeys=%d mapN=%d keyBacking=%d valueBacking=%d askedRefs=%d askedListCapacity=%d scanSize=%s scanBacking=%d%n",order.length,edge.length,tiles.size(),((Object[])value(tiles,"elementData")).length,asked.size(),value(asked,"n"),((long[])value(asked,"key")).length,((Object[])value(asked,"value")).length,refs,listCapacity,scan.getClass().getMethod("size").invoke(scan),((long[])value(scan,"a")).length);
        for(String f:List.of("order","edge","tiles","askedBy","toScan"))System.out.printf("GRAPH field=%s bytes=%d%n",f,graph(value(l,f)));
        long old=(order.length+edge.length+(int)scan.getClass().getMethod("size").invoke(scan))*8L;
        System.out.printf("RESULT retainedGraphBytes=%d oldRetainedArrayEstimate=%d missing=%d%n",graph(l),old,graph(l)-old);
    }
    public static void main(String[] args)throws Exception {
        var c=Class.forName("ua.zentix.incoming.nuclear.world.NuclearPrep");var m=c.getDeclaredMethod("layout",Detonation.class);m.setAccessible(true);
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);
        for(double yield:new double[]{1,100,1000}) {
            var d=new Detonation(-1,new Vec3(3104.5,63,560.5),63,yield,true,0,0,0,20000,0,1,false,.25f);
            long before=bean.getThreadAllocatedBytes(Thread.currentThread().threadId()),start=System.nanoTime();
            var l=m.invoke(null,d);long elapsed=System.nanoTime()-start,allocated=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-before;
            System.out.printf(Locale.ROOT,"LAYOUT yieldKt=%.0f allocatedBytes=%d elapsedNanos=%d%n",yield,allocated,elapsed);describe(l);var reservation=c.getDeclaredMethod("layoutReservation",Detonation.class);reservation.setAccessible(true);System.out.println("RESERVATION bytes="+reservation.invoke(null,d));
        }
    }
}
