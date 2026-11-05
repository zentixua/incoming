import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

/** Private, single-shot read-only diagnostic. No retransformation, game changes or recurring task. */
public final class OwnershipAgent6 {
    private static final String P = "ua.zentix.incoming.nuclear.world.";
    public static void agentmain(String path, Instrumentation instrumentation) throws Exception {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("Absolute new output path required");
        Path output = Path.of(path);
        if (!output.isAbsolute() || Files.exists(output)) throw new IllegalArgumentException("Output must be new and absolute");
        Map<String, Class<?>> loaded = new HashMap<>();
        for (Class<?> type : instrumentation.getAllLoadedClasses())
            if (type.getName().startsWith(P) || type.getName().equals("ua.zentix.incoming.registry.ModAttachments")
                    || type.getName().equals("net.neoforged.neoforge.server.ServerLifecycleHooks")) loaded.put(type.getName(), type);
        Class<?> hooks = loaded.get("net.neoforged.neoforge.server.ServerLifecycleHooks");
        if (hooks == null) throw new IllegalStateException("ServerLifecycleHooks not loaded");
        Object server = call(hooks, "getCurrentServer");
        if (server == null) throw new IllegalStateException("No live server");
        CompletableFuture<String> result = new CompletableFuture<>();
        call(server, "execute", (Runnable) () -> {
            try { result.complete(snapshot(server, loaded)); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        // Write outside the server thread. This thread owns no server/world reference.
        Thread writer = new Thread(() -> {
            String value;
            try { value = result.get(15, TimeUnit.SECONDS); }
            catch (Exception failure) { var trace = new java.io.StringWriter(); failure.printStackTrace(new java.io.PrintWriter(trace)); value = "SNAPSHOT_FAILED " + trace; }
            try { Files.writeString(output, value, StandardOpenOption.CREATE_NEW); }
            catch (Exception failure) { System.err.println("OwnershipAgent output failed: " + failure); }
        }, "Incoming ownership diagnostic writer");
        writer.setDaemon(true); writer.start();
    }
    private static String snapshot(Object server, Map<String, Class<?>> loaded) throws Exception {
        long begin = System.nanoTime();
        StringBuilder out = new StringBuilder(24000);
        Set<Object> inspectedPlans = Collections.newSetFromMap(new IdentityHashMap<>());
        int[] readBudget = {64};
        out.append("wall_ms=").append(System.currentTimeMillis()).append(" mono_ns=").append(begin)
                .append(" thread=").append(Thread.currentThread().getName()).append('\n');
        for (String type : List.of("RuinWorkers", "RuinResults", "RuinInputs")) dump(out, type, loaded.get(P + type));
        Object pool = read(loaded.get(P + "RuinWorkers"), "pool");
        if (pool instanceof ThreadPoolExecutor executor) {
            out.append("poolMetrics size=").append(executor.getPoolSize()).append(" active=").append(executor.getActiveCount())
                    .append(" queued=").append(executor.getQueue().size()).append(" completed=").append(executor.getCompletedTaskCount())
                    .append(" shutdown=").append(executor.isShutdown()).append('\n');
            Object task = executor.getQueue().peek(); dump(out, "pool.head", task);
        }
        Object holder = read(loaded.get("ua.zentix.incoming.registry.ModAttachments"), "NUCLEAR_WORLD");
        Object attachment = ((Supplier<?>) holder).get();
        int dimension = 0;
        for (Object level : (Iterable<?>) call(server, "getAllLevels")) {
            if (dimension >= 16) { out.append("DIMENSION_LIMIT\n"); break; }
            String name = "dimension[" + dimension++ + "]";
            out.append(name).append(" gameTime=").append(call(level, "getGameTime"))
                    .append(" key=").append(call(level, "dimension")).append('\n');
            if (!Boolean.TRUE.equals(call(level, "hasData", attachment))) { out.append("no existing nuclear attachment\n"); continue; }
            // hasData already established ownership: getData cannot create a new attachment here.
            Object world = call(level, "getData", attachment);
            dump(out, name, world);
            for (String child : List.of("matter", "craterMatter", "ruinProgress", "ledgers", "settle", "scars", "prep")) {
                Object owner = read(world, child); dump(out, name + "." + child, owner);
                for (String sub : List.of("moving", "supportSources", "search", "palette", "order"))
                    dump(out, name + "." + child + "." + sub, optional(owner, sub));
            }
            supportTables(out, name + ".supportTables", read(world, "matter"));
            queueHeads(out, name + ".queueHeads", level, world);
            watchedJobs(out, name, level, world);
            Object stats = read(read(world, "scars"), "preparedStats");
            if (stats instanceof Map<?, ?> map) for (var entry : map.entrySet())
                out.append(name).append(" preparedStats event=").append(entry.getKey()).append(" values=")
                    .append(Arrays.toString((long[]) entry.getValue())).append('\n');
            dump(out, name + ".prepClock", read(read(world, "prep"), "clock"));
            Object clockAttachment = ((Supplier<?>) read(loaded.get("ua.zentix.incoming.registry.ModAttachments"), "NUCLEAR_CLOCK")).get();
            if (Boolean.TRUE.equals(call(level, "hasData", clockAttachment)))
                dump(out, name + ".sharedClock", call(level, "getData", clockAttachment));
            Object ruins = read(world, "ruins");
            if (ruins instanceof Map<?, ?> contexts) {
                int index = 0;
                for (Object ctx : contexts.values()) {
                    if (index == 16) { out.append("CONTEXT_LIMIT\n"); break; }
                    context(out, name + ".context[" + index++ + "]", ctx);
                    classify(out, name + ".contextOwnership", ctx, read(world, "scars"));
                    planReads(out, name + ".contextReads", level, world, optional(ctx, "restoredPlans"), inspectedPlans, readBudget);
                }
            }
            Object prep = read(world, "prep"); Object preps = read(prep, "preps");
            int index = 0;
            for (Object request : (List<?>) preps) {
                if (index == 8) { out.append("PREP_LIMIT\n"); break; }
                String label = name + ".prep[" + index++ + "]"; dump(out, label, request);
                for (String sub : List.of("plans", "reading", "required", "lods")) dump(out, label + "." + sub, optional(request, sub));
                for (String sub : List.of("reads", "archived", "terrainReads")) inbox(out, label + "." + sub, optional(request, sub));
                Object ctx = read(request, "ruins"); context(out, label + ".ruins", ctx);
                planReads(out, label + ".demandReads", level, world, optional(request, "plans"), inspectedPlans, readBudget);
                Object archiveInbox = read(request, "archived");
                synchronized (archiveInbox) {
                    Object head = ((Deque<?>) read(archiveInbox, "ready")).peekFirst();
                    if (head != null && ctx != null) {
                        Method bound = Arrays.stream(ctx.getClass().getDeclaredMethods())
                            .filter(m -> m.getName().equals("publicationBytes") && m.getParameterCount() == 2).findFirst().orElseThrow();
                        bound.setAccessible(true);
                        out.append(label).append(" archivedHeadKnownPublication=")
                            .append(bound.invoke(ctx, read(head, "current"), read(head, "data")))
                            .append(" slotReservation=").append(read(archiveInbox, "reservation")).append('\n');
                    }
                }
                Object order = read(request, "order"); int next = ((Number) read(request, "nextFar")).intValue();
                if (ctx != null && order instanceof long[] keys && next >= 0 && next < keys.length) {
                    long key = keys[next]; int cx = (int) key, cz = (int) (key >>> 32);
                    out.append(label).append(" frontier key=").append(key).append(" x=").append(cx).append(" z=").append(cz).append('\n');
                    Map<?, ?> shots = (Map<?, ?>) read(ctx, "shots");
                    int present = 0;
                    for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
                        long k = ((long) (cz + dz) << 32) | ((cx + dx) & 0xffffffffL);
                        Object shot = shots.get(k); if (shot != null) present++;
                        out.append("frontierShot dx=").append(dx).append(" dz=").append(dz).append(" key=").append(k)
                                .append(" present=").append(shot != null).append('\n');
                        if (dx == 0 && dz == 0) dump(out, label + ".centreShot", shot);
                    }
                    out.append("frontierStoredShots=").append(present).append("/25 (presence only; freshness not evaluated)\n");
                }
            }
        }
        return out.append("snapshot_nanos=").append(System.nanoTime() - begin).append('\n').toString();
    }
    private static void context(StringBuilder out, String label, Object ctx) throws Exception {
        dump(out, label, ctx); if (ctx == null) return;
        for (String sub : List.of("inputs", "fragmentCache", "archive", "restoredPlans")) dump(out, label + "." + sub, optional(ctx, sub));
        inbox(out, label + ".reads", optional(ctx, "reads"));
        Object owners = read(ctx, "resultOwners");
        if (owners instanceof Map<?, ?> map) {
            int count = 0;
            for (Object lease : map.values()) { if (count == 8) break; dump(out, label + ".result[" + count++ + "]", lease); }
        }
    }

    /** Bounded pure map/field inspection; never calls the cache's state-changing eviction predicate. */
    private static void classify(StringBuilder out, String label, Object ctx, Object scars) throws Exception {
        Map<?,?> shots = (Map<?,?>)read(ctx,"shots");
        Set<?> pending = (Set<?>)read(ctx,"pendingWindows");
        Map<?,?> tasks = (Map<?,?>)read(ctx,"tasks");
        Map<?,?> jobs = (Map<?,?>)read(scars,"jobs");
        Object archive = read(ctx,"archive");
        Map<?,?> saved = archive == null ? Map.of() : (Map<?,?>)read(archive,"saved");
        Map<?,?> writes = archive == null ? Map.of() : (Map<?,?>)read(archive,"writes");
        long[][] bytes = new long[4][5]; int[][] counts = new int[4][5]; int visited=0;
        // First axis: none, pending, task, both. Second: saved exact, completed exact, pending write, unsaved, failed write.
        for(var e: shots.entrySet()) {
            if(visited++ >= 8192) { out.append("SHOT_CLASSIFICATION_LIMIT\n");break; }
            long key=((Number)e.getKey()).longValue(); Object shot=e.getValue();
            long serial=((Number)read(shot,"serial")).longValue(); long size=((Number)read(shot,"bytes")).longValue();
            int x=(int)key,z=(int)(key>>>32),pins=0;
            for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++) {
                long centre=((long)(z+dz)<<32)|((x+dx)&0xffffffffL);
                if(pending.contains(centre))pins|=1;
                if(tasks.containsKey(centre))pins|=2;
            }
            Object savedSerial=saved.get(key),write=writes.get(key); int receipt=3;
            if(archive==null || savedSerial instanceof Number n && n.longValue()==serial)receipt=0;
            else if(write!=null) {
                CompletableFuture<?> done=(CompletableFuture<?>)read(write,"done");
                if(!done.isDone())receipt=2;
                else if(done.isCompletedExceptionally())receipt=4;
                else if(((Number)read(write,"serial")).longValue()==serial)receipt=1;
            }
            counts[pins][receipt]++;bytes[pins][receipt]+=size;
        }
        for(int pin=0;pin<4;pin++)for(int receipt=0;receipt<5;receipt++)
            out.append(label).append(" pinCategory=").append(pin).append(" receiptCategory=").append(receipt)
                    .append(" shots=").append(counts[pin][receipt]).append(" bytes=").append(bytes[pin][receipt]).append('\n');
        out.append("pinCategory 0=none 1=pending 2=task 3=both; receiptCategory 0=exactSaved(orNoArchive) 1=exactCompletedWrite 2=pendingWrite 3=unsavedOrDifferentSerial 4=failedWrite\n");
        int[] histogram=new int[26];int pendingVisited=0,hasJob=0,hasTask=0,samples=0;
        for(Object item:pending) {
            if(pendingVisited++>=2048){out.append("PENDING_CLASSIFICATION_LIMIT\n");break;}
            long key=((Number)item).longValue();int x=(int)key,z=(int)(key>>>32),stored=0;
            for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++)
                if(shots.containsKey(((long)(z+dz)<<32)|((x+dx)&0xffffffffL)))stored++;
            histogram[stored]++;boolean job=jobs.containsKey(key),task=tasks.containsKey(key);
            if(job)hasJob++;if(task)hasTask++;
            if(samples++<16){out.append(label).append(" pending key=").append(key).append(" x=").append(x).append(" z=").append(z)
                    .append(" stored=").append(stored).append(" job=").append(job).append(" task=").append(task).append('\n');
                if(job)dump(out,label+".pendingJob",jobs.get(key));}
        }
        out.append(label).append(" pendingWindowStoredHistogram=").append(Arrays.toString(histogram))
                .append(" hasJob=").append(hasJob).append(" hasTask=").append(hasTask)
                .append(" (presence only; absent/loaded/freshness not evaluated)\n");
        int taskCount=0;for(var e:tasks.entrySet()){
            if(taskCount++>=16)break;
            out.append(label).append(" taskKey=").append(e.getKey()).append(" hasJob=").append(jobs.containsKey(e.getKey())).append('\n');
            dump(out,label+".task",e.getValue());
            if(jobs.containsKey(e.getKey()))dump(out,label+".taskJob",jobs.get(e.getKey()));
        }
    }

