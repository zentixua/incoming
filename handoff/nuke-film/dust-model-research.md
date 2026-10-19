# Blast-lofted dust: literature for one dust model (15 kt, HOB ≈ 590 m, viewer 1.4 km)

Research notes, 2026-10-18. Goal: replace "dust wall/head" + "pall" with one physical dust layer.
Tags: **[M]** measured/observed (tests, eyewitnesses); **[Md]** model or calculation by the cited authors;
**[I]** my own inference or arithmetic (method stated). Sources (with links) at the end; § = section, p. = page.

## 0. Reference case numbers
- Scaled HOB: 590 m / 15^(1/3) = 239 m/kt^(1/3) = 785 ft/kt^(1/3) [I]. Hiroshima DS02: 16 kt at 600 m (as quoted in
  Sutou 2018, Fig. 2 caption) → 238 m/kt^(1/3). (ENW 1977 still gives 12.5 kt / 1670 ft, pp. 36–37 — superseded.)
- The mod's own blast model (`BlastModel`, Kinney–Graham, 0.5 blast fraction, ×1.8 ground) gives at ground range
  1.0 / 1.4 / 2.0 / 3.0 km: 4.4 / 2.8 / 1.7 / 1.1 psi, wind behind the front 66 / 43 / 28 / 17 m/s; positive phase
  0.4·Y^(1/3) ≈ 1.0 s; thermal (`ThermalModel`) 16.8 cal/cm² normal at 1.4 km, 6.5 cal/cm² on level ground [I, mod numbers].
- Comparison scale used below: Upshot-Knothole GRABLE, 15 kt at 160 m (SHOB 212 ft/kt^(1/3)), Nevada desert;
  Teapot MET (Shot 12), 22 kt, 400 ft tower (SHOB 140 ft/kt^(1/3)), Frenchman Flat. Both have much lower SHOB than Hiroshima.

## 1. Mechanisms that loft surface dust
1. **Shock-induced turbulent dusty boundary layer ("sweep-up", scouring).** The shock passing over loose material deposits
   vorticity (baroclinic, ∇p×∇ρ); the wall layer rolls up into large vortices that entrain dust (Kuhl et al. 1992, §3.1, §5) [Md].
   Entrainment is strongest right behind the front: Ṁ = (0.035 − 0.1ξ)·ρ₂u₂, ξ = 1 − r/R_s (Kuhl Eq. 28) [Md].
   Shock tube on a loose bed: scouring rate 2–3 % of free-stream mass flux, layer grows ≈ linearly behind the shock
   (Batt et al., summarised in Kuhl §1, p. 2) [M]. Laminar/turbulent clean-wall theory: Mirels 1956/1984 (via Kuhl refs 1–5).
2. **Saltation/sandblasting** at lower winds: grains hop and kick out fines. Onset at friction velocity u*th
   (Gillette 1988, in Gaj & Small 1991 Table 1, dry soils): loose sand 0.28, sandy loam 0.29, loamy sand 0.34, clay 0.54,
   loam 0.78, silt loam 1.08 m/s; cloddy/crusted 0.75 to >2 m/s; desert pebble pavement 1.36 m/s (their Eq. 11);
   dense crops/grass assumed ≥ 3 m/s (§2.2) [M; vegetation value assumed by authors]. "Just a nominal amount of moisture
   effectively eliminates dust lofting"; real surface textures always reduce lofting (Batt & Peabody 1995, abstract) [M, wind tunnel].
3. **Thermal precursor / pre-shock thermal layer.** Over heat-absorbing surfaces the thermal pulse makes a hot dusty layer;
   a precursor wave can run ahead in it; "Dust, which may billow to heights of more than 100 feet, shows the upward flow"
   (ENW §3.81) [M]. Not expected for SHOB > 800 ft/kt^(1/3) or where peak overpressure < ~6 psi, regardless of yield;
   commercial/industrial areas are "thermally nearly ideal" (ENW §3.83, Table 3.83) [M/Md]. Precursor raises sweep-up only
   for SHOB < 650 ft/kt^(1/3) (Gaj & Small §4.2) [Md].
   → Hiroshima-like case: SHOB 785 ft/kt^(1/3), < 6 psi beyond ~0.7 km (mod), urban surface: **no precursor at 1–2 km** [I].
