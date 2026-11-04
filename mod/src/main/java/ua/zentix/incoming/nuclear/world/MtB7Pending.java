package ua.zentix.incoming.nuclear.world;

import java.util.function.LongSupplier;
import net.minecraft.server.level.ServerLevel;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.registry.ModAttachments;

/** TEST ONLY. One explicitly selected scene/event; retains numbers only, never a world, chunk or job. */
final class MtB7Pending {
    static final int HOLDER_LIMIT = 20_000, PLAYER_LIMIT = 16, EVENT_LIMIT = 1024;
    static final long BUDGET_NS = 1_000_000;
    final int event;
    private long prepared, fresh, seenApplied, appliedMaxLag, preparedDrops, unloaded, failed, clears;
    private long samples, partialSamples, sampledMaxLag;

    MtB7Pending(int event) { this.event = event; }

    static boolean enabled() {
        return Boolean.getBoolean("incoming.mtB7")
                && "natural-fixed-v1".equals(System.getProperty("incoming.mtB7.pendingScene"))
                && Integer.getInteger("incoming.mtB7.pendingEvent", 0) > 0;
    }

    static MtB7Pending configured() {
        return enabled() ? new MtB7Pending(Integer.getInteger("incoming.mtB7.pendingEvent", 0)) : null;
    }

    void applied(int id, long lag, boolean seen, boolean prebuilt) {
        if (id != event) return;
        if (prebuilt) prepared++; else fresh++;
        if (seen) { seenApplied++; appliedMaxLag = Math.max(appliedMaxLag, Math.max(0, lag)); }
    }

    void preparedDropped(int id) { if (id == event) preparedDrops++; }
    void removed(boolean failure) { if (failure) failed++; else unloaded++; }
    void cleared() { clears++; }

    /** Numeric result only. Counts are lower bounds unless complete=true; lag is now minus centre wave due. */
    record Census(boolean complete, String cutoff, int holders, int watchedLoaded, int affected,
                  int scarred, int pending, int overdue, int withoutJob, long maxLag, long observerNs) {}

    Census census(ServerLevel level, ScarQueue queue, Detonation d, long now, LongSupplier nanoTime) {
        long start = nanoTime.getAsLong();
        int holders = 0, loaded = 0, affected = 0, scarred = 0, pending = 0, overdue = 0, missing = 0;
        long maxLag = 0;
        String cutoff = "none";
        if (d == null || d.id() != event) cutoff = "event_missing";
        else if (level.players().size() > PLAYER_LIMIT) cutoff = "players";
        else {
            double radius = d.ruinRadius();
            for (var holder : level.getChunkSource().chunkMap.getChunks()) {
                if (holders >= HOLDER_LIMIT) { cutoff = "holders"; break; }
                // Cooperative bound: no reflective graph walks, block reads or terrain/source collection.
                if ((holders & 31) == 0 && nanoTime.getAsLong() - start >= BUDGET_NS) { cutoff = "time"; break; }
                holders++;
                var pos = holder.getPos();
                if (!ScarQueue.watched(level, pos)) continue;
                var chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
                if (chunk == null) continue;
                loaded++;
                if (NuclearPrep.horizontal(pos, d) > radius) continue;
                affected++;
                // getExistingData does not create the scar attachment on an untouched chunk.
                if (chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) >= event) { scarred++; continue; }
                pending++;
                long lag = now - ScarQueue.due(d, pos);
                if (lag > 0) { overdue++; maxLag = Math.max(maxLag, lag); }
                int queued = queue.mtB7Queued(event, pos.toLong());
                if (queued < 0) { cutoff = "job_events"; break; }
                if (queued == 0) missing++;
            }
        }
        long elapsed = nanoTime.getAsLong() - start;
        // A last small batch or first-use linkage can overrun too: never label it a complete cheap census.
        if (elapsed >= BUDGET_NS && cutoff.equals("none")) cutoff = "time";
        boolean complete = cutoff.equals("none");
        samples++;
        if (!complete) partialSamples++;
        sampledMaxLag = Math.max(sampledMaxLag, maxLag);
        return new Census(complete, cutoff, holders, loaded, affected, scarred, pending, overdue, missing, maxLag, elapsed);
    }

    String row(Census c) {
        return " pending_v1 event="+event+" complete="+c.complete()+" cutoff="+c.cutoff()
                +" holders="+c.holders()+" watched_loaded="+c.watchedLoaded()+" affected="+c.affected()
                +" scarred="+c.scarred()+" pending="+c.pending()+" overdue="+c.overdue()
                +" pending_without_job="+c.withoutJob()+" pending_max_lag_ticks="+c.maxLag()
                +" observer_ns="+c.observerNs()+" prepared_success="+prepared+" fresh_success="+fresh
                +" seen_success="+seenApplied+" seen_success_max_lag_ticks="+appliedMaxLag
                +" prepared_drops="+preparedDrops+" unloaded_pending="+unloaded+" failed_pending="+failed
                +" clears="+clears+" samples="+samples+" partial_samples="+partialSamples
                +" sampled_pending_max_lag_ticks="+sampledMaxLag;
    }
}
