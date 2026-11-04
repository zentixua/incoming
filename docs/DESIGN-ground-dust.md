# Continuous ground dust

Ground dust should read as a three-dimensional medium from the ground and from above.
The current flat billboards expose their planes when viewed across or down into the layer.

The rendering change keeps `GroundDust` and `FrontDust` as the sources of birth time,
position, mass, spreading and settling. Extinction continues to come from `DustModel`'s
size bins. Turbulence redistributes density within that material; it must not introduce
an eye-local hole, an independent disappearance clock or a second copy of the dust.

Implementation work starts with conventional strikes, using the existing effects layer,
scene depth and reduced-resolution volume infrastructure. Work per frame must remain
bounded for 100 strikes. Validation includes grass, forest and built surfaces, raised
views, daylight and night, with paired main frames and the scene benchmark.

Orbital Laser by Stink-O (MIT, commit `8ae6409a`) provides the visual reference for a
continuous, noisy absorbing medium. Its scripted density and lifetime are not the physical
model for this renderer. The nuclear head already uses the pall volume; its shaders are
also being changed by MT-B5, so a nuclear follow-up needs separate evidence and coordination.