4. **Thermal "popcorning"/steam blow-off** of soil grains by the flash (SCOPE 28 §3.8.1 item 1; Gaj & Small §2.4, citing
   Versteegen et al. 1989) [M qualitative]; threshold fluence not found. Can also glaze sand (Trinity) and stabilise it.
5. **Pore-air ("reverse percolation") ejection** when overpressure decays below the pore pressure: up to ~10 cm of soil,
   slow, low speed (Rosenblatt 1981 p. 22, citing Zernow et al. 1973) [M lab/HE].
6. **Afterwinds into the stem** (ENW §2.09–2.11; NRC 1985 p. 18) [M]. For 10 kt bombs at 450 m under balloons
   (SHOB ≈ 690 ft/kt^(1/3), close to Hiroshima) "dusty stems that did not merge with the ring vortex" (NRC p. 22) [M].
   Only "sweep-up" and thermal dust feed stem dust (NRC p. 22). Fireball downflow near the axis suppresses sweep-up
   rise there for the first seconds (Rosenblatt Fig. 13e, GRABLE DICE calc.) [Md].
7. **Buoyant rise of the heated dusty layer**, then fires: Hiroshima fires broke out "after about 5 minutes"
   (Kita, 3.7 km) [M]; mass fire with inflow wind from ~20 min, peak 30–40 mph at 2–3 h (ENW §7.71) [M]. No measured
   rise rate of the low dust layer was found [gap].
8. **Urban debris dust** (collapsed wooden houses with clay walls and clay under roof tiles, plaster): eyewitnesses at
   0.8–1 km: "Sandy dust was flying around" (Kawamoto, 0.8 km), "mud and sand was sucked into my mouth" (Sakai) [M qualitative].
   No quantitative source found.

**Which dominates at 1–2 km for 15 kt / 590 m [I]:** the ~1 s gust (43 m/s at 1.4 km) is above saltation onset for every
dry soil but only marginal for suspension of natural soil (§7). The darkness reported at 1.2–1.5 km in Hiroshima (§4)
therefore most likely came from pulverised building material plus loose soil, lifted by the gust and the collapses, then
kept aloft and thickened by buoyancy (flash-heated ground) and, after ~5–20 min, by fire. Precursor: absent.
Stem afterwinds: matter only within ~1 km, and the stem stays separate from the cap at this SHOB.

## 2. Heights of blast-raised dust vs range and time
| Source | Case | Range / time | Height | Tag |
|---|---|---|---|---|
| ENW §3.81 | NTS low air bursts, precursor | precursor region | "more than 100 feet" (>30 m) | M |
| Rosenblatt 1981 Fig. 8 (tracings of photos) | GRABLE 15 kt / 160 m | 300–550 m, 0.31–0.66 s | dust at the front ≈ 6–25 m (read off, ±50 %) | M |
| Rosenblatt Fig. 13e (DICE calc.) | GRABLE | 0–300 m, 1.4 s | dense layer ≈ 20–60 m, rising outward | Md |
| Rosenblatt p. 18, Fig. 7 photo | NTS shots, SHOB 150–500 ft/kt^(1/3) | whole layer, ~4–5 s | "a few hundred feet are typical" (≈ 60–150 m) | M |
| Rosenblatt p. 18 | same | max radius | 1000–1300 ft/kt^(1/3) ≈ 5–10 psi (→ 750–980 m for 15 kt [I]) | M |
| Rosenblatt p. 18 | 1 Mt | max height | "~1000 ft heights ... seem plausible" (≈ Y^(1/3) from 20 kt [I]) | Md |
| Teapot WT-1153 §2.4.2 | MET 22 kt, water line | behind Mach stem | column to 200 ft (61 m) | M |
| Teapot WT-1153 §2.4.2, Fig. 2.37 | MET, desert line | 2,500 ft (760 m) | dust front lags shock ~190 ft, inclined ~20° to horizontal | M |
| Teapot WT-1153 §2.4.2 | MET, desert line | — | dust lagged from 1,900 ft, "ceased to propagate horizontally" at 3,100 ft (945 m) | M |
| DICE THROW Symp. vol. 3 (aircraft paper) | 628 t ANFO surface | 375 m, ~62 kPa (9 psi) | film of the target aircraft obscured by "the cloud of dust raised by the blast" 0.55–0.8 s after shock arrival | M |
| MISERS BLUFF II (ADA087856 §2-3) | 120 t ANFO surface | stabilised cloud | visible top ≈ 2300 m | M |
| NRC 1985 p. 22 | 10 kt at 450 m | stem | dusty stem did not reach the cap | M |
| Caron 1985 interview | Hiroshima, ~1–3 min, from the aircraft | whole city | "low, bubbling mass ... like bubbling molasses ... running up into the foothills, just covering the whole city"; fires "through this under cast" | M (no height) |
No measured height of the Hiroshima low dust layer was found. Nuclear-winter studies (NRC 1985 ch. 4; SCOPE 28 §3.8)
treat only dust carried to the stabilised cloud, not the low layer.

