# MT-P2: exact post-queue server peaks

4 Nov2026, Sol/high focused diagnosis only. MT-L2 owns #579 merge/runtime acceptance. Input is the completed current `p2-window-pair` with the same helper5c8f4f79 on all arms: B=f8d6b1408, A2=eee272309. No new scene/client/server/GPU/profile, source edit, PR/merge/publication/contact or world deletion.

**Conclusion:** both selected late peaks are dominated in their sampled Server-thread CPU by **Burnt's scheduled smoldering-wood Stage3 → RandomCollapse → FallingSlabEntity construction, including cold class loading/Mixin transformation**. This is a demonstrated producing path outside the impact queue. These clips do not establish a redundant Incoming operation or an own fix. Do not blame FuelFires wholesale, LSS or #579 from the whole-tick number, and do not change other mods' fire/collapse behavior to hide it.

## Exact raw scope

| Field | B | A2 |
|---|---:|---:|
| Tick n / end t_ms |1681 /138051.216|1481 /133613.039|
| Whole tick wall / CPU, ms |78.0436 /62.1545|69.8445 /58.3839|
| Wall minus CPU, ms |15.8891|11.4606|
| ImpactCost outer, ms |.0010|.0010|
| Kind/stage arrays |all0|all0|
| work-window rows at n |0|0|
| Since salvo / last blast, seconds |41.208712 /27.599347|36.356221 /22.799018|
| Since last queue window, seconds |27.077145|22.307763|
| Prior last work-window n |1141|1036|
| Packets C2S/S2C/Incoming objects |0/35/0|1/26/1|
| Block writes total / air / fire |56/18/12|74/11/12|

Both fall in t35–50, long after the last actual queue window. A1's separate cold-GROUND peak is not clipped or diagnosed here. Packet/block counts are contextual activity counts, not attribution of exclusive CPU or proof of network bytes.

Only the exact two selected tick intervals were exported from the original recordings. Derived files are under `mod/run/p2-postqueue-peak/{b,a2}`: clip.json, native.cpu.collapsed, stock.tsv, attribution.json, span.tsv. Original events/recordings were read-only. Original input hashes are captured in clip.json; all derived hashes are retained in artifact-hashes.json.

Epoch intervals (ms) from existing PERFA median `srv.epoch_ms-t_ms` anchor:
- B:1793788972146.8818→1793788972224.9255; jfrconv rounded1793788972147→1793788972225.
- A2:1793789173943.4695→1793789174013.3140; rounded1793789173943→1793789174013.

Per-run anchor spreads are.9771/.9639ms, so a boundary sample has about1ms alignment uncertainty. Direct raw n/t matching is exact. Existing retained async-profiler4.6 jfrconv (`--cpu --threads --dot --from --to -o collapsed`) and JfrSpans.java were used; no whole new export/profile was made.

## Primary CPU/JFR evidence

Only Server-thread samples count below. Method labels were normalized for JIT/interpreter suffixes and deduplicated once per stack; exclusive leaf sums equal Server totals. Categories overlap and **must not be added or converted into exact removable milliseconds**.

| Inclusive stack scope | B (29 Server samples) | A2 (27 Server samples) |
|---|---:|---:|
| LevelTicks.runCollectedTicks / scheduled block path |18|20|
| BurntPlanksStage3Procedure.execute |17|19|
| RandomCollapseProcedure.execute |17|19|
| FallingSlabEntity constructor |16|19|
| Module/ClassLoader class-loading frames |18|15|
| MixinTransformer.transformClass |11|14|
| Stage3 samples also containing class loading |15|14|
| Incoming FuelFires.onLevelTick/hold |1|1|
| Incoming DebrisEntity.tick |0|0|
| LSS delivery / window observer frames |0|0|

Primary repeated stack, in caller→callee order:

```text
MinecraftServer.tickServer → tickChildren → ServerLevel.tick
→ LevelTicks.tick → runCollectedTicks → ServerLevel.tickBlock
→ BlockState.tick → Burnt SmolderingPlanks[3]Block.tick
→ BurntPlanksTickProcedure.execute → BurntPlanksStage3Procedure.execute
→ RandomCollapseProcedure.execute → EntityType.spawn/create
→ BurntModEntities factory → FallingSlabEntity.<init>
→ ModuleClassLoader / ClassTransformer / MixinProcessor...
```

B uses SmolderingPlanks3Block; A2's matching sampled path uses SmolderingPlanksBlock and reaches the same Stage3 collapse/constructor. Class-loading/Mixin frames occur **inside that producing chain**, not just in unrelated JVM threads. Native leaves include JVM symbol/method lookup and Mixin/ASM transformation helpers. The profiles do not name the exact class being transformed at each nested load, so no such class/pass is invented.

Stock JFR independently has only **one Server-thread execution sample per selected interval**; both follow the same Burnt Stage3→RandomCollapse→FallingSlabEntity constructor chain. This corroborates the path without supplying precise exclusive duration. No matching GC pause is present in retained quality/GC rows or stock selected-event data. The only stock Server-thread park events clip to~.486/.481ms at an interval boundary, below the anchor granularity and far short of the whole wall/CPU gaps. Those15.889/11.461ms gaps remain unresolved (no claim that they are entirely class loading, GC, a profiler stall or foreign contention).

