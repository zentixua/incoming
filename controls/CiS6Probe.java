package ua.zentix.incoming.nuclear.world;
public final class CiS6Probe {
    private CiS6Probe() {}
    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        var f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    public static void dump(net.minecraft.server.level.ServerLevel level, int id, net.minecraft.world.level.ChunkPos pos, int way) {
        try {
            var ctx = NuclearWorld.get(level).ruinsIfKept(id);
            var assembly = field(ctx, "assembly");
            var holder = (RuinContext) field(assembly, "owner");
            ua.zentix.incoming.Incoming.LOG.info("CIS6 assembly detonation={} used={} pending={} holderTasks={} holderReads={}", holder == null ? null : holder.d.id(), holder == null ? null : holder.used, holder == null ? null : field(holder, "pendingWindows"), holder == null ? null : holder.tasks(), holder == null ? null : holder.pendingWindowReads());
            ua.zentix.incoming.Incoming.LOG.info("CIS6 flash id={} pos={} way={} tasks={} inFlight={} capacity={} assemblyOwner={} own={} centre={} priority={} reads={} resident={} result={}", id, pos, way, ctx.tasks(), RuinWorkers.inFlight(), RuinWorkers.capacity(), field(assembly, "owner"), ctx, field(assembly, "centre"), field(assembly, "priority"), ctx.pendingWindowReads(), NuclearPrep.residentMemory(level), RuinResults.bytes());
        } catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
    }
    public static Object capture(net.minecraft.server.level.ServerLevel level) {
        try {
            var queue = ua.zentix.incoming.strike.StrikeWorld.get(level).impacts();
            for (String name : new String[]{"heads", "tails"}) for (Object entry : (Iterable<?>) field(queue, name)) {
                Object job = field(entry, "job");
                if (job.getClass().getSimpleName().equals("StagedExplosion")) return job;
            }
            return null;
        } catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
    }
    public static void body(net.minecraft.server.level.ServerLevel level, net.minecraft.world.entity.LivingEntity body, Object job) {
        if (job == null) return;
        try {
            var method = job.getClass().getDeclaredMethod("wave"); method.setAccessible(true);
            var wave = (BlastBody.Wave) method.invoke(job);
            var before = (BlastBody.Before) ((java.util.Map<?, ?>) field(job, "bodyBefore")).get(body);
            ua.zentix.incoming.Incoming.LOG.info("CIS6 actual wave centre={} breach={} placement={} p={} wind={} reaches={} wound={} before={}", field(job,"centre"), field(job,"breach"), field(job,"placement"), wave.overpressureKpa(body.position()), wave.wind(level,body), wave.reaches(level,body), BlastBody.wound(level,wave,body,before), before);
        } catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
    }
}