## 3. Mass loading, optics, particle sizes
- MET, ~20 psi, particle velocity ~600 ft/s, 3 ft above ground: dust ≈ 2×10⁻³ g/cm³ = **2 kg/m³**, uncertainty ×3
  (Rosenblatt p. 20) [M]. MET Project 1.13, desert line, 2,000 ft, 40–340 ms after the shock: ρ_dust/ρ_total = 0.12–0.77
  at 3 ft, ≈0.42 at 10 ft; asphalt ≈0.30 ≈ 0.25 mg/cm³; concentration rose during the sampling; "considerably smaller"
  at greater ranges (WT-1153 §2.4.2) [M]. At 1.4 km for this case: no data.
- Profile estimate in the sweep-up layer (Rosenblatt Fig. 9, p. 20) [Md, read off]: ~1 g/cm³ at 1 cm, ~0.05 g/cm³ at 1 m,
  ρ_dust ≈ ρ_air at ~3–10 m, ~10⁻⁵ g/cm³ (10 g/m³) at 100 m → roughly ρ_dust ∝ z^(−1.5) above 1 m [I fit].
- Sizes, MET desert air sample: NMD 1.21 µm, MMD 15 µm, σ_g 2.87, "correspond closely to ... near-surface soil in
  Frenchman Flat" (WT-1153, Project 1.13 summary) [M]. Lognormal mass fractions [I]: <1 µm 0.5 %, <10 µm 35 %,
  10–100 µm 61 %, >100 µm 3.6 % (an isokinetic sampler 3 ft up under-samples coarse grains).
- MISERS BLUFF II dust cloud (aircraft, minutes): MMD 25–30 µm, ≈85 % of mass < 47 µm; 15 % > 47 µm at 20 min
  (ADA087856 §2-3.2, §2-4) [M]. Natural dust storms: MMD ≈ 50 µm (Hagen & Woodruff 1973, citing Whitby 1957) [M].
- Fine fractions (surface-burst clouds): submicron "few percent to 20 percent", plausible 8 % (NRC p. 17); basalt crater
  ejecta 1 % < 10 µm, 0.1 % < 1 µm; tuff (10 t HE) 6 % < 10 µm, 3 % < 1 µm (NRC pp. 24–25) [M].
- Optics: Johnie Boy submicron dust 3 m²/g at 550 nm, absorption 1–3 % of extinction (SCOPE 28 §3.8.2 p. 82, from NRC) [M].
  Geometric optics, k = 3Q/(2ρ_p D), Q ≈ 2, ρ_p 2.65 g/cm³ (per-bin: 5 µm 0.23, 20 µm 0.057, 50 µm 0.023, 150 µm 0.0075,
  500 µm 0.0023 m²/g); MET size distribution → **k ≈ 0.13 m²/g** [I].
- Visibility V = 3.9/σ (Hagen & Skidmore 1977, Eq. 3). Measured: "100 to 400 mg/m³ are needed to reduce daytime
  visibility to 200 m" (Hagen & Skidmore 1977, abstract) [M] → **40 m visibility ≈ 0.5–2 g/m³** [I, σ ∝ C];
  with k = 0.13 m²/g: 0.73 g/m³ [I]. MET-type near-ground loadings (0.3–7 kg/m³) mean visibility of centimetres [I].
- Sun hidden: overhead τ = k·M_col; τ = 3 needs only ~22 g/m² of MET-type dust, τ ≈ 13 (deep twilight) ~100 g/m² [I].

