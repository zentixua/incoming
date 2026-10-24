# WV revision 3: bounded shimmer, dust that settles

Evidence for PR #425 after Claude review round 2. Do not merge this orphan evidence branch into production.

Same stand as revision 2: 50 kg TNT Shahed, ground hit, seed 7, flat grass, day; rain at 50 m. Standing creative
player, camera distance 20/50/100 m, height 1.62 m, bearing 0°, look height 3 m. Native GPU dev sandbox, no Iris,
framebuffer 1280×692, quiet power profile (not a performance benchmark). VSync off / 60 FPS capture fixture as in r2
(`capture-settings.patch`, dev-only, not in the PR). Source head and main: `source.json`. The captures were taken at
`93048d54` on main `cd310f2b`; the PR head is the same WV code rebased onto main `9364394a` (#424 booster nozzle glow,
#427 nuclear stem foot), which does not touch ordinary blasts. Main and R2 rows are the r2 captures (`../r2/main`,
`../r2`) from the same stand.

Ages printed on every sheet are the actual frame ages from `capture.json`, not the requested times.

| File | What it shows |
|---|---|
| `dome-crop.jpg` | First frames at 20 and 50 m: R2 white dome, R3 none. The ordinary-front shimmer is limited to 3 px after projection. |
| `compare-20/50/100.jpg` | main / R2 / R3 in the first tenth of a second: the ring of lifted dust runs outward with the front. |
| `settle-20/50/100.jpg` | R2 / R3 from 0.15 to 3 s: R2 dust vanished by 0.3 s; R3 dust stays and settles over 1–2 s. |
| `bagels-50.jpg` | R2 / R3 at 0.08 and 0.1 s: R2 charcoal puff centres; R3 soft backlit centres. |
| `horizon-lines-50.jpg` | Diagnosis of the thin lines over the horizon (rows: before the fix; shimmer off; dust off; after the fix). |
| `rain-50.jpg` | Saturated air: condensation forms behind the shock and evaporates (unchanged from R2). |
| `frame-intervals.csv` | Frame intervals around the blast birth, main / R2 / R3. |
| `wv-*.mp4`, `main-wv-50.mp4` | Real-time first two seconds from the actual frame intervals, with the unnormalised blast audio. |
| `d20`, `d50`, `d100`, `wet50` | Selected original PNGs, the 4 s 48 kHz WAV, `capture.json` and all frame ages. |

What changed and why:

- **Dome.** At display gain 24 the conventional front's refraction pulled samples tens of pixels into the flash-lit
  ground next to the fireball. The shader now limits the summed ordinary-front displacement to 3 px after projection
  (72e1bacc). The physical jump, radius and timing are unchanged, as is the nuclear front.
- **Dust that vanished.** The 8-phase envelope is gone (72e1bacc): the lifted dust leaves only by settling
  (120 µm bin of `DustModel`, 0.745 m/s from a 1.3 m mixed layer), drifting with the wind and spreading by Briggs.
  At the column depth chosen there (0.08) the ring was not visible at all; R3 uses 0.5 (presentation), with at most 0.8
  per puff at the running edge, where the puff is still compressed behind the front. Puffs grow from the ground.
- **Bagels.** `FrontDust` passed its screen colour where the puff renderer expects albedo, so the thick layer was
  grey and the puff centres dark. It now converts the colour like every other source. The colour is light dry soil
  dust, single-scattering albedo about 0.9 (estimate). The remaining soft darkening of a centre is the fireball
  backlighting a puff: thin edges let more light through than the thick middle.
- **Thin lines over the horizon.** They came from the baked-puff atlas, not from the shimmer: `sixFrames` kept the
  sample half a sheet texel from the frame edge, so on mip levels 3–4 bilinear filtering read the neighbouring frame.
  Flat dust layers seen almost edge-on and distant puffs showed it as lines. The inset is now half a texel of the
  coarsest level read (`sixInset`, `PuffAtlasEdgeGlslTest`). This touches every baked puff.
- **Birth hitch.** The torn-litter kick integral uses an exact step for the mid-step wind: 32 steps instead of 400,
  within 0.2 % of a fine Runge–Kutta integral. First frame interval R3 / main / R2: 23 / 55 / 65 ms at 20 m,
  24 / 22 / 38 ms at 50 m, 27 / 26 / 35 ms at 100 m.

Not changed: the condensation cloud still reads as round puffs. Its cells are always limited by the 4σ-behind-the-front
rule, so they are small next to the 0.36 r spacing of 48 cells on the hemisphere; a smooth shell needs a different
primitive than Gaussian puffs. Its physics (shock heating, isentropic expansion, Magnus, latent heat) is unchanged.

Scripts: `capture-r3.py` (sandbox commands), `assemble-r3.py` (sheets, movies, intervals), `sheet.py`, `probe.sh`.
