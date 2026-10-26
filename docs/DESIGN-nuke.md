# Design: nuclear strike

Design used to implement the mod's nuclear strike. Physics, behavior and architecture here; code structure in
`.claude/rules/nuclear.md` and `nuclear-world.md` (`nuclear/`, `client/nuclear/`), usage in README.md.
Class names in §3–§10 are proposed names; actual layout is in those files, implementation differences in the table below.

Artem's request: “actually be able to launch a nuclear missile… incredible flash, mushroom, deafening sound wave,
a huge fire and blast wave over enormous distances, radiation, deadly rain… a clean, modern, optimized implementation
without hacks”.

## Implementation deviations

What differs from the design below, and why.

| Design location | Implementation | Reason |
|---|---|---|
| §0.6, §7 — nuclear warhead on cruise missile/B-2 | “Warhead” switch on remote (`Loadout.Nuke.onCarrier`), not separate `Warhead` class; missile bursts as selected (air/surface), B-2 bomb underground after drilling; single, no salvo | projectile already knows its explosion (`impact`), a `StrikeProjectile` flag suffices; B61-11 is a penetrating bomb, nuclear salvos excessive; disabled by `nuclear.carrier_nukes` |
| §0.4 — palette-based section skipping | column scans top to natural ground (≤ 96 blocks), stops | wave does not affect ground, so palette rarely needed; simpler, equally cheap |
| §3.1–3.3 — destroy chunk block by block on wave arrival | prebuild chunk ruins during ICBM flight (`NuclearPrep`: ≥ 5 psi heavy zone loaded from disk in non-ticking 5×5 squares, chunks with whole ruin windows on disk, plan `RuinPlanner`), apply per chunk at once (`RuinPlan`: position delta written directly into sections on replacement; changed-column heightmaps, sky sources, light-check positions calculated in plan, replacement only installs (chunk changed after plan: rescan all columns with plan positions); low memory (live heap above 75 % after two consecutive GCs) stops new plans, zone loads only planned chunks, other ruins built on-site; section packets to clients, immediate Distant Horizons LODs; fires in same replacement); block entities/POIs via world; stale plan (plan positions changed) rebuilt | `setBlock` costs ≈10 µs/block: city destruction would lag wave by seconds/tens of seconds; Artem: “wave and destruction synchronized, no lag” |
| §3.4 — light trace over all ground in radius | beyond heavy zone in Distant Horizons LODs (chunks outside world), ground trace only, no ruins (`RuinPlanner.scorch`, `FarLods.scorch`): turf, plants, scorch, fires, biomes from one disk-chunk snapshot, on wave arrival, nearest first, to `RuinPlanner.scorchReach` (~7.5 km at 15 kt); glass/trees stand until chunk load (then full ruins); no distant terrain light shadow | neighbor-window ruin plans for hundreds of thousands of distant chunks would occupy background threads for hours; without trace, LODs beyond heavy zone stayed green with a sharp circular edge (16.10.2026 frames) |
| §3.3 — pressure thresholds at blocks | two physical processes over original world around chunk (“physical”): wave breaks thin sky-air-exposed elements, blast-facing reflected pressure versus block threshold × thickness (1–3), tall/narrow topples, three rounds (farther behind breaches); then unsupported blocks fall (material span, cascading), trunkless leaves, bottomless water, attachments; ground breaks only if thin with sky both sides, leaving mountains, mesas, cliffs, caves, bunkers; ≥ 5 psi rubble except fireball; same chunk plan in advance/on-site | Artem: “destruction must derive from a physical process”; column rules (building signs, +4 % per height block, “half column removed, whole column falls”) produce chunk-border stripes and surviving towers |
| §8 — yield to `max_yield` 1000 kt, presets to 10 Mt | maximum 15 kt (`Loadout.Nuke.MAX_YIELD`), presets 1/15 kt | 15 kt heavy zone ~32 thousand chunks (ground 5 psi to 1.6 km by Brode; old model ~11 thousand), preplans capped at 512 MB; higher yields would leave ruins behind wave; Artem: “better a high-quality bomb that is not too powerful” |
| §3.2 — nuclear debris (`max_debris`) | broken blocks vanish without debris entities; server sends chunk viewers up to 24 removed positions and former blocks (`S2C.NukeDebris`, `RuinPlan.thrown`); client tears pieces when front reaches position: initially stationary material bodies (fragment, plank, glass shard, leaf) carried by Friedlander post-front wind, drag from own fall speed (`client/nuclear/BlastGust`, `client/fx/Fragments`: ≤ 1200 alive, nearer camera pieces evict farthest at cap); stone/brick/ground breach dust trails downwind as effects-layer billows (`client/nuclear/BlastDust`: 5·10⁻⁴ of removed mineral mass, modeled debris dust per kilogram of building clay/plaster, (est.)) | thousands of flying blocks per detonation are main lag source; conventional weapons retain debris; client pieces cost server nothing and never settle into world |
| §3.5 — core: vanilla explosion `core_power` 60 | no vanilla explosion | synchronously loads neighbors, breaks tens of thousands of blocks with item drops, stalls server 5 s. `CraterJob` digs crater, queue breaks above-ground blocks, front pushes craft (§3.6) |
| §6 — distant `AFTER_SKY` without depth, near `AFTER_WEATHER` | all `AFTER_LEVEL` with depth, distant compressed to 0.97 far plane | under Iris shaders everything before world end passes through pack, whose fog consumes mushroom entirely; checked with Complementary |
| §6 — ICBM trail as billow ribbon | exhaust flame (`client/render/PlumeRenderer`) and particle trail (`client/fx/Exhaust`, `particle/Fx`) like all projectiles | one motor-rendering path for whole mod; trail laid along entire tick path |
| §6 — front dust wall as ground billow ring, shake from `P` at arrival | dust wall is pall dust-layer head, a volume in pall passes (`client/nuclear/BlastWall`, `pall.glsl`), not billows: front invisible, pressure jump in clear air; wind behind it (Rankine–Hugoniot: 17 m/s at 1 psi, 72 m/s at 5 psi) lofts surface dust/soil, with smoke over fires, spray over water; wave has no matter of its own. Head/pall share one layer/laws (`nuclear/model/DustModel`, pall row below): youngest, lowest, densest part, wedge from front to where local positive phase ended (range-dependent by burst height, `HobBlast`: 15 kt at 592 m, 0.45 s under burst, 0.74 s at 1 km, 1.29 s at 5 km); leading dust edge is in-game front; grounded fireball has no dust, neither head/handoff, spray nor pall (surface 15 kt head rises from zero as front leaves sphere by ~0.13 s; near sphere wedge shorter than phase, younger/lower/thinner at back; full-phase wedge by ~0.6 s; airburst sphere never touches ground). Wedge lofting grows by post-front dynamic-pressure impulse, top by dusty boundary layer (Kuhl et al. 1992: front path behind point to power 5/6, actual decelerating path along normal over dust age, not arrival speed × phase, which gave kilometer tops at epicenter; under airburst not ground trace, faster than front near epicenter, but slant-range increment for regular reflection, stem path in Mach region: ground trace gave 52 m under 15 kt at 592 m, sagging to 36 m away, now 23 m and rising; fresh front starts from zero, meters in first 0.1 s; 15 kt at 592 m: 6–30 m at 0.25 s, 23–70 m at phase end; GRABLE 6–25 m in first tenths). Ground density is their ratio, densest near leading edge (one tenth into wedge, 2.6 times back density); behind wedge, half-phase handoff to pall, combined depth equals layer depth; when wind stops lofting, head detaches/fades, dust stays (MET: “stopped moving along ground at 945 m”). Leading head rolls: billows in dust coordinates, born where front lofted dust, stay there shifted by post-front air path over phase (U·t₊/e, Friedlander, tens of meters); against departing front, dust goes up/back. Head rests on pall terrain map; light shared with pall (diffuse/beams by wedge optical depth): dark inside dense head, meters visible, top sun/fireball-lit, bottom fire-lit at night; daytime face away from sphere gray-brown in sky/sun, orange only where fireball light reaches. Mushroom skirt billows sit on underlying terrain; same head volume at camera (pall draws at eye too), land particles only litter, removed pieces and surface litter (square eye-local billows would duplicate volume dust; fallback wall-lit billows `NukePall.dustColor` only when pall not drawn and only inside head); no ground shake before front, arriving wind pushes standing body (`BodySway`, below) | Artem: wave must cover surface, not one plane, feel like a “doomsday wave”, camera shakes more as it approaches; 17.10: wave is no smoke source, lofts/carries existing matter in a “horizontal” vortex, “like real life, based on real physics laws”; “Silo” frame only inspiration; 18.10: one physical dust process, head the youngest/lowest/densest part of layer that later remains pall, instead of two fitted curves. Old-head deviations: own envelope up to 300 m near center (~155 at 1 km), edge 0.25 s behind front, downwind density u² − u₀²; now height/density from entrainment and Kuhl layer like pall, low (tens of meters) as measured. Unsourced values (est.): airburst Kuhl path along normal (∫cos α over ground trace, cos α square root of along-ground dynamic-pressure share; Kuhl/Mirels front runs along wall); roll axis 0.3 wedge, linear edge/back density, handoff 1–1.5 wedge lengths; roll direction inferred from wall wind profile (ground cannot be fastest due to friction, fastest just above, so leading dust rises), no nuclear-wave roll measurements; old billows had unshadowed fireball glow, orange on all daytime sides, now only where light reaches (“Silo” frame lit by fireball itself: wall top orange, front dark, like ours at night) |
| §2, §6 — post-wave dust/debris settle (epicenter dust haze as minute-long fog) | volumetric pall behind front (`client/nuclear/PallField`, `client/fx/layer/PallPass`): same dust layer as wall head, laws from “Blast-lofted dust” survey (`nuclear/model/DustModel`): along-ground wind-lofted soil (regular reflection dynamic-pressure share `HobBlast.alongGround`) over local positive phase (rough-plate friction velocity/suspension, Gaj and Small 1991; below 10 entrainment thresholds saltation; dry loamy sand on land, centimeter available soil), debris dust where houses collapse (from 0.6 psi, full at 6 psi); top initially Kuhl dusty boundary layer, 50–100 m after seconds (Rosenblatt “several hundred feet”, 60–150 m at 4–5 s), flash-heated ground raises it over a minute (entraining convective layer: 120–350 m at 1 km, 80–230 m at 1.4 km); five size bins (3–400 µm, MET/MISERS BLUFF II) settle from mixed layer, coarse in minutes (fallout emits ash), fine hangs hours, diluted (e-fold ~13 minutes) and drawn into stem near epicenter; optical depth geometric optics, mix 108 m²/kg. At 15 kt/592 m (Hiroshima): 1.42 km beneath layer night until minute 20, brighter by 30 (Fujii in Hersey), 3.2 km twilight (Tanimoto), 300 m clears in minutes (Takakura); surface 15 kt pall visible ~1.6 h, airburst (592 m) ~1.7 h (longest at 1 km; at 3 km ~0.9/~1.1 h). Dust age model seconds since in-game front arrival like head; drifts at half detonation wind since lofting (dust edge on front in every direction); lights like dust-wall head (`CloudPuffs.ball`, `burn` shared with shader/camera dust): fireball above by overhead column, fires below by dust below point, neighboring-billow shadow toward main light, sphere at night; nighttime dust skylight ≥ 0.17 on screen (`FarView.ambient`), no black curtain. Smoky haze above ignition zone (900 m visibility) until morning (time of day at detonation, ≥ 5 minutes: within 5 min of sunrise means next morning; then world clock, sleep/`/time set day` at night means morning, `/time add`/`/time set` chooses nearest sunrise no earlier than 5 min after detonation), downwind plume, fire-lit underneath at night; EFX muffling, falling ash/paper/litter (`NukeAsh`, particle group `ASH`); modeled client field, no packet | Artem: dense city pall for minutes, then haze while city burns until morning; no custom damage-state packet needed, client knows pressure/front by same model; pall height from epicenter ground (hills protrude, valleys fill); 18.10: head/pall one process by literature laws. Deviations: old fitted curves (instant 25 m, growth 4 m/s, 150 m center, 150 s e-fold settling, pressure-based density) replaced by laws; dust stays dark tens of minutes, visible hours, matching Hiroshima accounts. One land surface (dry loamy sand), no cell classes (grass retains soil, city, sand) yet, Artem to decide. Fitted to Hiroshima or otherwise inferred (est.), not sourced: debris dust 0.1 kg/m² and fines loss 800 s together using burst-height ground pressure (`HobBlast`; old Kinney–Graham ×1.8 used 0.5 kg/m²/1000 s); stem draw (within 1 km, from 10 s, over 90 s), heated-ground rise over 60 s/capped 400 m, wave-phase visible top 0.1 of Kuhl law, flash heating-air share 0.2 (survey 0.1–0.3), γ 5 K/km, bin shares/settling speeds between table nodes; entrainment wind follows pressure positive phase (`HobBlast.positivePhaseSeconds`), while survey §8.1 ties it to usually longer dynamic-pressure phase; lofted share by phase fraction (`DustModel.lifted`, shapes head wedge) is dynamic-pressure impulse ∫(1 − x)²e^(−2x), not mass entrainment law (`soil`: saltation ∝ u*³ decays faster late in phase, so actual lofting near wedge edge is larger) |
| §6 — water absent from design | no dust over water: wall, plume, skirt use white spray/mist, same spray-head volume at camera plus crest drops (billows fallback only when pall not drawn); spray separate layer/height/law, not dust: own pall row, rises to crest-stripping height by post-front wind (`WaterModel.sprayHeight`), coarse drops settle, top lowers to 0.6 of it, density thins over 30 s (60 m visibility), clear after three minutes; over-water head same lower spray wedge, 0.5 s behind dust edge, same pall handoff; dark front ring on water an effects-layer decal (`fx/layer/WaterDecal`, ripple/foam by post-front wind and age). Burst on/low over water (below half sphere radius) gives hollow water column/spray crown (mushroom volume, `MushroomField`) and pall base surge scaled by ∛W from Baker and shallow underwater 100 kt (Glasstone, Dolan), `WaterModel`; condensation cloud is post-front Wilson cloud, no separate fog ring; water from client chunks/map tiles (DH), distant unknown defaults sea surface level 63 | Artem: film quality; test-footage sea wave is dark ripple circle with spray wall, not brown dust; 18.10: spray has own initial height/law. Values (est.): spray top falling to 0.6 stripping height, spray wedge shaped like dust |
| “Cinema-level nuke” plan step 3 — light with flash: burned ground, scorch, melted snow, singed biomes, shadows/face scorch during pulse not wave; flash layer is ruin-free trace (`scorch` at pressure 0), prebuilt in flight, no fires | flash layer (`FlashScorch`, `RuinPlanner.flash`): same `land` pass without pressure over 3×3 detonation ruin-snapshot window, `FlashFaces` face scorch; built in background after detonation, applied to in-memory chunks when light at point nearest sphere reaches ignition threshold (`Detonation.flashTicks` from emitted pulse share; pulse 10·t_max, 1.24 s for 15 kt), player-visible first; fires in layer, no ticks until chunk ruins; wave ruins use pre-flash world (layer snapshots reverted via `RuinPlan.unflash`), written over layer: layer fire absent from ruins (half from 2 psi, removed roof) extinguishes, rest gets ticks; no layer near Sable craft without loaded neighbors; client (`FireField`, `FireStorm`, `NukeAfter`) ignites with flash/blows out with wave likewise. No in-flight prebuild | Artem: all light effects with flash, fires too; wave only pressure. Pre-flash ruin plan gives same world as before and stays valid after layer (preplan retained); ruin fires subset of layer fires (same position roll), wave only extinguishes them; 3×3 window matches ruin face scorch. Prebuilt layer unimplemented: no city measurement whether visible layers finish by pulse end |
| “Cinema-level nuke” plan step 4 — firestorm: planned fires become 32-block-grid sources (chunk mark: strength/time), clustered shader light instead of 4 `FxLights` | client-modeled sources, no packet (`client/nuclear/FireStorm`): ruin/sound ignition law (`FireField`), district/cell flammability from detonation-seed noise (ragged edge), flash ignition (step 3), wave blows out some fire on arrival; flames catch over 12 s, grow e-fold over 80 s, from minute 6 (cell ±30 %) decay toward smolder over 9 min e-fold, extinguish by pall morning; known water/ice/snow (local chunks/terrain tiles) do not burn, unknown uses epicenter (distant sea burst never burns sea). GPU slot fire map (RGBA16F cells: flame, smoke feed, column rise) with mips: levels are source clusters, glow at height h from level with cell ≈ 2h, column from width level, each ground cell own column (merge at ~65 blocks; columns narrower than a third ray step read wider, thin ones made “rain”). Column smoke in same pall volume (`fx_pall`), more wind drift with height, billows (noise edges, broad columns pall billows), darkens in own sun shadow (soot scatters little), top spreads into haze; glow below lights dust/haze/smoke, noise flicker, entire map lights surrounding dust (1/r², light attenuates as pall e^(−kτ), dust phase: city glows under smoke at night from 2 km), uniform table haze glow disabled then. Surface flames in pall composite by world depth (also DH LODs; beyond render distance without DH, pall terrain `PallGround`), within 96 blocks only real vanilla fire (visible model fades in to 144). Fire tornadoes at strongest cells (three for 15 kt), one every couple minutes from 150 s after ignition, 45–90 s, Lamb–Oseen vortex with curl noise, wander at 1.5 blocks/s. No storm/fire haze if server does not ignite (`Detonation.fires`, settings at detonation in packet), fades where fire put out (young local-fire check `FireStorm.trust`, survives seek); pall OFF disables storm (fires still light stem underside: city burns, only smoke hidden) | marks exist only in loaded chunks, city burns for kilometers; strength/time packet would send what client already models like pall; mips give same light/smoke clusters as lists, one sample/ray step, no lists/per-frame sort. Late joiner after local fire burned out cannot verify it, uses model (trust 1) |
| “Cinema-level nuke” plan step 5 — pall at ground/in depth | pall ray without world behind (beyond render distance without DH, beyond DH LOD edge) stops at coarse underlying terrain (`client/nuclear/PallGround`: 128² cells over whole bounds, client chunks/terrain tiles, unknown from known surroundings, epicenter ground cell-weighted), not epicenter-ground plane; dense pall smooth billow shape (gaps to twice mean), two lightward samples for shadows, diffuse brighter toward source, shoulder for light > 0.6; `far/glow` halo larger than quarter image is shader formula, nuclear halo, warhead trail head and flash afterimage formula-only (`nuke_flare`, no `nuke/flare` image) | valley below epicenter ground put below-horizon ray under plane at eye, leaving no pall, sharp horizon cutoff (trailer n36); client already has terrain (chunks/DH tiles), no packet. Pall height still relative to epicenter ground (local terrain next step): dense dust fills valley to bottom. Dense dust light is source function, density-independent: structure only from light differences, absent with stepped shape/isotropic glow (flat orange frame) |
| “Cinema-level nuke” plan step 6 — pall over water/shore: white, weakly absorbing, forward-scattering spray/base-surge mist, settles/evaporates in minutes; shore mix by underlying source matter with drift; water-share terrain channel | spray own pall row (`PallField.spray`: post-front wind `WaterModel.spray`, 60 m visibility, thins in 30 s) and crest-stripping height (`WaterModel.sprayHeight`), coarse drops fall, top lowers to 0.6 of it, not dust-layer top (dust step 4, 18.10: previously dust-row fourth channel at dust height); base surge via slot (`PallWater0/1`: radius, top, extinction), same model laws/clock as billows (`PallField.surge`), full surge drift like pall surge (dust/spray drift since lofting). Water share channel 2 of `PallGround` (RG16F), shader samples at same drift-subtracted source as dust table (haze/fire glow at source on plume axis); unknown nearest known, epicenter known; absent land/water means absent table matter; light path through spray uses transport optical depth (delta-Eddington, 0.28 full at g ≈ 0.85), eye ray full; color like spray wall | without material, pressure-based dust everywhere made brown haze over sea (`nuke-water` frames). Spray needs row, not dust channel: own height (crest stripping, not hot-ground layer), top descends as drops fall; dust channel would impose dust top. Row read only with water under pall, one extra table sample/step beyond dust/haze; surge analytic (known shape/time), zero samples/step; water/terrain same map, one sample/step only if water present. Source matter because dust table also read there, both share drift. Nearest known, not mean: water is categorical (shore continues, sea stays sea); averaging pond spreads pale spray over unknown land, half-dust over shore sea. Transport depth because drops scatter forward: full depth toward light makes inside mist gray like dust. No packet: client knows water (chunks/map tiles/sea level under epicenter) |
| “Cinema-level nuke” plan step 7 — first-principles pall light: multiple scattering by depth/albedo, frame exposure, billows visible over tens of blocks; dust/spray one model with own numbers | matter (`client/nuclear/PallLight`, `pall.glsl`): particle albedo ω/asymmetry g (dust/smoke/haze 0.6, drops 0.85, soot 0.5) after delta-Eddington: forward peak f = g² joins unscattered light, table extinction is σ' (both eye ray/light path), scattering with ω', residual g' = g/(1 + g); ω' from thick-layer colors (dust/spray walls) by inverse two-stream reflectance: dust ω ≈ 0.91–0.81, spray almost nonabsorbing, fire smoke ~0.5. Light is Eddington two-stream in plane layer (table depths above/below point without noise, floor from local `PallGround` terrain): sun/fireball beams above, sky above, fires below dust, ground below (land 0.2, water 0.06); point scatters beams toward eye with Henyey–Greenstein phase, diffuse I₀ + g'μ·I₁; same layer casts ground shadow. Linear light: Minecraft sky in linear values, ~one fifth daytime light; sun remainder to white (daytime white-block top 1). Screen: pall matter color (light divided by opacity) converted to screen γ 2.2; world shadow to power 1/γ; screen colors (wall, soot, flame) converted to linear, wall/fire glow added to sky on screen becomes difference of conversions; fire light (fires/storm/tornado) no brighter than flame (linear flame color per channel). Billow cascade: small billows quarter size within large (mean 1, unchanged visibility) while ray resolves them (below pixel/third step use mean, no sample); billow shadow optical-depth deviation summed by scale (large tile steps, small quarter-tile). No spray transport depth (0.28, step 6) anymore | thin lit sea mist became almost white wall at ~45 s (`nuke-water`): full eye depth plus forward phase peak already in transport depth, counted twice; diffuse ignored overhead depth; dense dust after a minute flat orange without structure within ~80 blocks (`pall-low`). Nighttime floor dust fire light used wall glow inverted through dust color, three times brighter than flame (wall billow already fire-colored on screen): frame > 1, shoulder flattened color; scattering matter no brighter than source. Layer floor at epicenter put valley viewers/all local dust “at bottom” in full fire light, no vertical gradient. Shadows need both scales: unshadowed small billows are flat density spots; small-only shadows lose large masses (whole optical-depth units in thick dust versus ~0.3 small). Screen-space light made midtones dark (0.3 linear = 0.58 screen), fog dirty-gray, twilight black without billows; radiative transfer linear, screen nonlinear: calculate linear, convert matter color like all frame transparency (effects layer uses same). Two-stream instead of light samples: depth above/below without per-step samples (3 solves × 7 exponentials, 6 with fires below dust); previously 7 noise/2 table samples/slot, now up to 10, 2, terrain map (floor). No separate fog numbers: wall color, particle asymmetry, optical depth only |
| §1.3, §6 — mushroom of 300–1200 square billows (`MushroomCloudRenderer`) | volumetric mushroom in pall pass (`fx_pall`, same ray/light/shadows as dust). Law-based shape (`nuclear/model/MushroomModel`): sphere rises/rolls into vortex ring over 2.5·√(R/g) (Turner, Morton–Taylor–Turner), dome above, recess below entered by stem (radius share of cap per Glasstone/Dolan 2.17: half up to 20 kt, 0.2 at 1 Mt, 0.1 at 10 Mt; widens by third toward cap; before rollup, sphere trail 0.16 radius); ground dust flare (stem foot while dust remains at least 0.38 cap radius; kiloton stems already wider), skirt behind; foot inflow: wind carries dust carpet into stem, top bends upward along wall (“ground–wall” corner flow ψ = x·z, layer dust undiluted, no carpet/stem step, band tapers to wall by height where carpet dust equals stem, `MushroomField.foot`, `StemFootTest`). Cap droplets/NO₂ (§1.3): rolled ring reflected ochre, transmitted reddish-brown; growth dilutes NO₂, drops whiten outside (15 kt light beige at minute, almost white at two), transmitted/under-cap light stays brown (Glasstone/Dolan 2.08; Taylor/Minge 1963), no color clocks; stays dust-gray only if sphere touched ground (`MushroomModel.soil`), distant fades in altitude-thinning haze (`Sight.Air`). Signed-distance field (ring/dome smooth minimum), edge displaced by billows: noise channel B domes (`VolumeNoise`, inverted Worley F1, round with smaller domes; Perlin elongated patches, equal octave gradients make shadows shimmer); stem patches stretched by rise. Table (`client/nuclear/MushroomField`, 68 RGBA32F texels/mushroom, up to 4, black-body light row), shader laws (`mushroom.glsl`) checked by `MushroomGlslTest`. Light: solar two-stream along sun axis, nearest 0.75 billow tile uses actual billows (5 segments twice old length), then mean field; sky via vertical/eye-ray layers (eye depth actually traversed), stronger of two; trough sees sky through gap, lit by neighboring walls (cavity share/(1 − R∞·(1 − share)), R∞ hemispheric-mean two-stream, ≥ 0); glow black body (`BlackBody`, Planck/CIE curves) at ring-core temperature, cooler air-mixed edge; sphere/cap light stem/skirt/water column (M2 below). Fires below like pall storm glow: mean zone flame from map (water does not burn), visible fire-disk share at height R²/(R² + h²), storm smoke below; night city colors stem bottom, barely kilometer-high cap, not by day/over water. Cap rolls/churns (`mushCapLobes`): matter circles ring cross-section at top-rise speed (Turner 1957, ω = w/a, `MushroomModel.churnRate`), churn clock integral of ω from detonation in per-event table (`MushroomModel.churn`, texel 12.w; age function matches seeks/late entry); 15 kt period ~25 s at second 20, cap stops by stabilization. Billow pattern advects around ring axis by Rankine vortex: cross-section core rotates as whole, outside angular speed 1/r² (otherwise dome crosses mushroom axis), stagnation at mushroom axis fades rotation; two half-life-offset layers (Neyret 2003), lifetime rise/fall over half cross-section (1.26 clock radians, Kolmogorov); dying layer sinks into cap, new pattern rises, edge upper of two with mean correction; clocks shifted by smooth half-cap patches (one clock would make cap breathe together), weak layer unable to reach surface skipped. Empty ray steps by edge-distance estimate with frame jitter. Daytime world shadow: sun-plane 128² tile/mushroom each frame, sunlight transmission at three heights; one sample/mushroom on world, then pall; direct e^(−τ) plus cloud-forward-scattered two-stream sunlight with obscured-sky share w²/(w² + d²): thin stem mostly beam shadow, broad low layer like infinite layer. Sphere lights world (15 kt until ~12 s night, ~9 s day): Lambertian cap-radius black-body sphere, L·R²/max(d², R²), through overhead pall, normal from frame depth, block color pixel divided by Minecraft open-sky light (weaker in caves/houses); no sphere shadows from terrain/houses. `nuke_dust_quality` controls sphere/mushroom, dust wall/pall (OFF removes wall/pall, sphere/mushroom LOW) | Artem: volumetric cinematic mushroom by physics. Billboard squares cannot shadow each other or transmit through depth; volume shares pall transfer in one pass. Mean-field sun shadow flattened cap into sphere, soft surface edge tens of depths. Rising 15 kt cap almost spherical (bottom ~0.6 top); broad flat caps are megatons reaching tropopause (G&D 2.17: 10 kt cloud roughly as wide as tall). Old stem 0.16 cap radius at every yield (from billow frames), three times thinner for kilotons than G&D, “dandelion on thin stalk”. Planned churn (plan step 3) used curl-noise own texture, only moves old billows; churn means billows swelling/falling, extra texture would enter pall pass. Core faster than shell (Lamb–Oseen) invisible: cap opaque (15 kt ring-crossing droplet τ tens, ~130 after rise), visible shell moves at rise speed; no stem axial spin, no angular-momentum source (atmospheric moment would spin via inflow over tens of minutes). Foot inflow patches rotate while rising (up to radian at ground, zero by merge), presentation, not physics, showing carpet drawn into stem without changing dust size/time/path. No `fx_pall_resolve` velocity field, as with cap rise since step 1: shell speed same order (downward edge rotation nearly cancels rise), history clamped to current neighbors. Sphere light applied to world pixel, not overlay (blending only attenuates), pass copies frame while sphere shines; no sphere shadows yet, need world depth map from sphere (own block/LOD pass), normals already give facing/away slopes/faces. Shadow map instead of four sunward steps/pixel (M6 plan): cap hundreds of meters thick a kilometer up, sparse steps would intermittently miss, striping shadow; map uses 128 steps once/frame, also usable for pall/particles |
| §1.3, §6 — “Physical nuke” plan step 5: measured/physical mushroom rise/color, no fitted numbers | one sphere/cloud rise law (§1.3, `CloudModel`): top from maximum sphere top (1.3 radii above burst) exponentially to stabilized top, center follows (`FireballModel.centreHeight`, speed `FireballModel.riseSpeed`), so sphere top never outruns cloud, mushroom top/ring center never descend even during rollup. Nodes (kt → top above burst, τ): 1 → 5.66 km/227 s (Wasp), 15 → 9.84 km/121 s (Grable), 1000 → 21.5 km/144 s (Glasstone/Dolan fig. 2.16/table 2.12), 10 000 → 30 km; measured heights AGL (§1.3); stabilization 3τ: 1 kt 11.4 min, 15 kt 6.1, 1 Mt 7.2; initial rise speeds 25, 79, 139 m/s; bottom 0.6 of path, cap radius (2 km at 15 kt) unchanged. Droplets/NO₂ (`MushroomModel.capDrops`, `capNo2`) replace τ = 40 and 60 s NO₂ clock: drops τ = 3·LWP/(2·ρw·r_e), NO₂ detonation molecules throughout cap volume × per-channel cross-section (Bogumil et al. 2003); color same delta-Eddington two-stream as pall (`MushroomField.capMatter`: per-channel ω'/g' in texel, green depth), gray cap via thick-layer `CAP_DUSTY` soil absorption; trough-wall reflectance hemispheric-mean two-stream R∞ (`mushWallR`, `MushroomField.wallReflectance`), not Eddington (backscatter γ₂ < 0 at ω' < 1/(4 − 3g'), R∞ to −0.07, hidden by zero clamp). Sky glare (`NukeFlash`)/amber (`NukeSky.amber`) use air-scattered sphere light: total direct+diffuse transmittance (`ThermalModel.transmittance`, Glasstone fig. 7.104), not `exp(−d/V)`; sphere glare (`NukeRenderer.fireballs`) beam `Sight.Air`; amber/flash one light, detonation weather (`Detonation.visibility`). Lifetime stabilization plus turbulent dispersion (§1.3, `MushroomModel.lifeSeconds`): particle-pair separation to cap radius, (R²/(g·ε))^⅓ (Richardson–Obukhov), not 4 stabilization times. Stem jets rise at 0.3 cloud rise (`MushroomModel.stemRise`), not initial speed for whole lifetime. Water-burst steam mixed as matter, extinction-weighted ω' (`MushroomField.capMatter`), not screen-layer color. Flame color still in four places (`FireStorms.FIRE_R/G/B`, `FIRE` in `fx_pall` with `NukePall.FIRE`, `WHIRL_FIRE`, fire light in `NukePall.light`), no single black-body source; retained, labeled eyeballed (1100 K black body 1 : 0.26 : 0) | Artem: derive nuke from physics/measured numbers, mark non-derived values `(est.)` and check against observations, no local frame fitting. Values (est.): liquid water content 0.5 g/m³, r_e = 10 µm (cumulus); NO₂ fraction of NOx 0.1 (checked: nearly white outside after rise, brown transmission Taylor/Minge, rolled-ring reddish-brown transmission Glasstone/Dolan; 0.3 beige after rise, 0.03 orange-brown transmission); NOx proportional yield, droplets with rollup share (no condensation timing sources); soil-cap asymmetry toward pall dust 0.6 makes surface-cap σ' 2.3 times droplet-only; spheroidal cap volume, log-linear top/τ between nodes, τ above 1 Mt fixed at 1 Mt; sky glare 0.6 screen at full transmission/world-flash threshold 0.05, no yield or 1/d²: world flashes to ~5.3 visibility at any yield (airburst at 240·∛Y m: clear 1/15 kt ~107 km, 1 Mt ~119; rain 27/28/44; storm 6/7/17; beyond half visibility extrapolates Glasstone fit), but second-maximum fireball light there 0.5 % noon at 1 kt, 4.5 % at 15 kt, 90 % at 1 Mt: nighttime flash, daytime small distant yields overstate glare. Water-burst steam 0.45 cap extinction share at full water/rolled ring (old screen-color share; no steam at sphere); ε = 4·10⁻³ m²/s³ at cloud (chosen to keep 15 kt lifetime near old, largest round value giving monotonic lifetime; quiet free troposphere 10⁻⁵–10⁻⁴, cumulus 10⁻³–10⁻²; check 1 Mt visible 68 minutes, “about an hour or more”, Glasstone/Dolan §2.15), first half dispersion intact mushroom, second fades; stem flow 0.3 cloud rise. Stem τ 4/skirt τ 2 unchanged, unsourced, dust separate step. Differences: 15 kt/592 m top 10.4 km AGL not 12 (Grable 10 km), 1 kt/240 m 5.9 km not 6.1; 1 kt sphere rises 25 m/s like Wasp, not 85, 1 kt cap stabilizes in 11 min not 5 (Wasp still rising at minute 8), thinner (ring-crossing τ' 5–13 versus 40), core shines through at night. Rolled ring reflected ochre (15 kt hue 35°), not reddish-brown: that is transmitted light, reflected NO₂ never redder than 29°. Surface sphere before drops only NO₂ (ω' = 0), at 15 kt/5–12 s 1.4–2 times brighter than old dust color from start, no scattering reduction at edge. World flash farther, clear to ~107 km not ~50. Lifetime 17 min at 0.1 kt, 22 at 1 kt, 27 at 15 kt, 68 at 1 Mt, not 45/45/24/29; 15 kt stem jets rise only 2.9 km and stop with cloud (old 24 m/s throughout, 34 km), 1 kt 1.7 km; first water-burst steam no longer jumps ω' to 0.38 (edge glow fell to ×0.77, fresh 1 kt ring grayed). Amber sky uses detonation weather like flash, unaffected by later rain changes. Unimplemented: core light through cap direct only, e^(−τ'); two-stream emission would also escape diffusely as e^(−kτ'), red at 30 s threefold for 1 kt, ~200-fold for 15 kt, stronger interior glow at night |
| §6 — fireball plasma icosphere (`FireballRenderer`), crown/halo images | sphere same shape/volume as mushroom cap (M2 “Cinema-level nuke” plan): before rollup, cap is fireball sphere (`FireballModel.radius`, `centreHeight`), ring grows by rollup share without image swap. Apparent temperature from Stefan–Boltzmann and double-flash luminosity/radius until second maximum (P ∝ T⁴R²: first blue-white pulse, minimum, 7 700 K at second maximum, Glasstone/Dolan 2.110; 15 kt minimum ~10 ms, shorter than frame under white flash, so no visible double flash), then cooling toward air ΔT ∝ (t/t_max)^−0.35 (footage: 15 kt ~3 900 K at 1 s, ~1 950 K at 9 s, core, rolled edge cooler below); black-body light with eye exposure/frame shoulder (white → yellow-white → orange). Nearly isothermal, nearly smooth sphere; baked heat pattern (`NukeBallSurface`, `tools/bake_fx`, Mantaflow: hot-gas bubble with charge-vapor clumps rises/mixes with air): before minimum smooth shock surface (G&D 2.117–2.119), clumps emerge through clearing front by breakaway (15/11 minimum time, G&D 2.120, 2.125; Brode 1963; ~4.5 % variation, correlation 0.15 R from Trinity 25 ms, ⚠ film response), then pattern follows rise path in sphere radii, bottom darkened by cool-air inflow; modeled light power (mean p⁴ over sphere 1); rolling edge from quarter rollup time mixes with air to 0.35 core temperature excess, billows grow to full amplitude. Lambertian cap-radius sphere light L·R²/d²: beam on stem/skirt/water column with path depths, effects-layer particle light, glare/veil with visibility probe (terrain/DH LODs, pall/dust wall to sphere front, mushroom caps/stems/skirts, own cap cannot hide sphere; no glare clipping at quad corners, dust not counted twice), amber sky instead of time envelope. Matter light adds to background: ray-end overbrightness becomes coverage cover·L, saturating white over background; otherwise clear-air 10 km haze share 0.14 makes sphere pale daytime patch. Volume noise prepares at world entry, sphere present from first instant without launch warning. No plasma/crown/halo images | Artem: volumetric mushroom/sphere by physics. Difference: surface darkens from rollup (15 kt ~3 s; edge at 5 s 2 200 K, 9 s 1 250 K dark red, 12 s ~830 K brown smoke); old disk orange to 9 s, faded by 16. Day sphere brighter than white wall to ~7 s; night (eye ×1200, `Sight.adapted`) cap edge glows to ~10–11 s, not planned ~150: hot core deep in cap (15 kt ring-crossing τ' 11–36, half to core, direct-only core light, step 5), visible air-mixed edge 0.35 core excess; 1 kt τ' 5–13 allows core through at night. Same light on wall/pall/Wilson cloud/camera dust (`CloudPuffs.pallLight` → `pallBallLight`), not old separate law: daytime first-second wall top/facing side orange, outer side sky/sun-lit (sphere low behind head; 15 kt at 3 s/1.5 km ≈ 11 noon suns); night overexposed sphere-lit areas until ~8 s, glow ends ~11 not ~16 |
| §6 — ICBM `LayerDefinition` model | OBJ like other projectiles (`client/render/WeaponModels`, `tools/gen_models.py`) | one projectile-rendering path across mod |
| §8 — `effects_scale` 0.1–1.0 | 0.005–1.0 | GameTest destruction on 64×64 platform (scale 0.01–0.07) |
| §8 — `clear` removes fallout fields | `clear` forgets whole detonations | otherwise chunks loaded after cancellation still destroyed; client mushroom also fades |
| §8 — `ops_only`: anyone launches without it | with `ops_only = false`, non-operator pays ammunition (ICBM or carrier plus “Nuclear warhead” item) and second key: another player within `second_key_distance` (16) during `second_key_window` (30 s), solo online skips key; ≥ 90 s warning before strike (ICBM ≥ 90 s flight, carrier starts 90 s after target alert); everyone gets “ICBM launch detected from area X, Z” (256 cell) and siren; nuclear cancel/retarget only operator/host, `nuke now` operator only (`strike/NuclearKeys`) | unrestricted friend nukes would strike without warning/consent; real-launch-style rules: two keys, early detection, alert |
| §8 — launch-origin presets (here/distant) | player launches “here” (missile visible), console/command block “distant” | choice without extra button |
| §9 — warning packet | adds `alarm`, `mine`, scale; server decides alert/“my missile” per player | client lacks `warning_radius`/owner |
| §4 — wave damage: 20 psi fatal | body model (`world/BlastBody`, `model/BlastInjury`, Glasstone & Dolan ch. 12): lungs, throw into solid/tumbling, window shards, burial (§4 below) | pressure alone does not kill: throws/glass/collapse kill below 20 psi, open-field people survive even 20; Artem: strike must kill realistically, by physics |
| §8 — `max_fires` / `max_debris` 4000 / 300 | `fires_per_detonation` 20000, no debris entities (§3.2 above) | fires apply per chunk with flash (flash layer, step 3), cheap; 4000 at 15 kt sparse |
| §8 — `time_budget_ms` 4 | whole `work_ms_per_tick` 30 minus impacts/blackout share | Artem prioritizes strike properties over optimization: destruction keeps front pace, TPS drops during wave |
| §3.6 — craft explosion power 6…60 immediately at front arrival | same 6…60, queued under front budget; only craft blocks, no entities, no destruction without `block_damage` (Sable push remains) | otherwise TNT-style explosion destroys ground within ~100 blocks bypassing `nuke_*`/`block_damage`, hits/throws entities within 120 blocks again (already pressure-hit by front) |
| §3.1 — `LevelTickEvent.Post` processing | `ServerTickEvent.Post`, one budget/server tick across dimensions, next dimension goes first each tick | per-dimension budgets would double with two dimensions; rotation prevents endless waiting |
| §4 — `incoming:burns`: no natural regeneration, running damage | healing allowed but health capped by burn loss until healing (game weeks); no running injury | burns remove health share by burned skin until healed (§4 “Light”); running pain is not a wound |
| §4 — creative mode | meter shows field, no burns/dose | build/inspect aftermath without sickness |
| §3.5 — crater profile relative to epicenter | depth/rim relative to each column's natural ground, per-chunk progress saved | slope crater does not flatten hill to epicenter; resumes after restart |
| §4 — instant thermal/prompt radiation, all loaded chunks queued at once | detonation tick snapshots only; entities (nearest first)/chunk enqueue under tick budget, light before front | hundreds of mobs/thousands of chunks would stall detonation tick 0.2–0.4 s; real pulse also lasts about a second (Glasstone §7.85) |
| §3.1 — “epicenter chunk not loaded for destruction” | loads in background 10 s before detonation | exact terrain height/crater soil needed; background load never stops server |
| §3.1 — enqueue chunk when loaded | queued while in memory (not only fully loaded); loaded-world edge holds ticket with neighbors until processed; after 7 days detonation forgotten for fallout but retained for destruction (`NuclearEvents.past`) | view-edge chunk drops below full loading/returns without new `ChunkEvent.Load`, player-area edge would wait forever for neighbors, leaving untouched seams/bands |
| §4 — mobs only instant dose | accumulate burst/fallout dose, same sickness stages; `mob_radiation` | otherwise mobs “ignore” zone radiation |
| §2 — modeled front (supersonic near epicenter, sound farther) | modeled out to two sphere radii (≤ 200 blocks), smoothly to 600 blocks toward far-zone sound-speed share `nuclear.wave_speed` (default 1.0, real 17.15 blocks/tick; lower is gameplay deviation), beyond dust-wall edge 0.3 psi sound at sound speed (`FrontProfile`); pressure still modeled distance function | Artem: real life, but server must keep up/player see and understand, one far-zone speed. 16.10 chose 0.42 from three videos, 18.10 real-life default 1.0, slower only by setting. Wall/haze/sound/damage/knockback/ruins (chunk center) share arrival time |
| §4 — front hits ring in its tick | front position each tick, covered-body hits queued under shared budget, nearest first, after own detonation's light pulse (`BlastFront`) | hits include death/loot/all pack death handlers: host's wave through hundred epicenter mobs takes 84 ms in one tick |
| §5 — deafen all new sounds | existing loops too (own from owner, others `SoundFilters.loops` on tick until hearing returns: Sound Physics every tick, otherwise on filter change), except music/records/tinnitus; wave sounds use pre-wave hearing | filter remains until sound end: pre-wave loops otherwise clear through hours of deafness; music not world sound |
| §5 — hearing (`HearingModel`) | linear threshold shift between threshold and 5 psi; lows third of highs; above 40 dB recovery doubles per 10 dB; waves separated by > 2 game minutes (~1.7 s) take largest separate shift, no energy addition (seconds-spaced salvo deafens like strongest wave); no permanent loss, both eardrums together, indoors like outdoors, ringing volume not modeled, death/world exit restores hearing | little single-long-wave threshold-shift data; anchors are energy threshold/injury at eardrum-rupture threshold; no different-dose shift-addition data; ear pressures nearly equal, windows long broken at 0.34 psi, wave enters house; hearing client state |
| §6 — shake from `P` at arrival | front impulse/body lean in wind (`nuclear/model/BodySway`): transient flow loading (G&D ch. IV: chest reflected pressure decays to stagnation over 3S/U, back unpressurized L/U then 4S/U to full; body thickness 0.25 m, half-width 0.2 m, ANSUR II; G&D building idealization, body values (est.)), ~2 ms gives immediate lean velocity: 1 psi 11 N·s (8°/s), 3.5 psi 38 N·s (27°/s), along-ground cos α share; camera from body front-arrival moment, fractional tick. Ankle inverted pendulum with balance (delayed feedback via muscle activation, Peterka 2002, van der Kooij & Peterka 2011; numbers Pasma et al. 2017 without slow torque feedback, < 1 % over seconds), standing drag area 0.66 m² (Cd 1.0–1.3 Hoerner, area 0.6 m²), along-ground dynamic pressure/phase from detonation (`Detonation.dynamicPressureKpa`, `HobBlast`), wind square root with Friedlander phase. Optimal airburst, back to wave, including impulse: 15 kt (1 psi phase 1.3 s), 0.3 psi 1.2°, 1 psi 7.3°; 2 kt (0.6 s), 1 psi 5.4°, 1 kt 4.9°. Stability limits forward 8°, backward 4.5°, sideways 8°, diagonal ellipse (McCollum & Leen 1989), reached facing wave from ~0.77 psi at 15 kt/~0.88 at 2 kt, back ~1.06/~1.35; person steps there, lean stays at boundary while wind exceeds balance, straightens as wind weakens, balance restarts at edge (linear feedback would pull “fallen” body back through vertical to opposite edge). Negative-phase reverse wind leans toward burst (Friedlander ≤ 13.5 % front pressure, dynamic pressure 1.8 %): 2–3 psi gives 1–2°, reaches edge back-to-wave (backward 4.5°) ~4.6 psi, facing (forward 8°) 6–8 psi. Numbers at `effects_scale` 1, smaller shortens listener phase/reduces lean; multiple lean waves add (small-angle linear pendulum). Head with body: exact camera rotation about world-horizontal axis across lean, decomposed heading/pitch/roll (`CameraShake.leaned`; heading about world vertical, independently adding sideways angles while looking down would spin image). No ground shake before/with wave; wall within 12 blocks toward burst scales impulse/wind by open-ray share; not standing (riding/swimming/flying) no lean, jump fades lean over quarter second, restores on standing; shake setting scales lean; no flash push | Artem requested approach-growing shake (§6 above), approved physical pass 18.10 (step 7), but visible ground shake absent in reality. Airslap arrives with wave, drives ground down then partly back (G&D §6.12–6.16); ground velocity p/ρc (plane ground wave, ρ 1.8–2 t/m³, c 450–1500 m/s (est.), no G&D value; analysis design/nuke-film/sources-check.md §8): 1 psi 0.2–0.9 cm/s, displacement 1–4 mm; 15 kt at 592 m, 1.38 km (~6 psi Brode) 1.5–5 cm/s, 0.4–1.6 cm. Body feels millisecond-rise acceleration 0.1–10 m/s² versus perception threshold 0.015 m/s² (ISO 2631-1 annex C), but screen cannot: ground/player/surroundings move together, eye shift relative to distant ≤ 1.6 cm (100 m 0.01°), ground slope = ground velocity/ground-front speed (~430 m/s at 1–1.5 km), ≤ 0.007°; 1080p/70° pixel 0.065°. Deviation: no felt impulse, game cannot transmit body acceleration, camera shake invented. Pre-wave solid-ground motion weak at 1–2 km, 0.1–3 s early (est.), cannot calculate (G&D §6.84), absent; light does not push. Pendulum lacks stepping/falling beyond stability, held at edge while wind stronger, server models throw; step threshold by static lean limits, but wind center of pressure reaches foot edge earlier: wind-only foot torque implies steps facing wave ~0.6 psi at 15 kt/~0.8 at 2 kt, back ~0.8/~1.1 (needs two-link stepping model); wall wind shadow from three rays (est.); no neck/head sway (own head impulse gives half velocity of body lean at head height) |
| §5 — own sound synthesis | recordings (CC0/CC BY) plus synthesis | recordings more realistic; synthesis supplies modeled front hit, clatter, infralow hit absent in recordings |
| §5 — Wilson cloud | epicenter biome humidity (always over water); volumetric pall fog shell (`BlastWall`, `pallWilson`), negative phase behind front at 0.93 radius, half-thickness 0.06, 0.15–3.5·∛(Y/15) s, sphere/sun/sky-lit, patchy breakup | game has no air humidity |
| §7 — acceleration 0.5 → 25 blocks/tick, vanishes above ceiling + 500 | Minuteman III first-stage thrust/mass acceleration (`WeaponSpec.Ascent`: ~1.5 g liftoff, 1.8 g/8 blocks/tick at 10 s), simplified pitch (vertical 3 s, then 85° above horizon); vanishes above world ceiling + 320, ~8.5 s from y 64 | acceleration to 25 blocks/tick would leave sight in ~4 s at 350 m/s, real one ~60 m/s at 4 s; no open Minuteman pitch program; strike timer independent |

## Checks implemented in code
- Model unit tests (`mod/src/test/.../nuclear`): pressure, front arrival, light, burns (`BurnInjuryTest`: fig. 12.64
  thresholds, §12.65 spread, table 7.35 clothing, §12.62 shock), prompt radiation, fallout, crater, sickness, blast
  injuries (`BlastInjuryTest`: G&D ch. 12 tables, dummy at 5.3 psi), eyes (`EyeInjuryTest`: 10 s at fig. 12.88 range,
  longer closer up to cap, longer at night, retinal-burn range). Burn GameTest `BurnGameTests` (batch `nuke_burns`):
  cow shock, shirted villager third-degree face/arm burns without ignition (same flash cannot hurt twice), shirt
  ignites at 20 cal/cm², iron-armored pillager intact, leather burns, underwater cod intact; second detonation never
  postpones shock; shock deadline kills even with fire resistance.
- `EyeGameTests` (batches `nuke_eyes*`): retinal burns where sphere lies on retina, none to side/behind, 40 km only at
  night; entity-light path accumulates up to 8 spots, none in creative; ridge 100 blocks away hides sphere at 2 km
  (sparse shadow points would skip it).
- Post-strike sound unit tests: terrain-echo side, geometric delay, levels, ridge shadow, flat ground
  (`TerrainEchoTest`), clipping-free track mixing (`EchoMixTest`), burning-city/secondary-explosion model (`FireFieldTest`).
  `PanicGameTests` (batch `nuke_panic`): cows, husk, villager flee flash/wave and calm down; sitting tamed wolf/owned
  horse stay; penned cow searches paths less often.
- GameTest (`mod/src/devtest/.../NuclearGameTests`): tree toppling/block thresholds, shadow, crater profile, budgeted
  chunk queue/chunk mark, basement shielding, strike save/cancel, ICBM launch.
- Headless client: `tools/client_scenario.sh nuke` — ICBM launch, warhead entry, flash, sphere, front, mushroom, black
  rain; `tools/client_scenario.sh pall` — post-wave pall from hill, inside, above, twilight/night (`SCENARIO pall`);
  `tools/client_scenario.sh pall-front` — night from 400 m/120 m above ground, front arrival to one minute
  (`INCOMING_EXPORT=2560x1440`: game frame larger than window, like Flashback export).
- Pall unit tests: `PallFieldTest` (coverage, profile/column, dust behind front only, time, haze until morning/world
  clock, ash, dust-top growth; spray over water settles in minutes, table includes only matter beneath pall, matter
  from lofting source, base surge only for water burst, no ash/haze over water), `PallGroundTest` (terrain below pall;
  unknown water like epicenter, shore continues, sea stays sea, camera lake never spreads onto land), `PallGlslTest`
  (shader/Java laws match, including layer light/screen conversion; matter colors dust/spray wall, night pall lit like
  local wall; storm column laws, base-surge profile, spray color same as wall, fire light no brighter than flame),
  `PallLightTest` (two-stream energy conservation, thick-layer color also on screen, thin fog glows by depth not white
  wall, dense lower dust twilight not black, ground shadow, wall glow over sky, fire light ≤ flame, nighttime fire-lit
  dust shadowed not flat), `PallNoiseTest` (seamless noise, mean billow shape 1, small billows preserve mean
  density/visibility, blue noise), `PallDetailTest` (detail by GPU budget), wave-wind body lean `BodySwayTest`,
  `CameraShakeTest`; `ShaderSourceTest` guards Veil (Sable) shader rebuilds, every program `uniform` declared in JSON.
  Headless `tools/client_scenario.sh nuke-water` (sea burst, shore viewer), `nuke-lake` (land burst beyond pond) check
  over-water pall.
- Firestorm `FireStormTest` (ignition-law sources inside zone, ragged edge, ignition not before wave, growth/decay/morning,
  map/seek update, no water/ice fires, rare short tornadoes at strongest cells); headless
  `tools/client_scenario.sh firestorm` (night, `INCOMING_FIRESTORM=day` for day): 15 kt airburst, frames from 2 km/800 m
  and inside near tornado at ~1/5/15 minutes, pall time with/without storm.

## 0. Main decisions

1. **Real scale.** 1 block = 1 m, yield in kilotons, radii from open-source formulas (Glasstone & Dolan, “The Effects of
   Nuclear Weapons”, 1977; ground pressure by burst height, Brode 1987/DNA standard; checked with NUKEMAP). Default
   15 kt at 592 m (Hiroshima): 5 psi at 1.6 km, broken windows to 8–9.5 km, third-degree burns in half of people at
   2.0 km. Small worlds have `effects_scale` (1.0 real life).
2. **No per-tick effect packets.** One detonation packet to all dimension players (position, yield, burst height, wind,
   time). Each client computes flash, sphere, mushroom, wave, delayed sound using same shared `nuclear/model`.
   Late joiners receive active-event list on entry.
3. **Destruction independent of loaded chunks.** Detonation saved in `SavedData`. Loaded chunks processed as front
   passes, queued under per-tick time budget. Unloaded chunks get same damage on load (data-attachment chunk mark).
   No destruction-driven chunk loads except crater footprint.
4. **Cheap chunk processing.** Skip entire sections whose palettes have no affected blocks
   (`LevelChunkSection.maybeHas`); far zone (glass/leaves only) skips almost everything.
5. **No custom rendering shaders.** Only vanilla `RenderType`s with own textures, supporting Sodium and Iris/shaderpacks.
   No thousands of particles: sphere mesh, mushroom a few hundred sorted billow billboards in one batch.
6. **Default carrier: intercontinental ballistic missile (ICBM).** Point target (real ICBMs strike coordinates). Warhead
   separate from carrier (`Warhead`), so nuclear payload on cruise missile/B-2 bomb (B61) almost free architecturally.
7. **Two time rates.** Visible flash, sphere, wave, mushroom rise in real time. Long processes (fallout arrival/decay,
   sickness) use game time: 1 game hour = 1000 ticks (Minecraft day = 24 h). Fallout “7–10” rule remains: after 7 game
   hours dose rate ×0.1, after 49 ×0.01. Firestorm/black rain from its smoke use own clock: real time compressed 15-fold.
8. **Physics sets events, presentation shows them.** §1–§4 model determines order, sizes, time, speed, destruction;
   no aesthetic number changes, only preset scale (`effects_scale`, `wave_speed`) and marked “not physical” deviations.
   Presentation (light, color, contrast, billows, detail, movement, sound, camera) may exceed camera footage, like a good
   film in Minecraft style; code marks it as presentation, changing no model facts.

## 1. Physical model (`nuclear/model`, pure math, shared client/server code, unit tests)

Notation: `Y` yield, kt; `R` slant range from explosion center, m (= blocks); `HOB` burst height.

### 1.1 Blast wave — `HobBlast`, `BlastModel`
- Ground overpressure by burst height/ground range (`HobBlast`), scaled `x, y = range, height / W^(1/3)` (kft/kt^⅓):
  - down to 2 psi, Brode fit to height-of-burst curves (H. L. Brode, “Airblast from Nuclear Bursts — Analytic
    Approximations”, PSR Report 1419-3, 1987, eq. 62): regular reflection near epicenter, Mach region beyond, no
    “×1.8 reflection”;
  - from 1 psi outward, DNA 1 kt standard (Needham & Crepeau, DNA 5648T, 1981; formulas in MIT glasstone package):
    free air doubled by Mach-region reflection (`free(R/∛2)`), Rankine–Hugoniot reflection at epicenter; Brode fit
    approaches zero there (15 kt/592 m: 0.3 psi at 8.8 km, negative farther), standard decays as weak spherical wave,
    1/(R·√ln R);
  - 1–2 psi smooth in ln P. Pressure decreases with range at any burst height (`HobBlastTest`).
  Precomputed ln R/angle table (384 × 91 nodes, < 0.6 % error over 0.05–3000 psi). Elevated point treated as ground
  with burst lowered by its height, above burst as surface hemisphere: upper bound (est.), not data. High above ground,
  incident wave arrives, reflection later/separately; together as Mach stem only near ground below triple point
  (tens–hundreds of meters), whose height model lacks. Above airburst, pressure exceeds incident by 1.4 at 2–5 psi,
  up to 1.9 at hundreds psi; craft 300 m AGL at 1/1.5 km from 15 kt/592 m: 8.3/4.1 psi versus incident 4.0/2.3 (craft
  breaks farther: 3 psi to 1.9 km, old Kinney–Graham ×1.8 to 1.45).
- Dynamic pressure (post-front wind throws people/vehicles): `q = 2.5·P² / (7·P0 + P)` (Rankine–Hugoniot); along ground
  share `1 − (1 − m)·sin²α`, α incidence angle, m Mach transition (0 to 0.8·XM, 1 from XM): regular-reflection air
  angled to ground, no along-ground wind at epicenter (G&D fig. 3.75). Mach onset
  `XM = 170·y / (1 + 60·y^0.25) + 2.89·(y/100)^2.5` (y ft/kt^⅓; 15 kt/592 m 694 m).
- Ground arrival/positive-phase duration: Brode PT subroutine (same source); airborne closer than burst height uses
  DNA free air `t = r²(6.7 + r) / (7.12·10⁶ + 7.32·10⁴·r + 340.5·r²)·∛W` (joined to Brode under burst). Slant-range
  arrival table `ArrivalTable` (1000 logarithmic nodes); distant front sound speed 343 m/s = 17.15 blocks/tick.
  After positive phase, **reverse wind** (negative phase pulls toward center), updrafts toward fireball.
- Check (`HobBlastTest`): ground ranges, 10 % tolerance to Glasstone & Dolan curves (fig. 3.73), 1 % to Brode equation.
  Airburst height `240 m · Y^(1/3)`:

| Y, kt | Burst | 20 psi | 12 psi | 5 psi | 2 psi | 1 psi | 0.5 psi | 0.3 psi |
|---|---|---|---|---|---|---|---|---|
| 1 | surface | 220 m | 285 m | 465 m | 855 m | 1.4 km | 2.4 km | 3.6 km |
| 1 | 240 m | 145 m | 350 m | 650 m | 1.15 km | 1.8 km | 2.9 km | 4.2 km |
| 15 | surface | 545 m | 705 m | 1.15 km | 2.1 km | 3.5 km | 6.0 km | 8.9 km |
| 15 | 592 m | 360 m | 860 m | 1.6 km | 2.8 km | 4.5 km | 7.2 km | 10.4 km |
| 100 | 1.1 km | 675 m | 1.6 km | 3.0 km | 5.3 km | 8.4 km | 13.5 km | 19.5 km |
| 1000 | 2.4 km | 1.45 km | 3.5 km | 6.5 km | 11.5 km | 18 km | 29 km | 42 km |

  Beneath airburst at 240 m/kt^⅓: 28.5 psi. 15 kt/592 m arrival under burst 0.79 s, at 1 km 2.17 s, 2 km 4.64 s,
  5 km 13.05 s, 10 km 27.5 s; positive phase 0.45 s under burst, 0.74 s at 1 km, 0.93 s at 2 km (down to 2 psi Brode),
  1.29 s at 5 km, 1.44 s at 10 km (below 1 psi listener Kinney–Graham, §5: Brode weak-wave phase plateaus at 1.2 s,
  real grows with range). Surface 15 kt: 200 m 0.07 s, 1 km 1.6 s, 2 km 4.3 s, 5 km 12.9 s, 10 km 27.4 s.
- Conventional charges: Kinney–Graham (`BlastModel.surfaceOverpressureKpa`) by TNT mass.

### 1.2 Fireball — `FireballModel`
- Maximum luminous radius (Glasstone 2.12): airburst `0.07·Y^0.4` km (15 kt → 207 m), surface `0.088·Y^0.4` km
  (15 kt → 261 m, 1 Mt → 1.4 km).
- **Double flash** (nuclear signature): first pulse < 1 ms, minimum `t_min ≈ 0.0025·Y^0.5` s, second maximum
  `t_max ≈ 0.032·Y^0.5` s (15 kt 0.12 s, 1 Mt 1 s), 80% light emitted over ~10·t_max. Client uses frame milliseconds,
  not ticks.
- Color: blue-white → white → yellow-orange → dark red (cooling) → ochre ring (reflected NO₂; transmitted reddish-brown)
  → white cloud (gray if sphere touched ground).
- Rise with cloud top (§1.3): initial 1 kt 25 m/s, 15 kt 79, 1 Mt 139 (1 Mt at 10 s 130, Glasstone/Dolan §2.05: 76–107),
  drawing stem. `HOB < R_ball` means surface burst: ground contact, dense dirty stem, strong fallout.

### 1.3 Mushroom — `CloudModel`
- Measurements: Nevada test clouds (Hawthorne 1979, DNA 1251-1-EX; red lines in Blakeley et al. fig. 2,
  LLNL-JRNL-2012579, digitized at 300 dpi, ±0.1 km): Grable (15 kt/160 m) top 1.73 km AGL at 15 s, 2.65 at 30 s,
  4.14 at 1 min, 6.15 at 2, 7.53 at 3, 9.03 at 5, 9.97 from minute 9; bottom 6.1–6.2 km. Wasp (1 kt/232 m) top
  0.68 km at 15 s, 1.60 at 1 min, 3.94 at 4, 5.27 at 8, still rising (~4 m/s over last minute); bottom 3.2 km at
  minute 6.5.
- Heights AGL, not sea level: axis says “km AGL”, early points below sites' sea-level altitudes (Wasp top 0.68 km at
  15 s, bottom 0.82 km at 30 s; Yucca Flat ~1.28 km, Frenchman Flat ~0.94 km) — sea-level interpretation puts clouds
  underground. Uncertainty: graph reading ±0.1 km (2–15 % early points), primary source DNA 1251 not checked.
- Megaton: Glasstone/Dolan 1 Mt top at 0.3/0.7/1.1/2.5/3.8 minutes is 2/4/6/10/12 miles above burst (table 2.12,
  “rough averages”; first-minute mean ~134 m/s); stabilized 1 Mt ≈ 21.5 km, 10 Mt ≈ 30 km (fig. 2.16: cloud reaches
  tropopause, 10 Mt penetrates; heights below 15 kt there relative to burst, higher relative to sea level).
- One sphere/cloud rise law: top from maximum sphere top (1.3 radii above burst) exponentially to stabilized top,
  h(t) = h₀ + (h∞ − h₀)(1 − e^(−t/τ)); sphere center follows (`FireballModel.centreHeight`), sphere top never outruns
  cloud; mushroom top/ring center never descend during rollup (`MushroomModelTest.topAndRingNeverGoDown`). Initial
  speed (h∞ − h₀)/τ: 1 kt 25 m/s, 15 kt 79, 1 Mt 139.
- Nodes, top above burst/τ: 1 kt 5.66 km/227 s (Wasp fit RMS 0.9 %, worst 2.6 %; at 8 min 5.22 km, still 3 m/s);
  15 kt 9.84 km/121 s (measured top, fitted τ: 4.0 % over 13 points, worst 11 % at 15 s, within 5 % from 30 s);
  1 Mt 21.5 km/144 s (τ fitted to table 2.12: 11 %, worst 18 %, nearly steady rise stops at tropopause, exponential
  smooths it); 10 Mt 30 km, 1 Mt τ (est.); log-linear between nodes (est.). Wasp→Grable top grows Y^0.20,
  close to thermal ¼ law in stable air (Morton, Taylor, Turner 1956: height ∝ buoyancy^¼·N^−½). Rise time ∝ 1/N,
  independent of yield: Wasp τ twice Grable/megaton reflects air stability there/that day, not yield, so τ nodes use
  measurements as is, not yield law.
- Stabilization three τ (95 % path): 1 kt 11.4 min, 15 kt 6.1, 1 Mt 7.2 (98 % by 10 min, “about 10 minutes”,
  Glasstone/Dolan §2.15).
- Cap bottom 0.6 of burst-to-top path (Grable 6.1 of 10 km, Wasp 3.2 of 5.3). Cap radius ~2 km for 15 kt, ~10 km for
  1 Mt ((est.): no radius measurements, sources 1.5–2.3 km for 15 kt).
- Cap droplets/NO₂ (`MushroomModel`): drops whiten reflected light, NO₂ keeps transmitted/interior brown
  (Glasstone/Dolan §2.08; Taylor/Minge 1963, AFSC 61-142-1 pp. 201–202). Droplet depth τ = 3·LWP/(2·ρw·r_e)
  (Stephens 1978), water content 0.5 g/m³, r_e = 10 µm (cumulus values, (est.)): extinction 0.075 1/m, stabilized 15 kt
  ring-crossing τ ≈ 130 (~36 after forward-peak removal, delta-Eddington); droplets arrive with air entrained during
  rollup (no condensation timing sources). NO₂: 10²⁹ NOx molecules/kt (NRC 1985 ch. 6; Gilmore 1975 0.8–1.5·10³²/Mt),
  NO₂ fraction 0.1 (est.), throughout cap volume; frame-channel absorption cross-sections Bogumil et al. 2003,
  243 K, blue eight times red. No color clocks: small cap dense NO₂, rolled ring reflected ochre (15 kt screen
  ~0.60 0.48 0.31, hue 35°; 1 kt darker 0.36 0.26 0.15), transmitted reddish-brown 17°; growth dilutes NO₂, outside
  whitens (15 kt minute 1: 0.88 0.82 0.70, minute 2 nearly white), transmitted light always brown. Reflected ring never
  reddish-brown at any NO₂ fraction: strongly absorbing reflectance ∝ ω' ∝ 1/σ per channel, hue 29°, darker not redder;
  Glasstone/Dolan “red or reddish-brown” cloud is transmission/cooling-sphere glow. Thick-layer reflectance uses
  hemispheric-mean two-stream (R∞ 0–1), not Eddington, whose R∞ < 0 under strong absorption.
- Cap is toroidal vortex: surface billows rotate poloidally (up inside, outward over top, down outside;
  Glasstone/Dolan 2.20), the mushroom's “boiling”.
- **Wilson cloud** (condensation ring/dome) first 1–3 s in humid air (biome `downfall`, always over water); hidden in
  deserts.
- **Dust skirt:** ground lofts dust where pressure > ~3 psi, ring follows front, drawn into stem.
- “Ice cap” veil above high-yield cloud (> 100 kt), thin disk above cap.
- Mushroom life (`MushroomModel.lifeSeconds`) stabilization plus dispersion: atmospheric turbulence disperses stopped
  cloud, particle pairs separate as ⟨r²⟩ = g·ε·t³ (Richardson–Obukhov relative dispersion, g ≈ 0.5, Ott/Mann 2000,
  Sawford 2001); after stabilization, cap radius R mixes over (R²/(g·ε))^⅓. ε = 4·10⁻³ m²/s³ (est.) (no test-cloud
  measurements), chosen for near-old 15 kt lifetime, largest round value keeping lifetime increasing everywhere
  (> 4.2·10⁻³ stabilization shortens just above 1 kt faster than dispersion lengthens); quiet free troposphere
  ε 10⁻⁵–10⁻⁴, cumulus/weak turbulence 10⁻³–10⁻². Dispersion 1 kt 10.6 min, 15 kt 21, 1 Mt 61; life 0.1 kt 17 min,
  1 kt 22, 15 kt 27, 1 Mt 68 (check “about an hour or more”, Glasstone/Dolan §2.15). First half dispersion intact,
  second fades ((est.): old 4-stabilization-time appearance, intact to 2.5). Old four stabilization times gave 1 kt
  and below (Wasp τ) 45 min, longer than 15 kt 24/1 Mt 29, based on slow rise not size. Cap spreads as before, quarter
  radius per stabilization, at most 1.5 times; wind drift from detonation.

### 1.4 Thermal pulse — `ThermalModel`
- Energy density cal/cm²: `Q = f·Y·10¹²·τ / (4π·(100·R)²)`, `f` 0.35 airburst, 0.18 surface, transmittance
  `τ = exp(−(R / V)^0.544)` (direct/diffuse, fit to Glasstone 1964 fig. 7.104), visibility `V` 20 km clear, 5 km rain,
  1 km thunderstorm, same as distant visuals (`util/Visibility`).
- Range/trace thresholds: first-degree 3, second-degree 5, third-degree 8 cal/cm² (human/mob burns yield-dependent, §4);
  leaves/grass/wool ignition 5–10; wood darkens/smokes 10–15; surface melting/sand glass > 50–100 (near sphere).
- Skin burns in half people (§4), slant range (clear airburst): 15 kt third-degree ~2.1 km, first ~3.4 km; 1 Mt
  11.5/18 km; surface less (15 kt 1.5/2.5 km).
- **Shadows.** Straight-line light, point affected only if fireball visible. Blocks use ray heightmap check (3.4),
  entities `Level.clip` from eyes to sphere center. Naturally gives “Hiroshima shadows”, preserved slopes behind hills.

### 1.5 Prompt radiation — `PromptRadiationModel`
- First-minute dose `D = K·Y·exp(−R/λ) / R²`, `λ` 330 m, `K` 1.52·10⁹ rem·m²/kt, from Nagasaki kerma (RERF:
  7.8/0.89/0.13/0.021 Gy gamma at 1/1.5/2/2.5 km plus ~10 % neutrons). 1 Gy ≈ 100 rem.
- Low yields: lethal radiation farther than heavy destruction (1 kt LD50 beyond 5 psi by 440 m); high yields reverse.
  Model gives this naturally.
- Shielding (`radiation/Shielding`) by path mass: block = meter of material at its density (from block sound: stone
  2300 kg/m³, dirt 1600, wood 600, metal 7800, water 1000; glass is window), path per block by grid. Half-value layer
  (G&D table 8.41) 320 kg/m² first-minute radiation, 130 fallout gamma: stone block ×1/150/almost zero, dirt
  ×1/30/×1/5000. First-minute radiation: 70 % direct toward burst, 30 % sky-scattered (rays up/45° toward burst,
  share (est.)), trench not complete protection. Fallout on all sky-exposed surfaces: surface points on 1–16-block
  rings, 8 directions, ring weight uniform-ground power fraction inside r, ln(1 + r²)/ln(1 + R²), R = 100 m (gamma
  range in air), ray to each.

### 1.6 Fallout — `FalloutModel`
- Trace only when sphere touches ground (`HOB < R_ball`), fallout share smoothly grows toward surface burst.
- Wind chosen at detonation (random direction, 5–15 m/s), stored in event.
- H+1 dose-rate field (1 game hour later): downwind trace sums two Gaussians, epicenter (radius ~ `0.6·R_ball`, hottest)
  and downwind with growing width `σy(x) = 0.1·x + R_ball`, length ~ `L = 20 km · (Y/15)^0.45 · wind/10`. Initial
  constants; mod session fits to NUKEMAP 1000/100/10 R/h contours for 15 kt surface burst, 24 km/h wind (three-point
  test, 25% tolerance).
- Deposition (game hours): particle at downwind `x` reaches ground at `x / wind`, no earlier than largest particles
  arrive from cloud/stem, `4·Y^0.2` min (Miller model; 15 kt 7 min, 1 Mt 16 min). None under cloud/upwind/sideways
  before then. Local dose zero before arrival.
- Decay `Ḋ(t) = Ḋ₁ · t^(−1.2)`, `t` game hours after explosion (Way–Wigner, 7–10 rule), minimum half-hour.
- Black rain (`BlackRainModel`) from fires at any server-igniting detonation: over ignition zone/downwind band (widens
  by 0.2 of path, cloud rains out over 2 h travel), above fires from minute 20 for 1.5 h, later downwind by cloud
  travel time (Hiroshima, Uda 1953). Firestorm clock ×15. Airburst rain washes some fission products down: up to
  2 R/h at H+1 for 15 kt near fire zone ((est.) between Hiroshima/Nagasaki).
- No per-block field storage: formula of (event, x, z, t), cheap, chunk-independent.

### 1.7 Crater — `CraterModel` (surface burst only)
- Apparent dry-ground crater radius `23·Y^0.3` m, depth `7.7·Y^0.3` m (15 kt 52/17 m, 1 Mt 183/61 m). Rock ×0.7,
  water-saturated ground (underwater bed) ×1.3; dry sand/gravel like dry ground (TM 23-200: dry sand reference medium).
- Noisy-edge paraboloid, ejecta rim ~0.25 depth out to 2 radii, ejecta patches farther. Floor/walls partly glassy
  (trinitite, 3.3).
- No crater above `HOB > 0.1·R_ball`, only burned ground.

## 2. Look and sound over time (15 kt airburst at 580 m)

Real optimal 15 kt height ~580 m, above world ceiling 320. Fine: entities/effects may exist above ceiling; clients see
high-sky detonation like Hiroshima.

| Time | Player at 2 km sees/hears |
|---|---|
| −90 s | Within 20 km of target: “MISSILE ATTACK” siren (own, unlike drone), sounds until detonation; screen text, launcher's countdown. |
| −90…−70 s | “Here” launch: missile erupts from ground at launcher, first-stage roar, fire/smoke column; rises, leaves white contrail twisted/drifted by wind for ~two minutes. |
| −3 s | Bright reentry plasma streak over target, almost vertical from above. Silent: its sound arrives after explosion. |
| 0 | **Flash.** Whole screen white if facing/seeing burst point (strength by `Q`). Behind wall, sky/fog whiten, everything casts shadows. Double pulse in first 0.1 s. Sirens wind down in pitch/volume over ~4 s, then total silence. |
| 0…1 s | White-yellow fireball grows to 207 m; everything seeing it smokes/ignites (leaves/grass/wood/wool, mob/human burns). Visual-field glare for all who saw sphere/lit sky: tens of seconds by day, minutes at night; direct viewers permanent retinal-burn blind spot (§4 “Eyes”). |
| 0.5…3 s | Humid-air Wilson dome/ring around sphere. Front invisible; post-front wind lofts ground-rolling dust wall just behind. |
| 4.3 s | **Front arrives.** Sharp powerful hit/pop muffles game: tinnitus, all sounds dull 10–20 s. Camera tossed, player thrown outward by dynamic pressure, windows burst, doors/fences/leaves break, trunks topple away from epicenter. Hurricane wind one–two seconds, then terrain echo rolls. |
| 5…15 s | Reverse wind toward epicenter draws dust/debris back. Unextinguished fires grow. |
| 10 s…7 min | Sphere cools dark red, rises/rolls into mushroom cap (~10 km by 6–7 min); rolled ring reflected ochre, transmitted reddish-brown, outside whitens in 1–2 min, transmitted/beneath stays brown; dusty stem, boiling cap. Echo thunder continues tens of seconds. |
| 80 s…15 min | Black rain over fires (80–440 s), later downwind to ~15 min: dark drops, brown sky/fog, patter. Carries fallout in trace, mildly radioactive for airburst. |
| 7 game minutes… | Fallout deposits in surface-burst trace. Geiger counter crackles. |
| game hours…weeks | Radiation sickness: nausea after hours, latent period, weakness/hunger/no regeneration; dose decides survival. |

Player at 20 km (1 Mt) sees same, front after ~50 s, mushroom fills half sky.

## 3. World: budgeted destruction

### 3.1 Event and queue
- `NuclearEvents extends SavedData` per dimension: `Detonation` list — increasing `id`, `pos`, `yield`, `hob`,
  `burst` (air/ground), `gameTime`, `wind`, `seed`, `radiusMax` (greater of radius where `P` falls below 0.5 psi and
  first-degree burn radius).
- `ChunkScar` data attachment on `LevelChunk` (serialized `int appliedUpTo`), latest applied event `id`.
  `ChunkEvent.Load`/detonation (already loaded chunks): enqueue if in-range events newer than `appliedUpTo`.
  Chunk load itself only enqueues.
- `ScarQueue` priority by front arrival at nearest chunk point. Process no earlier than wave arrival (later-loaded
  chunks immediately).
- Server `LevelTickEvent.Post`, `nuclear.time_budget_ms` (default 4 ms/tick), `System.nanoTime()` after each column,
  shared budget for all events.
- Mid-processing unload means no mark, reprocess on next load (idempotent: destroyed already air, burned already burned).
- Only crater holds tickets (`ChunkTickets`, crater+rim radius) until dug.
- Newly generated chunks in radius also damaged, fairly: ground burned there too.

### 3.2 Column processing
For each chunk column (x, z), `r` → `P`, `q`, `Q`, sphere visibility (3.4). Stop if nothing changes in range. Then
sections top-down, skipping those without affected blocks (`section.maybeHas(BlockResponse::affected)`, palette check,
not 4096 blocks):
- **Above ground** (above natural-ground `WORLD_SURFACE` terrain): break if `P ≥ threshold(block)`.
- **Natural ground and below** untouched by wave (basements/bunkers survive like reality); only light-changed top
  layer (3.3) and crater (3.5).
- Writes `setBlock(..., UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE | UPDATE_SUPPRESS_DROPS)`, no neighbor-update cascade/drops.
  `ChunkHolder` batches client changes into `ClientboundSectionBlocksUpdatePacket` per section.
- Fraction becomes debris (`falling_block`, outward initial velocity, mod `Debris`), capped by `nuclear.max_debris`/event.

### 3.3 Block response — `BlockResponse` (tags + strength fallback)
Pressure thresholds psi (`data/incoming/tags/block/nuke_*.json`, datapack-overridable):

| Material | Threshold | Result |
|---|---|---|
| glass, panes, lanterns, torches, candles, flowerpots, cobweb, snow layer (`nuke_fragile`) | 0.5–1 | air (glass-break sound to nearby players) |
| grass, flowers, saplings, crops, fern, vines, moss carpet (`nuke_plants`) | ~8 | air with pressure-stripped turf; light burns them nearer edge (below) |
| leaves (`#leaves`) | 2 | air; at `Q` ≥ 10 fire in place |
| doors, fences, trapdoors, wool, carpets, planks, furniture (`nuke_light`) | 3–5 | air / debris |
| trees (log on natural ground + leaves) | 5 | toppled, horizontal logs on ground **away from epicenter** (radial blowdown like Tunguska/Hiroshima) |
| brick, cobblestone, stone brick, concrete, terracotta (`nuke_masonry`) | 10–15 | air / debris / gravel |
| strength 6–9 (iron etc.) | 25 | air / debris |
| strength ≥ 1200 (obsidian, netherite, bedrock) | never | fireball/crater only |
| untagged | strength ≤0.5 → 1 psi, ≤3 → 5, ≤6 → 12, ≤9 → 25 | |

Thermal changes, only blocks seeing sphere:

| Condition | Result |
|---|---|
| `Q` ≥ 5, flammable top block (leaves, grass, wool, wood, hay via `isFlammable`) | fire above, probability ~ `Q/20`, capped `nuclear.max_fires`; wave extinguishes some at `P` ≥ 2 |
| above-ground plants (`nuke_plants`, `Q` ~6) | grass/flowers/bushes/crops burn in patches with everything above (tall-grass top, reeds), ~8 % leave dead bush; snow layer melts, turf without snow loses snowy sides |
| turf/moss (`Q` ~15) | patchy dirt/coarse dirt; within burned area at light ~30, podzol scorch |
| snow block (`Q` ~15) | water |
| mean light in 4×4×4 biome cell | “singed”, “scorched”, “charred land” biome (`data/incoming/worldgen/biome`), permanent grass/foliage/water color only, on ground/map/LOD |
| inside grounded fireball (surface burst) | sand → `incoming:trinitite` (new greenish glass block), top 3 water blocks evaporate, ice → air |

Fire spreads as vanilla, respects `doFireTick`.

Turf/plant/snow/pressure-stripped turf (8 psi) numbers are medians: half of positions affected. Share increases by
lognormal fragility curve (`model/Fragility`), affected positions chosen by smooth world noise (`model/WorldNoise`,
FastNoiseLite OpenSimplex2 fBm with domain warp, patches to ~90 m) plus position hash; ragged patchy edges, not threshold
circles. Ground light includes incidence angle: facing slopes burn up to 2.5 times stronger, away slopes weaker,
penumbra at shadow edge (sphere not point). Light trace fades over final 20 % of ruin radius; no plans farther,
otherwise sharp circle. Small-scale strikes have patches at least 12 blocks. Biomes change only in rainy non-cold
climates (snow/desert remain), not oceans/rivers/caves (`incoming:nuke_keeps_biome`), only increasing severity; cells
change from 8 blocks below their columns' lower ground quartile.

### 3.4 Sphere visibility for a block (cheap)
Ray from sphere center to block top uses `MOTION_BLOCKING` heightmap, 16 samples over horizontal projection; terrain
above ray means shadow. One array read/sample, no `clip`; hill/building shadows.

### 3.5 Crater
Separate `CraterJob`: rim-radius columns sorted outward, one at a time, cut to profile depth; rim of ground material
(mod `GroundMaterial`), ~30% trinitite on floor/walls. Same budget. Immediately after detonation, vanilla `level.explode`
at center (`nuclear.core_power` 60) pushes Sable craft/nearby entities; everything else custom.

### 3.6 Create Aeronautics / Sable craft
Each sub-level within 3 psi on front arrival: vanilla `explode` at nearest-to-epicenter point, power from `P` (6 at
3 psi … 60 at 50+ psi), Sable breaks/pushes craft (`ExplosionMixin`). Farther, 0.5–3 psi, only companion API push if
available, otherwise nothing. Sole nuclear/Sable connection, all in `compat/SubLevels`.

### 3.7 Flash traces on faces (`nuclear/flash`)
Like Hiroshima photos: wood charred toward sphere, stone soot-darkened, light human shadow on wall behind.
- **Scorch.** Background chunk ruin plan (`RuinPlanner.finish`, `nuclear/world/FlashFaces`) takes surviving flammable
  (`incoming:nuke_chars`: planks/logs/wool/hay) or stone (`incoming:nuke_soots`: masonry/terracotta/sandstone) faces open
  toward sphere: `Detonation.fluence` × incidence cosine, ruin-window height shadow, face under full original neighbor
  clean. Tiers 1–7 by lognormal `Fragility` (median 10 cal/cm² flammable, 12 masonry, soot at most 45 % black); volume
  world noise/position hash choose patchy affected faces. Marks apply with ruins (`RuinPlan.apply`), on wave arrival
  rather than flash, same background/snapshot ensures consistency. Layer uses detonation ID/time even for prebuilt
  `NuclearPrep` ruins, shared with body shadows.
- **Body shadows.** Detonation-tick snapshot of living entities/vehicles in light reach (nearest first, ≤ 256); then
  one body/work unit under nuclear budget (`FlashShadows`): lit body gets 27 rays from sphere through bounds to
  12 blocks beyond; hit faces/planar neighbors get 16×16 2-bit masks (penumbra from three sphere-disk points). Figure:
  player-model boxes for tall/narrow, ellipsoid for other living entities, vehicle bounds. Shadow only on scorched faces.
- **Storage.** Chunk attachment `FLASH_MARKS`, saved: per-detonation layers with ID/time, scorch faces (position, side,
  tier, block hash in one int), shadow faces/masks. ≤ 4096 scorch/96 shadow faces per chunk (strongest above cap,
  old layers yield to new), ≤ 16 layers (oldest evicted, same limit in client packet decoding). Changed block at mark
  (ruins/player/command/piston/explosion) removes mark immediately via neighbor notification (`NeighborNotifyEvent`,
  chunk packet at tick end); same-type replacement cannot restore it. Without notification (some mods), hash mismatch
  removes on next save/send.
- **Clients:** `S2C.NukeFlashMarks` on change to chunk viewers, on `ChunkWatchEvent.Sent` to player; late entry/reload
  see same traces. Show layer when client clock reaches detonation time (Flashback/seek); mesh via
  `AddSectionGeometryEvent`: copied block-model face quads, darker vertex color, shadow brighter by mask cells.

## 4. People and mobs

- **Front** (each tick, `level.getEntities` in ring between previous/current front radius, only `LivingEntity`/craft;
  hits as budgeted units from 0.3 psi): `BlastBody` uses `BlastInjury` (Glasstone & Dolan 1977 ch. 12), `nuclear_blast`
  damage without vanilla knockback (`minecraft:no_knockback`):
  - pressure reaches bodies with an air path to sky (≤ 2048 cells; windows/leaves/wood doors/trapdoors/gates pass,
    wave breaks them), not bunkers/sealed caves; wind under sky (also canopy, blows through leaves), or where sphere
    visible through opening; houses without such window get pressure only;
  - front-facing window/back wall as at front arrival: glass bursts into body, wall reflects before falling, but
    ruins apply one tick early, so bodies near chunk (+5 blocks, window may be next chunk) remember before first
    nearby ruins (`BlastFront.remember`); by front arrival body may move, remembered window valid within 2 horizontal
    blocks, wall half-block. Throw-path wall from current world: wave-demolished walls fall before body arrives
    (front faster). Wall is ray-hit collision shape: carpet underfoot, slab below ray, off-path stair are not walls;
  - lungs by effective pressure: facing blast, pressure plus stagnation dynamic pressure; wall half-block behind,
    reflected; sideways/back, incident. Fatality 1 % at 40 psi, 50 % at 62 (table 12.38), severe injury 50 % at 25;
  - throw by post-front dynamic pressure (`Detonation`: along-ground wind/positive phase by burst height), dv/dt =
    ρ(u − v)²·Cd·A/2m, human Cd·A/m 0.6 m²/70 kg (check G&D §12.44 dummy: 5.3 psi 21 ft/s), mobs by bounds width/
    knockback resistance; then tumble deceleration 0.5 g. Solid on throw path gives impact at speed reached there
    (fatality 50 % at 16.5 m/s, skull fracture 5.5, table 12.49), otherwise ground tumble (injury 50 % at 22.9 m/s,
    fatality 69 m/s (est.)). Under airburst near epicenter, wind points into ground (regular reflection), little
    throw. Game throw capped at 6 blocks/tick;
  - window shards between eyes/burst (≤ 4 blocks, table 12.43: 80·√psi ft/s), severe injury 50 % at 245 ft/s
    (table 12.46);
  - burial: body inside ruin blocks after replacement but not before, 20 % fatal, another 30 % severely injured
    (Coburn & Spence masonry M4, earthquake-based (est.)); survivors suffocate until escaping.

  Outcome by per-body random value (UUID/detonation ID): dies below fatality probability, otherwise health loss is
  severe-injury probability divided by that value, capped 95 %. 15 kt/592 m: half thrown against wall two steps away
  die to 0.85 km, rare deaths to 1.45; open-field wave almost never kills (lungs only with back wall, to 0.65 km),
  tumble injuries to ~0.75 km, glass to 1 km (rare to 2). Surface 15 kt open field: half die to 0.4 km, rare to 0.55.
  After positive phase, light reverse push only for those reached by direct wind (hit still queued: by sky above
  body). Each body hit once: thrown bodies may outrun slow front.
- **Light** (`radiation/FlashBurns`, `model/BurnInjury`, Glasstone & Dolan ch. 7/12): budgeted immediately after
  detonation, nearest first, one `clip`/entity to most-sensitive first-degree range. Facing half of skin burns (§12.60).
  Yield-dependent thresholds fig. 12.64 (half people: 15 kt 2.5/4.5/7.2 cal/cm², 1 Mt 3.3/6.3/10; longer large-yield
  pulse allows skin heat loss), skin spread β = 0.18 (§12.65: 1 Mt, 4.5–6 cal/cm² second degree in 18 %), one skin
  random value across degrees. Clothing by armor slots: humans (players/villagers/pillagers/witch) empty slots mean
  short-sleeve shirt/trousers (thin cotton), shoes (dense cloth); face/hands/forearms exposed, 9.9 % facing skin;
  animals/monsters whole facing side, 49.5 %. Leather armor dense cloth, others metal, blocks light. Fabric ignition
  table 7.35 (shirt cotton 14, dense 20 cal/cm² at 35 kt, higher at high yields; below 35 kt slope extrapolation,
  (est.)): body burns 10–20 s, water extinguishes. Second/third-degree burns remove health fraction relative to
  shock threshold (third on 25 % body, second on 30 %, §12.62); `nuclear_thermal` bypasses armor (already screened
  light), no knockback; wound/`incoming:burns` cap in 10 % steps rounded up (90 % to shock threshold stays 90 %), based
  on unburned health; invulnerability-blocked wound (respawn/hit) removed only by cap, only health above it. Cap holds
  through healing, second degree 2 game weeks, third 5 (§12.54–12.55), first one week without loss. At shock threshold,
  burn shock: health 5 %, death after 0.5–12 game hours (`burn_shock`, armor/potions/enchantments cannot reduce; no
  in-game treatment, totem saves like any death). Second detonation burns same side, larger fraction wins, each burn
  own healing deadline (weaker new effect hidden under stronger old like vanilla), existing shock unchanged.
  Clothed flash burns face/arms only, 9.9 %, far from shock; flame burns under ignited clothes (§12.60) are Minecraft
  fire until extinguished. Undead/fire-immune/fire-resistance-potion entities do not burn; underwater light absorbed
  (underwater eyes checked against world, not entity flag, set only by entity tick); golems/shulkers lack living skin.
  Player-strike flash leaves rule-protected players entirely untouched (PvP off/friendly team), including self, no
  clothing ignition, like arrows.
- **Eyes** (`model/EyeInjury`, `radiation/FlashEyes`, client `NukeFlash`; Glasstone & Dolan §12.83–12.88). Anchor fig.
  12.88: 10 s flashblindness/retinal-burn ranges for looking at sphere (10 000 ft burst, clear day/night), log yield.
  Temporary whole-field glare (client): full eye pulse if sphere visible (through windows too), sky-scattered 0.15
  (est.) if no sphere but open sky, none under roof; 10 s at figure range, longer closer by log pulse, capped at
  recovery from full pigment bleaching (flash-lit ground before eye 1.5·10⁷ td·s, upper bound (est.): ground like sphere-
  facing surface though flat ground gets less direct light from low sphere), day one minute, night 10 (cone plateau
  of dark adaptation). Night-adaptedness by eye-local sky/block light 0–15 (sky darkness from time/weather like server
  vanilla, not recomputed client-side): day 15 daytime, open-sky night 4/darker nighttime; nearby torch nearly daytime
  eye. 15 kt day/3 km ~50 s, night/10 km ~7.5 min. Screen veil from pulse maximum (white frame before): bright
  afterimage darkens in seconds to gray by day/black at night, fades (1 − t/T)²; glare under 10 s scales veil down
  proportionally. Permanent server retinal burns (player `RETINA`, cleared by death, ≤ 8 spots): sphere seen within
  60° of gaze, inside figure range (day/night blend by eye light; burn range grows with visibility, air alone sets
  it, §12.86; surface same as airburst figure, no data, upper bound (est.)), spot at sphere's retinal position/size
  (pulse samples players' gaze first). Client soft dark spots in configured FOV, paler at sides (§12.85: peripheral
  burns usually unnoticed). Distant sphere visibility also local terrain: dense heightmap every 8 blocks to 512
  (all entities). Creative/spectator are cameras, not eyes: no glare/burn, existing spots hidden (Flashback viewer
  spectator); white frame/afterimage for all. Sleeping eyes closed; death clears glare. Player-strike retinal burns,
  like skin, respect launcher's injury rules (PvP off/friendly team); temporary glare/white frame affects all.
- **Radiation** for players/mobs (except undead; `mob_radiation` disables). Do not tick mobs every second: `MobFallout`
  adds fallout dose every 10 s under nuclear-work budget; mob's `radiation_sickness` handles same stages (nausea,
  hunger, clothing contamination players only). Players:
  - `RadiationDose` saved player data attachment (cumulative Gy, death resets); custom sync when change > 0.01 Gy.
  - Every 20 ticks, dose rate = sum of local fallout events (trace/black-rain washout) × wall/roof protection
    (`Shielding.fallout`, §1.5) + own contamination (below).
  - **Contamination:** black rain/fallout zone adds dust (`contamination`) irradiating until washed; water, not rain,
    removes over 5 s, real decontamination.
  - **Radiation sickness** `incoming:radiation_sickness`, no medicine (`fillEffectCures` override, milk no cure),
    course by Gy/game hours, `SicknessModel`, G&D table 12.108: prodrome (vomiting after 3 h at 1 Gy … immediately at
    50), latent period (weeks at 1–6 Gy, days gastrointestinal, none cerebral), manifest illness, recovery 5–10 weeks
    or death. Fatality compares own random value with lognormal curve (LD50 4.5 Gy, <1 % at 2, 80 % at 6, all at 10),
    one per irradiation (UUID/time dose crossed 1 Gy). Death 6 weeks at 2 Gy, 30 days at 6, 12 at 10, 5 at 20,
    36 h at 50, faster with dose. Manifest illness: weakness, hunger ×1.5–2, from 2 Gy no regeneration/−4 max health,
    from 6 Gy −10/weakness II.

   Vomiting: particles/sound, hunger loss. Dose death uses `radiation` damage/death message.
