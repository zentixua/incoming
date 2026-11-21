# Mission dust-wall-pass-2, phase 2: finish probe B (Incoming, next release)

Look target: LOOK.md (binding), scene and checks: ACCEPTANCE.md. Phase 1 report: mod/run/agents/dust-wall-pass/report.md,
judge.md, judge-r2.md; probe B branch dust-wall-p1b. Artem picked B (soft particles with real-dust textures).

## Goal (Artem's words, 21.11)
A small hump, the wave rolls on and settles right away, and the mass reads as real dust. The look wins: do not tie
together what conflicts; physics only as visible inputs. No blockers left, only time to polish.

## Decisions already taken (Artem, 21.11)
- Base: probe B. Keep its material (matte, ragged, real-dust CC0 flipbooks) and its cost (1-2 ms, limit 7 ms).
- Missing and to build: the front itself. Dust torn up at the contact line as a ragged, uneven rise, not flat equal
  ovals; a crest 1.3-1.5x the layer behind it, uneven along the front; rolls over and settles in about 1 s.
- Handoff to the old pall is a visual crossfade only. If the seam shows, the new model owns the whole visible pall in
  the judging view, including its height (the pall rising to ~157 m above a 120 m camera from ~7 s is not a law to
  keep; a presentation height marked "presentation, not physical" in code and guide is allowed).
- Physics that fights the look is removed from this effect, not reconciled (AGENTS.md "never glue").
- Judging view stays Silo (4.5 km, 120 m, low sun). Add a second view looking back over the passed wall to judge
  settling (the Silo camera faces ground zero and cannot see it). The pall's own 45-100 ms frame cost is a separate
  optimization task, not this mission, unless this mission replaces the visible pall.

## Work
1. Draft PR (reuse #724 or new from dust-wall-p1b, branch from origin/main), number in `PR`.
2. Front model in the fast harness first, then a confirming game take.
3. Every 2-3 h: frames at 1, 2, 4, 6, 8, 12 s and a 30 fps clip in both views beside silo-texture-c, frame cost; an
   independent fresh-session check by eye against LOOK.md; only a "yes" clip is published (private claude.ai artifact by
   the laptop lead) for Artem. Fix only what he names afterwards.
4. Stop rule: 3 rounds without a measured shift toward the look, or the timebox → stop, root cause from data, decide.
5. Merge only when Artem approves a clip in words, the highest preset stays within 7 ms, the 100-strike bench is in
   budget and the cross-vendor review has no serious finding. Guide (EN+RU) notes the presentation model.

## Limits
No release, servers, or Artem's game/instances/settings. Do not reuse #707's path.

Budget: time 12h, cpu 12h, gpu 4h
