# MT-B2 review fixes: registered support continuation

Production head: `f92dabaaac30bb04b3e9b07296be44a2602114c8`. Main merged with merge commit `3c6f54e87`; no rebase.

## Corrections to the earlier evidence

The original `PipelineBench` did not register events 8200+. Its MTMAT rows exclude `RuinSupport`; they measure moving-record work. The earlier commit/apply samples also changed different numbers of blocks, so they are fixture observations, not an equal-work comparison or a whole-zone service-rate bound.

## Registered continuation probe

`ReviewSupportBench.java` registers each event in `NuclearEvents`, removes the only glass bearing contact above an obsidian anchor, and calls the production `RuinMatter.work` with one bounded unit per call. The complete connected concrete roof crosses chunk seams. It measures original-connectivity proof, live discovery, validation, release and seed drainage. Setup, introspection and serialization are outside work timing. Game time does not advance inside each sample: released bodies remain in flight, so this is support-continuation cost, not full flight/settlement or cold-load latency.

Each size has one first pass and two warm repeats. Dividing total continuation time by the number of affected chunks is an amortized observation, not the duration of one chunk-application call.

| Roof | Chunks | Warm total ms | Warm ms/chunk | Largest warm unit ms | Peak graph collection entries | Peak support seeds | Saved NBT bytes | Warm save ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 16×16 | 4 | 2.586–2.695 | 0.646–0.674 | 0.287 | 732 | 168 | 89911 | 0.639–1.032 |
| 32×32 | 9 | 9.500–10.014 | 1.056–1.113 | 0.400 | 3004 | 825 | 357943 | 2.594–2.604 |
| 48×48 | 16 | 16.297–22.542 | 1.019–1.409 | 0.589 | 6812 | 1994 | 804663 | 4.828–5.882 |

First-pass maximum unit: 2.737 ms. Serialized bytes are actual uncompressed NBT size, **not retained Java heap bytes**. Graph entries sum frontier, visited and component collections; positions may appear in more than one. No whole-zone/global-heap bound is established. Active journal entries retire only when all support queues, including cold faces, drain. Completed crater checkpoints and stranded-piece compaction from optional O2 remain outside this blocking-fix pass.

## Regressions

- B1: registered weak detonation, floating end-stone island and a separate stone platform with a broken glass pendant, clean save/reload before continuation; terrain stays and the queue drains.
- B2: four falling materials, including a barrel with seven diamonds; rails, torch and wire inserted during flight; clean save/reload, exact settlement/type/contents and ordinary decoration-drop counts.
- B3: real Create upward belt at a cold chunk boundary reaches release; the next callback neighbourhood stays cold, source remains intact, and a belt arriving at a destination also waits. Both removals and onPlace use the shared radius-2 contract.
- B4: new obsidian contact inserted after an earlier validation slice and between release slices; standard neighbour notifications and our own batched replacements both invalidate the certificate. No further roof cells are released after the repair.
- Registered support loss still releases a 56-block roof across seams; redundant support seeds and completed journals drain.

Ordinary external changes use NeoForge neighbour notifications (including cancelled notifications); our replacements notify explicitly despite suppressing neighbour cascades. Release also checks local state and contacts. Code that silently edits raw chunk sections must participate in invalidation; this implementation does not add a vanilla/foreign mixin to observe arbitrary unsupported writes.

## Follow-up capture status

The final head reached green Итог while the optional follow-up sandbox was still queued behind MT-B3. Per Artem's explicit instruction to stop after green CI, only our unstarted request was cancelled. No MT-B2 GPU client was launched in this correction pass and no new frames are claimed. The earlier paired frames remain attached with their actual reviewed head (`ad1f7c064`) and diagnostic FX-clear disclosure. B1–B4 on the corrected head are established by the registered continuation GameTests above. The unused disposable fixture/counter source is retained here as preparation, not executed evidence.