- **Panic** (`nuclear/MobPanic`): flash viewers (entity light) flee 10–20 s, wave ≥ 0.3 psi 20–40 s, away from epicenter.
  Each `PathfinderMob` gets highest-priority flee goal on entry (8–16-block outward point within 45° of straight line,
  every 2–3 s); brain mobs (villagers/piglins/goats) use `WALK_TARGET`, not villager “panic” activity (summons golems).
  All speed ~0.38 blocks/tick, bosses stay; start/repaths stagger by entity ID. Owned mobs stay: tamed/owned horse or
  llama, leashed, named (otherwise ordered-sitting wolf flees hundreds of blocks); sheltered wave causes no panic.
  Path at most once/second, enclosed/no-progress path (pen/farm) backs off to once/8 s. Custom destination, not vanilla
  `DefaultRandomPos`: random height (1 of 15 valid on flat ground), sideways/underfoot points made empty/one-step
  searches look penned, mobs stood for seconds (`mobsFleeTheBlastAndCalmDown` failed 4/30 runs). Airborne newly spawned/
  wave-tossed mobs wait for ground, cannot ground-path from air. Parrot/allay (navigation targets solid cell itself)
  use fallback vanilla `LandRandomPos`.
- Only deep underground survival at epicenter; wave/light/prompt radiation account for this without special cases.

