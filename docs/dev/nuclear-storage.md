# Nuclear preparation storage

Preparation must preserve the existing pressure, collapse and material results. Storage and
solver optimizations are accepted only against the same input snapshots and complete plans,
including original states, light, fluid ticks, fire, biome and face-scorch data.

The 512 MiB admission budget is a resident working-window budget. Durable prepared data must
not require a whole-zone NBT tree at autosave. Disk work must run outside the server tick;
pending writes remain charged until completion, and reads enter the same admission budget.
Cancelled or superseded writes must not become current again after restart.

Prepared data is not authority over later player edits. A restored plan must validate its
source revision before application. Runtime object identities are not durable fingerprints.
Unknown or incompatible stored data requires rebuilding, never unconditional application.

Measure planning, live application/support, material continuation, persistence and LOD
publication separately. A sum of CPU samples is not completed end-to-end preparation time.
Keep fixture measurements and projected whole-zone times distinguishable; CI checks results
and counted work, not wall-clock speed on shared runners.
