# PR 426, review round 2

Iris 1.8.14 + Complementary Unbound r5.9.3, quiet laptop power profile.
Base b4073c12; feature d8548eb1. Each capture includes raw PNG, WAV and actual ages/camera in capture.json.
Main-art controls swap only the old main fireball PNG/JSON into the same round-2 runtime: same fixed upright pose,
new 256px atlas slots, frame interpolation and small source bloom. They isolate texture colour/alpha/light changes;
they are not a full checkout of main. The original main runtime capture remains under /v1 in the same evidence tree.
The round-1 row is the archived first-review capture (base 3a8b6eb1 / 4498ecfc), with the old random sprite rotation.
Upstream DU changed surrounding dust since that capture; V1 does not claim those dust changes.
Client-only blast cases use seed1, real bomb strikes use random server seeds at separate fresh sites in the same flat world.
Bombs are chosen surface detonations, not flight/penetration tests. 3.4.1 and hero references remain under /v1, with
other terrain/camera framing. See the PR for author-mod comparison and limits.

Bomb and missile animations/filmstrips: requested 0.05..3.00 s, step0.05 s; actual ages remain in the raw capture files.
No audio changes in V1; WAVs are unmodified recordings. Credits remain /v1/SOUND-CREDITS.md.

The final rebase onto cd310f2b adds upstream water effects and slab/front shielding fixes. These do not change
the V1 land-fireball renderer or its packed art. Final feature head: 7d650284. The first round-two drone capture
after a real-bomb teleport had unsettled terrain; only that affected case was replaced after returning HOME
and settling chunks. Maximum frame lateness in both 60-frame movies is 0.015 seconds.