## 5. Sound (recordings and synthesis like all mod sounds, `tools/build_sounds.py`)

Client receives event, schedules sounds at its range's front arrival (`ArrivalTable`). Volume by listener peak `P`,
not vanilla attenuation (`Attenuation.NONE`, position 3 blocks toward epicenter from ear): full at 1 psi (conventional
near perspectives full at 1.1–2.2 psi), below that half pressure decibels, like conventional −3 dB per doubling
(`Outdoor.pressureVolume`); speakers cannot exceed full, no increase above 1 psi. Same conventional `Outdoor` path,
ISO 9613-2: terrain edge along burst ray, epicenter/ear ground, rain, daytime shadow from source-ear ray touching ground
(√(2R)·(√hs + √hr): ground explosion ~1 km, 600 m burst beyond 9 km). No hearing-threshold fade: detonation audible far
beyond world extent. Each listener's echoes independent, unsynchronized.

Far away (< 1 psi), weak shock: one jump, positive-phase decay to rarefaction (Friedlander). No N-wave trailing shock,
which forms only beyond a hundred kilometers; ground reflection at 1.6 m ear arrives milliseconds later, beyond Mach
vertex one wave, no double bang. Listener positive phase `Detonation.positivePhaseSeconds`: below 1 psi nuclear
Kinney–Graham (KG85 NE, `BlastModel.positivePhaseSeconds(R, Y)`, 15 kt/30 km ~1.7 s; checked against Garcés 2019/ANSI S2.20),
nearer 2 psi Brode, smooth between (§1.1). Wave sustains shock steepness; weaker shocks rise more slowly, ~0.17 Pa·s /
`P` (sonic-boom measurements: 30–200 Pa, 1–5 ms; approximate, turbulent); 0.01 psi rises 2.5 ms (dull hit), 0.1 psi
0.25 ms (sharp). Below 20 Hz inaudible: seconds-long phase is impulse, not sound. Modeled shock at every range,
including near: law (listener pressure/phase), not recording, gives scale. All nuclear recordings native speed,
shift ≤ ±25 % (build `NATIVE`, client bounded pitch, `NukeTonesTest`); previously near explosion recording pitch 0.55,
roll 0.75, build hit/roll slowed 0.62/0.7 “eyeballed Sachs” (Sachs from recorded charge to kilotons would give tens-fold).

