package ua.zentix.stand;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import java.util.*;
/** Private observer for the existing stand. Existing Incoming state is read, never set/called to advance work. */
public final class WindowTelemetry {
    private static volatile boolean active;
    private static MinecraftServer server;
    private static WindowTimingView timing;
    private static String phase="outside";
    private static Boolean spareAtPhaseEntry;
    private static Long total,cap;
    private static Boolean lowerPending;
    private static long phaseSequence;
    private static int phaseWindows;
    private static final Deque<Window> stack=new ArrayDeque<>();
    private static final List<Row> rows=new ArrayList<>();
    private record Row(String type,long ns,int serverTick,Map<String,Object> values){}
    private static final class Window {
        final Object clock;
        final WindowClockView view;
        final int tick;
        final long began,used,stepSum,stepCount,firstCount,lateCount;
        final Map<String,Object> values;
        Long accountBegan,accountUsedArg,accountUsedBefore,accountUsedAfter,accountEnter,accountExit;
        Window(Object clock,WindowClockView view,int tick,Map<String,Object> values){
            this.clock=clock;this.view=view;this.tick=tick;this.values=values;
            used=view.stand$used();stepSum=view.stand$stepSum();stepCount=view.stand$stepCount();
            firstCount=view.stand$firstCount();lateCount=view.stand$lateCount();
            // This is the observer HEAD-to-RETURN wall bracket, not WorkClock's own sampled boundary.
            began=System.nanoTime();
        }
    }
    private WindowTelemetry(){}
    public static void activate(){active=Boolean.getBoolean("perfa.windows");}
    public static boolean enabled(){return active;}
    public static void timing(MinecraftServer s,Object value){
        if(!active)return;
        if(server!=s){server=s;timing=null;}
        if(value instanceof WindowTimingView v)timing=v;
    }
    public static void phase(MinecraftServer s,String name,boolean spare){
        if(!active)return;
        if(server!=s){server=s;timing=null;}
        phase=name;spareAtPhaseEntry=spare;total=null;cap=null;lowerPending=null;
        phaseSequence++;phaseWindows=0;
    }
    public static void cap(long common,boolean lower,long impact){
        if(!active)return;
        total=common;lowerPending=lower;cap=impact;
    }
    private static Map<String,Object> context(){
        Map<String,Object> v=new LinkedHashMap<>();
        v.put("schema",1);v.put("phase",phase);v.put("phase_seq",phaseSequence);
        v.put("common_budget_ns",total);v.put("impact_cap_ns",cap);v.put("lower_pending_at_cap",lowerPending);
        // This calls neither WorkClock.spare nor any extra hasTime supplier.
        v.put("spare_at_phase_entry",spareAtPhaseEntry);
        v.put("early_budget_ns",timing==null?null:timing.stand$earlyBudget());
        v.put("early_used_snapshot_ns",timing==null?null:timing.stand$earlyUsed());
        v.put("timing_active",timing==null?null:timing.stand$active());
        return v;
    }
    public static void begin(ServerLevel level,Object clock){
        if(!active)return;
        if(!(clock instanceof WindowClockView v))throw new IllegalStateException("Private window clock mixin missing");
        Map<String,Object> values=context();
        values.put("world",level.dimension().location().toString());
        values.put("clock_id",System.identityHashCode(clock));
        values.put("deadline_enter_ns",v.stand$deadline());values.put("busy_deadline_enter_ns",v.stand$busyDeadline());
        values.put("worked_enter",v.stand$worked());values.put("late_enter",v.stand$late());values.put("counting",v.stand$counting());
        Window w=new Window(clock,v,level.getServer().getTickCount(),values);
        stack.push(w);phaseWindows++;
    }
    public static void accountBefore(Object clock,long began,long usedBefore,long used){
        if(!active||stack.isEmpty())return;
        Window w=stack.peek();if(w.clock!=clock)return;
        w.accountEnter=System.nanoTime();w.accountBegan=began;w.accountUsedArg=usedBefore;w.accountUsedBefore=used;
    }
    public static void accountAfter(Object clock,long used){
        if(!active||stack.isEmpty())return;
        long now=System.nanoTime();Window w=stack.peek();if(w.clock!=clock)return;
        w.accountExit=now;w.accountUsedAfter=used;
    }
    public static void end(Object clock){
        if(!active)return;
        long end=System.nanoTime();
        if(stack.isEmpty()||stack.peek().clock!=clock)throw new IllegalStateException("Private window nesting mismatch");
        Window w=stack.pop();WindowClockView v=w.view;Map<String,Object> p=w.values;
        p.put("elapsed_wall_ns",end-w.began);p.put("observer_begin_ns",w.began);p.put("observer_end_ns",end);
        p.put("used_before_ns",w.used);p.put("used_after_ns",v.stand$used());p.put("charged_delta_ns",v.stand$used()-w.used);
        p.put("record_charged_ns",v.stand$stepSum()-w.stepSum);p.put("record_count",v.stand$stepCount()-w.stepCount);
        p.put("first_record_count",v.stand$firstCount()-w.firstCount);p.put("late_record_count",v.stand$lateCount()-w.lateCount);
        boolean recorded=v.stand$stepCount()!=w.stepCount;
        p.put("last_record_ns",recorded?v.stand$lastStep():null);
        p.put("last_record_began_clock_ns",recorded?v.stand$lastBegan():null);
        p.put("last_record_ended_clock_ns",recorded?v.stand$lastBegan()+v.stand$lastStep():null);
        p.put("last_record_first",recorded?v.stand$lastFirst():null);p.put("last_record_late",recorded?v.stand$lastLate():null);
        p.put("worked_exit",v.stand$worked());p.put("late_exit",v.stand$late());
        p.put("deadline_exit_ns",v.stand$deadline());p.put("busy_deadline_exit_ns",v.stand$busyDeadline());
        p.put("account_window_seen",w.accountBegan!=null);p.put("account_began_ns",w.accountBegan);
        p.put("account_used_argument_ns",w.accountUsedArg);p.put("account_used_before_ns",w.accountUsedBefore);
        p.put("account_used_after_ns",w.accountUsedAfter);p.put("account_enter_ns",w.accountEnter);p.put("account_exit_ns",w.accountExit);
        rows.add(new Row("work-window",end,w.tick,p));
    }
    public static void phaseEnd(MinecraftServer s){
        if(!active)return;
        if(phaseWindows>0){Map<String,Object> v=context();v.put("windows",phaseWindows);rows.add(new Row("work-phase",System.nanoTime(),s.getTickCount(),v));}
        phase="outside";
    }
    static void flush(Out out,long end,int n,int serverTick){
        if(!active||rows.isEmpty())return;
        // Emission is after existing tick wall/CPU capture. No serialization/logging runs in a queue unit.
        out.event("work-link",end,"n",n,"server_tick",serverTick,"schema",1);
        for(Row row:rows){
            List<Object> kv=new ArrayList<>(row.values.size()*2+6);
            kv.add("n");kv.add(row.serverTick==serverTick?n:-1);
            kv.add("server_tick");kv.add(row.serverTick);
            kv.add("tick_link_mismatch");kv.add(row.serverTick!=serverTick);
            row.values.forEach((k,v)->{kv.add(k);kv.add(v);});
            out.event(row.type,row.ns,kv.toArray());
        }
        rows.clear();
    }
}
