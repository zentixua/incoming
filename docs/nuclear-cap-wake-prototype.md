# Experimental cap-born wake

`CapWakePrototype` and `CapWakeStudy` live in `src/devtest`; the normal mod jar excludes them. They do not change the
renderer or the production cloud. This experiment asks whether a finite source of cloud material can produce an
upper wake without inventing a completed ground-to-cap column.

## Source and assumptions

[Lecoanet and Jeevanjee (2019), section 4, equation 12 and Figure 6](https://nadirjeevanjee.com/papers/17thermals.pdf)
define net entrainment as `d ln(V)/dz = epsilon - delta`. Their resolved dry thermals leave a weaker detrained wake.
This motivates a deliberately approximate closure `delta = beta * epsilon_net`, not a measurement of a nuclear
cloud. Their neutral, weak-density-contrast, dry calculations do not describe the hot initial fireball, atmospheric
stratification or moisture. Beta values `0.03` and `0.1` are comparison cases; `0` is a mechanism-denial control and
`0.2` is a stress case, not a confidence interval.

The existing `MushroomModel` supplies cap volume, cap bottom and nominal stem radius. Release starts at the existing
roll-up time and stops at the existing stabilization time. These phase boundaries are estimates. Material created
during roll-up, nonuniform mixing, turbulent intermittency and re-entrainment are outside this prototype.

The passive cap inventory starts at one. With `q = V/V_start`, uniform mixing gives:

```
dM_cap / M_cap = -beta * d ln(V)
M_cap = q^(-beta)
M_wake = 1 - M_cap
```

Each bounded release interval receives the difference between its endpoint inventories, including only the
elapsed fraction of the current interval. The source uses no ground-front clock, soil fraction or water map.

A second inventory exposes the moisture uncertainty without silently duplicating cap matter. The existing cap
assumes fixed liquid-water content and droplet radius, hence a reference extinction inventory `Q_ref = sigma * V`.
In units of `sigma * V_start`, only the increase in `q` adds condensate. The same detrainment closure gives:

```
dC_cap/dq = 1 - beta * C_cap/q
C_cap = (q + beta * q^(-beta)) / (1 + beta)
C_wake = q - C_cap
```

Both inventories are finite after stabilization. Adding the resulting wake to an unchanged full-density cap would
violate this budget. This second calculation inherits the existing cap's estimated 0.5 g/m3 liquid-water content
and 10 micrometer effective droplet radius. It predicts neither saturation nor evaporation. Its cylinder optical
depth assumes complete droplet retention; loss of condensate can reduce it all the way to zero. It is a diagnostic
upper estimate under this assumed moisture budget, not a universal physical upper bound or a measured opacity.

## Transport and geometry

For a release at time `s`, the vertical path is

```
z(t,s) = capBottom(s) + chi * (capBottom(t) - capBottom(s))
```

`chi = 0` is the neutral passive-wake limit. `0.25`, `0.5` and `0.75` test progressively greater retained rise;
none is a measured nuclear-wake velocity. This parameter is independent of the production ground-dust factor 0.3.
Release intervals map to consecutive axial intervals, with mass divided by their volume. Their radius uses the
existing nominal stem radius at release, an estimate rather than a fitted wake width. No lower edge is attached to
the ground, and no joining time is prescribed.

The source inherits the historical cap-axis wind displacement. After release, the existing prescribed velocity
`u(z) = wind * z/endTop` is integrated over the parcel's actual height history. Replacing that integral with
`wind * currentHeight * currentTime/endTop` would apply the new height retroactively. Queries are pure and replayable.
The model operates in meters and seconds; the caller must apply the existing detonation scale and event clock.

## Reproduction and decision

`CapWakeStudy <output.csv>` writes the 192-case 5/15 kt, beta, chi and age comparison after compilation. It has no
Minecraft/server/GPU dependency at execution. The tests check finite inventory, source debit, current-interval
causality, no ground birth, no indefinite emission, pure replay, bounded history, wind integration against an
independent numerical integral, and release-bin convergence. They do not validate physical parameter choices.

At 60 s, beta=0.03 releases 9.0% / 8.1% of the passive inventory for 5 / 15 kt. Across chi=0..0.75 the lower wake edge
is 779..1684 m / 1273..2567 m; cap bottoms are 1986 m / 2998 m. The inherited no-evaporation droplet closure already
gives peak cylinder optical depths 5.5..22.0 / 9.6..38.5. Increasing density to obtain a stronger visual is unsupported.
The important uncertainty is retained rise and moisture survival, not whether a causal finite source is possible.

The nearby nuclear evidence remains [Spriggs et al. (2020), Figure 5](https://www.osti.gov/servlets/purl/1771428):
cloud-origin and surface-origin stems in the regime containing Wasp and Doppler. It does not give a timed trajectory
or opacity for these cases. Before production integration, compare a clearly labeled prototype sequence against
the unchanged source with separate cap/wake/dust inventories. Preserve the lower surface source and do not require
ground connection. A timed comparable reference is still needed to claim a measured joining time or thickness;
one attractive frame is insufficient.