| Sound | When | Character |
|---|---|---|
| `nuke.alarm` | launch to detonation | wartime civil-defense siren recording, steady howl loop, pitch spin-up/down |
| `nuke.launch` | “here” launch | solid-rocket roar/crackle, fades over 20 s |
| `nuke.crack` | front arrival, `P` ≥ 1 psi | point-blank front hit (huge explosion/close thunder at native speed + synthesized clatter), three directions, intentionally clipped near epicenter |
| front hit (`client/sound/BlastPulse`) | front arrival | modeled own-OpenAL-source track from `nuke.boom_far` start: `P`-based rise, listener positive phase, 20 Hz cut (single front click tens of ms, 0.25 s track), hill/shadow highs by `Outdoor`, starts at beginning even frame-late; no own source means distant `nuke.crack_far` recording (same for 2 kt beyond 5 km/15 kt beyond 25 km) |
| `nuke.boom_far` | front arrival | distant explosion native-speed recording, then roll |
| terrain echo | after hit, by geometry | same hit recording from each returning slope, own OpenAL sources below |
| `nuke.wind` | positive then negative phase | hurricane loop: wind follows pressure (weak wave u = p/ρc), gust/phase decay, weak reverse rarefaction; volume ∝ u (ear wind noise ∝ ρu², Strasberg 1988), full at 33 m/s (hurricane recording) |
| `nuke.glass`, `debris.fall` | local front arrival + sound travel | actually removed nearby glass/debris (“Destruction” below) |
| `nuke.tinnitus` | hearing-threshold shift | 4.87 kHz pure beating tone, 10 s loop, volume by shift |
| `nuke.rain` | black rain | heavy drops, thinner than vanilla |
| `geiger.click` | meter | single click, Poisson rate by dose rate |
| `nuke.fire` | city burning | real house-fire recordings (Yle archive 1978, house burned for filming), native speed, four cardinal loops, volume by burning heat below |
| `nuke.fire_wind` | city burning, near fire | inward fire wind (native-speed hurricane loop `nuke_wind`), from fires |
| `nuke.collapse` | minutes after wave, nearby ruins | support crack, impact cascade, falling rubble, synthesized; with `debris.fall` |
| secondary explosions | 30 s – 10 min after wave | own Shahed/missile explosions in burning districts (visual/sound front, no destruction) |

