# Continuous ground dust

Conventional ground dust uses the existing `SmokePass` density field and `PallPass` ray,
resolve and depth-composite passes. `GroundDust`, `FrontDust` and crushing dust still supply
birth time, position, mass, spreading and settling. Fireballs, rising soot and ejecta keep
their existing renderer. Nuclear density and timing are unchanged; MT owns that follow-up.

`SmokePreparation` snapshots preserve the ground channel. `SmokePuffs.Draw` sends that
channel to `DustGrid` and does not emit its old sprites. If the volume shaders are unavailable,
the normal sprite path remains. The legacy large-cloud grid has priority if it is occupied.

The shared 64³ field fits the visible ground material, with an independent vertical cell
size. A decimetre layer must not become a two-metre cube merely because it covers a field.
At most 512 Gaussian records reach the GPU. Excess records merge in a world-space lattice,
preserving optical mass, centre and second moments. All records share one field and one
pass; additional strikes cannot allocate additional volume textures or ray passes. This is
a bounded approximation: widely separated sources reduce spatial detail in the shared box.

Each source uses two separable turbulent profiles. In the truncated Gaussian's cumulative
mass coordinate u, `1 + 0.92 cos(2π n u + phase)` has a unit integral for integer n. The
analytic primitive integrates each cell. Products of the three profiles, mixed with weights
summing to one, preserve source optical mass at every phase and cell alignment. The phase
is source-local and evolves with model time; neither camera position nor an invented fade
changes the mass. The source's existing size-bin extinction and settling remain authoritative.
This is a presentation model for patchiness, not a fluid simulation.

A small GPU profile pass factors the Gaussian integrals into three 1D rows per source.
One layered draw adds their products into the existing 3D texture; OpenGL 3.2 slice draws
are the fallback. The same density attenuates world light and foreground sprites. The ray
uses Beer–Lambert extinction and a single-scattering approximation for shallow ground dust,
with sun, sky, ground and the existing fireball lighting. The legacy deep-smoke lighting
remains unchanged. No eye-local hole is cut into the field.

Dust alone uses quarter resolution or coarser, capped at 80,000 rays, with existing temporal
reprojection and depth joining. The grid, profile pass and shader programs are warmed on
world entry and after reload. Nuclear frames retain their configured quality and ray budget.

## Validation

Unit tests cover optical mass under turbulent phase and grid shifts, thin-layer height,
512-record overflow at 100 and 400 strikes, moments after merging, and preservation of the
ground channel through cached preparation. Shader checks protect the profile table binding:
Minecraft's `setDefaultUniforms` overwrites reserved `Sampler0..11`, so the profile uses
`DustSources`. A GPU readback during development matched input optical mass within 0.1%;
readback code is absent from the runtime path. Visual and final paired scene-bench evidence
belongs to the PR's separate `look-gate/` branch.

Orbital Laser by Stink-O (MIT, commit `8ae6409a`) inspired the continuous, absorbing medium.
Its scripted density, lifetime and scale are not the physical model here. The implementation
uses Incoming's existing volume infrastructure; no Orbital Laser source or assets are copied.
