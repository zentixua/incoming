---
paths:
  - "mod/src/main/java/ua/zentix/incoming/client/sound/**"
  - "mod/src/main/resources/assets/incoming/sounds/**"
  - "mod/src/main/resources/assets/incoming/sounds.json"
  - "tools/build_sounds.py"
  - "tools/synth_mod_sounds.py"
  - "tools/freesound.py"
  - "mod/src/test/java/ua/zentix/incoming/client/sound/**"
---

# Sound

Crater fuel roar is `CraterFire`: continuous fire recording at native speed; level from `Sources.flame` and ISO 9613,
duration and decay from `FuelPool.share` at emission time; starts from fuel-fire packet `S2C.FuelFire` at its cell
centroid (aged from impact), not from the explosion packet (without a server, at impact position,
`BlastEffects.legacyPool`). Fires have no individual loops: all fires sum by power into 4 `blast.fire` loops in
quarters around the ear (`Quarters`, `BlastSounds.fireLoops`; cos²/sin² between neighbors, highs averaged by power).
A 300-Shahed raid started one loop per crater, ~150 channels, each retrying every 10 ticks without a channel — pool 247
saturated every half-second, the nearby fire fell into rumble (Sol WAV run, 25.10). Loop powers add at the ear only if
their sounds are incoherent at every start offset: each quarter has its own `blast.fire` variant (`Quarters.seed`),
a separate non-overlapping segment of a long large-fire recording (craigsmith 483323, `fire()` in `build_sounds.py`:
native speed, avoiding metallic ringing, spectrum matched to accepted roar 483304 by linear correction without a
limiter; at its own darker spectrum, A correction −5.6 dB versus −0.2, `Sources.flame` would be 3.5–5 dB quieter at
the same power). The same recording in two loops started on one tick added by amplitude, making a fire between them
2 dB louder (R8 review, OpenAL loopback); offsets of one recording do not work — coherent when start difference equals
the offset; noise synthesis under the recording's envelope softened crackling (review round 4). Neither 483304 (14 s)
nor 483313 (42.7 s): both repeat their low layer (every 11.03 and 13.62 s), segments correlate up to 0.21. Spectrum sets
the incoherence limit: for two independent noises with the loops' spectrum over 12 s, maximum |ρ| across all offsets
is about 0.02. Check actual file audio (`SoundAssetsTest.craterFireLoopsAreIncoherent`: full sample rate, all circular
offsets, pair against one loop). File loudness for the law is mean loop loudness (`LOOPS_BY_MEAN` in `build_sounds.py`),
not the loudest 400 ms: the flame law is mean power, while a crackling recording's 400 ms with a click exceeds its mean
by 3–7 dB (steady 483304 by 1.7: it played that far below the law). Loops have mean loudness at the ceiling (`FIRE_LUFS`),
like motor loops: Minecraft cannot play louder than the file, so a quieter loop would clip the sum of many fires below
the ceiling (peak-normalized to −1 dBFS, −21.6 LUFS, they lost 5.1 dB on 300 fires of 2 MW at 30 m — R10 review;
`CraterFireTest.summedFiresPlayAsComputedUpToTheCeiling`). Clicks exceed RMS by 20–22 dB, while the ceiling-level file
allows at most 12.5: a limiter with 10 ms release cuts them (deeper than 1 dB for 4–5 % of the time); crackling remains
stronger than 483304. Ceiling applies to the sum of all quarters (`Quarters.volume`), not each: otherwise a fire field
between two loops would be 3 dB above ceiling. Fire audibility is at the ear in the shared calibrated measure
(`Sources.gain` → `Loudness.gain` > 0: at least one octave above background, `CraterFire.volume`), not file volume:
the ceiling-level file is 8.1 dB louder, playback gain 2.54 times lower, and its `AUDIBLE` threshold cut a 2 MW fire's
range from 1630 to 790 m (R11 review; `CraterFireTest.audibilityIsAtTheEarNotInTheFile` makes the same decision for the
file and one 8.1 dB quieter). Now fire fades to the masking boundary (0.2/2/20 MW at night: 1265/1955/2735 m; at 6763a1c8
the file level set it to 990/1630/2390). Inaudible individual fires do not enter the sum. Obstruction rays to audible
fires use a tick budget (`FireRays`: at most 16 per tick, first untraced fires, one per each of 16 sectors, then oldest);
the ray itself stops at the last loaded chunk (`SoundFilters.reach`: beyond it the client world is empty, same result).
A ray to every audible fire each tick costs ~1.4 million block/fluid reads per tick for 300 fires at 1.7–1.9 km
(Sol scene client tick after #456: 0.85 → 17.9 ms; ray share from counters, no scene profile). Budget refines obstruction,
not audibility: audible fires join the sum that tick, before their ray using known sector openness (`FireRays.known`:
sector-ray average, nearest sector if empty); keeping untraced fires silent took 19 ticks to build a 300-fire raid's
power (R12 review). Tick order: hearing for all (`CraterFire.hear`), rays, mix (`FireRays.tick`). Budget cost is delayed
obstruction: each fire's ray refreshes every `FireRays.lag` ticks (300: up to 0.95 s; 3000: up to 9.4 s); the loop does
not hide it — volume approaches the target by 0.05 per tick. Check: `FireRaysTest` (same rays and world reads per tick
at 30, 300 and 3000 fires; sum power from tick 0 on open paths, behind a wall, and with a wall covering half the circle).
Fire tick core has no Minecraft; world and setting pass through `SoundFilters.Path`. Loops play while the sum has an
audible fire (`Quarters.plays`), not by a threshold on each loop: a fire between two loops splits in half, so per-loop
thresholds removed it entirely (R9 review); explosion rumble does the same (`Rumble.plays`). Do not restore repetition
every 260 ticks: the old file faded for its last 5 s, and weapon-dependent speed caused gaps or overlaps. Channel
restart after device/slider changes uses `AmbientLoop.playing`, not `isActive`. Section `fire` in `build_sounds.py`
builds `blast_fire_1..4` (uses recordings: without Freesound login, `--previews fire`); remeasure spectrum and LUFS after
file changes.

## Pitfalls
- Quarter-loop recording variants are chosen by sound seed (`Quarters.seed`: Minecraft chooses a variant with the
  first weighted `nextInt`). Scatter seeds over `long`: `LegacyRandomSource`'s first values for small seeds are nearly
  identical; seeds 0–4095 yield neither 0 nor 1 from `nextInt(4)`, so the old search silently returned the fallback
  seed — three of four rumble loops played one file.
- OpenAL filters (`SoundFilters`: air, obstructions, deafness) attach in `PlaySoundSourceEvent` (sound thread) and remain
  until sound end; update projectile motors each tick via `SoundEngine.instanceToChannel`, other loops (water, Nether,
  beacon, minecart) via `SoundFilters.loops` on client tick: while deafness/pall filter differs from 1 (with Sound
  Physics every tick: `update_moving_sounds` reapplies its own filter; without it when change reaches 0.005), and once
  when back to 1 or pall and deafness both disappear (`restoreLoops`) (hearing returns over hours: a pre-wave loop
  would remain clear, one started during deafness muffled afterward). AT exposes `Channel.source`,
  `SoundManager.soundEngine`, `instanceToChannel`, `SoundEngine.tickCount` (`META-INF/accesstransformer.cfg`). Reapply
  after sound-engine restart. Distant sounds are duller in the post-nuclear pall (`NukePall.muffle`: dust density at
  ear and sound distance), combined with deafness while pall is not OFF and `sound_muffling` is enabled. Our sound
  paths (air, obstructions) remain ours with Sound Physics Remastered: our sounds play 3 blocks from the ear (or at
  the delayed audible position), while our model computes path volume/highs; SPR would see only those 3 blocks,
  making distant impacts sound near (before PR #450 we removed our air/obstructions with SPR). Our sound's direct
  filter (`Muffled`) replaces its filter (`SoundFilters.direct`, `SoundFiltersTest`), and its
  `AL_AIR_ABSORPTION_FACTOR` (SPR sets it on every sound, even at rate 0) resets to 0 (`ownPath`), otherwise OpenAL would
  cut distant motor highs twice. `update_moving_sounds` reassesses playing sounds every few ticks (even at rate 0);
  `SoundFilters.loops` in `ClientTickEvent.Post`, after the sound tick queues this on the sound thread, restores our
  filter (`SoundFilters.OWN`). Rate 0 for every `incoming:` sound in its `sound_rates.properties` (README; no `*`
  wildcard) also removes its reflections over our echo. Other sounds remain its responsibility: our deafness/pall
  filter multiplies its filter (`SoundFilters.Spr`); a source has only one direct filter, and pall muffles for minutes.
  SPR sets its filter at `Channel.play` start via a shared object (`SoundPhysics.directFilter0`, read by reflection);
  NeoForge's `PlaySoundSourceEvent` follows `play()` in the same sound-thread task, so at that instant it contains
  this sound's parameters. Other loops capture its startup filter (`SPR_AT_START`): our deafness filter overwrites
  SPR's long-sound updates (`updateMovingSounds`) each tick.
- Motor volume follows source and path, without “ref/floor/cutoff” curves: projectile-part sound power (`sound/Noise`:
  propeller from RPM, RPM ∝ entity speed; turbofan via Lighthill; solid rocket as a fraction of jet mechanical power;
  flow via Curle dipole), recording octaves (`sound/Spectra`, written by `build_sounds.py`) and path
  (`sound/Propagation`: ISO 9613-1 and -2 per octave, daytime shadow, octave background — audible while any octave
  exceeds background). In-game `Loudness`: half decibels (like explosions), `ENGINE_LUFS` reference at `Loudness.REF`
  (~126 dBA), quiet-night background at `VoiceBudget.AUDIBLE` relative to reference, ceiling `CEILING_LUFS` (near
  explosion from 1 psi, ~137 dBA for motors). Loop and start files are at ceiling (`MOTOR_LUFS`, build events `MOTOR`;
  MLRS launch rail exit 2 dB lower, separation pop and catapult at reference: never louder than `REF`); Minecraft volume
  is `Loudness.motor`/`volume` (volume relative to reference × file volume); `VoiceBudget` chooses layers by volume
  relative to reference. A file quieter than calculated clips volume below ceiling: in 3.5.0 all files were at reference,
  so missile ignition within ~45 m of the launcher and booster roar within ~20 m sounded identical
  (`SoundAssetsTest.motorFilesAtTheirLoudness`, `LoudnessTest`). Starts at 25–45 m (probe scenes) are 5–10 dB quieter than
  3.4.1 for another reason: 3.4.1 mixed them by ear louder than a Shahed explosion at 50 m, while data gives a quieter
  launch than explosion (PR #372). The nuke's ceiling remains the reference (`EngineSound.ceiling`,
  `Sources.Source.ceilingLufs`), its sound unchanged. File-volume-only changes: `build_sounds.py --relevel` (no Freesound
  recordings). Distant sounds are near ones through `FAR_M` of air (`EngineSound.FAR_AIR`), distant high-cut filter
  applies only beyond that. The server chooses path recipients with the same calculation (`Hearing.audible`): do not
  raise volume “to make it audible”, which would desynchronize server and client. Weapon-specific sound numbers go
  in `WeaponSpec.*_NOISE` with sources, not in the layer.
- Emission time uses all roots of the delay equation (`Acoustics.emissions`: stepping |f| / (c + v_max) back from “now”
  cannot skip a root); layers follow the latest; no root means sound has not arrived, layers stay silent (nothing
  sounds before arrival, including the track's first point). Supersonic projectiles are inaudible outside the Mach
  cone; cone arrival brings an N-wave (`Booms`, pressure/duration from Carlson, `sound/SonicBoom`, volume
  `Outdoor.pressureVolume`); the early root (approach sound backward) is not played — Minecraft cannot. Doppler has
  no limit; pitch is within Minecraft's 0.5…2. Path history: `Hearing.MAX_DELAY` ticks (60 s); sound is not carried
  beyond `Hearing.RANGE`.
- Explosion sound has one front at every range: near (`BlastEffects.Timeline`), far (`FarBlasts`) and echo
  (`BlastSounds.echoes`) wait for `FarBlasts.arrivalTicks` (`ChemicalFront`: faster than sound nearby; sound speed
  without its own wave) from the explosion point, not `d/343` from 1.5 m above: otherwise at the 640 m edge the roll
  lagged the same front on the other side by 18–130 ms (CONSIST C06). A roll for an event evicted beyond `FarBlasts.CAP`
  waits its sound-path deadline in `FarBlasts.SOUNDS` (C18). Bomb-event sounds, like explosions, use one voice per
  range: within 640 m, near model (`Timeline` waits for the front `SOUND_WAIT`, 60 ticks, 1 km, regardless of visual
  lifetime: ground entry cut off beyond 515 m, collapse beyond 343, C01; deadline leaves margin for listeners leaving
  the edge: with sound-path-to-edge time alone, 38 ticks, a listener moving away at 640 m heard neither near nor far
  voice); beyond that, event-cloud roll (`FarBlasts.Event.voice`: entry/collapse use a fraction of impact energy in air
  as an underground thud, `BlastEffects.thud`; gas vent uses its charge, C13). Check: `BombSoundWaitTest`.
- Path air uses world surface humidity (`util/Air.surfaceHumidity`: clear 70 %, rain saturated) through
  `Propagation.Weather.humidity` (same weather on server/client) and `SoundFilters.air` (5 kHz). Recording conditions
  (`sound/Air`: 20 °C, 70 %) calibrate recordings: air already in a distant recording (`Emission.heardAt`,
  `Weather.recorded`), and explosion perspective changes (`BlastMix.AIR_4K`). Check: `PropagationTest.airFollowsWorldHumidity`.
- A motor layer lives while it has an engine channel (`ClientSounds.playing`: `instanceToChannel`), not by `isActive`:
  zero category volume removes the channel, but `isActive` still says “playing”, leaving the layer silent for the
  remaining flight.
- Sounds are mono only (Minecraft cannot position stereo spatially); `SoundAssetsTest` checks this. Conventional
  explosion echoes come from terrain with our own sources (`BlastEchoes`, like the nuke), not stereo recordings:
  use one or the other, otherwise echo doubles. libsndfile crashes on one large Vorbis write; `build_sounds.py` writes
  chunks.
- Explosion mix (`Mix`): game volume is half the decibels of total level at the ear; ceiling applies to the sum, not
  each sound. Mapping sounds individually made speaker power add: Grad salvos at 30 blocks were 4–5 dB above ceiling,
  hitting full scale (0 dBFS clips in RV1 run, 3.4.1 had 0–1). An explosion is one wave: perspectives of one recording
  add by amplitude; separate recordings (chest hit, ear hit, echo, `Mix.Kind.APART`/`OWN`) by power (amplitude addition
  left a 1 psi explosion 3 dB below ceiling — F4 review). Different explosions: total G = min(1, (Σ p²)^½), explosion
  share is its real-life power share, Q_b = G·p_b²/Σp², each has its own factor k_b² = G·p_b/Σp² (`Mix.Blast.factor`;
  common k² = G/Σp before 3.7.1 reduced everyone equally: a near blast over a hundred far ones lost 3 dB, and the far
  ones together sounded equal to it — now −0.04 dB and −20 dB, `MixTest.nearStaysOverAHundredFar`). This is presentation
  (HDR audio window: Wwise, Frostbite), not recreating life: p is power after mapping over the largest window, masking
  is by loudness only. Perspectives and bomb sounds are ticking `ClientSounds.MixedShot` (volume read each tick,
  self-removes at recording end); ear hit and own-source echoes use `OwnSources.play(…, blast::factor)` each frame.
  Echoes are delayed layers of their explosion (`BlastEchoes.bus`, track envelopes in background during mixing):
  without them, late echoes after the direct hit fades would fade with its factor; EFX reverb tail is excluded from
  the mix (~10 dB below echo). Explosion mix starts at actual sound start (`Mix.Blast.late` by `OwnSources.Anchor`).
  Current sound follows the recording's envelope (`Envelope.of`: each tick uses the loudest 400 ms window containing
  that tick, both starting there and earlier — last tick's hit is still in the window; `Envelopes` takes the maximum
  over variants, read from files on resource reload), an upper estimate for the ceiling. The nuke has no mix. Check:
  `MixTest.gradSalvoStaysUnderTheCeiling` (salvo from real recordings, at ceiling; peaks exceed full scale only on the
  hit tick: near-perspective and chest hits of one explosion begin at the first sample, so their peaks coincide at
  ceiling, as in 3.4.1; OpenAL Soft's limiter catches them, enabled automatically on integer output).
- One-shot explosion channels have their own ceiling (`BlastVoices`, like motor `VoiceBudget`): no static channels
  means silent engine refusal (`SoundEngine.play`, “Failed to create new sound handle” logs only in development),
  losing even nearby sounds in a hundred-strike raid. Device decides channel count (`Library.init`: mono sources
  minus streaming clamp(√mono, 2, 8)); OpenAL Soft has 255 mono and 1 stereo, 247 static; our 12 (`OwnSources.MAX`) use
  the same 256 OpenAL sources, leaving 236 in practice (`BlastVoices.reach`, device count from `OwnSources.deviceSources`
  on engine load; engine-constructor event has no context). Ceiling: 236 minus motors, explosion rumble, fire roar,
  the nuke, world (est.), reserve for yielding voices — 96 (`BlastVoices.CAP`); beyond world estimate, use actual
  free engine channels at hit time and a near-hit reserve (`BlastVoices.reserve`: once per tick, if free including
  channels yielding voices will soon return is less than `SPARE + GROUP`, quietest own voices yield early: channels
  cannot be freed on the hit tick). `GROUP` reserve belongs to the loudest: a new sound quieter than any playing one
  cannot use it (`admit`: `GROUP` fewer channels available) and enters rumble immediately instead of a voice whose
  channel the reserve would take next tick; the loudest can use it, quieter voices restore it. Only voices that an
  explosion as loud as the loudest current voice would evict on admission yield early: quieter (attacks need twice
  the loudness) or recording ended; never the loudest: a near voice using reserve for its attack yielded to that same
  reserve next tick, entering rumble mid-attack (R7 review;
  `BlastVoicesTest.reserveNeverYieldsTheLoudestNorAProtectedAttack`, `theReserveBelongsToTheLoudest`; stress scene checks
  the near voice after reserve too). Crater ejecta falls are one-shot explosion sounds, in the mix and this budget
  (`BlastSounds.oneShot` through `Sources.play(…, player)`), not world sounds — 299 raid falls occupied channels outside
  the budget. Check: `BlastVoicesTest.stressSceneOf300KeepsTheNearVoice` (WAV overload scene: engine-pool model, also
  with pool filled by other sounds). A channel is occupied while the engine holds it (`Sound.held`:
  `ChannelAccess.ChannelHandle.isStopped`): sound `stop()` only sets a flag, the engine stops the channel next tick
  and returns it to the pool with a later sound-thread task (`tickNonPaused`, `ChannelAccess.scheduleTick`), while
  `instanceToChannel` retains entries up to 20 ticks after start; count free channels by `isStopped`, not map size.
  Thus eviction gives no immediate room: a new sound uses channels already free, yielding voices (fading and awaiting
  return, `HEADROOM` reserve) return theirs later, restoring our ceiling and world reserve `SPARE` (R1, R2 reviews).
  The quietest in the mix yields (attacks, first 400 ms, only to something twice as loud); ended recordings yield
  first. A new voice (even the loudest) is admitted only if all hold: enough engine channels are free now; including
  it and yielding voices not yet returned, explosion channels stay within `cap + HEADROOM`; yielding quieter voices
  (attacks require twice the loudness) restores both our ceiling and world reserve `SPARE` after channel return
  (R6 review); for a newcomer quieter than any playing voice, first and third also preserve `GROUP` for the loudest
  (R7 review). Otherwise it enters rumble at the same power. This is channel virtualization, not an attack-audibility
  threshold over background (no temporal masking). `SCENARIO blast-sound` reports `voices`: channels, ceiling, requested,
  admitted, refused, sent to rumble, yielded.
- Rumble (`Rumble`): voiceless explosions sum by power into 4 `blast.rumble` loops at cardinal directions (cos²/sin²
  between neighbors, like `FireRoar`); each member is k_b² × explosion engine-voice energy (`Mix.Blast.voiced`, excluding
  own sources) by the playing recording's profile (`Envelope.energy`: momentary loudness over the past 400 ms of the
  seed-chosen variant, `Envelopes.energy`; the ceiling envelope would raise rumble before impact and give the longest
  variant's tail), × obstruction share behind obstacles, minus f² × still-playing fading voices (`Member.power`):
  sounds refused by the engine join rumble immediately in full (R3 review). Voices and loops transition on the same
  clock, sound-engine ticks (`SoundEngine.tickCount`, AT): voices tick and set volume in `SoundManager.tick`, before
  `ClientTickEvent.Post`; loops calculate there too, in their own tick (`BlastSounds.RumbleLoop`); calculating on client
  tick would lag one tick, yielding 4/9, 6/9, 8/9 power in transition (R4 review). A yielding voice stays full for one
  tick (`Rumble.voice`): a new quarter's loop starts on client tick after yielding, at power after voices fade
  (`Rumble.potential`), with calculated volume (`AmbientLoop.prime`), not 0.001; a voiceless member immediately sets loop
  channel volume (`RumbleLoop.now`). Checks: `MixedShotTest.minecraftTickOrderKeepsTheHandoverPower` (real `MixedShot` in
  Minecraft tick order), `BlastVoicesTest.raidStaysUnderTheCeilingAndTheNearKeepsItsVoice` (every-tick comparison).
  Rumble does not reenter the mix. Loop pitch is members' power-weighted mean pitch; highs from their paths, actual
  contributions, not `potential`: a just-yielded, still-full voice does not affect audible rumble timbre (R5 review);
  no rumble yet means use the timbre of incoming members. Loops are noise with the mean spectrum of `blast_far` tails,
  no attacks, independent variant per quarter (`rumble()` in `build_sounds.py`, `--alone rumble` uses built files
  without recordings; `engine()` checks steadiness ≤ 1 dB; quarter chooses its own via `Quarters.seed`), at ceiling
  (`BlastSounds.RUMBLE_LUFS`). Quarters play their rumble share while it has members, explosions audible at the ear
  (decided by their sound), without a loop-file-volume threshold (`Rumble.plays`, R11 review: remixing would change it)
  or a separate per-loop threshold; a loop with nothing to play for `Rumble.HOLD` ticks (one second) is released.
- Minecraft cannot play louder than the file (volume ≤ 1), so explosion loudness is in the files: perspectives use
  EBU R128 with peak headroom like vanilla TNT (≈ 10 dB between peak and loudness), not crushed by a soft limiter
  (crushed headroom 5–8 dB, “old game sound”). Explosions and one-shots played on front arrival have `"preload": true`
  (otherwise first use decodes on start), with impact from the first sample. Perspectives of one explosion share a
  seed (`RandomSource.create(seed)`: vanilla chooses a weighted `nextInt` variant), so `blast.near`/`mid`/`far` have equal
  variant counts (`SoundAssetsTest`). Motor loops also use EBU R128, all at `MOTOR_LUFS`, ceiling, the near explosion
  perspective (crushed loops become louder than explosions). Loops use steady stationary-engine recordings, not
  flybys: flyby pitch shifts with Doppler and jumps back every loop; stitching short segments wobbles volume
  (distant missile “rustle”). Build `engine()` checks steadiness (momentary loudness standard deviation ≤ 1 dB);
  without a steady recording, use noise with the recordings' mean spectrum (`noise_loop`). Recordings run at native
  speed: the build rejects `speed()` shifts beyond ±25 % (`SHIFT_MAX`) without a reason (`why`: Sachs for explosions
  or marked “not physical”); nuclear sections (`NATIVE`: `sirens`, `nuke`, `after`) allow no reasons, and their client
  pitches stay within the same ±25 % (`NukeTonesTest`): nuclear scale comes from laws — `BlastPulse` shock with modeled
  phase at every range, echo, wind, destruction, storm roar. Need another pitch: another recording, not speed change;
  3.5.0 Shahed sped up threefold to blade pitch sounded like “flies” — body/exhaust resonances move with pitch. Shahed
  loop uses a powered paraglider (single-cylinder firing at MD-550 shaft frequency); `SoundAssetsTest` pins its strongest
  tone to the spec's shaft rate.
- Terrain echo is not copies of one seed on a delay grid: identical sounds every 10 ms create a comb with 100 Hz tone
  and harmonics (an “arc” in every 3.5.0 explosion, peaks up to 32 dB in probe P WAV). A terrain face returns energy as
  smooth noise across its arrival spread (`TerrainEcho.Reflection.spread`, from face angles), sample-accurate delays,
  FFT convolution (`EchoMix`); comb measure `EchoMixTest.comb` (summed-track autocorrelation at 2–50 ms, threshold 0.1).
- Terrain echoes (nuke and conventional explosions, `BlastEchoes`) use our OpenAL sources (`OwnSources`), bypassing the
  sound engine: Sound Physics Remastered attaches EFX filters to all engine sources and would add reflections to
  reflections. The process has one OpenAL context (`alcMakeContextCurrent`, AL capabilities per process), so calls
  come from the client thread (frame, tick), not sound thread. At most 12 own sources: OpenAL Soft has 256 total, while
  the engine allocates up to 255 on demand. Engine restart (`SoundEngineLoadEvent`, device change, F3+T) destroys the
  context with our buffers/sources: forget them without AL calls (`OwnSources.engineLoaded`); gain above one uses
  source `AL_MAX_GAIN` (track compressed to peak 0.98, gain restored during playback). Master volume is listener gain,
  set by the engine; category is “Master”. AL errors (`alGetError`) are shared with the engine's sound thread: check
  our calls' success through objects (`alIsSource`, `alIsBuffer`, buffer size, filter/effect type read back), and consume
  our own failure with `alGetError` (`OwnSources.spent`), otherwise the next engine check takes it as its error
  (`Channel.create` loses an already allocated source). Own sources retain our obstruction handling with Sound Physics
  too (`SoundFilters.openToward`: three rays over 12 blocks toward the track when the voice starts, which may wait
  ~24 s, allowing the player to enter a house; filter on direct path and reverb send); otherwise indoor echo was
  louder and brighter than the direct hit.
- Echo time starts at actual direct-hit onset (`OwnSources.Anchor`: `PlaySoundSourceEvent` arrives from the sound
  thread immediately after play), not a tick: non-preloaded recordings decode on first use, and the headless client
  ticks once per frame — tick timing made echo precede the direct hit by 0.27 s (`nuke` cloud scenario). Create the
  anchor before `play`: an engine-held buffer (same recording's second use in a game) starts on the sound thread
  before `play` returns; an anchor created afterward would wait 40 ticks, shift one tick, and lose near reflections'
  first 0–2 s (`SCENARIO nuke-sound … start +… by sound|tick`). Engine-refused sound (`isActive` false) uses tick time
  immediately.
- Nuclear strike volume follows peak pressure at the ear (`Outdoor.pressureVolume`: full from 1 psi, below that half
  pressure decibels, like conventional explosions), same `Outdoor` path as conventional ones (edge by `Sightline` from
  detonation, ground at epicenter, daytime shadow from ray/ground contact, `Outdoor.shadowFrom`: beyond 9 km for a
  600 m burst). Distant front hit (below 1 psi) is a modeled track (`BlastPulse`: one shock, rise 0.17 Pa·s / P,
  listener's KG85 NE phase, 20 Hz cut) on an own source from `nuke.boom_far` recording start (own `OwnSources.anchor`
  before `play`, like echo), not a file: a file has one rise/phase for all ranges. Highs behind hills and in shadow
  are cut in the track itself: own-source filters only cover ear-local obstruction and deafness; air does not soften
  the shock (the wave sustains it), so `farAir` applies only to the recording. Hit voice is `whole`: late starts (frame
  > 30 ms, non-preloaded `nuke.boom_far` starts between frames) play from the beginning, not the middle — a mid-track
  start has no shock (`mushroom` cloud scenario, 3–6 fps: no click). No slots (12 own sources occupied by echo): use
  `nuke.crack_far` recording. Direct hit (`ClientSounds.loudShot` with caller's filter) uses ear-local obstruction,
  `openToward`, 12 blocks, house walls; farther terrain uses `Sightline`. Post-front wind is `AmbientLoop` approaching
  by 1 per tick (gust arrives with the front, not over a second), volume ∝ air speed through the Friedlander phase;
  owner `NukeSounds.Schedule` also removes it in `stop`.
- Hearing after nuclear/conventional waves (`client/nuclear/Deafness`, laws in `nuclear/model/HearingModel`): threshold
  shift follows the wave's A-weighted energy at the ear (`exposure(pa, t₊)`, Friedlander pulse; a long nuclear wave's
  energy is its shock = peak − 38.7 dB, while conventional positive phases last milliseconds and the whole wave is
  audible), not peak: a 140 dB peak threshold (CHABA) would deafen players 70 km from a nuke and a kilometer from a
  Shahed. Filter `Deafness.gain`/`highs` (lows: cochlear loss with recruitment plus conductive loss; highs: difference),
  also for own sources. Hearing clock is unpaused client ticks plus forward time-of-day jumps exceeding game-time
  advance by more than 20 ticks (sleep, `/time add`), not `getGameTime`: sleep restores hearing. Jumps count only in
  the same client world while game time advanced normally: a new client world (portal) starts with zero clocks and
  its time packet would otherwise count as sleep; server clock corrections move both times. Respawn:
  `ClientPlayerNetworkEvent.Clone` with old player `isDeadOrDying` (dimension change also clones, hearing remains).
  Multiple waves use `HearingModel.Ear` (within 2 game minutes, ~1.7 s, one dose). Wave sounds themselves (hit, pops,
  glass; own-source shock uses `before` in `OwnSources.play`) use `Deafness.before`, hearing as it was before the wave;
  otherwise a non-preloaded recording on first use would start after ear injury and be muffled by its own wave.
  Ringing is its own non-positional loop (`relative`, inside the head: an ear-positioned world source would lag a tick
  and pan while walking; `nuke.tinnitus` is a steady 10 s loop, whole periods of tone, beats and drift); class excludes
  it from deafness, non-positional `MASTER` from pall.
- Owner-driven loops (`AmbientLoop`: fire roar, wind into fire) fade and remove themselves once the owner stops
  driving them (detonation removed or replaced after replay seek: nobody calls the old `Schedule`). Restart by
  `playing()`, a channel in `SoundEngine.instanceToChannel`, not `isStopped()` or `SoundManager.isActive`: F3+T, device
  changes and channel exhaustion remove sounds without `stop`; zero category volume removes channels
  (`SoundEngine.tickNonPaused`), while `isActive` still says “playing” based on the start record (same for `SirenSound`);
  master volume 0 does not remove channels (listener gain), only prevents new engine sounds. Do not start while
  category volume is zero (`canStartSilent` would hold a silent channel).
- Synthesis without Freesound recordings (cloud, Freesound unavailable):
  `uv run tools/build_sounds.py --alone <sections>` builds only these sections, preserving other `sounds.json` events
  and credits; sections using recordings cannot be built this way.
- World sounds other than motors/explosions (sirens, substation, districts, ruin collapse, ICBM launch, fire roar and
  crater falls) use `client/sound/Sources`: source power from spec (sourced or marked `(est.)`), ISO 9613 path, `Loudness`;
  file normalized to reference `Loudness.ENGINE_LUFS` using its loudness from `Spectra`, no louder than ceiling
  (`*_LUFS`, measured by `build_sounds.py --measure` on built files, without Freesound); sound arrives at sound speed.
  No manual ranges (`ClientSounds.at` with volume > 1). Bomb sounds belong to S5, SAM sounds to S6.
- Nuclear destruction near the listener (`Clatter`) and burning-city roar (`FireRoar`) use a source law without
  Minecraft: sound power is a fraction of dislodged energy (`Clatter.watts`: m(v²/2 + g·h) × `IMPACT_EFFICIENCY` over
  √(2h/g)) or flame heat (`FLAME_EFFICIENCY`), path `Propagation`, volume `Loudness` with nuclear ceiling. Thousands of
  sources, hundreds of engine channels: `Clatter` sums arrived power into quarters around the ear (first immediately,
  next summed after `HOLD` ticks; events shorter than the window spread their energy over it); `FireRoar` uses quarters
  and logarithmic distance rings (exact spreading within a ring, air/ground at power-mean distance; exact-sum comparison
  within 0.5 dB, `FireRoarTest`). Roar volume follows modeled storm (`FireStorm.map`, same flame drawn by the pall) and
  real fire in local chunks, not server fire count: one law for look and sound.
- Shahed: MD-550 unmuffled exhaust adds +`WeaponSpec.OPEN_EXHAUST` (10 dB) to muffled ultralight level; check: motor
  audible beyond 10 km on a quiet night (`HearingTest`). Derive source power from a distance measurement only by
  inverting the mod's propagation law (`Noise.fromMeasured`), not a spreading formula with a separate ground
  correction: that made Shahed 4.9 dB quieter than its reference measurement
  (`HearingTest.shahedSourceReproducesItsReferenceMeasurement`).
- Minecraft → OpenAL Soft → scenario WAV plays a mono recording into stereo ~4 dB below file loudness (`playsound`
  motor loop at volume 0.5: −23.4 LUFS instead of −19.5), equally for all sounds and 3.4.1. Subtract this when comparing
  probe-recording loudness with the model. The look-gate flyby had an alert siren over the motor in its separate scene
  (harmonics 436/877/1314 Hz), unlike after the launch scene: measure the motor in its tone band (shaft 125 Hz × pitch).
  Compare the sound window in a raid with the same isolated hit only at the same hearing state: raid (99 Shaheds,
  first at 30–50 blocks) by second 10 is one deafness dose (`HearingModel.Ear`: waves within 2 game minutes add),
  threshold shift ~80 dB in highs and ~27 in lows — full-band volume −10.9 dB, highs down to −40 dB (filter limit),
  ringing at ~4.9 kHz — the suppression and spectral-difference mechanism in the near-hit window (raid versus single;
  bands depend on window/method: reviewer with Hann window got −8.8/−9.9/−19.5/−1.4/−43.8 dB at
  20–125/125–500/500–2000/2000–6000/6000–20000 Hz, main similar). Whole-window M loudness (background, ringing, impact,
  recordings, not a single near-hit track) is not a mix measure and is not explained entirely by deafness: compare
  mixes at equal hearing, `nuke_tinnitus = false` for both or an isolated hit after the same dose; decompose using a
  run at equal hearing.
