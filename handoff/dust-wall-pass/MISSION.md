# Mission dust-wall-pass, phase 1: two probes, two clips (Incoming 3.14.1)

Supersedes any earlier dust-wall-pass MISSION.md ("port v6 one to one + fallback bake"). Look target: LOOK.md in this
folder (binding, Artem 21.11 17:30-17:50 Warsaw); scene and checks: ACCEPTANCE.md. SPEC.md and the v16 animation are a
sketch: take their method where useful, never copy them 1:1.

## Goal (Artem's words, 21.11)
The nuclear dust wall: a small hump, the wave rolls on and settles right away, and the mass reads as real dust, not
liquid metal or water. Do not tie together what cannot be tied together properly; do it the way others would, with
a model of its own where the presentation matters, not the physics. Maximum chance of success, no repeat of #707.

## Decisions already taken (Artem, 21.11)
- 3.14.0 ships with the 3.13 wall; this goes into 3.14.1. No release pressure.
- The young wall (front to ~10 s behind it) is ONE presentation model (own hump, rise, settle, height), marked once as
  presentation/not physical in code and guide. Physics only as visible inputs: front position and time, where and how
  much dust is born (ground type), wind direction and decay, plausible overall scale. Behind it the existing pall takes
  over (physics). No law-then-override (#707's path is stopped; do not reuse it).
- Hump 1.3-1.5x the layer behind it, uneven along the front; settles in about 1 s; dust born at the contact line.
- Judge only in the Silo scene (4.5 km, 120 m, low sun) beside silo-texture-c; colour follows the light.
- Up to 20 FPS cost for one wall on RTX 4060 at the highest preset; 100-strike bench limit still holds.

## Work, in this order
1. Draft PR first (incoming, branch from origin/main), number in `PR`.
2. Judging camera's first frame matched to silo-texture-c (horizon, ground-zero position). Nothing else before this.
3. Two probes in parallel, timebox 4 h each from step 2's end, both in game:
   A. a dedicated dust-wall volume pass (dust-top heightfield + density, ray-marched) using the animation's method,
      not the shared pall renderer;
   B. soft particles with real-dust flipbook textures (CC0/compatible license), born at the contact line, hump, settle.
   Iterate shaders in a fast harness (seconds per try); the game only for confirmation takes.
4. For each probe: frames at 1, 2, 4, 6, 8 s and a short 30 fps clip in the judging view, side by side with
   silo-texture-c; frame cost on the highest preset.
5. Independent check of both clips by a fresh session that built neither, against LOOK.md (four traits, never-glue)
   and the Silo frame. Only a "yes" clip goes on; a "no" gets one fix round inside the timebox.
6. Gate at 4 h: if neither probe shows the four traits, stop and write report.md with frames and the measured cause;
   no tuning rounds.
7. Write report.md (clip and frame paths, FPS cost per probe, check verdicts) and DONE. Artem picks A or B from the clips;
   phase 2 (finish the picked one, clip every 2-3 h, merge) is a new mission on his word.

## Limits
- No merge in this phase. No release, servers, or Artem's game/instances/settings.
- Do not touch #719 (3.14.0 candidate) or its files; it runs in parallel. #707 stays unmerged.
- Stop rule: 3 rounds without a measured shift toward the look, or a timebox overrun → stop, root cause from data, decide.

Budget: time 10h, cpu 10h, gpu 4h