**Terrain echo** (`client/sound/TerrainEcho`, `EchoMix`, `OwnSources`; `client/nuclear/NukeEchoes`). While front travels
to listener, 72 rays from ear over heightmap (`Sightline.Heights`: client chunks, terrain-map tiles beyond; to 4 km,
step grows with distance) find faces above ray horizon, visible to ear. Lambertian reflected direct-hit energy share
`ρ·cos θ₁·Ω/π·(p(r₁)/p(rₑ))²` (θ₁ incidence on heightmap normal, Ω face solid angle at ear, p modeled pressure; near
epicenter capped at spherical spreading); flat ground low-frequency reflection nearly specular/forward, tenth
scattered, full for slopes ≥ 30°; slope behind ridge from blast gets quarter by diffraction. Delay is in-game front
to slope (`arrivalTicks`) + slope-to-ear sound-speed path, relative to direct ear arrival. +12 dB over diffuse model
(large-slope lows nearly specular), each reflection ≤ −3 dB direct. Same direct-hit recording start/seed variant/pitch,
background mixed into 8 cardinal tracks (11 025 Hz, 10 ms windows, neighboring power cos²/sin², extra-path air low-pass
by length), tail via own-slot EFX reverb decaying by delay spread. **Own OpenAL sources**, bypass engine: Sound Physics
Remastered filters all engine sources, would add echo to echo. Track EFX on direct/send: directional obstruction
(three 12-block rays along/above/steeply up; indoor echo muffled like direct, also with Sound Physics which leaves own
sources alone) and deafness. Delay starts at actual direct sound (`PlaySoundSourceEvent`, anchor before `play`,
engine-held buffer starts immediately), not tick: non-preloaded file starts later. Rays unfinished at arrival (entry/
teleport then) run 24/tick, echo starts later. Live only: no echo on seek/world entry; removing detonation silences
voices/loops.

