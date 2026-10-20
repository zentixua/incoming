# Session 4. Blast sound, hearing and body jolt by pressure

Area rules: `client.md`, `client-sound.md`, `client-fx.md` (camera shake), `nuclear.md` (hearing, sway),
`client-scenario.md`. Laws: `ref/formulas.md` §3 (Kinney–Graham), §6 (Sachs scaling, propagation). Verified nuke
numbers: `nuke/sources-check.md`. Target: `ref/feel.md` (impact paragraphs). Shared packet change:
`cloud/contract.md`. Old map: `ref/code-map/sound.md` §4–9.

## Goal

What a conventional blast sounds like and does to the body comes from its charge and the path, with the nuke's
machinery: pressure at the listener by Kinney–Graham (`BlastModel.surfaceOverpressureKpa(range, tntKg)` and the
positive phase), the shock synthesised at the ear (`BlastPulse`), loudness and timbre scaled by Sachs (∛W), echoes from
terrain arriving at distance/speed of sound, hearing loss and ringing by `HearingModel`/`Deafness`, and the body jolt by
`BodySway` from the pressure–time history at the body, not by tick buckets (`CameraShake.blast(ticks)` today).

## What the player should hear and feel (from `ref/feel.md`)

- Lancet: a short dry crack. Grad: a sharp crack; a salvo rolls like thunder. Shahed: a heavy thump. Tomahawk: a low,
  long blow. Today the Lancet sounds like a Shahed (the packet does not tell them apart).
- Light first, sound later by distance/343 m/s (already so; keep it).
- Close to a strong blast the ears ring and everything goes muffled, recovering over game hours, by the same model as
  the nuke; at a safe distance nothing.
- The blast shoves a standing body with one sharp jolt when the front arrives (strength from pressure and impulse at
  the player); far away only the ground tremor, if any.
- Echo from hills and houses arrives late, by distance.

## Reuse (generalise, do not copy)

`BlastPulse` (shock at the ear from the model), `BlastModel` (Kinney–Graham, phase duration, wind behind the front),
`HearingModel` + `Deafness` (EFX low-pass, ringing, recovery on game hours, eardrum rupture), `BodySway` +
`CameraShake.sway(pPa, qPa, …)`, `TerrainEcho`, `NukeEchoes`, `EchoMix`, `Outdoor`, `SoundFilters`, `OwnSources`.

## What to do

1. **Shared packet** (`cloud/contract.md`): the client knows each blast's weapon and charge.
2. **Blast mix by charge**: duration and pitch of the recording scaled by (W₀/W)^⅓ from its reference charge (Sachs);
   level from the pressure at a reference distance (Kinney–Graham), then `Outdoor` bands and air absorption to the
   listener. `BlastMix`'s hand `scale` and per-kind profiles go; the near/mid/far perspectives are weighted by pressure
   and spectrum at the listener, not by hand distances.
3. **Shock at the ear**: `BlastPulse` for conventional blasts with their pressure and positive phase (milliseconds, not
   a second): near a small charge the whole pulse is audible, unlike the nuke's far front where only the jump is. Make
   the pulse model hold for both and keep the nuke's output unchanged.
4. **Hearing**: open `Deafness` to conventional blasts through `HearingModel`. Pitfall: `HearingModel` counts only the
   front's audible energy because a far nuclear wave is mostly infrasound; a conventional blast's pulse is short, so
   its full A-weighted energy is audible and the impulse-noise criteria it mentions (140 dB peak, CHABA/NIOSH) do apply.
   Derive the shift from the energy of the actual pulse, one law for both; the nuke tests stay green unchanged.
5. **Body jolt**: replace `CameraShake.blast(ticks)` buckets with `BodySway` driven by the conventional pressure–time
   history at the player (dynamic pressure from `BlastModel`), same as the nuke's lean; ground tremor (`quake`) only
   from a coupling law with a marked estimate, or remove it if no law supports it at that range.
6. **Echoes**: generalise `NukeEchoes` to any blast (arrival = path/speed of sound, level by charge); pick one of the
   two (echo sources or the stereo `blast.tail`) so they do not double; share the 12-own-source budget between blasts.
7. **Range of blast packets**: a Tomahawk blast is audible beyond today's `far_range` (8 km) on a quiet night; make the
   server's send range follow the derived audible range (common code, like session 3's ranges), not a constant.

## Do not

- Do not raise small charges "for effect": loudness from pressure.
- Do not change the nuke's sound, hearing or lean: its scenarios and tests must not move.
- Do not change charges, damage or flight.

## Checks

- Unit: blast loudness monotonic in charge, duration ∝ ∛W; `BlastPulse` for a 3 kg and a 450 kg charge at 50 m and 1 km
  against Kinney–Graham; hearing shift at the impulse-noise threshold and none below it; jolt from `BodySway` for a
  Shahed at 20 m and nothing measurable at 300 m; echo arrival times.
- Headless client: `fx`, `occlusion`, `nuke` (unchanged) — sound logs in the PR.
- Guide: hearing and shove from conventional blasts, EN and RU.

## Done when

Every blast's sound, the ringing in the ears and the shove come from charge, distance and the path, with the nuke's own
models; no per-kind sound profiles or shake buckets remain.
