package ua.zentix.incoming.nuclear.world;

import java.util.function.LongSupplier;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.registry.ModAttachments;

/** TEST ONLY. One explicitly selected scene/event; retains numbers only, never a world, chunk or job. */
final class MtB7Pending {
    static final int HOLDER_LIMIT = 20_000, PLAYER_LIMIT = 16, EVENT_LIMIT = 1024;
    static final int CANDIDATE_LIMIT = 20_000;
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
                  int scarred, int pending, int overdue, int withoutJob, long maxLag, long observerNs,
                  String traversal, int candidates) {}

    Census census(ServerLevel level, ScarQueue queue, Detonation d, long now, LongSupplier nanoTime) {
        long start = nanoTime.getAsLong();
        var scan = new Scan(level, queue, d, now, start, nanoTime);
        if (d == null || d.id() != event) scan.cutoff = "event_missing";
        else if (level.players().size() > PLAYER_LIMIT) scan.cutoff = "players";
        else {
            var views = new ChunkTrackingView[level.players().size()];
            for (int i = 0; i < views.length; i++) views[i] = level.players().get(i).getChunkTrackingView();
            if (nativeViews(views) && !ScarQueue.mtB7HasSeenOverride()) {
                scan.traversal = "views";
                visitViews(views, scan::candidate);
            } else {
                // Custom views and the additive test predicate have no trustworthy finite native bounds.
                scan.traversal = "holders";
                for (var holder : level.getChunkSource().chunkMap.getChunks()) {
                    if (!scan.holder(holder.getPos())) break;
                }
            }
        }
        long elapsed = nanoTime.getAsLong() - start;
        // A last small batch or first-use linkage can overrun too: never label it a complete cheap census.
        if (elapsed >= BUDGET_NS && scan.cutoff.equals("none")) scan.cutoff = "time";
        boolean complete = scan.cutoff.equals("none");
        samples++;
        if (!complete) partialSamples++;
        sampledMaxLag = Math.max(sampledMaxLag, scan.maxLag);
        return new Census(complete, scan.cutoff, scan.holders, scan.loaded, scan.affected, scan.scarred,
                scan.pending, scan.overdue, scan.missing, scan.maxLag, elapsed, scan.traversal, scan.candidates);
    }

    static boolean nativeViews(ChunkTrackingView[] views) {
        for (var view : views) if (view != ChunkTrackingView.EMPTY && !(view instanceof ChunkTrackingView.Positioned)) return false;
        return true;
    }

    @FunctionalInterface
    interface Candidate { boolean visit(int x, int z, boolean watched); }

    /** Native forEach bounds and contains(..., true); caller sees every square cell for cooperative bounds. */
    static void visitViews(ChunkTrackingView[] views, Candidate visit) {
        for (int i = 0; i < views.length; i++) {
            if (!(views[i] instanceof ChunkTrackingView.Positioned view)) continue;
            // Match Positioned.forEach bounds; widen loop counters so cooperative stops also cover extreme inputs.
            int minX = view.center().x - view.viewDistance() - 1;
            int maxX = view.center().x + view.viewDistance() + 1;
            int minZ = view.center().z - view.viewDistance() - 1;
            int maxZ = view.center().z + view.viewDistance() + 1;
            for (long x = minX; x <= maxX; x++) {
                for (long z = minZ; z <= maxZ; z++) {
                    boolean watched = view.contains((int) x, (int) z);
                    // Numeric/current-view union only: no visited set, world cache or retained player references.
                    for (int previous = 0; watched && previous < i; previous++)
                        if (views[previous].contains((int) x, (int) z)) watched = false;
                    if (!visit.visit((int) x, (int) z, watched)) return;
                }
            }
        }
    }

    /** Sample-local only; no game objects escape census. */
    private final class Scan {
        final ServerLevel level;
        final ScarQueue queue;
        final Detonation d;
        final long now, start;
        final LongSupplier clock;
        final double radius;
        int holders, candidates, loaded, affected, scarred, pending, overdue, missing;
        long maxLag;
        String cutoff = "none", traversal = "none";

        Scan(ServerLevel level, ScarQueue queue, Detonation d, long now, long start, LongSupplier clock) {
            this.level = level; this.queue = queue; this.d = d; this.now = now; this.start = start; this.clock = clock;
            radius = d == null || d.id() != event || level.players().size() > PLAYER_LIMIT ? 0 : d.ruinRadius();
        }

        boolean next() {
            boolean fallback = traversal.equals("holders");
            if (candidates >= (fallback ? HOLDER_LIMIT : CANDIDATE_LIMIT)) {
                cutoff = fallback ? "holders" : "candidates"; return false;
            }
            if ((candidates & 31) == 0 && clock.getAsLong() - start >= BUDGET_NS) { cutoff = "time"; return false; }
            candidates++;
            return true;
        }

        boolean candidate(int x, int z, boolean watched) {
            if (!next()) return false;
            if (!watched || level.getChunkSource().chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(x, z)) == null) return true;
            holders++;
            return inspectLoaded(new ChunkPos(x, z));
        }

        boolean holder(ChunkPos pos) {
            if (!next()) return false;
            holders++;
            if (!ScarQueue.watched(level, pos)) return true;
            return inspectLoaded(pos);
        }

        boolean inspectLoaded(ChunkPos pos) {
            var chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk == null) return true;
            loaded++;
            if (NuclearPrep.horizontal(pos, d) > radius) return true;
            affected++;
            if (chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) >= event) { scarred++; return true; }
            pending++;
            long lag = now - ScarQueue.due(d, pos);
            if (lag > 0) { overdue++; maxLag = Math.max(maxLag, lag); }
            int queued = queue.mtB7Queued(event, pos.toLong());
            if (queued < 0) { cutoff = "job_events"; return false; }
            if (queued == 0) missing++;
            return true;
        }
    }

    String row(Census c) {
        return " pending_v1 event="+event+" complete="+c.complete()+" cutoff="+c.cutoff()
                +" holders="+c.holders()+" watched_loaded="+c.watchedLoaded()+" affected="+c.affected()
                +" scarred="+c.scarred()+" pending="+c.pending()+" overdue="+c.overdue()
                +" pending_without_job="+c.withoutJob()+" pending_max_lag_ticks="+c.maxLag()
                +" traversal="+c.traversal()+" candidates="+c.candidates()
                +" observer_ns="+c.observerNs()+" prepared_success="+prepared+" fresh_success="+fresh
                +" seen_success="+seenApplied+" seen_success_max_lag_ticks="+appliedMaxLag
                +" prepared_drops="+preparedDrops+" unloaded_pending="+unloaded+" failed_pending="+failed
                +" clears="+clears+" samples="+samples+" partial_samples="+partialSamples
                +" sampled_pending_max_lag_ticks="+sampledMaxLag;
    }
}
