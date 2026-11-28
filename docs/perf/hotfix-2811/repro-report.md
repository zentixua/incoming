# Repro of the 28.11 Zearth nuke (3.14.0): measurement report

Raw runs stay on the laptop; this is the executor's report, paths redacted.

# hotfix-2811-repro: report

Executor: claude2 (Opus 5.5). Measurement only, no fixes. Take of record: **run-2** (full sequence), **run-3** (artifact),
run-1 (valid to +148 s, spoiled after by a probe defect). Every t is seconds since the client received the detonation.
Rig, deviations and the exact commands to repeat: section 7.

## Top 10 numbers
1. **Flashback recording after a nuke fills the 12 GB heap → whole-client freezes.** While Flashback records (Artem
   recorded 10:12:31 → 10:16:29), the pack mod Flashback NeoForge Fixed 1.0.19 (`dev.flashbackfix`, third-party, MIT)
   copies every modded payload into one growing heap buffer: **493 MB/s of allocation (62 % of the client's) at 0…+148 s
   against 32 MB/s before the detonation**, **+2.5 GB of live `byte[]`** (538 MB at join → 3 021 MB at +140 s → 539 MB
   right after the save). The heap sits at 95–100 % after collections, GC runs 12–31 cycles per 30 s, and ZGC
   **allocation stalls freeze every thread at once: 597 ms at +129.5 s** (render, network, chunk builders, LSS, Voxy) —
   Artem's "1 fps at GPU 4 %" frame at +132 s. Run-2: 135 stalls (23.9 thread-s) in 0…+148 s, **0 after the save**.
2. **Flashback's save runs on the render thread: one 9 799 ms frame** (run-2, +148 s; run-1's stop alone 3.7 s). Artem's
   saves: Zearth +150 s (his p98/p99.5 collapse to 2/1 fps appears right after it), Newisle +116 s with a 15.9 s
   render-thread gap. The recording is huge because of the nuke's payload stream: `incoming:voxy_chunk` 117–178/s,
   `lss:voxel_column`, chunk/light packets 412 kB/s (Artem's Zearth replay: 1.17 GB for 225 s).
3. **The pall at the floor rung costs 21–29 ms against its 15 ms budget**; the ladder can cut only the rays (**34 ms at
   the setting's rung → 8 ms at rung 8**, same frozen age). Near volume 5.2–5.6, composites 4.1–8.2 (full resolution),
   sums 1.2–1.3, wall march 1.3–2.9, preparation 0.8–1.0 ms = **12–17 ms no rung touches**, already near the budget.
4. **Without recording and without F3 the window is smooth but GPU-bound**: +190…+240 s at Artem's spot: 23.4 fps,
   p98 58 ms, p99.5 63 ms, 1 frame > 100 ms in 50 s, GPU 100 % busy, 42.6 ms GPU per frame of which **Incoming FX 26.8 ms
   (pall 24.6)**; before the launch 66 fps at 14.5 ms. The post-nuke frame cost is our FX layer (pall ≈ 92 % of it).
5. **F3 open adds a ~150 ms frame every 2 s** (LayerTimes' drain frame: `glFinish` at every pass mark): +250…+300 s p98
   154 ms and p99.5 210 ms against 58/63 ms with F3 closed, 26 frames > 100 ms in 50 s against 1. Artem's F3 p98/p99.5
   readings are partly this probe.
6. **Pall OFF (same spot, +640…+700 s): 33.8 fps against 24.5–26 with MEDIUM**, GPU 29.4 against 38–40 ms per frame; the
   pall pass still costs 6.1 ms when OFF (near volume 1.7, rays 1.7, composites 1.3: the cloud/smoke part).
7. **Shader pack off (vanilla, +500…+560 s, frozen age): 36.7 fps against 24.4 with Photon**; GPU 26.9 against 41.0 ms;
   the pall itself 16.8 against 21.0 ms (rays 5.9 against 8.0, near volume 4.1 against 5.4).
8. **The artifact reproduces at Artem's spot and time** (run-2 +158…+170 s, run-3 +120…+150 s) and is drawn by the pall
   pass with or without Photon; **no `DustWallDiag` switch removes it** (terrain, material, open-air, history, interleave,
   reprojection, fixed offsets, rung 2/8). Under a spire the near dust is thinner (T 0.89 vs 0.83 within ~230 m of the
   eye); the tops keep moving with the wave's age held (top std p90 82–126 px): a time-varying per-column dust top.
9. **Server and LOD traffic are not the stall cause**: laptop server tick avg 16–31 ms (Artem's Zearth 18.5–31.5), Voxy
   changed-chunk LODs 5 900–11 300/min (his 11 000–11 600), and frames stay clean at 10–11 k/min after the save.
10. **The wave's passage (+20…+28 s) has its own client-tick hitches**: frames 300–690 ms with 100–160 ms of client ticks
    (Incoming `Fragments`, `SoundFilters`, `FireRoar`, Lithium entity collisions); run-2 worst 686 ms at +26 s.

Severity × reach (the lead's triage note): 1 and 2 hit everyone who records with Flashback while nuking (Artem always
records; friends with the pack mod installed) — the freezes he reports; 3/4/6/7 are the 13–24 fps floor every MEDIUM
player gets for minutes; 5 only with F3 open; 10 is a few seconds at the front. Which are hotfix vs 3.14.2 is the lead's
call; 1–2 sit in a third-party mod (pack choice / author issue / our payload volume), 3 in our pall.

## Q1. GPU: the pall's parts at the floor rung, over time

`SCENARIO pall-parts` (PallPass.describe every 20 ticks; ms per frame, mean over the last second), run-2, MEDIUM
(budget 15 ms), 2560×1600, Photon high. Eye at −1069 −293 to +100 s, then at Artem's spot (2.8 km) facing ground zero.

| t s | rung | total | preparation | near volume | sums | rays | accumulation | wall march | pack | wall resolve | composites |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0–20 | 2→6 | 13.3 | 0.70 | 1.23 | 0.48 | 6.46 | 0.52 | 0.70 | 0.03 | 0.23 | 2.97 |
| 20–40 | 2–6 | 11.5 | 0.95 | 1.58 | 0.51 | 1.90 | 0.52 | 1.66 | 0.03 | 0.26 | 4.03 |
| 40–60 | 2 | 12.0 | 1.07 | 2.01 | 0.52 | 0.72 | 0.48 | 2.23 | 0.04 | 0.29 | 4.62 |
| 60–80 | 2→8 | 15.7 | 1.02 | 2.33 | 0.52 | 2.20 | 0.45 | 3.51 | 0.03 | 0.24 | 5.41 |
| 80–100 | 8 | 17.9 | 0.98 | 2.30 | 0.52 | 5.51 | 0.44 | 1.69 | 0.03 | 0.21 | 6.24 |
| 100–120 (at spot) | 8 | 25.1 | 0.88 | 3.92 | 0.99 | 8.84 | 0.42 | 2.27 | 0.03 | 0.20 | 7.52 |
| 140–160 | 8 | 26.0 | 0.89 | 4.43 | 1.22 | 8.20 | 0.44 | 2.81 | 0.02 | 0.19 | 7.83 |
| 160–180 | 8 | 28.9 | 0.99 | 5.60 | 1.30 | 9.14 | 0.45 | 2.95 | 0.04 | 0.31 | 8.17 |
| 200–220 | 8 | 23.2 | 0.89 | 5.40 | 1.23 | 8.53 | 0.44 | 1.32 | 0.03 | 0.07 | 5.27 |
| 240–260 | 8 | 22.3 | 0.76 | 5.24 | 1.21 | 7.99 | 0.43 | 1.31 | 0.03 | 0.07 | 5.25 |
| 280–300 | 8 | 22.6 | 0.76 | 5.52 | 1.27 | 8.20 | 0.45 | 1.35 | 0.03 | 0.07 | 4.93 |
| 340–360 | 8 | 21.9 | 0.96 | 5.53 | 1.27 | 8.00 | 0.46 | 1.38 | 0.03 | 0.07 | 4.25 |

Same frozen age (+460 s), rung pinned (`dw:rung`):

| rung | total | near volume | sums | rays | wall march | composites |
|---|---|---|---|---|---|---|
| 2 (the setting's; "rung 0" is capped there) | 41.2–47.2 | 4.6–5.2 | 1.0–1.2 | **29.4–33.9** | 1.2–1.4 | 3.7–4.0 |
| 8 (floor) | 21.7–21.9 | 5.5 | 1.25 | **7.9–8.0** | 1.3 | 4.2 |

- What the ladder cannot cut, and why: `LADDER` steps change the ray detail (`1/2 × 28` → `1/4 per frame, 1/2 × 22`) and
  the wall divisor; the near volume (214×134×64 to 256 blocks), its sums, the preparation and the full-resolution
  composites (outside the ladder: dust-wave.md "Budget") stay. At rung 8 they are 12–17 ms, the rays 8–9 ms.
- Run-1 (rung 8, +158 s, burst frames): 24–27 ms: rays 7.9–8.3, composites 6.9–8.0, near volume 4.5–5.5, wall march
  2.4–2.5, sums 1.1–1.2, preparation 0.8–0.9: the same split.
- Over time: rays and near volume grow as the eye enters the dust (+100 s: near volume 2.3 → 3.9 → 5.5 ms); composites
  peak 7.5–8.2 ms at +100…+180 s and fall to 4.1–5.3 ms by +200…+360 s; wall march 2.3–3.5 ms early, 1.3 later; wall
  resolve 0.2 → 0.07 ms. Rung history: 2→6 by +13 s, back to 2 by +37 s, 2→8 at +66–69 s (16–17 ms measured), then 8.
- Whole frame (nuke-layer per-pass means): steady +190…+240 s: pall 24.63, heat 0.95, quads 0.45, bloom 0.28, depth
  0.25, water 0.22 = 26.8 ms of a 42.6 ms GPU frame. Pre-launch the whole frame was 14.5 ms.
- One more number for the lead: after the shader pack was toggled off and on again (+600 s) the pall stayed cheaper
  (17.8 ms: rays 6.0, near volume 4.1) than before the toggle at the same frozen age (21.7 ms); not explained here.

## Q2. CPU: what makes the 0.5–10 s frames

Run-2, frames > 100 ms after the detonation, probe-made frames removed (burst PNG grabs, pass dumps, heap histograms,
the shader toggles, the quit). Each frame is attributed to a render-thread ZGC allocation stall when the stall covers
≥ 40 % of it, to the LayerTimes drain when a 5 ms sample shows `glFinish` in `LayerTimes.mark`, else to the owner of its
5 ms render-thread samples (`longframes.py`, `analysis/longframes.tsv`, `analysis/longframes-clean.txt`).

| phase | frames > 100 ms | time | largest shares |
|---|---|---|---|
| 0…+148 s, Flashback recording | 115 | 19.2 s | Incoming FxParticle 12 % (2.2 s), Subtle Effects 9 %, Lithium collisions 7 %, Incoming Fragments 7 %, **ZGC stall 6 % (the 836 ms frame)**, LSS→Xaero 5 %, Incoming FireRoar 5 % |
| +148…+170 s, the save | 7 | 10.8 s | **Flashback ReplayExporter 91 % (one 9 799 ms frame)** |
| +170…+360 s, no recording | 72 | 11.9 s | **LayerTimes drain (F3 open +250…+300 s) 38 % (30 frames, ~150 ms)**, rest scattered 100–400 ms frames |

- Allocation stalls (gc.log): run-2 0…+148 s **135 stalls**, by thread (thread-ms): chunk builders 7 424, Flashback
  saver 5 665, file watcher 2 678, **render thread 1 220 (10 stalls, max 597 ms)**, Netty 1 168, LSS 786, Voxy 760; after
  the save none. Run-1 (recording to +148 s): 248 stalls, Flashback saver up to 1 740 ms each, render 35–67 ms.
- Heap (run-2 class histograms, live): join+110 s 3.77 GB; +140 s (recording) **5.81 GB, `byte[]` 3 021 MB**; +175 s
  (after the save) 3.34 GB, `byte[]` 539 MB. ZGC major collections during recording: 11.6–12.2 GB → 5.5–10.2 GB every
  4–11 s; after the save 1 major per 100+ s, 3.5–3.7 GB live.
- Allocation by owner (JFR, `analysis/alloc-windows.txt`): pre-launch 473 MB/s (LSS column processing 203, render 149);
  recording before detonation 339 MB/s (flashbackfix 32); **recording after detonation 796 MB/s, flashbackfix
  `ActionModdedPayload.write` 493 MB/s on Flashback's `AsyncReplaySaver` thread** (stack: `FriendlyByteBuf.writeBytes →
  AbstractByteBuf.ensureWritable → UnpooledHeapByteBuf.capacity`: the buffer grows by copying); after the save 256 MB/s
  (vanilla meshing 57, Incoming TerrainTiles 21).
- Client CPU (`probes/client-threads.csv`): recording +100…+148 s 7.7 CPU-s/s — ZGC old workers 953 ms/s, Flashback
  saver 694, C2 615, chunk builders 3 165, render thread 784 with **148 ms/s run-queue wait** — and GPU only 64 % busy;
  after the save 3.4 CPU-s/s, render wait 19 ms/s, GPU 100 %.
- Packets (hitch-net, per second): 0…+148 s `lss:voxel_column` 137, `incoming:voxy_chunk` 117, `lod_batch_finished` 55,
  chunk-with-light 412 kB raw (29 kB compressed); after +190 s `incoming:voxy_chunk` 178/s with clean frames: LOD ingest
  itself does not stall the render thread.
- Safepoints: no game-stopping pause outside allocation stalls; the largest are my probes (`GC_HeapInspection` 1 013 ms
  at +143.9 s, histogram). JIT: C2 1.0–1.15 CPU-s/s for 20 s after the teleport (+100 s), not in long frames.
- The first frame after the detonation 349 ms (`OpenAir` 12 of 15 samples) and the wave's passage +20…+28 s (frames up to
  686 ms, client ticks 100–160 ms per frame: Incoming Fragments, SoundFilters/ThroughLeaves, FireRoar; Lithium
  collisions) are our own client-tick costs (run-1 the same: 22 frames > 100 ms at +20…+30 s).
- Artem's p98 2 fps / p99.5 1 fps after +168 s on Zearth: his F3 percentiles held the save frame and the 2 s drain frames;
  without both, the same scene keeps p98 ≈ 58 ms here.

## Q3. The artifact

Frames (Artem's spot −346.37 98.13 −100.36, facing −100.2/−8.7, Photon high, MEDIUM, rung 8):
- run-2: `run-2/client/screenshots/art_a_f00…f11.png` (+158…+170 s, 12 consecutive frames), `art_b_*` (+237 s),
  `art_c_*` (+297 s); gone by `sw_base_*` (+360 s). Spire band frame-to-frame |Δ| 0.94/255 against 0.56 in the dust above;
  per-column spire top moves with std p90 90 px across the burst (median 0: most columns still, some jump).
- run-3 (artifact take): `a_pre_*` (+120 s), `a_base_*` (+150 s), then the wave held at that age (`dw:freezeage=1`)
  for `a_frz_*` and every switch below; montage `run-3/analysis/frames/switches.jpg`, onset `…/onset.jpg`.

Per-term switches at the held age (band 420–760 × 700–1900 px; "dark share" = pixels darker than `a_frz`'s 20th
percentile; "top std" = per-column spire-top movement across the 10–12 frames, p90):

| switch (run-3) | spires | dark share | band mean | frame-to-frame Δ /255 | top std p90 px |
|---|---|---|---|---|---|
| none (`a_frz`, age held) | yes | 0.088 | 96.4 | 0.27 | 126 |
| `terrain=0` (flat ground for the march) | **yes** | 0.477 | 92.5 | 0.18 | 21 |
| `material=0` (all soil) | **yes** (dust turns tan, spires stay grey) | 0.000 | 160.9 | 0.19 | 82 |
| `openair=0` | **yes** | 0.040 | 96.5 | 0.28 | 90 |
| `history=0` | **yes** | 0.056 | 96.5 | 0.54 | 98 |
| `interleave=0` | **yes** | 0.055 | 96.9 | 0.21 | 91 |
| `reprojection=0` | **yes** | 0.000 | 121.4 | 0.20 | 68 |
| `freeze=1` (ray/sample offsets fixed) | **yes** | 0.000 | 121.2 | 0.13 | 82 |
| rung 2 (setting's) / rung 8 | **yes** / **yes** | 0 / 0 | 124 / 122 | 0.18 / 0.15 | 81 / 100 |
| shader pack off (`a_van_*`, vanilla) | **yes** | 0.000 | 132.5 | 0.21 | 72 |
| pall off, same instant (`pall_a_pair_off_*`) | **no** | – | – | – | – |
| age released (`a_unfrozen_*`, ≈+330 s) | low remnants | 0.001 | 106.3 | 0.11 | 77 |

(The dark-share column moves with the overall brightness each switch gives; read the frames: the spire silhouettes are
in every row but "pall off".)

The dust wave's own numbers for those pixels (march pixels under the spires against the light band beside them; dumps
`run-3/client/screenshots/a_dump_base_*` at +150 s, `a_probe_end_*` with `probe=-2` at the held age, `a_van_dump_*`
without shaders; script `probe_end.py`):

| term | under spires | beside | source |
|---|---|---|---|
| transmittance | **0.89** (p10 0.80) | **0.83** | `span` a, base dump |
| first occupied depth | 0 m (eye inside the dust) | 0 m | `span` r / coverage |
| last occupied depth | 233 m (p10 203, p90 2 359) | 258 m | `span` g / coverage |
| light (luma) | 0.08 | 0.11 | `march` rgb |
| same without shaders | T 0.89 vs 0.84, last depth 229 vs 267 m | | `a_van_dump` |
| ray end (probe −2) | 30 000 (cap) | 30 000 | no drawn depth, no relief hit, both |
| relief end / scene depth | none / none | none / none | probe −2 |
| samples on the ray | 11 | 16 | probe −2 |
| eye above source floor | 34.8 m | 34.8 m | probe −2 |

What this says, by number: the spires are drawn by the pall pass (gone with the pall off), the same with and without the
shader pack, so not Photon; they are not the march's ray end (open-sky rays run to the cap in and beside them), not the
terrain relief, material tiles, open-air window, temporal history, interleave, reprojection, per-frame offsets or the
ladder (each switched off, the spires stay). Under a spire the ray collects **less** dust (T 0.89 against 0.83, fewer
occupied samples 11 against 16) within the first ~230 m from the eye, so the dark shape is the haze behind showing through
a lower local dust top: a per-column top of the near dust that is jagged across screen columns. Its tops keep moving with
the wave's age held and the offsets fixed (top std p90 82–126 px), so the input that shapes them changes over time by
itself. The term that sets a per-column dust top near the eye is not among the `DustWallDiag` switches; the lead's next
probe is a `dw:` switch or a `probe` sample sweep **taken while the age is held at +150 s** (run-3's sweep ran after the
age was released, `a_sweep_p*`, when the spires had collapsed). Terrain map at the held age: `a_ground_0*` (128² cells
of 338 m, 315 known).

## Q4. Mod or pack

| window (run-2, Artem's spot) | fps | GPU/frame ms | pall ms | other Incoming passes ms |
|---|---|---|---|---|
| before launch (Photon, no nuke) | 66.0 | 14.5 | – | – (Artem: FX 0.3) |
| after save, MEDIUM, Photon | 23.4 | 42.6 | 24.6 | 2.2 |
| pall OFF, Photon (+640…+700) | 33.8 | 29.4 | 6.1 | 4.3 |
| MEDIUM again (+700…+770) | 24.5 | 40.3 | 18.8 | 4.4 |
| vanilla, MEDIUM (+500…+560, frozen) | 36.7 | 26.9 | 16.8 | 2.9 |
| Photon, MEDIUM, same frozen age (+360…+430) | 24.4 | 41.0 | 21.0 | 2.4 |

- Our layer is the post-nuke cost: pall MEDIUM → OFF frees 10–11 ms GPU per frame (fps 25 → 34); the rest of the
  post-nuke frame over the pre-nuke 14.5 ms is Photon/world drawing the same scene (+5 ms) and our other passes (heat
  rises to 3.3 ms by +700 s).
- The 0.5–10 s freezes are the pack's Flashback NeoForge Fixed + Flashback (record, save) under our payload stream, not
  our render code; the hitches at the front are ours.

## Lead's notes (12:00): F3 halves, FxParticle, stalls at the teleport
**F3 closed against F3 open** (run-2, Artem's spot, no recording; F3 opened at +257.3 s, closed at +317.3 s, drains go on
5 s after the last read):

| window | frames | fps | p50 ms | > 100 ms | of which drain frames | others > 100 ms |
|---|---|---|---|---|---|---|
| F3 closed +190…+245 s | 1 288 | 23.4 | 43.6 | 1 | 0 | 1 (118 ms, DustWallPass) |
| F3 open +257…+296 s (no burst inside) | – | 22.4 | 41.7 | 21 | **20, mean 150 ms, one every 2.0 s (3.0 s of 39 s)** | 1 (104 ms, Essential) |
| F3 closed +318…+360 s | – | 23.9 | 41.5 | 3 | 0 | 2 (102–107 ms, Essential) + my heap histogram (981 ms, probe) |

The drain's own share: with F3 open it is 20 of 21 long frames and 97 % of their time; F3 itself costs ~1 fps besides.

**FxParticle** (render-thread 5 ms samples whose leaf-most Incoming frame is `FxParticle`, 0…+148 s): 3.9 % (run-1) and
4.4 % (run-2) of all samples; by method `move` 47–60 % (`Entity.collideBoundingBox` → Lithium `CollideMovement`: a
block-collision sweep per particle per tick), `light` 30–41 % (`LevelRenderer.getLightColor` per particle per tick, with
`Level.getBlockState`), `emit` 1–7 % (`FxPool.collect` from `FxLayer.draw`), `tick` 2–11 %. All of it runs in
`FxPool.tick` from `IncomingClient.tick`, so a slow frame's catch-up ticks multiply it: the long frames attributed to it
held 2.8 (run-1) / 4.5 (run-2) client ticks with 40 / 60 ms of tick time. Counts: the vanilla particle engine 64–2 162
(peak +20…+40 s), Incoming fragments 1 200 (cap) at +20 s; FxPool's own particle count is not logged by the hitch probe
(add it to `HitchProbe.state` for the rerun).

**Allocation stalls at the teleport** (run-1, tp at +100.0 s): the heap was already 94–100 % full (12 288 MB, live after
the last major 7.5 GB, the recording's buffers); from +100.5 to +101.5 s young collections freed nothing
(12 288 → 12 288 MB) and the render thread stalled 9 times (44, 23, 101, 4, 42, 43, 42, 48, 12 ms = 358 ms) until the
major collection ended (+101.5 s, 12 164 → 7 836 MB). Allocation +95…+110 s: 759 MB/s all threads, 90 MB/s on the render
thread: Lithium entity collisions 10.7 (entities 247 → 1 017 after the teleport), `TerrainTiles.column/color/wet/
dustGround` 15.9 together (Incoming), Xaero colour lock (Incoming mixin) 4.9, `LssColumns$Store.chunk` 4.5 (Incoming),
`Shapes.create`/`AABB.move` 6.2, LSS→Xaero tile writer 2.8 MB/s. The render thread's own allocation is small; the stall
comes from the full heap (point 1 of the top 10), the teleport only adds the load that tips it.

## 5. Artem's session (existing data; the Newisle nuke covered without a second server)
Full table: `run-1/artem/analysis.md` (every screenshot, ladder lines, server lines, timeline).
- Zearth: pall at rung 8 from +61 s to the end, 17.6–26.9 ms; fps 13–25; p98/p99.5 22/17 until +132 s, 2–5/1 from +168 s
  (after his save at +150 s); allocation up to 2 339 MB/s; one 1 fps frame at +132 s with GPU 4 % (= the allocation
  stall above); spikes in 6 of 10 frames, all with Photon on, facing ground zero, +123…+177 s.
- Newisle (second 300 kt, ground burst at −1075 78 742, ordered 10:19:37.8, detonation 10:21:08.4; he stood 3.0 km away
  to +26 s, then flew in as spectator to 1.1 km): ladder carried rung 8 over from the Zearth world (started at 8, not
  the setting's 2), 3 by +32 s, back to 8 at +34 s right after switching to spectator; pall 8–14 ms at rungs 7–3 and
  14.4–27.2 ms at 8; fps 20–33; allocation up to 1 819 MB/s; Flashback recording 10:19:40 → save at +116 s followed by a
  15.9 s render-thread gap; server "Can't keep up" 4×, worst 5 898 ms. **No Newisle number contradicts the Zearth
  run**: the same 22–27 ms at the floor rung, the same recording/save pattern; no spike frame there, but no Newisle frame
  repeats the Zearth view and time.
- Ladder note from both sessions: the rung jumps to 8 within 2–5 s of switching to spectator (Zearth +41 s 4→8 at
  35.47 ms; Newisle +32.8 s 3→6→8), and the rung state survives a world change.

## 6. Raw files
Run folders under `mod/run/agents/hotfix-2811-repro/`: `run-1/` (partial), `run-2/` (record), `run-3/` (artifact).
In each: `client/logs/` (latest.log, debug.log, gc.log, run.jfr, hitch-frames/stacks/samples/net/sched/state/be.txt),
`client/screenshots/` (burst frames, `*_dump_*` pass targets `.f32` + `_dw.json`, `*_sweep_p*` per-sample probes),
`client/config/spark/*.sparkprofile`, `client/flashback/replays/`, `srv/logs/` (latest.log, gc.log, run.jfr),
`srv/config/spark/profile-2026-11-28_*.sparkprofile`, `probes/` (run-events.log with every probe's time, commands.txt,
client/server-threads.csv, perf-client.csv, nvidia-smi.csv, vmstat.txt, jfr/client-N.jfr, heap/ (run-2:
heap_info.txt, histo-*.txt)), `analysis/` (analysis.md, buckets.csv, longframes.tsv, alloc-windows.txt,
packets-windows.txt, frames/, dump/). Artem's copies: `run-1/artem/` (logs, screenshots, replays, both servers' logs and
Zearth's config); his Zearth Voxy/Xaero caches as of 10:23: `artem-caches-2811/`. Scripts: `run-2/{run.sh,
setup_server.sh, heap_probe.sh, threads_probe.py, analyze.py}`, `longframes.py`, `alloc_windows.py`.
Probe-only code: branch `probe/hotfix-2811-repro` (worktree `<laptop>/wt-hotfix-2811-repro`): devtest
`DustWallSteps` gains `dw:key=<mapping>` (presses a key mapping, used for Iris' toggle), `dw:f3=0|1`,
`dw:flashback=start|stop`, `dw:log=<ticks>` (`SCENARIO pall-parts` lines). No main-code change.

## 7. Rig, deviations, repeat
### What ran
- **Server**: the full the pack 0.3.1 server (packwiz `-s server` from the pack repository `main`, installed by its
  `tools/boot_check.py server`), NeoForge 21.1.265, `incoming-3.14.0.jar` sha256 `99fb1ee1…6ed1` (= the release), a fresh
  `cp` of the VPS `golden/Project Zearth` (world + `server_map_cache`, 2679 files, 4 939 312 118 bytes, identical count and
  size), Zearth 25566's `config/` and `server.properties` (wave_speed 0.35 CUSTOM, early_warning off), Xaero id
  289867042. Address <host>:25591, offline, Dev op 4. JVM: Zearth's G1 flags with a 4 GB heap (Zearth: 6 GB), plus
  JFR profile ring (`srv/logs/run.jfr`), `-Xlog:gc*,safepoint` (`srv/logs/gc.log`), `-Dincoming.nukeDiag=true`.
- **Client**: `tools/prod_client.py` on a fresh copy of Artem's instance (his 104 mods, config, options, shaderpacks;
  Photon v1.4 "high" with his changed option, `nuke_dust_quality = MEDIUM`, render distance 10, maxFps 240), his Zearth
  Voxy store and Xaero maps copied under the test address (so the client arrives preloaded as he did), 2560×1600 window
  (fullscreen off: the nested screen would change the size), his JVM (`-Xms12288m -Xmx12288m -XX:+UseZGC
  -XX:+ZGenerational`, the runtime he uses), the worktree's scenario build of 3.14.0 (origin/main has no `mod/src`
  change since `v3.14.0`) plus the probe steps below. Pall ladder as in play: `-Dincoming.pall.adaptive=true` and
  `dw:rung=-2`.
- **Scenario** (Artem's Zearth sequence): creative at −1069.5 68.5 −293.5 facing ground zero; 2 min after the join
  `time set 1000`, Flashback recording started (Artem 10:12:31), `incoming nuke at 2475 30 -14 300 ground from 1681
  -2694` (the server snapped it to y 73, flight 91 s as his); +35 s spectator; +100 s at −346.37 98.13 −100.36 facing
  −100.2/−8.7; +148 s Flashback stopped and saved (Artem 10:16:29/10:16:30.9); 12-frame bursts at +155/+237/+297 s;
  F3 open +245…+305 s; at +360 s the wave held at its age (`dw:freezeage=1`) for the per-term switches; the shader pack
  toggled off for vanilla frames; pall OFF/MEDIUM A/B at the end. The full step list: `run-2/probes/commands.txt`.
- **Probes** (all recorded; checked at start): client JFR profile ring (`client/logs/run.jfr`) + 5-min `JFR.dump`s
  (`probes/jfr/`), client `gc.log` (gc*, safepoint), hitch probe (`client/logs/hitch-*.txt`: every frame, render-thread
  stacks of stalls > 100 ms, 5 ms render-thread samples while live, packets per tick with wire bytes, per-thread
  schedstat every 250 ms, client state per tick), `SCENARIO nuke-layer` (0.5 s) and `SCENARIO pall-parts` (1 s, run-2),
  `SCENARIO frame … pall` per burst frame, spark profilers (server and client, saved to file), per-thread CPU and
  run-queue wait of both JVMs every second (`probes/{client,server}-threads.csv`), perf per-thread instructions/cycles of
  the client every 250 ms (`probes/perf-client.csv`), `nvidia-smi` 1 Hz, `vmstat` 1 Hz, run-2 heap probe (`GC.heap_info`
  every 30 s, live class histograms at join+110 s and detonation +140/+175/+330 s: `probes/heap/`).

### Deviations (each with its reason)
1. **Server on the laptop, not 25571 on the VPS.** the VPS had 8.2 GB available with the three play servers up
   (25565–25567, not ours to touch); a full-pack server of Zearth's heap would leave < 4 GB (team rule). The only 25571
   server is the Almighty teaser's ("Zearth Teaser", its README: stopped for the same memory reason). The laptop server is
   pinned to CPUs 12–15 (cores 6–7; `taskset`), so the client keeps 12 threads to itself; measured server load during the
   show 2.9–3.7 CPU-s/s. Heap 4 GB instead of 6 GB (agents.slice 22 GB with the 12 GB client). Server numbers stayed
   close to Artem's Zearth (run-2 tick avg 16–31 ms against his 18.5–31.5; Voxy changed-chunk LODs 5 900–11 300/min against
   11 000–11 600).
2. **Run-3, an extra short take** (the artifact only, same rig and sequence without Flashback recording, switches at
   +150 s with the wave's age held): run-2's switches were scheduled at +360 s, after the artifact had gone; my planning
   error, not a new question.
3. **Run-1 spoiled after +148 s** (probe defect): Flashback's stop opened its "Save Replay" dialog (Artem's
   `quicksave: false`; he clicked Save 1.9 s later). Data from −4 min to +148 s is valid and used; run-2 is the take of
   record, with `quicksave: true` (finishing goes straight to the same export) and the fixes below.
4. Run-1 had `-Dincoming.fx.drain=true` (LayerTimes drain frame every 2 s, what F3 does) all the time; run-2 has it only
   while F3 is open (+245…+305 s), so F3's own cost splits out. Run-2 dropped spark's tickmonitor (it posts every slow
   server tick into the client chat).
5. Artem's world had been played (zone chunks on disk 34 857 at detonation); the golden copy has 7 189 (fewer pre-built
   ruins to send). Not a factor in the client numbers below (ruin work is server-side).

### Repeat identically (before/after a fix)
```sh
A=<laptop>/agents/hotfix-2811-repro
WT=<incoming worktree with the fix + this branch's devtest steps>   # probe/hotfix-2811-repro adds dw:key, dw:f3, dw:flashback, dw:log
RUN=$A/run-N; mkdir -p $RUN/server
# server install (once; or rsync -a an earlier run's srv/ without world, world-bootcheck, server_map_cache, logs*)
(cd <the pack repository worktree at origin/main> && JAVA_HOME=… python3 tools/boot_check.py server $RUN/srv)
rsync -a -e "ssh -o HostName=<host>" "<server>:<golden> Zearth/" $RUN/server/
cp $A/run-2/{setup_server.sh,run.sh,heap_probe.sh,threads_probe.py,analyze.py} $RUN/
mkdir -p $RUN/artem && ln -s $A/run-1/artem/the VPS-zearth $RUN/artem/the VPS-zearth
bash $RUN/setup_server.sh $RUN
# client: a fresh instance copy with Artem's Zearth caches under the test address, fullscreen off, Flashback quicksave
python3 $WT/tools/prod_client.py --copy-only --dir $RUN/client
K=$A/artem-caches-2811; C=$RUN/client     # Artem's Zearth caches as of 28.11 10:23 (copied before any of our runs)
sed -i 's/^fullscreen:true/fullscreen:false/' $C/options.txt
mkdir -p $C/.voxy/saves $C/xaero/minimap $C/xaero/world-map
cp -a $K/voxy-<host>_25566 $C/.voxy/saves/<host>_25591
cp -a $K/xaero-minimap/Multiplayer_<host> $C/xaero/minimap/Multiplayer_<host>
cp -a $K/xaero-worldmap/Multiplayer_<host> $C/xaero/world-map/Multiplayer_<host>
python3 -c "import json,sys;p=sys.argv[1];d=json.load(open(p));d['recordingControls']['quicksave']=True;json.dump(d,open(p,'w'),indent=2)" $C/config/flashback/flashback.json
nohup bash $RUN/heap_probe.sh $RUN > $RUN/probes/heap_probe.out 2>&1 &   # mkdir -p $RUN/probes first
bash $RUN/run.sh $RUN $WT          # queues for the GPU lease; ~30 min of GPU (run-3/run.sh: the artifact take, ~15 min)
python3 $RUN/analyze.py $RUN       # → $RUN/analysis/analysis.md, buckets.csv
```
`artem-caches-2811/` keeps Artem's Zearth Voxy store and Xaero maps as both runs started from (his instance changes as he
plays). The instance copy itself (mods, config, options, shaderpacks) comes from his live instance: if the pack updates,
copy `run-2/client/{mods,config,options.txt,shaderpacks}` instead.

### Budget used
Wall 10:37–13:00; GPU ≈ 39 min (run-1 10, run-2 18, run-3 11) of 60; laptop CPU within the job units (server pinned to
4 threads). The GPU was shared with hotfix-2811-survey by lease order (agreed with that session).

## Knowledge
Gauge trap (F3 / fx.drain drain frame in frame-time percentiles) added to the deep-debug skill: draft PR #811 (docs only; the lead decides review and merge).