**Burning city** (`client/nuclear/NukeAfter`, `FireField`, `FireWatch`). Client lacks server fire settings, so distant
`FireField` model: 32-block rings, column-burning share same as ruins (`min(1, q/20)`, 60 % columns flammable), flash
ignites, ≥ 2 psi wave blows out half; default `fires_per_detonation` 20 000 using server post-wave count (15 kt airburst
40 000 before wave to ~146 blocks, 20 000 after), grow 20 s, decay with 5 min time. Local chunks count real fire blocks
(fire-palette sections sample every 8th block, chunks fitting 0.1 ms/tick; detailed-city scenario 0.13 ms/tick versus
3.2 full count), place at actual positions; distant model weighted by real/modeled local fires (disabled/extinguished
means silent), excluding local burning: server ignites player chunks too (replaced first), one detonation counter
(check for inward wind/secondary explosions). Roar four cardinal loops by source law (`client/sound/FireRoar`): fire
heat burning area × 175 kW/m² (burning wood face, `FirePlume.BLOCK_WATTS`; peak wood heat release 100–250 kW/m²,
Babrauskas, SFPE Handbook ch. 26), sound fraction 10⁻⁶ (low end of open turbulent flame noise 10⁻⁶–10⁻⁵, Strahle 1978,
(est.)), ISO 9613 path by distance rings, `Loudness` nuclear ceiling. Distant fires are `FireStorm` cells, same flame
as pall, trusted if server ignites/local fire not extinguished; local real fire blocks. Thus roar grows first minutes
with storm (80 s e-fold), fades with it by morning, not server fire count. Inward wind from fires within 1.5 km,
volume by old sink-based values (est.), no storm-inflow law yet.

