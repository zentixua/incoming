# First natural-path 100 kt trial — failed far-preparation gate

Test-only source `820257c4a7e02a5df0cc0aa91387c69fa7a1010c`, jar SHA256 `fe5879a67f88d7783313dd412da73965f6b84fb1c5edd9e91d7b5a8c5d14dcc8`. Production composition `8d251a2c1`; protocol55. No release candidate or 1 Mt acceptance.

Project Zearth dense east city (3104.5,63,560.5), fresh checked domain100 input. Camera (3136.5,106,-448.5), yaw1.82/pitch2.5; Complementary Unbound5.9.3 ON, 2560x1600, render12/simulation8. Server8GiB/12 processors; minimum host available18.589GiB. No helper, forced GC, native/hash/PNG capture during timed windows. Passive stand and bounded server telemetry enabled.

Normal command preparation to accepted launch18.546s; scheduled flight89.994s by exact server log timestamps (90s nominal). All near675 plans became available; far preparation remained112/58486 throughout flight and eight-minute post-detonation window, still112 at clean shutdown. MT-S2 owns the newly identified disk-read frontier liveness cause.

| Phase | Tick work p50 / p99 / max, ms | Frame interval p50 / p99 / max, ms | Frames >100 ms |
|---|---|---|---:|
| baseline | 7.545 / 10.565 / 40.112 | 17.669 / 25.706 / 33.226 | 0 |
| prepare | 16.179 / 37.589 / 113.217 | 18.015 / 24.872 / 30.460 | 0 |
| flight | 13.726 / 23.782 / 130.547 | 18.530 / 25.742 / 63.817 | 0 |
| flash_30s | 46.697 / 70.721 / 96.178 | 24.828 / 70.324 / 230.281 | 4 |
| wave_30to120s | 48.667 / 66.320 / 80.625 | 27.629 / 47.260 / 74.762 | 0 |
| settle_120to480s | 42.835 / 50.160 / 79.359 | 31.727 / 43.983 / 160.804 | 9 |

Tick work is Pre→Post, excluding between-tick tasks. Each phase sampled exactly20ticks/s except one baseline boundary tick; do not equate every tick-work maximum with measured end-to-end TPS. Heap occupied maximum6,339,304,992bytes includes garbage; not live-set memory.

Sampled resident accounting high550,364,542bytes (524.869MiB); future-worker reservation high622,380,751bytes (593.549MiB). This fails to establish the512MiB global gate; source owner is diagnosing the actual ownership components. These are existing calculations sampled at call sites, not complete heap measurements.

Timing: persistent SSH before clock bracket server-client−7.136±26.765ms; after−4.099±24.945ms. Stand epoch drift within−0.012..0.985ms. Server CSV/progress footers complete, drops0. Stand pairs continuous, GPU ring waits0, all measurement phases covered; SIGTERM of the owned wrapper did not emit observe-stop. Last recorded frame is after the full measurement window; shutdown tail remains unbounded/not analyzed. TCP is all Minecraft traffic on attributed sockets, not Incoming-only.

Post-window image shows haze and distant silhouettes; it is not proof of eventual LSS refresh or exact ruin timing. Preflight frame shows the intended populated near field and dense city. Both frames are outside the clean performance window.

## Clean stopped persistence cut A

Remote immutable world+logs: `~/airstrike-work/lead2/corrected100kt-natural-on/clean-stop-A`. Native save-all flush then normal Crafty stop; no restart acceptance yet. Auditor schema pinned4d89 matches the persisted production format; actual producer source is820 above.

Saved moving pieces425,271; cargo-bearing rows2,626; support positions35,440; original support-state rows305,437. Released944,063, deposited393,168, dispersed125,624. Exact count residual0, mass residual0, volume residual−6.862e−7m³ (within source tolerance). All four saved-file audits pass. One nonempty archive reference remains, scan cursor0; archive contents require separate preservation/hash verification. Crater passes already retired, so this cut does not exercise an active crater cursor.

This is real nonempty saved accounting, not import/restart or independent block/inventory conservation proof. Preserve A and use later clean post-import saves with elapsed game ticks; unchanged pre-save disk hashes alone do not prove restored ownership.

Next: reviewed disk-frontier/memory fix, new matching shaderON/OFF source pair; complete1Mt source, normal full-zone runtime, persisted restart/cargo/support/archive proof, ordinary saved-chunk→LSS delivery, final caps/docs/protocol and required merge checks.
