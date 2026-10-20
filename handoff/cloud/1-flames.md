# Session 1. Engine flames and trails by propellant physics

Area rules: `client.md`, `client-fx.md`, `client-models.md`, `client-scenario.md`. Laws: `ref/formulas.md` §5 (black
body, soot and Al₂O₃ optics, Briggs σ, Schmidt–Appleman). Data: `ref/passport.md` (motors and propellants). Target
picture: `ref/feel.md` (launch and flight paragraphs of each weapon). Old map: `ref/code-map/visual.md` §3–4, §6.

## Goal

The flame of every motor and the smoke it leaves come out of the motor's physics, one law for all, with the numbers in
the passport: what burns, at what temperature, how much per second, how fast it leaves the nozzle. Today
`ClientWeaponSpec.Flame` and `Trail` (one spec near and far since #346, good) hold hand-picked lengths, widths, colours,
brightness and ribbon lives.

## What the player should see (from `ref/feel.md`)

- Tomahawk and Grad boosters, ICBM stages (composite solid propellant with aluminium): a white-hot core, orange edge,
  bright enough to light the ground and smoke at night; thick white smoke (Al₂O₃) for the burn time only; Mach diamonds
  only where the jet is under-expanded at that altitude.
- Shahed launch booster: short bright flash and a white puff at the rail; in cruise the piston engine shows no flame
  and no visible exhaust by day.
- Lancet: no flame and no smoke at all (electric motor).
- Tomahawk cruise turbofan: invisible by day; no smoke.
- B-2: a contrail only where the Schmidt–Appleman criterion says it forms at its current altitude (at today's 170 m:
  never, the air is too warm).

## Reuse (do not rebuild)

`ClientWeaponSpec.Flame`/`Stage`/`Trail` (one source for `PlumeRenderer` near and `FarFlightView` far — keep that
property, it fixed Artem's "flame vanishes at render distance" complaint, #346); `BlackBody` (`nuclear/model`) for
colour by temperature; `FxLights` for light of hot sources on particles and ribbons; the mushroom fireball's light on
the world (`MushroomField.balls`) as the model for flame light at night.

## What to do

1. **Motor data in the passport** (`ClientWeaponSpec`, client-side; read flight speed from the entity, never from
   here): propellant kind (composite with Al, double-base, JP-10 turbofan, petrol piston, battery), chamber/flame
   temperature, mass flow and exhaust velocity or thrust and burn time where known (sources in `ref/passport.md`; what
   is marked "уточнить" there stays a marked estimate with a range).
2. **Flame model** (pure class, unit tests): visible length from thrust and exhaust velocity (a marked estimate law:
   say which one and its error), width from the nozzle, colour and luminance from `BlackBody` at the flame
   temperature (afterburning of the fuel-rich plume included or explicitly left out), Mach diamonds from nozzle exit
   pressure versus ambient pressure at the current altitude (standard atmosphere). `Flame.bright(...)` hand numbers go.
3. **Flame light**: a burning booster lights terrain and smoke around it at night with the luminance from step 2
   through `FxLights` / the fireball-light path, limited the same way as the nuke's balls.
4. **Trail model**: smoke mass per second from propellant flow × condensed-product fraction (Al₂O₃ for composite
   propellant, soot for others, none for battery/turbofan by day); optical thickness from that mass (`formulas.md` §5);
   spread σ_y, σ_z by Briggs for the stability class of the hour (day unstable, night stable — the same day/night the
   nuke's sound uses in `Outdoor`); drift with one wind (see below); rise of a hot trail by buoyancy. `Trail` hand
   numbers (life, widths, alpha) go: life is when optical thickness falls below visibility.
5. **One wind**: smoke must drift with the same wind the nuke uses (`Detonation.windDir/windSpeed`; find where the
   server chooses it). `Fx.WIND_X/Z` are a placeholder (0.044 m/s). If you need a wind without a detonation, lift the
   choice into one small client-visible source and use it for both; do not add a third wind. Wind only moves smoke and
   flame, never a projectile (flight is not yours).
6. **Contrail**: Schmidt–Appleman criterion from altitude temperature (standard atmosphere) and humidity (Minecraft
   weather: rain or thunder means humid; a marked estimate), for the B-2 and the ICBM; replace the always-on cruise
   contrail.
7. Far and near stay one: `FarFlightView` and `PlumeRenderer` draw from the same model output; frames across the
   render-distance edge prove it (the old complaint must not come back).

## Do not

- No `switch` by entity or weapon for flame or trail; everything from the passport.
- No white smoke where the propellant gives none; no flame on the Lancet or the cruising Shahed.
- Do not make launch clouds or trails a volume yourself: session 5 moves them into the smoke grid after session 2.
  You own the model numbers (mass, optics, spread); session 5 uses them.
- Do not touch launch timing, boost duration in flight, speeds or trajectories (`LaunchProfile`, `Ascent` are read
  only).

## Checks

- Unit: flame colour equals `BlackBody` at the given temperature; diamonds appear only when exit pressure differs from
  ambient beyond the stated ratio; trail width follows Briggs σ(t) for two classes; contrail threshold at two altitudes;
  no trail for battery and turbofan by day.
- Headless client: `launch`, `rocket`, `loiter`, `far-models`, `fx-night`; frames across the render-distance edge by
  day and night in the PR; GPU time of the effects layer from the scenario log.
- Guide: flames and trails in `docs/guide` (EN, RU) where they are described.

## Done when

Flames, their light, trails and contrails come from the passport and laws; no hand numbers for them remain; near and
far agree.
