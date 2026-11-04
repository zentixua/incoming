# Source-condition proof plans

These are plans, not implemented fixes or executed regressions. Source pin452f1f78a; selected file hashes are in code-provenance.json.

## Clock

StemDust constructs24 source-annulus arrival times from HobBlast. PallField evaluates the supplying column with Detonation/FrontProfile arrival and removes the stem share on that age. MushroomField.foot also mixes the physical start with the game-clock layer. Changing wave speed changes the latter but cannot change the former's arguments/cache identity.

A minimal correction would snapshot actual per-annulus arrivals once, in model seconds (`arrivalTicks(blocks(hypot(r,hob)))/(20*scale)`), share that timing at the foot, and key the bounded table cache by all immutable timing inputs. Preserve rise coefficient, cap, onset delay and mass law. This fixes premature supply; it does not fill the AIR gap.

Regressions must use the real Detonation API: no supply before its local source front plus delay; differing GAME/REAL histories; non-unit scale; cache isolation; AIR and the demonstrated shared GROUND path. Include fractional table-node ages to catch pre-entry interpolation. Compare transfer amounts with the existing quadrature's explicit error, not an unsupported exact conservation claim. Paired final frames, required code tests, independent review and CI remain necessary before adoption.

## Surface

StemDust always uses DustModel.Surface.REFERENCE, regardless of source terrain. Suppression over a genuinely known all-water footprint is causally justified; a water block at the axis does not identify that footprint.

PallGround.hasLand is derived from a nearest-filled map, including guesses. Even knownWet loses completeness: measure averages whichever of four samples are available, and resize remaps by old cell centers. A gate using these values alone can silently classify unknown/coastal land as water.

First preserve coverage separately from water value for the finite inflow footprint at the chosen model resolution. Then suppress only proven terrestrial supply, leaving supported spray/condensate separate. Specify how later knowledge affects future inflow without deleting already transported matter. Keep mixed and unknown cases distinct; do not introduce a global terrain rewrite.

Proof cases: known all-water gives zero soil/debris; known land retains supply; a water center with land in the annulus is not all-water; partial/unknown/nearest-filled/remapped inputs do not certify coverage; water and cap components remain. No implementation or validated frame evidence exists for this proposal yet.
