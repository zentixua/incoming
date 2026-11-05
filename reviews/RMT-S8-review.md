# RMT-S8 independent source review

Reviewed: `56f735f2b6ba7c5c30fec8d148359c75c0373372..fa2dc8a5cdd9a93a003580ac65fbb0110d3a4c0a`.
Checkout: clean detached `[detached first review checkout]` at the exact head above.
Reviewer: fresh Codex / Sol, xhigh. Implementation remains MT-S8; integration, runtime and delivery remain RT8.

## Verdict

**One blocking ownership finding, F1 (P2), on a decoder-accepted malformed cargo type.** It is conditional source evidence, not an observed failure of the supplied native ledger. No additional serious finding in the header retirement or FAR source-admission changes. Green bounded controls do not resolve F1 or establish the complete player outcome.

The source blocker was sent immediately to MT-S8-inbox.md and RT8-inbox.md before this closure. A serious corrective delta needs the requested second fresh review; this reviewer has made no production corrections.

## F1 — compact import can undercharge cargo that the decoder normalizes to an empty compound

Changed location: `mod/src/main/java/ua/zentix/incoming/nuclear/world/LedgerScan.java:89-98`, with cargo measurement at lines 215-216.
Decoder/owner: `RuinMatter.java:750-756`, `299-304`, `695-703`, `795-798`; admission/publication: `LedgerLoads.java:92-110`, `74-80`.

A moving row with a valid 145-byte `motion` array and `outside=false` receives the compact estimate. If `contents` is a ByteTag, the scanner counts nine bytes of cargo and charges `157 + 1 + 64 + 3*9 = 249` bytes, apart from its bounded shared-palette allowance. The decoder tests untyped `contains("contents")`, then calls `getCompound("contents")`. Minecraft returns a new empty CompoundTag for this wrong type. The piece therefore retains a non-null cargo of 48 bytes, and FIFO encoding installs an inventory index. The counted resident representation needs at least `157 + 64 + 3*48 = 365` bytes per row, before page overhead.

This follows the actual local Minecraft CompoundTag/ByteTag sources, not an assumed coercion rule. The source was inspected from the local NeoForm sourcesWithNeoForge archive. Empty CompoundTag.sizeInBytes() is 48; ByteTag.sizeInBytes() is 9; getCompound returns a new empty compound for a non-compound value.

For example, about 1,500,000 equal-state packed rows, with otherwise valid released/pending and volume/mass balances, fit the new scan estimate at roughly 385 MB even with the supplied native registry's full 115,814-state palette allowance. Their resident row/cargo/index charge alone is about 547.5 MB, exceeding the unchanged 536,870,912-byte cap. The base scanner's conservative per-row allowance would refuse this large input. LedgerReader accepts these rows; normal cold admission reserves the smaller measured amount, and publication has no final retained-size rejection. The overrun can already exist during decoding, so merely rejecting publication afterward is insufficient.

The current writer emits compound cargo; the supplied native ledger does not demonstrate this malformed shape. The defect is a gap in the accepted cold-input contract introduced by reducing its allowance, not evidence of native save corruption.

Discriminator/correction: use valid packed rows with scalar `contents` and valid material balances. Require rejection before owner growth (preserving the source file), or make preflight and decoder normalize/charge the same representation. Include a small type discriminator and a bounded repeated-row ownership check. Normal absent cargo, empty/nonempty compound cargo, legacy rows and packed outside reservoirs must retain their intended behavior. No reproducer or decoder was executed by the reviewer.

## Source paths checked

- Header producer/consumer: layout construction, LayoutBuild actual-exit cancellation and transfer, installation, scan replies, answer(), tile unknown/partial/state masks, metadataBytes(), diagnostic counts, handoff/clear. Every production reverse list is an ObjectArrayList in a LayoutMap. A reply nulls only its list value; it does not remove keys or shrink the still-owned backing. Final retirement waits for all requested replies, replaces both exhausted structures, and preserves tile masks and completed count. No independent retained Layout future remains after installation.
- FAR source ownership: sourceMemory uses resident cross-dimension ownership, the maximum world height, one real startup need, the 64-MiB transition margin, and the existing 32-MiB encoding margin. Ordinary and archived sources reserve their respective inbox slots. Checked downstream source/publication transfer, solver result/fragment reservation, worker birth/height service, cancellation until actual exit, assembly/Task ownership and consumer collection. The narrower source guard does not remove those actual-owner guards. It is not a guarantee that every arbitrary complete workload fits.
- Cold import: LedgerLoads placeholder/scan/reserve/decode/publish sequence, LedgerReader streaming rows/final validation, packed/legacy readPiece, RuinRecords full pages/tail slack, shared RuinPalette, cargo inventory, outside-to-reservoir conversion and failure/source preservation. The final one-byte-per-row page allowance bounds the existing 96-byte page term; the registry/row-count palette bound and actual cargo-index charge cover ordinary writer-produced packed rows. Outside classification uses the same final motion byte as the decoder; malformed motion length is rejected in preflight.
- Consumer validity: prepared/restored/parked ownership, memory refusal versus stale retry, refreshed snapshot serials and retained flash normalization. No blanket hash/state exemption or synchronous fallback was introduced by this diff.
- All changed GameTests and the rule-file diff were reviewed. No cosmetic suggestions are blockers.