Original t35–50 quality: performance power profile, mean foreign CPU B0/A2.113cores (max0/.18), foreign GPU0 in those coarse samples. Recorded window GC maxima9.936/8.166ms exist elsewhere but do not overlap these exact peaks. Coarse host sampling cannot exclude every short scheduling interruption. Full-JVM compiler/worker sample totals are not added to the Server-thread budget.

## Local source and exact artifact references

Both arms contain the same `burnt_basic-1.10.5.3-neoforge-1.21.1.jar` SHA256 **dce22e69dbf4520511b24801c626ff21cec05ac701f9dae806bd510ef0a4372b**. Read-only javap bytecode/line tables from this exact jar are retained in the diagnostic root:

- `BurntPlanksStage3Procedure.javap.txt:64`: BCI98 calls RandomCollapseProcedure.execute.
- `RandomCollapseProcedure.javap.txt:124–139`: BCI233 reads BurntModEntities.FALLING_SLAB and BCI261 invokes EntityType.spawn. Other branches have the same factory/spawn path (BCI328/356,594/620,687/713). Existing collapse config/headroom/chance gates appear at BCI21–65. No settings were changed.
- `FallingSlabEntity.javap.txt:9–20`: constructor BCI3 invokes PathfinderMob superclass; line table maps it to source FallingSlabEntity.java:28. The sampled constructor can therefore initialize/load generic entity machinery as well as Burnt's own types. No unsupported assertion about which exact transformed class dominates is made.

The minor own contribution is independently visible in one sample per arm:
`FuelFires.onLevelTick→hold→Warheads.igniteIn→Level.setBlock`. B continues through vanilla FireBlock's Burnt hook into Firestarter/ignition procedures; A2's sampled leaf instead continues into light-task scheduling. These are **different stacks from the dominant scheduled Stage3 collapse**.

Current local own code references:
- `warhead/FuelFires.java:314–328`: own crater pool upkeep on level Post, outside UnitQueue.
- `FuelFires.java:330–371`: support/fire state checks and existing relight backoff;371 calls igniteIn.
- `warhead/Warheads.java:401–405`: ready-neighbor guard, BaseFireBlock state/survival and ordinary setBlock. This permits the loader/block's normal behavior; it does not itself call Burnt collapse/entity construction.
- `.claude/rules/warhead.md` documents the existing Burnt fire replacement/backoff and ordinary block-fire ownership.

`git diff eee272309 f8d6b1408 -- mod/src/main` touches only WorkClock and UnitQueue. FuelFires/Warheads are also unchanged between eee272309 and main14f36e8ec. No basis exists here to call this tail path introduced by #579. Its presence in both baseline and candidate proves the path pre-exists; this is not a proof that every timing/frequency is causally independent of earlier work interleaving.

## Disposition / remaining limits

- The selected peaks are **not an impact-window accounting failure**: queue windows ended22–27s earlier and current outer impact time is.001ms. They do not invalidate the parent's separate active-window accounting result.
- A repeated external producing cause is demonstrated: scheduled Burnt wood collapse reaches entity construction/class transformation in both fresh JVMs. This looks like a cold entity path, but two exact clips do not establish warm recurrence/frequency or a third-party correctness bug.
- No own producing defect or safe own optimization is demonstrated. One FuelFires sample is insufficient to justify changing its law/timing/backoff, and zero sampled Debris/LSS frames do not prove those systems universally free.
- The recordings do not identify which earlier fireball/pool/ordinary spread ignited the specific wood cell. Strike ignition can lead to ordinary fire gameplay, but causal coordinates/ignition provenance are absent. Do not invent that link or replace/disable another mod's mechanics in Incoming.
- Therefore no new Incoming fix/benchmark is proposed from these two peaks. Keep them explicitly classified as **post-impact scheduled Burnt entity/class-load cost with unresolved wall-gap remainder** in readiness reporting. If the owner later needs pack-level cold/warm recurrence assessment, that is a separate bounded task using normal scheduled wood collapse in a disposable copy, not justification to repeat575 or change579. No upstream contact/config/pack change is authorized or performed by this report.

## Owned resource completion

Read latest TEAM/live/BOARD/owner/GOLD requests before launch: no explicit active quiet hold; root/C2 declared diagnostic co-run rather than quiet latency, GOLD flight was not running. Host MemAvailable23609MiB before exports; clips needed<1GiB and no GPU.

- `incoming-job-mt-p2-postqueue-clips-315268.service`: exit0, awaited;6.486s CPU /2.456s wall /636.6M memory peak. Only two exact native/stock peak clips.
- `incoming-job-mt-p2-postqueue-bytecode-318154.service`: exit0, awaited;.832s wall /69.7M peak, selected existing-jar javap source evidence only.

No owned job/client/server/GPU lease/review/CI remains. Tracked tree untouched/clean atf8d6b1408 at inspection; parent may merge newer accepted main independently. No public artifacts were written; eventual evidence remains owner's look-gate publication.

Final resource snapshot 2026-11-04T11:10:00.209038+00:00: MemAvailable15991MiB; zero owned active units/clients/leases, no pending export.
