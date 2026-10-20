# Session 2. The explosion as a volume: smoke grid, fireball, flash

Area rules: `client.md`, `client-fx.md`, `nuclear.md` (volume pass, mushroom), `client-scenario.md`. How the nuke's
volume is built: `nuke/mushroom-volume.md` (read first). Laws: `ref/formulas.md` §5 (thermal rise MTT, CCPS fuel
fireball, black body, soot/dust optics, Briggs). Data: `ref/passport.md` (charges, compositions, fuel). Target
picture: `ref/feel.md` (impact paragraphs). Shared packet change: `cloud/contract.md`. Old map:
`ref/code-map/visual.md` §1–2, §5.

## Goal

Smoke, dust and fire of conventional blasts become a lit volume like the nuke's, not camera-facing sprites: you can fly
into a cloud, the sun lights its thin edges, fire glows from inside, the cloud shades the ground, the wind bends the
column. Shape and motion come from laws (heat of the explosive and of the fuel → buoyant rise; wind and stability →
drift and spread), colour from what burned, light from temperature. Particles stay only for solid things (sparks,
fragments, debris, clods of earth). This is Artem's decision: all smoke and dust are volume in the nuke's pass.

## What the player should see (from `ref/feel.md`, current charges)

- Shahed: a flash for a frame or two, an orange fuel fireball (CCPS: D = 5.8·M^⅓ m, t = 0.45·M^⅓ s from the fuel
  left), black soot above it, earth-coloured dust at the base, the column rising hundreds of metres and drifting.
- Tomahawk: a bigger fuel fireball, grey (not black) smoke of an RDX/HMX-based charge, a higher column.
- Grad: flash, no fuel fireball, grey-black puff and dust; a salvo of 40 makes a field of puffs that merge into a dust
  wall moving with the wind.
- Lancet: a small flash and a cloud of tens of metres, no fireball.
- At night fire lights the ground and the smoke from inside by temperature, as the nuke's fireball does.
- Sizes follow `WeaponSpec.charge` as it is today (Tomahawk still carries 450 kg there; the local package will make it
  ~150 kg TNT and the picture must follow without a change from you).

## What exists and what is in the way

- `PallPass` has everything the volume needs: reduced-resolution raymarch, temporal resolve, depth-aware composite,
  shadows, `OpenAir` (no smoke underground), black-body row, GPU timer and quality ladder at 2 ms. But it is drawn only
  when the nuke is alive: `FxLayer` calls `NukePall.frame(...)` and draws nothing without it.
- The mushroom table has 4 slots with one step length shared by all: a small cloud near the eye and a mushroom far away
  would stretch each other's step, and picking by angular size would push the nuke out. Do not put conventional clouds
  in the mushroom table.
- `BlastEffects`, `Explosions` (near) and `FarBlasts` (far, table `Look` with hand-picked colours and sizes, columns of
  sprite puffs) draw today's conventional blasts.

## What to do

1. **Shared packet** (`cloud/contract.md`): the client knows the weapon, charge, fuel and composition of each blast.
2. **Cloud model** (pure class, unit tests, no Minecraft): from charge (heat of detonation, kJ/kg TNT with source) and
   fuel (heat of combustion) → buoyancy flux → rise of the thermal and later the column by Morton–Taylor–Turner with the
   stratification of the hour; fuel fireball by CCPS; wind at height → drift; stability → spread (Briggs σ); mass of
   soot (composition) and dust (crater volume from the existing crater numbers or a marked estimate) → optical
   thickness; life = until optical thickness falls below visibility. Colour: TNT-like → soot (albedo 0.2–0.3),
   RDX/HMX-based → grey, dust → the colour of `GroundMaterial` at the spot (not a fixed grey).
3. **Smoke grid around the camera** inside `PallPass`, its own march: a 3D texture of density and medium (soot, dust,
   white smoke, temperature) with ~1–2 m cells near the eye and coarser further (cascades or a frustum-aligned grid),
   refreshed every frame or two from the list of live clouds; one march through it with its own step, however many
   clouds there are; fine shape from `VolumeNoise` like the mushroom. The pass runs when there are clouds, not only
   when a nuke is alive; the nuke's mushrooms and pall keep their own path and share the frame, resolve and composite.
   Expose a small API (add a cloud: position, heat, masses by medium, temperature over time) — session 5 will feed
   launch clouds, near trails and fire smoke through it.
4. **Light**: the fuel fireball and burning gas emit by temperature (black-body row); they light the world and
   particles through `FxLights` and the fireball-light path (no more than its limit); sun and sky light the cloud the
   same way the mushroom is lit (two-stream, Henyey–Greenstein); the cloud's shadow on the ground.
5. **Flash**: brightness and duration of the detonation flash from the charge (fireball of detonation products), seen
   at once; `Flash`/`FlashFalloff` take it from the model, not from a constant per weapon.
6. **Far blasts**: `FarBlasts` uses the same cloud model; inside the grid it is the volume, beyond the grid a coarser
   volume or sprites whose shape, colour and life come from the same model (choose by measured GPU time and say why).
   The table `Look` goes.
7. **Near effects**: `BlastEffects` keeps particles only for solid matter (soil fountain, debris, sparks); its smoke and
   dust go to the grid. The condensation sphere appears only where pressure and humidity allow it (a marked estimate),
   not always.
8. The bomb's underground blast keeps its current picture (depth and venting are physics of the local package); only
   its dust at the surface may use the cloud model.

## Do not

- Do not draw smoke as sprites "because it is faster" before measuring the volume.
- Do not change the nuke's mushroom, pall, dust wall or fire storm beyond sharing the frame; their scenarios' frames
  must not move.
- Do not pick colours "nicer": colour from composition and ground; if it differs from what Artem might expect, say so.
- Do not touch charges, damage, craters or fire ignition on the server.

## Checks

- Unit: cloud rise against MTT for two stratifications; fuel fireball size and duration against CCPS; colour against
  `BlackBody`; layout of the grid shared by Java and GLSL (like `MushroomGlslTest`); the nuke tests unchanged.
- Headless client: `fx`, `fx-night`, `fx-late`, `pall` and `mushroom` (to prove the nuke did not move), plus new cases
  "Grad salvo next to the camera" and "flying through a Shahed column"; frames in the PR; GPU time from the pass's timer
  for the worst case (salvo of 40 near the camera with a live 15 kt pall) — say that his RTX 4060 number is unchecked.
- Guide: how blasts look, EN and RU.

## Done when

Smoke, dust and fire of conventional blasts are a volume made by laws from charge, fuel, ground and air; particles only
for solids; the far view comes from the same model; the nuke looks exactly as before; the budget holds.
