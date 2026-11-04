import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.util.*;
public class JfrSummary {
 public static void main(String[] args)throws Exception {
  Map<String,Long> groups=new TreeMap<>();Map<String,Map<String,Long>> methods=new TreeMap<>();long total=0;
  try(var file=new RecordingFile(Path.of(args[0]))){while(file.hasMoreEvents()){
   var event=file.readEvent();if(!event.getEventType().getName().equals("jdk.ExecutionSample")||event.getStackTrace()==null)continue;total++;
   String group="other";var frames=event.getStackTrace().getFrames();
   for(var frame:frames){String c=frame.getMethod().getType().getName();if(c.endsWith(".ReferenceCollapse")){group="reference";break;}if(c.endsWith(".Collapse")){group="candidate";break;}}
   if(group.equals("other"))for(var frame:frames){String c=frame.getMethod().getType().getName();if(c.endsWith(".Blast")){group="blast";break;}if(c.endsWith(".RuinPlanner")||c.contains(".RuinPlanner$")){group="finish";break;}if(c.endsWith(".DiskShots")){group="parse";break;}}
   groups.merge(group,1L,Long::sum);
   if(group.equals("candidate")||group.equals("reference")){
    String method=frames.get(0).getMethod().getType().getName()+"."+frames.get(0).getMethod().getName();
    methods.computeIfAbsent(group,k->new HashMap<>()).merge(method,1L,Long::sum);
   }
  }}
  System.out.println("total="+total+" groups="+groups);
  for(var item:methods.entrySet()){System.out.println(item.getKey());item.getValue().entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(20).forEach(e->System.out.println(e.getValue()+" "+e.getKey()));}
 }
}