## 4. Settling, and how long Hiroshima stayed dark
Terminal velocity, quartz 2.65 g/cm³ in air (Stokes + Cunningham slip, Clift–Gauvin drag above Re ≈ 0.1) [I calc]:
| D (µm) | 1 | 5 | 10 | 20 | 30 | 50 | 100 | 200 | 500 | 1000 |
|---|---|---|---|---|---|---|---|---|---|---|
| v_s (m/s) | 8.0e-5 | 2.0e-3 | 7.9e-3 | 0.031 | 0.069 | 0.18 | 0.58 | 1.5 | 3.8 | 7.0 |
| fall 100 m | 14 d | 14 h | 3.5 h | 53 min | 24 min | 9 min | 2.9 min | 66 s | 26 s | 14 s |
Pure Stokes is within 10 % up to ~50 µm, ~40 % too fast at 100 µm, ×2 at 200 µm.
**Consequence [I]:** for the MET size distribution in a 100 m well-mixed layer, settling removes only 2 % of the
extinction in 150 s, 6 % in 10 min, 19 % in 1 h. A 150 s e-fold fits only the > 100 µm fraction (sand, grit). The fines
leave by dilution, advection (afterwinds), rain scavenging, not by settling.

Hiroshima observations [M]:
- British Mission 1946 ¶9: eyewitnesses "all ... spoke of the settling darkness as they found themselves enveloped by a
  universal cloud of dust. Shortly afterwards they became aware of fires".
- Manhattan District 1946 (Hiroshima section): "a great cloud of dust and smoke began to cast a pall of darkness over the city."
- Hersey 1946: Mrs Nakamura, 1,350 yd (1.23 km), her child: "Why is it night already?"; Fr Kleinsorge, 1,400 yd (1.28 km):
  "the day had turned dark"; **Dr Fujii, 1,550 yd (1.42 km): "about twenty minutes in the darkened morning"**;
  Rev. Tanimoto, 3,500 yd (3.2 km): "Such clouds of dust had risen that there was a sort of twilight around ... the day
  grew darker and darker"; later from a mound: "as much of Hiroshima as he could see through the clouded air ... Clumps of
  smoke ... had begun to push up through the general dust", then marble-size drops of rain (ch. 1–2).
- Kita (Weather Bureau, 3.7 km): "The city turned into a yellow sand ... the color of the yellow desert ... After about
  5 minutes, fire broke out here and there." Sakai: "pitch black ... Gradually it became lighter. ... the sun ray broke
  through the clouds. The light appeared to be in many different colors, red and yellow, purple and also white."
  Takakura (300 m): "found myself in the dark ... the sky started to grow lighter" (within minutes).
- Rain with the fire wind (ENW §7.71); black rain "started to pour down 20–30 min after the detonation" (Sutou 2018,
  review text, uncited) [M, secondary].
Summary [I]: at 1.2–1.5 km near-night darkness from seconds to ~20 min, light returning gradually over ~10–30 min,
then smoke takes over. Colour from outside: yellow/ochre ("yellow sand"); sun seen through it reddish/yellow.

## 5. Turbulent mixing: growth of the dusty layer
- Dusty BL behind an ANFO surface blast (hydrocode, 20–80 psi, loose bed): **δ/R_s = 0.0256 ξ^(5/6)**, ξ = 1 − r/R_s
  (Kuhl et al. 1992 Eq. 17) [Md]. Other cases (Kuhl Table 1, p. 11): wedge reflections δ/R_s = a ξ^(3/5), a = 0.015–0.037;
  precursor flow 0.0325 ξ^(5/6); shock tube over loose soil (M 1.7, Ausherman): δ_t = 0.0325 Δx^(5/6) (cm); clean wall
  δ_t = 0.00983 Δx^0.93; clean flat plate δ/x = 0.37 Re_x^(−1/5) [M/Md]. Growth is by entrainment from the bed, not wall drag.
- Inside the layer (Kuhl §3.4–3.5) [Md]: velocity ≈ 0 near the bed; vertical velocity 0.02 u∞ at the bottom, 0.04–0.12 u∞ at
  the top; u'/u∞ ≈ 0.25, w'/u∞ ≈ 0.2; density fluctuations up to 7× free stream — lumpy, striated, not smooth.
- Applied at 1.4 km [I]: front length of the positive phase Δx ≈ U_s·t+ ≈ 370 m → δ ≈ 12 m (Kuhl) or ≈ 2 m (shock-tube law).
  MET/GRABLE dust fronts are steeper (~20°, 6–25 m within ~60 m) because of precursors.