**Destruction near listener** (`client/sound/Clatter`, `NukeSounds.Schedule.clatter`). With ruins, server sends chunk
viewers ≤ 24 removed positions/former blocks and total removed (`S2C.NukeDebris.removed`: hash sample, each weighted
“total/sample”). Each position: material mass (meter block, stone 2300 kg, wood 600, window glass 25; `Shielding.density`,
window by block sound, not ice) thrown by dynamic pressure (`Detonation.throwSpeed`), falls to first block beneath
in post-ruin world (window to same-floor sill, masonry rubble; client ruins one tick after debris packet, so compute
after 2 ticks); energy m(v²/2 + g·h), sound fraction 10⁻⁴ (solid impacts 10⁻⁴–10⁻³, (est.)) over fall time √(2h/g).
Glass/other chunk materials get events at power centroid, from front arrival there; ear sound-speed/ISO 9613 path.
Arrived power sums into ear quarters separately by kind, first immediate, next sum after 0.5 s. Empty field quiet
after hit; city glass/masonry everywhere, nearby just after front, distant after sound travel. Collapses near listener
at server-sent removed/debris positions (`S2C.NukeDebris`), increasingly rare. Secondary explosions scheduled from seed,
same clients/replay, in burning rings. All by detonation age: late entry/seek sounds current age, no repeated past
secondary explosions.

**Animal silence** (`client/nuclear/AnimalSilence`). Unprompted voices (`entity.*.ambient`, “Creatures” and bats) inside
consequence radius silent 4 minutes after flash, return by minute 8.

