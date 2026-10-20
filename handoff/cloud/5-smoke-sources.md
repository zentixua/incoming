# Session 5. Launch clouds, near trails and fire smoke in the volume

**Start only after session 2 ("The explosion as a volume") is merged into `main`**: you feed its smoke grid. If
session 1 (flames and trails) is merged too, use its trail model; if not, write the smoke-mass law for exhaust here and
session 1 will reuse it (say so in the PR).

Area rules: `client.md`, `client-fx.md`, `nuclear.md`, `client-scenario.md`. Laws: `ref/formulas.md` §5. Data:
`ref/passport.md`. Target: `ref/feel.md` (launch paragraphs, aftermath of Shahed and Tomahawk).

## Goal

All remaining smoke becomes volume through the grid session 2 built: the launch cloud at the rail or the launcher
(Shahed booster, Tomahawk booster, each Grad round, the ICBM), the near part of booster trails, and smoke over fires
after a strike. Particles stay only for sparks and solids.

## What the player should see

- Grad salvo: the launcher drowns in smoke and dust for the 20 s of the salvo; the clouds hang and drift with the wind.
- Tomahawk: a thick white booster trail for the burn time that hangs and spreads.
- Shahed launch: a short white puff at the rail.
- After a Shahed or Tomahawk: a column of smoke over what burns, drifting, lit from below at night; seen from far.

## Reuse

Session 2's grid API and cloud model; session 1's trail optics (mass of Al₂O₃ or soot, Briggs spread) if merged;
`FireStorm` and its fire map in `PallPass` (how the nuke draws smoke columns over a burning city and glow from below).

## What to do

1. **Launch cloud**: from propellant burned at the launcher (mass × condensed fraction), its heat and the ground dust
   the jet lifts (a marked estimate) → a cloud in the grid that rises by buoyancy, drifts and spreads like any other.
   `Exhaust`'s launch-cloud particles go.
2. **Near trail as volume**: inside the grid, the booster trail is volume with the same mass and spread law as the
   ribbon; beyond the grid the ribbon stays and agrees with it at the edge (frames across the edge in the PR).
3. **Fire smoke**: the client sees fire blocks in loaded chunks itself; group them into sources on a coarse grid (as
   `FireStorm` does), heat per burning block a marked estimate with a source, → plume rise by Briggs / MTT, drift,
   spread → clouds in the grid; glow of the fire from below by temperature. No new packet unless measurement shows the
   client cannot see it (then say why). Where fires come from is the server's business (local package): smoke follows
   whatever burns.
4. Particles of `Exhaust` and fire smoke that the volume replaced are removed.

## Do not

- Do not change ignition, fire spread or damage on the server.
- Do not change the nuke's fire storm; share its machinery only.

## Checks

- Unit: launch cloud rise against MTT; fire plume rise against Briggs for two winds.
- Headless client: `rocket` (Grad salvo at the launcher), `launch`, `fx-late`, `firestorm` (unchanged), a new night case
  "smoke over a burning house after a Shahed"; frames and GPU time in the PR.
- Guide, EN and RU, where launches and aftermath are described.

## Done when

No smoke is drawn as sprites; launch clouds, near trails and fire smoke come from the same volume and laws as the
blasts; the budget holds.
