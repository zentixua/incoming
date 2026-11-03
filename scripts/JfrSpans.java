import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Keeps exact event timestamps for recorded frame hitches; no sampling extrapolation. */
public class JfrSpans {
    record Span(String id, double from, double to) {}
    public static void main(String[] a) throws Exception {
        var spans = new ArrayList<Span>();
        for (String line : Files.readAllLines(Path.of(a[1]))) {
            String[] p = line.split("\t");
            spans.add(new Span(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2])));
        }
        try (var rf = new RecordingFile(Path.of(a[0]));
             var out = new PrintWriter(Files.newBufferedWriter(Path.of(a[2])))) {
            out.println("span\tevent\tstart_epoch_ms\tduration_ms\tthread\tweight\tstack");
            while (rf.hasMoreEvents()) {
                var e = rf.readEvent(); String kind = e.getEventType().getName();
                boolean sample = kind.equals("jdk.ExecutionSample") || kind.equals("jdk.NativeMethodSample");
                boolean alloc = kind.equals("jdk.ObjectAllocationSample");
                if (!sample && !alloc && !Set.of("jdk.GCPhasePause", "jdk.ThreadPark", "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.ThreadSleep", "jdk.FileRead", "jdk.SocketRead", "jdk.Compilation", "jdk.SafepointBegin").contains(kind)) continue;
                double start = e.getStartTime().getEpochSecond()*1000.0 + e.getStartTime().getNano()/1e6;
                double duration = e.getDuration().toNanos()/1e6;
                RecordedThread t = e.getThread(sample ? "sampledThread" : "eventThread");
                String thread = t == null ? "?" : t.getJavaName();
                StringBuilder stack = new StringBuilder();
                if (e.getStackTrace() != null) for (var f : e.getStackTrace().getFrames()) {
                    if (stack.length() > 0) stack.append(';');
                    stack.append(f.getMethod().getType().getName()).append('.').append(f.getMethod().getName());
                }
                long weight = alloc ? e.getLong("weight") : 1;
                for (var span : spans) if (start < span.to && start+duration >= span.from)
                    out.printf(Locale.ROOT, "%s\t%s\t%.6f\t%.6f\t%s\t%d\t%s%n", span.id, kind, start, duration, thread, weight, stack);
            }
        }
    }
}
