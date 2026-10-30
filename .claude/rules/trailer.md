---
paths:
  - "tools/trailer/**"
  - "mod/src/devtest/java/ua/zentix/incoming/scenario/trailer/**"
---

# Trailer

## Pitfalls
- Trailer: while the plan's camera waits for chunks to load, the scenario freezes the world (`/tick freeze`); otherwise
  the projectile reaches the target before recording starts; while frozen, the whole mod stops (`StrikeWorld`: salvos,
  projectiles outside the world, explosions). The frame is exactly `INCOMING_SIZE`: the nested KWin screen is larger
  than the window, the window has no frame (`Trailer.exactFrame`); otherwise recording will not start.
- Trailer: in hidden plans an invisible entity follows the camera (`followCamera`), so the camera waiting for a
  projectile must stay where it will re-enter the world (`CineCamera.chase` with a start point); otherwise chunks at the
  target unload and the projectile outside the world (B-2) never appears. Onboard video starts only when the missile is
  in the world; recording runs with a simulation distance of 16 chunks.
- Onboard video under Complementary: in the morning (`time 1000`) the shader's light rays (volumetric light, several
  times weaker at noon, stronger on the way to the low sun) cover the whole frame with a pinkish-white pall, even 70
  blocks from the target; distance fog (render distance 12: ~60 % at 100 blocks) adds whiteness far away. A player
  underwater or on land makes no difference. Check: `tools/client_scenario.sh onboard shaders`: frames onboard-dry
  (noon), onboard-wet (the aimer underwater), onboard-dawn (morning); without shaders the frame is clean. The trailer's
  onboard plan is shot at noon with render distance 24, and the edit takes video from the “close” mark (150 blocks to
  the target). Volume puffs at the projectile's plume, on which the camera rides, are placed with a one-tick lag
  (`Exhaust.volume`): otherwise the last stretch lands in front of the lens. Game language during filming is
  `INCOMING_LANG`; plans with game text in frame (`.hud()`) are shot in en_us and ru_ru, `edit.py` takes the take by
  `--lang`.
- Trailer: sound is assembled from the log (`Recorder`), and the engine reports a sound only at start: loops started
  before the plan (the projectile's motor since launch): `Recorder.start` writes them into the log itself; without this
  the chase plans have no motor.
