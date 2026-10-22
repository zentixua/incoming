# PR 372 (F2c): launch and blast-tail levels against v3.4.1

Laptop, copy of the instance (Iris + Complementary, DH), `tools/look_gate/run.sh` with `INCOMING_LOOK_HEARING=off`.
- `audio/<scene>.3.4.1.ogg` — v3.4.1, `<scene>.old-main.ogg` — main 590865eb without hearing loss (compare-final),
  `<scene>.F2c.ogg` — this PR without hearing loss. Event at 1 s.
- `loudness.txt` — `meas.py`: EBU R128 momentary max and loudness by time windows, LUFS.
- `sheets/` — frames 3.4.1 (top) and this PR (bottom). The picture is not changed by this PR. In these takes the
  camera loses the missile after ~1.5 s: other jobs kept the laptop at load 40–50 and the server stalled; the sound of
  the first 1.5–2 s (ignition) is comparable, the later seconds of launch-side/launch-night-behind are not.
