# PR #362 — look gate

Probe scenes (`tools/look_gate`, `ProbeScenario`) on the laptop (RTX 4060, Iris + Complementary Reimagined, DH,
1920×1080). Not for merging.

- `sheets/sam-*.jpg` — the SAM scenes, rows: v3.4.1 (last accepted release) and this branch, same seed, places and
  time (the SAM on a platform above the forest, so both versions launch at the same moment). `sam-burst.jpg` has no
  v3.4.1 row: in neither version did the Shahed reach the SAM at that site.
- `sheets/drone-*`, `grad-*`, `launch-*` — the conventional probe scenes, rows: v3.4.1, V (#358, runs V7–V9) and this
  branch: the baked puffs now shade by the albedo of their matter (white alumina smoke is lit through, dust a little
  lighter, soot a little darker).
- `wav/` — scene audio from the launch or burst, v3.4.1 and this branch.