- After the positive phase there is no blast forcing. Buoyancy: the flash-heated ground warms the air; standard
  convective-BL growth into stable air (Deardorff/Tennekes encroachment, A ≈ 0.2; from memory, not re-checked here):
  h = √(2(1+2A)·f_h·E/(ρc_pγ)), E = absorbed flash energy (mod: 0.62 / 0.27 / 0.10 / 0.03 MJ/m² at 1 / 1.4 / 2 / 3 km),
  f_h = fraction into air (unknown, 0.1–0.3), γ = 3.5–9.8 K/km → **h ≈ 120–350 m at 1 km, 80–230 m at 1.4 km,
  50–140 m at 2 km, 27–80 m at 3 km** [I]. Rate ~ w* = (g/T·H/(ρc_p)·h)^(1/3) with H = f_h·E/60 s: ≈ 1.5 m/s at 1.4 km,
  ≈ 4 m/s near GZ [I]. Fires after 5–20 min add far more heat (§1.7).

## 6. One continuous cloud?
- MET: "in the early stages of formation the dust front is coincident with the precursor front near the ground. However,
  as the precursor front progresses to larger ground ranges the dust lags farther and farther behind, assuming an angle
  near 20 degrees to the horizontal"; on the desert line it stopped at 3,100 ft while the shock went on (WT-1153 §2.4.2) [M].
- GRABLE: the layer "begins forming very quickly behind the shock wave"; "the sweep-up layer is fairly persistent, and
  some ... are subsequently drawn up into the main cloud" (Rosenblatt pp. 17–18, Figs. 7–8) [M].
- Simulation: one turbulent layer of rolled-up vortices, densest at the bed, entrainment strongest at the front and
  falling to zero at ξ ≈ 0.35, thickening backwards as ξ^(5/6) (Kuhl §3, Eq. 28) [Md].
- Hiroshima: "universal cloud of dust" (British Mission ¶9); one low layer over the whole city (Caron) [M].
- [I] So the "head" and the "pall" are one layer: the head is its youngest, densest, lowest part right behind the front;
  behind it the layer is thicker and more diluted. Where the wind drops below the suspension threshold the head detaches
  from the (invisible) shock and stalls (MET), leaving a standing layer that then rises by buoyancy and drifts with the
  afterwind. The shock itself is visible only through the dust it raises.

## 7. Check of the current model [I unless marked]
- **Wall height** 300 / 155 / 116 / 60 m at 0 / 1 / 1.4 / 3 km: in the first seconds the data give tens of metres at the
  front (GRABLE 6–25 m, MET ~20° slope), "a few hundred feet" (60–150 m) for the whole layer after seconds, and only
  inside 5–10 psi (≤ ~1 km here) for NTS soil. The current heights fit the *late* buoyant top (§5), not the head.
- **Pall rise 4 m/s** (base surge): wrong analogue (a base surge is a dense gravity current); buoyant estimate ≈ 1.5 m/s at
  1.4 km, ≈ 4 m/s near GZ. **Pall top 73 m at 1.4 km**: inside the buoyant estimate 80–230 m only at its low end.
- **e-fold 150 s**: right only for > 100 µm grains; fines stay for hours (§4). Hiroshima at 1.4 km: ~20 min dark.
- **Visibility 40 m** in the densest dust ↔ 0.5–2 g/m³: plausible for the pall; the head near the ground at > 10 psi is far
  denser (MET, visibility ≪ 1 m).
- **Lift ∝ u² − u0², u0 = 5 m/s, saturating at 20 m/s**: u0 ≈ saltation onset for loose dry sand (4–5 m/s, 0.25–0.33 psi;
  Gaj & Small Eq. 7 with Table 1). Measured high-speed flux keeps rising: F ∝ U^1.144 (Hartenbaum 1971, in Gaj & Small p. 9)
  and F ∝ U^1.24 (Gaj & Small Eq. 8); low-speed aeolian flux ∝ u*³ (Gaj & Small §3.1). Saturation is supported only as a
  *supply* limit (moisture, crusts, vegetation: Batt & Peabody 1995; Gaj & Small §4).
