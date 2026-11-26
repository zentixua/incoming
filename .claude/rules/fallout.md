---
paths:
  - "mod/src/main/java/ua/zentix/incoming/nuclear/radiation/**"
  - "mod/src/main/java/ua/zentix/incoming/nuclear/model/FalloutModel.java"
  - "mod/src/main/java/ua/zentix/incoming/nuclear/model/BlackRainModel.java"
  - "mod/src/main/java/ua/zentix/incoming/util/StrikeHistory.java"
  - "mod/src/test/java/ua/zentix/incoming/nuclear/radiation/**"
  - "mod/src/devtest/java/ua/zentix/incoming/nuclear/radiation/**"
---

# Fallout on the ground (`nuclear/radiation/GroundFallout`, `FalloutModel`, `StrikeHistory`)

Clocks and the rest of the nuke are in `nuclear.md`; the rain's own law (smoke parcels) too.

- Fallout on the ground (time.md §8.3 rule 4, `GroundFallout`). Each deposit lands at a point when its carrier brings it:
  the plume at motion age `FalloutModel.arrivalHours` (also after the mushroom is too thin to see), black-rain dust when
  its rain reaches the point (`BlackRainModel.startSeconds`, a motion age; `NuclearEvents.landingByMotion` for both, never
  the strike clock, which a vanilla day passes ahead of the rain: `probe_rain_landing`). From then it ages on the detonation's ground clock
  G (`NuclearEvents.ground`: S plus the `unseen` jumps since the burst; a night someone stayed awake for moves doses, not
  the air or the fires): ground age = landing age + G since (`FalloutModel.Landing`: arrival and the ground clock's lead
  over M at the landing, `StrikeHistory.groundLead`, found by `ageAtMotion`). One age for the whole field
  would read 1/53, 1/91 and 1/129 of the law at 13, 20 and 40 km on a vanilla day (`FalloutGroundAgeTest`). Doses step per
  source by ΔG (`NuclearEvents.stepFrom`) as the analytic integral from the landing (`FalloutModel.groundDose`; a mob's
  gap and a sleep's long step alike); body contamination ages as the youngest deposit landed where the body stands.
  `FalloutModel.FLOOR` (0.01 R/h) is the radiation floor: the history is compacted once the plume's last landing above
  it (`lastLandingHours`: 270 km, 11.24 h for 15 kt in 24 km/h wind) and all rain have landed, both on M
  (`StrikeHistory.compact`, at most 96 samples, under 4 KB; later jumps one sample each; a full history merges a jump
  into the one before it, read earlier, never later). A detonation forgotten at day 7 of S (`FORGET_AFTER_S`; its air, fires and rain are
  over) keeps its fallout as a ground field (`NuclearEvents.groundFields`: the detonation in `past` with its anchors and
  history) while its peak at its youngest ground age is above the floor (15 kt surface burst: 9.55·10⁵ world hours,
  109 world years, 1.5 real years on a vanilla day); `/incoming clear` drops it. Live detonations and fields are read
  only within `Detonation.groundReach` (`FalloutModel.boundH1`: a proven bound, `GroundFallout.NEGLIGIBLE`, a tenth of the
  floor: 15 kt reaches 344 km after a world week, 36 km after a world year), taken at the youngest ground age at the step's
  start (`GroundFallout.reach`: a mob's account over months reads every point above the cut then), so a far or old one
  costs one distance check per player a second and per mob pass; within reach one H+1 evaluation and a binary search of
  its history (0.4 µs; a reach 0.9 µs, reused while the age grows under 5 %: `GroundFalloutCostTest`). No step at day 7
  (GameTest `nuke_ground_field`), an awake night (`nuke_ground_unseen`), `NuclearGroundFieldTest`.
