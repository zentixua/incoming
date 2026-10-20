# Session 3. Flight sound by engine and propagation

Area rules: `client.md`, `client-sound.md`, `client-scenario.md`, `strike.md` (only for `Hearing` and `FarFlights`),
`gametest.md`. Laws: `ref/formulas.md` §6 (source levels, ISO 9613-1/-2, Doppler, Mach cone, Carlson N-wave).
Data: `ref/passport.md` (engines, power, RPM, propellers). Target: `ref/feel.md` (flight paragraphs). Old map:
`ref/code-map/sound.md` §1–3, §6–9. Earlier sound work and its traps: `.claude/rules/client-sound.md`.

## Goal

Each weapon's sound comes from its engine and its real speed: spectrum from the engine kind and RPM, loudness from the
source's sound power, how far it is heard from propagation (air absorption, ground, refraction by day and night,
terrain), not from the hand-set ranges in `strike/Hearing` (ENGINE 300, JET 1500, LOITER 200, …). A supersonic source
is heard after its Mach cone passes, with the N-wave crack first.

## What the player should hear (from `ref/feel.md`)

- Shahed: a rattling two-stroke "moped" with the tone of cylinder firings and propeller blades, heard for kilometres on
  a quiet night (from 3 km about 50 s before it arrives at today's speed: derive, do not hard-code), less by day
  (upward refraction).
- Lancet: a thin whine of the electric motor and propeller, gone beyond a few hundred metres; it rises in the dive.
- Tomahawk: booster roar at launch; in cruise the turbofan, heard only seconds before arrival because the sound lags.
- Grad: launch roar arriving with the real delay; supersonic rounds (once the local package makes them ~690 m/s) crack
  before their howl.
- B-2: a faint low rumble long after it passes.

## Reuse

`Outdoor` (ISO 9613-2 in two bands, day/night refraction — tie it to the same hour the smoke uses), `SoundFilters` and
`OwnSources` (OpenAL EFX), `Acoustics.emissionTime`, `EngineSound` layers, `Ducking`, `VoiceBudget`. The nuke's wind
behind the front is unrelated; do not touch `NukeSounds`.

## What to do

1. **Source model in the passport** (`ClientWeaponSpec` sound part; flight speed from the entity): sound power level
   by engine law — solid motor: a fraction of the jet's mechanical power (NASA SP-8072); propeller and piston: blade
   passing and firing frequencies from RPM and blade/cylinder count, level from a recording at a known distance;
   turbofan: from flyover recordings at a known distance; electric: motor and propeller tones. Spectrum in the two bands
   `Outdoor` uses. Each number with its source or a marked estimate.
2. **Audible range = where the level at the listener falls under the background**, computed in common code (not
   client-only) so the server's `FarFlights` (who receives a flight path) and the client agree. The constants in
   `strike/Hearing` become derived values; a test checks that the derived ranges keep the order of the old ones where
   those were right.
3. **Loudness on the client** = source level − propagation (`Outdoor` bands, ISO 9613-1 air absorption, occlusion),
   without ref/floor/cutoff curves. Minecraft caps gain at 1: one reference level for the whole mod plus file level.
4. **Supersonic**: `emissionTime` finds both roots inside the Mach cone; an N-wave event (short double crack) with
   pressure and duration by Carlson; Doppler without the 0.9c clamp where the source is supersonic (sound "from the
   past"). Test with synthetic speeds: no weapon flies supersonic in today's flight logic.
5. **History by delay**: the per-projectile history the emission time searches is kept by delay time (up to ~60 s for
   a B-2 at 12 km and a Grad launch at 20 km), not 256 ticks.
6. **Recordings** (`tools/build_sounds.py`): loops levelled by LUFS to the source level (not RMS); check the Shahed loop
   (a Trabant recording sped up ×1.42) against the real MD-550/L550E firing and blade frequencies and fix the speed-up
   from the numbers; the Lancet must be an electric motor and propeller, not a glow-engine RC plane (replace the
   recording if it is: CC0 or CC BY 4.0 with attribution, as now; the Freesound token is never committed).

## Do not

- Do not raise loudness "so it can be heard": loudness comes from the source and the path.
- Never play a sound before it physically arrives.
- Do not change flight speeds, routes or launch timing; `FarFlights` may change only who receives paths (by the derived
  range), not the paths.

## Checks

- Unit: `AcousticsTest` (two roots at 1.4 M, none outside the cone, N-wave before engine sound), `EngineSoundTest`,
  `OutdoorTest`, a new audibility test per weapon (Shahed heard from 3 km on a quiet night, Lancet not from 1 km).
- GameTest `virtualFlightReachesOnlyThoseWhoHearOrSeeIt` on the derived ranges.
- Headless client `flyby-all` (approach, dogleg, pass, grad, far): the layer log with `SCENARIO check … PASS` in the PR.
  Do not send audio files to Artem.

## Done when

No hand-set hearing ranges remain; loudness and arrival time of every engine layer come from the source, its speed and
the path; supersonic sound works for any speed.
