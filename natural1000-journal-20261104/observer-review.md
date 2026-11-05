# RPROBE5 independent source review

Reviewer: fresh Codex / GPT-6.1 Sol / high. Read-only source review; no nested reviewer, JVM, attach, game, server, GPU, build, CI or heavy job launched. Production checkout is clean `codex/mt-natural-trial-base` at `3efac357cc368045f6fe821ac02c561ab99af4c8`; diagnostic checkout HEAD verified `d553892fd4839841af89045f03d6f12d73cebd0c`.

## Exact inputs

Under `/mnt/data/projects/incoming/mod/run/wt-LEAD2/mod/run/lead2/`:

- Old `ownership-probe4/OwnershipAgent4.java`: SHA256 `135e3d792156c23a37bf7148789aceb77ff1761b993260a5750964f52ae9877b`.
- New `ownership-probe5/OwnershipAgent5.java`: SHA256 `4f57ed67c1c48979d292d2936ff2579e89e210d5d926eea7fd452ce8f6fe7e40`.
- Existing `ownership-probe5/ownership-agent5.jar`: SHA256 `46a7031005c92194473ef3e2a2b167a69a33aae1bd4803ddb58c42553917b247`.
- `build_probe5.sh` inspected, not run. Hash matching does not independently prove source/JAR equivalence or native attach success.

## Finding F1: literal passivity contract is stronger than the native predicate

The new `planReads` calls `getChunkNow` at line335. Native `ServerChunkCache.getChunkNow` reads the FULL holder result through `GenerationChunkHolder.getChunkIfPresent` → `getChunkIfPresentUnchecked` → `CompletableFuture.getNow`; on a hit it can also call `storeInCache`, replacing entries in the four-slot vanilla chunk lookup cache, and increments a profiler counter. Therefore an absolute “never read a future payload or cache” approval would be false. This is **not** a plan-future decode/collection, chunk load, ticket acquisition, reservation, admission or eviction, and no serious new planner-lifecycle fault was found.

Distinction: probe4 already uses this exact native predicate in `queueHeads` and `watchedJobs` (new file lines234–238,287). The delta adds at most64 calls to that existing mechanism. F1 blocks unqualified compliance with the literal wording, not the intended one-shot use under an explicitly narrower contract permitting native readiness lookups. No runtime harm or measurable overhead is established.

Smallest strict-contract correction: omit the new native lookup and report `loaded=uninspected`; the same existing lookups must be omitted if the prohibition covers the whole probe. Discriminator: trace all snapshot call sites and show no `getChunkNow`/holder-future lookup is reachable; a pending/completed/exceptional plan-read fixture must leave its future and owner maps unchanged. No additional observer framework is needed.

## Other changed paths

- `supportTables` lines299–309 reads only fields/array lengths. `read` lines392–399 walks superclasses, reaching actual fastutil `size/n/minN/maxFill/f/key/value/link`; missing `link` on ContactMap becomes null. `RuinMatter.java:85–100,138–144` establishes the concrete subclasses. No `bytes`, `retainedBytes`, reservation or claim method is invoked.
- `planReads` lines313–345 iterates the private plain `reads` map, not `RetainedPlans.get`. `RetainedPlans.java:13–24,151–165,186–224` shows why calling the latter would collect/decode/admit, and that this path avoids it. `jobs` is a plain Long2ObjectOpenHashMap (`ScarQueue.java:195`), `demanded` a LongLinkedOpenHashSet. Inspected fastutil8.5.12 class bytecode: map size/get and entry.getValue read fields/backing arrays; boxed bridges dispatch to those plain implementations; set.contains only probes its table. The entry-set wrapper may be lazily cached, without changing planner ownership.
- Identity deduplication avoids map hashCode/equals; one shared budget limits inspected read slots to64 across dimensions/owners. Actual read maps are bounded to16 by `RetainedPlans`. Future inspection uses only completion/cancellation/exception status, never the plan future payload. `RuinPages.java:44–51` returns ordinary CompletableFutures. `watched` (`ScarQueue.java:586–593`) reads native player views plus the existing test predicate; `ChunkTrackingView.contains` is arithmetic. The test override must remain null for a native-view interpretation; inherited output already reports it.

## Limits and handback

The inherited writer waits15seconds for the diagnostic String future off the server thread; failure becomes `SNAPSHOT_FAILED`, output uses CREATE_NEW. Its timeout does not cancel a queued/running snapshot. Any reflective error fails the whole snapshot; partial output is not returned. The complete inherited scan has no wall-time preemption; the new64-slot bound is not a whole-probe latency guarantee. Chunk-key job presence does not prove a consumer for this plan's detonation. Capacity lengths are not a complete heap estimate.

Native evidence: cached NeoForge-patched1.21.1 source archive `/home/artem/.gradle/caches/neoformruntime/intermediate_results/sourcesWithNeoForge_0e9be3e3bbc647eb9af1266fec36029e4755faff_output.zip`: `ServerChunkCache.java:173–200`, `GenerationChunkHolder.java:248–255`, `ChunkTrackingView.java:58–88,117–118`, `ServerPlayer.java:1869–1870`, `ChunkPos.java:40–42`. Fastutil bytecode: `/home/artem/.gradle/caches/modules-2/files-2.1/it.unimi.dsi/fastutil/8.5.12/c24946d46824bd528054bface3231d2ecb7e95e8/fastutil-8.5.12.jar`.

No source fix, runtime integration, performance acceptance, successful attach or zero-overhead claim. LEAD4 retains those decisions and all runtime/CI waits. All reviewer-owned resources0: jobs0, JVM0, servers0, clients0, GPU0, leases0, CI0, reviewers0.