## Raw evidence inspected

All following paths are under `[local private control directory]/`. Helper sources, init overlays and binding scripts were read, not run. The saved `before/NuclearPrep.java` and `scan-before/LedgerScan.java` hash exactly to the specified base files.

| Raw control | Observation and limit |
|---|---|
| `header-red.log`, `header-green.log` | Old reply handling retains 48,705,508 charged bytes and fails the release assertion. New handling reaches 14,966,796 with the last reply held, then 3,697,436; 45,008,072 bytes released, all 10,920 independently checked tile masks retained. This is the real 1-Mt layout, not a full detonation. |
| `packed-red.log`, final `build-touched.log` | Old 100,000-row compact import refuses the 32-MiB room. Final public test admits 22,593,480 bytes versus 16,085,280 live, preserving cargo, trajectories and balances. Legacy/packed/outside and malformed-motion controls pass. No wrong-type cargo control covers F1. |
| `transition-before.log` | Old compact estimate leaves the real ledger cold after the fixture's 45-second wait. |
| `native-measure-transition.log`, `transition-old-header.log` | Earlier scanner controls; not substituted for the final scanner. |
| `final-native-green.log`, `final-old-header.log`, `final-native-wrapper.log` | Final scan estimate 423,967,983 bytes versus old 703,804,891; actual restored owner 342,756,623. With fixed competing owner 115,403,309 and completed header retirement, both native consumers commit in 27 controlled ticks, releasing 34,617 records; sampled resident peak 508,146,876. Old-header overlay imports the same ledger but leaves both consumers pending through the 120-tick bound. Wrapper exits 0. |
| `future-red2.log`, `future-green.log` | Identical initial resident 319,578,716 / speculative 506,126,940 / fixed 293,601,280, ten configured workers and one funded workspace. Old source guard fails the 120-tick bound. New path completes in 30 ticks and actually applies 2,511 material records. |
| `native-freshness.log` | Four immediate native source/solver/current comparisons pass. This excludes intervening world mutation and does not recreate the original live flash map. Fixture block-entity warnings are present; this is not a full-pack runtime acceptance log. |
| `native-retry.log`, `native-retry2.log` | First run has one passing flash case and one failed queue-rejection assertion; it is not a green pair. Final pair passes plain and retained-flash one-cell-change then stable retry, changes snapshot serial, and commits material after one stale rejection. These are controlled non-ticking sources. |
| `build-touched.log` | Completed build success, then all 31 required touched GameTests pass; wrapper exits 0. The log was read after completion, not treated as green while partial. |

Private provenance was read from RT8's `rt8-native-corpus/manifest.json` and `real-material-inputs/verified-provenance.json`. It identifies production base 56f735f2b, diagnostic a0babee626ad07cacc1d7d41dbe71cccfeadcc7b, the clean-stop native material input (135,695,543 bytes; SHA-256 37f82d21e8bc2d5516ca78877e138a87416693a180e98d683891639c3e4fbd83), and the native chunk corpus. No entire material file was read or decoded, no provider palette was replaced, and no private input was published.

The native transition is a bounded composed discriminator: two native consumers, reconstructed exact source states, a real cold import, controlled clock advancement and a fixed competing scalar owner. Its peak is sampled at fixture boundaries, not continuous JVM heap. It is not the complete original live pipeline or all 453 watched chunks, and it does not prove 1-Mt/100-kt traversal, save/rejoin, LOD convergence, player-visible delay or delivery readiness. No remote CI result was independently inspected in this source-only review; CI collection remains with its owner.

## Closure and resources

Review-only scope preserved: no production edit, build, JVM, heavy/GPU job, server/remote/helper/UI operation, PR body, MT-S8 author conversation/checkpoint or RT8-inbox read. Only the requested report, inbox/status/ownership closure and shared memory were written.

**RMT-S8 returns the review slot on completion. Owned resources = 0:** JVM/build/heavy/GPU/server/remote/helper/CI/review waiters = 0. No unfinished reviewer operation remains. MT-S8 owns F1 correction and validation; RT8 retains integration/runtime/delivery. Do not treat this report as implementation ownership transfer or merge/runtime approval.