    // Call once per existing dimension: queueHeads(out, name + ".queueHeads", level, world).
    // Passive fields/maps only; no RetainedPlans.get, current/apply/reserve or admission getters.
    private static void queueHeads(StringBuilder out, String label, Object level, Object world) throws Exception {
        Object scars = read(world, "scars");
        Map<?, ?> jobs = (Map<?, ?>) read(scars, "jobs");
        Map<?, ?> prepared = (Map<?, ?>) read(scars, "prepared");
        Map<?, ?> parked = (Map<?, ?>) read(scars, "parked");
        Map<?, ?> contexts = (Map<?, ?>) read(world, "ruins");
        Object chunks = call(level, "getChunkSource");
        List<Object> selected = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String source : List.of("readySeen", "readyUnseen")) {
            int scanned = 0, stale = 0, selectedHere = 0, limit = source.equals("readySeen") ? 8 : 4;
            for (Object job : (Collection<?>) read(scars, source)) {
                if (scanned++ >= 512 || selectedHere >= limit) break;
                long key = ((Number) read(job, "chunk")).longValue();
                if (jobs.get(key) != job) { stale++; continue; }
                selected.add(job); names.add(source + "[" + selectedHere++ + "]");
            }
            out.append(label).append(' ').append(source).append(" scanned=").append(scanned)
                    .append(" stale=").append(stale).append(" selected=").append(selectedHere).append('\n');
        }
        Object due = ((Queue<?>) read(scars, "byDue")).peek();
        if (due != null) { selected.add(due); names.add("byDue.head"); }
        for (int i = 0; i < selected.size(); i++) {
            Object job = selected.get(i); String name = label + '.' + names.get(i);
            long key = ((Number) read(job, "chunk")).longValue(); int x = (int) key, z = (int) (key >>> 32);
            List<?> events = (List<?>) read(job, "events"); int event = ((Number) read(job, "event")).intValue();
            Object d = event >= 0 && event < events.size() ? events.get(event) : null;
            Object id = d == null ? null : read(d, "id");
            out.append(name).append(" key=").append(key).append(" x=").append(x).append(" z=").append(z)
                    .append(" liveJob=").append(jobs.get(key) == job).append(" actualEvent=").append(id).append('\n');
            dump(out, name + ".job", job);
            int loaded = 0;
            for (int dz = -2, bit = 0; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++, bit++)
                if (call(chunks, "getChunkNow", x + dx, z + dz) != null) loaded |= 1 << bit;
            out.append(name).append(" loadedMask=").append(Integer.toHexString(loaded))
                    .append(" (bit=(dz+2)*5+dx+2; getChunkNow only)\n");
            Object ctx = contexts.get(id);
            Object task = ctx == null ? null : ((Map<?, ?>) read(ctx, "tasks")).get(key);
            dump(out, name + ".task", task);
            Object held = parked.get(key); dump(out, name + ".parked", held);
            if (held != null) dump(out, name + ".parkedPlan", read(held, "plan"));
            Map<?, ?> plans = (Map<?, ?>) prepared.get(id);
            if (plans == null && ctx != null) plans = (Map<?, ?>) read(ctx, "restoredPlans");
            if (plans != null) {
                // entry.getValue reads the backing slot; it does not dispatch the demand-loading get override.
                int scanned = 0; boolean found = false;
                for (var entry : plans.entrySet()) {
                    if (scanned++ >= 8192) break;
                    if (((Number) entry.getKey()).longValue() != key) continue;
                    found = true; dump(out, name + ".preparedPlan", entry.getValue()); break;
                }
                out.append(name).append(" preparedKeyFound=").append(found).append(" scanCount=").append(scanned)
                        .append(" completeAbsenceCheck=").append(found || scanned <= 8192).append('\n');
                Object reads = optional(plans, "reads");
                if (reads instanceof Map<?, ?> map) dump(out, name + ".planRead", map.get(key));
                Object demanded = optional(plans, "demanded");
                out.append(name).append(" planDemanded=").append(demanded instanceof Set<?> set && set.contains(key)).append('\n');
            } else out.append(name).append(" noPreparedOwner\n");
        }
    }

    private static void watchedJobs(StringBuilder out, String name, Object level, Object world) throws Exception {
        Object scars = read(world, "scars");
        Map<?, ?> jobs = (Map<?, ?>) read(scars, "jobs");
        Object chunks = call(level, "getChunkSource");
        long now = ((Number) call(level, "getGameTime")).longValue();
        Method watched = Arrays.stream(scars.getClass().getDeclaredMethods()).filter(m -> m.getName().equals("watched")).findFirst().orElseThrow();
        watched.setAccessible(true);
        Constructor<?> ctor = watched.getParameterTypes()[1].getConstructor(long.class);
        out.append(name).append(" testSeenOverride=").append(read(scars.getClass(), "seenForTest")).append('\n');
        int players=0;
        for(Object player : (Iterable<?>) call(level,"players")) {
            Object view=call(player,"getChunkTrackingView");
            out.append(name).append(" player[").append(players++).append("] x=").append(call(player,"getX")).append(" z=").append(call(player,"getZ")).append('\n');
            dump(out,name+".playerView",view);
        }
        int scanned=0,seen=0,overdue=0,loaded=0,shown=0; long maxLag=0;
        for(Object job: jobs.values()) {
            if(scanned++>=20000)break;
            long key=((Number)read(job,"chunk")).longValue();
            if(!Boolean.TRUE.equals(watched.invoke(null,level,ctor.newInstance(key))))continue;
            seen++;
            long wave=((Number)read(job,"wave")).longValue();
            long lag=now-wave;if(lag>0){overdue++;maxLag=Math.max(maxLag,lag);}
            boolean ready=call(chunks,"getChunkNow",(int)key,(int)(key>>>32))!=null;
            if(ready)loaded++;
            if(shown++<16){
                out.append(name).append(" watchedQueued x=").append((int)key).append(" z=").append((int)(key>>>32)).append(" lag=").append(lag).append(" loaded=").append(ready).append('\n');
                dump(out,name+".watchedJob",job);
                dump(out,name+".watchedParked",((Map<?,?>)read(scars,"parked")).get(key));
            }
        }
        out.append(name).append(" watchedSummary players=").append(players).append(" scanned=").append(scanned).append(" complete=").append(scanned<=20000).append(" jobs=").append(jobs.size()).append(" watched=").append(seen).append(" loaded=").append(loaded).append(" overdue=").append(overdue).append(" maxLag=").append(maxLag).append('\n');
    }

    /** Actual array capacity and live cardinality only; no retainedBytes/reservation/cache getters. */
    private static void supportTables(StringBuilder out, String label, Object matter) throws Exception {
        if (matter == null) return;
        for (String name : List.of("supports", "uncertain", "originals")) {
            Object map = read(matter, name);
            if (!(map instanceof Map<?, ?>)) {
                dump(out, label + "." + name + ".journal", map);
                map = read(map, "pages");
            }
            out.append(label).append('.').append(name).append(" type=").append(map.getClass().getName());
            for (String field : List.of("size", "n", "minN", "maxFill", "f", "key", "value", "link")) {
                Object value = optional(map, field);
                out.append(' ').append(field).append('=').append(summary(value));
            }
            out.append('\n');
        }
    }

    /** At most64 read slots. No plan-future payload access; native FULL lookups may warm vanilla caches. */
    private static void planReads(StringBuilder out, String label, Object level, Object world,
                                  Object plans, Set<Object> inspected, int[] budget) throws Exception {
        if (plans == null || !inspected.add(plans)) return;
        Object slots = optional(plans, "reads");
        if (!(slots instanceof Map<?, ?> reads)) return;
        Object demands = optional(plans, "demanded");
        Object scars = read(world, "scars");
        Map<?, ?> jobs = (Map<?, ?>) read(scars, "jobs");
        Method watched = Arrays.stream(scars.getClass().getDeclaredMethods())
                .filter(m -> m.getName().equals("watched")).findFirst().orElseThrow();
        watched.setAccessible(true);
        Constructor<?> chunkPos = watched.getParameterTypes()[1].getConstructor(long.class);
        Object chunks = call(level, "getChunkSource");
        out.append(label).append(" map_id=").append(Integer.toHexString(System.identityHashCode(plans)))
                .append(" reads=").append(reads.size()).append(" demanded=").append(summary(demands)).append('\n');
        int visited = 0;
        for (var entry : reads.entrySet()) {
            if (budget[0] <= 0) { out.append(label).append(" PLAN_READ_LIMIT\n"); break; }
            budget[0]--; visited++;
            long key = ((Number) entry.getKey()).longValue();
            Object future = entry.getValue();
            Object job = jobs.get(key);
            boolean ready = call(chunks, "getChunkNow", (int) key, (int) (key >>> 32)) != null;
            out.append(label).append(" x=").append((int) key).append(" z=").append((int) (key >>> 32))
                    .append(" state=").append(summary(future))
                    .append(" exceptional=").append(future instanceof CompletableFuture<?> f ? f.isCompletedExceptionally() : "unknown")
                    .append(" demanded=").append(demands instanceof Set<?> set && set.contains(key))
                    .append(" loaded=").append(ready)
                    .append(" watched=").append(watched.invoke(null, level, chunkPos.newInstance(key)))
                    .append(" job=").append(summary(job)).append('\n');
            if (job != null) dump(out, label + ".consumer", job);
        }
        out.append(label).append(" visited=").append(visited).append(" complete=").append(visited == reads.size()).append('\n');
    }

    private static void inbox(StringBuilder out, String label, Object box) throws Exception {
        if (box == null) return;
        synchronized (box) {
            dump(out, label, box);
            Object ready = read(box, "ready");
            if (ready instanceof Deque<?> queue) {
                Object head = queue.peekFirst(); dump(out, label + ".head", head);
                for (String sub : List.of("current", "data", "read")) dump(out, label + ".head." + sub, optional(head, sub));
            }
        }
    }
    private static void dump(StringBuilder out, String label, Object target) throws Exception {
        out.append(label).append('='); if (target == null) { out.append("null\n"); return; }
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        out.append(type.getName()).append('@').append(Integer.toHexString(System.identityHashCode(target))).append(' ');
        int count = 0;
        for (Class<?> c = type; c != null && c.getName().startsWith("ua.zentix.incoming"); c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers()) != (target instanceof Class<?>)) continue;
                if (++count > 128) { out.append("FIELD_LIMIT "); break; }
                field.setAccessible(true); Object value = field.get(target instanceof Class<?> ? null : target);
                out.append(field.getName()).append(':').append(summary(value)).append(' ');
            }
        }
        out.append('\n');
    }
    private static String summary(Object value) {
        if (value == null) return "null";
        if (value instanceof AtomicLong n) return Long.toString(n.get());
        if (value instanceof AtomicInteger n) return Integer.toString(n.get());
        if (value instanceof AtomicIntegerArray a) { int[] copy = new int[Math.min(16,a.length())]; for(int i=0;i<copy.length;i++)copy[i]=a.get(i); return Arrays.toString(copy); }
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) return String.valueOf(value);
        if (value instanceof String s) return s.substring(0, Math.min(120,s.length()));
        if (value.getClass().isArray()) return "length=" + Array.getLength(value);
        if (value instanceof Map<?, ?> m) return "size=" + m.size();
        if (value instanceof ConcurrentLinkedQueue<?>) return "concurrentQueue(notTraversed)";
        if (value instanceof Collection<?> c) return "size=" + c.size();
        if (value instanceof Future<?> f) return "future(done=" + f.isDone() + ",cancelled=" + f.isCancelled() + ")";
        return value.getClass().getSimpleName() + '@' + Integer.toHexString(System.identityHashCode(value));
    }
    private static Object optional(Object target, String name) throws Exception {
        if (target == null) return null;
        try { return read(target, name); } catch (NoSuchFieldException absent) { return null; }
    }
    private static Object read(Object target, String name) throws Exception {
        if (target == null) throw new IllegalStateException("Missing owner for field " + name);
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(target instanceof Class<?> ? null : target); }
            catch (NoSuchFieldException absent) { /* Continue through own superclass. */ }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
    private static Class<?> boxed(Class<?> t) {
        if(t==int.class)return Integer.class; if(t==long.class)return Long.class;
        if(t==boolean.class)return Boolean.class; if(t==double.class)return Double.class;
        if(t==float.class)return Float.class; if(t==short.class)return Short.class;
        if(t==byte.class)return Byte.class; if(t==char.class)return Character.class; return t;
    }
    private static Object call(Object target, String name, Object... args) throws Exception {
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        for (Method m : type.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            Class<?>[] parameters = m.getParameterTypes(); boolean matches = true;
            for (int i=0;i<args.length;i++) if (args[i] != null && !boxed(parameters[i]).isInstance(args[i])) matches=false;
            if (matches) return m.invoke(target instanceof Class<?> ? null : target, args);
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
}
