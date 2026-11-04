package ua.zentix.incoming.nuclear.world;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.registry.ModAttachments;

/** Read-only, bounded diagnostic capture before the original failing assertion. */
public final class CiAstraProbe {
    private CiAstraProbe() {}
    public static void sample(ServerLevel level, BlockPos target, int id, long elapsed) {
        var world = NuclearWorld.get(level);
        long key = new ChunkPos(target).toLong();
        Object queue = world.scars();
        Object job = ((Map<?, ?>) get(queue, "jobs")).get(key);
        RuinContext ctx = world.ruinsIfKept(id);
        Object task = ctx == null ? null : ((Map<?, ?>) get(ctx, "tasks")).get(key);
        Future<?> future = task == null ? null : (Future<?>) get(task, "future");
        Object pool = getStatic(RuinWorkers.class, "pool");
        var chunk = level.getChunkSource().getChunkNow(target.getX() >> 4, target.getZ() >> 4);
        int ready = 0;
        for (int z = -2; z <= 2; z++) for (int x = -2; x <= 2; x++)
            if (level.getChunkSource().getChunkNow((target.getX() >> 4) + x, (target.getZ() >> 4) + z) != null) ready++;
        Incoming.LOG.info("CI_ASTRA_PIPE tick={} game={} target={} state={} scar={} ready25={} craters={} queues={} job={} ctx={} task={} pool={} resident={} results={} detached={} material={}",
                elapsed, level.getGameTime(), target, chunk == null ? "unready" : chunk.getBlockState(target),
                chunk == null ? -1 : chunk.getData(ModAttachments.CHUNK_SCAR), ready, world.craterJobs(),
                describe(queue, "jobs", "byDue", "readySeen", "readyUnseen", "background", "parked"),
                describe(job, "event", "due", "wave", "held", "ready", "waitsNeighbours", "needs", "diagWaits"),
                describe(ctx, "shots", "heights", "tasks", "pendingWindows", "reads", "failed", "shotBytes", "heightBytes", "blastBytes", "appliedBytes"),
                future == null ? "none" : "since=" + get(task, "since") + ",started=" + RuinWorkers.started(future) + ",done=" + future.isDone() + ",cancel=" + future.isCancelled() + ",finished=" + RuinWorkers.finished(future),
                pool instanceof ThreadPoolExecutor e ? "size=" + e.getPoolSize() + ",active=" + e.getActiveCount() + ",queued=" + e.getQueue().size() + ",completed=" + e.getCompletedTaskCount() : pool,
                NuclearPrep.residentMemory(level), RuinResults.bytes(), RuinInputs.detachedBytes(), get(world, "materialReservation"));
    }
    private static String describe(Object object, String... names) {
        if (object == null) return "none";
        var out = new StringBuilder();
        for (String name : names) {
            Object value = get(object, name);
            if (value instanceof Map<?, ?> map) value = map.size();
            else if (value instanceof java.util.Collection<?> c) value = c.size();
            else if (value instanceof int[] a) value = java.util.Arrays.toString(a);
            else if (value instanceof RuinInbox<?>) value = describe(value, "pending", "ready");
            out.append(name).append('=').append(value).append(',');
        }
        return out.toString();
    }
    private static Object get(Object object, String name) {
        try { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
        catch (ReflectiveOperationException e) { return "absent:" + name; }
    }
    private static Object getStatic(Class<?> type, String name) {
        try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(null); }
        catch (ReflectiveOperationException e) { return "absent:" + name; }
    }
}