- **"Opaque to 1.3 psi, gone at 0.3 psi"**: suspension needs u* ≥ 10 u*th (Gaj & Small §3.2, Owen 1964): with their Eq. 7
  (x = 10–100 m behind the shock) that is 2.7–3.6 psi for loose sand, 5–7 psi for u*th = 0.5, 8–12 psi for loam, 17–25 psi
  for desert pavement; saltation onset 0.25–0.33 / 0.45–0.6 / 0.7–0.9 / 1.2–1.7 psi. NTS sweep-up ended at 5–10 psi (M).
  So "opaque to 1.3 psi" needs loose sand or debris dust; "gone at 0.3 psi" matches saltation onset for loose sand.

## 8. Proposed one-model laws
State per ground cell at range r: column mass m_i (kg/m²) in size bins i, layer top h(t), shock arrival t_a.
Bins [I, from MET + MISERS BLUFF]: D = 3, 15, 40, 120, 400 µm with mass fractions ≈ 0.2, 0.3, 0.3, 0.15, 0.05.
1. **Friction velocity** behind the front (Gaj & Small Eq. 7, Schlichting rough plate):
   u* = U/√2 · [2.87 + 1.58 log10(x/K_s)]^(−1.25), x = distance behind the shock, K_s ≈ 0.05 m; U from Rankine–Hugoniot,
   decaying over the dynamic-pressure positive phase.
2. **Lift flux** while t_a < t < t_a + t+ (kg m⁻² s⁻¹):
   if u* ≥ 10 u*th: F = 1.80 ρ U^1.24 / (u*th^0.24 [2.87 + 1.58 log10(x/K_s)]^2.80) (Gaj & Small Eq. 8, from the Mirels 1984
   blowing model; ≈1 kg m⁻² s⁻¹ at 43 m/s over loose sand [I]);
   if u*th < u* < 10 u*th: F = C₃ u*³ (1 − u*th²/u*²), C₃ set for continuity at 10 u*th [I bridge; u*³ form: Gaj & Small §3.1];
   cap ∫F dt at the erodible supply (dry loose soil: effectively unlimited, 1 cm of soil ≈ 15 kg/m² [I]; moist soil 0
   (Batt & Peabody); vegetation u*th ≥ 3 m/s (Gaj & Small §2.2)). u*th per surface: §1.2. Urban targets: add a debris-dust
   source tied to building collapse (no data — tune to Hiroshima: dark at 1.2–1.5 km, twilight at 3.2 km).
3. **Vertical extent.** During the positive phase: h = c·R_s^(1/6)·Δx^(5/6) with c = 0.0256 (Kuhl; dense core) up to
   ~0.1 for the visible top (to reach MET/GRABLE 6–25 m and ~20° near the front) [I]. After it: h → h_b =
   √(2(1+2A) f_h E/(ρ c_p γ)) at rate ≈ w* (§5) [I]; within ~1 km of GZ add inflow toward and up the stem.
4. **Settling per bin**: dm_i/dt = −v_s,i m_i / h while turbulent (v_s from §4); when turbulence has decayed, the top of
   bin i falls at v_s,i. Coarse bins empty in 1–3 min (the dark "head" falls out); fine bins remain.
5. **Optics**: σ = Σ k_i m_i / h, k_i = 3Q/(2ρ_p D_i), Q ≈ 2 (submicron 3 m²/g, SCOPE); V = 3.9/σ; overhead τ = Σ k_i m_i.
   Colour: yellow-ochre (Kita); darker with smoke.
6. **Removal of fines**: dilution as h grows, inward advection by afterwinds, rain scavenging from ~20–30 min
   (Hiroshima), otherwise hours (§4). Choose the fine-bin decay so that 1.4 km is dark ~20 min and lighter by ~30 min.

## 9. Biggest uncertainties
1. **Urban debris dust**: Hiroshima's darkness at 1.2–1.5 km (where natural soil is only marginally lofted) has no
   quantitative source; the source term must be tuned to eyewitness timing.
2. **Growth after the positive phase**: no measured dust-top vs time at 1–3 km; buoyant estimate depends on the unknown
   fraction of flash energy that heats the air and on morning stratification (factor ~3 in h).
3. **Surface state**: u*th spans 0.28 to > 3 m/s and moisture can stop lofting (Batt & Peabody) — the radius of dense
   dust moves between ~3 and > 10 psi.