**Hearing** (`nuclear/model/HearingModel`, `client/nuclear/Deafness`). Ears at player's body, sound at camera
(`client/nuclear/FrontMeeting`): engine listener hears hit/echo/wind; threshold shift/eardrums/body lean at actual player
front arrival using own pressure/path/phase. Projectile camera orbits burst 32–42 blocks away, so camera ears would
inflict point-blank injury on distant player. Camera on player (first/third person) or near (burst-distance difference
within one tick's front travel) meets front with body that tick, sounds heard with pre-wave hearing. Front meets
camera/body only when crossing this tick, not after teleport behind it (return to already-passed body, orbit after
front departed); camera > 64 blocks ahead again (return/teleport) can meet again. Spectator body at camera. Hearing
injury from audible-band energy, not peak: far wave almost all infrasound, one audible front. A-weighted shock energy
peak − 38.7 dB (LAE, phase-independent). Threshold daily noise dose, 85 dBA for 8 h (LAE 129.6 dB, ISO 1999/NIOSH),
front ~0.75 psi; aircraft booms to ~1 psi did not damage hearing (DTIC AD0680800). CHABA 140 dB peak applies gunshots,
all energy audible, not long waves. High-frequency (4 kHz) shift at 2 min: zero at threshold, 50 dB at 5 psi
(acoustic trauma at eardrum rupture threshold), linear energy decibels between, lows one third. Hills/shadow subtract
same energy as sound pressure (`Outdoor.pressureLoss`, highs `Outdoor.pressureHighs`: edge/shadow, no ground or ground
beyond edge, reflection already in wave pressure). Waves within 2 game minutes (~1.7 s) one dose, energies sum (two
equal +3 dB); later doses own recovery lines, maximum shift wins (`HearingModel.Ear`). Recovery linear in log time
from 2 min, ≤ 40 dB over 16 h (Ward 1960), above doubles per extra 10 dB; game hours 1000 ticks, sleep/`/time add`
advance. Eardrum rupture lognormal (1 % at 5 psi, 50 % at 15, Glasstone & Dolan table 12.38), conductive loss 25 dB
lows/15 highs, heals 4–8 game weeks. In-game muffling less than whole threshold shift: cochlear recruitment catches
normal loudness by 100 dB, game sound ~70 dB quieter by 30·S/(100 − S) dB; conductive loss attenuates by its full value.
OpenAL EFX low-pass each new source via `PlaySoundSourceEvent`, own sources, every tick other loops (water/beacon/
minecart, `SoundFilters.loops`: pre-wave loops otherwise clear through deafness hours): `GAIN` low loss, `GAINHF`
high/low difference. Wave's own hit/pops/glass/shock use pre-wave ear (`Deafness.before`). While threshold shifted,
`nuke.tinnitus` non-positional head loop, volume by shift. Player category sliders untouched.

## 6. Client effects (`client/nuclear`)

All from one `ClientNuclearEvents` list (event + time), updated in `ClientTickEvent`, rendered in
`RenderLevelStageEvent`/`RegisterGuiLayersEvent`; removed when cloud disperses.

- **Flash — `NukeFlash`** GUI layer: overexposed frame catches up like camera auto-exposure (presentation,
  `NukeExposure.overexposure`: K^(e^(−u/0.2 s)) after second maximum, K camera sphere light at second maximum relative
  to frame exposure; frame gain/lens-scattered light, 15 kt/1.4 km white to ~0.25 s, normal by 1 s); strength
  `min(1, Q/2)` if sphere visible/in FOV, otherwise 0.3 of it. Fireball exposed for fire/visible surface, film curve
  like conventional (`NukeExposure`). Blindness: after white, slow-fading dark-orange retinal spot at sphere location
  (gaze-following afterimage), 5–30 s. Field glare/retinal burns §4 “Eyes”. Existing client `flash` (0 disables).
- **Sky/fog** (`ViewportEvent.ComputeFogColor`, `RenderFog`): first seconds white/yellow, then orange-brown; black-rain
  zone gray-brown/nearer.
- **Fireball — `FireballRenderer`:** icosphere (3 subdivisions), `RenderType.entityTranslucentEmissive`, animated plasma
  texture/scrolled UVs, `FireballModel` color/radius, larger/translucent second corona layer. Stem billow cone below.
- **Mushroom — `MushroomCloudRenderer`:** 300–1200 billows (`cloud_quality`), camera-facing quads, billow textures
  (4–8 atlas variants), `CloudModel` positions (cap torus/stem/skirt), glowing-to-gray-brown color; sphere light below
  while glowing, sky above. Back-to-front sort each frame, one `BufferBuilder` batch, no depth writes.
- **Distance.** 1.21.1 far clip `renderDistance·4` blocks (12 chunks → 768), 15 kt mushroom 10 km tall. Beyond `0.8·far`,
  render compressed in `AFTER_SKY`: move point along same ray to `0.8·far`, shrink proportionally to preserve angular
  size; no depth test, terrain rendered after sky occludes. Near `AFTER_WEATHER` depth-tested. Own actual-distance
  haze replaces vanilla fog.
- **Dust skirt:** separate set of same billows. **Front dust wall/Wilson cloud:** pall volumes
  (`client/nuclear/BlastWall`), rolling head behind front/condensation shell around sphere (1–3 s).
- **Water** (`NukeWater`, `fx/layer/WaterDecal`, column `MushroomField`, model `nuclear/model/WaterModel`). Dark front ring:
  post-front wind ripples water (Beaufort, from 1.5 m/s, full at 18), darkest at front (positive-phase band ~25 ticks),
  smooths over minutes; epicenter wind ≥ 25 m/s gives whitecaps. Effects-layer decal before quads, full-frame world-
  distance pass (`SceneDepth`, DH LODs), water horizontal at standing-water top (8/9 block, shaderpack lower at its
  waves) within render range (walls/trunks/slopes/sky-edge leaves at same fractional height not water), farther sea
  plane (position at/below it, ray intersection); modeled ring profile 64 samples/frame. Spray wall: head/skirt over
  water white spray, lower than dust wall (crest stripping from 14 m/s, height 6–45 m), behind dark ring by head path
  over 0.5 s (`WaterModel.SPRAY_LAG`, drops take time to reach height; near fireball no farther than edge); camera spray/
  drop particles (`FxBudget.SPRAY`), camera dust land only. Water burst (on water/below half sphere radius,
  `WaterModel.wetBurst`): hollow column/crown in mushroom volume (`WaterModel.column`, `MushroomField`, `mushroom.glsl`,
  pall OFF uses LOW like mushroom), 15 kt outer radius ~260 m, wall ~80 m, ~940 m tall in seconds (water hundreds m/s;
  Glasstone/Dolan shallow underwater 100 kt > 1.5 km at 2 s, surface 100 kt `SURFACE_COLUMN` share ~0.9 km); from ~6 s
  narrows to 0.6 radius (outer spray curtain falls first, 100 kt diameter 1000→600 m by 20 s), from ~9 s settles to
  water over ~22 s, water amount falls. Appears as sphere cools (15 kt 0.5–2.5 s, like stem dust, glowing sphere's water
  is steam); crown ring atop wall where jets stop, opens 1.4 times column width, falls with outer curtain (settling
  wall top rounded edge); wall jets rise/fall with water. Mushroom cap rises through hollow column, hides top by
  ~10 s (Glasstone/Dolan “cauliflower” is gas/steam through column). Condensation cloud post-front Wilson like humid
  land, no extra fog ring. Base surge in pall, absent OFF, 0.7 Baker (deep-water surface burst lifts less than
  underwater): from ~7 s, initial ~20 m/s, ~2 km/~330 m tall by 4 min; stem/cap lighter with steam. Epicenter water
  client chunk/map tile, else sea 63 (surface block `ceil(groundY) − 1`, water aim `groundY` surface 62.889); settled
  in first 1.5 client seconds (historical world-entry/seek 5 s, forward replay delivers live burst 1.5 s), fixed after.
  Over-water post-front pall (step 6) spray (60 m visibility, settled by 3 min)/base-surge fog (50 m densest), white,
  forward-transmitting, no dust; source-water material (shore dust drifted to sea stays dust), no over-water haze/fires.
- **Warhead entry:** glowing streak (camera-quad ribbon/star head), known trajectory.
- **ICBM trail:** billow ribbon along boost trajectory, wind-drifted/slowly twisting.
- **Black rain — `BlackRainRenderer`:** local rain band/time only (`Detonation.blackRain`), dark heavy custom drops,
  player-centered 24-block radius, ~200/tick high quality, `nuke.rain`. Vanilla weather unchanged, black rain local.
- **Shake:** existing mod camera, strength by `P`, wind slow drift by `q`.
- **EMP/wisps** (`FlashAftermath`, live only): grid lamps (`GridLights`) in server-darkened districts (detonation
  packet radius `Blackouts.nukeRadius`, no arcs without grid/with `grid.nuke` off), staggered arc/spark shorts,
  more immediately, before lamp front arrival/no later than 2.5 s (≤ 48, nearest camera first; palette search,
  48 sections/tick; queue lamp as soon as section scanned, skip if already too late for wave, discharge only if lamp
  still there); crackling 2–4 discharges 1–3 ticks apart, 40 % repeat later. Spark-star zigzag sideways/down from lamp
  over glow: single spot invisible on daytime flash-lit ground, post-aligned zigzag hidden by lamp/post. Server knocks
  out substations (`Blackouts.nuke`), arc/smoke from grid failure (`GridEffects.failure`); lit grass/leaves/flammable
  faces in frame within 128 blocks emit wisps until wave (light like scorch, median 5 cal/cm², `SCORCH` particle group).
- **HUD:** launcher's detonation countdown; victims “MISSILE ATTACK” countdown; dose > 0 plus held meter shows dose rate.

## 7. Carrier: ICBM

- `WeaponType.NUKE` (`nuke`, “nuclear”, aliases `icbm`, `ядерка`), siren `SirenKind.NUCLEAR`.
- Launch via `StrikeService` like others: origin `here` default (30 blocks behind launcher player, dramatic) or
  `remote` (boost invisible).
- `IcbmEntity extends StrikeProjectile` boost only: vertical start, 0.5 → 25 blocks/tick, turn toward target. Above
  ceiling + 500 vanishes; server stores **scheduled strike** in `NuclearEvents` (`ScheduledStrike`: target, yield,
  burst, detonation time). Remaining “flight” is SavedData timer, survives restart, holds no chunks.
- Launch-to-detonation `nuclear.flight_time` 90 s (real 30 min). Three seconds before detonation client warhead entry
  along same trajectory, no per-tick server packets.
- ICBM cannot be intercepted (nothing in Minecraft can), `/incoming clear` cancels scheduled strikes.
- Other carriers: `Warhead` = `Conventional(power)` | `Nuclear(yield, burst)`; all `impact()` calls
  `warhead.detonate(level, point)`. Remote missile/B-2 “Warhead” switch. B-2 airburst by barometric fuze, missile surface.
- Burst height: `burst = air` optimal for 5 psi, `HOB = 0.24 km · Y^(1/3)` (15 kt ~590 m, 1 Mt 2.4 km, Hiroshima 600 m);
  `ground` contact. Default `air`: less fallout, more destruction area, as used in reality.

## 8. Interface, commands, settings

- Remote “Nuclear” weapon, Shift+right-click screen: yield presets 1 kt “tactical”, 15 kt “Hiroshima”, 100 kt “W76”,
  300 kt “W87”, 1 Mt, 10 Mt if `max_yield` allows; air/surface burst, launch origin. Two-step launch: left-click arm,
  screen 3 s “LAUNCH” countdown, another left-click cancels. No accidental nuclear strike from misclick.
- Operator `/incoming …` commands:
  - `nuke <yield> [air|ground]` by gaze, `nuke at <x y z> <yield> [air|ground]`,
    `nuke now <yield> [air|ground]` no-flight detonation for debugging/GameTest.
  - `radiation <player>` dose/rate, `radiation clear <player>`.
  - `clear` also cancels scheduled strikes, stops queues, removes fallout fields; destroyed blocks not restored.
- New items: **Geiger counter**, held clicks/screen rate µSv/h … Sv/h; `trinitite` block. No hazmat suit (not requested,
  separate task if desired).
- SERVER `nuclear` settings:

| Key | Default | Meaning |
|---|---|---|
| `enabled` | true | nuclear weapons available |
| `ops_only` | true | operators only (Artem host/operator, disable for friends) |
| `default_yield` / `max_yield` | 15 / 1000 kt | yield |
| `effects_scale` | 1.0 | all-radius scale 0.1–1.0 for small worlds |
| `block_damage`, `fires`, `tree_fall`, `crater` | true | destruction components |
| `fallout`, `radiation`, `black_rain` | true | radioactivity |
| `flight_time` | 1800 ticks | launch to detonation |
| `time_budget_ms` | 4 | chunk-work budget/tick, 1–20 |
| `max_fires` / `max_debris` | 4000 / 300 | per event |
| `warning_radius` | 20000 | alert recipients |

  Client: `nuke_dust_quality` (off/low/medium/high: volumetric mushroom, dust wall/pall), `nuke_tinnitus` on, existing
  `flash`/`camera_shake`.

## 9. Network (all packets in `net/`, `StreamCodec`)

| Packet | Recipient | When |
|---|---|---|
| `NukeWarningPayload(strikeId, target, launchPos, launchTime, detonateTime)` | all in dimension | launch (siren within `warning_radius`, client boost/reentry trajectory for visuals) |
| `NukeDetonationPayload(id, pos, yield, hob, burst, gameTime, wind, seed)` | all in dimension | detonation |
| `NukeEventsSyncPayload(list)` | joining player | entry, dimension change |
| `RadiationPayload(dose, doseRate, contamination)` | owner | on change |
| `S2C.NukeFlashMarks(chunk, marks)` | chunk viewers | flash traces changed; chunk sent to player |
| `S2C.RetinaScars(scars)` | owner | retinal burn; entry, dimension change, respawn |

No per-tick per-event packets. Blocks use standard section-change packets.

## 10. Architecture (mod packages)

```
nuclear/
  model/     BlastModel, HobBlast, BlastInjury, ArrivalTable, FireballModel, CloudModel, ThermalModel,
             PromptRadiationModel, FalloutModel, CraterModel, Yield (+ presets)       ← shared code, unit tests
  Warhead, NuclearWarhead                                                            ← detonation: event, packets, immediate effects
  NuclearEvents (SavedData: Detonation, ScheduledStrike), NuclearTicker (LevelTickEvent)
  world/     ScarQueue, ChunkScar (attachment), ColumnScar, BlockResponse, TreeFall,
             ThermalShadow, CraterJob, BlastFront (ring front), BlastBody (body blast)
  entity/    IcbmEntity
  radiation/ RadiationDose (attachment), RadiationTicker, RadiationSicknessEffect,
             BurnsEffect, Contamination, GeigerCounterItem
client/nuclear/
  ClientNuclearEvents, FlashOverlay, SkyTint, FireballRenderer, MushroomCloudRenderer,
  CloudPuffs (shared billows), FarCompressor, ReentryRenderer, IcbmTrailRenderer,
  BlackRainRenderer, NuclearSoundScheduler, Deafness (EFX), GeigerClicker
data/incoming/: tags/block/nuke_fragile|nuke_plants|nuke_light|nuke_masonry, tags/worldgen/biome/nuke_keeps_biome,
                 worldgen/biome/singed|scorched|charred, damage_type/nuclear_blast|nuclear_thermal|radiation,
                 lang (ru_ru, en_us)
assets/incoming/: textures/nuke/(puffs, rain), sounds (section 5), ICBM model (LayerDefinition)
```

Same dependencies as mod, nothing new.

## 11. Performance (targets and checks)

- Detonation < 5 ms in detonation tick (save event, packet, in-range entity light check).
- Queue ≤ `time_budget_ms`/tick. Reference 15 kt, player at epicenter, 12-chunk render distance: ~625 loaded chunks,
  almost all ≥ 1 psi; ~20 µs/column gives ~3.2 s work, ~800 ticks × 4 ms. Front travels 3 km then, so process chunks
  near front first, keep up with visible player front; far glass zone usually instant palette skip.
- 15 kt crater ~100 thousand blocks, ~20 thousand changes/tick, 5 ticks.
- Client high mushroom 1200 quads + sphere ~1300 triangles, less than one rendered chunk.
- Spark profile after implementation: 15 kt/1 Mt with two players, MSPT not above 50 for more than 1 s.

## 12. Checks (GameTest and unit tests)

Unit tests (`model`):
- pressure table 1.1 for 1/15/1000 kt, 10% tolerance; 15 kt arrival at 1/5 km;
- light 15 kt, 8 cal/cm² at 1.9–2.1 km (Glasstone/NUKEMAP 1.9 km), hill shadow with synthetic heightmap;
- prompt radiation Nagasaki kerma at 1–2.5 km (RERF, 25 % tolerance); mass-path shielding, G&D table 8.41 half-value
  layer 320 kg/m² first minute, 130 fallout;
- fallout ×0.1 decay in 7 h, zero before arrival, nowhere before coarse particles (`4·Y^0.2` min); shorter upwind contour;
- radiation sickness stages by dose/time;
- burns yield thresholds fig. 12.64, spread §12.65, cloth ignition table 7.35, shock §12.62;
- blast injuries lungs table 12.38, impact/tumble 12.49, shards 12.43/12.46, dummy throw at 5.3 psi (§12.44: 21 ft/s,
  22 ft) for 20–40 kt.

GameTest (`nuke`, `effects_scale` 0.01, tiny platform radii):
- glass breaks, stone stands, tree log falls away from epicenter, hill-shadowed leaves intact;
- surface crater matches profile ±1 block, airburst none;
- budget never exceeds `time_budget_ms`+1 in any tick;
- `ChunkScar` with `appliedUpTo` older than event queues on load, processes once;
- event/scheduled strike survive world save/load;
- player under 3 stone blocks in basement gets < 1% outside dose;
- `blastHitsBodiesByPhysics`: open-field outward throw, wall 2 blocks away fatal/barely alive, sealed shelter untouched,
  house with door away from burst no throw, gravel-buried dead/injured;
- `clear` cancels scheduled strike.
- `FlashGameTests`: scorch only sphere-facing, not under neighbor/shadow; marks survive chunk save/packet; replacement
  removes mark, same-type block replacing broken one never regains; body's wall shadow; prebuilt-ruin scorch/shadows
  one layer with detonation ID.

Client headless like rest of mod: screenshots 0.05 s, 2 s, 30 s, 5 min after 15 kt from 2 km; flash/sphere/front/mushroom
visible, FPS logged.

## 13. Work order

1. `nuclear/model` and unit tests, foundation of everything else.
2. `NuclearEvents`, `Warhead`, `nuke now` no-flight detonation.
3. Queue, `ChunkScar`, `BlockResponse`, shadows, trees, fire, crater; world GameTest.
4. Damage, push, burns; radiation, sickness, contamination, Geiger counter.
5. Client flash, sky, sphere, mushroom with far compression, front; sound/deafness.
6. ICBM boost, scheduled strike, reentry, alert, remote.
7. Black rain, cruise-missile/B-2 nuclear payload.
8. Profiling, README, `help`, CLAUDE.md.

## 14. Deliberately omitted
- EMP: surface/low-airburst zone smaller than destruction, no separate point; high-altitude EMP over hundreds of km
  has no representation in Minecraft world.
- ICBM interception, anti-missiles, hazmat suit not requested, architecture permits.
- Global weather change: black rain local.

## Sources
- S. Glasstone, P. Dolan, “The Effects of Nuclear Weapons”, 3rd ed., 1977 (ch. 2 sphere/cloud, 3 blast, 6 craters,
  7 thermal, 8 prompt radiation, 9 fallout, 12 radiation sickness).
- H. L. Brode, “Airblast from Nuclear Bursts — Analytic Approximations”, PSR Report 1419-3, 1987, ground pressure by
  burst height, arrival/positive phase.
- C. E. Needham, J. E. Crepeau, “The DNA Nuclear Blast Standard (1 KT)”, DNA 5648T, 1981 (glasstone package formulas,
  E. Geist, MIT), far zone/free air.
- G. Kinney, K. Graham, “Explosive Shocks in Air”, 1985, conventional-charge pressure.
- A. Wellerstein, NUKEMAP, radius checks (1 kt, 15 kt, 1 Mt).
- IAEA/WHO, acute radiation sickness stages by dose.

Table 1.1 is `HobBlast`, numbers checked by `HobBlastTest`.
