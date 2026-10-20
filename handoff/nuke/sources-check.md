# Sources check: nuclear-strike physics numbers

Checked on 2026-10-18, before any code change. The checks used web sources only.

**Reference cases.** 15 kt at a height of burst (HOB) of 592 m, and 2 kt at 240·W^(1/3) = 302 m. Both have the same scaled height, 240 m/kt^(1/3) (787 ft/kt^(1/3)). Every 2 kt distance and time therefore equals the 15 kt value × (2/15)^(1/3) = **0.511**.

**Labels.**
- **[primary]**: read in the original document.
- **[derived]**: my calculation from a primary formula. The scripts are listed at the end.
- **[secondary]**: a source that quotes or plots the original.
- **[unconfirmed]**: not found in any source. Treat it as an estimate.

**Abbreviations.**
- **G&D**: Glasstone & Dolan, *The Effects of Nuclear Weapons*, 3rd ed., 1977 (OSTI https://www.osti.gov/servlets/purl/6852629). Page numbers are printed pages.
- **G64**: Glasstone, *The Effects of Nuclear Weapons*, revised ed., 1962/1964 (archive.org item `TheEffectsOfNuclearWeapons1957`, file "Glasstone Effects of Nuclear Weapons 1964 Revised Edition.pdf").
- **Brode87**: H. L. Brode, *Airblast from Nuclear Bursts — Analytic Approximations*, PSR Report 1419-3, Pacific-Sierra Research, July 1987, DNA contract 001-85-C-0089 (https://archive.org/download/airblastfromnucl00brod/airblastfromnucl00brod.pdf).

---

## 1. Air-burst overpressure on the ground: Brode HOB fit (replaces the constant ×1.8)

### 1a. Formula to use: Brode87 Eq. (62) [primary]
Source: Brode87 p. 60 (form) and pp. 70–71 (functions), checked against the scanned page. Brode's own accuracy note:

> "For NRL calculations, agreement, in most cases, is within 20 percent on peak overpressure."

**Inputs**
- W in kt; m = W^(1/3).
- Ground range GR and burst height H in feet.
- x = GR/m/1000 and y = H/m/1000, both in kft/kt^(1/3).
- r = √(x² + y²) and z = y/x. Brode's Fortran caps z at 100 and sets x, y ≥ 1e-9.

```
a(z) = 1.22 − 3.908 z²/(1 + 810.2 z⁵)
b(z) = 2.321 + 6.195 z¹⁸/(1 + 1.113 z¹⁸) − 0.03831 z¹⁷/(1 + 0.02415 z¹⁷) + 0.6692/(1 + 4164 z⁸)
c(z) = 4.153 − 1.149 z¹⁸/(1 + 1.641 z¹⁸) − 1.1/(1 + 2.771 z^2.5)
d(z) = −4.166 + 25.76 z^1.75/(1 + 1.382 z¹⁸) + 8.257 z/(1 + 3.219 z)
e(z) = 1 − 0.004642 z¹⁸/(1 + 0.003886 z¹⁸)
f(z) = 0.6096 + 2.879 z^9.25/(1 + 2.359 z^14.5) − 17.15 z²/(1 + 71.66 z³)
g(z) = 1.83 + 5.361 z²/(1 + 0.3139 z⁶)
h(z,r,y) = 8.808 z^1.5/(1 + 154.5 z^3.5) − (0.2905 + 64.67 z⁵)/(1 + 441.5 z⁵) − 1.389 z/(1 + 49.03 z⁵)
           + 1.094 r²/((781.2 − 123.4 r + 37.98 r^1.5 + r²)(1 + 2y))
j(y) = 0.000629 y⁴/(3.493e-9 + y⁴) − 2.67 y²/(1 + 1e7 y^4.3)
k(y) = 5.18 + 0.2803 y^3.5/(3.788e-6 + y⁴)
ΔP [psi] = 10.47/r^a + b/r^c + d·e/(1 + f·r^g) + h + j/r^k
```

**Code: glasstone** by Edward Geist ([GOFAI/glasstone](https://github.com/GOFAI/glasstone), file `glasstone/overpressure.py`)
- Functions: `_brode(z, r, y)`, `_brodeop(bomb_yield, ground_range, burst_height)` (kt and kilofeet, result in psi), and `brode_overpressure(y, r, h, yunits='kT', dunits='m', opunits='kg/cm^2')`.
- License: **MIT**, "Copyright (c) 2016 GOFAI".
- It differs from the paper in one constant: f(z) uses `17.5` where the paper has `17.15`. The effect is at most 0.6 % in range. **Use 17.15.**
- The Fortran `PPEAK` in Brode87 Appendix B (p. 181) adds a `BB` term to b and evaluates y in feet inside h. That only matters below about 1 psi, where the 0.3 psi range comes out 8 % shorter. The published Eq. (62) is the one to use.
- glasstone's DNA functions (`_DNAairburstpeakop`) blow up near ground zero (about 3e8 psi at 1 m). **Do not use them there.**

### 1b. Ground ranges [derived from Eq. 62, cross-checked against G&D Figs. 3.73b and 3.73c]
- The G&D column is the range read at scaled HOB 787 ft on the 1 kt charts, then multiplied by 15^(1/3).
- The surface-burst column uses Eq. 62 with HOB = 0 at 15 kt, for comparison.

| psi | 15 kt / 592 m | 2 kt / 302 m | G&D 3.73 (15 kt) | DNA fit (glasstone) | 15 kt surface burst |
|---|---|---|---|---|---|
| 20 | 358 m | 183 m | ~323 m | 530 m | 546 m |
| 10 | 998 m | 510 m | ~1,080 m | 952 m | 775 m |
| 5 | **1,603 m** | 819 m | ~1,620 m | 1,547 m | 1,146 m |
| 2 | 2,833 m | 1,448 m | ~2,930 m | 2,813 m | 2,107 m |
| 1 | 4,594 m | 2,348 m | ~4,550 m | 4,454 m | 3,516 m |
| 0.3 | 8,847 m | 4,521 m | not on chart | 10,378 m | 8,991 m |

- **Profile at 15 kt / 592 m** (psi at ground range): 28.5 at ground zero; 23.6 at 250 m; 16.1 at 500 m; 13.5 at 750 m; 10.0 at 1 km; 5.56 at 1.5 km; 3.46 at 2 km; 1.85 at 3 km; 1.24 at 4 km; 0.64 at 6 km; 0.37 at 8 km; 0.23 at 10 km.
- **Agreement with G&D:** within about 10 % at every pressure. At ground zero G&D gives about 30 psi (its 30 psi contour crosses ground zero at about 790 ft); Eq. 62 gives 28.5.
- **The mod today** puts 5 psi at 0.92 km. Eq. 62 puts it at 1.60 km.
- **Where the Mach stem forms** [primary formula, Brode87 Appendix B]: `XM = 170·Y/(1 + 60·Y^0.25) + 2.89·(Y/100)^2.5`, with Y in ft/kt^(1/3), giving XM in ft/kt^(1/3). That is **694 m** at 15 kt and 354 m at 2 kt. The broken line in G&D Fig. 3.73b gives about 710 m. glasstone's `_scaledmachstemformationrange` gives the same value.

### 1c. Dynamic pressure and wind behind the front
- **Mach region (ground range > XM)** [primary relations]. G&D §3.54, p. 97: "in the Mach region below the triple point path for an air burst, the various blast wave characteristics at the shock front are uniquely related by the Rankine-Hugoniot equations." The equations (G&D §3.55):
  - q = 2.5·p²/(7·p0 + p) (G&D eq. 3.55.1)
  - u = (5p/7p0)·c0/√(1 + 6p/7p0)
  - U = c0·√(1 + 6p/7p0)
  - p0 = 14.7 psi and c0 = 340 m/s. These reproduce G&D Table 3.07 exactly (10 psi → q 2.2 psi, wind 294 mph = 131 m/s).
- **Values at 15 kt with Eq. 62 pressures** [derived]:

  | Range | q | Wind |
  |---|---|---|
  | 750 m | 3.9 psi | 167 m/s |
  | 1 km | 2.2 psi | 131 m/s |
  | 1.5 km | 0.71 psi | 80 m/s |
  | 2 km | 0.28 psi | 52 m/s |
  | 3 km | 0.08 psi | 29 m/s |
  | 4 km | 0.04 psi | 20 m/s |

- **Regular-reflection region (inside XM)**: the horizontal q falls to 0 at ground zero, where the front strikes vertically. Readings from G&D Fig. 3.75 (p. 117) at scaled HOB 787 ft, converted to 15 kt:
  - q_h ≈ 1 psi at about 170 m.
  - 2 psi at about 450 m.
  - Peaks at about 4 psi between 680 and 830 m.
  - Above 830 m it matches the Rankine-Hugoniot q from Eq. 62.
- **Brode's own dynamic-pressure HOB fit**, Brode87 Eq. (64), pp. 120–122: I transcribed it, but my transcription does not reproduce his Fig. 84 beyond 1,000 ft. **Not recommended [unconfirmed].**

### 1d. Arrival time and positive-phase duration on the ground [primary formula; values derived]
Source: Brode87 Appendix B, `SUBROUTINE PT` (pp. 179–180), read from the scan.
- X and Y are in ft/kt^(1/3), and R = √(X² + Y²)/1000.
- TAU and D+ come out in ms/kt^(1/3). Multiply both by W^(1/3).

```
U   = (0.543 − 21.8R + 386R² + 2383R³)·R⁸ / (2.99e-14 − 1.91e-10R² + 1.032e-6R⁴ − 4.43e-6R⁶ + (1.028 + 2.087R + 2.69R²)·R⁸)
TAU = U                                   if X < XM
Wt  = (1.086 − 34.605R + 486.3R² + 2383R³)·R⁸ / (3.0137e-13 − 1.2128e-9R² + 4.128e-6R⁴ − 1.116e-5R⁶ + (1.632 + 2.629R + 2.69R²)·R⁸)
TAU = U·XM/X + Wt·(1 − XM/X)              if X ≥ XM
S2  = 1 − 15.18(Y/100)^3.5/(1 + 15.18(Y/100)^3.5) − [0.02441(Y/1e6)²/(1 + 9000(Y/100)^7)]·[1e10/(0.441 + (X/100)^10)]
D+  = [(1640700 + 24629T + 416.15T²)/(10880 + 619.76T + T²)] ·
      [0.4 + 0.001204T^1.5/(1 + 0.001559T^1.5) + (0.6126 + 0.5486T^0.25/(1 + 0.00357T^1.5) − 3.47T^0.637/(1 + 5.696T^0.645))·S2],   T = TAU
```

| Range | 15 kt arrival | 15 kt D+ | 2 kt arrival | 2 kt D+ |
|---|---|---|---|---|
| Ground zero | 0.79 s | 0.45 s | 0.40 s | 0.23 s |
| 0.6 km | 1.40 s | 0.61 s | 1.31 s | 0.40 s |
| 1 km | 2.17 s | 0.74 s | 2.31 s | 0.47 s |
| 1.5 km | 3.34 s | 0.85 s | 3.68 s | 0.52 s |
| 2 km | 4.64 s | 0.93 s | 5.08 s | 0.54 s |
| 3 km | 7.37 s | 1.01 s | 7.94 s | 0.56 s |
| 5 km | 13.05 s | 1.09 s | 13.72 s | 0.58 s |
| 10 km | 27.5 s | 1.15 s | 28.2 s | 0.60 s |

- **Check against G&D Fig. 3.76** (p. 119, 1 kt):
  - At HOB 0 the fit is within ±15 % of the chart. Example: chart 0.30 s at 1,400 ft, fit 0.29 s.
  - At HOB 787 ft the fit runs 10–15 % longer beyond 1,000 ft. Example: the chart's 0.30 s contour sits at about 1,950 ft, where the fit gives 0.34 s.
  - Scaled to 15 kt, the chart gives D+ ≈ 0.62 s at 0.9 km and 0.74 s at 1.5 km.
- **Free-air comparison** [primary figure]: Brode87 Fig. 15 (p. 37) puts the DNA 1 kt standard (Needham & Crepeau 1981) at about 385 ms at 2 km, against Brode's own fit at about 305 ms. Scaled, that is about 0.95 s at 5 km for 15 kt.
  - Brode87 Eq. (46), free-air duration vs range, keeps growing: 1.85 s at 10 km for 15 kt.
  - Eq. (43), duration vs arrival time, levels off near 0.75 s.
  - Brode p. 36: the difference "is typical of the uncertainty and expected variation (particularly at large distances)".
- **The mod's D+ = 0.4·W^(1/3) s** (0.99 s at 15 kt) is right from about 3 km outward and too long within 1 km.
- The arrival-time column replaces the "0.42 c" front speed. The actual mean speed is 10 km / 27.5 s ≈ 364 m/s, and the front never travels slower than the speed of sound.

**Confidence:** high for overpressure (two independent sources agree within about 10 %); medium for D+ (fits differ by 15–25 %); high for the Rankine-Hugoniot q and wind in the Mach region.

---

## 2. Mushroom cloud: rise, stabilization, size

| Source | 2 kt | 15 kt | Label |
|---|---|---|---|
| G&D Fig. 2.16 (p. 34): cloud top above burst point for yields ≤15 kt; cloud radius | top ≈ 11 kft (3.4 km); radius ≈ 2.5 kft (0.75 km) | top ≈ 20 kft (6.1 km); radius ≈ 4.5 kft (1.4 km) | primary, read off the plot (±2 kft) |
| G&D Fig. 9.96 (p. 431): stabilized top and bottom altitude, "surface or low air bursts" | top 11.4 kft (3.5 km); bottom 6.9 kft (2.1 km) | top 21.6 kft (6.6 km); bottom 11.7 kft (3.6 km) | primary; numbers from NUKEMAP's fit of this figure (`cloud_top`, `cloud_bottom` in https://static.nuclearsecrecy.com/nukeeffects.js) |
| C. F. Miller 1963 (SRI IM-4021), as coded in NUKEMAP `cloud_final_height` etc. (W ≤ 28 kt): cap centre h = 6,600·W^0.445 ft, vertical semi-axis b = 1,400·W^0.431 ft, horizontal semi-axis a = 2,340·W^0.431 ft | centre 2.7 km, top 3.3 km, bottom 2.2 km, a = 0.96 km | centre 6.7 km, top 8.1 km, bottom 5.3 km, a = **2.3 km** | secondary (code comment calls it "slightly dodgy") |
| **Observed, Nevada:** Hawthorne 1979 (DNA 1251-1-EX) cloud top and bottom vs time, plotted in Blakeley et al., "Real time simulations of nuclear cloud rise with the ERF model", LLNL-JRNL-2012579 (2026), Fig. 2 and Tables 1 and 3 (https://www.osti.gov/servlets/purl/3493017) | Wasp, 1 kt at 232 m: top ≈ 5.3 km AGL at 8 min, still rising; bottom ≈ 3.2 km, level by about 6.5 min | Grable, 15 kt at 160 m: **top ≈ 10 km AGL, bottom ≈ 6 km AGL**; top within about 3 % of final by 7 min. ERF/WRF mean cloud height 8.1 / 8.3 km | secondary plot of primary data. I could not download DNA 1251-1 itself (DTIC/DTRA return 403) |
| WSEG-10 cloud-centre height, H_c = 44 + 6.1 ln Y − 0.205·\|d\|·d kft, with d = ln Y + 2.42 and Y in **Mt** (Hanifen 1980, as in glasstone `fallout.py`, class `WSEG10`) | 2.8 km | 5.8 km | primary formula. Note: glasstone passes the raw `yld` (kt) to `np.log` here, not `self.yld` (Mt). This is a glasstone bug when yunits='kT' |

**Time to stabilization**
- G&D §2.15, p. 32 (1 MT context): "The cloud attains its maximum height after about 10 minutes."
- J. D. Shreve, Upper Atmosphere Sampling Symposium, Sandia, 1961, p. 78 (rminucleardocs.icaad.ngo): "Time of stabilization apparently increases somewhat with yield, but is usually taken as 6 minutes."
- WSEG-10 simply assumes 15 min.
- Observed: Grable (15 kt) about 7–8 min; Wasp (1 kt) cloud bottom levels off at about 6–7 min.
- **Use: 2 kt ≈ 6 min; 15 kt ≈ 7–8 min.**

**Early rise at about 15 kt** (Grable cloud top, read off the plot)
- About 2.5 km at 30 s, 4 km at 1 min, 6 km at 2 min, 7.5 km at 3 min, 9 km at 5 min.
- That is about **65 m/s on average over the first minute** and about 33 m/s during the second minute.
- G&D Fig. 10.158a (p. 506), 10 kt at low altitude: about 3 mi (4.8 km) at 1 min, 4 mi at 2 min, 5.5 mi (8.9 km) at 5 min.
- G&D Table 2.12 (440 ft/s ≈ 134 m/s in the first minute) is for **1 MT**. Do not use it for 15 kt.

**Recommendation**
- **15 kt:** top ≈ 9–10 km AGL, bottom ≈ 5–6 km, cap radius ≈ 1.5–2.3 km, stabilization 7–8 min. This follows the Nevada observations. G&D's charts are about 1.5× lower.
- **2 kt:** top ≈ 5 km and bottom ≈ 3 km, using the observed 1 kt Wasp as a floor. G&D gives 3.4 / 2.1 km.

**Confidence:** medium for heights and rise (weather changes them by ±30 %); low for cap radius (no observation found).

---

## 3. Shielding: prompt gamma and neutrons, fallout

**Initial gamma, effective tenth-value thickness (TVL)** [primary: G&D Table 8.41, p. 337]

| Material | Density (lb/ft³) | Fission-product γ TVL | N-capture γ TVL |
|---|---|---|---|
| Concrete | 146 | 11 in (0.28 m) | 16 in (0.41 m) |
| Earth | 100 | 16 in (0.41 m) | 24 in (0.61 m) |
| Steel | 490 | 3.3 in | 4.3 in |
| Water | 62.4 | 24 in | 59 in |
| Wood | 40 | 48 in | 65 in |

- G&D §8.42 rule of thumb: D×T ≈ 130 lb/ft² for fission-product γ and 200 lb/ft² for N-capture γ. It recommends "that the higher of these values be employed".
- **Per 1 m block** [derived]:
  - Concrete: 10^(−1/0.41) = **0.004**.
  - Earth: 10^(−1/0.61) = **0.023**.
  - Stone at 2.65 g/cm³, with D×T = 200: TVL ≈ 0.37 m, giving **0.002**.

**Neutrons** [primary: G&D §8.68, p. 348]
- "A thickness of 12 inches of concrete … will decrease the neutron fluence from a thermonuclear weapon by a factor of about 10, and 24 inches by a factor of roughly 100."
- Damp earth needs "about 50 percent greater thickness".
- So TVL ≈ 0.30 m for concrete and ≈ 0.46 m for earth.
- Per 1 m block [derived]: concrete **5e-4**, earth **7e-3**.

**Whole structures**, where scattering through openings dominates [primary: G&D Table 8.72, p. 349]. Values are the initial-γ and neutron transmission factors.

| Structure | Initial γ | Neutrons |
|---|---|---|
| 3 ft underground | 0.002–0.004 | 0.002–0.01 |
| Frame house | 0.8–1.0 | 0.3–0.8 |
| Multistorey building, lower storeys | 0.3–0.6 | 0.3–0.8 |
| Concrete blockhouse, 12-in walls | 0.05–0.1 | 0.2–0.4 |
| Concrete blockhouse, 24-in walls | 0.007–0.02 | 0.1–0.2 |
| Shelter partly above grade, 2 ft earth | 0.03–0.07 | 0.02–0.08 |

**Fallout gamma**
- G&D gives no fallout half-value thickness (HVL) table. §9.118 says fallout γ is softer, so the same material attenuates it more.
- G&D Table 9.120 (p. 441) transmission factors: 3 ft underground **0.0002**; basement 0.05–0.1; 12-in blockhouse 0.001–0.03; 24-in blockhouse 0.0001–0.002; frame house 0.3–0.6.
- **HVL** [secondary, government-published]: C. H. Kearny, *Nuclear War Survival Skills* (ORNL, 1987), ch. 1, Fig. 1.3. Packed earth "about 3.6 inches (9 centimeters)"; concrete "about 2.4 inches (6.1 cm)".
- Per 1 m block [derived]: earth ≈ 5e-4, concrete ≈ 1e-5.

**What this means for the mod**
- The current values, stone ×0.1 and earth ×0.2 per 1 m block, let far too much through a solid block in the line of sight. They should be about 0.002–0.004 (stone/concrete) and about 0.02 (earth) for initial γ, and even lower for neutrons and fallout.
- Real protection is limited by radiation scattered around the shield. Keep a floor taken from Table 8.72, for example no structure better than about 0.002 for initial γ unless it is deep underground.

**Confidence:** high for the table values; medium for the per-block extrapolation, since G&D notes the attenuation in the first inches is greater than the TVL implies.

---

## 4. Untreated lethal dose and slope

**Use** [secondary reproduction of a primary review, Anno et al. 2003]
- **LD50 = 4.14 Gy free-in-air, no medical care**, lognormal with **GSD 1.381**.
- So P(death) = Φ(ln(D/4.14)/ln 1.381).
- Percentiles: LD10 = **2.74 Gy**; LD90 = **6.26 Gy**; LD1 = 1.95; LD5 = 2.43; LD95 = 7.04; LD99 = 8.77; threshold (0.01 %) = 1.25 Gy.
- With mass-casualty medical care every value is ×1.5 (LD50 = 6.21 Gy).

**Sources**
- D. J. Strom, *Health Impacts from Acute Radiation Exposure*, PNNL-14424 (2003, rev. 2005), Table 10 and Fig. 4. Its caption: "LD50 is 4.14 Gy for no medical care, and 1.5× higher (6.21 Gy) … These are lognormal distributions with GSDs of 1.381."
- G. H. Anno, R. W. Young, R. M. Bloom, J. R. Mercier, *Health Phys.* 84(5):565–575 (2003), PMID 12747475. The abstract gives no numbers.

**Cross-checks**
- PNNL Table 4 (from NCRP 1989): LD50/60 is "3.2-3.6" Gy with minimal supportive care. That is a marrow/midline dose, which is lower than free-in-air for the same exposure.
- G&D Table 12.108 (pp. 580–581): 200–600 rem gives incidence of death "0 to 90%"; 600–1,000 rem gives "90 to 100%"; above 1,000 rem, 100 %.

**Note:** the audit's bug, where the 2–6 Gy death chance is never applied, falls exactly on the steep part of this curve.

**Confidence:** high.

---

## 5. Hiroshima fire, and black rain

**Area** [primary]
- US Strategic Bombing Survey, *The Effects of Atomic Bombs on Hiroshima and Nagasaki* (1946), ch. II (https://ibiblio.org/hyperwar/AAF/USSBS/AtomicEffects/AtomicEffects-2.html): "… a roughly circular shape to the 4.4 square miles which were almost completely burned out."
- That is **11.4 km²**, a radius of about 1.9 km.
- The fire wind "attained a maximum velocity of 30 to 40 miles per hour 2 to 3 hours after the explosion". The natural ground wind was about 5 mph.

**Timing** [primary: G&D §7.71, p. 304]
- "About 20 minutes after the detonation … a mass fire developed showing many characteristics usually associated with fire storms."
- The wind reached "a maximum velocity of 30 to 40 miles per hour about 2 to 3 hours after the explosion, decreasing to light or moderate and variable in direction about 6 hours after."

**Timing, finer detail** [secondary: S. Sutou, *Genes and Environment* 42:1 (2020), doi:10.1186/s41021-019-0141-8, summarising Uda's 1945–53 survey. The author argues against the linear no-threshold model; the chronology itself comes from Uda.]
- "Fires started about 5 min after detonation in shattered houses."
- "The cumulative fires began to rage around 9 AM [≈45 min], becoming most furious from 10 AM to 2 PM, but abated in the evening … The fire lasted for three days. Smoldering lasted for a week."

**Black rain: yes**
- G&D §7.71 [primary]: "The wind was accompanied by intermittent rain, light over the center of the city and heavier about 3,500 to 5,000 feet (0.67 to 0.95 mile) to the north and west."
- Uda and Masuda maps, via Sutou [secondary]: started about 9 AM, heaviest about 10 AM, drifted north-west, gone about 30 km NNW by about 3 PM. Uda's heavy-rain oval is about 19 × 11 km; Masuda estimated about 4× that area.

**Use for 15 kt**
- Ignitions at 0–5 min.
- Mass fire forms at **about 20 min**.
- Peak fire wind 13–18 m/s at **2–3 h**; most intense burning roughly 2–6 h.
- Wind dies down at **about 6 h**; flames into the night; smouldering for days.
- Black rain from about 45 min, peaking at about 1.75 h, lasting about 6–7 h downwind.

The mod's firestorm is currently compressed about 15×.

**Confidence:** high for area and wind timing; medium for the hour-by-hour detail.

---

## 6. Thermal transmittance vs visibility; visibility in rain and thunderstorms

**Transmittance vs distance at two visibilities**
- G&D 1977 has no family of curves by visibility. Fig. 7.98 (p. 318) covers only a visibility of 12 miles, as a function of HOB.
- The earlier edition does have one [primary]: **G64 Fig. 7.104** (p. 363), "Atmospheric transmittance as a function of distance for visibilities of 10 miles and 50 miles", direct plus scattered light, 10 and 5 g/m³ water vapour. G64 §7.104: "reliable up to distances of half the visibility".
- Readings (slant range → τ):

  | Visibility | 0.16 km | 1.6 km | 3.2 km | 6.4 km | 8.5 km | 16 km | 40 km |
  |---|---|---|---|---|---|---|---|
  | 10 mi (16 km) | 0.94 | 0.765 | 0.68 | 0.56 | 0.50 | — | — |
  | 50 mi (80 km) | 0.965 | 0.88 | 0.82 | 0.75 | — | 0.635 | 0.50 |

- **My fit to these readings** [derived]: **τ = exp(−(D/V)^0.544)**. It is within ±0.03 for D ≤ V/2 and V between 16 and 80 km. Below V = 16 km it is an extrapolation.
- G&D §7.13 (p. 279): at distances "less than about half the visibility, the degree of attenuation … is relatively insensitive to atmospheric conditions if at least moderately clear (10 miles or more)".
- G&D §7.100 (pp. 318–319): multiply τ by 1.5 for a cloud layer above the burst or snow on the ground, and by 2.25 for both.
- G&D §7.16: a burst *above* a dense cloud, smoke or fog layer loses much more. No number is given.
- **Direct beam only** (sharp shadows, glare): G&D §7.95 footnote (p. 316): κ ≈ 0.03 km⁻¹ at 80 km visibility, 0.1 at 40 km, 0.2 at 20 km.
- For converting visibility to an extinction coefficient: Rec. ITU-R P.1814-0 (2007) eq. (4) uses 3.91/V, with visibility defined at 2 % transmission.

**Visibility in rain**
- **Moderate rain is 0.11–0.30 in/h, i.e. 2.8–7.6 mm/h** [secondary: the AMS Glossary entry "rain" and the AFMAN 15-111 table, as reported by search. Both pages refused direct fetch (403).]
- Rain extinction [primary]: ITU-R P.1814 Table 2: γ_rain = 1.076·R^0.67 dB/km (France) or 1.58·R^0.63 (Japan).
- Implied visual range V = 3.91/(0.2303·γ) [derived, rain alone with no mist]:
  - Moderate rain: **about 4–8 km** (France) or about 3–6 km (Japan).
  - 25 mm/h: 1.4–1.8 km.
  - 50 mm/h: 0.9–1.2 km.
  - 100 mm/h: 0.6–0.7 km.
- **Thunderstorm**: I found no primary observation statistics.
  - The same formula gives about 0.6–1.8 km inside heavy rain shafts (25–100 mm/h).
  - A Météo-France/ALADIN model report (https://www.cnrm.meteo.fr/aladin/IMG/pdf/report_visibility_cy43t2bf10_updated20191030.pdf) says forecast "visibility dropped below 1 km in case of simulated reflectivity exceeding 40 dBz". That is model output, not observation.
- **Use:** moderate rain V ≈ 5 km; inside a thunderstorm rain shaft V ≈ 1 km (0.5–2 km); higher outside the shafts.

**Confidence:** high for the clear-air curves; medium for the rain numbers [derived]; low for thunderstorms [unconfirmed].

---

## 7. Far blast sound

**Converting pressure to sound level**
- Standard definition: L = 20·log10(p / 20 µPa) dB SPL, unweighted peak. 1 psi = 6,894.757 Pa.

  | Pressure | Level |
  |---|---|
  | 10 psi | 190.7 dB |
  | 5 psi | 184.7 dB |
  | 2 psi | 176.8 dB |
  | 1 psi | 170.7 dB |
  | 0.3 psi | 160.3 dB |
  | 0.1 psi | 150.7 dB |
  | 0.01 psi | 130.7 dB |

- At 15 kt / 592 m with Eq. 62: about 176 dB at 3 km, about 169 dB at 5 km and about 158 dB at 10 km.
- These are peak levels, not loudness. Most of the energy is below 20 Hz.

**Positive-phase duration at 1–10 km, 15 kt** (see 1d)
- On the ground, HOB fit: 0.74 s at 1 km; 1.0 s at 3 km; 1.15 s at 10 km.
- Free air, DNA standard: about 0.95 s at 5 km.
- Reed's long-range reference duration J0 = 0.375 s at 1 kt, scaled by W^(1/3): **about 0.92 s at 15 kt and 0.47 s at 2 kt**. Fundamental frequency ν ≈ 1/(2·J0·W^(1/3)) ≈ 0.5 Hz at 15 kt.
- Source for Reed's values [secondary]: McFadden et al., "Fireball characteristics derivable from acoustic data", arXiv:2102.06574, eq. (19) and the text after it, citing J. W. Reed, *J. Geophys. Res.* 77 (1972), pp. 1616–1622 and 1623–1629.

**Pulse shape**
- G&D §3.05 [primary]: the negative phase "is somewhat longer than the positive phase"; underpressure is "generally not more than about 4 pounds per square inch below the ambient pressure".
- At 1–10 km in still air, this means one sharp shock (a crack or boom), then about 1 s of positive and about 1–2 s of negative phase. Echoes from buildings and terrain add the rumble. This last point is my inference [unconfirmed].

**Long range, ducting** [primary: G&D §§3.40–3.43, pp. 93–94]
- A temperature inversion raises far-field pressure. Unstable afternoon air bends the wave away from the ground, so it decays faster.
- Jet-stream ducting may cause "several 'rays' to converge into a sharp focus", the first at "20 to 50 miles", repeating at intervals. "In an explosion of a 20-kiloton weapon in the air at the Nevada Test Site, this effect caused windows to break 75 to 100 miles away."
- Mesospheric ducting acts downwind at 70–150 miles: "breakage of windows on the second ground strike at 285 miles downwind from a 17-kiloton nuclear air burst".
- Thermospheric ducting reaches beyond 100 miles, upwind: "no structural damage … However, sharp pops and crackles have been heard."
- **So:** far away, the sound arrives as several separate arrivals, not one N-wave: sharp cracks at foci, quiet zones in between.
- Reed's own microbarograph reports (SC-RR-69-572; the Danny Boy and Sedan long-range reports) are on DTIC, which refused access (403). **Not checked.**

**Confidence:** high for the dB conversion and D+; medium for the qualitative long-range behaviour.

---

## 8. Ground shock from an air burst at 1–2 km

**What G&D says** [primary: G&D §§6.12–6.16, pp. 236–237; §6.84, p. 260; §6.94, p. 263]
- Two sources of ground motion: direct coupling near the crater, and "airslap", the air blast pressing on the ground.
- Airslap "almost always results in a single movement downward followed by a slower, partial relaxation upward … Close to the surface, airslap motion is initially abrupt, similar to the rise of pressure in the air shock."
- **Superseismic:** where the air front moves faster than the seismic velocity, "the first ground motion is determined essentially by the airslap". The motion then arrives *with* the blast.
- **Outrunning:** where the ground wave is faster, the ground ahead of the blast shows "two or three cycles of a damped … undulation with the first motion usually upward".
- "Outrunning ground motion generally occurs so far from surface zero that it is relatively small."
- G&D gives no numeric formula: "No simple method is presently available for calculating the effects of outrunning ground motion" (§6.84).

**How fast the blast sweeps along the ground at 15 kt / 592 m** [derived from Brode's arrival-time fit]
- About 1,900 m/s within 300 m.
- About 670 m/s at 300–600 m.
- About 520 m/s at 0.6–1 km.
- About 430 m/s at 1–1.5 km.
- About 385 m/s at 1.5–2 km.

**How much earlier the ground wave arrives** [derived]
Assumption: the ground wave leaves from the point where the blast's sweep speed drops below the ground P-wave speed c_P, then travels at c_P.

| c_P | Ground type (typical) | Lead at 1 km | Lead at 1.5 km | Lead at 2 km |
|---|---|---|---|---|
| 600 m/s | Dry soil | 0.1 s | 0.45 s | 0.9 s |
| 1,500 m/s | Wet soil | 0.8 s | 1.6 s | 2.6 s |
| 3,000 m/s | Rock | 1.1 s | 2.1 s | 3.2 s |

Typical c_P values by ground type: **[unconfirmed]**.

**Size** [derived]
- One-dimensional plane-wave relation: v ≈ p/(ρ·c).
- At 1 km (10 psi): **about 2–9 cm/s**. At 2 km (3.5 psi): about 1–3 cm/s. Range is for ρ = 1.8–2.0 t/m³ and c = 450–1,500 m/s.
- I could not confirm this against a nuclear design manual: the DTIC copies, the Hindawi/Wiley paper doi:10.1155/2018/3029837, and ERDC TR-N-72-2 were all blocked.

**Use:** the main jolt comes with the blast front, downward then easing. On firm ground or rock there is a weak tremor 0.1–3 s *before* the blast at 1–2 km.

**Confidence:** high for the qualitative behaviour; low for the magnitudes [derived/unconfirmed].

---

## 9. Cooled cap: water, optical depth, NO2 colour

**Colour** [primary]
- G&D §2.08 (p. 29): "The color of the radioactive cloud is initially red or reddish brown, due to the presence of various colored compounds (nitrous acid and oxides of nitrogen) … As the fireball cools and condensation occurs, the color of the cloud changes to white, mainly due to the water droplets."
- G&D §2.123: ozone, nitrogen dioxide and nitrous acid form ahead of the shock front early on. No duration is given.

**Colour persists inside the cloud** [primary]
- L. B. Taylor & W. A. Minge, *History of Air Force Atomic Cloud Sampling*, AFSC Historical Publication 61-142-1 (1963), printed pp. 201–202 (https://rminucleardocs.icaad.ngo/en/entity/odrmuyrjaao?page=221 and 222; quotes taken from the OCR text):
  - Sampling crews recognised the bomb material "by its reddish brown color in transmitted light arising largely from the presence of nitrogen dioxide".
  - Condensed moisture "caused the primary cloud to appear white in reflected light", while the interior was "suffused with typically reddish brown light".
- So the brown persists inside the cloud for as long as the cloud was sampled, which was minutes or more. **I found no timed duration [unconfirmed].**
- The visible sequence is a reddish-brown top and edges first, then white from outside once condensation starts. Any number of seconds or minutes for that change would be a guess.

**Liquid water content and optical depth of the cap: not found in any source.**
- Estimate [unconfirmed]: τ = 3·LWP/(2·ρw·r_e), the standard cloud-optics relation. With LWC 0.1–1 g/m³ over 2–3 km depth and r_e ≈ 10 µm, τ ≈ 30–450.
- That is opaque, like a thunderstorm anvil. Render the cap as an optically thick cloud from about 1 min onward.

**Confidence:** high for the colour qualitatively; none for durations and optical depth.

---

## Summary

| # | Item | Value to use | Source | Confidence |
|---|---|---|---|---|
| 1 | Ground overpressure vs range and HOB | Brode Eq. 62 (f-constant 17.15). 15 kt/592 m: 20/10/5/2/1/0.3 psi at 0.36/1.00/1.60/2.83/4.59/8.85 km. 2 kt/302 m: ×0.511 | Brode87 pp. 60, 70–71; glasstone `overpressure._brode` (MIT); G&D Figs. 3.73b/c | High (±10 %) |
| 1 | Dynamic pressure and wind | Mach region (beyond 694 m at 15 kt): Rankine-Hugoniot q = 2.5p²/(7p0+p), u = (5p/7p0)c0/√(1+6p/7p0). Inside: q_h → 0 at ground zero | G&D §3.54–3.55, Table 3.07, Fig. 3.75 | High |
| 1 | Arrival time and D+ on the ground | Brode PT fit: 15 kt arrival 2.17/4.64/13.05/27.5 s at 1/2/5/10 km; D+ 0.74/0.93/1.09/1.15 s | Brode87 App. B pp. 179–180; G&D Fig. 3.76 | Medium (±15 %) |
| 2 | Cloud (15 kt) | Top 9–10 km, bottom 5–6 km, cap radius 1.5–2.3 km, stable at 7–8 min, about 65 m/s mean rise in the first minute | Nevada data (Grable) via Blakeley et al. 2026; G&D Figs. 2.16, 9.96 (about 1.5× lower); Miller 1963 | Medium (radius low) |
| 2 | Cloud (2 kt) | Top about 5 km, bottom about 3 km, stable at about 6 min | Wasp 1 kt observation; G&D 3.5/2.1 km | Low–medium |
| 3 | Shielding per 1 m | Initial γ: concrete or stone 0.002–0.004, earth 0.02. Neutrons: concrete 5e-4, earth 7e-3. Fallout: earth 5e-4. Structure floor from Table 8.72 | G&D Table 8.41, §8.68, Tables 8.72 and 9.120; Kearny NWSS Fig. 1.3 | High (table) / medium (per block) |
| 4 | Lethality | LD50 4.14 Gy free-in-air, lognormal GSD 1.381 (LD10 2.74, LD90 6.26 Gy); ×1.5 with medical care | PNNL-14424 Table 10 (Anno et al. 2003) | High |
| 5 | Hiroshima fire | 11.4 km²; mass fire at about 20 min; peak wind 13–18 m/s at 2–3 h; calmer at about 6 h; burns into the night. Black rain yes, about 45 min to about 7 h, north-west | USSBS 1946 ch. II; G&D §7.71; Sutou 2020 (Uda) | High / medium |
| 6 | Thermal transmittance | τ = exp(−(D/V)^0.544) for D ≤ V/2; ×1.5 for cloud above or snow | G64 Fig. 7.104 (my fit); G&D §§7.13, 7.100 | High (clear air) |
| 6 | Visibility in rain | Moderate rain about 5 km (4–8 km); thunderstorm rain shaft about 1 km | ITU-R P.1814 Table 2 + 3.91/V (derived); AMS rain classes | Medium / low |
| 7 | Far sound | dB = 20 log10(p/20 µPa) (1 psi = 170.7 dB); D+ about 0.75–1.15 s at 1–10 km (15 kt); single sharp shock nearby, ducted multiple arrivals far away | Definition; Brode87; Reed via McFadden 2021; G&D §§3.05, 3.40–3.43 | High / medium |
| 8 | Ground shock | Main motion arrives with the blast (airslap). Weak precursor 0.1–3 s earlier at 1–2 km on firm ground. About 1–9 cm/s | G&D §§6.12–6.16, 6.94; magnitudes derived | Medium / low |
| 9 | Cap optics and NO2 | Reddish-brown at first; white outside once condensation starts, brown inside (NO2) for minutes or more; cap opaque | G&D §2.08; Taylor & Minge 1963; τ estimate unconfirmed | Low |

**Scripts** (scratchpad, not in the repo): the folder `/tmp/claude-0/-home-user/41ac67c9-9c61-5238-b373-29981f79ba64/scratchpad/scripts/` holds `brode_variants.py` (Eq. 62 and its variants), `brode_dplus.py` (Brode PT arrival time and D+), `brode_q.py` (Eq. 64, not recommended), `ranges.py` and `profile.py` (run glasstone), and `tau_fit.py` (transmittance fit).
