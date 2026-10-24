# TL2 evidence: SAM trail "swells and vanishes" (final 3.7.0 pass)

Not part of any PR diff. Frames come from the effects sandbox (`tools/sandbox.sh`, Iris + the host shader pack,
1280x720), main = `5da7f6c0` (sandbox code swapped back to main for the in-cloud baseline), TL2 = the PR branch.

## 1. Geometry of the final-pass scenes (no rendering involved)

World wind (`util/Wind`, `util/Air`), `wind_tl2.py` reproduces it:

| scene | game time | wind at 10 m | at the trail (37-67 m above sea) | blows toward | stability |
|---|---|---|---|---|---|
| probe sam-side | 22313 | 7.7 m/s | 8.8-9.2 m/s | +x 0.78, -z 0.62 (to the east-north-east) | C |
| probe sam-burst | 25949 | 7.6 m/s | 8.7-9.1 m/s | +x 0.79, -z 0.62 | C |
| sandbox `sam` | 6000 | 6.6 m/s | 7.3-8.3 m/s | +x 0.75, -z 0.66 | C |

Both final-pass cameras stand downwind of the trail and look upwind:

- **sam-side**: camera 45 m east of the SAM, looking west. The launch cloud (`LaunchCloud`, MTT thermal) drifts
  (17, -13) m by 3 s, (29, -23) m by 5 s: at 5 s its centre is 16 m in front of the camera plane, 23 m to the right,
  10 m up — 30 m away, radius 9.1 m, about 36 degrees across: the "huge white wad" in the upper right. By 8 s it is
  3.7 m behind the camera plane and 39 m to the right: out of the frame. The trail column does the same.
- **sam-burst**: camera 59 m east of the burst, looking west. The trail between the SAM and the burst drifts
  (7.15, -5.6) m/s: at 6 s it lies 14-27 m in front of the camera across the whole frame (the white wall), it crosses
  the camera plane at 8-10 s and passes within metres of the eye, at 12 s it is 16-29 m behind the camera. The
  black burst cloud: 18 m ahead and 18 m right at 6 s, 25 m behind the camera at 12 s.

Measured on live trail points in the sandbox with the same wind direction (`main/sandbox-diag.log`, lines
`TL2DBG`, camera 45 m east of the SAM): nearest trail point 35.6 m at 3 s, 33.2 m at 5 s, then it passes to the right
and behind; trail points inside the view cone 15 / 12 / 1 / 0 at 3 / 5 / 7 / 8 s; all 88 points stay alive to 30 s,
centre opacity of the nearest one 0.96 at 8 s, 0.74 at 30 s. So the trail does not disappear: the wind carries it
past the camera.

Width by law (`TrailModel.look`, SAM boost trail laid at 60-150 m/s, class C): 6.8-10.4 m at 3 s, 8.6-13.4 m at 5 s,
10.8-16.9 m at 8 s, 15-23 m at 15 s. The growth is the rise of a buoyant line thermal (radius alpha*z, Turner 1973,
ch. 6, alpha 0.5); the Richardson-Obukhov part is sigma_r = 0.14 m at 5 s. Physical growth 3 -> 5 s is x1.3; the rest
of the "swelling" is the trail coming from ~45-60 m to ~15-30 m from the camera.

## 2. What was a defect: the frame while the trail passes through the camera

The final pass has no frame between 6 s and 12 s. Sandbox, camera 60 m downwind of the SAM, eye 30 m up, looking
upwind (`sheets/sam-burst-crossing.jpg`, PNG frames in `main/`, `tl2/`): on main the frame is a flat white sheet from
8 to 9 s (luminance SD 1.5-3.1 of 255, veil alpha up to 0.93), also above the tube. Cause and fix: see the PR.

## Sheets

- `sheets/sam-side.jpg` — side view as probe sam-side, 3/5/8/15/30 s, main / TL2 (no veil in this view: identical).
- `sheets/sam-burst-crossing.jpg` — the trail drifting through the camera, 3-12 s, main / TL2.
- `sheets/in-cloud.jpg` — camera 7 m from a Shahed blast, 0.5-12 s, main / TL2 (cloud veil path, no regression).
