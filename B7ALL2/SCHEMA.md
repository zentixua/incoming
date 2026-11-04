# B7ALL2 current-view census refinement (diagnostic only)

Base: fc19f16d193905b39a27d1a899fde0a359a72b0a, NATURAL/codex/mt-natural-trial, draft586. Never merge into main or ship this instrumentation.

The pending_v1 row retains its event/dimension/server-session/game-time/source-progress/drop/history fields and existing meanings. Census completeness is distinct from strike readiness. Partial or missing coverage is UNKNOWN; a positive partial overdue count remains a failure witness. A zero/empty cohort does not establish readiness.

Two additive fields identify enumeration:

- traversal=views: every square cell in native ChunkTrackingView.Positioned.forEach bounds is visited, native contains(x,z,true) determines membership, earlier current views exclude overlap. EMPTY contributes no members. Only positions present in visibleChunkMap and returning getChunkNow are counted as watched_loaded. All mutable membership, load readiness, existing scar marks and queue evidence are read afresh each sample.
- traversal=holders: bounded visibleChunkMap fallback for any non-native view or active additive seenForTest predicate. It preserves the exact union including test-only extra visibility, even while real players exist. No custom forEach is called.
- traversal=none: event_missing or player-cap rejection before enumeration.
- candidates: raw square-cell visits for views (including excluded corners and overlap), or holder visits for fallback. Each32 candidates checks the cooperative time bound. At most20000 raw candidates /20000 fallback holders; cap cutoffs are candidates /holders respectively.

holders remains the count of visible holders examined. In native traversal it counts watched union members present in visibleChunkMap, including below-FULL holders. In fallback it includes unwatched holder visits as before. Do not compare holders across traversal methods as workload or completeness evidence without this distinction. watched_loaded/affected/scarred/pending/overdue/pending_without_job and wave-centre lag laws are unchanged.

Limits retained:16players,1024event lookup entries,32pending job events per chunk,1000000ns cooperative census allowance,20server-tick cadence. Final elapsed check also marks a last small batch or cold first use incomplete. No time/count allowance increased. Observer clocks do not alter production WorkClock,30ms budget,512MiB admission, physical clocks or acceptance limits. observer_ns and observer_pre_enqueue_ns retain their previous scopes; report costs/cutoffs and logger drops independently.

No persistent geometry/member cache, visited-set framework or retained world/chunk/player/view references are introduced. The <=16-view array and small scan object exist only during one census. Native Minecraft enumeration/contains are reused rather than adopting another tracking framework. The existing Detonation geometry caches and ScarQueue.due remain authoritative; their values are not approximated or rescaled.

Runtime cost and exhaustive real1Mt coverage must be measured in LEAD4's next already-required trial. Component unit/GameTests are deterministic semantic/passivity/bounds checks, not CI wall-time assertions or actual-trial acceptance.
