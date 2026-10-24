# SM2 / PR #443 evidence: smoke after strikes

Sandbox frames (`tools/sandbox.sh shot`, flat world, 1280×720), the same effect, camera, game time and wind on both
branches (`blast`, `raid` and `fire` now pin the game time, commit 04dc5223). Laptop RTX 4060, power profile
performance, 24.10.2026.

- **main** — origin/main df4ee0d6 + the sandbox air fixture (04dc5223), rows `base`/`basei`.
- **new** — PR #443: `v2`/`v2i` = final code (7bbc7a46); `v1`/`v1i` = fed48f24 (new puff sheet, before the glow-share
  commit). The glow-share commit changes only the first seconds of a fireball (its smoke share), so `v1` rows of fire,
  fire at night, water, launch and SAM are the final look; `v1` missile at night 1 s and 3 s predates it.
- **Iris** — Iris 1.8.14 with the host's Complementary Unbound r5.9.3; other rows without a shaderpack.
- **3.4.1** — `sheets/ref341.jpg`: 3.4.1 frames from the RV2 full-pack run (`mod/run/compare-final`, another place
  and camera, Complementary + DH) next to the new sandbox frames. 3.4.1 has no sandbox, so the same moment in the same
  place is not available.

Scenes: `drone` (Shahed, 80 m, 3/10/30 s), `near` (40 m), `missile` (120 m), `fire` (16×16 fire, 10/20 s), `raid`
(16 Shaheds in 120 m, camera 30 m inside the dust, 20/55 s), `early` (missile fireball to smoke, 0.05–2 s), `earlyN`
(Shahed at night, 0.2–3 s); `N` — night. `water`, `launch`, `sam` — the other users of the same puff sheet
(spray maps re-measured, launch cloud, SAM trail).

`timings.txt` — every shot: fps, quads, GPU time of the effects layer (mean over the preceding second, longest),
smoke fill in screens. `scripts/` — shot lists and sheet tools. `sheets/old-mantaflow-puff-maps.png` and
`sheets/new-puff-frames-lit-from-top.png` — the puff flipbook before and after, lit as the layer shader lights it.

## Round 2 (review of 7bbc7a46: flat dust slab with a vertical edge in the raid at 55 s)

`round2/` — the same shots with the fixed puff sheet (PR head a7826cf9): rows **main** / **r** (the reviewed head,
`v2`/`v2i`; `v1`/`v1i` where the reviewed head was not re-shot, those rows predate only the glow-share commit) / **new**
(`v4`, `v4i`), each without a pack and under Iris (Complementary Unbound). `round2/sheets/raid-020s-main-r-new.jpg`,
`raid-055s-main-r-new.jpg` (and `raidi-…` under Iris): full raid frames main | r | new.

Cause of the slab: every frame of the reviewed sheet had a straight bottom (the cap of the EmberGen cloud was cut from
its stem by a horizontal plane), and dust puffs lie flat on the ground, so the straight edge stood up as a wall. The
first fix (cap seen from above) left an elongated footprint (axes 1.4–1.7 : 1) whose long even sides read as a
straight slanted edge on a big near puff (`v3`, not used). Final: cap from above, footprint squeezed to round in the
density data, frames 72–117 (no frame touches the simulation domain wall).

New night shots: `waterN`, `launchN`, `samN` for main and new, with and without Iris. `timings.txt` has every shot;
`round2/ab-cost.txt` — main and new raid alternating in one sitting (layer GPU time, Iris).
