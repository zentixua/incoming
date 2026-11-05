# RAIR8 independent source review

Date: 2026-11-04. Fresh gpt-6.1-sol / xhigh session; review only.

Base: `452f1f78af23779200baf4aef804e8e92d6dca40` (tree `844ffd5ac990fb60f9eaff9ca1751dc607722bc9`). Candidate: `68a48e2f5a0c410a6ce14d9e3c75091f8377121f` (tree `732f9bd00d01f5d3fef675ab4cdb0c927cebaff5`). Detached `/mnt/data/projects/incoming/mod/run/wt-RAIR8`, clean before and after inspection. AIR8 remains source owner; LEAD4 retains integration/runtime/GPU.

## Finding requiring correction

**P2 — failed background computation can escape into the frame through a check/use race.** `mod/src/main/java/ua/zentix/incoming/nuclear/model/StemDust.java:213`, with asynchronous exceptional completion at lines 223–228.

`ready` evaluates `entry.isCompletedExceptionally() ? null : entry.getNow(null)`. A pending future can report false, then the worker can execute `entry.completeExceptionally(e)`, then the frame calls `getNow`. That method throws `CompletionException` for an exceptional result, instead of returning the intended dust-free null. Holding CACHE's monitor does not serialize these two observations with completion: `compute` does not acquire that monitor. `MushroomField.shape` calls `ready` directly in the frame path. This is a failure-isolation defect conditional on a construction failure, not an observed gameplay crash or a claim that normal valid inputs currently fail.

The [Java 21 CompletableFuture contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html#getNow(T)) explicitly allows `getNow` to throw on exceptional completion. Make the nonblocking read failure-safe, for example by catching its completion exception, or by first returning null for a pending entry and inspecting only an already terminal entry. Add a deterministic pending-to-exceptional transition regression; checking an already failed future alone misses the interleaving. A controlled future can complete exceptionally after the status observation but before the result read. The current cache test does not exercise this path.

Source acceptance is withheld for this concrete defect. A serious-fix revision needs the repository's second fresh independent round; this report does not automatically approve a later patch.

## Source assessment outside that finding

- The source clock conversion agrees with `Detonation`: horizontal model metres plus nonnegative HOB become slant distance, multiplied by effects scale into blocks; front ticks are divided by `20 * scale` into model seconds. The supplying PallField nodes already use this same front and time convention. The moving foot now evaluates the actual front at its own wall radius rather than substituting HobBlast arrival.
- `Source` snapshots the 24 annular arrivals once per PallField. Its equality/hash include yield, HOB, surface flag, visibility and all sampled arrivals. These are the values consumed by StemDust construction; the foot uses its own retained Source for arbitrary radii, so sharing a table does not substitute another profile for the foot. FrontProfile has private final arrays and no mutation API. Source/cache objects retain model values, not a world. Replay's Active copy retains PallField and its Source.
- Annular material, surface reference, inflow fraction, delay law, settling, rise fraction and wake rule are unchanged. At integer seconds the original right-endpoint release amounts, held sum and height divisor remain. Additional onset knots prevent the first positive interval from interpolating back before a supplying source exists. Fractional intervals use a new reconstruction of the existing quadrature; this is not measured physical calibration or exact continuous mass integration. Existing height/transport approximations remain outside this narrow correction.
- CACHE admits at most eight futures, including pending/failed entries; room() evicts only terminal entries. Frame calls do not join or construct tables synchronously. Source hashing is precomputed; equality touches at most 24 arrivals; lambda/top lookup is binary search. Construction is still quadratic in the number of time nodes, off the frame. With the current clamped rise-time constants, n is at most 1023; at most 24 onset knots are added. Three retained double arrays then hold at most 3141 elements (~25 kB payload per table), and the temporary n-by-24 release array has ~196 kB double payload plus array overhead. These are derived bounds, not measured heap or frame timings. More than eight distinct active clocks can churn completed entries and leave some calls without a ready table; source bounds do not prove multi-strike throughput or visual continuity. Final runtime measurement remains necessary.
- EN/RU onset paragraphs match the corrected clock and each other. They explicitly retain approximate transport and uncalibrated gap duration; they do not claim a new real-world timing fit. Water-footprint classification and all other unchanged world/material systems were not reviewed here. No speculative extra cloud component is demanded.

## Tests and raw evidence

Read the exact diff, surrounding Source/FrontProfile/Detonation/PallField/Active/MushroomModel/DustModel code, old StemDust/foot tests, and the new unit/native tests. `git diff --check BASE CANDIDATE` passes. No JVM, build, native server, GPU, nested reviewer or PR mutation was launched by this reviewer.

Raw directory: `/mnt/data/projects/incoming/mod/run/wt-AIR8-evidence/checks/`.

`clock_control.py` keeps the new Source identity and interpolation structure but replaces BOTH StemDust's onset and sampled age with HobBlast arrival, and replaces the moving foot's Source arrival with HobBlast. It runs only StemClockTest and StemSourceTest, saves XML, restores both source files in finally, and reruns. It does not revert the whole production diff, change rendering, or inject a physical reference.

- `clock-green.log`: selected JUnit task succeeds; it is not a native GameTest result.
- `wrong-clock-red.log` / `wrong-clock-red-xml`: 5 JUnit tests, 4 failures, 0 errors. Three clock tests and the foot test fail; the value/cache test passes. The first-supply control reports positive lambda 1255583.3482424307 at t=46, although the actual supplying onset is 46.518054848921764.
- `correct-clock-green.log` / `correct-clock-green-xml`: the same 5 tests pass, 0 failures/errors. The red and restored-green XML timestamps are 23:05:11 and 23:05:15–16 UTC.
- The foot mutant's first assertion fails by only ~0.0000304 blocks (95.37312042974412 versus 95.37315081358057). That establishes exact clock arithmetic discrimination at the first loop case, not a practically visible GAME/REAL delay. The source test independently checks the formula across modes/scales; the larger-yield first-supply control is the strong causal discriminator.
- The native `nuke_stem_clock` fixture encodes/parses a Detonation at scale 2.5, tests GAME/REAL table discrimination for AIR/GROUND and positive AIR supply around actual arrival plus inflow delay. It does not register a real strike or exercise client rendering. No execution log for that native test was included in the supplied raw directory, so its runtime pass is not certified here.
- The integer-node oracle restates the existing discrete integration independently of the new Source class; it checks preservation of the old algorithm under the right clock, not physical acceptance. Cache tests establish value identity, reuse and completed-entry size/eviction behavior; they do not deterministically hold eight pending workers, test failure transitions, or prove scheduling fairness.

Raw hashes (SHA-256):

```
clock_control.py          987f88a092c1907b4ba73cdbf8309be6245ee669157272d43be44382f4837343
wrong-clock-red.log       1d0b38eb03ad96fecb321002c076eab34698beb450fe929cc6528f615e4db72d
correct-clock-green.log   26c44eb41695247597d0d10b62bf4447222e73959de0f1ee3836f0beb6829814
```

## Remaining gates and handback

Paired native frames and final 100-scene measurements were not supplied for this source phase. **No visual or performance acceptance.** Author full build, native execution and final CI are separately owned and were not certified from process activity. No external physical reference was recalibrated or independently accepted here; unchanged citations remain references rather than a timing oracle.

Report and exact-pin finding returned to AIR8-inbox and LEAD4-inbox. This source-review session ends with no owned jobs, clients, servers, GPU lease, CI waiters or source edits. It may later resume only for added raw frames/final exact diff as assigned; serious source fixes require the second independent round.