4. **Coarse fraction** (> 100 µm), under-sampled by the MET sampler, sets how fast the head falls out (1–3 min).
5. **Yield scaling of height**: "still uncertain" (Rosenblatt p. 18); ~Y^(1/3) from "a few hundred feet" (~20 kt) to
   ~1000 ft (1 Mt). Unverified: "Mach wave mass of 3800 t" for Hiroshima (Sutou 2018 Fig. 2; primary source not found).

## Sources
- ENW: Glasstone & Dolan 1977, *The Effects of Nuclear Weapons*, 3rd ed. §2.09–2.11, 2.37, 3.49–3.50, 3.79–3.85
  (pp. 124–126), Table 3.83, §7.71. https://archive.org/details/effectsofnuclear1977unit
- Rosenblatt 1981, *Introduction to Nuclear Dust/Debris Cloud Formation*, DNA 5832T, §2.2 pp. 17–23, Figs. 7–9, 13e.
  https://archive.org/details/DTIC_ADA114546
- Kuhl, Ferguson, Chien, Collins 1992, *Turbulent Dusty Boundary Layer in an ANFO Surface-Burst Explosion*, DNA-TR-92-17.
  https://archive.org/details/DTIC_ADA254130
- Gaj & Small 1991, *Target Area Operating Conditions: Dust Lofting from Natural Surfaces*, DNA-TR-90-71, Table 1, Eqs. 4–11.
  https://archive.org/details/DTIC_ADA237024
- Batt & Peabody 1995, *Dust Sweep-Up Experiments*, DNA-TR-94-117. https://archive.org/details/DTIC_ADA302183
- Eardley & Katz 1983, *Direct Course and Dust Experiments*, JASON JSR-82-402 (DIRECT COURSE: 600 t ANFO ≈ 1 kt
  nuclear; sweep-up > 0.01 Mt dust per Mt). https://archive.org/details/DTIC_ADA128908
- *Operation Teapot, Technical Summary of Military Effects, Programs 1–9*, WT-1153 (EX), 1981 extract, Shot 12 dust data.
  https://archive.org/details/TheEffectsOfTheAtomicBombOnHiroshima (file "ADA995120 Teapot 1955 ...")
- DICE THROW Symposium Proceedings vol. 3, 1977 (aircraft response paper). https://archive.org/details/DTIC_ADA048800
- MISERS BLUFF II Cloud Sampling Program, 1979. https://archive.org/details/DTIC_ADA087856
- NRC 1985, *The Effects on the Atmosphere of a Major Nuclear Exchange*, ch. 4 "Dust", pp. 17–27, Fig. 4.4.
  https://nap.nationalacademies.org/read/540/chapter/5
- Pittock et al. 1986, SCOPE 28 vol. I, §3.8 pp. 80–82.
  https://scope.dge.carnegiescience.edu/SCOPE_28_1/SCOPE_28-1_1.3_Chapter3_39-103.pdf
- British Mission to Japan 1946, *The Effects of the Atomic Bombs at Hiroshima and Nagasaki*, ¶9.
  https://archive.org/details/460101-effects-of-atomic-bombs-hiroshima-nagasaki
- Manhattan Engineer District 1946, *The Atomic Bombings of Hiroshima and Nagasaki*. https://www.gutenberg.org/ebooks/685
- Hersey 1946, *Hiroshima*, ch. 1–2. https://archive.org/details/in.ernet.dli.2015.165959
- Testimonies (Kita, Sakai, Takakura, Kawamoto): https://www.atomicarchive.com/resources/documents/hibakusha/
- Caron, interview 1985: https://ahf.nuclearmuseum.org/voices/oral-histories/bob-carons-interview
- Hagen & Woodruff 1973, Atmos. Environ. 7:323; Hagen & Skidmore 1977, Trans. ASAE 20(5):898.
  https://infosys.ars.usda.gov/WindErosion/publications/Andrew_pdf/1257.pdf , .../1601-A.pdf
- Sutou 2018, Genes Environ. 40:26 (Fig. 2 caption: DS02 yields/HOBs, 3800 t claim). https://europepmc.org/article/PMC/PMC6299535
- Not verified here: TTAPS (Turco et al. 1983) dust numbers; DIRECT COURSE/MINOR SCALE dust-cloud heights (DTIC was down).
